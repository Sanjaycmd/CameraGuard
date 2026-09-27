# CameraGuard — Phase 7.15: Final-Year Project & Jury Defense Guide

## 1. Project Overview & Fact Sheet

- **Project Title**: CameraGuard: Context-Aware Software-Only Camera Privacy Monitoring Framework for Android
- **Domain**: Mobile Systems Security, Privacy-Enhancing Technologies, Applied Machine Learning
- **Target OS Baseline**: Android 15 (API level 35) & backward compatible to Android 8.0+
- **Evaluation Platform**: Physical smartphone hardware (`vivo V2202`, API 35) + 1,193-record research telemetry corpus
- **Key Results**:
  - Binary Hardware Detection: **100.0% accuracy** ($F_1 = 1.0000$) on static dataset, **95.12%** on physical stress runs
  - Contextual Hybrid Classification: **91.01% accuracy** ($F_1 = 0.9048$) with **0.0% false ambiguous alarms**
  - Alert Latency: Mean **4.58 ms**, 95th percentile **9.00 ms**
  - Privileges Required: **Zero** (100% unprivileged consumer application)
  - Camera Frames Collected: **Zero** (Availability callback only)

---

## 2. Core Contributions & Innovations

1. **Context-Aware Semantic Policy**: Bridges the gap between raw hardware availability and user intent by evaluating whether the camera was opened intentionally in the foreground or covertly in the background.
2. **Two-Tier Cascade Architecture**: Combines deterministic security rules (resolving 61.8% of cases with zero ambiguity) with a native, zero-dependency Decision Tree ($depth \le 5$) resolving 100% of the remaining uncertain telemetry without runtime overhead.
3. **Transition Lookback Window**: Solves the classic race condition where background malware captures camera frames immediately after the user exits an application, attributing callers with 100% accuracy within a 5000 ms window.
4. **Strict Anti-Self-Attribution Invariant**: Employs architectural guardrails ensuring CameraGuard never falsely attributes camera activity to itself, preventing recursive alerting loops.
5. **Real-Time Sub-10ms Responsiveness**: Operates entirely via push callbacks and in-memory decision tree traversal, detecting and notifying within 4.58 ms on average.

---

## 3. Defense Questions & Authoritative Evidence-Based Answers

### Q1: Why is the standard Android privacy indicator insufficient for this research problem?
**Answer**: Android's green status bar indicator is *context-blind* and *passive*. It illuminates identically whether a camera is opened by the user in the foreground or by covert spyware running in a background service. It does not provide application attribution on multi-tasking screens, maintains no auditable event log, and cannot alert the user when access occurs while the screen is locked, in a pocket, or unattended.

### Q2: What exactly does CameraGuard detect?
**Answer**: CameraGuard monitors physical sensor hardware state transitions emitted by the Android camera subsystem (`cameraserver` / HAL) via `CameraManager.AvailabilityCallback`. It detects the exact millisecond a camera sensor becomes unavailable (`CAMERA_BECAME_UNAVAILABLE`, sensor allocated to an application) and available (`CAMERA_BECAME_AVAILABLE`, sensor released).

### Q3: How does application attribution work?
**Answer**: When a hardware transition occurs, the `ContextualInferenceEngine` queries `UsageStatsManager` for `ACTIVITY_RESUMED` events in the preceding 30 seconds. It correlates the timestamp of the hardware transition with recent application transitions. If CameraGuard is in the foreground, it evaluates a 5000 ms lookback window to catch background services that launched capture immediately upon leaving the foreground.

### Q4: Why is a hybrid Rule + ML approach used instead of pure ML or pure rules?
**Answer**: Pure deterministic rules are safe but rigid: in our evaluation, rules left 38.2% of complex or ambiguous events as `UNKNOWN`. Pure machine learning models, on the other hand, can hallucinate or fail unpredictably on edge cases. Our two-tier cascade gives the best of both: deterministic rules resolve 61.8% of obvious cases immediately with 100% certainty, while the audited Decision Tree resolves the remaining 38.2% with 91.01% overall true accuracy and zero false ambiguous alarms.

### Q5: What happens when attribution is uncertain or inconclusive?
**Answer**: When no recent user interaction is found (e.g. an unprivileged background service captures the camera while the device was idle), attribution cleanly degrades to honest `null` (`UNKNOWN`), and package confidence becomes `NONE`. The Decision Tree evaluates this state and escalates the event to `AMBIGUOUS` $\to$ **`UNEXPECTED`**, triggering an immediate high-priority warning to the user.

### Q6: What are the measured false positives and false negatives?
**Answer**: Across our 89-session research corpus:
- **False Ambiguous Rate**: **0.0%** (zero innocent control sessions were falsely flagged as suspicious).
- **False Negatives**: 7 background continuation sessions where recent activity count was low ($F_{09} \le 1.0$) were conservatively routed to CONTROLS.
- In live physical testing, detection recall was **94.67%** (4 cycles missed in rapid-burst switching due to state deduplication), with **100% precision** (0 false alarms).

### Q7: What are the main system limitations?
**Answer**: We document four specific limitations:
1. *UsageStats 30s Window*: If a user stays idle inside an app for $>30$s before tapping camera, attribution falls back to UNKNOWN.
2. *Backgrounded Streaming Over-Alerting*: A legitimate app moved to background mid-stream can over-alert as UNEXPECTED.
3. *HAL Rapid Switching Deduplication*: Multi-caller switching without an intermediate AVAILABLE callback can coalesce rapid events.
4. *Idle Screen Unattributed Capture*: Capture on a completely idle screen ($F_{09} \le 1.0$) routes to CONTROLS.

### Q8: Does CameraGuard capture, inspect, or store camera frames?
**Answer**: No. CameraGuard never calls `openCamera()`, allocates zero camera preview surfaces (`SurfaceView`, `ImageReader`), and captures zero image or audio buffers. It listens strictly to public availability status callbacks.

### Q9: How is user privacy preserved?
**Answer**:
1. Zero frame capture.
2. Minimal metadata storage (~256 bytes per event).
3. Zero network permissions declared in `AndroidManifest.xml` (data never leaves device).
4. Unprivileged execution within the standard Android sandbox.

### Q10: How was the experimental evaluation conducted?
**Answer**: We evaluated across two corpora:
- **Corpus A ($N=89$ sessions, 1,193 records)**: Static research dataset gathered across front/rear lenses, screen states, and background triggers, evaluated via Stratified 5-Fold Grouped Cross-Validation.
- **Corpus B ($N=82$ runs)**: Real-time physical device testing on Android 15 (`vivo V2202`) spanning lifecycle interruptions, stress bursts, dynamic permission churn, and adversarial tests.

### Q11: How is the evaluation reproducible?
**Answer**: The repository contains an automated, deterministic runner:
```bash
.venv/bin/python3 scripts/evaluation/run_phase6_evaluation.py
```
This script validates data checksums, reconstructs sessions, evaluates models, computes confusion matrices, and outputs all metrics to `data/derived/phase6/` in seconds. All 260 unit tests pass via `./gradlew testDebugUnitTest`.

### Q12: What distinguishes the research evaluation from production behavior?
**Answer**: The production application (`:app`) is completely independent. It uses a lightweight native Kotlin Decision Tree ($depth \le 5$, $<0.1\text{ ms}$ inference time) and clamps third-party permissions ($F_{04} = -1.0$) for safety. The research infrastructure (`:camera-test-harness`, Python scripts) evaluates the system externally without polluting the production APK.

### Q13: What are the main technical contributions?
**Answer**:
1. First software-only, unprivileged context-aware camera monitor for Android 15.
2. Two-tier hybrid classification architecture eliminating false ambiguous alarms.
3. Proven transition lookback window solving post-foreground background capture attribution.
4. Sub-10ms real-time detection latency.

### Q14: What future improvements are possible?
**Answer**:
1. Adaptive UsageStats correlation window expanding dynamically based on screen state.
2. Lightweight accessibility service integration for fine-grained UI view click correlation.
3. Multi-modal sensor expansion extending the hybrid architecture to microphone (`AudioRecord`) and location (`GPS`) monitoring.
