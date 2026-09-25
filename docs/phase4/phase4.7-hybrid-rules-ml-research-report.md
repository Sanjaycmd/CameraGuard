# CameraGuard Phase 4.7 — Hybrid Rules + ML Research Implementation Report

**Document ID:** `CG-DOC-P47-001`
**Phase:** Phase 4.7 (Hybrid Rules + Machine Learning Contextual Classifier Prototype)
**Status:** Completed, Audited & Reconciled
**Overall Determination:** **`TWO-TIER HYBRID ARCHITECTURE EXPERIMENTALLY VALIDATED & AUDITED`**
**Target Repository:** `Sanjaycmd/CameraGuard`
**Baseline Git Checkpoint:** `031225b` ("research: complete Phase 4.6.5 model comparison")
**Implementation Commit:** `01b63a7` ("research: implement Phase 4.7 hybrid rules ml")
**Modules Referenced:** `:app` (CameraGuard production baseline, frozen), `:camera-test-harness` (Controlled physical experiments, test suite & hybrid prototype)
**Author:** CameraGuard Research & Engineering Team
**Date:** September 26, 2026

---

## 1. Executive Summary

Phase 4.7 implements and experimentally validates a **two-tier decision architecture** combining CameraGuard's frozen deterministic rules with an interpretable shallow Machine Learning contextual classifier.

The fundamental design principles of this architecture are:
1. **Deterministic Rule Precedence (Tier 1):** Definitive rule decisions (`LEGITIMATE`, `CONTROL`) are preserved unconditionally. The machine learning model is **never** invoked on definitive events.
2. **Contextual ML Resolution (Tier 2):** When Tier 1 rules output `UNKNOWN`, a shallow Decision Tree ($D=5$) evaluates production-observable features available at activation timestamp $T_0$ to resolve ambiguous context.
3. **Strict Feature Feasibility:** Telemetry relies solely on features observable without special platform privileges. Runtime camera permission telemetry ($F_{04}$) is clamped to `UNVERIFIED` (-1.0), and zero retrospective post-activation metrics ($F_{12}\dots F_{20}$) are admitted.
4. **Complete Provenance:** Every classification records its full decision lineage (`tier_used`, `deterministic_result`, `ml_result`, `final_result`, `ml_invoked`).

### Key Empirical Findings

```text
Cohort: TARGETED_41 (N=41 physical sessions, Android 14 vivo V2202)
-------------------------------------------------------------------------------------
Metric                       System A (Rules)   System B (Pure DT)   System C (Hybrid)
-------------------------------------------------------------------------------------
Overall True Accuracy             60.98%              90.24%              92.68%
Macro-F1 Score                    0.6098              0.9006              0.9220
Tier 1 Rule Coverage              60.98%               0.00%              60.98%
UNKNOWN Ambiguity Rate            39.02%               0.00%              39.02%
ML Invocation Rate                 0.00%             100.00%              39.02%
UNKNOWN Resolution Rate            0.00%             100.00%             100.00%
ML Subset Accuracy                  N/A               90.24%              81.25%
False Legitimate Count                 0                   0                   0
False Ambiguous Count                  0                   1                   0
-------------------------------------------------------------------------------------
```

- **Accuracy vs. Resolution Rate:** The hybrid architecture achieves a **100.0% UNKNOWN resolution rate** (16/16 ambiguous sessions resolved) and a **92.68% true classification accuracy** (38/41 sessions correctly classified against ground truth).
- **False Alarm Mitigation on Evaluated Dataset:** System B (Standalone Decision Tree) exhibited 1 false alarm where normal foreground photography was classified as `AMBIGUOUS`. System C (Hybrid Cascade) prevented this error (**0 False Ambiguous** on `TARGETED_41`), because deterministic Rule 4 classified verified user photography before ML was evaluated.
- **Threat Model Grounding:** The physical dataset contains **zero verified malware or covert spyware samples**. Ambiguous events stem from automated background triggers designed to evaluate telemetry boundary conditions.

---

## 2. Background & Problem Statement

CameraGuard's Phase 2 baseline established a deterministic rule engine (`CameraRuleEvaluator.kt`) operating on real-time Android platform events. While deterministic rules provide zero-latency execution, complete auditability, and zero false alarms on clear user interactions, their strict condition boundaries leave complex, overlapping context unclassified.

Specifically, when an application accesses the camera while user focus is fragmented, the screen is transitioning, or background services are active, the rule engine emits `UNKNOWN`. In the targeted physical dataset of Phase 4.6.2, **39.02% of all sessions resulted in `UNKNOWN`**.

In Phase 4.6.4 and Phase 4.6.5, standalone machine learning models demonstrated strong contextual discrimination. However, replacing deterministic rules entirely with an unconstrained ML model introduces critical failure modes:
1. **Loss of Verifiable Invariants:** Well-defined security bounds (such as verified foreground user photography) become subject to statistical boundary shifts.
2. **False Alarms on Legitimate Usage:** Standalone ML models misclassify edge-case legitimate camera usage as suspicious (e.g., Standalone DT misclassified 1 legitimate session as `AMBIGUOUS`).
3. **Execution Overhead:** Invoking ML models on every camera callback consumes unnecessary CPU cycles and battery.

The solution is a **cascaded two-tier architecture** where rules act as an authoritative filter and ML acts solely as a contextual resolver for edge cases.

---

## 3. Two-Tier Decision Architecture

The hybrid evaluation pipeline executes in strict sequential order:

```text
                       +---------------------------------------+
                       |           Camera Event at T0          |
                       +---------------------------------------+
                                           |
                                           v
                       +---------------------------------------+
                       |       Tier 1: Deterministic Rules     |
                       |       (CameraRuleEvaluator Semantics) |
                       +---------------------------------------+
                                           |
                    +----------------------+----------------------+
                    |                                             |
            Definitive Result                              UNKNOWN Result
       (LEGITIMATE / CONTROL)                                     |
                    |                                             v
                    |                         +---------------------------------------+
                    |                         |     Tier 2: ML Contextual Resolver    |
                    |                         |      (Shallow Decision Tree D=5)      |
                    |                         +---------------------------------------+
                    |                                             |
                    |                                             v
                    |                                     ML Classification
                    |                                  (AMBIGUOUS / CONTROLS /
                    |                                       LEGITIMATE)
                    |                                             |
                    +----------------------+----------------------+
                                           |
                                           v
                       +---------------------------------------+
                       |        Final Hybrid Decision          |
                       |  + Full Cryptographic/Audit Metadata  |
                       +---------------------------------------+
```

### Architectural Invariants
1. **Rule Primacy:** If Tier 1 produces a definitive result (`LEGITIMATE` or `CONTROL`), this decision is **final and immutable**. Tier 2 is bypassed entirely (`ml_invoked = false`).
2. **Conditional Invocation:** Tier 2 is invoked **if and only if** Tier 1 produces `UNKNOWN` (`ml_invoked = true`).
3. **Provenance Integrity:** Every decision is bundled into a `HybridDecisionResult` recording:
   - `tierUsed`: `TIER_1_RULE` or `TIER_2_ML`
   - `deterministicResult`: Raw output from Tier 1
   - `mlResult`: Raw output from Tier 2 (`null` if Tier 1 was definitive)
   - `finalResult`: The conclusive system classification
   - `mlInvoked`: Boolean flag indicating execution path

---

## 4. Decision Tier Semantics & Complete Provenance

### Tier 1: Deterministic Rules

The Tier 1 rule evaluator implements the exact logic of Phase 2 `CameraRuleEvaluator`:

- **Rule 1 (Background & Locked):** Screen off or device locked $\implies$ Emits `UNKNOWN` (under baseline evaluator semantics, unverified background access is flagged for investigation).
- **Rule 2 (No Camera Permission):** Permission denied $\implies$ Emits `CONTROL`.
- **Rule 3 (No Active Camera Stream):** Non-acquisition condition $\implies$ Emits `CONTROL`.
- **Rule 4 (Foreground & Interactive & Low Delta):** Screen interactive, device unlocked, package confidence HIGH, inference method FOREGROUND, and $\Delta T_{\text{resumed}} \le 2000\,\text{ms}$ $\implies$ Emits `LEGITIMATE`.
- **Default:** If none of the definitive criteria are satisfied $\implies$ Emits `UNKNOWN`.

### Tier 2: Machine Learning Contextual Classifier

When Tier 1 returns `UNKNOWN`, Tier 2 evaluates the activation feature vector $\mathbf{x} \in \mathbb{R}^{11}$. The shallow decision tree maps the input to one of three discrete classes:
- `LEGITIMATE`: Context indicates legitimate user photography that failed strict Rule 4 thresholds (e.g., slight timing jitter, non-standard activity resumption).
- `AMBIGUOUS`: Context indicates background camera activation, automated triggers, or suspicious context requiring security alerting.
- `CONTROLS`: Context indicates non-acquisition or benign negative controls (e.g., aborted acquisitions, unacquired permission trials).

---

## 5. Feature Contract & Strict Production Feasibility

The hybrid classifier operates strictly under the `PRODUCTION_T0` contract established in Phase 4.1 and validated in Phase 4.4:

### Feature Contract Specification

| Feature Index | Feature Name | Description | Production Feasibility & Constraints |
| :---: | :--- | :--- | :--- |
| $F_{01}$ | `screen_state` | Display state at activation | Accessible via `Display.getState()` |
| $F_{02}$ | `is_interactive` | Power manager interactive state | Accessible via `PowerManager.isInteractive()` |
| $F_{03}$ | `is_locked` | Keyguard lock state | Accessible via `KeyguardManager.isKeyguardLocked()` |
| $F_{04}$ | `perm_clamped` | Runtime permission status | **Strictly Clamped to `UNVERIFIED` (-1.0)** |
| $F_{05}$ | `known_camera_app` | Package whitelist match | Package verification against known camera packages |
| $F_{06}$ | `package_confidence`| Context inference confidence | Inferred confidence level: HIGH (2.0), MED (1.5), LOW (1.0) |
| $F_{07}$ | `inference_method` | Telemetry inference method | Method: FOREGROUND (2.0), RECENT (1.5), NONE (1.0) |
| $F_{08}$ | `delta_resumed_ms` | Milliseconds since app resumed | Elapsed duration between resume and camera open |
| $F_{09}$ | `recent_activity_cnt`| Foreground activity count | Recent activities in window |
| $F_{10}$ | `camera_id` | Physical camera hardware ID | Observable via `CameraManager.AvailabilityCallback` |
| $F_{11}$ | `is_back_camera` | Camera lens facing direction | Extracted from camera characteristics |

### Critical Leakage Prohibitions
1. **$F_{04}$ Permission Oracle Invariance:** To reflect unprivileged production constraints, $F_{04}$ is strictly clamped to `-1.0`. The Kotlin classifier enforces `require(features.f04PermClamped == -1.0)`.
2. **Zero Retrospective Features:** Features $F_{12}\dots F_{20}$ (session duration, frame counts, termination triggers, post-hoc user interaction) are **strictly barred**.
3. **Zero Target/Scenario Leakage:** Ground truth labels and scenario IDs are never accessible to the classification pipeline.

---

## 6. Machine Learning Model Selection & Justification

Phase 4.6.5 evaluated five candidate models. A **Shallow Decision Tree** (`max_depth=5`, `min_samples_split=3`) was selected as the Tier 2 engine based on four empirical and engineering criteria:

1. **Comparable Discrimination on Targeted Data:** On the clean `TARGETED_41` cohort, Decision Tree matched Random Forest, Logistic Regression, and Linear SVM (**90.24% OOF Accuracy, 0.9006 Macro-$F_1$**).
2. **Zero False Legitimates on Targeted Data:** Decision Tree produced **0 false legitimate classifications** on `TARGETED_41`.
3. **100% Native Code Portability:** A shallow decision tree compiles directly into nested Kotlin `if-else` branch logic ([`HybridResearchClassifier.kt`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/research/hybrid/HybridResearchClassifier.kt)). It requires:
   - **Zero external dependencies** (no TensorFlow Lite, ONNX, or Python runtime).
   - **Zero heap allocations** during inference.
   - Microsecond-bounded execution latency.
4. **Complete Auditability:** Every classification path can be traced and verified against clear security thresholds.

---

## 7. Cross-Validation & Out-of-Fold Protocol

To ensure leakage-free and reproducible evaluation:
- **5-Fold Stratified K-Fold:** Folds were generated using `StratifiedKFold(n_splits=5, shuffle=True, random_state=42)` on the session-level dataset, where each unit of observation is a unique logical session ID (1 row = 1 session).
- **Strict Out-of-Fold (OOF) Inference:** Every prediction recorded in `hybrid_oof_predictions.csv` was generated by an estimator trained strictly on the other 4 folds.
- **Separate Fitting for Prototype Export:** The exported tree structure (`decision_tree_model.json` and `decision_tree_structure.txt`) represents the final fit on the entire research dataset for prototype deployment, distinctly separated from the OOF validation matrices.

---

## 8. Systems Under Comparison

Three systems were benchmarked under identical conditions:

- **System A — Deterministic Baseline Rules:** The frozen Phase 2 rule engine (`CameraRuleEvaluator`). Unambiguous sessions are classified; ambiguous sessions remain `UNKNOWN`.
- **System B — Standalone Decision Tree:** The shallow decision tree applied directly to all sessions without rule pre-filtering.
- **System C — Hybrid Rule + Decision Tree Cascade:** The proposed two-tier architecture. Definitive rules execute first; Decision Tree resolves `UNKNOWN` states.

---

## 9. Empirical Results: Targeted Physical Dataset (`TARGETED_41`)

The targeted cohort comprises 41 physical sessions collected on Android 14 across all 7 experimental scenarios.

### Table 1: Targeted Dataset Comparative Performance

| Evaluation Metric | System A (Rules) | System B (Standalone DT) | System C (Hybrid Cascade) |
| :--- | :---: | :---: | :---: |
| **Total Physical Sessions** | 41 | 41 | 41 |
| **Overall True Accuracy** | 60.98% (25/41) | 90.24% (37/41) | **92.68% (38/41)** |
| **Macro-$F_1$ Score** | 0.6098 | 0.9006 | **0.9220** |
| **Tier 1 Rule Coverage** | 60.98% (25/41) | 0.00% (0/41) | **60.98% (25/41)** |
| **UNKNOWN Ambiguity Rate** | 39.02% (16/41) | 0.00% (0/41) | **39.02% (16/41)** |
| **ML Invocation Rate** | 0.00% (0/41) | 100.00% (41/41) | **39.02% (16/41)** |
| **UNKNOWN Resolution Rate** | 0.00% (0/16) | 100.00% (41/41) | **100.00% (16/16)** |
| **ML Subset Accuracy** | N/A | 90.24% (37/41) | **81.25% (13/16)** |
| **False Legitimate Count** | 0 | 0 | **0** |
| **False Ambiguous Count** | 0 | 1 | **0** |

### Confusion Matrix Breakdown (Targeted Cohort)

```text
System B: Standalone Decision Tree (OOF)
                 Predicted AMBIGUOUS   Predicted CONTROLS   Predicted LEGITIMATE
True AMBIGUOUS           13                    3                     0
True CONTROLS             0                   10                     0
True LEGITIMATE           1                    0                    14

System C: Hybrid Cascade (OOF)
                 Predicted AMBIGUOUS   Predicted CONTROLS   Predicted LEGITIMATE
True AMBIGUOUS           13                    3                     0
True CONTROLS             0                   10                     0
True LEGITIMATE           0                    0                    15
```

---

## 10. Empirical Results: Accumulated Dataset (`ACCUMULATED_89`)

The accumulated dataset includes all 89 valid physical sessions across historical, calibration, and targeted phases.

### Table 2: Accumulated Dataset Comparative Performance

| Evaluation Metric | System A (Rules) | System B (Standalone DT) | System C (Hybrid Cascade) |
| :--- | :---: | :---: | :---: |
| **Total Physical Sessions** | 89 | 89 | 89 |
| **Overall True Accuracy** | 61.80% (55/89) | 86.52% (77/89) | **91.01% (81/89)** |
| **Macro-$F_1$ Score** | 0.6180 | 0.8624 | **0.9048** |
| **Tier 1 Rule Coverage** | 61.80% (55/89) | 0.00% (0/89) | **61.80% (55/89)** |
| **UNKNOWN Ambiguity Rate** | 38.20% (34/89) | 0.00% (0/89) | **38.20% (34/89)** |
| **ML Invocation Rate** | 0.00% (0/89) | 100.00% (89/89) | **38.20% (34/89)** |
| **UNKNOWN Resolution Rate** | 0.00% (0/34) | 100.00% (89/89) | **100.00% (34/34)** |
| **ML Subset Accuracy** | N/A | 86.52% (77/89) | **76.47% (26/34)** |
| **False Legitimate Count** | 0 | 1 | **1** |
| **False Ambiguous Count** | 0 | 0 | **0** |

### Confusion Matrix Breakdown (Accumulated Cohort)

```text
System C: Hybrid Cascade (OOF, ACCUMULATED_89)
                 Predicted AMBIGUOUS   Predicted CONTROLS   Predicted LEGITIMATE
True AMBIGUOUS           26                    7                     1
True CONTROLS             0                   22                     0
True LEGITIMATE           0                    0                    33
```

---

## 11. 3-Way Comparative Evaluation (System A vs B vs C)

1. **System A (Deterministic Rules): High Precision, Moderate Coverage**
   - Correctly classifies 60.98% of sessions (all verified foreground or negative controls).
   - Exhibits a 39.02% ambiguity rate (`UNKNOWN`).
2. **System B (Standalone ML): High Coverage, Subject to Edge False Alarms**
   - Resolves all sessions into operational classes.
   - Misclassifies 1 legitimate user photography session as `AMBIGUOUS` on `TARGETED_41`.
   - In `ACCUMULATED_89`, Standalone ML misclassified 4 legitimate sessions as `CONTROLS`.
3. **System C (Hybrid Cascade): Practical Synthesis**
   - Tier 1 Rule 4 deterministically shields legitimate user photography, preventing the false alarm observed in Standalone ML on `TARGETED_41`.
   - Increases overall accuracy from 60.98% (Rules) and 90.24% (ML) to **92.68%**.
   - Achieves a **100.0% UNKNOWN resolution rate** while executing ML on only 39.02% of events, leaving ML dormant during straightforward usage.

---

## 12. Tier 1 Coverage, UNKNOWN Rate, and UNKNOWN Resolution Rate

- **Tier 1 Coverage Rate:** $25 / 41 = 60.98\%$ in `TARGETED_41` and $55 / 89 = 61.80\%$ in `ACCUMULATED_89`.
- **Baseline UNKNOWN Rate:** $16 / 41 = 39.02\%$ in `TARGETED_41` and $34 / 89 = 38.20\%$ in `ACCUMULATED_89`.
- **UNKNOWN Resolution Rate:** In both cohorts, Tier 2 assigns a discrete classification to $100.0\%$ of the `UNKNOWN` sessions ($16/16$ and $34/34$).
- **Resource Footprint:** Because 61.0% of camera activations are handled deterministically by Tier 1, the on-device ML engine remains completely uncalled during standard user photography, evaluating features only on ambiguous cases.

---

## 13. Resolution Rate vs. True Accuracy Clarification

A critical scientific distinction established in Phase 4.6.5 and audited here is the difference between **Resolution Rate** and **Classification Accuracy**:

$$\text{Resolution Rate} = \frac{N_{\text{ambiguous resolved}}}{N_{\text{ambiguous total}}} = \frac{16}{16} = 100.0\%$$

$$\text{Ground-Truth Classification Accuracy} = \frac{N_{\text{correct classifications}}}{N_{\text{total sessions}}} = \frac{38}{41} = 92.68\%$$

Phase 4.6.4 reported a "1.000" hybrid resolution metric. As audited, that metric measured **Resolution Rate** (100% of ambiguous states assigned an operational class), **NOT classification accuracy**. True classification accuracy is **92.68% on `TARGETED_41`** and **91.01% on `ACCUMULATED_89`**.

---

## 14. False Legitimate & False Ambiguous Security Analysis

### Specific Transition Counts

| Transition Category | Description | `TARGETED_41` | `ACCUMULATED_89` | Risk Level |
| :--- | :--- | :---: | :---: | :--- |
| **Ambiguous $\to$ Legitimate** | Background/suspicious classified as normal | **0** | **1** | Critical Security Risk |
| **Control $\to$ Legitimate** | Negative control classified as normal | **0** | **0** | Moderate Risk |
| **Legitimate $\to$ Ambiguous** | User photography classified as suspicious | **0** | **0** | False Alarm / Friction |
| **Legitimate $\to$ Control** | User photography classified as non-acquisition | **0** | **0** | Availability Risk |

- **False Legitimate Rate:**
  - `TARGETED_41`: **0.0% (0/41)**. Zero suspicious background activations were misclassified as legitimate photography.
  - `ACCUMULATED_89`: Exactly 1 session (`exp_20260925_205242_fa728c`), a legacy calibration trial where foreground telemetry remained active during background access.
- **False Ambiguous Rate:**
  - Standalone ML produced 1 false alarm on `TARGETED_41`.
  - Hybrid Cascade achieved **0 false alarms (0/41)** on `TARGETED_41` because Tier 1 Rule 4 classified verified foreground usage before ML was invoked.

---

## 15. In-Depth Error Analysis & Misclassification Breakdown

Recorded in `hybrid_error_analysis.csv`:

### Table 3: Error Breakdown (`TARGETED_41`, $N=3$)

| Session ID | Scenario ID | Ground Truth | System C Prediction | Error Category | Tier Used | Physical Root Cause |
| :--- | :--- | :--- | :--- | :--- | :---: | :--- |
| `exp_20260926_000409_rep1_8e3053` | `AMBIGUOUS_CONTEXT` | `AMBIGUOUS` | `CONTROLS` | `AMBIGUOUS_TO_CONTROLS_NON_ACQ` | `ML` | Hardware acquisition not attempted |
| `exp_20260926_000424_rep2_41fc88` | `AMBIGUOUS_CONTEXT` | `AMBIGUOUS` | `CONTROLS` | `AMBIGUOUS_TO_CONTROLS_NON_ACQ` | `ML` | Hardware acquisition not attempted |
| `exp_20260926_000436_rep3_50bdfb` | `AMBIGUOUS_CONTEXT` | `AMBIGUOUS` | `CONTROLS` | `AMBIGUOUS_TO_CONTROLS_NON_ACQ` | `ML` | Hardware acquisition not attempted |

### Root Cause Analysis of `AMBIGUOUS_TO_CONTROLS_NON_ACQ`
In Scenario 6 (`AMBIGUOUS_CONTEXT`), the test harness scheduled background activity but intentionally refrained from opening the camera hardware sensor (`f10_camera_id = -1.0`, `f11_is_back_camera = -1.0`). Because no physical hardware acquisition occurred, the feature vector was indistinguishable from negative control sessions (`CONTROLS`). The ML model classified these sessions as `CONTROLS`.

---

## 16. Reconciliation Between Phase 4.6.5 and Phase 4.7

An independent audit investigated the difference between the hybrid accuracy reported in Phase 4.6.5 (**92.13%**, 82/89) and Phase 4.7 (**91.01%**, 81/89).

A complete session-by-session comparison artifact was generated at:
`data/derived/phase4/hybrid/phase465_phase467_reconciliation.csv`

### Reconciliation Findings

1. **Model Architecture Difference:**
   - In Phase 4.6.4 and Phase 4.6.5, Task 6 evaluated a **Hybrid Rule + Random Forest Ensemble** (`Hybrid_Rule_RF_Ensemble`, 50 trees).
   - In Phase 4.7, following the production feasibility requirement for zero-dependency native Kotlin execution, the contextual resolver was implemented as a **Single Shallow Decision Tree** (`max_depth=5`, `min_samples_split=3`).
2. **Targeted Cohort Equivalence:**
   - On `TARGETED_41`, Decision Tree and Random Forest perform **identically**:
     - Both achieve **38/41 = 92.68% overall accuracy** and **0.9220 Macro-$F_1$**.
     - Both achieve 13/16 on the ML subset with 0 false alarms and 0 false legitimates.
3. **The Single Discrepant Session in Accumulated Data:**
   Across all 89 sessions in `ACCUMULATED_89`, exactly **one session** changed prediction:

   | Field | Value |
   | :--- | :--- |
   | **Session ID** | `exp_20260925_205242_fa728c` |
   | **Cohort** | `BASELINE_16` (Historical Phase 4.2 calibration) |
   | **Scenario** | `BACKGROUND_CAMERA_CONTINUATION` |
   | **Ground Truth** | `AMBIGUOUS` |
   | **Tier 1 Rule Result** | `UNKNOWN` (Delegated to Tier 2 ML) |
   | **Phase 4.6.5 (Random Forest)** | `AMBIGUOUS` (Correct $\implies$ 82/89 = 92.13%) |
   | **Phase 4.7 (Decision Tree)** | `LEGITIMATE` (Incorrect $\implies$ 81/89 = 91.01%) |

The difference of 1 session between 92.13% and 91.01% is not an implementation error or data corruption. It reflects the expected boundary difference between an ensemble of 50 trees and a single 5-depth Decision Tree on an early calibration session where foreground telemetry persisted during background execution.

---

## 17. Decision Tree Structure & Rule Transparency

The final research model fitted on all valid sessions (`FINAL_RESEARCH_FIT`) produces a transparent 5-level decision tree:

```text
CameraGuard Phase 4.7 — Decision Tree Structure (FINAL_RESEARCH_FIT)
Classes: ['AMBIGUOUS', 'CONTROLS', 'LEGITIMATE']

|--- f06_package_confidence <= 1.50
|   |--- f07_inference_method <= 1.00
|   |   |--- f09_recent_activity_count <= 1.00
|   |   |   |--- f03_is_locked <= -0.50
|   |   |   |   |--- class: CONTROLS
|   |   |   |--- f03_is_locked >  -0.50
|   |   |   |   |--- class: CONTROLS
|   |   |--- f09_recent_activity_count >  1.00
|   |   |   |--- class: AMBIGUOUS
|   |--- f07_inference_method >  1.00
|   |   |--- f09_recent_activity_count <= 1.50
|   |   |   |--- f08_delta_resumed_ms <= 59.50
|   |   |   |   |--- class: AMBIGUOUS
|   |   |   |--- f08_delta_resumed_ms >  59.50
|   |   |   |   |--- class: LEGITIMATE
|   |   |--- f09_recent_activity_count >  1.50
|   |   |   |--- class: AMBIGUOUS
|--- f06_package_confidence >  1.50
|   |--- class: LEGITIMATE
```

### Learned Threshold Interpretation
1. **$F_{06}$ Package Confidence ($> 1.50$):** High package confidence routes directly to `LEGITIMATE`.
2. **$F_{07}$ Inference Method ($\le 1.00$):** Absence of active foreground activity routes toward `CONTROLS` or `AMBIGUOUS`.
3. **$F_{08}$ Delta Resumed ($\le 59.50\,\text{ms}$):** Rapid camera opening within 59.5 ms of activity resume indicates automated background triggering rather than manual user interaction, routing to `AMBIGUOUS`.
4. **$F_{09}$ Recent Activity Count ($> 1.50$):** Rapid task switching concurrent with camera opening indicates fragmented context, routing to `AMBIGUOUS`.

---

## 18. Kotlin Implementation Architecture

Implemented natively in [`HybridResearchClassifier.kt`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/main/java/org/cameratestharness/research/hybrid/HybridResearchClassifier.kt):

- Implements `classify(features, deterministicResult)`:
  - If `deterministicResult != UNKNOWN` $\implies$ returns definitive result immediately, setting `mlInvoked = false`.
  - If `deterministicResult == UNKNOWN` $\implies$ validates feature vector, evaluates `decisionTreeEvaluator(features)`, and sets `mlInvoked = true`.
- Implements `evaluateDefaultDecisionTree(features)`:
  - Native Kotlin execution of the exact tree structure from `decision_tree_model.json`.
  - Verified in unit tests to match scikit-learn tree predictions across all branches.

---

## 19. Test Suite Verification & Validation

The test suite in [`HybridResearchClassifierTest.kt`](file:///home/sanjay/Projects/CameraGuard/camera-test-harness/src/test/java/org/cameratestharness/research/hybrid/HybridResearchClassifierTest.kt) validates 12 test cases:

1. `test 01` — Tier 1 `LEGITIMATE` skips ML execution.
2. `test 02` — Tier 1 `CONTROL` skips ML execution.
3. `test 03` — Tier 1 `UNKNOWN` invokes Decision Tree.
4. `test 04` — ML output becomes final result only when Tier 1 is `UNKNOWN`.
5. `test 05` — $F_{04} \neq -1.0$ triggers immediate `IllegalArgumentException`.
6. `test 06` — Prohibited feature terms and retrospective features ($F_{12}\dots F_{20}$) are rejected.
7. `test 07` — Full provenance and tier tracking recorded for every session.
8. `test 08` — Phase 4.7 model artifacts exist and are valid.
9. `test 09` — Three-way system comparison validates hybrid improvement.
10. `test 10` — Error analysis confirms zero false legitimate errors on targeted cohort.
11. `test 11` — `evaluateDefaultDecisionTree` corresponds branch-for-branch to exported `decision_tree_model.json`.
12. `test 12` — Reconciliation artifact records exact single-session discrepancy.

**Test Execution Status:** **193 unit tests passing across `:app` and `:camera-test-harness`** (0 failures, 0 errors, 0 skipped). Clean APK build verified via `./gradlew assembleDebug`.

---

## 20. Computational Overhead & On-Device Profile

Inference is computationally lightweight because the selected tree is implemented as bounded conditional branches with no external ML runtime.
- **Memory Overhead:** 0 heap allocations during inference.
- **Dependencies:** 0 third-party native libraries (no TFLite or ONNX).
- **Execution Path:** Evaluated on only 39.02% of camera events; dormant during straightforward user photography.

---

## 21. Research Limitations & Edge Cases

1. **Controlled Harness Stimuli:** Ambiguous context and background triggers were generated by test harness services to evaluate telemetry edge cases, not commercial spyware.
2. **Aborted Acquisition Artifacts:** Trials where acquisition was scheduled but aborted produce null hardware IDs ($F_{10} = -1.0$), causing the classifier to map them to `CONTROLS`.
3. **Single Device Validation:** Testing was conducted on a `vivo V2202` running Android 14. Vendor-specific background execution policies may introduce timing variations.
4. **Scope:** Implemented strictly in `:camera-test-harness` as a research prototype.

---

## 22. Threat Model Grounding & Security Posture

### Mandatory Declarations
1. **Zero Verified Malware Samples:**
   > *The CameraGuard experimental dataset contains zero verified malicious or covert unauthorized camera-access samples. Automated background triggers evaluate telemetry boundaries under controlled research conditions, NOT confirmed malware.*
2. **Threat Scope:** This research evaluates unprivileged detection of background and automated camera activations. It does not evaluate resistance to rootkits, kernel exploits, or compromised OS frameworks.

---

## 23. Production Readiness Assessment & Future Path

- **Status:** Validated research prototype in `:camera-test-harness`.
- **Baseline Freeze:** Production module `:app` remains 100% frozen at commit `7987c02` (`v1.0.0`).
- **Future Production Requirements:**
  - Multi-OEM validation across diverse background execution managers.
  - User experience evaluation of notification frequency on resolved ambiguous states.

---

## 24. Reproducibility & Research Artifact Inventory

All Phase 4.7 artifacts are preserved in `data/derived/phase4/hybrid/`:

| Artifact Name | Size | Purpose |
| :--- | :---: | :--- |
| `hybrid_oof_predictions.csv` | 12.0 KB | 130 out-of-fold predictions with full provenance |
| `hybrid_metrics.csv` | 1.2 KB | Authoritative metrics across System A, B, and C |
| `hybrid_error_analysis.csv` | 1.6 KB | Complete ledger of all 12 misclassified sessions |
| `phase465_phase467_reconciliation.csv` | 8.8 KB | Session-by-session reconciliation between 4.6.5 and 4.7 |
| `hybrid_provenance.csv` | 17.0 KB | Session-level execution traces and tier records |
| `decision_tree_model.json` | 2.7 KB | Machine-readable JSON representation of decision tree |
| `decision_tree_structure.txt` | 961 B | Human-readable ASCII decision tree structure |
| `hybrid_manifest.json` | 1.8 KB | Cryptographic and threat model manifest |
| `phase4.7-hybrid-rules-ml-research-report.md` | 24.0 KB | Full research report |

---

## 25. Sign-Off & Phase Completion

Phase 4.7 is **fully completed, tested, audited, and reconciled**.
Per explicit instructions, **execution has stopped. We will NOT proceed to Phase 4.8 until explicitly directed.**
