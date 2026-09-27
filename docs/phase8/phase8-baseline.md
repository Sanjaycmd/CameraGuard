# CameraGuard — Phase 8 Baseline Verification

**Document ID:** `CG-DOC-P8-001-BASELINE`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** VERIFIED & ESTABLISHED  

---

## 1. Executive Summary

Phase 8 is dedicated to **Post-Release Reliability, Transition-Aware Attribution & Alert Policy**.

This document formally establishes the authoritative baseline for Phase 8, verifying the frozen Phase 7 release checkpoint, current repository status, test suite metrics, directory immutability guarantees, and the known real-world false-positive conditions to be addressed.

---

## 2. Baseline Checkpoint & Git Status

| Property | Authoritative Value | Verification Command / Result |
| :--- | :--- | :--- |
| **Phase 7 Release Commit** | `33db3e843d44b79df10317241c072aa8d3667009` (`33db3e8`) | `git log --oneline -5` |
| **Phase 7 Release Tags** | `v1.0.0-final`, `phase7-complete` | `git tag --points-at 33db3e8` |
| **Current Working Branch** | `master` | `git branch --show-current` |
| **Current HEAD Commit** | `7747234` (*Independent Adversarial App creation for Jury Demo*) | `git show --stat --oneline HEAD` |
| **Production Code Diff vs `33db3e8`**| **0 bytes in core engine** (`org.cameraguard.monitoring.*`, `org.cameraguard.data.*`) | `git diff 33db3e8 app/src/main/java/org/cameraguard/monitoring/` (Empty) |
| **Target Device Testbed** | vivo V2202 (Android 15 / API 35, USB Serial: `10BCA92F67000FY`) | `adb devices` |

---

## 3. Automated Test Baseline

A fresh execution of the entire test suite with configuration cache invalidation (`./gradlew test --rerun-tasks`) established the initial Phase 8 test metric baseline:

| Module | Test Scope | Tests Run | Failures | Errors | Skipped | Status |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **`:app`** | Monitoring, Rule Engine, Hybrid ML Evaluator, Room DB, ViewModels | 104 | 0 | 0 | 0 | **PASS** |
| **`:adversary-test-app`** | Independent Adversarial Camera Controller & Lifecycle | 17 | 0 | 0 | 0 | **PASS** |
| **`:camera-test-harness`**| Deterministic Scenarios, Evaluation Framework, Metrics Pipeline | 156 | 0 | 0 | 0 | **PASS** |
| **TOTAL** | **Full System Regression Suite** | **277** | **0** | **0** | **0** | **PASS (100%)** |

Build Result: `BUILD SUCCESSFUL in 19s (74 actionable tasks executed)`.

---

## 4. Production vs Research Separation

1. **Production Pipeline (`:app`):**
   - Purely unprivileged monitoring using public Android APIs: `CameraManager.registerAvailabilityCallback()` and `UsageStatsManager.queryEvents()`.
   - Never captures camera frames, never records video/audio, never binds private camera HAL surfaces, never requires root permissions.
   - Independent UI with Room SQLite audit persistence.
2. **Independent Adversary (`:adversary-test-app`):**
   - Independent application running under separate package `org.cameraguard.adversarytest` and distinct UID.
   - Strictly controlled camera caller for jury demonstration and physical validation.
   - Zero shared code or IPC backdoors with `org.cameraguard`.
3. **Research Harness (`:camera-test-harness`):**
   - Isolated synthetic event generator and historical evaluation test framework.

---

## 5. Frozen Historical Directories

To protect research integrity and prevent retroactive metric tampering, the following directories are permanently frozen and must remain byte-for-byte unmodified:

* `data/raw/`
* `data/derived/phase4/`
* `data/derived/phase5/`
* `data/derived/phase6/`
* `docs/phase4/`
* `docs/phase5/`
* `docs/phase6/`
* `docs/phase7/`

All new Phase 8 evidence, artifacts, and documentation must reside strictly within:
* `docs/phase8/`
* `data/derived/phase8/`

---

## 6. Known Phase 8 Problems (Motivation)

### Finding A — Stock Camera Cold Launch Attribution Race
* **Pattern:** `com.android.launcher3` $\to$ `com.android.camera`
* **Mechanism:** When the user taps the stock Camera app icon from the home screen, Camera HAL reports `onCameraUnavailable` in 50–150 ms. Android's `ActivityTaskManagerService` takes 484–773 ms to commit `ACTIVITY_RESUMED` for `com.android.camera`.
* **Failure Mode:** CameraGuard temporarily attributes the hardware access to `com.android.launcher3`. Because the launcher does not declare `android.permission.CAMERA`, Rule 3 triggers, generating a false-positive `UNEXPECTED` alert.

### Finding B — Google Quick Search / Circle to Search Transition Race & ML Escalation
* **Pattern:** `com.google.android.googlequicksearchbox` $\to$ `com.android.camera`
* **Mechanism:** When the user accidentally touches the navigation bar, Google Assistant / Circle to Search opens an overlay (`Lensient`). When the user immediately taps Camera, the camera hardware opens while Google Search is still recorded as the most recent resumed activity in `UsageStatsManager`.
* **Failure Mode:**
  1. Google Search is attributed with `LOW` confidence.
  2. CameraGuard's unprivileged sandbox cannot verify third-party runtime permissions $\to$ Tier 1 yields `UNKNOWN`.
  3. Tier 2 Decision Tree classifies rapid interaction as `AMBIGUOUS`.
  4. `HybridCameraEvaluator` directly maps `AMBIGUOUS` $\to$ `UNEXPECTED`.
  5. `CameraMonitoringService` dispatches a high-priority heads-up alert for a benign activity transition.

---

## 7. Baseline Gate Sign-Off

The baseline has been verified against `33db3e8` and working-tree status. Phase 8 is authorized to proceed to Phase 8.2 Integrity Snapshot.
