# CameraGuard — Phase 7.13: Final Figures & Tables Package

This document compiles the authoritative, presentation- and paper-ready figures, diagrams, and tables generated from the CameraGuard research evaluation.

---

## 1. System Architecture Diagram

```mermaid
flowchart TD
    subgraph SENSORS ["Hardware & System Context"]
        CAM["Camera Sensor (HAL / cameraserver)"]
        SCREEN["Display Interactivity State (BroadcastReceiver)"]
        USAGE["Recent App Resumed Events (UsageStatsManager)"]
    end

    subgraph MONITORING ["CameraGuard Monitoring Core"]
        TRACKER["CameraAvailabilityTracker\n(State-Based Deduplication)"]
        ENGINE["ContextualInferenceEngine\n(Transition Lookback & Anti-Self Filter)"]
    end

    subgraph ENGINE_TIERS ["Two-Tier Hybrid Decision Engine"]
        TIER1["Tier 1: Deterministic Security Rules\n(Frozen Phase 2 Baseline)"]
        TIER2["Tier 2: Production Decision Tree\n(Native Zero-Dependency Depth <= 5)"]
    end

    subgraph OUTPUTS ["Auditing & Alerting"]
        NOTIF["NotificationManager\n(Heads-Up Alert on UNEXPECTED)"]
        ROOM["Room SQLite Database\n(cameraguard.db)"]
        COMPOSE["Jetpack Compose UI\n(Dashboard & Filtered Event History)"]
    end

    CAM -->|Availability Callback| TRACKER
    SCREEN -->|SCREEN_ON / OFF / LOCKED| TRACKER
    USAGE -->|30s Query Window| ENGINE

    TRACKER -->|Sensor Transition Tuple| TIER1
    ENGINE -->|Inferred Package & Confidence| TIER1

    TIER1 -->|Definitive Result (61.8%)| ROOM
    TIER1 -->|UNKNOWN Context (38.2%)| TIER2

    TIER2 -->|Policy Resolution (100% Resolved)| ROOM
    TIER2 -.->|If UNEXPECTED| NOTIF
    ROOM -->|Reactive Flow| COMPOSE
```

---

## 2. Decision Tree Structure ($depth \le 5$)

```
F06_package_confidence <= 1.50 (LOW or NONE)
├── F07_inference_method <= 1.00 (USAGE_STATS or NONE)
│   ├── F09_recent_activity_count <= 1.00 (Idle Screen)
│   │   └── CONTROLS (Final: EXPECTED)
│   └── F09_recent_activity_count > 1.00 (Active App Switching)
│       └── AMBIGUOUS (Final: UNEXPECTED)
└── F07_inference_method > 1.00 (ACCESSIBILITY or SPECIAL)
    ├── F09_recent_activity_count <= 1.50
    │   ├── F08_delta_resumed_ms <= 59.50 ms
    │   │   └── AMBIGUOUS (Final: UNEXPECTED)
    │   └── F08_delta_resumed_ms > 59.50 ms
    │       └── LEGITIMATE (Final: EXPECTED)
    └── F09_recent_activity_count > 1.50
        └── AMBIGUOUS (Final: UNEXPECTED)
F06_package_confidence > 1.50 (MEDIUM or HIGH)
└── LEGITIMATE (Final: EXPECTED)
```

---

## 3. Confusion Matrices

### Table 1: Binary Hardware Acquisition Detection (Task 2)

| Corpus | Sample Size ($N$) | True Positives (TP) | True Negatives (TN) | False Positives (FP) | False Negatives (FN) | Accuracy | $F_1$-Score |
|---|---|---|---|---|---|---|---|
| **Corpus A** (Static Dataset) | 89 | 56 | 33 | 0 | 0 | **100.0%** | **1.0000** |
| **Corpus B** (Live Empirical) | 82 | 71 | 7 | 0 | 4 | **95.12%** | **0.9726** |

### Table 2: 3-Tier Policy Resolution Confusion Matrix ($N=89$)

| True Class \ Predicted Class | Predicted AMBIGUOUS | Predicted CONTROLS | Predicted LEGITIMATE | Total True | Class Recall | Class Precision | Class $F_1$ |
|---|---|---|---|---|---|---|---|
| **True AMBIGUOUS** | **26** | 7 | 1 | 34 | 76.47% | **100.0%** | 0.8667 |
| **True CONTROLS** | 0 | **22** | 0 | 22 | **100.0%** | 75.86% | 0.8627 |
| **True LEGITIMATE** | 0 | 0 | **33** | 33 | **100.0%** | 97.06% | **0.9851** |
| **Total Predicted** | 26 | 29 | 34 | **89** | — | — | **Macro: 0.9048** |

---

## 4. Latency Distribution Histogram Data

```
Latency Range (ms)     Event Frequency
0.00 – 2.00 ms         ████████ (8 events)
2.01 – 4.00 ms         ████████████ (12 events)
4.01 – 6.00 ms         ███ (3 events)
6.01 – 8.00 ms         █ (1 event)
8.01 – 10.00 ms        ██ (2 events)
> 10.00 ms             (0 events)
```

- **Minimum**: 0.00 ms
- **Median ($P_{50}$)**: 4.00 ms
- **Mean**: 4.58 ms
- **95th Percentile ($P_{95}$)**: 9.00 ms
- **Maximum**: 10.00 ms
