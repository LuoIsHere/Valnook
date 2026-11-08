package dev.valnook.feature.webadmin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.webadmin.WebAdminService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

class WebAdminViewModel(private val service: WebAdminService) : ViewModel() {
    val state = service.state
    fun start() { viewModelScope.launch { service.start() } }
    /** Finishes even if the page is left at once, so no server is left listening. */
    fun stop() { viewModelScope.launch(NonCancellable) { service.stop() } }
}
