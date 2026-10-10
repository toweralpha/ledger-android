package com.xiaozhangben.ledger.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.data.local.CategoryEntity
import com.xiaozhangben.ledger.data.repository.InsertOutcome
import com.xiaozhangben.ledger.data.repository.NewTransaction
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.model.TxDirection
import com.xiaozhangben.ledger.domain.model.TxSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Saved(val id: String) : SaveState
    data class Failed(val message: String) : SaveState
}

class AddTransactionViewModel(private val container: AppContainer) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = container.ledger.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    fun save(
        amountCents: Long,
        direction: TxDirection,
        categoryKey: String,
        occurredAt: Long,
        note: String?,
        slotId: String? = null,
    ) {
        if (_saveState.value is SaveState.Saving) return
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            val outcome = container.ledger.insert(
                NewTransaction(
                    source = TxSource.MANUAL,
                    direction = direction,
                    occurredAt = occurredAt,
                    amountCents = amountCents,
                    note = note?.trim()?.ifBlank { null },
                ),
                now = System.currentTimeMillis(),
            )
            _saveState.value = when (outcome) {
                is InsertOutcome.Inserted -> {
                    container.ledger.reclassify(
                        id = outcome.id,
                        categoryKey = categoryKey,
                        learn = false,
                    )
                    // Recording from a meal card places the row in that meal immediately,
                    // marked as a user choice so no later rule can move it.
                    if (slotId != null) container.ledger.assignDailySlot(outcome.id, slotId)
                    SaveState.Saved(outcome.id)
                }
                is InsertOutcome.Duplicate -> SaveState.Failed("这笔已经记过了")
                is InsertOutcome.Rejected -> SaveState.Failed(outcome.reason)
            }
        }
    }

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
    }

    /** Updates an existing row instead of inserting a new one. */
    fun update(
        id: String,
        amountCents: Long,
        categoryKey: String,
        note: String?,
        learnRule: Boolean,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            container.ledger.updateAmountAndNote(id, amountCents, note?.trim()?.ifBlank { null })
            container.ledger.reclassify(id = id, categoryKey = categoryKey, learn = learnRule)
            onDone()
        }
    }
}
