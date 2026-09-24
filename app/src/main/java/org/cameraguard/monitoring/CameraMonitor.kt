package org.cameraguard.monitoring

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cameraguard.data.db.CameraGuardDatabase
import org.cameraguard.data.model.CameraAccessEvent
import org.cameraguard.data.repository.CameraEventRepository
import org.cameraguard.data.repository.DefaultCameraEventRepository

class CameraMonitor(
    val repository: CameraEventRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    // If repository is present, mirror database events; otherwise maintain in-memory fallback
    private val _inMemoryEvents = MutableStateFlow<List<CameraAccessEvent>>(emptyList())

    val events: StateFlow<List<CameraAccessEvent>> = if (repository != null) {
        repository.getAllEvents().stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )
    } else {
        _inMemoryEvents.asStateFlow()
    }

    fun startMonitoring() {
        _isMonitoring.value = true
    }

    fun stopMonitoring() {
        _isMonitoring.value = false
    }

    /**
     * Records a rich [CameraAccessEvent], persisting to Room database when available.
     */
    fun recordEvent(event: CameraAccessEvent) {
        _inMemoryEvents.value = listOf(event) + _inMemoryEvents.value
        repository?.let { repo ->
            scope.launch {
                repo.recordEvent(event)
            }
        }
    }

    /**
     * Backward-compatibility method for Phase 1 code.
     */
    fun recordEvent(
        source: String,
        isUnexpected: Boolean
    ) {
        val event = CameraAccessEvent(
            timestamp = System.currentTimeMillis(),
            source = source,
            isUnexpected = isUnexpected
        )
        recordEvent(event)
    }

    companion object {
        @Volatile
        private var INSTANCE: CameraMonitor? = null

        fun getInstance(context: Context): CameraMonitor {
            return INSTANCE ?: synchronized(this) {
                val db = CameraGuardDatabase.getInstance(context)
                val repo = DefaultCameraEventRepository(db.cameraEventDao())
                val instance = CameraMonitor(repo)
                INSTANCE = instance
                instance
            }
        }
    }
}
