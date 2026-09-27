# CameraGuard — Phase 8.12 Comparative Analysis: Phase 6 vs Phase 8

**Document ID:** `CG-DOC-P8-013-PHASE6-VS-PHASE8`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** COMPLETE  

---

## 1. Architectural Evolution

| Dimension | Phase 6 Release Baseline (`33db3e8`) | Phase 8 Architecture (Candidate E) |
| :--- | :--- | :--- |
| **Attribution Timing** | Single-point query at $T_0$ (`inferForegroundPackage`) | Two-stage: Fast-path ($T_0$) with Adaptive Lookahead Corroboration ($T_0 + 500\text{ms}$) |
| **Framework Race Handling** | None (assumed instantaneous `UsageStatsManager` updates) | Resilient to 484–773 ms `ActivityTaskManagerService` write latency |
| **Ambiguity Triage** | Direct escalation: `TreeClassification.AMBIGUOUS` $\to$ `AccessClassification.UNEXPECTED` | Decoupled triage: `AMBIGUOUS` $\to$ Audit Log; `UNEXPECTED` $\to$ Red Alarm |
| **Alert Trigger Condition** | Any `UNEXPECTED` event (including ambiguous multitasking transitions) | Strictly true security violations (`UNEXPECTED` only) |
| **UI Presentation** | 3 states: `EXPECTED` (Green), `UNEXPECTED` (Red), `UNKNOWN` (Amber) | 4 states: `EXPECTED` (Green), `UNEXPECTED` (Red), `UNKNOWN` (Yellow), `AMBIGUOUS` (Amber) |
| **Package Whitelisting** | Strictly forbidden | Strictly forbidden (Zero package-name string exemptions) |
| **Active Unit Test Suite** | 277 tests across 3 modules | 290 tests across 3 modules (+13 new regression scenarios) |

---

## 2. Empirical Performance Comparison on Physical Hardware (vivo V2202)

| Operational Scenario | Phase 6 Outcome | Phase 8 Outcome | Architectural Explanation |
| :--- | :--- | :--- | :--- |
| **Cold Stock Camera Launch** | `UNEXPECTED` Alert (False Alarm) | `EXPECTED` (Silent) | Phase 6 attributed access to `Launcher3` at $T_0$; Phase 8 corroborates camera resumption at $T_0 + 50\text{ms}$ |
| **Warm Stock Camera Launch** | `EXPECTED` (Silent) | `EXPECTED` (Silent) | Both architectures identify active camera session owner; 0 ms delay |
| **Google Search $\to$ Camera** | `UNEXPECTED` Alert (False Alarm) | `EXPECTED` (Silent) | Phase 6 escalated low-confidence search transition; Phase 8 promotes to resumed camera |
| **Accidental Assistant Launch** | `UNEXPECTED` Alert (False Alarm) | `AMBIGUOUS` (Audit Log) | Phase 6 alerted user; Phase 8 records event to Room DB without disrupting user |
| **Screen-Off Camera Probe** | `UNEXPECTED` (Rule 2 Alert) | `UNEXPECTED` (Rule 2 Alert) | Fast-path immediately alerts user in < 5 ms in both architectures |
| **Device Locked Camera Probe** | `UNEXPECTED` (Rule 4 Alert) | `UNEXPECTED` (Rule 4 Alert) | Fast-path immediately alerts user in < 5 ms in both architectures |
| **Adversary Foreground Test** | `EXPECTED` (Controls) | `EXPECTED` (Controls) | Both architectures correctly classify intentional test app session |
| **Adversary Session Closure** | `EXPECTED` (Rule 5) | `EXPECTED` (Rule 5) | Both architectures correctly attribute release of camera to session owner |

---

## 3. Scientific Invariants & Integrity Analysis

1. **Non-Regression of Core Detection Capabilities:**
   - Binary acquisition detection accuracy remains **100.0%**. Every physical hardware state transition emitted by Camera HAL is intercepted and evaluated.
2. **Preservation of Defense-in-Depth:**
   - If a malicious background app attempts to access the camera while the user is actively on the home screen, the corroboration window re-checks for camera application resumption. Because the malicious caller is not a verified camera app, attribution remains with the unauthorized caller, correctly triggering Rule 3 (`UNEXPECTED`).
3. **Auditability & Transparency:**
   - No data is discarded. Even when heads-up alarms are silenced for `AMBIGUOUS` events, every telemetry feature vector, timestamp, raw state, and ML classification is permanently persisted to Room SQLite.
