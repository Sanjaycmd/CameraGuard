# CameraGuard — Phase 8 Acceptance Criteria

**Document ID:** `CG-DOC-P8-005-CRITERIA`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  

---

## 1. Objective

To establish strict, quantitative, and testable acceptance criteria that any Phase 8 architectural enhancement must satisfy before being merged.

---

## 2. Core Acceptance Criteria Matrix

| Domain | ID | Measurable Requirement | Verification Method |
| :--- | :--- | :--- | :--- |
| **Attribution** | `AC-ATTR-01` | When launching stock Camera from Launcher (`com.android.launcher3`), final attribution must resolve to `com.android.camera` rather than the launcher. | Physical testbed & Unit test simulation |
| **Attribution** | `AC-ATTR-02` | When launching stock Camera from Google Search / Assistant (`com.google.android.googlequicksearchbox`), final attribution must resolve to `com.android.camera`. | Physical testbed & Unit test simulation |
| **Attribution** | `AC-ATTR-03` | No package names (e.g. `com.android.launcher3`, `com.google.android.googlequicksearchbox`) may be hardcoded into whitelists or universal ignore lists. | Source code inspection & grep |
| **Detection** | `AC-DET-01` | **Zero Regression on Hardware Recall:** CameraGuard must continue to detect 100% of physical camera acquisitions (HAL transitions) with $< 100\text{ ms}$ baseline callback latency. | Automated regression suite & physical tests |
| **Suspicious Events**| `AC-SEC-01` | Screen-off camera access must continue to be classified as `UNEXPECTED` with immediate high-priority alert. | `ContextualClassificationRobustnessTest` & Unit tests |
| **Suspicious Events**| `AC-SEC-02` | Locked-device camera access without verified lockscreen intent must continue to be classified as `UNEXPECTED` with immediate alert. | Unit tests |
| **Suspicious Events**| `AC-SEC-03` | Unauthorized background probe (app with no camera permission opening camera in background) must continue to trigger `UNEXPECTED`. | Unit tests |
| **Alert Policy** | `AC-POL-01` | `TreeClassification.AMBIGUOUS` (inconclusive transition telemetry) must **not** automatically trigger a high-priority `camera_alerts` notification. | NotificationHelper unit tests & physical run |
| **Alert Policy** | `AC-POL-02` | Ambiguous and uncertain events must remain 100% persisted and auditable in the Room SQLite database (`camera_events`). | Database queries |
| **Regression** | `AC-REG-01` | Full test suite must pass with 0 failures (`>= 277` passing tests). | `./gradlew test --rerun-tasks` |
| **Historical Integrity**| `AC-INT-01` | Phase 4, 5, 6, and 7 data, models, and documentation must remain 100% unmodified relative to `33db3e8`. | `git diff 33db3e8 data/ docs/phase4/ ...` |

---

## 3. Failure Conditions (Non-Negotiable Rollback Triggers)

An implementation will be **REJECTED AND ROLLED BACK** if any of the following occur:
1. Genuine camera access during screen-off or background is suppressed or marked as expected.
2. The independent adversarial app (`org.cameraguard.adversarytest`) fails to be detected.
3. Package whitelisting is introduced.
4. Historical datasets in `data/derived/` are altered.
5. Unit tests in `:app` or `:camera-test-harness` fail.
