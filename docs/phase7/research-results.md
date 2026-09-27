# CameraGuard — Phase 7.8: Final Research Results Package

## 1. Authoritative Evaluation Summary

This document packages the final, authoritative experimental results of the CameraGuard research project, establishing empirical performance across two validated corpora:
- **Corpus A ($N=89$ Included Sessions, 1,193 Raw Events)**: Algorithmic cross-validation across the static research dataset.
- **Corpus B ($N=82$ Live Executions)**: System-level physical device evaluation on `vivo V2202` (Android 15 / API 35).

---

## 2. Detection Performance (Task 2 Binary Sensor Acquisition)

### 2.1 Confusion Matrices

```
                   Corpus A Confusion Matrix (N=89)
                        Predicted Positive    Predicted Negative
Actual Positive (56)           56 (TP)                0 (FN)
Actual Negative (33)            0 (FP)               33 (TN)

                   Corpus B Confusion Matrix (N=82)
                        Predicted Positive    Predicted Negative
Actual Positive (75)           71 (TP)                4 (FN)
Actual Negative (7)             0 (FP)                7 (TN)
```

### 2.2 Core Detection Metrics

| Metric | Corpus A ($N=89$) | Corpus B (Live Physical Telemetry, $N=82$) |
|---|---|---|
| **True Positives (TP)** | 56 | 71 |
| **True Negatives (TN)** | 33 | 7 (verified blocked non-acquisition attempts) |
| **False Positives (FP)** | 0 | 0 (0 synthetic sessions fabricated) |
| **False Negatives (FN)** | 0 | 4 (C9 rapid cycling deduplication) |
| **Accuracy** | **100.0%** (95% CI: $[95.87\%, 100.0\%]$) | **95.12%** (95% CI: $[88.08\%, 98.14\%]$) |
| **Precision** | **100.0%** (0 false detections) | **100.0%** (0 false alarms) |
| **Recall / Sensitivity** | **100.0%** (95% CI: $[93.56\%, 100.0\%]$) | **94.67%** (95% CI: $[86.90\%, 98.02\%]$) |
| **Specificity** | **100.0%** (95% CI: $[89.57\%, 100.0\%]$) | **100.0%** (95% CI: $[64.57\%, 100.0\%]$) |
| **$F_1$-Score** | **1.0000** | **0.9726** |

---

## 3. Classification Performance (Task 3 Three-Tier Policy Resolution)

### 3.1 Two-Tier Cascade Performance

#### Accumulated 89 Cohort ($N=89$)
- **Hybrid True Accuracy**: **91.01%** (81 / 89 sessions) — 95% Wilson CI: $[83.33\%, 95.39\%]$
- **Macro-$F_1$ Score**: **0.9048**
- **Tier-1 Rule Coverage**: **61.80%** (55 sessions resolved deterministically)
- **Tier-2 ML Coverage**: **38.20%** (34 sessions resolved via Decision Tree)
- **Unknown Resolution Rate**: **100.0%** (0 sessions remained unclassified)

```
                 Accumulated 89 Confusion Matrix
                      Pred_AMBIGUOUS    Pred_CONTROLS    Pred_LEGITIMATE
True_AMBIGUOUS              26               7                  1
True_CONTROLS                0              22                  0
True_LEGITIMATE              0               0                 33
```

- **AMBIGUOUS** ($N=34$): Precision = **1.0000**, Recall = **0.7647**, $F_1$ = **0.8667**
- **CONTROLS** ($N=22$): Precision = **0.7586**, Recall = **1.0000**, $F_1$ = **0.8627**
- **LEGITIMATE** ($N=33$): Precision = **0.9706**, Recall = **1.0000**, $F_1$ = **0.9851**

#### Targeted 41 Cohort ($N=41$)
- **Hybrid True Accuracy**: **92.68%** (38 / 41 sessions) — 95% Wilson CI: $[80.60\%, 97.52\%]$
- **Macro-$F_1$ Score**: **0.9220**

```
                 Targeted 41 Confusion Matrix
                      Pred_AMBIGUOUS    Pred_CONTROLS    Pred_LEGITIMATE
True_AMBIGUOUS              13               3                  0
True_CONTROLS                0              10                  0
True_LEGITIMATE              0               0                 15
```

---

## 4. Empirical System Robustness & Latency (Live Testing)

### 4.1 Attribution & State Robustness
- **Permission and State Suite (P1–P10)**: **100.0% attribution accuracy** (8/8 sessions).
- **Adversarial Investigation Suite (T1–T5, A1–A6)**: **100.0% attribution accuracy** (4/4 sessions).
- **Ambiguous Context Suite (C1–C10)**: **62.5% attribution accuracy** (5/8 attributable sessions).
- **Anti-Self-Attribution Rate**: strictly **0.0%** (0 self-attributions across all live sessions).
- **State Contamination Rate**: strictly **0.0%** (zero state residue across permission toggles, denials, or process restarts).
- **Duplicate Event Rate**: strictly **0.0%**.

### 4.2 Latency Percentiles
- **Minimum**: **0.00 ms** (synchronous callback dispatch)
- **Maximum**: **10.00 ms**
- **Mean ($\mu$)**: **4.58 ms**
- **Median ($P_{50}$)**: **4.00 ms**
- **Standard Deviation ($\sigma$)**: **3.15 ms**
- **90th Percentile ($P_{90}$)**: **8.70 ms**
- **95th Percentile ($P_{95}$)**: **9.00 ms**
- **99th Percentile ($P_{99}$)**: **9.77 ms**

---

## 5. Machine-Readable Deliverables Directory

All authoritative metrics are preserved in machine-readable format under:
[`data/derived/phase6/`](file:///home/sanjay/Projects/CameraGuard/data/derived/phase6)

- `phase6_evaluation_manifest.json`
- `phase6_ground_truth_validation.json`
- `phase6_detection_metrics.json`
- `phase6_detection_confusion_matrix.csv`
- `phase6_scenario_wise_metrics.json`
- `phase6_scenario_metrics.csv`
- `phase6_classification_metrics.json`
- `phase6_classification_confusion_matrices.csv`
- `phase6_attribution_metrics.json`
- `phase6_contextual_inference_metrics.json`
- `phase6_latency_metrics.json`
- `phase6_error_analysis.json`
- `phase6_subgroup_robustness_metrics.json`
- `phase6_resource_overhead_metrics.json`
- `phase6_privacy_security_review.json`
- `phase6_statistical_summary.json`
