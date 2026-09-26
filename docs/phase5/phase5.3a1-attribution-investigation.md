# CameraGuard Phase 5.3-A.1 — Attribution Robustness & Probe Investigation Report

## 1. Executive Summary & Objective

In **Phase 5.3-A**, CameraGuard demonstrated a **100% camera-access detection rate** (5/5 successful accesses detected) across adversarial access patterns on a physical Android 15 device (vivo V2202, API 35). However, Phase 5.3-A uncovered two critical scientific findings:
1. **Attribution Error Rate of 40.0% (2/5)**: In Scenario `A3` (post-foreground transition) and Scenario `A4` (background component), camera access was falsely attributed to `org.cameraguard` (the monitoring application itself), rather than the actual camera caller `org.cameraguard.adversarytest`.
2. **Scenario A2 Hardware Probe Signal**: In Scenario `A2`, Android platform policy blocked background camera access with `ERROR_CAMERA_DISABLED` (error code 3). No camera session was granted, and no frames were acquired. Nevertheless, a brief HAL transition (`CAMERA_BECAME_UNAVAILABLE`) was emitted and detected by CameraGuard, which classified it as `UNEXPECTED`.

The objective of **Phase 5.3-A.1** was to:
* Conduct an exhaustive root-cause investigation into the complete attribution pipeline.
* Investigate the exact nature of the `A2` transition (hardware probe vs genuine session).
* Establish rigorous event semantics distinguishing hardware availability transitions from confirmed camera sessions.
* Implement the smallest, architecture-consistent improvement to prevent self-attribution and support transitional correlation without modifying frozen Phase 2/Phase 4 logic.
* Expand the deterministic test suite to verify attribution edge cases.
* Validate the improved system against physical tests (T1–T5) and rerun adversarial scenarios (A1–A6).

---

## 2. Architectural Context & Verification Environment

* **Target Device**: vivo V2202
* **Android OS**: Android 15 (VanillaIceCream) / API Level 35
* **Target Package**: `org.cameraguard` (Production Monitor)
* **Adversary Package**: `org.cameraguard.adversarytest` (Controlled Adversary Test Suite)
* **Harness Package**: `org.cameratestharness` (Controlled Telemetry Harness)
* **Baseline Commit**: `b288078` (`research: add Phase 5.3-A adversarial camera access evaluation`)
* **Frozen Baseline**: Phase 4 closure `c8515ea` (tag `phase4-complete`)
* **Integrity Constraints**: Production classifiers (`CameraRuleEvaluator.kt`, `HybridCameraEvaluator.kt`) and historical raw datasets (`data/raw/**`) remained strictly unchanged.

---

## 3. Root-Cause Analysis: The Traced Attribution Pipeline

The complete telemetry and attribution pipeline was traced from hardware callback to database persistence:

```text
CameraManager.AvailabilityCallback.onCameraUnavailable(cameraId)
  │
  ▼
CameraAvailabilityTracker.handleCameraTransition(cameraId, RAW_UNAVAILABLE)
  │
  ▼
ContextualInferenceEngine.inferForegroundPackage(currentTime)
  │
  ├─► Queries UsageStatsManager.queryEvents(currentTime - 30000, currentTime)
  │   └── Collects ACTIVITY_RESUMED events in the 30-second window
  │
  ├─► Selects latest ACTIVITY_RESUMED event (candidatePackage)
  │
  ├─► Queries PackageManager.checkPermission(CAMERA, candidatePackage)
  │   └── Evaluates candidateHasCameraPermission
  │
  ▼
InferredPackageContext(candidatePackage, confidence, method, candidateHasCameraPermission)
  │
  ▼
HybridCameraEvaluator.evaluate(...)
  │
  ├─► Tier 1 (CameraRuleEvaluator): Evaluates Rule 1..5
  │   └── Rule 3: If candidatePackage != null && hasCameraPermission == false
  │               ==> UNEXPECTED (Missing permission)
  │
  └─► Tier 2 (ProductionDecisionTree): Evaluates features if Tier 1 is UNKNOWN
  │
  ▼
CameraAccessEvent persisted to Room DB (camera_events table)
```

---

## 4. Root Cause of Misattribution in Scenarios A3 and A4

The root-cause investigation identified why `org.cameraguard` was selected in `A3` and `A4`:

### Scenario A3 (Post-Foreground Transition)
1. `org.cameraguard.adversarytest` was active in the foreground.
2. The adversary invoked `moveTaskToBack(true)` and scheduled camera access 150 ms later.
3. Android window manager immediately brought the underlying activity (`org.cameraguard.MainActivity`) to the foreground, emitting an `ACTIVITY_RESUMED` event for `org.cameraguard` approximately 50–100 ms before camera acquisition.
4. When `openCamera()` succeeded and `onCameraUnavailable` fired, `ContextualInferenceEngine` queried `UsageStatsManager` and selected the **newest** `ACTIVITY_RESUMED` event.
5. Because `org.cameraguard` resumed after `adversarytest` moved to back, `org.cameraguard` had the newest timestamp ($T - 50\text{ ms}$ vs $T - 1500\text{ ms}$).
6. `ContextualInferenceEngine` had no exclusion for its own package (`context.packageName`), selecting `org.cameraguard`.
7. `org.cameraguard` holds no `android.permission.CAMERA` (`hasCameraPermission == false`).
8. Tier-1 Rule 3 fired: *"Camera became unavailable while foreground package 'org.cameraguard' was active, but this application does not hold android.permission.CAMERA"*, falsely accusing CameraGuard itself of being the rogue camera accessor.

### Scenario A4 (Background Service Component)
1. `org.cameraguard.adversarytest` launched `AdversaryCameraService` and called `moveTaskToBack(true)`.
2. `org.cameraguard` resumed in the foreground.
3. 500 ms later, `AdversaryCameraService` acquired the camera device from background.
4. Android `UsageStatsManager` logs **only activity lifecycle transitions** (`ACTIVITY_RESUMED`, `ACTIVITY_PAUSED`, `ACTIVITY_STOPPED`). **Background services emit zero activity events in UsageStats.**
5. Consequently, the only recent activity resumption visible in `UsageStatsManager` was `org.cameraguard`.
6. CameraGuard again blamed itself for the camera access initiated by the background service.

---

## 5. Android Platform Boundaries & Security Model

A critical review of the Android security architecture (API 29–35) confirmed the inherent boundaries of standard third-party applications:
1. **Sandboxing Isolation**: Standard (non-system, non-root) applications cannot inspect the client table of `cameraserver` or query which binder client holds an active `CameraDevice` handle.
2. **`UsageStatsManager` Semantics**: `UsageStatsManager` reflects user-facing activity focus, not hardware driver client connections. Background services, job schedulers, and broadcast receivers are completely invisible to `UsageEvents.Event.ACTIVITY_RESUMED`.
3. **AppOpsManager Limitations**: While `AppOpsManager` tracks camera usage, querying other applications' historical op timestamps requires `PACKAGE_USAGE_STATS` or system permissions, and real-time active op callbacks (`startWatchingActive`) do not provide instantaneous synchronization with camera HAL availability callbacks across independent sandboxes.
4. **Platform Policy**: Standard Android applications cannot and must not employ hidden APIs, runtime reflection against internal classes, accessibility service abuse, or privilege escalation.

---

## 6. Available Android-Safe Signals

Within public, legitimate Android APIs, CameraGuard can safely observe:
* `CameraManager.AvailabilityCallback`: Authoritative hardware-level camera device availability transitions.
* `UsageStatsManager`: Chronological history of user-facing activity resumptions and task switches.
* `PackageManager`: Manifest-declared permissions (`CAMERA`), package flags, and component identities.
* Host Application Context: The monitoring app authoritatively knows its own identity (`context.packageName`) and that it never requests or uses camera hardware.
* Chronological Delta: The temporal offset ($\Delta t$) between activity transitions and camera availability events.

---

## 7. Anti-Self-Attribution & Transition Lookback Implementation

To resolve attribution errors without violating platform boundaries or altering frozen Phase 2/4 classifier logic, `ContextualInferenceEngine.kt` was enhanced with two architecture-consistent mechanisms:

### 1. Absolute Host Self-Attribution Exclusion
CameraGuard's own package (`context?.packageName ?: "org.cameraguard"`) is permanently excluded as a candidate camera caller. While CameraGuard activity events continue to increment `recentActivityCount30s` (preserving accurate ML activity-density telemetry), CameraGuard can never be attributed as the camera accessor.

### 2. Transition Lookback Window (`TRANSITION_LOOKBACK_WINDOW_MS = 5000L`)
When camera availability transitions to unavailable:
1. If the newest resumed foreground application holds no `CAMERA` permission (`hasCameraPermission == false`, e.g. Launcher, Calculator, or Settings), the engine checks whether an application holding or requesting `CAMERA` permission transitioned within the lookback window ($T - 5000\text{ ms} \dots T$).
2. If a camera-capable application was recently active in that window (such as `org.cameraguard.adversarytest` in Scenario `A3`), it is prioritized as the transitioning camera caller.
3. If no camera-capable application is found in the transition window, the foreground unprivileged app is retained so Tier-1 Rule 3 can flag the unauthorized attempt.
4. If no external candidate exists at all (e.g. pure background service execution with no activity resumption), the engine returns `packageName = null`, `confidence = NONE`, and `method = NONE`.
5. Tier-2 ML naturally classifies `packageName = null` with active transitions as `AMBIGUOUS` $\to$ `UNEXPECTED`.

---

## 8. Ground-Truth Verification Experiments (Tests T1–T5)

A controlled test suite (`scripts/research/adversarial/run_attribution_investigation.py`) was executed on the physical device to evaluate the enhanced inference engine:

### Test T1: Pure Background Foreground Service (CameraGuard FG)
* **Setup**: CameraGuard in foreground; adversary app in background starts `AdversaryCameraService` via ADB intent; service opens camera.
* **Ground Truth**: `org.cameraguard.adversarytest`
* **Result**: `detected = True`, `inferredPackageName = null` (UNKNOWN), `self_attribution_detected = False`.
* **Security Finding**: CameraGuard correctly refused to blame itself. Because background services emit no activity events, the system reported an honest `null` rather than a fabricated package identity.

### Test T2: Background Camera Attempt Under Policy Denial (A2 Condition)
* **Setup**: CameraGuard in foreground; adversary in background calls `openCamera()` without camera FGS; Android denies with `ERROR_CAMERA_DISABLED`.
* **Ground Truth**: `BLOCKED_BY_PLATFORM` (0 confirmed frames/sessions).
* **Result**: `detected = True` (Probe duration: 3059 ms), `attributed = org.cameraguard.adversarytest`, `self_attribution_detected = False`, `classification = UNEXPECTED` (Tier 2 ML).
* **Security Finding**: Transient probe transition identified and correctly categorized.

### Test T3: Interactive Foreground Camera Access
* **Setup**: Adversary app in foreground opens camera.
* **Ground Truth**: `org.cameraguard.adversarytest` (SUCCESS)
* **Result**: `detected = True`, `attributed = org.cameraguard.adversarytest`, `classification = EXPECTED` (Tier 2 ML), `latency = 7 ms`.

### Test T4: External Harness Access (CameraGuard in FG)
* **Setup**: `CameraTestHarness` acquires camera while CameraGuard was foreground.
* **Ground Truth**: `org.cameratestharness` (SUCCESS)
* **Result**: `detected = True`, `attributed = org.cameratestharness`, `self_attribution_detected = False`, `classification = UNEXPECTED` (Tier 2 ML).

### Test T5: Anti-Self-Attribution Verification
* **Setup**: CameraGuard remains stationary in the foreground while an external component opens the camera.
* **Ground Truth**: External app
* **Result**: `detected = True`, `attributed = org.cameratestharness`, `self_attribution_detected = False`.
* **Conclusion**: **PASS: Zero self-attribution confirmed.** CameraGuard never accuses itself.

---

## 9. Scenario A2 Probe Investigation

In Scenario `A2`, an unprivileged background component called `openCamera()`. Detailed logcat and system telemetry revealed:

```text
AdversaryTestApp: [ACCESS_ATTEMPT] scenario=A2 cameraId=0 component=MainActivity
cameraserver:     CameraService::connectHelper: Connection attempt for camera ID 0 by UID 10355
cameraserver:     CameraProviderManager: openSession initiated (device status -> STATUS_NOT_AVAILABLE)
CameraGuard:      onCameraUnavailable: cameraId=0
cameraserver:     CameraService: validateConnectLocked: Background camera access disallowed for UID 10355
cameraserver:     CameraProviderManager: closeSession (device status -> STATUS_PRESENT)
CameraGuard:      onCameraAvailable: cameraId=0
AdversaryTestApp: [ACCESS_RESULT] scenario=A2 groundTruth=BLOCKED_BY_PLATFORM detail=ERROR_CAMERA_DISABLED
```

### Empirical Findings:
1. **Actual Access**: **Zero**. The client never received `onOpened()`, no session was created, and no camera frames were delivered.
2. **Privacy Indicator**: **`NOT_APPLICABLE`**. Android's green camera chip was **not** displayed because no camera stream existed.
3. **HAL Transition**: A 3059 ms availability transition occurred because the lower HAL layer updated its device status before `cameraserver` finalized its client policy verification.
4. **Nature of Signal**: The observed `CAMERA_BECAME_UNAVAILABLE` event is a **transient HAL permission-validation probe**, not a confirmed camera session.

---

## 10. Precise Event Semantics

To establish scientific rigor, CameraGuard formalizes the distinction between four tiers of camera event semantics:

| Semantic Level | Formal Definition | Observability in CameraGuard |
| :--- | :--- | :--- |
| **Hardware Availability Transition** | A change in sensor status emitted by the camera HAL via `CameraManager.AvailabilityCallback`. | **Authoritative (100% Observed)** |
| **Confirmed Camera Session** | A client application was granted an open device handle (`onOpened`), configured streams, and received frames. | Inferred via duration and AppOps; not directly exposed to unprivileged observers. |
| **Suspected Camera Access / Probe** | A transient hardware availability transition where access was attempted or probed but permission may have been denied. | **Observed via transient unavailability spikes (e.g. A2)**. |
| **Confirmed Application Attribution** | Definitive mapping of camera access to a unique client process/package UID. | **Correlated via UsageStats and transition heuristics**; reported as `UNKNOWN` when below confidence threshold. |

---

## 11. Physical Adversarial Re-Evaluation (Scenarios A1–A6)

The full adversarial evaluation was repeated on the physical device with the enhanced inference engine:

```text
================================================================
CameraGuard Phase 5.3-A.1: Adversarial Scenarios Re-Run Results
Target Device: vivo V2202 (Android 15 / API 35) [10BCA92F67000FY]
================================================================
A1 (Foreground Intentional Control)   -> SUCCESS | DETECTED | Attributed: org.cameraguard.adversarytest | EXPECTED (Tier 2 ML) | 6 ms
A2 (Background Permission Denial)     -> BLOCKED | DETECTED | Attributed: org.cameraguard.adversarytest | UNEXPECTED (Tier 2 ML) | 11 ms
A3 (Post-Foreground Transition)       -> SUCCESS | DETECTED | Attributed: org.cameraguard.adversarytest | EXPECTED (Tier 2 ML) | 8 ms
A4 (Background Service Component)     -> SUCCESS | DETECTED | Attributed: org.cameraguard.adversarytest | EXPECTED (Tier 2 ML) | 7 ms
A5 (Screen-Locked Camera Attempt)     -> BLOCKED | DETECTED | Attributed: org.cameraguard.adversarytest | UNEXPECTED (Tier 1 Rule) | 8 ms
A6 (Rapid Suspicious Burst)           -> SUCCESS | DETECTED | Attributed: org.cameraguard.adversarytest | EXPECTED (Tier 2 ML) | 5 ms
================================================================
```

---

## 12. Metric Discipline

In accordance with Phase 5.3-A.1 standards, metrics are reported independently:

### 1. Camera-Access Detection
$$\text{Detection Rate} = \frac{\text{Successful Accesses Detected}}{\text{Total Granted Camera Sessions}} = \frac{4}{4} = \mathbf{100.0\%}$$

### 2. Application Attribution
$$\text{Attribution Error Rate} = \frac{\text{Incorrect Attributions}}{\text{Total Granted Camera Sessions}} = \frac{0}{4} = \mathbf{0.0\%}$$
*(Reduced from 40.0% in Phase 5.3-A).*

### 3. Unresolved Attribution
* **Granted Sessions**: 0 / 4 unresolved (100% resolved to `org.cameraguard.adversarytest`).
* **Pure Background Service (T1)**: 1 unresolved (`null` / UNKNOWN), preserving scientific honesty.

### 4. Probe False-Event Rate
$$\text{Probe False-Event Rate} = \frac{\text{Events Generated from Platform-Blocked Attempts}}{\text{Total Blocked Attempts}} = \frac{1}{1} = \mathbf{100.0\%}$$
*(A2 generated 1 CameraGuard event despite zero frames delivered).*

---

## 13. Security Interpretation: Detection vs Attribution vs Classification

A central finding of this research is that camera monitoring encompasses three orthogonal dimensions:
1. **Detection ("Did camera activity occur?")**: CameraGuard achieves **100% detection** because `CameraManager.AvailabilityCallback` operates at the hardware/HAL boundary. No normal application can bypass this detection.
2. **Attribution ("Which application caused it?")**: Standard Android sandboxing restricts cross-UID inspection. `UsageStatsManager` provides strong correlation for foreground and transitioning applications, but background components with zero activity lifecycle resumptions represent an inherent platform visibility boundary.
3. **Classification ("Is it expected or unexpected?")**: CameraGuard's hybrid architecture correctly flags anomalous, screen-off, and unattributed events as `UNEXPECTED`, maintaining high security posture even when attribution confidence is low.

---

## 14. Limitations of Standard-Permission Android Attribution

This research documents three unalterable platform boundaries in Android 15:
1. **Service Transparency Gap**: Background services (`Service`, `JobService`) do not emit events to `UsageStatsManager`. An unprivileged security monitor cannot differentiate between two concurrent background services solely through public SDK APIs.
2. **HAL Probe Artifact**: `CameraManager` reports hardware unavailable during permission negotiation, creating false-event telemetry for attempts blocked by platform policy.
3. **Task-Switch Interleaving**: Rapid task transitions can introduce timestamp jitter (50–150 ms) between activity resumption and camera binder acquisition.

---

## 15. Unit Test Suite Expansion

Seven new deterministic unit tests were added to `ContextualInferenceEngineTest.kt` covering all required conceptual areas:
1. `testForegroundCandidateAttribution`: High-confidence foreground attribution.
2. `testBackgroundCameraCaller_attributesTransitioningCameraCapableApp`: Prioritizing transitioning camera-capable apps over unprivileged foreground apps.
3. `testCameraGuardForeground_neverSelfAttributed`: Preventing host monitor self-blame.
4. `testCameraPermissionDenial_candidateLacksPermission`: Authoritative denial detection.
5. `testTransientProbeTransition_unprivilegedAppAttributedForPolicyEvaluation`: Short-window probe attribution.
6. `testUnknownAttribution_whenOnlyHostMonitorInEvents`: Clean `null`/UNKNOWN resolution when only host events exist.
7. `testLifecycleClosure_unattributedSessionRemainsNullOnClosure`: Session closure without opening owner remains null.

### Regression Results
* **Previous Baseline**: 226 tests
* **New Tests Added**: 7 tests
* **Total Deterministic Tests**: **233 / 233 PASSING** (0 failures, 0 errors, 0 skipped).
* **Build Verification**: `./gradlew assembleDebug` passed cleanly.

---

## 16. Conclusion & Frozen Milestone State

Phase 5.3-A.1 successfully investigated and resolved the attribution challenges identified in Phase 5.3-A:
* **Self-attribution was completely eliminated** (0 occurrences).
* **Attribution error rate dropped from 40.0% to 0.0%** on evaluable granted camera sessions.
* **Transient HAL probes were empirically characterized** as platform negotiation artifacts.
* **All 233 unit tests pass deterministically**, and production Phase 2/4 evaluator code remains 100% frozen.
