package com.nixplorer.screen.main.tab.home.data

import com.nixplorer.App.Companion.globalClass
import com.nixplorer.R
import kotlinx.serialization.Serializable

object HomeSectionIds {
    const val RECENT_FILES = "recent_files"
    const val CATEGORIES = "categories"
    const val STORAGE = "storage"
    const val BOOKMARKS = "bookmarks"
    const val PINNED_FILES = "pinned_files"
    const val RECYCLE_BIN = "recycle_bin"
    const val JUMP_TO_PATH = "jump_to_path"
    const val ANALYZE_STORAGE = "analyze_storage"
    const val RECENTS = "recents"
}

fun getDefaultHomeLayout(minimalLayout: Boolean = false) = HomeLayout(
    listOf(
        HomeSectionConfig(
            id = HomeSectionIds.RECENT_FILES,
            type = HomeSectionType.RECENT_FILES,
            title = globalClass.getString(R.string.recent_files),
            isEnabled = !minimalLayout,
            order = 0
        ),
        HomeSectionConfig(
            id = HomeSectionIds.CATEGORIES,
            type = HomeSectionType.CATEGORIES,
            title = globalClass.getString(R.string.categories),
            isEnabled = !minimalLayout,
            order = 1
        ),
        HomeSectionConfig(
            id = HomeSectionIds.STORAGE,
            type = HomeSectionType.STORAGE,
            title = globalClass.getString(R.string.storage),
            isEnabled = true,
            order = 2
        ),
        HomeSectionConfig(
            id = HomeSectionIds.BOOKMARKS,
            type = HomeSectionType.BOOKMARKS,
            title = globalClass.getString(R.string.bookmarks),
            isEnabled = !minimalLayout,
            order = 3
        ),
        HomeSectionConfig(
            id = HomeSectionIds.PINNED_FILES,
            type = HomeSectionType.PINNED_FILES,
            title = globalClass.getString(R.string.pinned_files),
            isEnabled = !minimalLayout,
            order = 4
        ),
        HomeSectionConfig(
            id = HomeSectionIds.RECYCLE_BIN,
            type = HomeSectionType.RECYCLE_BIN,
            title = globalClass.getString(R.string.recycle_bin),
            isEnabled = !minimalLayout,
            order = 5
        ),
        HomeSectionConfig(
            id = HomeSectionIds.JUMP_TO_PATH,
            type = HomeSectionType.JUMP_TO_PATH,
            title = globalClass.getString(R.string.jump_to_path),
            isEnabled = !minimalLayout,
            order = 6
        ),
        HomeSectionConfig(
            id = HomeSectionIds.ANALYZE_STORAGE, type = HomeSectionType.ANALYZE_STORAGE,
            title = globalClass.getString(R.string.analyze_storage), isEnabled = true, order = 7
        ),
        HomeSectionConfig(
            id = HomeSectionIds.RECENTS, type = HomeSectionType.RECENTS,
            title = globalClass.getString(R.string.recent_files), isEnabled = true, order = 8
        )
    )
)

@Serializable
data class HomeLayout(
    private val sections: List<HomeSectionConfig>
) {
    // Adds missing sections for backward compatibility with older saved layouts
    fun getSections(): List<HomeSectionConfig> {
        val additions = mutableListOf<HomeSectionConfig>()
        if (sections.find { it.id == HomeSectionIds.PINNED_FILES } == null) {
            additions.add(
                HomeSectionConfig(
                    id = HomeSectionIds.PINNED_FILES,
                    type = HomeSectionType.PINNED_FILES,
                    title = globalClass.getString(R.string.pinned_files),
                    isEnabled = true,
                    order = sections.maxOfOrNull { it.order }?.plus(1) ?: 0
                )
            )
        }
        listOf(
            Triple(HomeSectionIds.ANALYZE_STORAGE, HomeSectionType.ANALYZE_STORAGE, R.string.analyze_storage),
            Triple(HomeSectionIds.RECENTS, HomeSectionType.RECENTS, R.string.recent_files)
        ).forEach { (id, type, title) ->
            if (sections.none { it.id == id }) additions.add(HomeSectionConfig(id, type, globalClass.getString(title), true,
                (sections.maxOfOrNull { it.order } ?: -1) + additions.size + 1))
        }
        return sections + additions
    }
}

@Serializable
data class HomeSectionConfig(
    val id: String,
    val type: HomeSectionType,
    val title: String,
    val isEnabled: Boolean,
    var order: Int
)

@Serializable
enum class HomeSectionType {
    RECENT_FILES,
    CATEGORIES,
    STORAGE,
    BOOKMARKS,
    RECYCLE_BIN,
    JUMP_TO_PATH,
    PINNED_FILES,
    ANALYZE_STORAGE,
    RECENTS
}
