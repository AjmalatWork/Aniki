package com.aniki.anikiai.ui.note

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.sync.SyncWorker
import com.aniki.anikiai.work.EnrichmentScheduler
import kotlinx.coroutines.launch

class NewNoteViewModel(
    private val repository: ItemRepository,
    private val appContext: Context
) : ViewModel() {

    fun saveNote(title: String, body: String, onSaved: () -> Unit) {
        if (body.isBlank()) return
        viewModelScope.launch {
            val item = repository.createNote(title.takeIf { it.isNotBlank() }, body)
            EnrichmentScheduler.enqueue(appContext, item.id)
            SyncWorker.enqueueOneTime(appContext)
            onSaved()
        }
    }
}
