package com.nixplorer.screen.main.tab.apps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import com.nixplorer.common.emptyString
import com.nixplorer.screen.main.tab.Tab
import com.nixplorer.screen.main.tab.apps.holder.AppHolder
import com.nixplorer.screen.main.tab.apps.provider.getInstalledApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import com.nixplorer.screen.main.tab.apps.provider.AppUsageSnapshot
import com.nixplorer.screen.main.tab.apps.provider.loadAppUsage

class AppsTab : Tab() {
    override val id = globalClass.generateUid()
    override val header = globalClass.getString(R.string.apps_tab_header)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null

    val appsList = mutableStateListOf<AppHolder>()
    val systemApps = ArrayList<AppHolder>()
    val userApps = ArrayList<AppHolder>()

    var selectedChoice by mutableIntStateOf(0)
    var previewAppDialog by mutableStateOf<AppHolder?>(null)
    var isSearchPanelOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf(emptyString)
    var isSearching by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    internal var usage by mutableStateOf<AppUsageSnapshot?>(null)
        private set
    var sortOption by mutableStateOf(SortOption.NAME)

    private val ascendingOrders = mutableStateMapOf<SortOption, Boolean>()

    fun isAscending(option: SortOption) = ascendingOrders[option]
        ?: (option == SortOption.NAME || option == SortOption.LAST_OPENED)

    fun toggleSortOrder(option: SortOption) {
        ascendingOrders[option] = !isAscending(option)
        sortOption = option
        updateAppsList()
    }

    enum class SortOption {
        NAME, SIZE, INSTALL_DATE, UPDATE_DATE, LAST_OPENED
    }

    override suspend fun getSubtitle() = emptyString

    override suspend fun getTitle() = globalClass.getString(R.string.apps_tab_title)

    override fun onTabResumed() {
        super.onTabResumed()
        requestHomeToolbarUpdate()
        fetchInstalledApps()
    }

    override fun onTabStarted() {
        super.onTabStarted()
        requestHomeToolbarUpdate()
    }

    override fun onBackPressed(): Boolean {
        if (isSearchPanelOpen) {
            isSearchPanelOpen = false
            searchQuery = emptyString
            updateAppsList()
            return true
        }
        return false
    }

    override fun onTabRemoved() {
        scope.cancel()
        super.onTabRemoved()
    }

    fun fetchInstalledApps() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            // Keep an already loaded list visible during refresh.
            isLoading = appsList.isEmpty()
            try {
                val apps = getInstalledApps(globalClass)
                systemApps.clear()
                userApps.clear()
                apps.forEach { app ->
                    if (app.isSystemApp) systemApps.add(app) else userApps.add(app)
                }
                updateAppsList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                globalClass.logger.logError(e)
            } finally {
                isLoading = false
            }
            try {
                usage = loadAppUsage(globalClass)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                globalClass.logger.logError(e)
                usage = AppUsageSnapshot(true, false)
            }
            updateAppsList()
        }
    }

    fun updateAppsList() {
        val base = when (selectedChoice) {
            0 -> userApps
            1 -> systemApps
            2 -> userApps + systemApps
            else -> emptyList()
        }
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) base else base.filter {
            it.name.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
        val sorted = sortApps(filtered, sortOption, isAscending(sortOption), usage?.lastOpened.orEmpty())
        appsList.clear()
        appsList.addAll(sorted)
    }

    fun performSearch() {
        updateAppsList()
        isSearching = false
    }
}
