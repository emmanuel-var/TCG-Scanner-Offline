package com.tcgscanner.offline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tcgscanner.offline.AppContainer
import com.tcgscanner.offline.core.GameId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Creates a ViewModel scoped to the current navigation entry, wired to the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    val factory = remember(container) { viewModelFactory { initializer<VM> { create(container) } } }
    return viewModel<VM>(key = key, factory = factory)
}

/** The game the whole app is currently filtered to. Emits only when it really changes. */
fun AppContainer.currentGameFlow(): Flow<GameId> =
    settingsState.filterNotNull().map { it.currentGame }.filterNotNull().distinctUntilChanged()

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> AppContainer.perGame(block: (GameId) -> Flow<T>): Flow<T> = currentGameFlow().flatMapLatest(block)
