package dev.balaji.rem.di

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
 * A ViewModel built from the container: `containerViewModel { HomeViewModel(it.alarmRepository) }`.
 * It is scoped to the current navigation entry unless [owner] names another, which is how a
 * screen reaches the view model of a screen below it on the back stack.
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" },
    noinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(viewModelStoreOwner = owner) { create(container) }
}
