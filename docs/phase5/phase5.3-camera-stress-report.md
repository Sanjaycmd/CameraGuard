# Phase 5.3 — Camera Event Stress Testing Report

## 1. Objective

The objective of **Phase 5.3 — Camera Event Stress Testing** is to determine whether CameraGuard maintains reliable detection, attribution, classification, deduplication, notification behavior, and event persistence when camera activity occurs repeatedly or in rapid succession on real Android hardware.

---

## 2. Research Question

> *"Does repeated camera activity cause missed events, duplicate events, attribution errors, classification instability, or state corruption?"*

This evaluation rigorously answers this question across escalating workload tiers: from a single isolated baseline event, to high-volume sequences (20 sessions), rapid sub-second cycling, burst sequences with settle dwell, repeated same-owner access, and stress following full application process restart.

---

## 3. Test Environment

All stress scenarios were executed automatically against the connected physical test device:

| Environment Property | Specification / Value |
| :--- | :--- |
| **Physical Test Device** | vivo V2202 (`PD2215HF_EX`) |
| **Operating System** | Android 15 (API Level 35) |
| **Firmware / Build ID** | `PD2215HF_EX_A_15.3.15.0.W30` |
| **CPU Architecture** | `arm64-v8a` |
| **Security Patch Level** | 2025-05-01 |
| **Hardware Cameras** | Camera ID 0 (Back/Rear), Camera ID 1 (Front) |
| **CameraGuard Version** | `versionCode=1`, `versionName=1.0` (`minSdk=26`, `targetSdk=34`) |
| **CameraTestHarness Version**| `versionCode=1`, `versionName=1.0` (`minSdk=26`, `targetSdk=34`) |
| **Phase 5.2 Baseline Commit**| `bf304fb` (`research: complete Phase 5.2 lifecycle robustness`) |
| **Phase 4 Freeze Tag** | `phase4-complete` (`c8515ea`) |
| **Stress Automation Tool** | [`scripts/research/stress/run_camera_stress_evaluation.py`](file:///home/sanjay/Projects/CameraGuard/scripts/research/stress/run_camera_stress_evaluation.py) |
| **Structured Output Data** | [`data/derived/phase5/camera_stress_evaluation_results.json`](file:///home/sanjay/Projects/CameraGuard/data/derived/phase5/camera_stress_evaluation_results.json) |

---

## 4. Test Methodology

1. **Physical Event Generation**:
   * Hardware camera acquisitions were generated using the project's existing [`CameraTestService`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/CameraTestService.kt) in `org.cameratestharness`.
   * Real camera hardware devices were opened via Android `CameraManager.openCamera(selectedCameraId, ...)` and bound to real capture sessions with `ImageReader` streams.
2. **Monitoring Detection**:
   * CameraGuard monitored system-wide camera activity via `CameraAvailabilityTracker` registered with the Android framework `CameraManager`.
   * Inferred package context was resolved via `ContextualInferenceEngine` through Android `UsageStatsManager`.
   * Classification was performed by the two-tier `HybridCameraEvaluator`.
   * Events were persisted to the local SQLite database via Room (`cameraguard.db`).
3. **Automated Verification Pipeline**:
   * Pre- and post-scenario states of the SQLite database (`camera_events` table) were queried via `run-as org.cameraguard`.
   * Event counts, timestamps, latencies, attribution, and classification were programmatically validated against expected ground truth.

---

## 5. Stress Scenarios

The suite executed seven distinct stress workloads:

* **Scenario A — Single Event Control**: 1 camera open $\rightarrow$ close cycle (open=2.0s, pause=1.0s). Purpose: control baseline.
* **Scenario B — Repeated Sequential Events (5 Sessions)**: 5 consecutive sessions (open=1.5s, pause=1.0s). Purpose: basic sequential stability.
* **Scenario C — Higher-Volume Sequential Events (20 Sessions)**: 20 consecutive sessions (open=1.5s, pause=1.0s). Purpose: sustained throughput and memory stability across 40 transitions.
* **Scenario D — Rapid Transitions (5 Fast Cycles)**: 5 rapid transitions (open=0.8s, pause=0.5s). Purpose: fast turnaround stress.
* **Scenario E — Burst Sequence (5 Rapid Cycles + Dwell)**: 5 fast transitions (open=0.8s, pause=0.5s) followed by a 5.0s idle dwell. Purpose: burst handling and post-burst queue stabilization.
* **Scenario F — Repeated Same-Owner Activity (5 Sessions)**: 5 consecutive sessions under `org.cameratestharness`. Purpose: verify session owner tracking, session clearance on close, and prevent erroneous cross-session merging.
* **Scenario G — Stress After CameraGuard Restart**: Full process destruction (`am force-stop`), relaunch, service initialization, startup baseline suppression, followed by 3 sequential sessions. Purpose: post-recovery detection pipeline verification.

---

## 6. Raw Measurements

Across the 7 physical test scenarios:

* **Total Hardware Camera Sessions**: **44**
* **Total Expected Transitions**: **88** (44 open transitions, 44 close transitions)
* **Total Detected Transitions**: **88**
* **Total Missed Transitions**: **0**
* **Total Duplicate Events**: **0**
* **Total Attribution Errors**: **0**
* **Total Classification Errors**: **0**
* **Total Database Records Persisted**: **88** (plus prior historical entries)
* **Detection Latency Range**: 1 ms minimum, 26 ms maximum (mean: 3.7 ms)

Detailed scenario metrics are recorded in [`phase5.3-stress-test-matrix.md`](file:///home/sanjay/Projects/CameraGuard/docs/phase5/phase5.3-stress-test-matrix.md).

---

## 7. Detection Metrics

* **Detection Rate**: **100.0%** ($\frac{88}{88}$)
* **Miss Rate**: **0.0%** ($\frac{0}{88}$)

Every camera hardware acquisition initiated by the test harness produced an immediate `CAMERA_BECAME_UNAVAILABLE` event, and every session teardown produced a corresponding `CAMERA_BECAME_AVAILABLE` event. No transitions were dropped.

---

## 8. Attribution Metrics

* **Attribution Error Rate**: **0.0%** ($\frac{0}{88}$)

* **Sensor Identification**: In all 88 transitions, Camera ID was accurately resolved to physical hardware sensor `0` (Rear Camera).
* **Package Context Attribution**: For opening transitions where foreground `UsageStats` lookback was within the temporal window, package identity was accurately mapped to `org.cameratestharness`. On session closure, `CameraAvailabilityTracker` cleanly attributed the closure to the active session owner and cleared the owner map.

---

## 9. Classification Metrics

* **Classification Error Rate**: **0.0%** ($\frac{0}{88}$)

All 88 transitions were evaluated to valid definitive outcomes:
* Opening transitions where foreground activity was correlated resolved cleanly through the two-tier cascade (Tier 1 deterministic heuristic or Tier 2 shallow Decision Tree).
* All 44 closure transitions were classified deterministically as `EXPECTED` with explanation *"Camera returned to available state (lifecycle closure)"* via Tier 1 Rule $R_4$, requiring zero ML invocation.
* Zero events produced unhandled or corrupted classifications.

---

## 10. Deduplication Behavior

* **Duplicate Rate**: **0.0%** ($\frac{0}{88}$)

The `cameraStateMap` in `CameraAvailabilityTracker` maintains the last observed `RawCameraEventType` per camera ID:
```kotlin
val previousState = cameraStateMap.put(cameraId, rawType)
if (previousState == rawType) {
    logD("Duplicate availability state ignored for cameraId=$cameraId: state=$rawType")
    return
}
```
Under rapid cycling and repeated events, this state machine prevented duplicate callbacks from generating duplicate database records or notifications. Furthermore, the 3000ms `DEDUPLICATION_WINDOW_MS` bidirectional safeguard between UsageStats fallback and CameraManager callbacks functioned without race conditions.

---

## 11. Persistence Behavior

* **Persistence Integrity**: **100.0%**

All 88 detected events were successfully committed to the SQLite Room database (`camera_events` table). Inspection via `sqlite3` confirmed:
* Primary keys (`id`) are well-formed UUID strings.
* Timestamps are accurate 64-bit epoch milliseconds.
* Historical records from Phase 5.2 were preserved without truncation, overwriting, or schema corruption. Total database events grew monotonically from 6 to 94.

---

## 12. Notification Behavior

* `NotificationHelper` dispatches background alerts exclusively for `UNEXPECTED` classifications.
* Ongoing monitoring notification (ID 1001) in channel `camera_monitoring_service` remained persistent throughout all 44 acquisition cycles without crashing or dropping foreground service priority.
* Normal closure transitions (`EXPECTED`) cleanly bypassed alert notifications, preventing notification spam during rapid camera use.

---

## 13. Event Ordering

* **Event-Order Integrity**: **100.0%**

All 88 transitions exhibited strictly non-decreasing timestamps ($T_{open} < T_{close}$). In every session:
1. `CAMERA_BECAME_UNAVAILABLE` strictly preceded `CAMERA_BECAME_AVAILABLE`.
2. The delta between open and close matched the physical dwell duration configured by the harness.

---

## 14. Device & Platform Limitations

Testing revealed an important hardware and platform boundary on Android 15 (vivo V2202):

* **Minimum Safe Turnaround Latency (~500ms)**:
  * Android Camera HAL session teardown (`CameraDevice.close()`) combined with foreground service termination requires approximately 200–350ms of hardware settlement time.
  * In exploratory stress testing at 400ms pause, back-to-back taps hit during the active HAL cleanup window, causing Compose UI buttons to drop the tap while the service was transitioning.
  * At $\ge 500\text{ ms}$ pause duration, physical camera acquisition achieved 100.0% reliability (44/44 sessions).
  * Consequently, 500ms represents the physical hardware turnaround lower bound for reliable back-to-back camera sessions on this device.

---

## 15. Failures Observed

* **Zero monitoring failures**: CameraGuard exhibited zero detection misses, zero duplicates, and zero state corruption across all 88 evaluated transitions.
* No regressions or memory leaks were observed during continuous execution.

---

## 16. Interpretation

The experimental results directly answer the research question:
* Repeated camera activity does **not** cause missed events or duplicate events in CameraGuard.
* Event ordering, attribution, and two-tier hybrid classification remain strictly stable even under high-volume stress (20 consecutive sessions in under 60 seconds).
* The event pipeline cleanly recovers after complete process termination, establishing fresh availability baselines without startup false positives.

---

## 17. Conclusion

**Outcome: PASS**

CameraGuard satisfies all criteria for Phase 5.3 Camera Event Stress Testing:
* 44 physical camera sessions (88 transitions) evaluated with 100.0% detection rate and 0.0% error rate.
* 6 new deterministic stress unit tests added to [`CameraStressUnitTest.kt`](file:///home/sanjay/Projects/CameraGuard/app/src/test/java/org/cameraguard/monitoring/CameraStressUnitTest.kt) (217/217 total unit tests passing).
* Full debug assemble build verified.
* Telemetry, test matrix, and report fully documented and reproducible.
