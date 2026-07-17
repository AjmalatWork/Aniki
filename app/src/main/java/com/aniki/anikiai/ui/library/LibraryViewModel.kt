package com.aniki.anikiai.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.util.isOnline
import com.aniki.anikiai.util.observeOnline
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

enum class SortMode { DATE_SAVED, LAST_VIEWED, ALPHABETICAL }

/**
 * All derived StateFlows below use [SharingStarted.Eagerly], not the more common
 * `WhileSubscribed(5_000)`: this ViewModel survives Library<->Feed tab switches (Navigation-
 * Compose's saveState/restoreState keeps the same instance alive), so a switch away for more than
 * 5 seconds would otherwise let `WhileSubscribed` tear the upstream chain down, then pay a real,
 * user-visible cold-restart cost on return -- `searchResults`' 250ms debounce plus a fresh Room
 * query, landing well after the bottom nav's own selected-tab highlight has already switched.
 * Eagerly starts each chain once, at ViewModel construction, and keeps it running for the
 * ViewModel's whole lifetime, so content is always already warm by the time a screen re-subscribes.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repository: ItemRepository,
    private val appContext: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedTagIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTagIds: StateFlow<Set<String>> = _selectedTagIds

    private val _selectedType = MutableStateFlow<String?>(null)
    val selectedType: StateFlow<String?> = _selectedType

    /** Slice 2, item 5: the "Starred" chip. Orthogonal to type in the data model, but presented as
     *  one more single-select chip alongside All/Links/Videos/Notes, so selecting it clears the
     *  type filter and vice-versa (see [setTypeFilter]/[setStarredOnly]). */
    private val _starredOnly = MutableStateFlow(false)
    val starredOnly: StateFlow<Boolean> = _starredOnly

    private val _sortMode = MutableStateFlow(SortMode.DATE_SAVED)
    val sortMode: StateFlow<SortMode> = _sortMode

    val availableTags: StateFlow<List<TagEntity>> = repository.observeActiveTags()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Drives the offline-queued row state (a PENDING item with no connectivity) -- see
     *  LibraryScreen's ItemRow. Seeded with a synchronous read so the first frame is already
     *  correct instead of a flash of the normal "processing" state before the Flow's first emission. */
    val isOnline: StateFlow<Boolean> = observeOnline(appContext)
        .stateIn(viewModelScope, SharingStarted.Eagerly, isOnline(appContext))

    // The FTS text search runs in SQL (repository.searchItems); tag/type filtering and sorting
    // are cheap enough at personal-library scale to do in-memory once the search step narrows it.
    private val searchResults = _searchQuery
        .debounce(250)
        .flatMapLatest { query -> repository.searchItems(query) }

    val items: StateFlow<List<ItemWithTags>> = combine(
        searchResults, _selectedTagIds, _selectedType, _starredOnly, _sortMode
    ) { list, tagIds, type, starredOnly, sort ->
        list
            .filter { tagIds.isEmpty() || it.tags.any { tag -> tag.id in tagIds } }
            .filter { type == null || it.item.type == type }
            .filter { !starredOnly || it.item.isStarred }
            .let { filtered -> sortItems(filtered, sort) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True if search/tag/type/starred filtering is narrowing the list — distinguishes "no results" from "nothing saved yet". */
    val hasActiveFilter: StateFlow<Boolean> = combine(
        _searchQuery, _selectedTagIds, _selectedType, _starredOnly
    ) { query, tagIds, type, starredOnly ->
        query.isNotBlank() || tagIds.isNotEmpty() || type != null || starredOnly
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun sortItems(list: List<ItemWithTags>, sort: SortMode): List<ItemWithTags> = when (sort) {
        SortMode.DATE_SAVED -> list.sortedByDescending { it.item.createdAt }
        SortMode.LAST_VIEWED -> list.sortedByDescending { it.item.lastViewedAt ?: 0L }
        SortMode.ALPHABETICAL -> list.sortedBy { it.item.title.lowercase() }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleTag(tagId: String) {
        _selectedTagIds.value = _selectedTagIds.value.let { current ->
            if (tagId in current) current - tagId else current + tagId
        }
    }

    fun setTypeFilter(type: String?) {
        _selectedType.value = type
        if (type != null) _starredOnly.value = false // type and Starred are one mutually-exclusive chip row
    }

    fun setStarredOnly(starred: Boolean) {
        _starredOnly.value = starred
        if (starred) _selectedType.value = null // selecting Starred clears any type filter
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun retry(itemId: String) {
        EnrichmentScheduler.enqueue(appContext, itemId)
    }
}
