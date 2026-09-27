# CameraGuard — Phase 7.5: Production vs Research Separation Audit

## 1. Executive Summary

This report establishes the formal audit of separation between CameraGuard's production application (`:app`) and its research evaluation infrastructure (`:camera-test-harness`, `:adversary-test-app`, `scripts/`, `data/`).

A primary architectural requirement of CameraGuard is that the production application must function as a standalone, production-grade Android utility without dependencies on test fixtures, evaluation datasets, research scripts, or synthetic test harnesses.

---

## 2. Module Boundary Analysis

### 2.1 Gradle Module Structure

```
                             Project Root
                                  |
     +----------------------------+----------------------------+
     |                            |                            |
    :app                :camera-test-harness          :adversary-test-app
 (Production)                (Research)                   (Adversary)
     |                            |                            |
- Standalone APK             - Research Testbed           - Test Services
- AndroidManifest.xml        - ML Feature Extraction      - Exported Components
- Zero test harness deps     - Model Cross-Validation     - Controlled Access
- SQLite Room DB             - Evaluates :app via API     - Completely isolated
- Native Decision Tree       - Depends on :app            - Separate APK
```

### 2.2 Dependency Direction
Inspecting `app/build.gradle.kts`:
- `:app` has **ZERO** dependencies on `:camera-test-harness`.
- `:app` has **ZERO** dependencies on `:adversary-test-app`.
- `:app` has **ZERO** dependencies on Python, scikit-learn, or external research scripts.

Inspecting `camera-test-harness/build.gradle.kts`:
- `:camera-test-harness` depends on `:app` (`implementation(project(":app"))`) solely to execute research evaluations against production classes (`CameraRuleEvaluator`, `HybridCameraEvaluator`, `ContextualInferenceEngine`).
- This dependency is strictly one-way: Research $\to$ Production.

---

## 3. Production Independence Audit

| Requirement | Audit Finding | Status |
|---|---|---|
| **Synthetic Event Injection** | Controlled through dedicated `SyntheticEventGenerator` inside `:app` for demo purposes, explicitly flagged `isSynthetic = true`. Production monitoring does not rely on synthetic events. | **VERIFIED** |
| **Research Datasets** | Historical CSV files in `data/raw/` and `data/derived/` are **NOT** packaged into the production APK assets or resources. | **VERIFIED** |
| **Decision Tree Execution** | Production uses `ProductionDecisionTree.kt`, a native, zero-dependency Kotlin object with $depth \le 5$. It does not load external JSON/pickle models at runtime. | **VERIFIED** |
| **Feature Extraction** | Production uses `ProductionT0FeatureVector.kt` extracting 11 real-time features. Retrospective features ($F_{12}$–$F_{20}$) are strictly absent from production. | **VERIFIED** |
| **Evaluation Scripts** | All Python evaluation scripts reside in `scripts/research/` and `scripts/evaluation/`. None are packaged into Android APKs. | **VERIFIED** |

---

## 4. APK Packaging Audit

Building `:app:assembleDebug` produces `app-debug.apk`:
- Contains solely production classes and AndroidX runtime libraries.
- No dataset files, CSVs, or research test manifests packaged into APK.
- Size and DEX method count are strictly minimal.

**Verdict: PRODUCTION AND RESEARCH INFRASTRUCTURE ARE 100% CLEANLY SEPARATED.**
