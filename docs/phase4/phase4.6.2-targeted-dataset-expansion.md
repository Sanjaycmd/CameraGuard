# CameraGuard Phase 4.6.2 — Targeted Physical Dataset Expansion Specification & Protocol

**Document ID:** `CG-DOC-P462-001`  
**Phase:** Phase 4.6.2 (Targeted Physical Dataset Expansion)  
**Status:** Implementation Validated & Collection Protocol Ready  
**Target Repository:** `Sanjaycmd/CameraGuard`  
**Modules Referenced:** `:app` (CameraGuard production baseline, untouched), `:camera-test-harness` (Controlled experiment application)  
**Author:** CameraGuard Research & Engineering Team  
**Date:** September 25, 2026  

---

## 1. Objective

The objective of Phase 4.6.2 is to resolve the empirical deficiencies identified during the **Phase 4.6.1 Dataset Readiness Audit** through targeted, controlled physical experiments on Android hardware.

Rather than pursuing arbitrary sample counts, this expansion is designed to:
1. Unfreeze zero-variance and constant features ($F_{01} \dots F_{03}$ screen state, $F_{10} \dots F_{11}$ camera orientation).
2. Expand representation of under-supported multiclass contexts (`USER_INITIATED_BACKGROUND_CONTINUATION`, `AMBIGUOUS_CONTEXT`, `NO_CAMERA_ACTIVITY`).
3. Maintain strict separation between genuine production-observable telemetry and research ground truth.
4. Establish a repeatable manual collection protocol with explicit repetition tracking.
5. Provide a larger, balanced empirical foundation for the upcoming re-audit in Phase 4.6.3 and subsequent modeling.

---

## 2. Phase 4.6.1 Deficiencies Addressed

| # | Phase 4.6.1 Finding / Deficiency | Phase 4.6.2 Remediation / Solution |
| :-: | :--- | :--- |
| **1** | **$F_{01} \dots F_{03}$ 100% UNKNOWN in Harness Data** | Implemented [`ScreenStateResolver`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/ScreenStateResolver.kt) in `ExperimentLogger` to poll real Android `PowerManager.isInteractive` and `KeyguardManager.isDeviceLocked` system services on physical runs. |
| **2** | **$F_{10} \dots F_{11}$ 100% Rear Camera Only** | Implemented [`CameraSelector`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/CameraSelector.kt) to enumerate camera IDs, query `CameraCharacteristics.LENS_FACING`, support explicit Front/Rear selection, and record actual lens facing in telemetry. |
| **3** | **Multiclass Rare Classes ($N=1, N=2$)** | Defined targeted physical collection protocols for Background Continuation ($N \ge 5$), Ambiguous Observation ($N \ge 3$), and Permission Granted / No Camera ($N \ge 5$). |
| **4** | **Zero Automated Trigger Repetitions** | Standardized the 5-second countdown timer mechanism (`AUTOMATED_BACKGROUND_TRIGGER`) with repetition tracking ($N \ge 5$). |
| **5** | **Android Sandbox Observability ($F_{04}$)** | Re-verified that $F_{04}$ remains strictly `UNVERIFIED` in production feature extraction, while harness records its own permission state solely for research benchmarking. |
| **6** | **Repetition Tracking Integrity** | Enforced unique session IDs (`exp_<timestamp>_rep<N>_<uuid>`) and updated duplicate-detection rules so distinct physical runs are never falsely flagged as duplicate rows. |

---

## 3. Scenario Matrix

All scenarios reuse existing canonical scenario IDs from [`ExperimentScenario.kt`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/ExperimentScenario.kt):

| Group | Scenario ID | Description | Camera Orientation | Target Reps | Expected Ground Truth |
| :---: | :--- | :--- | :---: | :---: | :--- |
| **A1** | `NORMAL_FOREGROUND_CAMERA` | Legitimate foreground camera capture | **Rear (BACK)** | $\ge 5$ | `USER_INITIATED_FOREGROUND` |
| **A2** | `NORMAL_FOREGROUND_CAMERA` | Legitimate foreground camera capture | **Front (FRONT)** | $\ge 5$ | `USER_INITIATED_FOREGROUND` |
| **B** | `BACKGROUND_CAMERA_CONTINUATION` | Camera started in foreground, kept streaming in background | Rear / Front | $\ge 5$ | `USER_INITIATED_BACKGROUND_CONTINUATION` |
| **C** | `CAMERA_START_STOP` | Complete start-then-stop lifecycle | Rear / Front | $\ge 5$ | `USER_INITIATED_FOREGROUND` $\rightarrow$ `CAMERA_SESSION_CLOSED` |
| **D** | `PERMISSION_DENIED` | Permission denied; camera acquisition withheld | None | $\ge 5$ | `PERMISSION_DENIED` |
| **E** | `PERMISSION_GRANTED_NO_CAMERA` | Permission held, but camera access omitted | None | $\ge 5$ | `NO_CAMERA_ACTIVITY` |
| **F** | `AMBIGUOUS_CONTEXT` | Passive observation without camera opening | None | $\ge 3$ | `AMBIGUOUS_CONTEXT` |
| **G** | `AUTOMATED_BACKGROUND_TRIGGER` | Armed countdown timer fires in background | Rear / Front | $\ge 5$ | `AUTOMATED_BACKGROUND_TRIGGER` |
| **H1** | `PHYSICAL_CONDITION_TEST` | Screen turned OFF during active camera streaming | Rear | $\ge 3$ | Controlled condition (see Section 9) |
| **H2** | `PHYSICAL_CONDITION_TEST` | Device LOCKED during active camera streaming | Rear | $\ge 3$ | Controlled condition (see Section 9) |

---

## 4. Ground-Truth Definitions & Invariants

To prevent label ambiguity, ground-truth contexts in [`GroundTruthContext.kt`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/GroundTruthContext.kt) are governed by strict invariants enforced by [`ExperimentDataValidator.kt`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/ExperimentDataValidator.kt):

1. **`USER_INITIATED_FOREGROUND`:** The camera was acquired while the activity was in the foreground immediately following explicit user interaction (`USER_PRESSED_START`).
2. **`USER_INITIATED_BACKGROUND_CONTINUATION`:** The camera was legitimately acquired while the application was visible, and capture continued via an active foreground service (`camera`) after the activity transitioned to the background (`STOPPED` / `PAUSED`).
3. **`PERMISSION_DENIED`:** Camera permission was withheld/denied. **Invariant:** Cannot have `camera_permission == 'GRANTED'` and cannot record active camera events (`CAMERA_OPENED`).
4. **`NO_CAMERA_ACTIVITY`:** Camera permission was granted, but no hardware acquisition occurred. **Invariant:** Cannot record active camera events or active foreground services.
5. **`AMBIGUOUS_CONTEXT`:** Telemetry does not establish clear user intent; strictly observation-only. **Invariant:** Cannot initiate camera sessions or foreground services.
6. **`AUTOMATED_BACKGROUND_TRIGGER`:** Camera hardware was requested via an automated timer/event without immediate user button clicks. **Invariant:** User action cannot be `USER_PRESSED_START`.

> [!CAUTION]
> **No Malicious Labeling:** Controlled automated trigger sessions must NEVER be labeled as `malware`, `spyware`, or `unauthorized`. They represent controlled background acquisitions without immediate preceding UI interaction.

---

## 5. Camera Orientation Strategy

In Phase 4.6.1, 100% of camera activations used rear sensor ID `"0"`, causing $F_{10}$ and $F_{11}$ to be near-constant.

### Architectural Solution
1. **Dynamic Sensor Enumeration:** [`CameraSelector.inspectAvailableCameras`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/CameraSelector.kt) queries `CameraCharacteristics.LENS_FACING` across all available hardware IDs via `CameraManager`.
2. **User-Controlled Selection:** The UI exposes a 3-way segmented filter chip:
   * **`Auto (Default Rear)`:** Selects primary back camera.
   * **`Rear / Back Camera`:** Selects `LENS_FACING_BACK`.
   * **`Front / Selfie Camera`:** Selects `LENS_FACING_FRONT`.
3. **Graceful Fallback:** If the device lacks a physical front sensor, the selector logs the condition, marks `isFallback = true`, and safely falls back to default.
4. **Telemetry Provenance:** The actual lens facing resolved from `CameraCharacteristics` is recorded in event telemetry (`notes = "rep=...;lens_facing=FRONT"`), ensuring [`FeatureExtractor`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/FeatureExtractor.kt) sets $F_{11} = \text{FALSE}$ regardless of hardware ID string conventions.

---

## 6. Screen-State Strategy

In Phase 4.6.1, $F_{01} \dots F_{03}$ were 100% `UNKNOWN` because the test harness did not query Android display services.

### Architectural Solution
[`ScreenStateResolver`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/ScreenStateResolver.kt) inspects the real hardware state at event capture time:
* `PowerManager.isInteractive == false` $\rightarrow$ `"OFF"`
* `KeyguardManager.isDeviceLocked == true` $\rightarrow$ `"ON_LOCKED"`
* `PowerManager.isInteractive == true && !isDeviceLocked` $\rightarrow$ `"ON_UNLOCKED"`
* Context null / uninitialized $\rightarrow$ `"UNKNOWN"`

This unfreezes $F_{01}$ (`screen_state_code`), $F_{02}$ (`is_screen_interactive`), and $F_{03}$ (`is_device_locked`) using genuine Android system telemetry.

---

## 7. Background-Continuation Strategy

Android's camera foreground service contract allows background camera continuation only if:
1. The service was started while the application was in the foreground (`FOREGROUND_SERVICE_TYPE_CAMERA`).
2. The user was aware of camera capture initiation.

When multitasking:
* `Activity` transitions: `RESUMED` $\rightarrow$ `PAUSED` $\rightarrow$ `STOPPED`.
* `UsageStatsManager` records an `ACTIVITY_PAUSED` transition.
* CameraGuard's `ContextualInferenceEngine` downgrades confidence from `MEDIUM` to `LOW`.
* The experiment records this exact transition, allowing subsequent ML models to learn that legitimate background streaming is preceded by a foreground start event within the same logical session.

---

## 8. Automated-Trigger Strategy

The automated background trigger represents camera acquisition without an immediate preceding UI click:
1. User selects `AUTOMATED_BACKGROUND_TRIGGER`.
2. User taps **ARM TRIGGER (5s)**.
3. The harness starts the foreground service immediately while visible (complying with Android 14/15 foreground service start requirements).
4. A 5-second countdown timer is scheduled on a dedicated background thread.
5. User navigates to Home.
6. The timer fires: `CameraTestService` calls `openCamera()` while the activity is backgrounded.
7. Telemetry records `userAction = "AUTOMATED_TRIGGER_FIRED"`, `recentUserInteraction = false`, `notes = "trigger=countdown_timer"`.

---

## 9. Physical OS Limitations & Safety Analysis

### What Android Allows vs What It Restricts
Android 9 through 15 impose native-level constraints via the OS `CameraService`:
1. **Screen OFF Camera Opening:**
   * If an application attempts to call `CameraManager.openCamera()` while the device display is OFF (`isInteractive == false`), Android's native `CameraService` will reject the call with `ERROR_CAMERA_DISABLED` or throw a `SecurityException`, even if the app holds `CAMERA` permission and a foreground service.
   * *Conclusion:* An app **cannot reliably initiate a new camera session while the screen is off** on standard unrooted Android devices.
2. **Screen OFF Camera Continuation:**
   * If a camera session was **already streaming** while the screen was ON, and the user presses the Power button to turn the screen OFF, some OEM devices will keep the capture session alive briefly, while others immediately trigger `onError` or close the device to save power.
3. **Screen ON Locked Camera Opening:**
   * From the lock screen, only the default system camera app (`com.android.camera` or OEM equivalent) has permission to launch above the keyguard via `setShowWhenLocked(true)` and `turnScreenOn()`. Third-party applications cannot acquire the camera from the background while the device is locked without explicit user authentication.

> [!NOTE]
> **Safety Rule:** If Android's OS prevents camera capture during Screen OFF or Locked states, the harness will record the error event as returned by the OS (`onError`). Under no circumstances will artificial `SCREEN_OFF` or `ON_LOCKED` camera acquisitions be fabricated.

---

## 10. Data-Integrity Safeguards

1. **Idempotent Closure:** Redundant `CAMERA_STOP_REQUESTED` and `CAMERA_CLOSED` events are dropped to prevent duplicate closure anomalies.
2. **Repetition Counter:** Every physical repetition uses the explicit `+ Next Rep` button, which updates the session ID (`exp_<timestamp>_rep<N>_<uuid>`).
3. **Persistence File:** All events are continuously appended to `persisted_experiment_history.csv` in internal app storage (`filesDir/experiments/`), preserving history across permission toggles and activity restarts.
4. **Export Validation:** Prior to any dataset merging, [`ExperimentDataValidator.validateRecords()`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/ExperimentDataValidator.kt) verifies ground-truth invariants, valid durations, and timestamp ordering.

---

## 11. Manual Physical Collection Protocol

Follow the step-by-step physical collection protocol on the Android test device:

| Step | Scenario Group | Lens | Reps | Physical Actions on Device | Export Verification |
| :---: | :--- | :---: | :---: | :--- | :--- |
| **1** | `NORMAL_FOREGROUND_CAMERA` | **Rear** | 5 | 1. Select scenario chip.<br>2. Select `Rear / Back Camera`.<br>3. Tap **START EXPERIMENT**.<br>4. Keep app open for 5 seconds.<br>5. Tap **STOP EXPERIMENT**.<br>6. Tap **+ Next Rep**.<br>7. Repeat 5 times (reps 1..5). | Verify 5 sessions with `camId='0'`, `isBack=TRUE`. |
| **2** | `NORMAL_FOREGROUND_CAMERA` | **Front** | 5 | 1. Select `Front / Selfie Camera`.<br>2. Tap **START EXPERIMENT**.<br>3. Keep app open for 5 seconds.<br>4. Tap **STOP EXPERIMENT**.<br>5. Tap **+ Next Rep**.<br>6. Repeat 5 times (reps 1..5). | Verify 5 sessions with `camId='1'`, `isBack=FALSE`. |
| **3** | `BACKGROUND_CAMERA_CONTINUATION` | **Rear** | 5 | 1. Select `Background Camera Continuation`.<br>2. Tap **START EXPERIMENT**.<br>3. Immediately press **HOME** button.<br>4. Wait 10 seconds in home/another app.<br>5. Switch back to Harness.<br>6. Tap **STOP EXPERIMENT**.<br>7. Tap **+ Next Rep**.<br>8. Repeat 5 times. | Verify `appVisibility='BACKGROUND'`, `fgs=true`. |
| **4** | `CAMERA_START_STOP` | **Rear** | 5 | 1. Select `Camera Start/Stop`.<br>2. Tap **START EXPERIMENT**.<br>3. After 2 seconds, tap **STOP EXPERIMENT**.<br>4. Tap **+ Next Rep**.<br>5. Repeat 5 times. | Verify single clean lifecycle closure. |
| **5** | `PERMISSION_DENIED` | None | 5 | 1. Revoke Camera permission in App Info settings.<br>2. Launch Harness.<br>3. Select `Permission Denied`.<br>4. Tap **START EXPERIMENT**.<br>5. Tap **+ Next Rep**.<br>6. Repeat 5 times. | Verify 0 camera events, `perm='DENIED'`. |
| **6** | `PERMISSION_GRANTED_NO_CAMERA` | None | 5 | 1. Re-grant Camera permission.<br>2. Select `Permission Granted / No Camera`.<br>3. Tap **START EXPERIMENT**.<br>4. Tap **+ Next Rep**.<br>5. Repeat 5 times. | Verify 0 camera events, `gt='NO_CAMERA_ACTIVITY'`. |
| **7** | `AMBIGUOUS_CONTEXT` | None | 3 | 1. Select `Ambiguous Context`.<br>2. Tap **START EXPERIMENT**.<br>3. Tap **+ Next Rep**.<br>4. Repeat 3 times. | Verify observation recorded, 0 camera events. |
| **8** | `AUTOMATED_BACKGROUND_TRIGGER` | **Rear** | 5 | 1. Select `Automated Background Trigger`.<br>2. Tap **ARM TRIGGER (5s)**.<br>3. Immediately press **HOME** button.<br>4. Wait 8 seconds for timer to fire in background.<br>5. Re-open Harness.<br>6. Tap **STOP EXPERIMENT**.<br>7. Tap **+ Next Rep**.<br>8. Repeat 5 times. | Verify `userAction='AUTOMATED_TRIGGER_FIRED'`. |
| **9** | Screen OFF Continuation | **Rear** | 3 | 1. Start `Background Camera Continuation`.<br>2. Press **POWER** button to turn screen OFF.<br>3. Wait 5 seconds.<br>4. Press **POWER** to wake device, unlock.<br>5. Tap **STOP EXPERIMENT**.<br>6. Tap **+ Next Rep**.<br>7. Repeat 3 times. | Check if camera remained active or OS closed it. |
| **10** | Export Session CSV | — | — | 1. Scroll to Export Card.<br>2. Tap **EXPORT EXPERIMENT CSV**.<br>3. Share or copy file from device `Downloads` / `filesDir`. | File saved as `cameraguard_experiment_<timestamp>.csv`. |

---

## 12. Target Repetition Counts Summary

| Category | Prior Sessions (Phase 4.6.1) | Target Additions (Phase 4.6.2) | Projected Total |
| :--- | :---: | :---: | :---: |
| Normal Foreground Camera (Rear) | 4 | 5 | **9** |
| Normal Foreground Camera (Front) | 0 | 5 | **5** |
| Background Camera Continuation | 2 | 5 | **7** |
| Camera Start / Stop Lifecycle | 1 | 5 | **6** |
| Permission Denied Controls | 3 | 5 | **8** |
| Permission Granted / No Camera Controls | 1 | 5 | **6** |
| Ambiguous Observation Controls | 1 | 3 | **4** |
| Automated Background Triggers | 0 | 5 | **5** |
| Screen OFF / Lock Tests | 0 | 3 | **3** |
| **Total Projected Physical Sessions** | **12** | **41** | **~53 sessions** |

---

## 13. Preparation for Phase 4.6.3 (Re-Audit)

Once the physical CSV is exported from the device and placed into `data/raw/`:
1. Phase 4.6.3 will invoke [`SessionReconstructor.reconstructSessions()`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/SessionReconstructor.kt) to aggregate events into sessions.
2. [`FeatureExtractor`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/FeatureExtractor.kt) will generate updated feature matrices.
3. [`DatasetReadinessAuditor`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/readiness/DatasetReadinessAuditor.kt) will re-evaluate:
   * Missingness and variance of $F_{01} \dots F_{03}$ (screen state).
   * Variance of $F_{10} \dots F_{11}$ (front vs rear camera).
   * Multiclass class support (verifying if rare classes now satisfy $N \ge 3$ or $N \ge 5$).
   * Feasibility of Stratified 5-Fold Cross-Validation.

Phase 4.6.2 infrastructure implementation is **COMPLETE and VALIDATED**.
