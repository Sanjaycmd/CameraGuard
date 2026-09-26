# CameraGuard — Phase 5.5: Contextual Classification Robustness Test Matrix

## Overview

Phase 5.5 evaluates CameraGuard's combined pipeline of camera-access detection, caller attribution,
and final classification under ambiguous contextual conditions — specifically when public Android
APIs cannot deterministically establish who the camera caller is.

- **Device**: vivo V2202 (Android 15 / API 35, `10BCA92F67000FY`)
- **Evaluation script**: `scripts/research/classification/run_contextual_classification_evaluation.py`
- **Results file**: `data/derived/phase5/contextual_classification_evaluation_results.json`
- **Baseline tests at start of Phase 5.5**: 245 passing (from Phase 5.4 completion)

---

## Scenario Matrix

| ID  | Name | Context | CameraGuard FG | Attribution Evidence | Expected Attribution | Expected Classification | PASS Criteria |
|-----|------|---------|---------------|---------------------|---------------------|------------------------|---------------|
| C1  | Known foreground legitimate caller | CameraTestHarness interactive foreground | No | ACTIVITY_RESUMED in window | `org.cameratestharness` | `EXPECTED` | Detected + attributed to Harness + EXPECTED |
| C2  | Known foreground adversarial caller | AdversaryTestApp interactive foreground | No | ACTIVITY_RESUMED in window | `org.cameraguard.adversarytest` | `EXPECTED` | Detected + attributed to adversary + EXPECTED |
| C3  | Background service with transition evidence | CameraGuard FG, adversary went BG within 5000ms | Yes | ACTIVITY_RESUMED within 5s window | `org.cameraguard.adversarytest` | `EXPECTED` | Detected + attributed to adversary + EXPECTED |
| C4  | Background service without attribution evidence | CameraGuard FG, adversary FGS, no recent transition | Yes | None (UNKNOWN) | `null` | `UNEXPECTED` (Tier-2 AMBIGUOUS) | Detected + null attribution + 0% self-attribution |
| C5  | CameraGuard foreground, stale candidates rejected | CameraGuard FG, adversary FGS (stale context) | Yes | Stale candidates only | `null` | `UNEXPECTED` or `AMBIGUOUS` | Detected + null attribution + no self-attribution + no stale attribution |
| C6  | Harness foreground → background during stream | Harness opens camera, then backgrounds while streaming | No | ACTIVITY_RESUMED at open time | `org.cameratestharness` | `EXPECTED` | Detected + attributed to Harness open+close + EXPECTED |
| C7  | Permission denied attempt | Adversary CAMERA permission revoked, attempt blocked | No | N/A (blocked by platform) | N/A | NOT_APPLICABLE | 0 hardware transitions, 0 fabricated sessions |
| C8  | Permission denied then granted | Denied attempt → grant → legitimate access | No | ACTIVITY_RESUMED in window | `org.cameraguard.adversarytest` | `EXPECTED` | Detected + correct attribution + no state contamination |
| C9  | Rapid caller switching | Harness→close→Adversary→close→Harness (3 cycles, ≥3.5s between) | No | ACTIVITY_RESUMED per cycle | All 3 correctly attributed | `EXPECTED` per cycle | All 3 detected, no cross-contamination |
| C10 | CameraGuard restart + unknown context | CG restarts, waits 6.5s, adversary FGS accesses camera | Yes | None post-restart | `null` | `UNEXPECTED` | Baseline clean after restart, detected, null attribution, no self-attribution |

---

## Scenario Specifications

### C1 — Known Foreground Legitimate Caller

| Field | Value |
|-------|-------|
| **ID** | C1 |
| **Phase** | 5.5 |
| **Ground truth caller** | `org.cameratestharness` |
| **CAMERA permission** | GRANTED |
| **CameraGuard state** | Background |
| **Setup** | Bring Harness to fresh foreground (`am start -n ... && input keyevent resume`), select `NORMAL_FOREGROUND_CAMERA` scenario |
| **Action** | Tap `START EXPERIMENT`, wait 2.5s, tap `STOP EXPERIMENT` |
| **Attribution mechanism** | UsageStatsManager `ACTIVITY_RESUMED` within 30s window |
| **Expected attribution** | `org.cameratestharness` |
| **Expected confidence** | HIGH or MEDIUM |
| **Expected classification** | `EXPECTED` |
| **Success criteria** | Detected AND correct attribution AND `EXPECTED` classification |
| **Known risk** | If `ACTIVITY_RESUMED` is >30s stale, UsageStats returns `null` → attribution fails → status FAIL (documented as platform timing constraint) |

---

### C2 — Known Foreground Adversarial Caller

| Field | Value |
|-------|-------|
| **ID** | C2 |
| **Ground truth caller** | `org.cameraguard.adversarytest` |
| **CAMERA permission** | GRANTED |
| **CameraGuard state** | Background |
| **Setup** | `am force-stop adversarytest`, then `am start -n adversarytest/.MainActivity` |
| **Action** | AdversaryTestApp opens camera with 2s hold |
| **Expected attribution** | `org.cameraguard.adversarytest` |
| **Expected classification** | `EXPECTED` (LEGITIMATE foreground) |
| **Success criteria** | Detected AND attributed to adversary AND EXPECTED |

---

### C3 — Background Service with Sufficient Transition Evidence

| Field | Value |
|-------|-------|
| **ID** | C3 |
| **Ground truth caller** | `org.cameraguard.adversarytest` |
| **CAMERA permission** | GRANTED |
| **CameraGuard state** | Foreground |
| **Setup** | Bring CameraGuard to foreground. Within 5000ms, start adversary FGS with camera |
| **Attribution mechanism** | Adversary's `ACTIVITY_RESUMED` within TRANSITION_LOOKBACK_WINDOW_MS (5000ms) |
| **Expected attribution** | `org.cameraguard.adversarytest` (MEDIUM confidence) |
| **Expected classification** | `EXPECTED` |
| **Success criteria** | Detected AND attributed to adversary AND EXPECTED |

---

### C4 — Background Caller with Insufficient Attribution Evidence (P7 Reproduction)

| Field | Value |
|-------|-------|
| **ID** | C4 |
| **Ground truth caller** | `org.cameraguard.adversarytest` |
| **CAMERA permission** | GRANTED |
| **CameraGuard state** | Foreground (>5s elapsed since adversary last visible) |
| **Setup** | CameraGuard foreground, wait 6.5s (stale UsageStats window), adversary FGS opens camera |
| **Attribution mechanism** | None (no camera-capable `ACTIVITY_RESUMED` within 5000ms) |
| **Expected attribution** | `null` (UNKNOWN — honest attribution) |
| **Expected classification** | `UNEXPECTED` (Tier-2 ML: AMBIGUOUS due to `f09_recent_activity_count > 1.0`) |
| **Robustness checks** | `self_attribution = false`, `stale_context = false` |
| **Success criteria** | Detected AND `null` attribution AND `self_attribution = false` |
| **Research finding** | Reproduces P7 ambiguity: ML classifies UNKNOWN + active background access as AMBIGUOUS/UNEXPECTED |

---

### C5 — CameraGuard Foreground, Stale Candidates Rejected

| Field | Value |
|-------|-------|
| **ID** | C5 |
| **Ground truth caller** | `org.cameraguard.adversarytest` |
| **CameraGuard state** | Foreground |
| **Setup** | CG foreground, all candidate events stale (>30s or >5s without camera perm) |
| **Expected attribution** | `null` (stale candidates rejected) |
| **Expected classification** | `UNEXPECTED` or `AMBIGUOUS` |
| **Success criteria** | Detected AND `null` attribution AND `self_attribution = false` AND `stale_context_observed = false` |
| **Research finding** | Confirms stale-candidate rejection logic prevents false positive attribution |

---

### C6 — CameraTestHarness Foreground-to-Background Transition

| Field | Value |
|-------|-------|
| **ID** | C6 |
| **Ground truth caller** | `org.cameratestharness` |
| **CAMERA permission** | GRANTED |
| **Setup** | Harness opens camera in foreground, then `input keyevent 3` to background it while camera remains open, then restore Harness and stop |
| **Expected attribution** | `org.cameratestharness` at both OPEN and CLOSE events |
| **Expected classification** | `EXPECTED` |
| **Success criteria** | Detected OPEN+CLOSE AND consistent attribution AND `EXPECTED` classification |
| **Known limitation** | When Harness backgrounds mid-stream, ML may evaluate reduced recent-activity feature → AMBIGUOUS/UNEXPECTED. This is a known ML feature granularity limitation, not a detection failure |

---

### C7 — Permission Denied Attempt

| Field | Value |
|-------|-------|
| **ID** | C7 |
| **Ground truth caller** | `org.cameraguard.adversarytest` (blocked) |
| **CAMERA permission** | REVOKED |
| **Expected outcome** | Platform blocks `openCamera()` with `SecurityException` prior to HAL allocation |
| **Expected events** | 0 `CAMERA_BECAME_UNAVAILABLE` hardware transitions |
| **Expected fabricated sessions** | 0 |
| **Success criteria** | `hardware_transitions == 0` AND `confirmed_session_fabricated == false` |

---

### C8 — Permission Denied then Granted (State Recovery)

| Field | Value |
|-------|-------|
| **ID** | C8 |
| **Ground truth caller** | `org.cameraguard.adversarytest` (second access) |
| **Permission sequence** | REVOKE → denied attempt → GRANT → legitimate access |
| **Success criteria** | Detected AND attributed to adversary AND `EXPECTED` AND `state_contamination_observed = false` |
| **Research question** | Does a prior denied attempt contaminate the permission/context state for the subsequent legitimate access? |

---

### C9 — Rapid Caller Switching

| Field | Value |
|-------|-------|
| **ID** | C9 |
| **Ground truth sequence** | Harness → close → Adversary → close → Harness |
| **Cycle pause** | ≥3.5s between cycles (exceeds `DEDUPLICATION_WINDOW_MS = 3000ms`) |
| **Expected** | 3 independent `CAMERA_BECAME_UNAVAILABLE` events, correctly attributed per-cycle |
| **Success criteria** | `successful_sessions_detected == 3` AND exact cycle attribution match AND no cross-contamination |
| **Known limitation** | If Android deduplication coalesces rapid `onCameraUnavailable` callbacks (vivo V2202 behavior), fewer than 3 distinct events may be delivered. Documented as platform/HAL timing limitation |

---

### C10 — CameraGuard Restart Followed by Unknown Context

| Field | Value |
|-------|-------|
| **ID** | C10 |
| **Ground truth caller** | `org.cameraguard.adversarytest` (background FGS) |
| **CameraGuard state** | Restarted → 6.5s idle → foreground |
| **Expected** | Startup baseline suppressed (no spurious events at restart), then detected, `null` attribution (no recent transition evidence after restart), `UNEXPECTED` classification |
| **Robustness checks** | `startup_baseline_suppressed = true`, `self_attribution = false`, `pre_restart_attribution_leaked = false` |
| **Success criteria** | `baseline_clean == true` AND detected AND `null` attribution AND `self_attribution = false` |

---

## Metrics Collected

### 1. Detection Metrics
- Total successful camera sessions established: **11** (C1×1, C2×1, C3×1, C4×1, C5×1, C6×1, C7×0, C8×1, C9×3, C10×1)
- Sessions detected: counted per-scenario using DB `CAMERA_BECAME_UNAVAILABLE` event presence

### 2. Attribution Metrics
- **Confirmed attributions**: `MEDIUM` or `HIGH` confidence, correct package
- **Candidate attributions**: `LOW` confidence, correct package
- **Unknown attributions**: `null` / `NONE` confidence
- **Self-attribution rate**: occurrences where `attributed = org.cameraguard`
- **Stale-context incidents**: stale candidates leaked into positive attribution
- **Caller-switch contamination**: Cycle N's attribution leaked into Cycle N+1

### 3. Classification Metrics
- Classification accuracy among interactive foreground sessions (C1, C2, C3, C8, one cycle of C9)
- Ambiguous-context classification: among UNKNOWN attribution events, breakdown of `EXPECTED` / `AMBIGUOUS` / `UNEXPECTED`

### 4. Robustness Metrics
- Self-attribution rate
- Stale-context rate
- Caller-switch contamination rate
- Duplicate event rate
- State contamination rate (C8: denied-attempt contamination)
- Startup suppression (C10)

---

## Deterministic JVM Test Coverage

The `ContextualClassificationRobustnessTest` covers the following semantic areas:

| Test | Semantic area |
|------|--------------|
| `testKnownForegroundCaller_attributedCorrectly` | Known foreground, ACTIVITY_RESUMED in window |
| `testBackgroundCaller_withRecentTransition_isAttributed` | Background FGS, transition within 5s → attributed |
| `testBackgroundCaller_withoutTransition_isUnknown` | Background FGS, no transition → UNKNOWN |
| `testCameraGuardForeground_unknownCaller_noSelfAttribution` | CG FG + UNKNOWN → `null`, not `org.cameraguard` |
| `testStaleCandidateRejection_noCameraPermission` | Candidate lacks perm + stale → UNKNOWN |
| `testPermissionDenied_noHardwareSession` | Platform denial → 0 events |
| `testDeniedThenGranted_noStateContamination` | State resets correctly after denial |
| `testCallerSwitching_noAttributionCrossContamination` | Independent sessions → independent attributions |
| `testLifecycleClosure_attributionAfterClose` | Session closure retains attribution |
| `testAntiSelfAttribution_guard_notAttributed` | `org.cameraguard` never attributed to itself |
| `testRestartTransientContextReset` | After restart, no pre-restart context leaked |
| `testDuplicateSuppression_within3sWindow` | Events within 3s window suppressed correctly |
| `testClassification_unknownContext_tier2ml` | UNKNOWN context → Tier-2 ML (AMBIGUOUS/UNEXPECTED) |
| `testClassification_legitimateContext_expected` | Legitimate foreground → EXPECTED |
| `testClassification_suspiciousContext_unexpected` | Background-only + no transition → UNEXPECTED |

---

*Generated for Phase 5.5 — Ambiguous Context & Classification Robustness*
*Device: vivo V2202 · Android 15 · API 35*
