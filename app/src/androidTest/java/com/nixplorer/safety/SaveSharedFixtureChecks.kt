package com.nixplorer.safety

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import androidx.core.content.FileProvider
import com.nixplorer.screen.share.SaveSharedFilesModel
import java.io.File
import java.util.UUID

internal class SaveSharedFixtureChecks(private val runner: Instrumentation) {
    fun run() {
        val context = runner.targetContext
        val root = File(context.cacheDir, "save-shared-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var status = Activity.RESULT_OK
        var report = ""
        try {
            val source = root.resolve("source").apply { mkdir() }
            val destination = root.resolve("destination").apply { mkdir() }
            val first = source.resolve("test.txt").apply { writeText("incoming first") }
            val second = source.resolve("second.bin").apply { writeBytes(ByteArray(65537) { (it % 251).toByte() }) }
            destination.resolve("test.txt").writeText("existing")
            val uris = listOf(first, second).map { FileProvider.getUriForFile(context, context.packageName + ".provider", it) }
            lateinit var model: SaveSharedFilesModel
            runner.runOnMainSync {
                model = SaveSharedFilesModel(context.applicationContext as Application)
                model.sources = uris
                model.save(destination)
            }
            fun awaitDone() {
                val until = System.currentTimeMillis() + 30000
                while (true) {
                    var busy = true
                    runner.runOnMainSync { busy = model.busy }
                    if (!busy) break
                    check(System.currentTimeMillis() < until) { "Save timed out" }
                    Thread.sleep(20)
                }
            }
            awaitDone()
            check(model.error == null) { model.error.orEmpty() }
            check(model.completed == 2)
            check(destination.resolve("test.txt").readText() == "existing")
            check(destination.resolve("test (1).txt").readText() == "incoming first")
            check(destination.resolve("second.bin").readBytes().contentEquals(second.readBytes()))
            check(first.exists() && second.exists())
            runner.runOnMainSync { model.save(destination) }
            awaitDone()
            check(destination.listFiles()!!.size == 3) { "Retry repeated successful copies" }
            lateinit var failed: SaveSharedFilesModel
            first.delete()
            runner.runOnMainSync {
                failed = SaveSharedFilesModel(context.applicationContext as Application)
                failed.sources = listOf(uris.first())
                failed.save(destination)
            }
            val until = System.currentTimeMillis() + 30000
            while (true) {
                var busy = true
                runner.runOnMainSync { busy = failed.busy }
                if (!busy) break
                check(System.currentTimeMillis() < until)
                Thread.sleep(20)
            }
            check(failed.completed == 0 && failed.error != null)
            check(destination.listFiles()!!.none { it.name.startsWith(".nix-stage-") })
            report = "PASS: multiple URI streams, exact bytes, collision preservation, originals retained, successful items not repeated, unreadable source failure and staging cleanup"
        } catch (e: Throwable) {
            status = Activity.RESULT_CANCELED
            report = e.stackTraceToString()
        } finally {
            root.deleteRecursively()
        }
        runner.finish(status, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, report) })
    }
}
