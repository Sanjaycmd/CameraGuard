package org.cameratestharness.experiment

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ExperimentLogger {

    private const val TAG = "CameraTestHarness"
    private const val LOG_PREFIX = "[CameraTestHarness][Experiment]"

    enum class SessionLifecycle {
        IDLE,
        STARTING,
        CAMERA_OPENING,
        CAMERA_OPEN,
        CAPTURE_SESSION_ACTIVE,
        RUNNING,
        STOPPING,
        CLOSED
    }

    private val lock = Any()
    private val recordsList = mutableListOf<ExperimentRecord>()
    private var storageFile: File? = null

    private val _records = MutableStateFlow<List<ExperimentRecord>>(emptyList())
    val records: StateFlow<List<ExperimentRecord>> = _records.asStateFlow()

    private val _recordCount = MutableStateFlow(0)
    val recordCount: StateFlow<Int> = _recordCount.asStateFlow()

    private val _currentScenario = MutableStateFlow(ExperimentScenario.NORMAL_FOREGROUND_CAMERA)
    val currentScenario: StateFlow<ExperimentScenario> = _currentScenario.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _repetition = MutableStateFlow(1)
    val repetition: StateFlow<Int> = _repetition.asStateFlow()

    private var sessionStartTimeMs: Long = 0L
    private var sessionLifecycle: SessionLifecycle = SessionLifecycle.IDLE

    fun init(context: Context) {
        synchronized(lock) {
            if (storageFile != null) return
            try {
                val dir = File(context.filesDir, "experiments").apply { mkdirs() }
                val file = File(dir, "persisted_experiment_history.csv")
                storageFile = file
                if (file.exists() && file.length() > 0) {
                    val loaded = ExperimentDataExporter.parseCsv(file.readText())
                    if (loaded.isNotEmpty()) {
                        recordsList.clear()
                        recordsList.addAll(loaded)
                        _records.value = ArrayList(recordsList)
                        _recordCount.value = recordsList.size
                        _currentSessionId.value = loaded.last().sampleId
                        val lastScenario = ExperimentScenario.fromId(loaded.last().scenarioId)
                        if (lastScenario != null) {
                            _currentScenario.value = lastScenario
                        }
                        sessionLifecycle = SessionLifecycle.CLOSED
                        Log.i(TAG, "$LOG_PREFIX Restored ${loaded.size} records from persistence file")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "$LOG_PREFIX Failed to initialize persistence", e)
            }
        }
    }

    fun getSessionLifecycle(): SessionLifecycle = synchronized(lock) { sessionLifecycle }

    fun setScenario(scenario: ExperimentScenario) {
        synchronized(lock) {
            _currentScenario.value = scenario
        }
        Log.i(TAG, "$LOG_PREFIX Scenario selected: ${scenario.id}")
        recordEvent(
            userAction = "USER_SELECTED_SCENARIO",
            cameraEvent = "NONE",
            cameraId = "NONE",
            cameraPermission = "UNKNOWN",
            activityState = "UNKNOWN",
            appVisibility = "UNKNOWN",
            foregroundServiceActive = false,
            foregroundServiceType = "none",
            screenState = "UNKNOWN",
            sessionState = "IDLE",
            lifecycleEvent = "NONE",
            cameraAvailability = "UNKNOWN",
            notes = "User selected scenario ${scenario.title}"
        )
    }

    fun setRepetition(rep: Int) {
        synchronized(lock) {
            _repetition.value = rep.coerceAtLeast(1)
        }
    }

    fun incrementRepetition() {
        synchronized(lock) {
            _repetition.value = _repetition.value + 1
        }
    }

    fun startNewSession(
        scenario: ExperimentScenario = _currentScenario.value,
        rep: Int = _repetition.value
    ): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val shortUuid = UUID.randomUUID().toString().take(6)
        val newSessionId = "exp_${timestamp}_rep${rep}_${shortUuid}"

        synchronized(lock) {
            _currentSessionId.value = newSessionId
            _currentScenario.value = scenario
            sessionStartTimeMs = System.currentTimeMillis()
            sessionLifecycle = SessionLifecycle.IDLE
        }

        Log.i(TAG, "$LOG_PREFIX Experiment started: session=$newSessionId, scenario=${scenario.id}, rep=$rep")
        return newSessionId
    }

    fun stopSession() {
        synchronized(lock) {
            val sessionId = _currentSessionId.value
            if (sessionLifecycle != SessionLifecycle.CLOSED) {
                sessionLifecycle = SessionLifecycle.CLOSED
            }
            Log.i(TAG, "$LOG_PREFIX Experiment stopped: session=$sessionId, total_records=${_recordCount.value}")
        }
    }

    fun clearSession() {
        synchronized(lock) {
            recordsList.clear()
            _records.value = emptyList()
            _recordCount.value = 0
            _currentSessionId.value = null
            _repetition.value = 1
            sessionStartTimeMs = 0L
            sessionLifecycle = SessionLifecycle.IDLE
            storageFile?.let { file ->
                if (file.exists()) {
                    file.delete()
                }
            }
        }
        Log.i(TAG, "$LOG_PREFIX Experiment cleared")
    }

    fun getRecords(): List<ExperimentRecord> {
        synchronized(lock) {
            return ArrayList(recordsList)
        }
    }

    fun recordEvent(
        userAction: String = "NONE",
        cameraEvent: String = "NONE",
        cameraId: String = "NONE",
        cameraPermission: String = "UNKNOWN",
        activityState: String = "UNKNOWN",
        appVisibility: String = "UNKNOWN",
        foregroundServiceActive: Boolean = false,
        foregroundServiceType: String = "none",
        screenState: String = "UNKNOWN",
        sessionState: String = "UNKNOWN",
        lifecycleEvent: String = "NONE",
        cameraAvailability: String = "UNKNOWN",
        recentUserInteraction: Boolean = true,
        notes: String = ""
    ) {
        try {
            val nowMs = System.currentTimeMillis()
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(nowMs))

            synchronized(lock) {
                var sId = _currentSessionId.value
                if (sId == null) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
                    val shortUuid = UUID.randomUUID().toString().take(6)
                    val rep = _repetition.value
                    sId = "exp_${timestamp}_rep${rep}_${shortUuid}"
                    _currentSessionId.value = sId
                    sessionStartTimeMs = nowMs
                    sessionLifecycle = SessionLifecycle.IDLE
                }

                // 1. Idempotency guards: prevent duplicate stop and closure events for the same session
                if (userAction == "USER_PRESSED_STOP") {
                    val alreadyHasStop = recordsList.any { it.sampleId == sId && it.userAction == "USER_PRESSED_STOP" }
                    if (alreadyHasStop) {
                        Log.d(TAG, "$LOG_PREFIX Dropping duplicate USER_PRESSED_STOP for session $sId")
                        return
                    }
                }

                if (cameraEvent == "CAMERA_STOP_REQUESTED") {
                    val alreadyHasStopReq = recordsList.any { it.sampleId == sId && it.cameraEvent == "CAMERA_STOP_REQUESTED" }
                    if (alreadyHasStopReq) {
                        Log.d(TAG, "$LOG_PREFIX Dropping duplicate CAMERA_STOP_REQUESTED for session $sId")
                        return
                    }
                    sessionLifecycle = SessionLifecycle.STOPPING
                }

                if (cameraEvent == "CAMERA_CLOSED") {
                    val alreadyHasClosed = recordsList.any { it.sampleId == sId && it.cameraEvent == "CAMERA_CLOSED" }
                    if (alreadyHasClosed) {
                        Log.d(TAG, "$LOG_PREFIX Dropping duplicate CAMERA_CLOSED for session $sId")
                        return
                    }
                    sessionLifecycle = SessionLifecycle.CLOSED
                }

                // Update session lifecycle progression
                when {
                    cameraEvent == "CAMERA_OPEN_REQUESTED" || userAction == "USER_PRESSED_START" -> {
                        if (sessionLifecycle == SessionLifecycle.IDLE) {
                            sessionLifecycle = SessionLifecycle.STARTING
                        }
                    }
                    cameraEvent == "CAMERA_OPENING" -> {
                        sessionLifecycle = SessionLifecycle.CAMERA_OPENING
                    }
                    cameraEvent == "CAMERA_OPENED" -> {
                        sessionLifecycle = SessionLifecycle.CAMERA_OPEN
                    }
                    cameraEvent == "CAPTURE_SESSION_STARTED" -> {
                        sessionLifecycle = SessionLifecycle.CAPTURE_SESSION_ACTIVE
                    }
                    sessionState == "RUNNING" -> {
                        sessionLifecycle = SessionLifecycle.RUNNING
                    }
                }

                val durationMs = if (sessionStartTimeMs > 0L) {
                    (nowMs - sessionStartTimeMs).coerceAtLeast(0L).toString()
                } else {
                    ExperimentRecord.VALUE_UNKNOWN
                }

                val scenario = _currentScenario.value

                // Derive ground truth context with strict invariants
                val groundTruth = deriveGroundTruth(
                    scenario = scenario,
                    cameraEvent = cameraEvent,
                    userAction = userAction,
                    cameraPermission = cameraPermission,
                    appVisibility = appVisibility,
                    activityState = activityState,
                    foregroundServiceActive = foregroundServiceActive
                )

                val finalNotes = when {
                    notes.isBlank() -> "rep=${_repetition.value}"
                    notes.contains("rep=") -> notes
                    else -> "rep=${_repetition.value};$notes"
                }

                val record = ExperimentRecord(
                    sampleId = sId,
                    timestamp = nowIso,
                    scenarioId = scenario.id,
                    groundTruthContext = groundTruth,
                    userAction = userAction,
                    cameraEvent = cameraEvent,
                    cameraId = cameraId,
                    packageName = "org.cameratestharness",
                    cameraPermission = cameraPermission,
                    activityState = activityState,
                    appVisibility = appVisibility,
                    foregroundServiceActive = foregroundServiceActive,
                    foregroundServiceType = foregroundServiceType,
                    screenState = screenState,
                    sessionState = sessionState,
                    sessionDurationMs = durationMs,
                    recentUserInteraction = recentUserInteraction,
                    lifecycleEvent = lifecycleEvent,
                    cameraAvailability = cameraAvailability,
                    notes = finalNotes
                )

                recordsList.add(record)
                _records.value = ArrayList(recordsList)
                _recordCount.value = recordsList.size

                // Persist to disk if storage file is initialized
                storageFile?.let { file ->
                    try {
                        file.writeText(ExperimentDataExporter.exportToCsvString(recordsList))
                    } catch (e: Exception) {
                        Log.w(TAG, "$LOG_PREFIX Failed to persist experiment data", e)
                    }
                }

                Log.d(TAG, "$LOG_PREFIX Event recorded [${record.userAction}|${record.cameraEvent}]: count=${_recordCount.value}, lifecycle=$sessionLifecycle")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$LOG_PREFIX Error recording experiment event", e)
        }
    }

    private fun deriveGroundTruth(
        scenario: ExperimentScenario,
        cameraEvent: String,
        userAction: String,
        cameraPermission: String,
        appVisibility: String,
        activityState: String,
        foregroundServiceActive: Boolean
    ): String {
        // Invariant 1: If cameraPermission is GRANTED, ground truth can NEVER be PERMISSION_DENIED
        if (scenario == ExperimentScenario.PERMISSION_DENIED) {
            return if (cameraPermission == "DENIED") {
                GroundTruthContext.PERMISSION_DENIED.label
            } else if (cameraPermission == "GRANTED") {
                GroundTruthContext.NO_CAMERA_ACTIVITY.label
            } else {
                GroundTruthContext.PERMISSION_DENIED.label
            }
        }

        // Invariant 2: Explicit camera session must NEVER be labeled AMBIGUOUS_CONTEXT
        val isExplicitCameraSession = cameraEvent in listOf("CAMERA_OPENED", "CAPTURE_SESSION_STARTED", "CAMERA_OPEN_REQUESTED") ||
                foregroundServiceActive ||
                (cameraEvent == "CAMERA_CLOSED" && scenario != ExperimentScenario.AMBIGUOUS_CONTEXT)

        if (scenario == ExperimentScenario.AMBIGUOUS_CONTEXT) {
            return if (isExplicitCameraSession) {
                if (appVisibility == "BACKGROUND" || activityState in listOf("STOPPED", "PAUSED")) {
                    GroundTruthContext.USER_INITIATED_BACKGROUND_CONTINUATION.label
                } else {
                    GroundTruthContext.USER_INITIATED_FOREGROUND.label
                }
            } else {
                GroundTruthContext.AMBIGUOUS_CONTEXT.label
            }
        }

        return when (scenario) {
            ExperimentScenario.PERMISSION_GRANTED_NO_CAMERA -> GroundTruthContext.NO_CAMERA_ACTIVITY.label
            ExperimentScenario.CAMERA_SESSION_CLOSED -> GroundTruthContext.CAMERA_SESSION_CLOSED.label
            ExperimentScenario.AUTOMATED_BACKGROUND_TRIGGER -> {
                if (cameraEvent == "CAMERA_CLOSED" || userAction == "USER_PRESSED_STOP") {
                    GroundTruthContext.CAMERA_SESSION_CLOSED.label
                } else {
                    GroundTruthContext.AUTOMATED_BACKGROUND_TRIGGER.label
                }
            }
            ExperimentScenario.BACKGROUND_CAMERA_CONTINUATION -> {
                if (cameraEvent == "CAMERA_CLOSED" || userAction == "USER_PRESSED_STOP") {
                    GroundTruthContext.CAMERA_SESSION_CLOSED.label
                } else if (appVisibility == "BACKGROUND" || activityState in listOf("STOPPED", "PAUSED")) {
                    GroundTruthContext.USER_INITIATED_BACKGROUND_CONTINUATION.label
                } else {
                    GroundTruthContext.USER_INITIATED_FOREGROUND.label
                }
            }
            else -> {
                if (cameraEvent == "CAMERA_CLOSED" || userAction == "USER_PRESSED_STOP") {
                    GroundTruthContext.CAMERA_SESSION_CLOSED.label
                } else if (appVisibility == "BACKGROUND") {
                    GroundTruthContext.USER_INITIATED_BACKGROUND_CONTINUATION.label
                } else {
                    GroundTruthContext.USER_INITIATED_FOREGROUND.label
                }
            }
        }
    }
}
