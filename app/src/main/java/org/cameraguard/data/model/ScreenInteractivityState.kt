package org.cameraguard.data.model

/**
 * Observable device screen and user interactivity state at the time of
 * a camera availability transition.
 */
enum class ScreenInteractivityState {
    SCREEN_ON_UNLOCKED,
    SCREEN_ON_LOCKED,
    SCREEN_OFF,
    UNKNOWN
}
