package com.aniki.anikiai.ui.trash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.db.ItemEntity
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.sync.SyncWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TrashViewModel(
    private val repository: ItemRepository,
    private val appContext: Context
) : ViewModel() {

    val trashedItems: StateFlow<List<ItemEntity>> = repository.observeTrashedItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(itemId: String) {
        viewModelScope.launch {
            repository.restoreItem(itemId)
            SyncWorker.enqueueOneTime(appContext)
        }
    }

    /** See ItemRepository.permanentlyDeleteItem's doc for the offline/unsynced-tombstone caveat
     *  this accepts by design. Enqueueing sync first (rather than after) gives an online device
     *  its best shot at pushing the tombstone before the local row is gone. */
    fun deleteForever(itemId: String) {
        viewModelScope.launch {
            SyncWorker.enqueueOneTime(appContext)
            repository.permanentlyDeleteItem(itemId)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            SyncWorker.enqueueOneTime(appContext)
            repository.emptyTrash()
        }
    }
}
