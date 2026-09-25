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
    private val customPermissionChecker: ((String) -> Boolean?)? = null
) {

    companion object {
        private const val TAG = "CameraGuard"
        const val DEFAULT_LOOKBACK_WINDOW_MS = 30000L

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

        var latestPackage: String? = null
        var latestEventTimestamp: Long = -1L
        var activityCount = 0

        if (customEventProvider != null) {
            val events = customEventProvider.queryEvents(startTime, endTime)
            for (rec in events) {
                if (rec.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (rec.timestamp in startTime..eventTimestamp) {
                        activityCount++
                        if (rec.timestamp >= latestEventTimestamp) {
                            latestEventTimestamp = rec.timestamp
                            latestPackage = rec.packageName
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
                        if (event.timeStamp >= latestEventTimestamp) {
                            latestEventTimestamp = event.timeStamp
                            latestPackage = event.packageName
                        }
                    }
                }
            }
        }

        if (latestPackage == null || latestEventTimestamp < 0) {
            return InferredPackageContext(
                packageName = null,
                confidence = InferenceConfidence.NONE,
                method = InferenceMethod.NONE,
                hasCameraPermission = null,
                deltaFromEventMs = null,
                recentActivityCount30s = activityCount
            )
        }

        val minDelta = eventTimestamp - latestEventTimestamp
        val hasPermission = customPermissionChecker?.invoke(latestPackage)
            ?: hasCameraPermission(latestPackage)

        val confidence = when {
            minDelta <= 500L -> InferenceConfidence.HIGH
            minDelta <= 2000L -> InferenceConfidence.MEDIUM
            else -> InferenceConfidence.LOW
        }

        return InferredPackageContext(
            packageName = latestPackage,
            confidence = confidence,
            method = InferenceMethod.USAGE_STATS_ACTIVITY_RESUMED,
            hasCameraPermission = hasPermission,
            deltaFromEventMs = minDelta,
            recentActivityCount30s = activityCount
        )
    }

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
