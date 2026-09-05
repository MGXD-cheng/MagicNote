package com.magicnote.mgxd.ui.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.magicnote.mgxd.AppContainer
import com.magicnote.mgxd.MGApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 在 Composable 中获取绑定了 AppContainer 的 ViewModel */
@Composable
fun <T : ViewModel> appViewModel(
    modelClass: Class<T>,
    create: (AppContainer) -> T
): T {
    val app = LocalContext.current.applicationContext as MGApp
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner in composition" }
    val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM =
            create(app.container) as VM
    }
    return ViewModelProvider(owner, factory).get(modelClass)
}

/** 一次性收集 Flow 的首个值 */
internal suspend fun <T> Flow<T>.collectFirst(): T = first()
