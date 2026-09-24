package org.cameraguard.monitoring.telemetry

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.cameraguard.data.model.ScreenInteractivityState

class ScreenStateTracker(private val context: Context) {

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val keyguardManager: KeyguardManager? =
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    /**
     * Inspects the physical display and lockguard state.
     */
    fun currentScreenState(): ScreenInteractivityState {
        val isInteractive = powerManager?.isInteractive ?: true
        if (!isInteractive) {
            return ScreenInteractivityState.SCREEN_OFF
        }

        val isLocked = keyguardManager?.isDeviceLocked ?: false
        return if (isLocked) {
            ScreenInteractivityState.SCREEN_ON_LOCKED
        } else {
            ScreenInteractivityState.SCREEN_ON_UNLOCKED
        }
    }
}
