# CameraGuard — Phase 8.8 Alert Policy & Severity Decoupling Report

**Document ID:** `CG-DOC-P8-009-ALERT-POLICY`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** IMPLEMENTED & VERIFIED  

---

## 1. Executive Summary

Phase 8.8 resolves the secondary false positive failure mode observed during complex multitasking transitions (e.g. Google Assistant invocation while launching Camera). 

Previously, when deterministic rules returned `UNKNOWN` and Tier-2 Decision Tree evaluated `TreeClassification.AMBIGUOUS`, the hybrid evaluator directly escalated this to `AccessClassification.UNEXPECTED`. Because `CameraMonitoringService` treated all `UNEXPECTED` classifications as active security breaches, this resulted in disruptive, user-facing high-priority heads-up alerts during benign, rapid UI transitions.

Phase 8.8 decouples classification ambiguity from security alarms by introducing `AccessClassification.AMBIGUOUS` and establishing a strict alert policy.

---

## 2. Architecture & Design Specification

### 2.1 AccessClassification Enum Extension

`AccessClassification` in `org.cameraguard.data.model`:
```kotlin
enum class AccessClassification {
    EXPECTED,
    UNEXPECTED,
    UNKNOWN,
    AMBIGUOUS
}
```

### 2.2 Two-Tier Mapping in `HybridCameraEvaluator.kt`

Tier 1 Deterministic Rules:
- Rule 1 (Foreground camera app with permission): `EXPECTED`
- Rule 2 (Screen OFF camera access): `UNEXPECTED` (Critical Alert)
- Rule 3 (Active screen, candidate lacks camera permission): `UNEXPECTED` (Critical Alert)
- Rule 4 (Device LOCKED camera access): `UNEXPECTED` (Critical Alert)
- Rule 5 (Camera closure / available): `EXPECTED`
- Inconclusive context: `UNKNOWN` $\to$ Dispatches to Tier 2 ML

Tier 2 ML Decision Tree (when Tier 1 is `UNKNOWN`):
- `TreeClassification.LEGITIMATE` $\to$ `AccessClassification.EXPECTED`
- `TreeClassification.CONTROLS` $\to$ `AccessClassification.EXPECTED`
- `TreeClassification.AMBIGUOUS` $\to$ `AccessClassification.AMBIGUOUS`

### 2.3 Alerting & Notification Policy

In `CameraMonitoringService.kt`:
```kotlin
tracker.onEventDetected = { event ->
    if (event.classification == AccessClassification.UNEXPECTED) {
        notificationHelper.showUnexpectedActivityAlert(event)
    }
}
```
- **`UNEXPECTED`:** Only true security violations (e.g. unauthorized background access, camera access while device is locked or screen is off, confirmed unprivileged caller) trigger the high-priority heads-up security notification.
- **`AMBIGUOUS`:** Fully logged to Room SQLite database for forensic auditing; displayed in the UI History Log with distinct Amber styling (`Color(0xFFE65100)`), but produces **no heads-up notification**.
- **`EXPECTED`:** Logged silently.

---

## 3. UI Integration

1. **Dashboard (`MainScreen.kt`):**
   - Displays `AMBIGUOUS` classification badge in amber (`#E65100`).
2. **Audit History (`HistoryScreen.kt`):**
   - Added dedicated `Ambiguous` filter chip.
   - Event cards render `AMBIGUOUS` events with distinct light-amber container background (`#FFF3E0`) and deep-orange text (`#E65100`).
3. **Database & Room Entity (`CameraEventEntity.kt`):**
   - Seamlessly deserializes string classifications, maintaining backward compatibility with zero database schema migrations required.

---

## 4. Verification & Regression Evidence (`8.8-GATE-B`)

- **`HybridCameraEvaluatorTest.kt`:**
  - `testC2_tier1Unknown_mlResolvesAmbiguous`: Verified that Tier 2 `AMBIGUOUS` maps to `AccessClassification.AMBIGUOUS`.
- **`ContextualClassificationRobustnessTest.kt`:**
  - Tests 13 & 15: Verified that Tier 2 ambiguous outcomes map to `AccessClassification.AMBIGUOUS`.
- **Regression Test Gate (`./gradlew test`):**
  - Total test suites: 21
  - Total unit tests executed: 282
  - Failures: 0
  - Skipped: 0
  - Errors: 0
  - Result: 100% PASS across `:app`, `:adversary-test-app`, and `:camera-test-harness`.
