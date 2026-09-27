# CameraGuard — Phase 8.7 Attribution Enhancement Report

**Document ID:** `CG-DOC-P8-008-ATTRIBUTION`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** IMPLEMENTED & VERIFIED  

---

## 1. Overview & Problem Statement

Physical device telemetry on vivo V2202 (Android 15 / API 35) revealed a 484–773 ms hardware-to-framework attribution race condition:
- The Camera HAL emits `onCameraUnavailable` within 50–150 ms of cold application launch.
- Android's `ActivityTaskManagerService` commits the `ACTIVITY_RESUMED` event to `UsageStatsManager` 484–773 ms after camera acquisition begins.
- At $T_0$, `inferForegroundPackage(currentTime)` observes the pre-existing foreground application (e.g. `com.android.launcher3`), which lacks camera permissions, causing Tier 1 Rule 3 to incorrectly issue an `UNEXPECTED` false alarm.

---

## 2. Technical Implementation

### 2.1 Contextual Inference Engine Lookahead Corroboration

In `ContextualInferenceEngine.kt`, implemented `corroborateTransition`:
```kotlin
fun corroborateTransition(
    eventTimestamp: Long,
    lookaheadWindowMs: Long = DEFAULT_TRANSITION_CORROBORATION_WINDOW_MS
): InferredPackageContext?
```
- Queries `UsageStatsManager` for events in `[eventTimestamp, eventTimestamp + lookaheadWindowMs]`.
- Identifies if a camera-capable application (holding `CAMERA` permission or registered in `KNOWN_CAMERA_PACKAGES`) resumed within this window.
- Returns `InferredPackageContext` with `confidence = HIGH`, `method = USAGE_STATS_ACTIVITY_RESUMED`, and `hasCameraPermission = true`.
- If no camera application resumed (e.g., genuine background probe or unauthorized caller), returns `null`.

### 2.2 Adaptive Tracking in `CameraAvailabilityTracker.kt`

In `CameraAvailabilityTracker.kt`:
- Added `DEFAULT_CORROBORATION_DELAY_MS = 500L`.
- Added configurable property:
  ```kotlin
  @Volatile
  var transitionCorroborationDelayMs: Long = if (context != null) DEFAULT_CORROBORATION_DELAY_MS else 0L
  ```
- Fast-path vs Corroboration decision logic in `handleCameraTransition`:
  - **Screen-Off / Device Locked:** Corroboration is BYPASSED (0 ms delay). Immediate security alert.
  - **Warm Camera / High Confidence with Camera Permission:** Corroboration is BYPASSED (0 ms delay, fast-path).
  - **Active Screen with Unprivileged or Low-Confidence Candidate:** Adaptive corroboration window launched asynchronously. After delay, evaluates `corroborateTransition`. If corroborated, updates attribution and active session owner. If uncorroborated, retains initial unprivileged attribution.
  - All existing unit tests default to 0 ms delay (`context == null`), preserving synchronous execution semantics.

---

## 3. Unit Test Verification & Regression Evidence

1. **`ContextualInferenceEngineTest.kt`:**
   - `testCorroborateTransition_detectsCameraResumedInLookaheadWindow`: Verifies promotion of camera candidate within window.
   - `testCorroborateTransition_returnsNullWhenNoCameraAppInWindow`: Verifies rejection of non-camera apps.
   - `testCorroborateTransition_ignoresResumptionAfterWindow`: Verifies rejection of events outside window.

2. **`CameraAvailabilityTrackerTest.kt`:**
   - `testColdTransitionCorroboration_promotesLauncherToCamera`: End-to-end corroboration test promoting Launcher3 $\to$ Camera with `EXPECTED` classification.
   - `testColdTransitionCorroboration_unprivilegedRemainsUnexpectedWhenNoCameraResumes`: Verifies uncorroborated malicious/unprivileged caller remains `UNEXPECTED`.

3. **Regression Test Gate (`8.7-GATE-B`):**
   - Total test suites: 21
   - Total tests executed: 282
   - Test failures: 0
   - Test skipped: 0
   - Result: 100% PASS across `:app`, `:adversary-test-app`, and `:camera-test-harness`.
