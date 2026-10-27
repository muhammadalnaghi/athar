package com.athar.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.repo.BackfillProgress
import com.athar.core.domain.repo.SmsImportController
import com.athar.core.domain.repo.SmsImportPreferencesRepository
import com.athar.core.domain.smsimport.DiscoveredSmsSender
import com.athar.core.domain.smsimport.SmsImportFilterSettings
import com.athar.core.domain.smsimport.SmsImportPreview
import com.athar.core.domain.smsimport.SmsImportResult
import com.athar.core.domain.smsimport.SmsSenderIds
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import javax.inject.Inject

@HiltViewModel
class SmsImportViewModel @Inject constructor(
    private val prefs: SmsImportPreferencesRepository,
    private val controller: SmsImportController,
) : ViewModel() {

    val settings: StateFlow<SmsImportFilterSettings> = prefs.settings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmsImportFilterSettings())

    val progress: StateFlow<BackfillProgress> = controller.progress

    private val _discovered = MutableStateFlow<List<DiscoveredSmsSender>>(emptyList())
    val discovered: StateFlow<List<DiscoveredSmsSender>> = _discovered.asStateFlow()

    private val _preview = MutableStateFlow<SmsImportPreview?>(null)
    val preview: StateFlow<SmsImportPreview?> = _preview.asStateFlow()

    private val _lastResult = MutableStateFlow<SmsImportResult?>(null)
    val lastResult: StateFlow<SmsImportResult?> = _lastResult.asStateFlow()

    private val _manualDraft = MutableStateFlow("")
    val manualDraft: StateFlow<String> = _manualDraft.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refreshDiscovered() {
        viewModelScope.launch {
            _discovered.value = runCatching { controller.discoverSenders() }.getOrDefault(emptyList())
        }
    }

    fun setManualDraft(value: String) {
        _manualDraft.value = value
    }

    fun toggleSender(raw: String, selected: Boolean) {
        viewModelScope.launch {
            prefs.update(settings.value.withSenderToggled(raw, selected))
            _preview.value = null
        }
    }

    fun addManualSender() {
        val exact = SmsSenderIds.preserve(_manualDraft.value) ?: return
        viewModelScope.launch {
            prefs.update(settings.value.withSenderToggled(exact, selected = true))
            _manualDraft.value = ""
            _preview.value = null
        }
    }

    fun setStartDate(date: LocalDate?) {
        viewModelScope.launch {
            prefs.update(settings.value.copy(startDate = date))
            _preview.value = null
        }
    }

    fun setEndDate(date: LocalDate?) {
        viewModelScope.launch {
            prefs.update(settings.value.copy(endDate = date, openEnded = date == null))
            _preview.value = null
        }
    }

    fun setOpenEnded(open: Boolean) {
        viewModelScope.launch {
            prefs.update(settings.value.copy(openEnded = open))
            _preview.value = null
        }
    }

    fun setLiveTracking(enabled: Boolean) {
        viewModelScope.launch {
            prefs.update(settings.value.copy(liveTrackingEnabled = enabled))
        }
    }

    fun runPreview() {
        viewModelScope.launch {
            _error.value = null
            controller.preview()
                .onSuccess { _preview.value = it }
                .onFailure { _error.value = it.message }
        }
    }

    fun runImport() {
        viewModelScope.launch {
            _error.value = null
            val result = controller.importSelected()
            _lastResult.value = result
            if (result.failed) _error.value = result.failureReason
        }
    }
}
