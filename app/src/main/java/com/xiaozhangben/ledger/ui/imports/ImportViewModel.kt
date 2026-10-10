package com.xiaozhangben.ledger.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhangben.ledger.data.local.ImportBatchEntity
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.domain.bill.ImportPreview
import com.xiaozhangben.ledger.domain.bill.ImportReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportStep {
    data object Pick : ImportStep
    data object Parsing : ImportStep
    data class Preview(val preview: ImportPreview) : ImportStep
    data class Done(val report: ImportReport) : ImportStep
    data class Failed(val message: String, val detail: String?) : ImportStep
}

/**
 * Two-phase bill import.
 *
 * The preview is not a nicety: this is the one action that can write hundreds of rows
 * at once, so the user gets to see the inferred column mapping and the
 * new/duplicate/excluded/unclassified counts before anything is stored.
 */
class ImportViewModel(private val container: AppContainer) : ViewModel() {

    private val _step = MutableStateFlow<ImportStep>(ImportStep.Pick)
    val step: StateFlow<ImportStep> = _step.asStateFlow()

    val history: StateFlow<List<ImportBatchEntity>> = container.ledger.observeImportBatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Guard against someone picking a 200MB file by mistake. */
    private val maxBytes = 24L * 1024 * 1024

    fun parse(fileName: String, bytes: ByteArray) {
        if (bytes.size > maxBytes) {
            _step.value = ImportStep.Failed("文件太大（${bytes.size / 1024 / 1024} MB）", "账单 CSV 通常在几百 KB 以内。")
            return
        }
        _step.value = ImportStep.Parsing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { container.imports.preview(fileName, bytes) }
            }
            _step.value = result.fold(
                onSuccess = { inner ->
                    inner.fold(
                        onSuccess = { ImportStep.Preview(it) },
                        onFailure = { ImportStep.Failed(it.message ?: "解析失败", null) },
                    )
                },
                onFailure = { ImportStep.Failed(it.message ?: "解析失败", null) },
            )
        }
    }

    fun commit(preview: ImportPreview) {
        viewModelScope.launch {
            val report = runCatching { container.imports.commit(preview) }
            _step.value = report.fold(
                onSuccess = { ImportStep.Done(it) },
                onFailure = { ImportStep.Failed("写入失败", it.message) },
            )
        }
    }

    fun undoBatch(batchId: String) {
        viewModelScope.launch {
            container.imports.undoImport(batchId)
        }
    }

    fun reset() {
        _step.value = ImportStep.Pick
    }
}
