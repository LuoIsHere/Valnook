package dev.valnook.feature.webadmin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.webadmin.WebAdminService
import kotlinx.coroutines.launch

class WebAdminViewModel(private val service: WebAdminService) : ViewModel() {
    val state = service.state
    fun start() { viewModelScope.launch { service.start() } }
    fun stop() { viewModelScope.launch { service.stop() } }
}
