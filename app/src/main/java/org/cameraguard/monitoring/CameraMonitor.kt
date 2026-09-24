package org.cameraguard.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraMonitor {

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private val _events = MutableStateFlow<List<CameraAccessEvent>>(emptyList())
    val events: StateFlow<List<CameraAccessEvent>> = _events.asStateFlow()

    fun startMonitoring() {
        _isMonitoring.value = true
    }

    fun stopMonitoring() {
        _isMonitoring.value = false
    }

    fun recordEvent(
        source: String,
        isUnexpected: Boolean
    ) {
        val event = CameraAccessEvent(
            timestamp = System.currentTimeMillis(),
            source = source,
            isUnexpected = isUnexpected
        )

        _events.value = _events.value + event
    }
}
