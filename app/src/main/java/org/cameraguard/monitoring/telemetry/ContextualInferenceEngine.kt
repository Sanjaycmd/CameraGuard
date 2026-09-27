package org.cameraguard.monitoring.telemetry

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import org.cameraguard.data.model.InferenceConfidence
import org.cameraguard.data.model.InferenceMethod
import kotlin.math.abs

data class InferredPackageContext(
    val packageName: String?,
    val confidence: InferenceConfidence,
    val method: InferenceMethod,
    val hasCameraPermission: Boolean?,
    val deltaFromEventMs: Long?,
    val recentActivityCount30s: Int = 0
)

data class UsageStatsCameraCandidate(
    val packageName: String,
    val className: String?,
    val timestamp: Long,
    val hasCameraPermission: Boolean?
)

data class UsageEventRecord(
    val packageName: String,
    val timestamp: Long,
    val eventType: Int = UsageEvents.Event.ACTIVITY_RESUMED,
    val className: String? = null
)

fun interface UsageEventProvider {
    fun queryEvents(startTime: Long, endTime: Long): List<UsageEventRecord>
}

class ContextualInferenceEngine(
    private val context: Context? = null,
    var correlationWindowMs: Long = DEFAULT_LOOKBACK_WINDOW_MS,
    private val customEventProvider: UsageEventProvider? = null,
    private val customPermissionChecker: ((String) -> Boolean?)? = null,
    val ownPackageName: String = context?.packageName ?: "org.cameraguard"
) {

    companion object {
        private const val TAG = "CameraGuard"
        const val DEFAULT_LOOKBACK_WINDOW_MS = 30000L
        const val TRANSITION_LOOKBACK_WINDOW_MS = 5000L
        const val DEFAULT_TRANSITION_CORROBORATION_WINDOW_MS = 500L

        private val KNOWN_CAMERA_PACKAGES = setOf(
            "com.android.camera",
            "com.android.camera2",
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "org.codeaurora.snapcam"
        )
    }

    private fun logD(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {
            // Android Log not mocked in local JVM unit tests
        }
    }

    private val usageStatsManager: UsageStatsManager? =
        context?.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private val packageManager: PackageManager? = context?.packageManager

    /**
     * Determines whether [packageName] and/or [className] corresponds to a dedicated camera application.
     */
    fun isCameraApplication(packageName: String, className: String? = null): Boolean {
        if (packageName in KNOWN_CAMERA_PACKAGES) return true
        if (packageName.endsWith(".camera") || packageName.contains(".camera.")) return true
        if (className != null && (className.endsWith("CameraActivity") || className.endsWith(".Camera") || className.contains("CameraActivity"))) {
            return true
        }
        return false
    }

    /**
     * Checks if the user has explicitly granted PACKAGE_USAGE_STATS access in Android Settings.
     */
    fun hasUsageAccessPermission(): Boolean {
        if (customEventProvider != null) return true
        val ctx = context ?: return false
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Identifies the most recent foreground camera activity from UsageStats within
     * [currentTime - correlationWindowMs, currentTime].
     *
     * Only considers ACTIVITY_RESUMED events. Future events and non-resumed events are rejected.
     */
    fun findForegroundCameraActivity(currentTime: Long): UsageStatsCameraCandidate? {
        if (!hasUsageAccessPermission()) return null

        val startTime = (currentTime - correlationWindowMs).coerceAtLeast(0)
        val endTime = currentTime

        var latestCandidate: UsageStatsCameraCandidate? = null
        var latestTimestamp = -1L

        if (customEventProvider != null) {
            val events = customEventProvider.queryEvents(startTime, endTime)
            for (rec in events) {
                if (rec.eventType == UsageEvents.Event.ACTIVITY_RESUMED && isCameraApplication(rec.packageName, rec.className)) {
                    if (rec.timestamp in startTime..currentTime && rec.timestamp >= latestTimestamp) {
                        latestTimestamp = rec.timestamp
                        val perm = customPermissionChecker?.invoke(rec.packageName) ?: hasCameraPermission(rec.packageName)
                        latestCandidate = UsageStatsCameraCandidate(
                            packageName = rec.packageName,
                            className = rec.className,
                            timestamp = rec.timestamp,
                            hasCameraPermission = perm
                        )
                    }
                }
            }
        } else {
            val statsManager = usageStatsManager ?: return null
            val usageEvents = try {
                statsManager.queryEvents(startTime, endTime)
            } catch (_: Exception) {
                null
            } ?: return null

            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && isCameraApplication(event.packageName, event.className)) {
                    if (event.timeStamp in startTime..currentTime && event.timeStamp >= latestTimestamp) {
                        latestTimestamp = event.timeStamp
                        val perm = customPermissionChecker?.invoke(event.packageName) ?: hasCameraPermission(event.packageName)
                        latestCandidate = UsageStatsCameraCandidate(
                            packageName = event.packageName,
                            className = event.className,
                            timestamp = event.timeStamp,
                            hasCameraPermission = perm
                        )
                    }
                }
            }
        }

        return latestCandidate
    }

    /**
     * Incurs temporal correlation over a 30-second backward lookback window [eventTimestamp - 30000, eventTimestamp].
     *
     * Identifies the most recent ACTIVITY_RESUMED event at or before [eventTimestamp].
     *
     * NOTE: Package identity through UsageStatsManager is an inferred temporal
     * correlation, not guaranteed ground truth.
     */
    fun inferForegroundPackage(eventTimestamp: Long): InferredPackageContext {
        if (!hasUsageAccessPermission()) {
            return InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null
            )
        }

        val startTime = (eventTimestamp - correlationWindowMs).coerceAtLeast(0)
        val endTime = eventTimestamp

        val nonOwnCandidates = mutableListOf<CandidateActivity>()
        var activityCount = 0
        var topResumedPackage: String? = null
        var topResumedTimestamp: Long = 0L

        if (customEventProvider != null) {
            val events = customEventProvider.queryEvents(startTime, endTime)
            for (rec in events) {
                if (rec.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (rec.timestamp in startTime..eventTimestamp) {
                        activityCount++
                        if (rec.timestamp >= topResumedTimestamp) {
                            topResumedTimestamp = rec.timestamp
                            topResumedPackage = rec.packageName
                        }
                        if (rec.packageName != ownPackageName) {
                            nonOwnCandidates.add(CandidateActivity(rec.packageName, rec.timestamp))
                        }
                    }
                }
            }
        } else {
            val statsManager = usageStatsManager ?: return InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null,
                recentActivityCount30s = 0
            )

            val usageEvents = try {
                statsManager.queryEvents(startTime, endTime)
            } catch (_: Exception) {
                null
            } ?: return InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null,
                recentActivityCount30s = 0
            )

            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                // Look for user-facing activity resumption/transitions at or before camera access
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp in startTime..eventTimestamp) {
                        activityCount++
                        if (event.timeStamp >= topResumedTimestamp) {
                            topResumedTimestamp = event.timeStamp
                            topResumedPackage = event.packageName
                        }
                        if (event.packageName != ownPackageName) {
                            nonOwnCandidates.add(CandidateActivity(event.packageName, event.timeStamp))
                        }
                    }
                }
            }
        }

        if (nonOwnCandidates.isEmpty()) {
            return InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null,
                recentActivityCount30s = activityCount
            )
        }

        // Sort candidates chronologically
        val sortedCandidates = nonOwnCandidates.sortedBy { it.timestamp }
        val newestCandidate = sortedCandidates.last()

        val transitionCutoff = eventTimestamp - TRANSITION_LOOKBACK_WINDOW_MS
        val cameraCapableTransition = sortedCandidates.reversed().firstOrNull { candidate ->
            candidate.timestamp >= transitionCutoff &&
                (customPermissionChecker?.invoke(candidate.packageName) ?: hasCameraPermission(candidate.packageName)) != false
        }

        // Case A: Host monitor (CameraGuard) is the top resumed activity
        if (topResumedPackage == ownPackageName) {
            if (cameraCapableTransition != null) {
                val perm = customPermissionChecker?.invoke(cameraCapableTransition.packageName)
                    ?: hasCameraPermission(cameraCapableTransition.packageName)
                val minDelta = eventTimestamp - cameraCapableTransition.timestamp
                val confidence = when {
                    minDelta <= 500L -> InferenceConfidence.HIGH
                    minDelta <= 2000L -> InferenceConfidence.MEDIUM
                    else -> InferenceConfidence.LOW
                }
                return InferredPackageContext(
                    packageName = cameraCapableTransition.packageName,
                    confidence = confidence,
                    method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
                    hasCameraPermission = perm,
                    deltaFromEventMs = minDelta,
                    recentActivityCount30s = activityCount
                )
            } else {
                // CameraGuard is foreground, and no camera-capable app transitioned recently.
                // The caller is an unattributed background component. Retain honest UNKNOWN.
                return InferredPackageContext(
                    packageName = null,
                    confidence = InferenceConfidence.NONE,
                    method = InferenceMethod.NONE,
                    hasCameraPermission = null,
                    deltaFromEventMs = null,
                    recentActivityCount30s = activityCount
                )
            }
        }

        // Case B: Another application was the newest resumed activity
        val newestPerm = customPermissionChecker?.invoke(newestCandidate.packageName)
            ?: hasCameraPermission(newestCandidate.packageName)

        val selectedCandidate: CandidateActivity
        val selectedPerm: Boolean?

        if (newestPerm == false) {
            if (cameraCapableTransition != null) {
                selectedCandidate = cameraCapableTransition
                selectedPerm = customPermissionChecker?.invoke(cameraCapableTransition.packageName)
                    ?: hasCameraPermission(cameraCapableTransition.packageName)
            } else if (newestCandidate.timestamp >= transitionCutoff) {
                // Unprivileged candidate within transition lookback window (e.g. unprivileged probe attempt)
                selectedCandidate = newestCandidate
                selectedPerm = newestPerm
            } else {
                // Candidate lacks camera permission and is outside transition window (e.g. home launcher from 15s ago).
                // Do not falsely attribute an innocent background app; retain honest UNKNOWN.
                return InferredPackageContext(
                    packageName = null,
                    confidence = InferenceConfidence.NONE,
                    method = InferenceMethod.NONE,
                    hasCameraPermission = null,
                    deltaFromEventMs = null,
                    recentActivityCount30s = activityCount
                )
            }
        } else {
            selectedCandidate = newestCandidate
            selectedPerm = newestPerm
        }

        val minDelta = eventTimestamp - selectedCandidate.timestamp
        val confidence = when {
            minDelta <= 500L -> InferenceConfidence.HIGH
            minDelta <= 2000L -> InferenceConfidence.MEDIUM
            else -> InferenceConfidence.LOW
        }

        return InferredPackageContext(
            packageName = selectedCandidate.packageName,
            confidence = confidence,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = selectedPerm,
            deltaFromEventMs = minDelta,
            recentActivityCount30s = activityCount
        )
    }

    /**
     * Checks if a camera-capable application resumed during the transition window [eventTimestamp, eventTimestamp + lookaheadWindowMs].
     *
     * This addresses the Android platform race condition where Camera HAL emits onCameraUnavailable
     * 300-600ms before ActivityTaskManager commits ACTIVITY_RESUMED in UsageStats.
     *
     * Returns an updated InferredPackageContext if a camera-capable app resumed, or null if no
     * camera application resumed during the window.
     */
    fun corroborateTransition(
        eventTimestamp: Long,
        lookaheadWindowMs: Long = DEFAULT_TRANSITION_CORROBORATION_WINDOW_MS
    ): InferredPackageContext? {
        if (!hasUsageAccessPermission()) return null

        val startTime = eventTimestamp
        val endTime = eventTimestamp + lookaheadWindowMs

        var latestCandidate: UsageEventRecord? = null
        var latestTimestamp = -1L

        if (customEventProvider != null) {
            val events = customEventProvider.queryEvents(startTime, endTime)
            for (rec in events) {
                if (rec.eventType == UsageEvents.Event.ACTIVITY_RESUMED && rec.packageName != ownPackageName) {
                    val perm = customPermissionChecker?.invoke(rec.packageName) ?: hasCameraPermission(rec.packageName)
                    val isCam = isCameraApplication(rec.packageName, rec.className)
                    if (perm == true || isCam) {
                        if (rec.timestamp in startTime..endTime && rec.timestamp >= latestTimestamp) {
                            latestTimestamp = rec.timestamp
                            latestCandidate = rec
                        }
                    }
                }
            }
        } else {
            val statsManager = usageStatsManager ?: return null
            val usageEvents = try {
                statsManager.queryEvents(startTime, endTime)
            } catch (_: Exception) {
                null
            } ?: return null

            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName != ownPackageName) {
                    val perm = customPermissionChecker?.invoke(event.packageName) ?: hasCameraPermission(event.packageName)
                    val isCam = isCameraApplication(event.packageName, event.className)
                    if (perm == true || isCam) {
                        if (event.timeStamp in startTime..endTime && event.timeStamp >= latestTimestamp) {
                            latestTimestamp = event.timeStamp
                            latestCandidate = UsageEventRecord(
                                packageName = event.packageName,
                                timestamp = event.timeStamp,
                                className = event.className
                            )
                        }
                    }
                }
            }
        }

        val candidate = latestCandidate ?: return null
        val perm = customPermissionChecker?.invoke(candidate.packageName) ?: hasCameraPermission(candidate.packageName)
        val minDelta = abs(candidate.timestamp - eventTimestamp)

        return InferredPackageContext(
            packageName = candidate.packageName,
            confidence = InferenceConfidence.HIGH,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = perm ?: true,
            deltaFromEventMs = minDelta
        )
    }

    private data class CandidateActivity(
        val packageName: String,
        val timestamp: Long
    )

    private fun hasCameraPermission(packageName: String): Boolean? {
        val pm = packageManager ?: return null
        val ctx = context ?: return null

        if (packageName == ctx.packageName) {
            return pm.checkPermission(
                Manifest.permission.CAMERA,
                packageName
            ) == PackageManager.PERMISSION_GRANTED
        }

        return try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            }

            val requestedPermissions = packageInfo.requestedPermissions
            if (requestedPermissions == null || Manifest.permission.CAMERA !in requestedPermissions) {
                // Authoritative denial: the application did not request android.permission.CAMERA in its manifest
                false
            } else {
                // Manifest requested CAMERA; check if PackageManager reports it granted
                if (pm.checkPermission(Manifest.permission.CAMERA, packageName) == PackageManager.PERMISSION_GRANTED) {
                    true
                } else {
                    // For third-party apps, dynamic runtime state cannot be authoritatively verified from sandbox
                    null
                }
            }
        } catch (_: PackageManager.NameNotFoundException) {
            // Package is filtered by Android package visibility or not found -> unverified
            null
        } catch (_: Exception) {
            // Any other sandbox or security limitation -> unverified
            null
        }
    }
}
