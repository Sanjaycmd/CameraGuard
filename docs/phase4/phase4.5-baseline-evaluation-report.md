# CameraGuard Phase 4.5 — Baseline Evaluation Report

**Document ID:** `CG-DOC-P45-001`  
**Phase:** Phase 4.5 (Phase 2 Deterministic Baseline Evaluation)  
**Status:** Completed & Validated  
**Target Repository:** `Sanjaycmd/CameraGuard`  
**Modules Referenced:** `:app` (CameraGuard production baseline), `:camera-test-harness` (Controlled experiments & evaluation adapter)  
**Author:** CameraGuard Research & Engineering Team  
**Date:** September 25, 2026  

---

## 1. Executive Summary

Phase 4.5 establishes a scientifically rigorous, reproducible empirical baseline for CameraGuard's existing **Phase 2 deterministic rule engine**. Prior to implementing any machine learning architectures (Phase 4.6) or hybrid detection systems (Phase 4.7), it is essential to measure exactly how the non-ML rule system performs on the physically collected, reconstructed dataset from Phase 4.3 and Phase 4.4.

The purpose of this evaluation is **not** to prove ML is superior; rather, it is to provide an unvarnished, empirical measurement of what the rule-based engine accomplishes, where it produces decisive outcomes, and where framework constraints cause indeterminate classifications.

### Key Empirical Findings:
1. **Zero False Alarms on Negative Controls (100% Specificity):** Across all 8 negative control sessions (`PERMISSION_DENIED`, `NO_CAMERA_ACTIVITY`, observation-only), the system produced **0 false alarms** because the Phase 2 engine only triggers upon hardware availability transitions from Android's `CameraManager`.
2. **The Production Observability Sandbox Barrier:** In strict production mode (`strictProductionObservability = true`), an unprivileged application cannot query the runtime permissions of third-party applications across the Android sandbox boundary. Because `hasCameraPermission` is strictly `UNVERIFIED` (`null`), Rule 4 (`EXPECTED`) cannot fire. Consequently, **100% of camera activations (5/5)** fall through to Rule 5: `UNKNOWN` (Indeterminate rate = 100%, Coverage = 0%).
3. **Oracle Permission Benchmark:** If permission observability is granted (e.g. via device-owner or system-level inspection), the baseline achieves **60.0% coverage (3/5)** on camera activations, correctly classifying normal foreground sessions as `EXPECTED`.
4. **The Multitasking / Background Continuation Blind Spot:** Legitimate background camera continuation sessions (e.g. video streaming while multitasking) result in `confidence = LOW` due to activity pause transitions, causing Rule 4 to fail and falling through to `UNKNOWN` even when permissions are known.
5. **Strict Zero Production Mutation:** All evaluations were executed via the deterministic research adapter [`Phase2RuleAdapter`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/Phase2RuleAdapter.kt), leaving `:app` and the Phase 2 commit (`7987c02`, `v1.0.0`) 100% untouched.

---

## 2. Phase 2 Baseline Implementation Inspected

The source of truth for CameraGuard's baseline is the Phase 2 implementation in `:app`:
* [`CameraRuleEvaluator.kt`](file:///home/sanjay/Projects/CameraGuard/app/src/main/java/org/cameraguard/monitoring/detection/CameraRuleEvaluator.kt)
* [`CameraAvailabilityTracker.kt`](file:///home/sanjay/Projects/CameraGuard/app/src/main/java/org/cameraguard/monitoring/CameraAvailabilityTracker.kt)
* [`ContextualInferenceEngine.kt`](file:///home/sanjay/Projects/CameraGuard/app/src/main/java/org/cameraguard/monitoring/telemetry/ContextualInferenceEngine.kt)
* [`ScreenStateTracker.kt`](file:///home/sanjay/Projects/CameraGuard/app/src/main/java/org/cameraguard/monitoring/telemetry/ScreenStateTracker.kt)

### Production Telemetry Event Model
When the camera hardware state changes, Android's `CameraManager.AvailabilityCallback` invokes `onCameraUnavailable(cameraId)` or `onCameraAvailable(cameraId)`. The tracker queries:
1. `ScreenStateTracker.currentScreenState()` $\rightarrow$ `ScreenInteractivityState` (`SCREEN_ON_UNLOCKED`, `SCREEN_ON_LOCKED`, `SCREEN_OFF`).
2. `ContextualInferenceEngine.inferForegroundPackage(timestamp)` $\rightarrow$ `InferredPackageContext` containing:
   * `packageName`: Candidate application ID attributed via `UsageStatsManager` lookback.
   * `confidence`: Ordinal rating (`NONE`, `LOW`, `MEDIUM`, `HIGH`) based on time delta from `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED`.
   * `method`: `InferenceMethod` (`USAGE_STATS_ACTIVITY_RESUMED`, `USAGE_STATS_ACTIVITY_PAUSED`, etc.).
   * `hasCameraPermission`: `Boolean?` (runtime permission check; `null` if unverified).

---

## 3. Exact Baseline Decision Logic

The baseline evaluates a cascading sequence of 5 deterministic rules in [`CameraRuleEvaluator.evaluate()`](file:///home/sanjay/Projects/CameraGuard/app/src/main/java/org/cameraguard/monitoring/detection/CameraRuleEvaluator.kt#L29):

```
                                    Raw Camera Event
                                           │
                        ┌──────────────────┴──────────────────┐
                        │ rawEventType == AVAILABLE?          │ ──► [EXPECTED] (Lifecycle Closure)
                        └──────────────────┬──────────────────┘
                                           │ NO (UNAVAILABLE)
                        ┌──────────────────┴──────────────────┐
                        │ Rule 1: screenState == SCREEN_OFF?  │ ──► [UNEXPECTED] (Alert: Screen Off)
                        └──────────────────┬──────────────────┘
                                           │ NO
                        ┌──────────────────┴──────────────────┐
                        │ Rule 2: screenState == LOCKED?      │ ──► [UNEXPECTED] (Alert: Device Locked)
                        └──────────────────┬──────────────────┘
                                           │ NO
                        ┌──────────────────┴──────────────────┐
                        │ Rule 3: hasCameraPermission == FALSE│ ──► [UNEXPECTED] (Alert: Missing Permission)
                        └──────────────────┬──────────────────┘
                                           │ NO
                        ┌──────────────────┴──────────────────┐
                        │ Rule 4: screen == ON_UNLOCKED  AND  │
                        │         hasPermission == TRUE  AND  │ ──► [EXPECTED] (Expected User Capture)
                        │         confidence in [HIGH, MEDIUM]│
                        └──────────────────┬──────────────────┘
                                           │ NO
                                           ▼
                                 [UNKNOWN / INCONCLUSIVE]
```

### Explanation of Decision Outcomes:
* **`EXPECTED`:** Camera access is verified as plausible user interaction (active unlocked screen, verified permission, high/medium activity correlation) or normal lifecycle closure.
* **`UNEXPECTED`:** Camera access occurs under conditions strongly conflicting with normal user operation (screen turned off, device locked, or candidate app explicitly lacks CAMERA permission).
* **`UNKNOWN`:** Inconclusive telemetry. The framework cannot verify candidate permission from the sandbox, confidence is low, or UsageStats correlation is outside the time window.

---

## 4. Evaluation Population & Exclusion Criteria

Following the dataset audit established in Phase 4.3 and feature schema in Phase 4.4, the 16 reconstructed sessions are partitioned into evaluation sets:

| Category | Session Count | Percentage | Description / Composition |
|---|---|---|---|
| **Total Ingested Sessions** | 16 | 100.0% | Reconstructed from 109 raw telemetry records in `data/raw/*.csv`. |
| **Excluded from Evaluation** | 3 | 18.8% | Pre-Phase-3.5.1 legacy contradiction runs: `4e75d0`, `0f3f21` (contradictory permission state), `1b68ae` (legacy camera activation during ambiguous scenario). |
| **Included Baseline Sessions** | **13** | **81.3%** | Clean, physically validated sessions meeting all ML and evaluation criteria. |
| ├── **Camera-Activation Subset** | 5 | 38.5% | Sessions where physical camera acquisition occurred (`OPENED_AND_CLOSED`). |
| └── **No-Camera Control Subset** | 8 | 61.5% | Negative controls where no camera acquisition occurred on the device. |

---

## 5. Session-Level Evaluation Methodology

To prevent performance inflation, **the primary evaluation unit is the logical session** ($N = 13$ included sessions), not individual event rows. 

Treating multiple telemetry records within the same session (e.g. `CAMERA_OPENED`, `CAPTURE_SESSION_STARTED`, `CAMERA_STOP_REQUESTED`, `CAMERA_CLOSED`) as independent samples would improperly inflate accuracy metrics. Each session is evaluated at its primary trigger point ($T_0$), mirroring the real-time decision CameraGuard makes upon camera acquisition.

### Target Mapping
Ground truth contexts from the research test harness are evaluated as follows:
* `USER_INITIATED_FOREGROUND`: Legitimate user-initiated foreground capture.
* `USER_INITIATED_BACKGROUND_CONTINUATION`: Legitimate multitasking foreground-service continuation.
* `PERMISSION_DENIED`: Negative control (camera acquisition blocked by OS).
* `NO_CAMERA_ACTIVITY`: Negative control (permission held, camera idle).
* `AMBIGUOUS_CONTEXT`: Observation-only context evaluation without camera opening.
* `AUTOMATED_BACKGROUND_TRIGGER`: Controlled activation without explicit user start action.

---

## 6. Primary Evaluation Results

The evaluation results generated by [`BaselineEvaluator`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/experiment/BaselineEvaluator.kt) are exported to `data/derived/phase4/baseline/`:

### A. Strict Production Observability Mode (Real-World Sandbox)

In this mode, `hasCameraPermission` is strictly `UNVERIFIED` (`null`), reflecting the actual unprivileged Android sandbox:

| Metric Name | Value | Denominator | Rate / Pct | Scientific Interpretation |
|---|---|---|---|---|
| **Total Included Sessions** | 13 | 13 | 100.0% | Primary evaluation population |
| **Camera Activation Sessions** | 5 | 13 | 38.5% | Sessions where camera became unavailable |
| **No-Camera Control Sessions** | 8 | 13 | 61.5% | Negative controls |
| **Activation Decisive Count** | 0 | 5 | **0.0%** | Decisive classifications (`EXPECTED`/`UNEXPECTED`) |
| **Activation Indeterminate Rate** | 5 | 5 | **100.0%** | Telemetry falling through to `UNKNOWN` |
| **Overall Expected Count** | 0 | 13 | 0.0% | Classified as EXPECTED |
| **Overall Unexpected Count** | 0 | 13 | 0.0% | Classified as UNEXPECTED (Alerts) |
| **Overall Unknown Count** | 5 | 13 | 38.5% | Inconclusive sessions |
| **Overall No-Camera Count** | 8 | 13 | 61.5% | Correct silence (no event triggered) |
| **Operational Alert Rate** | 0 | 13 | **0.0%** | Sessions triggering active user alerts |
| **False Positive Count** | 0 | 13 | **0** | Zero false alarms triggered |

### B. Oracle Permission Mode (Privileged Permission Benchmark)

If CameraGuard has access to the target app's internal runtime permission (`hasCameraPermission = true`):

| Metric Name | Value | Denominator | Rate / Pct | Scientific Interpretation |
|---|---|---|---|---|
| **Activation Decisive Count** | 3 | 5 | **60.0%** | Normal foreground sessions classified as `EXPECTED` |
| **Activation Indeterminate Rate** | 2 | 5 | **40.0%** | Background continuations resulting in `UNKNOWN` |
| **Overall Expected Count** | 3 | 13 | 23.1% | Verified foreground sessions |
| **Overall Unknown Count** | 2 | 13 | 15.4% | Background continuations |
| **Overall No-Camera Count** | 8 | 13 | 61.5% | Correct silence |
| **Operational Alert Rate** | 0 | 13 | 0.0% | Zero false alarms triggered |

---

## 7. Multiclass Confusion Matrix

The multiclass confusion matrix maps Ground Truth Context against Baseline Predictions across all 13 included sessions:

### Production Observability Mode Matrix
From [`baseline_confusion_matrix.csv`](file:///home/sanjay/Projects/CameraGuard/data/derived/phase4/baseline/baseline_confusion_matrix.csv):

| Ground Truth Context | Pred: EXPECTED | Pred: UNEXPECTED | Pred: UNKNOWN | Pred: NO_CAMERA_EVENT | Total Support |
|---|---|---|---|---|---|
| `USER_INITIATED_FOREGROUND` | 0 | 0 | **3** | 3* | **6** |
| `USER_INITIATED_BACKGROUND_CONTINUATION` | 0 | 0 | **2** | 0 | **2** |
| `PERMISSION_DENIED` | 0 | 0 | 0 | **3** | **3** |
| `NO_CAMERA_ACTIVITY` | 0 | 0 | 0 | **1** | **1** |
| `AMBIGUOUS_CONTEXT` | 0 | 0 | 0 | **1** | **1** |
| **Total** | **0** | **0** | **5** | **8** | **13** |

*\*Note: 3 sessions under `USER_INITIATED_FOREGROUND` were UI launch / permission evaluations where camera acquisition was not initiated.*

---

## 8. Per-Scenario Analysis

Detailed performance breakdown by evaluation scenario across all 13 included sessions:

| Scenario ID | Total Sessions | Camera Acquisitions | Pred: EXPECTED | Pred: UNEXPECTED | Pred: UNKNOWN | Pred: NO_CAMERA | Decisive Rate |
|---|---|---|---|---|---|---|---|
| `NORMAL_FOREGROUND_CAMERA` | 5 | 2 | 0 (2 oracle) | 0 | 2 (0 oracle) | 3 | 0.0% (100% oracle) |
| `BACKGROUND_CAMERA_CONTINUATION` | 1 | 1 | 0 | 0 | 1 | 0 | 0.0% |
| `CAMERA_START_STOP` | 1 | 1 | 0 (1 oracle) | 0 | 1 (0 oracle) | 0 | 0.0% (100% oracle) |
| `CAMERA_SESSION_CLOSED` | 1 | 1 | 0 (1 oracle) | 0 | 1 (0 oracle) | 0 | 0.0% (100% oracle) |
| `PERMISSION_DENIED` | 3 | 0 | 0 | 0 | 0 | 3 | 100.0% (Silent) |
| `PERMISSION_GRANTED_NO_CAMERA` | 1 | 0 | 0 | 0 | 0 | 1 | 100.0% (Silent) |
| `AMBIGUOUS_CONTEXT` | 1 | 0 | 0 | 0 | 0 | 1 | 100.0% (Silent) |

### Key Scenario Insights:
1. **`NORMAL_FOREGROUND_CAMERA`:** Correctly produces no false alerts. In production mode, camera activations result in `UNKNOWN` due to unverified third-party permissions. In oracle mode, they evaluate 100% as `EXPECTED`.
2. **`BACKGROUND_CAMERA_CONTINUATION`:** Demonstrates the core research challenge. Even in oracle mode, background continuation results in `UNKNOWN` because the activity state is `STOPPED`/`PAUSED`, dropping `package_inference_confidence` to `LOW`.
3. **`PERMISSION_DENIED` & `NO_CAMERA_ACTIVITY`:** Handled with 100% precision. The absence of camera callbacks guarantees zero false alerts.

---

## 9. False-Positive and False-Negative Analysis

### A. False-Positive Analysis
* **Definition:** Baseline output indicating `UNEXPECTED` (alert) when the ground truth is legitimate or inactive.
* **Empirical Count:** **0 false positives across all 13 sessions.**
* **Mechanism:** Rule 1 (Screen Off) and Rule 2 (Device Locked) did not trigger on legitimate sessions because the screen was active. Rule 3 (Missing Permission) did not trigger because permission was not explicitly false.

### B. False-Negative Analysis
* **Definition:** Baseline output failing to trigger `UNEXPECTED` on unauthorized or anomalous background camera activity.
* **Observation:** In the controlled `AUTOMATED_BACKGROUND_TRIGGER` scenario (synthetic test), if the screen remains `ON_UNLOCKED`, the baseline outputs `UNKNOWN` rather than `UNEXPECTED` because the rule engine does not know that no user button press occurred. It only triggers `UNEXPECTED` if the automated trigger occurs while the screen is `OFF` or `LOCKED`.

---

## 10. Coverage & Indeterminate Telemetry Analysis

The coverage analysis measures how frequently the Phase 2 baseline can make a definitive determination:

```
Camera Activation Coverage (N = 5):
  ├── Production Sandbox Mode:     0.0% Decisive (5/5 UNKNOWN)
  └── Oracle Permission Mode:     60.0% Decisive (3/5 EXPECTED, 2/5 UNKNOWN)

Overall Included Sessions Coverage (N = 13):
  ├── Production Sandbox Mode:    61.5% Decisive (8/13 Silent, 5/13 UNKNOWN)
  └── Oracle Permission Mode:     84.6% Decisive (8/13 Silent, 3/13 EXPECTED, 2/13 UNKNOWN)
```

### Why Does Production Output UNKNOWN?
In production database `cameraguard.db`, row `8613bd60`, the exact explanation string generated was:
> `"Inconclusive telemetry: Inference confidence is LOW. Camera permission could not be verified from sandbox. Screen state was SCREEN_ON_UNLOCKED."`

This confirms that `UNKNOWN` is not an engine malfunction; it is the **deliberately conservative design** of Phase 2 to prevent false accusations when framework evidence is incomplete.

---

## 11. Comparison with Phase 4.4 $T_0$ Feature Representation

Comparing the Phase 2 evaluator logic with the $T_0$ feature dataset reveals the alignment between the two representations:

| Phase 2 Rule Signal | $T_0$ Feature Representation | Representation Status | Observations |
|---|---|---|---|
| Screen state (`SCREEN_ON_UNLOCKED`, `SCREEN_OFF`, etc.) | $F_{01}$ `screen_state_code` | Fully Represented | Captures exact display and lock status. |
| Screen interactivity boolean | $F_{02}$ `is_screen_interactive` | Fully Represented | Direct binary mapping from PowerManager. |
| Device keyguard lock status | $F_{03}$ `is_device_locked` | Fully Represented | Direct binary mapping from KeyguardManager. |
| Third-party runtime permission | $F_{04}$ `has_camera_permission` | Fully Represented | Honestly preserved as `UNVERIFIED` in production. |
| Known system camera app | $F_{05}$ `is_known_camera_app` | Fully Represented | Package name lookup heuristic. |
| UsageStats attribution confidence | $F_{06}$ `package_inference_confidence` | Fully Represented | Ordinal rating: 0=NONE, 1=LOW, 2=MED, 3=HIGH. |
| UsageStats attribution method | $F_{07}$ `inference_method_code` | Fully Represented | Method code (RESUMED, PAUSED, FALLBACK). |
| Latency from resume to trigger | $F_{08}$ `delta_resumed_to_trigger_ms` | Fully Represented | Continuous latency; `-1.0` if no resume event. |
| Recent foreground transitions | $F_{09}$ `recent_activity_count_30s` | Fully Represented | Transition density in 30s lookback window. |
| Hardware camera sensor ID | $F_{10}$ `camera_hardware_id` | Fully Represented | Sensor ID (`"0"`, `"1"`). |
| Back camera facing indicator | $F_{11}$ `is_back_camera` | Fully Represented | 3-state boolean. |

**Conclusion:** The $T_0$ feature dataset contains all information utilized by the Phase 2 rule engine, plus additional continuous dimensions ($F_{08}$ exact latency, $F_{09}$ activity density) that the rule engine currently evaluates only through coarse thresholds.

---

## 12. Small-Sample Limitations & Scientific Humility

* **Sample Size ($N = 13$ included):** This empirical baseline is based on 13 physically validated sessions collected on a single Android 14 test device.
* **Statistical Claim:** These results **must not be interpreted as definitive population statistics**. They represent an empirical characterization of the existing rule engine under controlled test conditions.
* **Support:** Calculating complex confidence intervals or claiming universal generalization would be scientifically invalid with $N = 13$. Raw counts are provided for all metrics.

---

## 13. Reproducibility & Exported Artifacts

The baseline evaluation pipeline is 100% deterministic and exported to [`data/derived/phase4/baseline/`](file:///home/sanjay/Projects/CameraGuard/data/derived/phase4/baseline/):

```
data/derived/phase4/baseline/
 ├── baseline_predictions.csv         # Session-by-session predictions, rules fired, and rationales
 ├── baseline_metrics.csv             # Summary metrics with raw counts and denominators
 ├── baseline_confusion_matrix.csv    # Multiclass confusion matrix (Ground truth vs Predicted)
 └── baseline_manifest.json           # Execution manifest with provenance and scenario breakdown
```

### Determinism Guarantee
Running `BaselineEvaluator.evaluateAll()` repeatedly over identical input data produces byte-for-byte identical prediction rows and metric summaries.

---

## 14. Verification and Test Suite Execution

The baseline evaluation framework is validated by 17 unit tests in [`BaselineEvaluatorTest.kt`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/test/java/org/cameratestharness/experiment/BaselineEvaluatorTest.kt):

* **`test 1`:** Legitimate foreground camera session evaluates with Phase 2 rules (both strict and oracle modes).
* **`test 2`:** Legitimate background continuation evaluates with Phase 2 rules (`UNKNOWN` due to low confidence).
* **`test 3`:** Permission denied negative control produces `NO_CAMERA_EVENT`.
* **`test 4`:** Permission granted no camera produces `NO_CAMERA_EVENT`.
* **`test 5`:** Ambiguous context observation produces `NO_CAMERA_EVENT`.
* **`test 6`:** Automated background trigger evaluates as `UNEXPECTED` when screen is `OFF` or `LOCKED`.
* **`test 7`:** `UNKNOWN` screen telemetry falls back gracefully without exceptions.
* **`test 8`:** `UNVERIFIED` permission produces documented `UNKNOWN` explanation.
* **`test 9`:** Duplicate legacy sessions are flagged for review.
* **`test 10`:** Contradictory ground-truth sessions retain `EXCLUDED` status.
* **`test 11`:** Session-level aggregation evaluates exactly one prediction per session ID.
* **`test 12`:** Deterministic repeated evaluation produces identical results.
* **`test 13`:** Zero ground-truth leakage into prediction logic.
* **`test 14`:** Metric computation correctly calculates rates with explicit denominators.
* **`test 15`:** Confusion matrix generates exact cell counts.
* **`test 16`:** Zero-denominator handling produces `0.0` without `NaN`.
* **`test 17`:** Physical raw dataset baseline evaluation and artifact export.

### Build & Test Results
```bash
./gradlew testDebugUnitTest assembleDebug
```
* **Unit Tests:** 50/50 test tasks passed, **BUILD SUCCESSFUL** (all 66 tests across `:app` and `:camera-test-harness` passed with 0 failures).
* **Assembly:** Both application APKs (`:app` and `:camera-test-harness`) compiled and assembled cleanly.
* **Production Integrity:** Strictly **zero** modifications made to `:app` or Phase 2 baseline code.

---

## 15. Research Conclusions & Recommendations for Phase 4.6

### Objective Answers to Central Research Questions:
1. **What does the Phase 2 baseline currently accomplish?**  
   It provides perfect specificity (zero false alarms) on negative controls because it requires physical camera callbacks to activate. When the screen is OFF or LOCKED, it decisively triggers alerts (`UNEXPECTED`).
2. **Where does it produce UNKNOWN / indeterminate outcomes?**  
   Whenever camera access occurs while the screen is ON_UNLOCKED, but candidate app permission cannot be verified across the sandbox, or when correlation confidence is low.
3. **What scenarios expose limitations?**  
   Legitimate background continuation (multitasking video streaming) is indistinguishable from stealth background access under the rule engine when the screen is ON.
4. **Which production-observable signals appear useful?**  
   Screen state ($F_{01}-F_{03}$) is highly decisive for locked/screen-off attacks. Latency ($F_{08}$) and recent activity count ($F_{09}$) provide continuous nuance that the rule engine currently ignores.
5. **Recommendation for Phase 4.6:**  
   Proceed to **Phase 4.6 (Preliminary ML Experimentation & Model Comparison)**. ML models should specifically target the `UNKNOWN` region of the decision space, learning to distinguish legitimate background continuation from unauthorized access using multidimensional continuous telemetry ($F_{08}$ latency, $F_{09}$ activity count, $F_{06}$ confidence) rather than rigid binary rules.
