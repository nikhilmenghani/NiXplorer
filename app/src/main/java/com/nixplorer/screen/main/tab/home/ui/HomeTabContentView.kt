package com.nixplorer.screen.main.tab.home.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.cheonjaeung.compose.grid.SimpleGridCells
import com.cheonjaeung.compose.grid.VerticalGrid
import com.google.gson.Gson
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.App.Companion.logger
import com.nixplorer.R
import com.nixplorer.common.ui.Space
import com.nixplorer.screen.main.MainActivityManager
import com.nixplorer.screen.main.tab.files.FilesTab
import com.nixplorer.screen.main.tab.files.coil.canUseCoil
import com.nixplorer.screen.main.tab.files.holder.LocalFileHolder
import com.nixplorer.screen.main.tab.files.holder.StorageDevice
import com.nixplorer.screen.main.tab.files.holder.VirtualFileHolder
import com.nixplorer.screen.main.tab.files.holder.VirtualFileHolder.Companion.BOOKMARKS
import com.nixplorer.screen.main.tab.files.holder.VirtualFileHolder.Companion.RECENT
import com.nixplorer.screen.main.tab.files.provider.StorageProvider
import com.nixplorer.screen.main.tab.files.ui.FileContentIcon
import com.nixplorer.screen.main.tab.home.HomeTab
import com.nixplorer.screen.main.tab.home.data.HomeLayout
import com.nixplorer.screen.main.tab.home.data.HomeSectionConfig
import com.nixplorer.screen.main.tab.home.data.HomeSectionType
import com.nixplorer.screen.main.tab.home.data.getDefaultHomeLayout
import com.nixplorer.screen.main.ui.SimpleNewTabViewItem
import com.nixplorer.screen.main.ui.StorageDeviceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.HomeTabContentView(tab: HomeTab) {
    val mainActivityManager = globalClass.mainActivityManager
    val scope = rememberCoroutineScope()
    val enabledSections = remember { mutableStateListOf<HomeSectionConfig>() }
    val showRecentFiles = globalClass.preferencesManager.showRecentFiles

    LaunchedEffect(tab.id) {
        withContext(Dispatchers.IO) {
            async {
                tab.getPinnedFiles()
            }
            async {
                val config = try {
                    Gson().fromJson(
                        globalClass.preferencesManager.homeTabLayout,
                        HomeLayout::class.java
                    )
                } catch (e: Exception) {
                    logger.logError(e)
                    getDefaultHomeLayout()
                }.getSections().filter { it.isEnabled }.sortedBy { it.order }

                enabledSections.addAll(config)
            }
        }
    }

    val recentFilesEnabled = showRecentFiles &&
        enabledSections.any { it.type == HomeSectionType.RECENT_FILES }
    LaunchedEffect(tab.id, recentFilesEnabled) {
        if (recentFilesEnabled) {
            tab.fetchRecentFiles()
        }
    }

    if (tab.showCustomizeHomeTabDialog) {
        HomeLayoutSettingsScreen { sections ->
            tab.showCustomizeHomeTabDialog = false
            var isAllDisabled = false

            // Prevent disabling all sections
            if (sections.all { !it.isEnabled }) {
                isAllDisabled = true
            }

            sections.forEachIndexed { index, config ->
                config.order = index
            }

            enabledSections.apply {
                clear()
                if (isAllDisabled) {
                    addAll(getDefaultHomeLayout(true).getSections().filter { it.isEnabled }
                        .sortedBy { it.order })
                } else {
                    addAll(sections.filter { it.isEnabled }.sortedBy { it.order })
                }

            }

            scope.launch {
                if (isAllDisabled) {
                    globalClass.preferencesManager.homeTabLayout = Gson().toJson(
                        getDefaultHomeLayout(true)
                    )
                } else {
                    globalClass.preferencesManager.homeTabLayout = Gson().toJson(
                        HomeLayout(sections)
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val shortcutTypes = setOf(HomeSectionType.BOOKMARKS, HomeSectionType.RECYCLE_BIN,
            HomeSectionType.ANALYZE_STORAGE, HomeSectionType.RECENTS)
        val visibleSections = enabledSections.filter {
            it.type != HomeSectionType.JUMP_TO_PATH &&
                (showRecentFiles || it.type != HomeSectionType.RECENT_FILES) &&
                (it.type != HomeSectionType.BOOKMARKS || globalClass.preferencesManager.bookmarks.isNotEmpty())
        }
        val shortcuts = visibleSections.filter { it.type in shortcutTypes }
        visibleSections.forEach { section ->
            if (section.type in shortcutTypes) {
                if (section == shortcuts.firstOrNull()) {
                    HomeShortcuts(shortcuts, mainActivityManager)
                }
            } else when (section.type) {
                HomeSectionType.RECENT_FILES -> RecentFilesSection(tab, mainActivityManager)
                HomeSectionType.CATEGORIES -> CategoriesSection(tab)
                HomeSectionType.STORAGE -> StorageSection(mainActivityManager)
                HomeSectionType.PINNED_FILES -> PinnedFilesSection(tab, mainActivityManager)
                else -> Unit
            }
        }
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = tab::openAiSearch).padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(stringResource(R.string.ai_search), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
        }

    }
}

@Composable
fun PinnedFilesSection(
    tab: HomeTab,
    mainActivityManager: MainActivityManager
) {
    val context = LocalContext.current
    val pinnedFiles = remember {
        mutableStateListOf<LocalFileHolder>().apply {
            addAll(tab.pinnedFiles)
        }
    }

    if (pinnedFiles.isNotEmpty()) {
        // Pinned files
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp, bottom = 8.dp),
            text = stringResource(R.string.pinned_files),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                )
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(12.dp)
                )
                .clip(RoundedCornerShape(12.dp))
        ) {
            pinnedFiles.forEachIndexed { index, it ->
                var showDeleteOption by remember(it.uid) { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = {
                                    if (showDeleteOption) {
                                        showDeleteOption = false
                                    } else if (it.isFile()) {
                                        it.open(
                                            context = context,
                                            anonymous = false,
                                            skipSupportedExtensions = !globalClass.preferencesManager.useBuiltInViewer,
                                            customMimeType = null
                                        )
                                    } else {
                                        mainActivityManager.replaceCurrentTabWith(FilesTab(it))
                                    }
                                },
                                onLongClick = {
                                    showDeleteOption = !showDeleteOption
                                }
                            )
                            .padding(12.dp)
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var canUseCoil by remember(it.uid) {
                            mutableStateOf(canUseCoil(it))
                        }
                        if (canUseCoil) {
                            AsyncImage(
                                modifier = Modifier
                                    .size(45.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                model = ImageRequest
                                    .Builder(globalClass)
                                    .data(it)
                                    .build(),
                                filterQuality = FilterQuality.Low,
                                contentScale = ContentScale.Fit,
                                contentDescription = null,
                                onError = { canUseCoil = false }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(45.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                FileContentIcon(it)
                            }
                        }
                        Space(size = 8.dp)
                        Text(text = it.displayName)
                    }
                    AnimatedVisibility(visible = showDeleteOption) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(60.dp)
                                .background(color = MaterialTheme.colorScheme.errorContainer)
                                .clickable {
                                    pinnedFiles.remove(it)
                                    tab.removePinnedFile(it)
                                },
                        ) {
                            Icon(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .align(Alignment.Center),
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                if (index != tab.pinnedFiles.lastIndex) HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun RecentFilesSection(
    tab: HomeTab,
    mainActivityManager: MainActivityManager
) {
    val context = LocalContext.current

    // Recent files
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        text = stringResource(R.string.recent_files),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    if (tab.recentFiles.isEmpty()) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .clickable {
                    mainActivityManager.replaceCurrentTabWith(
                        FilesTab(VirtualFileHolder(RECENT))
                    )
                },
            text = stringResource(R.string.no_recent_files),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    } else {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item { Space(6.dp) }

            items(tab.recentFiles, key = { it.path }) {
                Column(
                    modifier = Modifier
                        .size(110.dp, 140.dp)
                        .padding(horizontal = 6.dp)
                        .background(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = {
                                it.file.open(
                                    context = context,
                                    anonymous = false,
                                    skipSupportedExtensions = !globalClass.preferencesManager.useBuiltInViewer,
                                    customMimeType = null
                                )
                            },
                            onLongClick = {
                                mainActivityManager.replaceCurrentTabWith(
                                    FilesTab(it.file)
                                )
                            }
                        )
                ) {
                    var useCoil by remember(it.file.uid) {
                        mutableStateOf(canUseCoil(it.file))
                    }

                    Box(modifier = Modifier.weight(2f)) {
                        if (useCoil) {
                            AsyncImage(
                                modifier = Modifier.fillMaxSize(),
                                model = ImageRequest
                                    .Builder(globalClass)
                                    .data(it.file)
                                    .build(),
                                filterQuality = FilterQuality.Low,
                                contentScale = ContentScale.Crop,
                                contentDescription = null,
                                onError = { useCoil = false }
                            )
                        } else {
                            FileContentIcon(it.file)
                        }
                    }
                    Box(modifier = Modifier
                        .weight(1.2f)
                        .padding(6.dp)
                    ) {
                        Text(
                            modifier = Modifier
                                .fillMaxSize(),
                            text = it.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = true
                        )
                    }
                }
            }
            item {
                TextButton(
                    onClick = {
                        mainActivityManager.replaceCurrentTabWith(
                            FilesTab(VirtualFileHolder(RECENT))
                        )
                    }
                ) {
                    Text(text = stringResource(R.string.more))
                }
            }

            item { Space(6.dp) }
        }
    }
}

@Composable
private fun CategoriesSection(
    tab: HomeTab
) {
    // Quick access tiles
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        text = stringResource(R.string.categories),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    VerticalGrid(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        columns = SimpleGridCells.Fixed(3)
    ) {
        tab.getMainCategories().forEach {
            Column(
                Modifier
                    .padding(4.dp)
                    .background(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { it.onClick() }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    modifier = Modifier.padding(8.dp),
                    imageVector = it.icon,
                    contentDescription = null
                )
                Text(
                    modifier = Modifier.basicMarquee(),
                    text = it.name,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StorageSection(
    mainActivityManager: MainActivityManager
) {
    val context = LocalContext.current
    val storageList = remember { mutableStateListOf<StorageDevice>() }

    LaunchedEffect(Unit) {
        storageList.addAll(StorageProvider.getStorageDevices(globalClass))
    }

    // Storage options
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp),
        text = stringResource(R.string.storage),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            )
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
    ) {
        storageList.forEachIndexed { index, device ->
            StorageDeviceView(storageDevice = device) {
                mainActivityManager.replaceCurrentTabWith(FilesTab(device.contentHolder))
            }
            if (index != storageList.lastIndex) HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
private fun HomeShortcuts(sections: List<HomeSectionConfig>, manager: MainActivityManager) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shortcuts = sections.toMutableList()
        val bookmarksIndex = shortcuts.indexOfFirst { it.type == HomeSectionType.BOOKMARKS }
        val recycleIndex = shortcuts.indexOfFirst { it.type == HomeSectionType.RECYCLE_BIN }
        if (bookmarksIndex >= 0 && recycleIndex >= 0) {
            val bookmarks = shortcuts[bookmarksIndex]
            shortcuts[bookmarksIndex] = shortcuts[recycleIndex]
            shortcuts[recycleIndex] = bookmarks
        }
        shortcuts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { section ->
                    val icon = when (section.type) {
                        HomeSectionType.BOOKMARKS -> Icons.Rounded.Bookmark
                        HomeSectionType.RECYCLE_BIN -> Icons.Rounded.DeleteSweep
                        HomeSectionType.ANALYZE_STORAGE -> Icons.Rounded.Storage
                        else -> Icons.Rounded.History
                    }
                    Row(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                            .border(0.5.dp, MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                when (section.type) {
                                    HomeSectionType.BOOKMARKS -> manager.replaceCurrentTabWith(FilesTab(VirtualFileHolder(BOOKMARKS)))
                                    HomeSectionType.RECYCLE_BIN -> manager.replaceCurrentTabWith(FilesTab(globalClass.recycleBinDir))
                                    HomeSectionType.RECENTS -> manager.replaceCurrentTabWith(FilesTab(VirtualFileHolder(RECENT)))
                                    HomeSectionType.ANALYZE_STORAGE -> context.startActivity(android.content.Intent(context,
                                        com.nixplorer.screen.storage.StorageAnalyzerActivity::class.java))
                                    else -> Unit
                                }
                            }.padding(horizontal = 12.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                        Text(section.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}
