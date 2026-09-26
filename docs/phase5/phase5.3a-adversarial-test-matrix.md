# CameraGuard Phase 5.3-A — Adversarial Test Matrix

## Privacy Indicator vs. CameraGuard Detection Comparison

This document provides the structured comparison matrix for **Phase 5.3-A — Privacy Indicator / Adversarial Camera Access Evaluation**, conducted on a physical test device running Android 15 (API 35).

---

### Core Security Evaluation Matrix

| Scenario | Scenario Description | Access Result (Ground Truth) | Android Privacy Indicator | CameraManager Callback Event | CameraGuard Detection | Attributed Package | Classification Result | Detection Latency | Tier Used |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **A1** | Foreground intentional control (Control baseline) | `SUCCESS` | `VISIBLE` | `CAMERA_BECAME_UNAVAILABLE` | **Detected** (`true`) | `org.cameraguard.adversarytest` | `EXPECTED` | 5 ms | `TIER_2_ML` |
| **A2** | Background camera access attempt (No FGS) | `BLOCKED_BY_PLATFORM` (`ERROR_CAMERA_DISABLED`) | `NOT_APPLICABLE` (Access prevented) | `CAMERA_BECAME_UNAVAILABLE` (Brief HAL probe) | **Detected** (`true` probe) | `org.cameraguard` | `UNEXPECTED` | 9 ms | `TIER_1_RULE` (Rule 3) |
| **A3** | Access immediately after leaving foreground | `SUCCESS` (Transitional window) | `VISIBLE` | `CAMERA_BECAME_UNAVAILABLE` | **Detected** (`true`) | `org.cameraguard` | `UNEXPECTED` | 4 ms | `TIER_1_RULE` (Rule 3) |
| **A4** | Service / background component attempt (Camera FGS) | `SUCCESS` | `VISIBLE` | `CAMERA_BECAME_UNAVAILABLE` | **Detected** (`true`) | `org.cameraguard` | `UNEXPECTED` | 6 ms | `TIER_1_RULE` (Rule 3) |
| **A5** | Screen-locked attempt | `SUCCESS` | `VISIBLE` | `CAMERA_BECAME_UNAVAILABLE` | **Detected** (`true`) | `org.cameraguard.adversarytest` | `UNEXPECTED` | 8 ms | `TIER_1_RULE` (Rule 1) |
| **A6** | Rapid suspicious start/stop burst (3 cycles) | `SUCCESS` (3/3 cycles) | `VISIBLE` | `CAMERA_BECAME_UNAVAILABLE` (x3) | **Detected** (3/3 cycles) | `org.cameraguard.adversarytest` | `EXPECTED` | 4 ms | `TIER_2_ML` |

---

### Summary Telemetry Metrics

* **Total Scenarios Evaluated**: 6
* **Successful Camera Accesses**: 5 (`A1`, `A3`, `A4`, `A5`, `A6`)
* **Platform-Blocked Attempts**: 1 (`A2`)
* **CameraGuard Detections**: 5 / 5 (100.0%)
* **Missed Successful Accesses**: 0 / 5 (0.0%)
* **False Positives**: 0
* **Attribution Errors**: 2 / 5 (40.0% — observed in `A3` and `A4` due to `UsageStatsManager` attributing the newly resumed foreground application rather than the background camera caller)
* **Classification Errors**: 0 / 5 (0.0% — `A1` and `A6` classified `EXPECTED` as interactive foreground usage; `A3`, `A4`, and `A5` classified `UNEXPECTED`)
* **Indicator-Independent Detection**: `N/A — no successful indicator-absent camera access was reproduced.`
* **Average Detection Latency**: 6.0 ms across all evaluated scenarios
