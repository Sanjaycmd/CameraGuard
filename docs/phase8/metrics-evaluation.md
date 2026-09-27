# CameraGuard — Phase 8.11 Evaluation & Metrics Report

**Document ID:** `CG-DOC-P8-012-METRICS`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** COMPLETED  
**Artifact:** `data/derived/phase8/metrics/phase8_metrics.json`  

---

## 1. Executive Summary

Phase 8 evaluates the performance of the CameraGuard privacy monitoring framework following the deployment of Candidate E (Adaptive Transition Corroboration and Classification Severity Decoupling).

The core research objective of Phase 8 was to resolve two critical real-world false alarm failure modes without degrading hardware acquisition detection recall (100.0%) and without employing hardcoded package whitelisting.

---

## 2. Key Performance Metrics Comparison

| Metric Dimension | Phase 6 Baseline | Pre-Phase 8 Physical Baseline | Phase 8 Verified |
| :--- | :--- | :--- | :--- |
| **Hardware Acquisition Recall** | 100.0% (Corpus A) / 94.67% (Corpus B) | 100.0% | **100.0%** |
| **Stock Camera Cold Launch FP Rate** | Untracked (cold launch race uncharacterized) | ~18.0% (Launcher3 FP) | **0.0%** (100% eliminated) |
| **Multitasking Transition Alert Rate** | Escalated to `UNEXPECTED` | ~25.0% (Spurious alarms) | **0.0%** (0 false heads-up alerts) |
| **Unauthorized Probe Retention Rate** | 100.0% | 100.0% | **100.0%** |
| **Screen-Off Violation Detection** | Immediate Rule 2 Alert | Immediate Rule 2 Alert | **Immediate Rule 2 Alert (0ms delay)** |
| **Screen-Locked Violation Detection** | Immediate Rule 4 Alert | Immediate Rule 4 Alert | **Immediate Rule 4 Alert (0ms delay)** |
| **Unit Test Suite Pass Rate** | 100.0% (277 tests) | 100.0% (277 tests) | **100.0% (290 tests)** |

---

## 3. Detailed Metric Breakdown

### 3.1 Hardware Acquisition Detection
- **Corpus A Binary Detection:** Accuracy = 100.0%, Precision = 100.0%, Recall = 100.0%, F1 = 1.0000.
- **Physical Hardware Acquisition Recall:** 100.0% across all 6 live physical camera sessions on vivo V2202.

### 3.2 False Positive Reduction in Daily Usage
- **Cold Launch Race Elimination:** On Android 15, the Camera HAL signals acquisition 484–773 ms before `ActivityTaskManager` updates `UsageStats`. By implementing an adaptive 500 ms lookahead corroboration window for unconfirmed candidates on active displays, false attribution to the preceding launcher application (`com.android.launcher3`) was completely eliminated (0/10 false alarms).
- **Multitasking Transition Decoupling:** Inconclusive attributions occurring during rapid UI transitions (e.g. Google Assistant invocation) are now classified as `AMBIGUOUS`. These are stored in Room SQLite database for auditability and displayed in the UI history, but produce **0 heads-up alarms**.

### 3.3 Security Invariants & Zero Delay Guarantees
- **Screen-Off / Device Locked:** Corroboration delay is strictly bypassed when `screenState != SCREEN_ON_UNLOCKED`. Latency to evaluate deterministic Rules 2 & 4 is 3.8–4.2 ms.
- **Warm Camera Fast-Path:** When a high-confidence candidate with confirmed camera permissions acquires the camera, corroboration is bypassed (latency 11.5 ms).
- **No Package Whitelisting:** Zero hardcoded string exceptions or blanket exemptions for `com.android.camera` or `com.android.launcher3` were introduced. All decisions remain grounded in observable Android framework telemetry (activity resumption timestamps, permission grants, and screen interactivity states).
