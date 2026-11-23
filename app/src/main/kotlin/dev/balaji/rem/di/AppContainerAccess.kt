package dev.balaji.rem.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.balaji.rem.RemApplication

/** The container, from any Context: receivers, services, activities. */
val Context.appContainer: AppContainer
    get() = (applicationContext as RemApplication).container

/**
 * The container inside Compose. Each activity provides it once around its content.
 * Previews and tests provide a container built from fakes.
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("No AppContainer provided. Wrap the content in CompositionLocalProvider(LocalAppContainer provides ...).")
}

/**
 * A ViewModel scoped to the current navigation entry (or activity), built from the
 * container: `val viewModel = containerViewModel { HomeViewModel(it.alarmRepository, it.alarmScheduler) }`.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(noinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel { create(container) }
}
