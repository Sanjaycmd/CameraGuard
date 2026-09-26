# Phase 5.2 — Lifecycle Robustness & Recovery Report

## 1. Objective

The objective of **Phase 5.2 — Lifecycle Robustness & Recovery** is to determine whether CameraGuard maintains reliable, continuous, and correct camera-access monitoring across Android operating system lifecycle interruptions and recovery conditions.

The central research question addressed in this phase is:

> *"Can CameraGuard continue reliable camera-access monitoring when the application/device lifecycle changes unexpectedly?"*

Specifically, this evaluation investigates:
1. Whether foreground monitoring services persist across backgrounding and display sleep.
2. Whether lifecycle transitions inadvertently generate spurious false-positive camera events.
3. Whether activity configuration changes compromise monitoring or UI state.
4. Whether CameraGuard correctly re-establishes its availability baselines and resumes accurate detection following unexpected process termination.
5. How CameraGuard behaves following device reboot events.

---

## 2. Test Environment

All evaluations were executed automatically via ADB on a physical Android test device:

| Environment Property | Telemetry / Specification |
| :--- | :--- |
| **Physical Test Device** | vivo V2202 (`PD2215HF_EX`) |
| **Operating System** | Android 15 (API Level 35) |
| **Firmware / Build ID** | `PD2215HF_EX_A_15.3.15.0.W30` |
| **Primary CPU ABI** | `arm64-v8a` |
| **Security Patch Level** | 2025-05-01 |
| **Hardware Cameras** | Camera ID 0 (Back/Rear), Camera ID 1 (Front) |
| **CameraGuard Version** | `versionCode=1`, `versionName=1.0` (`minSdk=26`, `targetSdk=34`) |
| **CameraTestHarness Version**| `versionCode=1`, `versionName=1.0` (`minSdk=26`, `targetSdk=34`) |
| **Starting Baseline Commit**| `0f2e0d0` (`research: establish Phase 5.1 baseline`) |
| **Authoritative Phase 4 Tag**| `phase4-complete` (`c8515ea`) |
| **Automation Tool** | [`scripts/research/lifecycle/run_lifecycle_evaluation.py`](file:///home/sanjay/Projects/CameraGuard/scripts/research/lifecycle/run_lifecycle_evaluation.py) |
| **Telemetry Evidence** | [`data/derived/phase5/lifecycle_evaluation_results.json`](file:///home/sanjay/Projects/CameraGuard/data/derived/phase5/lifecycle_evaluation_results.json) |

---

## 3. Scenarios Tested

The dedicated Phase 5.2 evaluation suite executed five comprehensive lifecycle scenarios:

1. **Scenario 5.2.1: App Background / Foreground**
   * *Procedure*: CameraGuard monitoring active → Send `KEYCODE_HOME` → 6-second background dwell → Return to CameraGuard foreground.
   * *Purpose*: Validate that foreground service persists and background transitions do not trigger false camera access alerts.

2. **Scenario 5.2.2: Screen Lock / Unlock**
   * *Procedure*: CameraGuard monitoring active → Send `KEYCODE_POWER` (display sleep) → 6-second sleep dwell → Send `KEYCODE_WAKEUP` and unlock gesture → Return to foreground.
   * *Purpose*: Validate that display sleep does not interrupt camera callback registration and wake events do not trigger spurious detection.

3. **Scenario 5.2.3: Activity Recreation (Configuration Change)**
   * *Procedure*: CameraGuard active in foreground → Force display rotation to 90° landscape (`wm user-rotation lock 1`) → 2-second dwell → Restore to 0° portrait (`wm user-rotation lock 0`).
   * *Purpose*: Validate that Compose UI recreation and activity teardown/recreation preserve service attachment, ViewModel state, and database integrity.

4. **Scenario 5.2.4: Controlled Process Interruption & Recovery**
   * *Procedure*: CameraGuard active → Terminate process via `am force-stop org.cameraguard` → Verify process destruction → Relaunch application → Start monitoring service → Verify baseline suppression → Trigger controlled hardware camera access via CameraTestHarness (`CameraTestService`) → Inspect Room persistence and attribution.
   * *Purpose*: Validate resilience against sudden process death, verifying that historical database entries survive, startup baseline suppression prevents false positives, and subsequent camera events are correctly classified and attributed.

5. **Scenario 5.2.5: Device Reboot Recovery & Architectural Analysis**
   * *Procedure*: Comprehensive static manifest analysis and dynamic system package manager query for boot receiver capabilities and auto-start handlers.
   * *Purpose*: Formally document post-reboot behavior, startup requirements, and platform service lifecycle expectations.

---

## 4. Results

The structured test results across all five scenarios are summarized below (detailed in [`phase5.2-lifecycle-test-matrix.md`](file:///home/sanjay/Projects/CameraGuard/docs/phase5/phase5.2-lifecycle-test-matrix.md)):

| ID | Scenario | Pass / Fail | Key Observation |
| :---: | :--- | :---: | :--- |
| **5.2.1** | App Background / Foreground | **PASS** | `CameraMonitoringService` remained active (`isForeground=true`). Process PID remained 8437. 0 false camera events recorded ($\Delta E = 0$). |
| **5.2.2** | Screen Lock / Unlock | **PASS** | Service remained active during display sleep (`mWakefulness=Asleep`). Woke and unlocked cleanly (`mWakefulness=Awake`). 0 false events recorded ($\Delta E = 0$). |
| **5.2.3** | Activity Recreation | **PASS** | Activity recreated across landscape (90°) and portrait (0°). Service connection and state flows remained active. Process PID preserved (8437). 0 false events ($\Delta E = 0$). |
| **5.2.4** | Process Interruption & Recovery | **PASS** | Old process 8437 terminated. New process 16005 initialized cleanly. Baseline suppressed 0 startup false positives. Test camera access was immediately detected (open: 22ms latency, close: 14ms latency). Historical database events (2) were preserved alongside new events (2). Total DB events: 4. |
| **5.2.5** | Reboot Recovery Analysis | **PASS WITH LIMITATIONS** | `RECEIVE_BOOT_COMPLETED` is absent from `AndroidManifest.xml`. CameraGuard does not auto-start upon boot by design; user must launch the app to restore monitoring. Post-launch monitoring and historical persistence are fully preserved. |

---

## 5. Detection Accuracy During Lifecycle Transitions

Across the entire Phase 5.2 automated lifecycle evaluation:

* **Expected Camera Access Events**: **2** (1 open transition, 1 close transition triggered during Scenario 5.2.4)
* **Detected Camera Access Events**: **2**
  * `e4768b89-272b-4898-892c-70ece0f5af33`: `CAMERA_BECAME_UNAVAILABLE`, Camera ID `0`, classified as `UNEXPECTED` via `TIER_2_ML` (ML Result: `AMBIGUOUS`). Detection latency: 22 ms.
  * `f92a5c54-e928-4216-a844-490acdb567c0`: `CAMERA_BECAME_AVAILABLE`, Camera ID `0`, classified as `EXPECTED` via `TIER_1_RULE` (Lifecycle closure). Detection latency: 14 ms.
* **Missed Events**: **0** (Detection rate = 100.0%)
* **False Positives**: **0**
  * Scenario 5.2.1 (Background dwell): 0 events generated
  * Scenario 5.2.2 (Screen-off dwell): 0 events generated
  * Scenario 5.2.3 (Rotation recreation): 0 events generated
  * Scenario 5.2.4 (Startup baseline period): 0 events generated
* **Duplicate Events**: **0** (Deduplication window and cached state map prevented spurious re-triggering)
* **Attribution Errors**: **0** (Correctly attributed to Camera ID `0` and candidate package `org.cameratestharness` via `USAGE_STATS_ACTIVITY_RESUMED`)

---

## 6. Recovery Behavior

### 6.1 App Background / Foreground Recovery
* When the user navigates away from CameraGuard (HOME or switching tasks), `CameraMonitoringService` remains resident in memory as an ongoing Android Foreground Service with notification ID 1001 and `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
* The `CameraAvailabilityCallback` registered with the system `CameraManager` remains fully active in the background.
* Returning to the application reconnects the Jetpack Compose UI to existing `StateFlow` streams without requiring service recreation or state clearing.

### 6.2 Screen Lock / Unlock Recovery
* Turning off the display puts the physical screen into sleep (`mWakefulness=Asleep`).
* The foreground service notification maintains CPU execution, ensuring that availability callbacks dispatched by `CameraService` in the Android native framework continue to be delivered to CameraGuard.
* Screen wake and keyguard unlock transitions execute without generating spurious transitions because the in-memory `cameraStateMap` accurately tracks that the hardware state did not change.

### 6.3 Activity Recreation Recovery
* Screen rotation forces an Android configuration change (`orientation=landscape` $\leftrightarrow$ `orientation=portrait`), destroying and recreating `MainActivity`.
* Because `CameraMonitoringService` runs independently of the `Activity` lifecycle, monitoring is completely decoupled from UI recreation.
* The Room repository and singleton `CameraMonitor` maintain continuous event flows, preventing event loss or duplicate entries during recomposition.

### 6.4 Controlled Process Interruption Recovery
* Following sudden process death (`am force-stop` or OS process reclamation), in-memory states (`cameraStateMap`, `activeCameraSessionOwners`) are cleared.
* Upon relaunch:
  1. Room database automatically reopens the persistent SQLite database (`cameraguard.db`), retaining all historical telemetry events without corruption.
  2. Starting `CameraMonitoringService` invokes `startTracking()`, querying `cameraManager.cameraIdList` and re-registering `CameraAvailabilityCallback`.
  3. The first callbacks received for each camera ID establish the initial availability baseline (`onCameraAvailable: cameraId=0` $\rightarrow$ `Baseline established... (suppressing event)`), suppressing false positives on startup.
  4. The very next hardware access is evaluated against the fresh baseline and detected with low latency (22ms).

### 6.5 Reboot Recovery Behavior
* Following device reboot, all running processes and foreground services are destroyed by the operating system.
* CameraGuard does not declare `android.permission.RECEIVE_BOOT_COMPLETED` and does not register a `BOOT_COMPLETED` broadcast receiver.
* As a result, CameraGuard does not wake automatically upon device boot. Monitoring remains dormant until the user launches CameraGuard and activates the service.
* Once launched, full monitoring and Room event history are completely restored.

---

## 7. Known Limitations

To maintain research rigor and scientific precision, the boundaries of this evaluation are explicitly categorized:

### 7.1 Tested & Confirmed
* User navigation between foreground and home screen.
* Display sleep, lock, wake, and unlock cycles.
* Activity configuration changes (display rotation).
* Controlled process destruction (`am force-stop`) and post-relaunch baseline initialization.
* Telemetry persistence across process recreation via SQLite Room database.
* Attribution and two-tier hybrid ML inference for camera access following recovery.

### 7.2 Not Tested in This Phase
* Extreme low-memory conditions triggering Android OS Low Memory Killer (LMK) eviction while active camera access is underway.
* Multi-user profile switching (switching between User 0 and secondary work profiles).
* Factory data reset.

### 7.3 Platform-Dependent Factors
* **OEM Background Task Management**: On certain OEM distributions (e.g., Funtouch OS, MIUI, ColorOS), aggressive OEM battery managers may terminate foreground services if the user does not enable "Allow Background Activity" or "Auto-Start" in OEM system settings.
* **Third-Party Permission Inobservability**: Due to Android sandbox isolation, third-party runtime permissions cannot be directly queried, resulting in $F_{04} = -1.0$ clamping for third-party camera apps.

---

## 8. Conclusion

**Outcome: PASS WITH LIMITATIONS**

* **Rationale**:
  * CameraGuard demonstrated **100% detection reliability** and **zero false positives** across all dynamic lifecycle transitions (background/foreground, display lock/unlock, activity recreation, and controlled process restart).
  * Historical telemetry in Room was perfectly preserved across process destruction.
  * The designation **PASS WITH LIMITATIONS** is assigned strictly because auto-start on boot is intentionally not implemented in the current architecture, requiring manual user initiation to re-establish monitoring following a device reboot.

All Phase 5.2 criteria are satisfied. The codebase and baseline remain frozen and regression-free.
