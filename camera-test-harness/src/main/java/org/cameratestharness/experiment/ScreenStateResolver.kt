package org.cameratestharness.experiment

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Resolves actual physical display and lockguard state from Android system services.
 * Strictly queries PowerManager and KeyguardManager without fabricating values.
 */
object ScreenStateResolver {

    private const val TAG = "ScreenStateResolver"

    const val STATE_SCREEN_ON_UNLOCKED = "ON_UNLOCKED"
    const val STATE_SCREEN_ON_LOCKED = "ON_LOCKED"
    const val STATE_SCREEN_OFF = "OFF"
    const val STATE_UNKNOWN = "UNKNOWN"

    /**
     * Inspects physical device display and keyguard state via context.
     * Returns "OFF", "ON_LOCKED", "ON_UNLOCKED", or "UNKNOWN" if services unavailable.
     */
    fun resolveScreenState(context: Context?): String {
        if (context == null) return STATE_UNKNOWN

        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = powerManager?.isInteractive ?: true
            if (!isInteractive) {
                return STATE_SCREEN_OFF
            }

            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            val isLocked = keyguardManager?.isDeviceLocked ?: false
            if (isLocked) {
                STATE_SCREEN_ON_LOCKED
            } else {
                STATE_SCREEN_ON_UNLOCKED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve physical screen state", e)
            STATE_UNKNOWN
        }
    }
}
