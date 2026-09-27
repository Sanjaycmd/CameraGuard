# CameraGuard

**Context-Aware Software-Only Camera Privacy Monitoring Framework for Android**

[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/Platform-Android%2015%20%7C%20API%2035-blue.svg)]()
[![Tests](https://img.shields.io/badge/Tests-260%2F260%20Passing-brightgreen.svg)]()
[![License](https://img.shields.io/badge/License-Academic%20Research-lightgrey.svg)]()

CameraGuard is an unprivileged, software-only Android privacy monitoring framework that detects hardware camera access, correlates multi-source device context, attributes active sessions to foreground or background applications, and evaluates access legitimacy using a two-tier hybrid classifier (deterministic security rules + native decision tree).

Unlike standard Android privacy indicators (which merely signal when a camera device is open without distinguishing authorized user action from unauthorized background capture), CameraGuard actively monitors hardware transitions, correlates screen interactivity and recent application history, enforces strict anti-self-attribution invariants, and alerts the user with sub-10ms latency.

---

## 1. Key Features

- **Software-Only & Unprivileged**: Operates entirely within the standard Android application sandbox without requiring root privileges (`su`), Xposed frameworks, custom ROMs, or private API access.
- **Zero Camera Frame Capture**: Never requests or inspects camera preview frames, images, or video buffers. Monitors availability status callbacks exclusively.
- **Two-Tier Hybrid Classification Engine**:
  - **Tier 1 (Deterministic Rules)**: Resolves 61.8% of access events with zero ambiguity.
  - **Tier 2 (Machine Learning Decision Tree)**: Resolves 100% of inconclusive telemetry with 91.01% true accuracy and **0.0% false ambiguous alarms**.
- **Real-Time Detection Latency**: Mean detection and dispatch latency of **4.58 ms** ($P_{95} = 9.00\text{ ms}$).
- **Resilient Attribution & Anti-Self-Attribution**: Resolves background applications that captured camera immediately upon leaving foreground (5000 ms transition lookback window), with **0.0% self-attribution to CameraGuard**.
- **Network Isolated**: Zero network permissions declared in `AndroidManifest.xml`. Telemetry never leaves the device.

---

## 2. System Architecture

```
                    CameraGuard Architecture Overview
                                   |
    +------------------------------+------------------------------+
    |                                                             |
1. Platform Monitoring                               2. Contextual Telemetry
- CameraAvailabilityTracker                          - ScreenStateTracker
- Push callbacks via CameraManager                   - UsageStatsManager (30s window)
- State-based deduplication                          - 5000ms transition lookback
- Active session owner caching                       - Anti-self-attribution filter
    |                                                             |
    +------------------------------+------------------------------+
                                   |
                   3. Two-Tier Classification Engine
                   - Tier 1: Deterministic Rules (EXPECTED/UNEXPECTED)
                   - Tier 2: Native Decision Tree (depth <= 5)
                   - Production T0 feature vector (F04 = -1.0 clamped)
                                   |
                   4. Persistence & Presentation
                   - SQLite Room Database (cameraguard.db)
                   - Persistent service notification (SpecialUse FGS)
                   - High-priority alert on UNEXPECTED camera access
                   - Jetpack Compose Dashboard & History Screen
```

---

## 3. Verified Performance Metrics

Authoritative metrics established during the Phase 6 research evaluation:

| Metric | Corpus A (Static Dataset, $N=89$) | Corpus B (Live Physical Telemetry, $N=82$) |
|---|---|---|
| **Binary Hardware Detection Accuracy** | **100.0%** (56/56 TP, 33/33 TN) | **95.12%** (71/75 TP, 7/7 TN) |
| **Detection Recall / Sensitivity** | **100.0%** (95% CI: $[93.56\%, 100.0\%]$) | **94.67%** (95% CI: $[86.90\%, 98.02\%]$) |
| **Detection Specificity** | **100.0%** (95% CI: $[89.57\%, 100.0\%]$) | **100.0%** (95% CI: $[64.57\%, 100.0\%]$) |
| **Detection Precision** | **100.0%** (0 false alarms) | **100.0%** (0 false alarms) |
| **Detection $F_1$-Score** | **1.0000** | **0.9726** |
| **Hybrid Contextual Accuracy** | **91.01%** (Macro-$F_1$: 0.9048) | **80.0%** (interactive foreground) |
| **False Ambiguous Alarm Rate** | **0.0%** (0 innocent controls flagged) | **0.0%** |
| **Anti-Self-Attribution Rate** | — | strictly **0.0%** |
| **Mean Empirical Latency** | — | **4.58 ms** ($P_{95} = 9.00\text{ ms}$) |

---

## 4. Repository Structure

```
CameraGuard/
├── app/                              # Production Android Application (:app)
│   ├── src/main/java/org/cameraguard/
│   │   ├── monitoring/               # Service, Tracker, Evaluator, Notification
│   │   ├── data/                     # Room Entities, DAO, Repository
│   │   ├── ui/                       # Jetpack Compose UI (MainScreen, History)
│   │   └── theme/                    # Material 3 Styling
│   └── src/test/                     # 104 unit tests (:app)
├── camera-test-harness/              # Ground-Truth Research Module (:camera-test-harness)
│   ├── src/main/                     # Experimental scenarios & reconstruction
│   └── src/test/                     # 156 unit tests (:camera-test-harness)
├── adversary-test-app/               # Controlled Adversarial Testbed (:adversary-test-app)
│   └── src/main/                     # Exported test services for physical evaluations
├── data/
│   ├── raw/                          # 15 raw telemetry CSVs (1,193 records)
│   ├── derived/phase4/               # Curated ML dataset & decision tree models
│   ├── derived/phase5/               # Live empirical physical JSON results
│   └── derived/phase6/               # Authoritative Phase 6 evaluation metrics
├── scripts/
│   ├── research/                     # ADB runners for Phases 4.6–5.5
│   └── evaluation/                   # run_phase6_evaluation.py (reproducibility)
└── docs/                             # Comprehensive technical documentation
    ├── phase4/                       # Phase 4 dataset and ML reports
    ├── phase5/                       # Phase 5 empirical robustness reports
    ├── phase6/                       # Phase 6 evaluation report
    └── phase7/                       # Phase 7 final release documentation
```

---

## 5. Building & Testing

### Prerequisites
- Linux / macOS / Windows with OpenJDK 17 or 21
- Android SDK Platform API 35
- Python 3.10+ (for evaluation reproduction)

### Build the Production Application
```bash
./gradlew assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`

### Execute Complete Unit Test Suite (260 Tests)
```bash
./gradlew testDebugUnitTest --rerun-tasks
```
All 260 deterministic tests pass with 0 failures and 0 errors:
- 104 tests in `:app`
- 156 tests in `:camera-test-harness`

### Reproduce Research Evaluation Metrics
```bash
.venv/bin/python3 scripts/evaluation/run_phase6_evaluation.py
```
Regenerates all machine-readable metrics, confusion matrices, and latency tables in `data/derived/phase6/`.

---

## 6. Demonstration & Usage

1. **Install and Launch**: Install `app-debug.apk` on an Android device running Android 8.0 to Android 15.
2. **Grant Permissions**:
   - Tap **"Grant Notification Permission"** to allow high-priority alerts.
   - Tap **"Enable Usage Access in Settings"** to grant `PACKAGE_USAGE_STATS` for application attribution.
3. **Start Monitoring**: Tap **"Start Service"**. The background foreground service starts with persistent notification.
4. **Test Real Camera Usage**: Open the stock camera app or WhatsApp camera. CameraGuard records `EXPECTED` access with zero false alarms.
5. **Test Suspicious Background Access**: Trigger camera opening in a background service. CameraGuard detects acquisition in 4 ms, routes to `UNEXPECTED`, and alerts the user immediately.
6. **Review Event History**: Tap **"View Detailed Event History"** to inspect timestamps, inferred callers, confidence scores, and telemetry breakdowns.

---

## 7. Privacy & Security Invariants

- **Zero Frame Access**: No camera preview surfaces are allocated.
- **Data Minimization**: Stores only lightweight event metadata (~256 bytes per event).
- **Network Isolation**: No network permissions declared in `AndroidManifest.xml`.
- **Clamped Permissions**: Feature $F_{04}$ is strictly clamped to `UNVERIFIED` (-1.0) in production to prevent spoofing across the Android sandbox.

---

## 8. Research Documentation Links

- [Phase 7 Architecture Documentation](docs/phase7/architecture.md)
- [Phase 7 Algorithm & Decision Flow](docs/phase7/algorithm.md)
- [Phase 7 Research Results Package](docs/phase7/research-results.md)
- [Phase 7 Reproducibility Guide](docs/phase7/reproducibility.md)
- [Phase 7 Research Paper](docs/phase7/research-paper.md)
- [Phase 7 Jury Defense Guide](docs/phase7/jury-defense-guide.md)
- [Phase 6 Evaluation Report](docs/phase6/phase6-evaluation-report.md)
- [Phase 4 Research Completion](docs/phase4/PHASE4-COMPLETION.md)

---

## 9. Authors & Academic Integrity

CameraGuard was developed as a cybersecurity and privacy research framework. Evaluated on physical Android 15 hardware (`vivo V2202`, API 35). All reported experimental results are grounded in physical telemetry without synthetic fabrication.
