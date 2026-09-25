# Phase 4 — Final Completion

## 1. Status

**COMPLETE AND FROZEN**

Phase 4 of the CameraGuard Android cybersecurity and camera privacy research project is formally closed. All research, physical data collection, multi-model evaluation, two-tier hybrid architecture design, consistency reconciliation, and production integration milestones have been successfully completed, audited, and verified.

---

## 2. Completed Milestones & Checkpoint History

| Phase Milestone | Description | Commit Hash | Key Deliverables / Artifacts |
| :--- | :--- | :--- | :--- |
| **Phase 4.6.1** | Readiness Audit | `902eda4` | Initial dataset quality & ML readiness audit |
| **Phase 4.6.2** | Targeted Physical Dataset Expansion | `7279ae0`, `a73c85c` | 41/41 targeted physical sessions collected; PERMISSION_DENIED semantics fixed |
| **Phase 4.6.3** | Dataset Re-audit & ML Readiness | `e724f06` | 15 raw CSVs, 652 physical telemetry events, 89 ML-included sessions |
| **Phase 4.6.4** | Multi-Model Training & Evaluation | `89c4e8f` | Logistic Regression, SVM, Decision Tree, Random Forest, Isolation Forest evaluated |
| **Phase 4.6.5** | Model Comparison & Risk Tradeoff | `031225b` | Selection of shallow Decision Tree for Task 3 contextual resolution |
| **Phase 4.7** | Hybrid Rules + ML Implementation | `01b63a7` | Two-tier hybrid cascade research prototype with full decision provenance |
| **Phase 4.7 Audit** | Final Consistency & Reconciliation | `07fc7a3` | Resolution of ACCUMULATED_89 metrics (81/89 = 91.01%, Macro-F1 = 0.9048) |
| **Phase 4.8** | Production Integration | `f14539f` | Zero-dependency native Kotlin hybrid classifier integrated into `:app` |

---

## 3. Final Research Baseline

* **Evaluated Dataset**:
  * 15 physical raw CSV files (`data/raw/`)
  * 1,193 raw records (541 duplicate export rows, 652 unique physical telemetry events)
  * 92 reconstructed logical sessions
  * 89 valid ML-included sessions (3 legacy unpolled sessions excluded)
  * 41 targeted physical sessions (41/41 valid, 0 excluded)
* **Physical Hardware Environment**:
  * Device: vivo V2202
  * OS: Android 14 (API level 34)
  * Hardware Cameras: Camera ID 0 (Back / Rear), Camera ID 1 (Front)
* **Threat Profile & Verified Malware Samples**:
  * **0 verified malware, spyware, or covert camera-access samples**.
  * All experiments represent controlled physical device conditions (user photography, foreground video apps, background services, lockscreen acquisitions, and negative controls).
* **Integrated Production Model**:
  * Native shallow Decision Tree (maximum depth 4 $\le$ 5).
  * Executed in pure Kotlin with zero external ML runtimes (no Python, no scikit-learn, no ONNX, no TensorFlow Lite).

---

## 4. Phase 4 Research Outcome

Phase 4 designed, experimentally evaluated, and integrated a two-tier hybrid decision architecture:

```text
Camera Access Event
        │
        ▼
Tier 1: Deterministic Security Rules (Frozen CameraRuleEvaluator)
        │
        ├── Definitive Rule (EXPECTED / UNEXPECTED) ──► Return Immediately (ML NOT Invoked)
        │
        └── UNKNOWN (Inconclusive Telemetry)
                │
                ▼
        Tier 2: Production Decision Tree ML (T0 Real-Time Features, F04 = -1.0)
                ├── F06 > 1.50 ─────────────────────────► LEGITIMATE ──► final EXPECTED
                ├── F06 <= 1.50, F07 <= 1.00:
                │     ├── F09 <= 1.00 ──────────────────► CONTROLS   ──► final EXPECTED
                │     └── F09 > 1.00 ───────────────────► AMBIGUOUS  ──► final UNEXPECTED (Alert)
                └── F06 <= 1.50, F07 > 1.00:
                      ├── F09 <= 1.50:
                      │     ├── F08 <= 59.5ms ──────────► AMBIGUOUS  ──► final UNEXPECTED (Alert)
                      │     └── F08 > 59.5ms ───────────► LEGITIMATE ──► final EXPECTED
                      └── F09 > 1.50 ───────────────────► AMBIGUOUS  ──► final UNEXPECTED (Alert)
```

### Audited Phase 4.7 Research Metrics

The final out-of-fold cross-validation performance of the hybrid classifier across the research cohorts:

```text
TARGETED_41 Cohort (N = 41):
  Hybrid Accuracy = 38 / 41 = 92.68%
  Macro-F1        = 0.9220
  Ambiguity Rate  = 0.0% (down from 39.0% in Tier 1 rules alone)

ACCUMULATED_89 Cohort (N = 89):
  Hybrid Accuracy = 81 / 89 = 91.01%
  Macro-F1        = 0.9048
  Ambiguity Rate  = 0.0% (down from 38.2% in Tier 1 rules alone)
```

> **Methodological Disclaimer**: These metrics represent experimental results measured against the evaluated physical research dataset under controlled conditions. They do not constitute universal statistical guarantees, nor do they guarantee error-free classification across unseen third-party device manufacturers, Android versions, or adversarial environments.

---

## 5. Production Limitations & Scope Boundaries

1. **Third-Party Runtime Permission Inobservability**: Due to Android UID and SELinux isolation boundaries, third-party runtime permission states cannot be verified by an unprivileged monitoring application. Consequently, production feature vector construction strictly clamps $F_{04} = -1.0$ (`UNVERIFIED`).
2. **Single Physical Device Scope**: The experimental dataset was collected and validated on a single physical Android smartphone (vivo V2202 running Android 14). Cross-device generalizability across heterogeneous OEM skins remains a subject for future research.
3. **Absence of Real-World Spyware**: The dataset contains zero confirmed real-world malware or APT spyware samples. All anomalous behaviors evaluate controlled foreground/background transitions and automated timer triggers.
4. **Authorized Screen-Off Continuation**: Camera continuation while the screen is off represents an authorized background persistence capability permitted by Android Foreground Services and must not be misinterpreted as confirmed unauthorized access.
5. **UsageStats Polling & Delivery Jitter**: Operating system scheduling and aggressive OEM background battery policies introduce 100–500ms lookback polling latency for activity resumption events.

---

## 6. Repository Integrity & Verification Audit

* **Frozen Base Evaluator**: `CameraRuleEvaluator.kt` is 100% byte-for-byte identical to Phase 2 baseline commit `7987c02` (`v1.0.0`).
* **Raw Telemetry**: `data/raw/**` is 100% unmodified.
* **Test Suite Verification**: `./gradlew testDebugUnitTest --rerun-tasks` passes 211/211 tests (64 `:app`, 147 `:camera-test-harness`) with 0 failures, 0 errors, 0 skipped.
* **Build Verification**: `./gradlew assembleDebug` compiles and packages cleanly.
* **Production/Research Separation**: Production `:app` contains zero dependencies on Python, scikit-learn, pandas, or `:camera-test-harness`.
