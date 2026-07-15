package com.aniki.anikiai.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.db.TagEntity
import com.aniki.anikiai.data.repository.ItemRepository
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

    private val _sortMode = MutableStateFlow(SortMode.DATE_SAVED)
    val sortMode: StateFlow<SortMode> = _sortMode

    val availableTags: StateFlow<List<TagEntity>> = repository.observeActiveTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The FTS text search runs in SQL (repository.searchItems); tag/type filtering and sorting
    // are cheap enough at personal-library scale to do in-memory once the search step narrows it.
    private val searchResults = _searchQuery
        .debounce(250)
        .flatMapLatest { query -> repository.searchItems(query) }

    val items: StateFlow<List<ItemWithTags>> = combine(
        searchResults, _selectedTagIds, _selectedType, _sortMode
    ) { list, tagIds, type, sort ->
        list
            .filter { tagIds.isEmpty() || it.tags.any { tag -> tag.id in tagIds } }
            .filter { type == null || it.item.type == type }
            .let { filtered -> sortItems(filtered, sort) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True if search/tag/type filtering is narrowing the list — distinguishes "no results" from "nothing saved yet". */
    val hasActiveFilter: StateFlow<Boolean> = combine(
        _searchQuery, _selectedTagIds, _selectedType
    ) { query, tagIds, type ->
        query.isNotBlank() || tagIds.isNotEmpty() || type != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
    }

    fun retry(itemId: String) {
        EnrichmentScheduler.enqueue(appContext, itemId)
    }
}
