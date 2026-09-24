package org.cameraguard.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cameraguard.data.model.AccessClassification
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.repository.CameraEventRepository
import org.cameraguard.monitoring.CameraMonitor
import org.cameraguard.monitoring.notification.NotificationHelper

class MainScreenViewModel(
    private val repository: CameraEventRepository,
    private val monitor: CameraMonitor,
    private val context: Context
) : ViewModel() {

    private val notificationHelper = NotificationHelper(context)

    val isMonitoring: StateFlow<Boolean> = monitor.isMonitoring

    val totalCount: StateFlow<Int> = repository.getTotalEventCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val unexpectedCount: StateFlow<Int> = repository.getUnexpectedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val expectedCount: StateFlow<Int> = repository.getExpectedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val latestEvent: StateFlow<CameraAccessEvent?> = repository.getLatestEvent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val recentEvents: StateFlow<List<CameraAccessEvent>> = repository.getRecentEvents(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedWindowMs = MutableStateFlow(1000L)
    val selectedWindowMs: StateFlow<Long> = _selectedWindowMs.asStateFlow()

    fun updateCorrelationWindow(windowMs: Long) {
        _selectedWindowMs.value = windowMs
    }

    fun injectSyntheticEvent(event: CameraAccessEvent) {
        monitor.recordEvent(event)
        if (event.classification == AccessClassification.UNEXPECTED) {
            notificationHelper.showUnexpectedActivityAlert(event)
        }
    }

    fun clearAllEvents() {
        viewModelScope.launch {
            repository.clearAllEvents()
        }
    }

    fun clearSyntheticEvents() {
        viewModelScope.launch {
            repository.clearSyntheticEvents()
        }
    }
}
