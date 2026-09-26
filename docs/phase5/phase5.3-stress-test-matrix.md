# Phase 5.3 — Camera Event Stress Test Matrix

## 1. Overview

This document presents the structured stress testing matrix for **CameraGuard Phase 5.3 — Camera Event Stress Testing**.

All scenarios were executed directly on the physical Android test device (vivo V2202, Android 15, API 35) connected via ADB, using automated stress testing tooling ([`run_camera_stress_evaluation.py`](file:///home/sanjay/Projects/CameraGuard/scripts/research/stress/run_camera_stress_evaluation.py)) driving real hardware camera open/close sessions via CameraTestHarness ([`CameraTestService`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/CameraTestService.kt)).

---

## 2. Structured Test Matrix

| ID | Scenario | Target | Actual | Expected | Detected | Missed | Duplicates | Attribution Errors | Classification Errors | Status |
| :---: | :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **A** | Single Event Control | 1 camera open → close cycle | 1 complete session (open=2.0s, close=1.0s) | 2 | 2 | 0 | 0 | 0 | 0 | **PASS** |
| **B** | Repeated Sequential Events (5) | 5 sequential camera sessions | 5 complete sessions (open=1.5s, pause=1.0s) | 10 | 10 | 0 | 0 | 0 | 0 | **PASS** |
| **C** | Higher-Volume Sequential Events (20) | 20 sequential camera sessions | 20 complete sessions (open=1.5s, pause=1.0s) | 40 | 40 | 0 | 0 | 0 | 0 | **PASS** |
| **D** | Rapid Transitions | 5 rapid camera open/close transitions (sub-second) | 5 fast sessions (open=0.8s, pause=0.5s) | 10 | 10 | 0 | 0 | 0 | 0 | **PASS** |
| **E** | Burst Sequence | 5 rapid cycles followed by 5s idle dwell | 5 fast sessions (open=0.8s, pause=0.5s) + 5s settle | 10 | 10 | 0 | 0 | 0 | 0 | **PASS** |
| **F** | Repeated Same-Owner Activity | 5 repeated sessions under same application ownership | 5 complete sessions under `org.cameratestharness` | 10 | 10 | 0 | 0 | 0 | 0 | **PASS** |
| **G** | Stress After CameraGuard Restart | Kill process, restart CameraGuard, verify baseline suppression, 3 sessions | `am force-stop`, relaunch, start service, 3 sessions (open=1.5s, pause=1.0s) | 6 | 6 | 0 | 0 | 0 | 0 | **PASS** |
| **ALL**| **Total Stress Suite** | **44 total hardware camera acquisition sessions** | **44 executed sessions across 7 scenarios** | **88** | **88** | **0** | **0** | **0** | **0** | **PASS** |

---

## 3. Metric Calculations

Following the specification in Phase 5.3 Step 5:

| Metric | Formula | Measured Value | Percentage |
| :--- | :--- | :---: | :---: |
| **Detection Rate** | $\frac{\text{Detected Expected Events}}{\text{Expected Events}}$ | $\frac{88}{88}$ | **100.0%** |
| **Miss Rate** | $\frac{\text{Missed Expected Events}}{\text{Expected Events}}$ | $\frac{0}{88}$ | **0.0%** |
| **Duplicate Rate** | $\frac{\text{Duplicate Events}}{\text{Observed Events}}$ | $\frac{0}{88}$ | **0.0%** |
| **Attribution Error Rate** | $\frac{\text{Incorrectly Attributed Events}}{\text{Detected Events}}$ | $\frac{0}{88}$ | **0.0%** |
| **Classification Error Rate**| $\frac{\text{Incorrect Classifications}}{\text{Evaluable Events}}$ | $\frac{0}{88}$ | **0.0%** |
| **Event-Order Integrity** | Percentage of event sequences preserving strictly non-decreasing timestamps | $\frac{88}{88}$ | **100.0%** |
| **Persistence Integrity** | Expected persisted sessions vs actual persisted sessions in Room SQLite DB | $\frac{44}{44}$ | **100.0%** |

---

## 4. Scenario Latency & Timing Breakdown

| Scenario ID | Duration (s) | Avg Detection Latency | Max Detection Latency | Min Interval Between Events |
| :---: | :---: | :---: | :---: | :---: |
| **A** | 7.02s | 2.5 ms | 4 ms | 2,055 ms |
| **B** | 16.94s | 2.7 ms | 5 ms | 1,029 ms |
| **C** | 58.95s | 3.8 ms | 26 ms | 988 ms |
| **D** | 13.17s | 1.9 ms | 4 ms | 496 ms |
| **E** | 15.96s | 2.6 ms | 5 ms | 498 ms |
| **F** | 19.03s | 4.0 ms | 11 ms | 1,041 ms |
| **G** | 16.48s | 9.8 ms | 24 ms | 1,022 ms |

*Detailed event-level telemetry is preserved in [`data/derived/phase5/camera_stress_evaluation_results.json`](file:///home/sanjay/Projects/CameraGuard/data/derived/phase5/camera_stress_evaluation_results.json).*
