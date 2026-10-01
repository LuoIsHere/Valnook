package dev.valnook.app.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.concurrent.atomic.AtomicInteger

/** Small in-process diagnostics for validating entry store cleanup; no persisted data or UI hooks. */
internal object EntryLifetime {
    val active = AtomicInteger()
    val created = AtomicInteger()
    val cleared = AtomicInteger()
}
private class EntryScopeProbe : ViewModel() {
    init { EntryLifetime.active.incrementAndGet()
        EntryLifetime.created.incrementAndGet() }
    override fun onCleared() { EntryLifetime.active.decrementAndGet()
        EntryLifetime.cleared.incrementAndGet() }
}
@Composable internal inline fun <reified T : ViewModel> pageViewModel(noinline factory: CreationExtras.() -> T): T {
    return scopedViewModel(factory)
}
@Composable internal inline fun <reified T : ViewModel> scopedViewModel(noinline factory: CreationExtras.() -> T): T {
    // Use the NavEntry's local owner. Never force an Activity owner here.
    observeEntryLifetime()
    return viewModel(factory = viewModelFactory { initializer(factory) })
}
@Composable internal fun observeEntryLifetime() {
    viewModel<EntryScopeProbe>(key = "entry-scope-probe", factory = viewModelFactory { initializer { EntryScopeProbe() } })
}
