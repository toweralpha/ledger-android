package com.xiaozhangben.ledger.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaozhangben.ledger.di.AppContainer
import com.xiaozhangben.ledger.di.appContainer

/**
 * Builds a screen's ViewModel from the app container.
 *
 * Hand-rolled instead of Hilt: one graph, a handful of classes, and no annotation
 * processor to slow the build down. `viewModel()` still caches per
 * ViewModelStoreOwner, so this does not recreate state on recomposition.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalContext.current.appContainer
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create(container) as T
    }
    return viewModel(key = key, factory = factory)
}
