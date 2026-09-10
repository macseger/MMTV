package com.example.mmtv.ui

import com.example.mmtv.model.GroupedMedia

/** Keep usable local data when a refresh fails, and retain items when metadata changes. */
internal fun mergeStartupCategories(
    current: List<GroupedMedia>,
    refreshed: List<GroupedMedia>
): List<GroupedMedia> {
    if (refreshed.isEmpty()) return current
    val currentById = current.associateBy { it.categoryId }
    return refreshed.map { category ->
        category.copy(items = currentById[category.categoryId]?.items ?: category.items)
    }
}
