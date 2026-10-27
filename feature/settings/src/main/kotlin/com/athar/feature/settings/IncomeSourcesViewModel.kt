package com.athar.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.repo.IncomeSourcePreferencesRepository
import com.athar.core.domain.repo.IncomeSourceSettings
import com.athar.core.domain.repo.SmsImportController
import com.athar.core.domain.smsimport.DiscoveredSmsSender
import com.athar.core.domain.smsimport.SmsSenderIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class IncomeSourcesViewModel @Inject constructor(
    private val prefs: IncomeSourcePreferencesRepository,
    private val smsImport: SmsImportController,
) : ViewModel() {

    val settings: StateFlow<IncomeSourceSettings> = prefs.settings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IncomeSourceSettings())

    private val _discovered = MutableStateFlow<List<DiscoveredSmsSender>>(emptyList())
    val discovered: StateFlow<List<DiscoveredSmsSender>> = _discovered.asStateFlow()

    private val _manualDraft = MutableStateFlow("")
    val manualDraft: StateFlow<String> = _manualDraft.asStateFlow()

    fun refreshDiscovered() {
        viewModelScope.launch {
            _discovered.value = runCatching { smsImport.discoverSenders() }.getOrDefault(emptyList())
        }
    }

    fun setManualDraft(value: String) {
        _manualDraft.value = value
    }

    fun toggleSender(raw: String, selected: Boolean) {
        viewModelScope.launch {
            prefs.update(settings.value.withSenderToggled(raw, selected))
        }
    }

    fun addManualSender() {
        val exact = SmsSenderIds.preserve(_manualDraft.value) ?: return
        viewModelScope.launch {
            prefs.update(settings.value.withSenderToggled(exact, selected = true))
            _manualDraft.value = ""
        }
    }
}
