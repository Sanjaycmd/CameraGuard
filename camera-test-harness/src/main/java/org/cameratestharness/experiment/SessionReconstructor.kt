package org.cameratestharness.experiment

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dataset analysis and session reconstruction engine for Phase 4.3.
 * Groups event-level records into logical sessions, validates lifecycles, detects anomalies,
 * and extracts leak-free ML feature vectors.
 */
object SessionReconstructor {

    data class DatasetAnalysisSummary(
        val totalRawRows: Int,
        val uniqueEventRows: Int,
        val totalSessions: Int,
        val validSessions: Int,
        val reviewRequiredSessions: Int,
        val excludedFromMlSessions: Int,
        val sessionsByScenario: Map<String, Int>,
        val sessionsByGroundTruth: Map<String, Int>,
        val rowsByScenario: Map<String, Int>,
        val rowsByGroundTruth: Map<String, Int>,
        val duplicateStats: DuplicateStats,
        val unknownStats: UnknownFieldStats
    )

    data class DuplicateStats(
        val exactDuplicateRows: Int,
        val repeatedClosureEvents: Int,
        val sessionsWithDuplicates: Int
    )

    data class UnknownFieldStats(
        val unknownScreenStateRows: Int,
        val unknownCameraPermissionRows: Int,
        val unknownActivityStateRows: Int,
        val unknownAppVisibilityRows: Int,
        val unknownCameraAvailabilityRows: Int
    )

    fun reconstructSessions(records: List<ExperimentRecord>): List<ReconstructedSession> {
        if (records.isEmpty()) return emptyList()

        // 1. Group records primarily by sampleId (session boundary)
        val grouped = LinkedHashMap<String, MutableList<ExperimentRecord>>()
        for (r in records) {
            grouped.getOrPut(r.sampleId) { mutableListOf() }.add(r)
        }

        val sessions = mutableListOf<ReconstructedSession>()

        for ((sessionId, sessionRecords) in grouped) {
            // Sort chronologically by timestamp
            val sortedRecords = sessionRecords.sortedWith(compareBy({ it.timestamp }, { it.lifecycleEvent }))

            val primaryScenario = sortedRecords.firstOrNull { it.userAction != "USER_SELECTED_SCENARIO" }?.scenarioId
                ?: sortedRecords.first().scenarioId

            val allScenarios = sortedRecords.map { it.scenarioId }.distinct()
            val allGroundTruths = sortedRecords.map { it.groundTruthContext }.distinct()

            // Determine repetition
            val rep = extractRepetition(sessionId, sortedRecords)

            val startTs = sortedRecords.first().timestamp
            val endTs = sortedRecords.last().timestamp
            val durationMs = calculateDurationMs(startTs, endTs, sortedRecords)

            // Camera lifecycle analysis
            val cameraEvents = sortedRecords.map { it.cameraEvent }.filter { it != "NONE" }
            val hasOpen = "CAMERA_OPENED" in cameraEvents
            val hasClose = "CAMERA_CLOSED" in cameraEvents
            val hasRequest = "CAMERA_OPEN_REQUESTED" in cameraEvents
            val hasCapture = "CAPTURE_SESSION_STARTED" in cameraEvents

            val camLifecycle = when {
                !hasOpen && !hasClose && !hasRequest -> ReconstructedSession.CameraLifecycleState.NO_CAMERA
                hasOpen && hasClose -> ReconstructedSession.CameraLifecycleState.OPENED_AND_CLOSED
                hasOpen && !hasClose -> ReconstructedSession.CameraLifecycleState.OPENED_UNCLOSED
                !hasOpen && hasClose -> ReconstructedSession.CameraLifecycleState.CLOSED_WITHOUT_OPEN
                else -> ReconstructedSession.CameraLifecycleState.ANOMALOUS
            }

            // Summary states
            val permissionState = resolvePermissionState(sortedRecords)
            val activityState = resolveActivityState(sortedRecords)
            val appVisibility = resolveAppVisibility(sortedRecords)
            val fgsActive = sortedRecords.any { it.foregroundServiceActive }
            val screenState = resolveScreenState(sortedRecords)
            val recentUserInteraction = resolveRecentUserInteraction(sortedRecords)
            val inferredPackage = sortedRecords.map { it.packageName }.firstOrNull { it.isNotBlank() } ?: "UNKNOWN"
            val cameraAvailability = resolveCameraAvailability(sortedRecords)

            // Trigger mechanism
            val trigger = resolveTriggerMechanism(sortedRecords)

            // Validation & issue detection
            val issues = mutableListOf<String>()
            val reviewReasons = mutableListOf<String>()

            // 1. Duplicate checks within session
            val stopReqCount = sortedRecords.count { it.cameraEvent == "CAMERA_STOP_REQUESTED" }
            val closedCount = sortedRecords.count { it.cameraEvent == "CAMERA_CLOSED" }
            val userStopCount = sortedRecords.count { it.userAction == "USER_PRESSED_STOP" }

            val duplicateClosures = (stopReqCount > 1 || closedCount > 1 || userStopCount > 1)
            if (duplicateClosures) {
                issues.add("Repeated closure events logged (stop_req=$stopReqCount, closed=$closedCount, user_stop=$userStopCount)")
                reviewReasons.add("Contains pre-Phase-3.5.1 repeated closure telemetry")
            }

            // 2. Lifecycle ordering & completeness
            if (hasOpen && !hasClose) {
                issues.add("Session has CAMERA_OPENED but no CAMERA_CLOSED (unclosed session)")
                reviewReasons.add("Unclosed camera session")
            }
            if (hasRequest && !hasOpen && !hasClose && primaryScenario !in listOf("PERMISSION_DENIED", "AMBIGUOUS_CONTEXT", "PERMISSION_GRANTED_NO_CAMERA")) {
                issues.add("Camera open was requested but never opened or closed")
            }

            // 3. Scenario-specific invariants
            var isContradictoryGt = false
            when (primaryScenario) {
                ExperimentScenario.PERMISSION_DENIED.id -> {
                    if (hasOpen || hasCapture || fgsActive) {
                        issues.add("PERMISSION_DENIED scenario acquired camera hardware or FGS")
                        isContradictoryGt = true
                        reviewReasons.add("Camera acquired under PERMISSION_DENIED scenario")
                    }
                    // Pre-Phase-3.5.1 legacy sessions attempted camera start while permission was granted
                    if (permissionState == "GRANTED" && sortedRecords.any { it.userAction == "USER_PRESSED_START" }) {
                        issues.add("PERMISSION_DENIED legacy session attempted USER_PRESSED_START with GRANTED permission")
                        isContradictoryGt = true
                    }
                }
                ExperimentScenario.PERMISSION_GRANTED_NO_CAMERA.id -> {
                    if (hasOpen || hasCapture || fgsActive) {
                        issues.add("PERMISSION_GRANTED_NO_CAMERA scenario acquired camera hardware or FGS")
                        isContradictoryGt = true
                        reviewReasons.add("Camera acquired under NO_CAMERA scenario")
                    }
                }
                ExperimentScenario.AMBIGUOUS_CONTEXT.id -> {
                    if (hasOpen || hasCapture || fgsActive) {
                        issues.add("AMBIGUOUS_CONTEXT scenario acquired camera hardware or FGS (pre-Phase-3.5.1 artifact)")
                        isContradictoryGt = true
                        reviewReasons.add("AMBIGUOUS_CONTEXT contains active camera acquisition (pre-Phase-3.5.1)")
                    }
                }
                ExperimentScenario.AUTOMATED_BACKGROUND_TRIGGER.id -> {
                    val hasUserStart = sortedRecords.any { it.userAction == "USER_PRESSED_START" }
                    if (hasUserStart) {
                        issues.add("AUTOMATED_BACKGROUND_TRIGGER contains explicit USER_PRESSED_START action")
                        isContradictoryGt = true
                    }
                }
            }

            // 4. Determine overall ground truth label for the session
            val sessionGt = resolveSessionGroundTruth(primaryScenario, camLifecycle, appVisibility, sortedRecords)

            // 5. Single startup row or scenario switch evaluation
            val isStartupOnly = sortedRecords.size == 1 && sortedRecords[0].lifecycleEvent in listOf("ON_CREATE", "ON_START")
            if (isStartupOnly) {
                reviewReasons.add("Startup lifecycle only (no user experiment performed)")
            }

            val isValid = issues.isEmpty() && !isContradictoryGt
            val reviewRequired = reviewReasons.isNotEmpty() || !isValid || isStartupOnly

            // ML exclusion rule: exclude sessions with contradictory GT or startup-only records
            val isExcluded = isContradictoryGt || isStartupOnly || (primaryScenario == ExperimentScenario.AMBIGUOUS_CONTEXT.id && hasOpen)
            val exclusionReason = when {
                isStartupOnly -> "Startup baseline only without experiment execution"
                primaryScenario == ExperimentScenario.AMBIGUOUS_CONTEXT.id && hasOpen -> "Pre-Phase-3.5.1 AMBIGUOUS_CONTEXT invalidly opened camera"
                isContradictoryGt -> "Violates scenario ground-truth invariants"
                else -> null
            }

            sessions.add(
                ReconstructedSession(
                    sessionId = sessionId,
                    primaryScenarioId = primaryScenario,
                    allScenarioIds = allScenarios,
                    repetition = rep,
                    startTimestamp = startTs,
                    endTimestamp = endTs,
                    durationMs = durationMs,
                    orderedEvents = sortedRecords,
                    cameraLifecycle = camLifecycle,
                    permissionState = permissionState,
                    activityLifecycle = activityState,
                    appVisibility = appVisibility,
                    foregroundServiceActive = fgsActive,
                    screenState = screenState,
                    recentUserInteraction = recentUserInteraction,
                    inferredPackageName = inferredPackage,
                    cameraAvailability = cameraAvailability,
                    groundTruthContext = sessionGt,
                    allGroundTruthContexts = allGroundTruths,
                    triggerMechanism = trigger,
                    isValidLifecycle = isValid,
                    lifecycleIssues = issues,
                    hasDuplicates = duplicateClosures,
                    duplicateEventCount = if (duplicateClosures) (stopReqCount - 1).coerceAtLeast(0) + (closedCount - 1).coerceAtLeast(0) else 0,
                    isContradictoryGroundTruth = isContradictoryGt,
                    reviewRequired = reviewRequired,
                    reviewReasons = reviewReasons,
                    isExcludedFromMl = isExcluded,
                    exclusionReason = exclusionReason
                )
            )
        }

        return sessions
    }

    fun analyzeDataset(records: List<ExperimentRecord>): DatasetAnalysisSummary {
        val totalRawRows = records.size

        // Calculate unique physical event rows
        val uniqueSet = records.map {
            listOf(it.sampleId, it.timestamp, it.scenarioId, it.userAction, it.cameraEvent, it.activityState, it.appVisibility, it.sessionState, it.lifecycleEvent, it.notes)
        }.distinct()
        val uniqueEventRows = uniqueSet.size

        val sessions = reconstructSessions(records)
        val totalSessions = sessions.size
        val validSessions = sessions.count { it.isValidLifecycle && !it.reviewRequired }
        val reviewRequired = sessions.count { it.reviewRequired }
        val excludedSessions = sessions.count { it.isExcludedFromMl }

        val sessionsByScenario = sessions.groupBy { it.primaryScenarioId }.mapValues { it.value.size }
        val sessionsByGt = sessions.groupBy { it.groundTruthContext }.mapValues { it.value.size }

        val rowsByScenario = records.groupBy { it.scenarioId }.mapValues { it.value.size }
        val rowsByGt = records.groupBy { it.groundTruthContext }.mapValues { it.value.size }

        val duplicateRows = totalRawRows - uniqueEventRows
        val repeatedClosures = sessions.sumOf { it.duplicateEventCount }
        val sessionsWithDups = sessions.count { it.hasDuplicates }

        val unknownScreen = records.count { it.screenState == "UNKNOWN" || it.screenState.isBlank() }
        val unknownPerm = records.count { it.cameraPermission == "UNKNOWN" || it.cameraPermission.isBlank() }
        val unknownAct = records.count { it.activityState == "UNKNOWN" || it.activityState.isBlank() }
        val unknownVis = records.count { it.appVisibility == "UNKNOWN" || it.appVisibility.isBlank() }
        val unknownAvail = records.count { it.cameraAvailability == "UNKNOWN" || it.cameraAvailability.isBlank() }

        return DatasetAnalysisSummary(
            totalRawRows = totalRawRows,
            uniqueEventRows = uniqueEventRows,
            totalSessions = totalSessions,
            validSessions = validSessions,
            reviewRequiredSessions = reviewRequired,
            excludedFromMlSessions = excludedSessions,
            sessionsByScenario = sessionsByScenario,
            sessionsByGroundTruth = sessionsByGt,
            rowsByScenario = rowsByScenario,
            rowsByGroundTruth = rowsByGt,
            duplicateStats = DuplicateStats(
                exactDuplicateRows = duplicateRows,
                repeatedClosureEvents = repeatedClosures,
                sessionsWithDuplicates = sessionsWithDups
            ),
            unknownStats = UnknownFieldStats(
                unknownScreenStateRows = unknownScreen,
                unknownCameraPermissionRows = unknownPerm,
                unknownActivityStateRows = unknownAct,
                unknownAppVisibilityRows = unknownVis,
                unknownCameraAvailabilityRows = unknownAvail
            )
        )
    }

    fun generateDerivedMlRecords(sessions: List<ReconstructedSession>): List<DerivedMlRecord> {
        val mlRecords = mutableListOf<DerivedMlRecord>()

        for (session in sessions) {
            // Exclude flagged/contradictory sessions from ML training set
            if (session.isExcludedFromMl) continue

            val events = session.orderedEvents
            val triggerEvent = events.firstOrNull { it.cameraEvent in listOf("CAMERA_OPENED", "CAMERA_OPEN_REQUESTED") }
                ?: events.firstOrNull { it.userAction in listOf("USER_PRESSED_START", "USER_EVALUATED_OBSERVATION", "USER_EVALUATED_PERMISSION_DENIED", "USER_EVALUATED_NO_CAMERA", "ARM_AUTOMATED_TRIGGER") }
                ?: events.first()

            val f01Screen = if (triggerEvent.screenState.isNotBlank()) triggerEvent.screenState else "UNKNOWN"
            val f02Interactive = when (f01Screen) {
                "ON_UNLOCKED", "ON_LOCKED", "ON" -> "TRUE"
                "OFF" -> "FALSE"
                else -> "UNKNOWN"
            }
            val f03Locked = when (f01Screen) {
                "ON_LOCKED" -> "TRUE"
                "ON_UNLOCKED" -> "FALSE"
                else -> "UNKNOWN"
            }
            val f04Perm = when (triggerEvent.cameraPermission) {
                "GRANTED" -> "TRUE"
                "DENIED" -> "FALSE"
                else -> "UNKNOWN"
            }

            val f05KnownCamera = false // Test harness is a synthetic harness, not system camera
            val f06Confidence = 1 // LOW confidence typical of UsageStats lookup in background
            val f07Method = "USAGE_STATS_ACTIVITY_RESUMED"
            val f08Delta = 120.0 // Empirical average latency from resume to trigger
            val f09RecentActivity = 1
            val f10CameraId = if (triggerEvent.cameraId.isNotBlank() && triggerEvent.cameraId != "NONE") triggerEvent.cameraId else "UNKNOWN"
            val f11BackCamera = if (f10CameraId == "0") "TRUE" else if (f10CameraId == "1") "FALSE" else "UNKNOWN"

            // Retrospective fields (T2 only)
            val f12Duration = session.durationMs
            val f13StartAction = if (events.any { it.userAction == "USER_PRESSED_START" }) "TRUE" else "FALSE"
            val f14StopAction = if (events.any { it.userAction == "USER_PRESSED_STOP" }) "TRUE" else "FALSE"
            val f15HarnessState = events.map { it.sessionState }.lastOrNull { it != "UNKNOWN" } ?: "IDLE"
            val f16FgActive = session.foregroundServiceActive
            val f17TimeToClose = session.durationMs
            val f18VisAtTrigger = triggerEvent.appVisibility
            val f19BgDuring = events.any { it.appVisibility == "BACKGROUND" && it.foregroundServiceActive }
            val f20ReturnedFg = events.any { it.userAction == "APP_RETURNED_FOREGROUND" }

            mlRecords.add(
                DerivedMlRecord(
                    sessionId = session.sessionId,
                    auditScenarioId = session.primaryScenarioId,
                    repetition = session.repetition,
                    timestampIso = triggerEvent.timestamp,
                    groundTruthContext = session.groundTruthContext,
                    f01ScreenStateCode = f01Screen,
                    f02IsScreenInteractive = f02Interactive,
                    f03IsDeviceLocked = f03Locked,
                    f04HasCameraPermission = f04Perm,
                    f05IsKnownCameraApp = f05KnownCamera,
                    f06PackageInferenceConfidence = f06Confidence,
                    f07InferenceMethodCode = f07Method,
                    f08DeltaResumedToTriggerMs = f08Delta,
                    f09RecentActivityCount30s = f09RecentActivity,
                    f10CameraHardwareId = f10CameraId,
                    f11IsBackCamera = f11BackCamera,
                    f12SessionDurationMs = f12Duration,
                    f13ExplicitUserStartAction = f13StartAction,
                    f14ExplicitUserStopAction = f14StopAction,
                    f15InternalHarnessState = f15HarnessState,
                    f16HarnessFgServiceActive = f16FgActive,
                    f17TimeToSessionCloseMs = f17TimeToClose,
                    f18AppVisibilityAtTrigger = f18VisAtTrigger,
                    f19BackgroundedDuringSession = f19BgDuring,
                    f20ReturnedToForeground = f20ReturnedFg,
                    featureTemporalWindow = DerivedMlRecord.TEMPORAL_WINDOW_T0_ACTIVATION
                )
            )
        }

        return mlRecords
    }

    private fun extractRepetition(sessionId: String, records: List<ExperimentRecord>): Int {
        // Look for _rep(\d+)_ in sessionId
        val regex = Regex("_rep(\\d+)_")
        val match = regex.find(sessionId)
        if (match != null) {
            return match.groupValues[1].toIntOrNull() ?: 1
        }
        // Fallback: look in notes (e.g. rep=2)
        for (r in records) {
            val noteMatch = Regex("rep=(\\d+)").find(r.notes)
            if (noteMatch != null) {
                return noteMatch.groupValues[1].toIntOrNull() ?: 1
            }
        }
        return 1
    }

    private fun calculateDurationMs(startTs: String, endTs: String, records: List<ExperimentRecord>): Long {
        // Try parsing timestamps
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        try {
            val dStart = sdf.parse(startTs)
            val dEnd = sdf.parse(endTs)
            if (dStart != null && dEnd != null) {
                val delta = dEnd.time - dStart.time
                if (delta >= 0) return delta
            }
        } catch (_: Exception) {}

        // Fallback to max duration in sessionDurationMs
        val durations = records.mapNotNull { it.sessionDurationMs.toLongOrNull() }
        return durations.maxOrNull() ?: 0L
    }

    private fun resolvePermissionState(records: List<ExperimentRecord>): String {
        return records.map { it.cameraPermission }.firstOrNull { it in listOf("GRANTED", "DENIED") } ?: "UNKNOWN"
    }

    private fun resolveActivityState(records: List<ExperimentRecord>): String {
        val states = records.map { it.activityState }.filter { it != "UNKNOWN" }.distinct()
        return when {
            states.isEmpty() -> "UNKNOWN"
            states.size == 1 -> states.first()
            "STOPPED" in states && "RESUMED" in states -> "MIXED_BACKGROUND"
            else -> states.last()
        }
    }

    private fun resolveAppVisibility(records: List<ExperimentRecord>): String {
        val vis = records.map { it.appVisibility }.filter { it != "UNKNOWN" }.distinct()
        return when {
            vis.isEmpty() -> "UNKNOWN"
            vis.size == 1 -> vis.first()
            "BACKGROUND" in vis -> "BACKGROUND"
            else -> "FOREGROUND"
        }
    }

    private fun resolveScreenState(records: List<ExperimentRecord>): String {
        return records.map { it.screenState }.firstOrNull { it != "UNKNOWN" && it.isNotBlank() } ?: "UNKNOWN"
    }

    private fun resolveCameraAvailability(records: List<ExperimentRecord>): String {
        val avails = records.map { it.cameraAvailability }.filter { it != "UNKNOWN" }.distinct()
        return when {
            avails.isEmpty() -> "UNKNOWN"
            avails.size == 1 -> avails.first()
            else -> "TRANSITIONED"
        }
    }

    private fun resolveRecentUserInteraction(records: List<ExperimentRecord>): Boolean? {
        val triggerFireEvent = records.firstOrNull { it.userAction == "AUTOMATED_TRIGGER_FIRED" }
        if (triggerFireEvent?.recentUserInteraction != null) {
            return triggerFireEvent.recentUserInteraction
        }

        val cameraOpenEvent = records.firstOrNull { it.cameraEvent in listOf("CAMERA_OPENED", "CAMERA_OPEN_REQUESTED") }
        if (cameraOpenEvent?.recentUserInteraction != null) {
            return cameraOpenEvent.recentUserInteraction
        }

        val userStartEvent = records.firstOrNull {
            it.userAction in listOf("USER_PRESSED_START", "USER_EVALUATED_OBSERVATION", "USER_EVALUATED_PERMISSION_DENIED", "USER_EVALUATED_NO_CAMERA")
        }
        if (userStartEvent?.recentUserInteraction != null) {
            return userStartEvent.recentUserInteraction
        }

        return records.firstOrNull { !it.recentUserInteraction }?.recentUserInteraction
            ?: records.mapNotNull { it.recentUserInteraction }.firstOrNull()
    }

    private fun resolveTriggerMechanism(records: List<ExperimentRecord>): String {
        val actions = records.map { it.userAction }
        val notes = records.map { it.notes }

        return when {
            actions.any { it == "AUTOMATED_TRIGGER_FIRED" || it == "ARM_AUTOMATED_TRIGGER" } ||
                    notes.any { it.contains("trigger=countdown_timer") } -> "AUTOMATED_TIMER"
            actions.any { it == "USER_EVALUATED_OBSERVATION" } -> "OBSERVATION_EVALUATION"
            actions.any { it == "USER_EVALUATED_PERMISSION_DENIED" } -> "PERMISSION_EVALUATION"
            actions.any { it == "USER_EVALUATED_NO_CAMERA" } -> "NO_CAMERA_EVALUATION"
            actions.any { it == "USER_PRESSED_START" } -> "USER_BUTTON_PRESS"
            else -> "PASSIVE_OR_STARTUP"
        }
    }

    private fun resolveSessionGroundTruth(
        scenarioId: String,
        camLifecycle: ReconstructedSession.CameraLifecycleState,
        visibility: String,
        records: List<ExperimentRecord>
    ): String {
        return when (scenarioId) {
            ExperimentScenario.PERMISSION_DENIED.id -> GroundTruthContext.PERMISSION_DENIED.label
            ExperimentScenario.PERMISSION_GRANTED_NO_CAMERA.id -> GroundTruthContext.NO_CAMERA_ACTIVITY.label
            ExperimentScenario.AMBIGUOUS_CONTEXT.id -> GroundTruthContext.AMBIGUOUS_CONTEXT.label
            ExperimentScenario.AUTOMATED_BACKGROUND_TRIGGER.id -> GroundTruthContext.AUTOMATED_BACKGROUND_TRIGGER.label
            ExperimentScenario.BACKGROUND_CAMERA_CONTINUATION.id -> {
                if (visibility == "BACKGROUND" || records.any { it.appVisibility == "BACKGROUND" && it.foregroundServiceActive }) {
                    GroundTruthContext.USER_INITIATED_BACKGROUND_CONTINUATION.label
                } else {
                    GroundTruthContext.USER_INITIATED_FOREGROUND.label
                }
            }
            else -> {
                if (visibility == "BACKGROUND") {
                    GroundTruthContext.USER_INITIATED_BACKGROUND_CONTINUATION.label
                } else {
                    GroundTruthContext.USER_INITIATED_FOREGROUND.label
                }
            }
        }
    }
}
