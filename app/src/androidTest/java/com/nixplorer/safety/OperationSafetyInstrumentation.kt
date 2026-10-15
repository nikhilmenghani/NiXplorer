package com.nixplorer.safety

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.screen.storage.hasStorageUsageAccess
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.holder.ZipFileHolder
import com.nixplorer.screen.main.tab.files.task.*
import com.nixplorer.screen.main.tab.files.zip.ZipTree
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs exclusively in disposable app-private fixtures. No personal storage or installed app data is removed. */
class OperationSafetyInstrumentation : Instrumentation() {
    private lateinit var root: File
    private val taskIds = mutableListOf<String>()
    private val results = mutableListOf<String>()
    private var phase: String? = null
    override fun onCreate(arguments: Bundle?) { phase = arguments?.getString("phase"); super.onCreate(arguments); start() }
    override fun onStart() {
        if (phase == "appUsage") { appUsage(); return }
        if (phase == "appsLoading") { appsLoading(); return }
        if (phase == "prepareRecovery") { prepareRecovery(); return }
        if (phase == "verifyRecovery") { verifyRecovery(); return }
        root = File(targetContext.cacheDir, "operation-safety-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            runBlocking {
                test("mixed copy outcomes and retry without repeating success") { mixedCopyAndRetry() }
                test("nested folder move removes only verified empty sources") { folderMove() }
                test("move preserves recreated source after completion") { moveAndRecreatedSource() }
                test("recreated-source race before unlink") { recreatedSourceRace() }
                test("ZIP traversal rejected by browser") { traversal() }
                test("ZIP DeleteTask preserves predictable sibling") { archiveDelete() }
                test("ZIP RenameTask publishes a validated archive") { archiveRename() }
                test("CompressTask stages new archives and refuses existing targets") { compression() }
                test("local DeleteTask completes with journaled entry results") { localDelete() }
                test("ZIP extraction uses staged overwrite") { extraction() }
                test("archive moves retain sources and report partial completion") { archiveMoveRetention() }
                test("symlink escape blocked") { symlink() }
                test("directory symlink swap during extraction is contained") { symlinkSwap() }
                test("partial write failure preserves replacement target") { writeFailure() }
                test("cancelled CopyTask discards incomplete writes") { cancellation() }
                test("task-owned concurrent conflict queue") { conflicts() }
                test("permission denial is not empty folder") { permissionDenial() }
                test("search indicates unreadable folders and result limits") { searchAccessAndLimit() }
                test("large enumeration is incremental and cancellable") { largeEnumeration() }
                test("local recursive deletion stops on cancellation") { deleteCancellation() }
                test("recreated deletion target is retained without overwrite") { deleteRecreation() }
                test("background/foreground service conflicts and decisions") { serviceTransitions() }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\n${results.joinToString("\n")}\nAll ${results.size} Android fixture checks passed\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\n${results.joinToString("\n")}\nFAILED: ${error.stackTraceToString()}\n") })
        } finally {
            taskIds.forEach { globalClass.taskManager.removeTask(it); globalClass.taskManager.journal.remove(it) }
            check(root.canonicalPath.startsWith(targetContext.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
    private suspend fun test(name: String, block: suspend () -> Unit) { block(); results.add("PASS $name") }
    private fun fixture(name: String) = File(root, name).apply { check(mkdirs()) }
    private fun tracked(task: Task): Task { taskIds.add(task.id); return task }
    private suspend fun mixedCopyAndRetry() {
        val source = fixture("copy-source")
        val destination = fixture("copy-dest")
        val good = File(source, "good").apply { writeText("original") }
        val missing = File(source, "missing")
        val task = tracked(CopyTask(listOf(LocalFileHolder(good), LocalFileHolder(missing)), false)) as CopyTask
        task.run(CopyTaskParameters(LocalFileHolder(destination)))
        check(task.getCurrentStatus() == TaskStatus.PARTIAL_FAILURE) { task.progressMonitor.toString() }
        val copied = File(destination, "good").apply { writeText("changed after successful copy") }
        missing.writeText("retry me")
        task.continueTask()
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        check(copied.readText() == "changed after successful copy")
        check(File(destination, "missing").readText() == "retry me")
    }
    private fun appUsage() {
        try {
            runBlocking {
                val snapshot = com.nixplorer.screen.main.tab.apps.provider.loadAppUsage(targetContext)
                check(snapshot.hasAccess == targetContext.hasStorageUsageAccess())
                if (snapshot.hasAccess) {
                    check(snapshot.available)
                    check(snapshot.lastOpened.isNotEmpty()) { "No usage history returned on granted device" }
                    check(snapshot.lastOpened.values.all { it > 0 })
                } else check(snapshot.lastOpened.isEmpty())
                lateinit var tab: com.nixplorer.screen.main.tab.apps.AppsTab
                withContext(Dispatchers.Main) { tab = com.nixplorer.screen.main.tab.apps.AppsTab(); tab.fetchInstalledApps() }
                withTimeout(10000) { while (withContext(Dispatchers.Main) { tab.usage == null }) delay(10) }
                withContext(Dispatchers.Main) {
                    tab.selectedChoice = 2
                    tab.sortOption = com.nixplorer.screen.main.tab.apps.AppsTab.SortOption.LAST_OPENED
                    tab.updateAppsList()
                    val timestamps = tab.appsList.map { tab.usage?.lastOpened?.get(it.packageName) ?: 0L }
                    check(timestamps == timestamps.sorted()) { "Last-opened sort order incorrect" }
                    tab.onTabRemoved()
                }
                val pm = targetContext.packageManager
                val installed = pm.getPackageInfo(targetContext.packageName, 0)
                val apk = requireNotNull(pm.getPackageArchiveInfo(targetContext.applicationInfo.sourceDir, 0))
                check(apk.packageName == installed.packageName && apk.longVersionCode == installed.longVersionCode)
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS usage access=${snapshot.hasAccess}, ${snapshot.lastOpened.size} apps with recorded use; oldest-first sorting and installed APK metadata passed.\n") })
            }
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${error.stackTraceToString()}") })
        }
    }

    private fun appsLoading() {
        try {
            runBlocking {
                val pm = targetContext.packageManager
                // Time the previous per-package metadata query pattern without changing installed apps.
                val baselineStart = android.os.SystemClock.elapsedRealtime()
                val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                apps.forEach { info ->
                    pm.getPackageInfo(info.packageName, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_META_DATA)
                    info.loadLabel(pm)
                    File(info.sourceDir).length()
                }
                val baseline = android.os.SystemClock.elapsedRealtime() - baselineStart
                val coldStart = android.os.SystemClock.elapsedRealtime()
                val cold = com.nixplorer.screen.main.tab.apps.provider.getInstalledApps(targetContext)
                val coldMs = android.os.SystemClock.elapsedRealtime() - coldStart
                val warmStart = android.os.SystemClock.elapsedRealtime()
                val warm = com.nixplorer.screen.main.tab.apps.provider.getInstalledApps(targetContext)
                val warmMs = android.os.SystemClock.elapsedRealtime() - warmStart
                check(cold === warm) { "Repeated opens did not reuse cached data" }
                check(cold.map { it.packageName }.toSet() == apps.map { it.packageName }.toSet()) { "Installed apps missing" }
                check(cold.any { it.packageName == targetContext.packageName })
                lateinit var tab: com.nixplorer.screen.main.tab.apps.AppsTab
                withContext(Dispatchers.Main) { tab = com.nixplorer.screen.main.tab.apps.AppsTab(); tab.fetchInstalledApps() }
                withTimeout(10000) { while (withContext(Dispatchers.Main) { tab.userApps.isEmpty() && tab.systemApps.isEmpty() || tab.isLoading }) delay(10) }
                val count = withContext(Dispatchers.Main) { tab.userApps.size + tab.systemApps.size }
                withContext(Dispatchers.Main) { tab.fetchInstalledApps() }
                delay(100)
                withContext(Dispatchers.Main) {
                    check(tab.userApps.size + tab.systemApps.size == count) { "Refresh duplicated apps" }
                    tab.selectedChoice = 2
                    tab.searchQuery = targetContext.packageName
                    tab.performSearch()
                    val matches = cold.filter { it.name.contains(targetContext.packageName, true) || it.packageName.contains(targetContext.packageName, true) }
                    check(tab.appsList.map { it.packageName }.toSet() == matches.map { it.packageName }.toSet()) { "Search results differ" }
                    tab.sortOption = com.nixplorer.screen.main.tab.apps.AppsTab.SortOption.SIZE
                    tab.updateAppsList()
                    check(tab.appsList.map { it.packageName }.toSet() == matches.map { it.packageName }.toSet()) { "Sorting lost the search filter" }
                    tab.onTabRemoved()
                }
                finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS Apps loading: ${cold.size} apps; previous query pattern ${baseline}ms; new cold ${coldMs}ms; cached ${warmMs}ms. Repeated refresh, search and sorting passed.\n") })
            }
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: ${error.stackTraceToString()}") })
        }
    }

    private suspend fun folderMove() {
        val source = fixture("folder-source")
        val destination = fixture("folder-dest")
        val selected = File(source, "selected").apply { mkdir() }
        val child = File(selected, "child").apply { mkdir() }
        File(child, "file").writeText("folder move")
        File(selected, "empty").mkdir()
        val task = tracked(CopyTask(listOf(LocalFileHolder(selected)), true)) as CopyTask
        task.run(CopyTaskParameters(LocalFileHolder(destination)))
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        check(!selected.exists()); check(File(destination, "selected/child/file").readText() == "folder move")
        check(File(destination, "selected/empty").isDirectory)
        val raced = File(source, "raced").apply { mkdir() }
        val target = File(destination, "raced").apply { mkdir() }
        val identity = SafeFileOperations.directoryIdentity(raced)
        check(runCatching { SafeFileOperations.deleteVerifiedSource(raced, target, identity, beforeQuarantine = {
            File(raced, "new-file").writeText("new content must survive")
        }) }.isFailure)
        check(File(raced, "new-file").readText() == "new content must survive")
    }

    private suspend fun moveAndRecreatedSource() {
        val source = fixture("move-source")
        val destination = fixture("move-dest")
        val file = File(source, "file").apply { writeText("move") }
        val task = tracked(CopyTask(listOf(LocalFileHolder(file)), true)) as CopyTask
        task.run(CopyTaskParameters(LocalFileHolder(destination)))
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        check(!file.exists())
        file.writeText("new source at same path")
        task.continueTask()
        check(file.readText() == "new source at same path")
        check(File(destination, "file").readText() == "move")
    }
    private fun recreatedSourceRace() {
        val directory = fixture("race")
        val source = File(directory, "source").apply { writeText("old") }
        val copied = File(directory, "copied").apply { writeText("old") }
        val identity = SafeFileOperations.identity(source)
        check(identity.key != null) { "Android filesystem has no stable inode key" }
        val retained = File(directory, "old")
        check(runCatching { SafeFileOperations.deleteVerifiedSource(source, copied, identity, beforeQuarantine = {
            Files.move(source.toPath(), retained.toPath()); source.writeText("new unrelated source")
        }) }.isFailure)
        check(source.readText() == "new unrelated source"); check(retained.readText() == "old")
    }
    private fun archive(name: String, paths: List<String>): File = File(fixture(name), "archive.zip").apply {
        ZipOutputStream(outputStream()).use { out -> paths.forEach { path -> out.putNextEntry(ZipEntry(path)); out.write("fixture".toByteArray()); out.closeEntry() } }
    }
    private fun traversal() {
        val file = archive("traversal", listOf("../outside"))
        val tree = ZipTree(LocalFileHolder(file))
        check(runCatching { tree.prepare() }.isFailure)
        check(!tree.isReady); check(tree.getRootNode().children.isEmpty())
    }
    private suspend fun archiveDelete() {
        val file = archive("delete", listOf("keep", "remove"))
        val sibling = File(file.parentFile, "archive_temp.zip").apply { writeText("do not touch") }
        val tree = ZipTree(LocalFileHolder(file)).apply { prepare() }
        val task = tracked(DeleteTask(listOf(ZipFileHolder(tree, tree.findNodeByPath("remove")!!)))) as DeleteTask
        task.run(DeleteTaskParameters())
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        java.util.zip.ZipFile(file).use { check(it.getEntry("remove") == null); check(it.getEntry("keep") != null) }
        check(sibling.readText() == "do not touch")
    }
    private suspend fun extraction() {
        val archive = archive("extract", listOf("folder/file"))
        val destination = fixture("extract-dest")
        File(destination, "folder").mkdir()
        File(destination, "folder/file").writeText("old")
        val tree = ZipTree(LocalFileHolder(archive)).apply { prepare() }
        val task = tracked(CopyTask(listOf(tree.createRootContentHolder()), false)) as CopyTask
        task.commonConflictResolution = TaskContentStatus.REPLACE
        task.run(CopyTaskParameters(LocalFileHolder(destination)))
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        check(File(destination, "folder/file").readText() == "fixture")
    }
    private suspend fun archiveRename() {
        val file = archive("rename", listOf("keep", "old"))
        val tree = ZipTree(LocalFileHolder(file)).apply { prepare() }
        val task = tracked(RenameTask(listOf(ZipFileHolder(tree, tree.findNodeByPath("old")!!)))) as RenameTask
        task.run(RenameTaskParameters("new", "", "", false))
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        java.util.zip.ZipFile(file).use { check(it.getEntry("old") == null); check(it.getEntry("new") != null); check(it.getEntry("keep") != null) }
    }
    private suspend fun archiveMoveRetention() {
        val directory = fixture("archive-move")
        val source = File(directory, "source").apply { writeText("fixture") }
        val archive = File(directory, "archive.zip").apply { ZipOutputStream(outputStream()).use { } }
        val tree = ZipTree(LocalFileHolder(archive)).apply { prepare() }
        val into = tracked(CopyTask(listOf(LocalFileHolder(source)), true)) as CopyTask
        into.run(CopyTaskParameters(tree.createRootContentHolder()))
        check(into.getCurrentStatus() == TaskStatus.PARTIAL_FAILURE) { into.progressMonitor.toString() }
        check(source.readText() == "fixture")
        tree.prepare()
        val destination = fixture("archive-move-out")
        val out = tracked(CopyTask(listOf(ZipFileHolder(tree, tree.findNodeByPath("source")!!)), true)) as CopyTask
        out.run(CopyTaskParameters(LocalFileHolder(destination)))
        check(out.getCurrentStatus() == TaskStatus.PARTIAL_FAILURE) { out.progressMonitor.toString() }
        check(File(destination, "source").readText() == "fixture")
        java.util.zip.ZipFile(archive).use { check(it.getEntry("source") != null) }
    }
    private suspend fun compression() {
        val directory = fixture("compress")
        val source = File(directory, "source").apply { writeText("fixture") }
        val destination = File(directory, "new.zip")
        val task = tracked(CompressTask(listOf(LocalFileHolder(source)))) as CompressTask
        task.run(CompressTaskParameters(destination.path))
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        val before = destination.readBytes()
        val replacement = tracked(CompressTask(listOf(LocalFileHolder(source)))) as CompressTask
        replacement.run(CompressTaskParameters(destination.path))
        check(replacement.getCurrentStatus() == TaskStatus.FAILED)
        check(before.contentEquals(destination.readBytes()))
    }
    private suspend fun localDelete() {
        val directory = fixture("local-delete")
        File(directory, "child").apply { mkdir(); File(this, "file").writeText("fixture") }
        val task = tracked(DeleteTask(listOf(LocalFileHolder(directory)))) as DeleteTask
        task.run(DeleteTaskParameters())
        check(task.getCurrentStatus() == TaskStatus.SUCCESS) { task.progressMonitor.toString() }
        check(!directory.exists())
    }
    private fun symlink() {
        val destination = fixture("link-dest")
        val outside = fixture("link-outside")
        Files.newDirectoryStream(destination.toPath()).use { stream ->
            sendStatus(0, Bundle().apply { putString("stream", "SecureDirectoryStream available: ${stream is java.nio.file.SecureDirectoryStream<*>}\n") })
        }
        Files.createSymbolicLink(File(destination, "link").toPath(), outside.toPath())
        check(runCatching { SafeFileOperations.stagedWrite(destination, "link/escape", true) { it.writeText("bad") } }.isFailure)
        check(!File(outside, "escape").exists())
    }
    private fun symlinkSwap() {
        val destination = fixture("swap-dest")
        val parent = File(destination, "parent").apply { mkdir() }
        val old = File(destination, "retained-parent")
        val outside = fixture("swap-outside")
        var swapped = false
        val input = object : java.io.ByteArrayInputStream("safe bytes".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!swapped) {
                    Files.move(parent.toPath(), old.toPath())
                    Files.createSymbolicLink(parent.toPath(), outside.toPath())
                    swapped = true
                }
                return super.read(buffer, offset, length)
            }
        }
        check(runCatching { SafeFileOperations.stagedStream(destination, "parent/file", false, 100,
            input = { input }) }.isFailure)
        check(outside.listFiles()!!.isEmpty()) { "Extraction escaped via swapped directory" }
        check(old.listFiles()!!.isEmpty()) { "Incomplete stage not cleaned through owned descriptor" }
        Files.delete(parent.toPath())
    }
    private fun writeFailure() {
        val directory = fixture("write-failure")
        val original = File(directory, "original").apply { writeText("original survives ENOSPC") }
        var written = false
        check(runCatching { SafeFileOperations.stagedStream(directory, "original", true, 1024 * 1024,
            writeChannel = { channel, bytes ->
                if (written) throw java.io.IOException("Injected ENOSPC after partial write")
                written = true; channel.write(bytes)
            }, input = { java.io.ByteArrayInputStream(ByteArray(128 * 1024)) }) }.isFailure)
        check(written); check(original.readText() == "original survives ENOSPC")
        check(directory.listFiles()!!.none { it.name.startsWith(".nix-stage-") })
    }
    private suspend fun cancellation() = coroutineScope {
        val source = fixture("cancel-source")
        val destination = fixture("cancel-dest")
        val file = File(source, "big").apply { outputStream().use { output -> val bytes = ByteArray(64 * 1024); repeat(1024) { output.write(bytes) } } }
        val task = tracked(CopyTask(listOf(LocalFileHolder(file)), false)) as CopyTask
        val job = launch(Dispatchers.IO) { task.run(CopyTaskParameters(LocalFileHolder(destination))) }
        withTimeout(15000) { while (task.getCurrentStatus() != TaskStatus.RUNNING) delay(1) }
        task.abortTask(); job.join()
        check(task.getCurrentStatus() == TaskStatus.CANCELLED) { task.progressMonitor.toString() }
        check(file.isFile); check(!File(destination, "big").exists())
    }
    private suspend fun conflicts() {
        val destination = fixture("conflict-dest")
        val source1 = File(fixture("conflict-one"), "same").apply { writeText("one") }
        val source2 = File(fixture("conflict-two"), "same").apply { writeText("two") }
        File(destination, "same").writeText("existing")
        val one = tracked(CopyTask(listOf(LocalFileHolder(source1)), false)) as CopyTask
        val two = tracked(CopyTask(listOf(LocalFileHolder(source2)), false)) as CopyTask
        one.run(CopyTaskParameters(LocalFileHolder(destination)))
        two.run(CopyTaskParameters(LocalFileHolder(destination)))
        val interceptor = globalClass.taskManager.taskInterceptor
        check(one.getCurrentStatus() == TaskStatus.CONFLICT && two.getCurrentStatus() == TaskStatus.CONFLICT)
        check(interceptor.task?.id == one.id)
        interceptor.remove(one.id)
        check(interceptor.task?.id == two.id)
        interceptor.remove(two.id)
    }
    private suspend fun permissionDenial() {
        val directory = fixture("denied")
        android.system.Os.chmod(directory.path, 0)
        try { check(runCatching { LocalFileHolder(directory).listContent() }.isFailure) }
        finally { android.system.Os.chmod(directory.path, 448) }
    }
    private suspend fun searchAccessAndLimit() {
        val directory = fixture("search")
        File(directory, "needle").writeText("fixture")
        val denied = File(directory, "denied").apply { mkdir() }
        val tab = com.nixplorer.screen.main.tab.files.FilesTab(LocalFileHolder(directory))
        val search = com.nixplorer.screen.main.tab.files.search.SearchManager()
        android.system.Os.chmod(denied.path, 0)
        try {
            search.searchQuery = "needle"
            search.startSearch(tab)
            withTimeout(10000) { while (search.isSearching) delay(20) }
            check(search.searchResults.size == 1)
            check(search.incompleteReason?.contains("Permission denied", true) == true) { search.incompleteReason ?: "No incomplete-result warning" }
        } finally { android.system.Os.chmod(denied.path, 448) }
        File(directory, "second").writeText("fixture")
        search.searchQuery = "*"
        search.searchOptions = search.searchOptions.copy(maxResults = 1)
        search.startSearch(tab)
        withTimeout(10000) { while (search.isSearching) delay(20) }
        check(search.searchResults.size == 1); check(search.incompleteReason?.contains("limit") == true)
        tab.scope.cancel()
    }
    private suspend fun largeEnumeration() = coroutineScope {
        val directory = fixture("large-enumeration")
        repeat(2000) { File(directory, "$it").writeText("fixture") }
        var visits = 0
        val job = launch(Dispatchers.IO) {
            LocalFileHolder(directory).forEachChild {
                visits++
                if (visits == 37) throw CancellationException("Fixture cancellation")
            }
        }
        job.join()
        check(visits == 37)
    }
    private fun deleteCancellation() {
        val directory = fixture("delete-cancel")
        repeat(100) { File(directory, "$it").writeText("fixture") }
        var deleted = 0
        check(runCatching { SafeFileOperations.deleteTree(directory, "fixture-cancel", { deleted >= 7 }) { deleted++ } }.exceptionOrNull() is java.util.concurrent.CancellationException)
        check(deleted == 7); check(directory.listFiles()!!.size == 93)
    }
    private fun deleteRecreation() {
        val directory = fixture("delete-recreation")
        val source = File(directory, "source").apply { writeText("original") }
        val previous = File(directory, "previous")
        var retained: File? = null
        check(runCatching { SafeFileOperations.deleteTree(source, "fixture-delete-recreation", onPrepared = { _, path ->
            retained = File(path)
            Files.move(source.toPath(), previous.toPath())
            source.writeText("new source must not be deleted")
        }) }.isFailure)
        check(previous.readText() == "original")
        check(retained!!.readText() == "new source must not be deleted")
    }

    private suspend fun serviceTransitions() {
        targetContext.startActivity(android.content.Intent(targetContext, com.nixplorer.screen.main.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        val destination = fixture("service-dest")
        File(destination, "same").writeText("existing")
        val source1 = File(fixture("service-one"), "same").apply { writeText("one") }
        val source2 = File(fixture("service-two"), "same").apply { writeText("two") }
        val one = tracked(CopyTask(listOf(LocalFileHolder(source1)), false)) as CopyTask
        val two = tracked(CopyTask(listOf(LocalFileHolder(source2)), false)) as CopyTask
        val manager = globalClass.taskManager
        manager.addTaskAndRun(one, CopyTaskParameters(LocalFileHolder(destination)))
        manager.addTaskAndRun(two, CopyTaskParameters(LocalFileHolder(destination)))
        withTimeout(15000) { while (one.getCurrentStatus() != TaskStatus.CONFLICT || two.getCurrentStatus() != TaskStatus.CONFLICT) delay(20) }
        check(uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        delay(300)
        targetContext.startActivity(android.content.Intent(targetContext, com.nixplorer.screen.main.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(300)
        val first = manager.taskInterceptor.task!!
        manager.taskInterceptor.resolve(TaskContentStatus.SKIP)
        withTimeout(15000) { while (first.getCurrentStatus() != TaskStatus.SUCCESS) delay(20) }
        val second = manager.taskInterceptor.task!!
        check(first.id != second.id)
        manager.taskInterceptor.resolve(TaskContentStatus.REPLACE)
        withTimeout(15000) { while (second.getCurrentStatus() != TaskStatus.SUCCESS) delay(20) }
        check(File(destination, "same").readText() in listOf("one", "two"))
    }

    private val marker get() = File(targetContext.filesDir, "safety-interruption-marker.properties")
    private fun prepareRecovery() {
        check(!marker.exists()) { "Previous recovery fixture exists; inspect it before rerunning" }
        val directory = File(targetContext.filesDir, "safety-recovery-${UUID.randomUUID()}").apply { check(mkdir()) }
        val original = File(directory, "original").apply { writeText("original retained") }
        val stage = File(directory, "stage").apply { writeText("replacement retained") }
        val source = File(directory, "source").apply { writeText("do not replay source deletion") }
        val id = "safety-interrupted-${UUID.randomUUID()}"
        OperationJournal(File(targetContext.filesDir, "operation-journal")).save(OperationRecord(id, "QA interrupted move", "move",
            listOf(original.path, source.path), stage.path, "RUNNING", "Disposable process-death fixture",
            listOf(OperationItemRecord(source.path, stage.path, "SUCCESS", null, true, false, SafeFileOperations.identity(source)))))
        try {
            SafeFileOperations.replace(stage, original, move = { from, to, atomic ->
                if (atomic) throw java.nio.file.AtomicMoveNotSupportedException(from.path, to.path, "fixture")
                Files.move(from.toPath(), to.toPath())
                if (from == original) throw AssertionError("Interrupted replacement fixture")
            })
        } catch (_: AssertionError) { }
        marker.outputStream().use { out -> java.util.Properties().apply {
            setProperty("directory", directory.path); setProperty("id", id)
        }.store(out, "Disposable recovery fixture"); out.fd.sync() }
        sendStatus(0, Bundle().apply { putString("stream", "READY_FOR_PROCESS_STOP\n") })
        while (true) Thread.sleep(1000) // The test controller force-stops this process at the persisted interruption.
    }
    private fun verifyRecovery() {
        try {
            val properties = java.util.Properties().apply { marker.inputStream().use { load(it) } }
            val directory = File(properties.getProperty("directory"))
            val id = properties.getProperty("id")
            check(directory.canonicalPath.startsWith(targetContext.filesDir.canonicalPath + "/safety-recovery-"))
            val manager = globalClass.taskManager
            runBlocking { withTimeout(10000) { while (manager.getTask(id) == null) delay(20) } }
            check(manager.getTask(id) is RecoveredTask)
            check(File(directory, "original").readText() == "original retained")
            check(File(directory, "stage").readText() == "replacement retained")
            check(File(directory, "source").readText() == "do not replay source deletion")
            manager.removeTask(id); manager.journal.remove(id)
            directory.deleteRecursively(); marker.delete()
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "PASS actual process stop: original restored, replacement retained, destructive steps not replayed\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED recovery: ${error.stackTraceToString()}") })
        }
    }
}
