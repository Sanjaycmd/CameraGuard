# CameraGuard — Phase 8 Architecture Options Analysis

**Document ID:** `CG-DOC-P8-006-OPTIONS`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  

---

## 1. Candidate Architecture Matrix

| Criteria | Candidate A: Fixed Grace Window | Candidate B: Adaptive Re-query | Candidate C: Multi-Signal (Tasks API) | Candidate D: State Machine | Candidate E: Selected Hybrid (B + Policy Separation) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Core Mechanism** | Block every attribution for 500ms | Immediate query; corroborate only on LOW confidence or missing perm | Query `ActivityManager.getRunningTasks()` | Maintain PROVISIONAL vs COMMITTED states | Adaptive Corroboration + Decoupled Alert Policy |
| **Attribution Fix** | Resolves cold camera race | Resolves cold camera race | Partially blocked by API 35 sandbox | Resolves cold camera race | Resolves cold camera race |
| **Alert Escalation Fix**| None (AMBIGUOUS still alerts) | None (AMBIGUOUS still alerts) | None | None | **Resolves AMBIGUOUS false alarm completely** |
| **Impact on Warm Latency**| **+500 ms penalty on ALL events** | **0 ms penalty on warm events** | Low | Low | **0 ms penalty on warm events (9–15ms)** |
| **False-Negative Risk** | Low | Very Low (Screen-off/locked alert instantly) | High | Medium | **Zero (Security rules fire without delay)** |
| **Complexity** | Minimal | Low | High (Blocked by SELinux) | High | Moderate, clean separation |
| **Android 15 Compatibility**| High | High | Low (`REAL_GET_TASKS` restricted) | High | **100% compliant with public APIs** |

---

## 2. In-Depth Evaluation of Candidates

### Candidate A — Fixed Grace-Window Delay
* **Mechanism:** Every `onCameraUnavailable` callback sleeps or delays for 500 ms before querying `UsageStatsManager`.
* **Drawback:** Degrades the system's core P90 detection latency from 15 ms to 515 ms across all legitimate and suspicious interactions. Unacceptable regression on performance benchmarks.

### Candidate B — Adaptive Transition-Aware Re-query
* **Mechanism:** 
  1. If screen is OFF or device is LOCKED $\to$ evaluate immediately (0 ms delay).
  2. If candidate is HIGH confidence ($\Delta T \le 500\text{ ms}$) and holds camera permission $\to$ evaluate immediately (0 ms delay).
  3. If candidate lacks permission or confidence is LOW on active display $\to$ perform initial evaluation, but schedule a 450 ms corroboration check. If a camera-capable package resumes within 450 ms, promote attribution.
* **Advantage:** Preserves sub-20ms latency for all normal warm camera usage and all instant security violations.

### Candidate C — Multi-Signal Activity Telemetry
* **Mechanism:** Supplement `UsageStatsManager` with `ActivityManager.getRunningAppProcesses()` or `getRunningTasks()`.
* **Drawback:** On Android 15 (API 35), `getRunningTasks()` returns only the caller's own tasks unless granted signature-level permission `REAL_GET_TASKS`. Fails unprivileged compliance.

### Candidate D — State-Machine Transition Tracking
* **Mechanism:** Introduce `PROVISIONAL` and `COMMITTED` camera states.
* **Drawback:** Requires extensive state synchronization across asynchronous lifecycle callbacks, risking session leak if an app crashes before commit.

### Candidate E — Adaptive Transition Corroboration + Alert Policy Separation (RECOMMENDED)
* **Mechanism:** Combines Candidate B's adaptive transition corroboration with architectural decoupling of alert severity:
  1. **Attribution Layer:** Adaptive corroboration resolves the 484 ms cold launch lag without whitelisting.
  2. **Notification Layer:** Decouples `TreeClassification.AMBIGUOUS` from `AccessClassification.UNEXPECTED`. Inconclusive transition events are recorded with full fidelity in Room SQLite for research audits, while high-priority heads-up notifications are reserved strictly for genuine security violations (`UNEXPECTED`).
* **Verdict:** Comprehensively satisfies all acceptance criteria (`AC-ATTR-01` through `AC-INT-01`).
