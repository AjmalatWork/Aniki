package com.aniki.anikiai.ui.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.db.ItemWithTags
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ItemDetailViewModel(
    private val repository: ItemRepository,
    private val appContext: Context,
    private val itemId: String
) : ViewModel() {

    val item: StateFlow<ItemWithTags?> = repository.observeItemWithTags(itemId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Slice 5's future ranking depends on this; nothing consumes it yet.
        viewModelScope.launch { repository.markViewed(itemId) }
    }

    fun saveSummary(summary: String) {
        viewModelScope.launch {
            repository.updateSummary(itemId, summary)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun addTag(label: String) {
        if (label.isBlank()) return
        viewModelScope.launch {
            repository.addUserTag(itemId, label)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun removeTag(tagId: String) {
        viewModelScope.launch {
            repository.removeTag(itemId, tagId)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun toggleStar(starred: Boolean) {
        viewModelScope.launch {
            repository.setStarred(itemId, starred)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteItem(itemId)
            SyncWorker.enqueueOneTime(appContext)
            onDeleted()
        }
    }

    fun retry() {
        EnrichmentScheduler.enqueue(appContext, itemId)
    }
}
