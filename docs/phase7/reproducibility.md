# CameraGuard — Phase 7.9: Research Reproducibility Package

## 1. Reproducibility Overview

CameraGuard is engineered for 100% deterministic reproducibility. Every result reported in the research papers, evaluation reports, and figures can be regenerated from raw source telemetry using automated, open-source tooling without human intervention.

---

## 2. Environment Prerequisites

- **Host Operating System**: Linux (Ubuntu 22.04+ or compatible POSIX)
- **Java Development Kit (JDK)**: OpenJDK 17 or 21 (`JAVA_HOME` configured)
- **Android SDK**: Android API 35 SDK Platform and Build-Tools
- **Python Environment**: Python 3.10+ with `pandas`, `numpy`, `scikit-learn` installed.
  - The repository includes a pre-configured virtual environment:
    ```bash
    /home/sanjay/Projects/CameraGuard/.venv/bin/python3
    ```

---

## 3. One-Command Evaluation Reproduction

To reproduce all Phase 6 and Phase 7 metrics, confusion matrices, latency distributions, and statistical summaries:

```bash
cd /home/sanjay/Projects/CameraGuard
.venv/bin/python3 scripts/evaluation/run_phase6_evaluation.py
```

### Execution Pipeline Stages:
1. **Phase 6.1 (Integrity)**: Scans all 15 raw telemetry CSV files in `data/raw/`, verifies row counts (1,193 rows), extracts SHA-256 checksums, and audits the curated ML dataset (`session_ml_dataset.csv`).
2. **Phase 6.2 (Ground Truth)**: Evaluates mapping invariants between scenario IDs and ground truth classes.
3. **Phase 6.3 (Detection)**: Evaluates Task 2 binary hardware acquisition across Corpus A ($N=89$) and Corpus B ($N=82$), generating `phase6_detection_metrics.json` and `phase6_detection_confusion_matrix.csv`.
4. **Phase 6.4 (Scenarios)**: Generates per-scenario metrics in `phase6_scenario_metrics.csv`.
5. **Phase 6.5 (Classification)**: Computes 3-tier confusion matrices, precision, recall, and Macro-F1 across `ACCUMULATED_89` and `TARGETED_41`.
6. **Phase 6.6 (Attribution)**: Ingests Phase 5 live attribution results, verifying anti-self-attribution and transition lookback accuracy.
7. **Phase 6.7 (Context)**: Evaluates screen interactivity and permission clamping.
8. **Phase 6.8 (Latency)**: Aggregates physical callback timestamps, calculating min, max, mean, median, $\sigma$, $P_{90}$, $P_{95}$, and $P_{99}$.
9. **Phase 6.9 (Errors)**: Performs automated root-cause analysis on all 8 static discrepancies and live physical deviations.
10. **Phase 6.10–6.13 (Robustness & Summary)**: Computes subgroup slices and emits `phase6_statistical_summary.json`.

---

## 4. Full Unit Test Suite Reproduction

To reproduce the complete deterministic JVM test suite (260 tests across production and research modules):

```bash
cd /home/sanjay/Projects/CameraGuard
./gradlew testDebugUnitTest --rerun-tasks
```

Expected Output:
```
BUILD SUCCESSFUL in 20-30s
71 actionable tasks executed
```

### Module Breakdown:
- `:app`: **104 tests passing** (`ContextualClassificationRobustnessTest`, `PermissionStateRobustnessTest`, `CameraStressUnitTest`, `HybridCameraEvaluatorTest`, `CameraRuleEvaluatorTest`, `CameraAvailabilityTrackerTest`, `ContextualInferenceEngineTest`, `HistoryEventFormatterTest`, `MainScreenViewModelTest`).
- `:camera-test-harness`: **156 tests passing** (`Phase464MultiModelEvaluationTest`, `Phase465ModelComparisonTest`, `HybridResearchClassifierTest`, `SessionReconstructorTest`, `DatasetReadinessAuditTest`, `TargetedDatasetExpansionTest`, `BaselineEvaluatorTest`, `FeatureExtractorTest`, `ExperimentDataTest`, `AdversaryEvaluationTest`).

---

## 5. Physical Device Evaluation Reproduction (Optional)

If a physical Android test device running Android 15 (e.g. `vivo V2202`, API 35) is connected via ADB with USB debugging authorized:

```bash
# Verify ADB device connection
adb devices

# 1. Execute lifecycle evaluation suite (5 scenarios)
python3 scripts/research/lifecycle/run_lifecycle_evaluation.py

# 2. Execute camera stress suite (7 scenarios, 60+ transitions)
python3 scripts/research/stress/run_camera_stress_evaluation.py

# 3. Execute adversarial camera suite (6 scenarios)
python3 scripts/research/adversarial/run_adversarial_evaluation.py

# 4. Execute permission and state robustness suite (10 scenarios)
python3 scripts/research/permission/run_permission_state_evaluation.py

# 5. Execute ambiguous context classification suite (10 scenarios)
python3 scripts/research/classification/run_contextual_classification_evaluation.py
```

All empirical physical outputs will update into `data/derived/phase5/*.json`.
