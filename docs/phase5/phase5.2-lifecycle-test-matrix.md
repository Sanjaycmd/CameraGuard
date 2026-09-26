# Phase 5.2 — Lifecycle Robustness Test Matrix

## 1. Overview

This document provides the structured test execution matrix for **CameraGuard Phase 5.2 — Lifecycle Robustness & Recovery**.

All tests were executed on the physical Android test device (vivo V2202, Android 15, API 35) connected via ADB, using automated execution tooling ([`run_lifecycle_evaluation.py`](file:///home/sanjay/Projects/CameraGuard/scripts/research/lifecycle/run_lifecycle_evaluation.py)) and controlled camera acquisition driven by the CameraTestHarness ([`CameraTestService`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/CameraTestService.kt)).

---

## 2. Structured Test Matrix

| ID | Scenario | Lifecycle Action | Expected Result | Actual Result | Pass/Fail | Evidence |
| :--- | :--- | :--- | :--- | :--- | :---: | :--- |
| **5.2.1** | App Background / Foreground | CameraGuard active → `KEYCODE_HOME` → 6s background dwell → Return to CameraGuard foreground | Foreground service persists in background, resumes foreground cleanly, zero false camera events, process PID unchanged | Service remained active in BG (`isForeground=true`) & FG (`isForeground=true`). Event delta: 0 (events: 2 → 2). Process PID unchanged (PID: 8437 → 8437). Top activity resumed cleanly. | **PASS** | Initial DB events: 2, Final DB events: 2 ($\Delta = 0$). PID before/after: 8437. Resumed activity: `org.cameraguard/.MainActivity`. |
| **5.2.2** | Screen Lock / Unlock | CameraGuard active → Power off (`KEYCODE_POWER`) → 6s dwell in sleep → Wake (`KEYCODE_WAKEUP`) & unlock swipe | Foreground service persists through sleep/wake, zero false camera events generated during display sleep, monitoring valid | Display entered sleep (`mWakefulness=Asleep`). Service remained active during sleep (`isForeground=true`). Display woke and unlocked (`mWakefulness=Awake`, `isKeyguardShowing=false`). Event delta: 0 (events: 2 → 2). | **PASS** | Dumpsys power: `mWakefulness=Asleep` → `mWakefulness=Awake`. Event count delta: 0. Service active in sleep and wake. |
| **5.2.3** | Activity Recreation (Configuration Change) | CameraGuard active in foreground → Rotate landscape (`wm user-rotation lock 1`) → 2s dwell → Rotate portrait (`wm user-rotation lock 0`) | Activity handles configuration change and recreation cleanly, service persists, zero false events, PID unchanged | Activity recreated and resumed. Service remained active in landscape and portrait (`isForeground=true`). Event delta: 0 (events: 2 → 2). Process PID unchanged (PID: 8437). | **PASS** | Display orientation: 90° landscape → 0° portrait. Top activity: `MainActivity`. Event delta: 0. PID before/after: 8437. |
| **5.2.4** | Controlled Process Interruption & Recovery | CameraGuard active → `am force-stop` → Verify PID killed → Relaunch app → Start Service → Trigger controlled camera event via CameraTestHarness | Clean restart, baseline suppression without false events, correct detection and attribution of subsequent camera access, database history preserved | Old PID 8437 terminated. New PID: 16005. Baseline established with 0 false positives. CameraTestHarness camera access detected on Camera ID 0: Open event recorded as UNEXPECTED (Tier-2 ML, latency 22ms), Close event recorded as EXPECTED (Tier-1 Rule, latency 14ms). Total DB events: 2 → 4. | **PASS** | Terminated PID: 8437. New PID: 16005. Baseline FPs: 0. New events recorded: 2. Open ID: `e4768b89-...` (Camera 0, `org.cameratestharness`). Close ID: `f92a5c54-...`. |
| **5.2.5** | Device Reboot Recovery & Architectural Analysis | System boot capability audit & architectural design inspection | Document auto-start capability; verify application requirements post-reboot without ungranted features | Auto-start on boot: **NOT IMPLEMENTED** (`RECEIVE_BOOT_COMPLETED` permission and receiver omitted by design). Post-reboot monitoring requires manual application launch. Post-launch monitoring and historical persistence recovery verified. | **PASS WITH LIMITATIONS** | Manifest inspection: `RECEIVE_BOOT_COMPLETED` absent (`false`). Package manager: 0 boot receivers registered. Architectural classification: User-Initiated Foreground Service (No Boot Daemon). |

---

## 3. Detailed Event Validation Telemetry (Scenario 5.2.4)

| Field | Open Transition ($T_{open}$) | Close Transition ($T_{close}$) |
| :--- | :--- | :--- |
| **Event ID** | `e4768b89-272b-4898-892c-70ece0f5af33` | `f92a5c54-e928-4216-a844-490acdb567c0` |
| **Timestamp (Unix ms)** | 1790434938973 | 1790434942217 |
| **Raw Event Type** | `CAMERA_BECAME_UNAVAILABLE` | `CAMERA_BECAME_AVAILABLE` |
| **Camera ID** | `0` (Hardware Rear Camera) | `0` (Hardware Rear Camera) |
| **Screen State** | `SCREEN_ON_UNLOCKED` | `SCREEN_ON_UNLOCKED` |
| **Inferred Package** | `org.cameratestharness` | `org.cameratestharness` |
| **Package Confidence** | `LOW` | `LOW` |
| **Inference Method** | `USAGE_STATS_ACTIVITY_RESUMED` | `USAGE_STATS_ACTIVITY_RESUMED` |
| **Classification** | `UNEXPECTED` | `EXPECTED` |
| **Tier Used** | `TIER_2_ML` | `TIER_1_RULE` |
| **Deterministic Result**| `UNKNOWN` | `EXPECTED` |
| **ML Result** | `AMBIGUOUS` | `null` (ML not invoked) |
| **ML Invoked** | `true` | `false` |
| **Detection Latency** | 22 ms | 14 ms |
| **Historical Data** | Preserved (2 pre-restart events intact) | Preserved |

---

## 4. Test Matrix Summary

* **Total Scenarios Evaluated**: 5
* **Scenarios Passed**: 4 (`5.2.1`, `5.2.2`, `5.2.3`, `5.2.4`)
* **Scenarios Passed with Limitations**: 1 (`5.2.5` — Auto-start on boot intentionally not implemented in architecture)
* **Scenarios Failed**: 0
* **False Positives Observed**: 0
* **Missed Events**: 0
* **Attribution Errors**: 0
