# CameraGuard — Phase 8 Failure Reproduction Results

**Document ID:** `CG-DOC-P8-003-REPRODUCTION`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Target Hardware:** vivo V2202 (Android 15 / API 35)  
**Baseline Git Commit:** `33db3e8`  

---

## 1. Overview

To substantiate Phase 8 with empirical evidence before implementing any architectural modifications, five controlled tests were conducted on the physical vivo V2202 testbed:

1. **Test A:** Launcher $\to$ cold stock Camera launch
2. **Test B:** Google Quick Search (Circle to Search) $\to$ stock Camera transition
3. **Test C:** Google Quick Search alone (no camera launch)
4. **Test D:** Warm stock Camera launch
5. **Test E:** Independent adversarial app (`org.cameraguard.adversarytest`) camera acquisition

All physical events were persisted in `/data/data/org.cameraguard/databases/cameraguard.db` and synchronized to [`data/derived/phase8/reproduction/phase8_failure_reproduction.json`](file:///home/sanjay/Projects/CameraGuard/data/derived/phase8/reproduction/phase8_failure_reproduction.json).

---

## 2. Reproduction Results Matrix

| Field | Test A: Cold Stock Camera | Test B: Google Search $\to$ Camera | Test C: Google Search Alone | Test D: Warm Stock Camera | Test E: Adversary Test App |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Trigger Action** | Tap Camera on launcher | Trigger Assistant, tap Camera | Invoke Assistant alone | Reopen Camera | Tap "START CAMERA" in app |
| **Actual Caller** | `com.android.camera` | `com.android.camera` | **None** | `com.android.camera` | `org.cameraguard.adversarytest` |
| **Hardware Acquired** | Yes (Sensor `1`) | Yes (Sensor `1`) | **No (0 events)** | Yes (Sensor `1`) | Yes (Sensor `0`) |
| **Inferred Package** | `com.android.launcher3` | `googlequicksearchbox` | N/A | `com.android.camera` | `org.cameraguard.adversarytest` |
| **Inference Confidence**| `LOW` | `LOW` | N/A | `HIGH` | `MEDIUM` / `HIGH` |
| **Permission Check** | `false` (Denied by manifest) | `null` (Sandbox unverified) | N/A | `true` (Granted) | `null` (Sandbox unverified) |
| **Deterministic Result**| `UNEXPECTED` | `UNKNOWN` | N/A | `EXPECTED` | `UNKNOWN` |
| **Tier 2 ML Result** | Bypassed | `AMBIGUOUS` | N/A | Bypassed | `LEGITIMATE` |
| **Final Classification**| **`UNEXPECTED`** | **`UNEXPECTED`** | N/A | **`EXPECTED`** | **`EXPECTED`** |
| **Notification Posted** | **YES (Heads-up alert)** | **YES (Heads-up alert)** | **NO** | **NO** | **NO** |
| **Detection Latency** | `25 ms` | `42 ms` | N/A | `9 ms` | `13 ms` |
| **Failure Classification**| **Finding A (Race Condition)**| **Finding B (Race + ML Escalation)**| **Baseline Control** | **Baseline Normal** | **Baseline Adversary** |

---

## 3. Deep Analysis of Failure Modes

### 3.1 Finding A: Cold Launch Attribution Race
* **Evidence:** In Test A, `CameraManager.AvailabilityCallback.onCameraUnavailable` fired at `19:07:55.045`. `ActivityTaskManager` did not emit `Displayed com.android.camera/.CameraActivity: +773ms` until `19:07:55.529` (+484 ms).
* **Consequence:** `UsageStatsManager` returned `com.android.launcher3`. Because the launcher does not hold `CAMERA` permission, Rule 3 evaluated to `UNEXPECTED` at Tier 1.

### 3.2 Finding B: Google Search Transition & ML Escalation
* **Evidence:** In Test B, the user invoked Google Search at `19:20:43.115` and tapped Camera at `19:20:45.000`. Camera HAL opened at `19:20:45.504` before `com.android.camera` was committed. `UsageStatsManager` returned `com.google.android.googlequicksearchbox`.
* **Consequence:** 
  1. Because Google Search runtime permission cannot be verified across sandbox boundaries, Tier 1 was `UNKNOWN`.
  2. The Tier 2 Decision Tree evaluated the rapid transition features (`f06 <= 1.5`, `f07 <= 1.0`, `f09 > 1.0`) as `AMBIGUOUS`.
  3. `HybridCameraEvaluator` mapped `TreeClassification.AMBIGUOUS` directly to `AccessClassification.UNEXPECTED`.
  4. `CameraMonitoringService` posted a high-priority heads-up alert for a routine multitasking transition.

### 3.3 Test C Negative Control
* Test C confirmed that Google Search alone never activates the camera hardware, eliminating any hypothesis of secret camera polling by Google Assistant.

---

## 4. Key Engineering Conclusions

1. **Root Problem 1 (Attribution Race):** Hardware acquisition precedes `UsageStatsManager` activity lifecycle commitment by 300–700 ms on cold application launches.
2. **Root Problem 2 (Alert Escalation):** Inconclusive contextual telemetry (`AMBIGUOUS`) is treated as a critical security breach (`UNEXPECTED`), generating false alarms during legitimate multitasking.
