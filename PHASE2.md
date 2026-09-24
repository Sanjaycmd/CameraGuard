# CameraGuard — Phase 2: Persistent Camera Privacy Monitoring

"CameraGuard: A Context-Aware Machine Learning Framework for Detecting Suspicious Camera Usage on Android Smartphones"

---

## 1. System Architecture Overview

Phase 2 transitions CameraGuard from a minimal prototype into a complete, persistent, on-device Android camera privacy monitoring framework. The system is architected across four decoupled layers to maintain strict research validity:

```
+-------------------------------------------------------------------------------+
|                             Jetpack Compose UI Layer                          |
|  - DashboardScreen (Live service toggle, event metrics, permissions status)   |
|  - HistoryScreen (Filterable timeline, classification badges, rationale dialog)|
|  - Research Test Pipeline (Controlled synthetic event generation)             |
+---------------------------------------+---------------------------------------+
                                        | Observes via StateFlow / Repository
+---------------------------------------v---------------------------------------+
|                       Foreground Service & Notification Layer                 |
|  - CameraMonitoringService (Android 14 specialUse background persistence)     |
|  - NotificationHelper (Service channel + Heads-up alerts for unexpected events)|
+---------------------------------------+---------------------------------------+
                                        | Coordinates
+---------------------------------------v---------------------------------------+
|                    Monitoring, Telemetry & Detection Layer                    |
|  1. Raw Observation: CameraAvailabilityTracker (CameraManager callbacks)       |
|  2. Contextual Telemetry: ScreenStateTracker + ContextualInferenceEngine      |
|  3. Classification: CameraRuleEvaluator (EXPECTED / UNEXPECTED / UNKNOWN)     |
+---------------------------------------+---------------------------------------+
                                        | Persists
+---------------------------------------v---------------------------------------+
|                            Persistence Layer (Room)                           |
|  - CameraGuardDatabase ("cameraguard.db")                                     |
|  - CameraEventDao (Reactive Flows for counts and filtered timelines)          |
|  - CameraEventEntity (Stores raw observation, inference, & classification)     |
+-------------------------------------------------------------------------------+
```

---

## 2. Core Components

1. **`CameraAvailabilityTracker` (`org.cameraguard.monitoring`)**:
   - Manages `CameraManager.registerAvailabilityCallback` on the main Looper.
   - Listens for `onCameraUnavailable(cameraId)` and `onCameraAvailable(cameraId)`.
   - Gathers concurrent screen telemetry, queries inferred foreground context, measures evaluation latency, executes rule evaluation, and notifies observers.
2. **`ScreenStateTracker` (`org.cameraguard.monitoring.telemetry`)**:
   - Evaluates `PowerManager.isInteractive` and `KeyguardManager.isDeviceLocked`.
   - Distinguishes between `SCREEN_ON_UNLOCKED`, `SCREEN_ON_LOCKED`, and `SCREEN_OFF`.
3. **`ContextualInferenceEngine` (`org.cameraguard.monitoring.telemetry`)**:
   - Queries `UsageStatsManager.queryEvents` around the transition timestamp using a configurable correlation window ($\Delta t \in \{500\text{ ms}, 1\text{ s}, 2\text{ s}, 5\text{ s}\}$).
   - Identifies candidate packages through `UsageEvents.Event.ACTIVITY_RESUMED`.
   - Inspects `PackageManager.checkPermission(Manifest.permission.CAMERA, packageName)`.
   - Assigns explicit inference confidence (`HIGH`, `MEDIUM`, `LOW`, `NONE`) and inference method (`USAGE_STATS_ACTIVITY_RESUMED`, `NONE`).
4. **`CameraRuleEvaluator` (`org.cameraguard.monitoring.detection`)**:
   - Evaluates observable signals against configurable heuristic rules.
   - Outputs an `AccessClassification` (`EXPECTED`, `UNEXPECTED`, `UNKNOWN`) and human-readable explanation rationale.
5. **`CameraMonitoringService` (`org.cameraguard.monitoring.service`)**:
   - Android `Service` running in the foreground to prevent termination by the Low Memory Killer (LMK).
   - Configured for Android 14 (`targetSdk 34`) with `foregroundServiceType="specialUse"` and subtype metadata property.
6. **`NotificationHelper` (`org.cameraguard.monitoring.notification`)**:
   - Manages notification channels `camera_monitoring_service` (low priority) and `camera_alerts` (high priority).
   - Fires actionable heads-up notifications for `UNEXPECTED` camera events that deep-link to the History screen.
7. **`CameraGuardDatabase` & `CameraEventDao` (`org.cameraguard.data.db`)**:
   - Local SQLite database managed via Room.
   - Persists all event fields across activity destruction, app restart, and device reboot.
8. **`SyntheticEventGenerator` (`org.cameraguard.testing`)**:
   - Injects controlled simulated scenarios marked explicitly with `isSynthetic = true` to validate the UI, notification, and database pipeline without spoofing hardware signals.

---

## 3. Android APIs Used

| Android API | Purpose | Permissions / Requirements |
| :--- | :--- | :--- |
| `CameraManager.registerAvailabilityCallback` | Observes camera availability transitions | System-wide hardware callback (no camera permission needed for availability) |
| `PowerManager.isInteractive` | Evaluates if the device screen is active | Standard framework API |
| `KeyguardManager.isDeviceLocked` | Determines if the device is currently locked | Standard framework API |
| `UsageStatsManager.queryEvents` | Infers active foreground package within $\Delta t$ | `android.permission.PACKAGE_USAGE_STATS` (granted via Settings) |
| `PackageManager.checkPermission` | Checks if candidate package holds CAMERA | Standard framework query |
| `ServiceCompat.startForeground` | Keeps monitoring service alive in background | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` |
| `NotificationManager` | Posts service status and unexpected alerts | `POST_NOTIFICATIONS` (Android 13+, API 33+) |
| `RoomDatabase` (SQLite) | Persistent local storage of telemetry | Standard local app storage |

---

## 4. Research Methodology: Separation of Observation, Inference, and Classification

To preserve scientific rigor, CameraGuard maintains a strict separation across three layers:

```
[ LAYER 1: RAW OBSERVATION ]
  • rawEventType: CAMERA_BECAME_UNAVAILABLE | CAMERA_BECAME_AVAILABLE
  • timestamp: Epoch milliseconds
  • cameraId: Device camera identifier ("0", "1")
  • screenState: SCREEN_ON_UNLOCKED | SCREEN_ON_LOCKED | SCREEN_OFF

              ↓ (Contextual enrichment)

[ LAYER 2: INFERRED CONTEXT ]
  • inferredPackageName: Candidate top package or null
  • packageInferenceConfidence: HIGH | MEDIUM | LOW | NONE
  • inferenceMethod: USAGE_STATS_ACTIVITY_RESUMED | NONE
  • candidateHasCameraPermission: Boolean or null

              ↓ (Rule evaluation)

[ LAYER 3: CLASSIFICATION ]
  • classification: EXPECTED | UNEXPECTED | UNKNOWN
  • classificationExplanation: Transparent rationale
  • detectionLatencyMs: Measured processing time
  • isSynthetic: True if from testing pipeline, False for real hardware
```

### Classification Rules

1. **`UNEXPECTED`**:
   - Camera transitioned to unavailable while `screenState == SCREEN_OFF`.
   - Camera transitioned to unavailable while `screenState == SCREEN_ON_LOCKED`.
   - Inferred package holds `candidateHasCameraPermission == false`.
2. **`EXPECTED`**:
   - `rawEventType == CAMERA_BECAME_AVAILABLE` (hardware sensor released).
   - Camera transitioned to unavailable while `screenState == SCREEN_ON_UNLOCKED`, candidate package holds `candidateHasCameraPermission == true`, and inference confidence is `HIGH` or `MEDIUM`.
3. **`UNKNOWN`**:
   - `UsageStatsManager` access is missing or no `ACTIVITY_RESUMED` occurred within correlation window $\Delta t$.
   - Conflicting or inconclusive telemetry signals.

---

## 5. Technical Limitations & Research Boundaries

1. **No Physical Sensor Verification**:
   - Software cannot physically verify whether electrical power is flowing to the image sensor or if raw frames are captured.
   - Root-level, kernel, or compromised HAL malware could bypass framework-level callbacks.
   - CameraGuard's claim is strictly: *"Software-based detection of suspicious camera usage using Android OS/framework telemetry and contextual behavioral analysis."*
2. **Package Identity is Inferred, Not Ground Truth**:
   - Standard Android public APIs do not provide the package name of the active camera client (`onCameraOpened(cameraId, packageId)` is `@SystemApi` requiring system signature permission).
   - Package identification in CameraGuard is an inferred temporal correlation, not ground truth.
3. **UsageStats Latency & OEM Batching**:
   - OEMs may batch `UsageStatsManager` events with delays between 100ms and 1s.
   - Configurable correlation windows ($\Delta t \in \{500\text{ ms}, 1\text{ s}, 2\text{ s}, 5\text{ s}\}$) enable empirical measurement of correlation accuracy.
4. **Privacy Respect**:
   - CameraGuard does **NOT** capture, record, or transmit camera frames, audio, or user content. Only anonymized framework telemetry is stored.

---

## 6. Testing Procedure & Expected Behaviors

| Test ID | Scenario | Procedure | Expected Observation & Behavior |
| :--- | :--- | :--- | :--- |
| **A** | **Normal Camera App** | Launch system camera app while screen is ON. | `CAMERA_BECAME_UNAVAILABLE`, inferred app = camera package, `hasCameraPermission = true`. Classified as **`EXPECTED`**. No alert notification fired. |
| **B** | **Camera Active with Screen ON** | Open verified video call / barcode app with screen unlocked. | Classified as **`EXPECTED`**. Logged in Room database. |
| **C** | **Camera Unavailable with Screen OFF** | Trigger synthetic test event or background service camera acquisition while screen is dark. | Classified as **`UNEXPECTED`**. High-priority alert notification posted on `camera_alerts` channel. |
| **D** | **Unknown Foreground App** | Open camera when top application cannot be resolved. | Inferred package is `null`, confidence = `NONE`. Classified as **`UNKNOWN`** with explanation. |
| **E** | **Missing UsageStats Access** | Revoke Usage Access in Android Settings. Open camera. | Telemetry logs `UsageStats` unavailable. Classified as **`UNKNOWN`** (Screen ON) or **`UNEXPECTED`** (if Screen OFF). |
| **F** | **Missing Notification Permission** | Deny `POST_NOTIFICATIONS`. Trigger unexpected event. | Event is still correctly classified and persisted in Room database; notification post is safely skipped without crash. |
| **G** | **App Restart** | Record events, close app from Recents, relaunch. | All historical events and counters reload from Room database. |
| **H** | **Device Reboot** | Reboot test device. Relaunch CameraGuard. | Database records persist intact in SQLite file (`cameraguard.db`). |
| **I** | **Synthetic Test Event** | Tap "Simulate: Screen-Off Camera Access" on Dashboard. | Generates event with `isSynthetic = true`. Alert notification posted. "Purge Test Events" removes it without altering real logs. |
| **J** | **Rapid State Transitions** | Rapidly open and close camera app 5 times. | 10 distinct events logged (`CAMERA_BECAME_UNAVAILABLE` followed by `CAMERA_BECAME_AVAILABLE`). Each latency recorded. |

---

## 7. Build Verification

Build command:
```bash
./gradlew test assembleDebug
```
Output artifact:
```
app/build/outputs/apk/debug/app-debug.apk
```
Target platform configuration:
- `minSdk`: 26 (Android 8.0)
- `targetSdk`: 34 (Android 14)
- `compileSdk`: 36
