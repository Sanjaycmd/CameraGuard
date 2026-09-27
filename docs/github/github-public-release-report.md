# CameraGuard GitHub Public Release & Repository Organization Report

**Release Target**: `v1.1.0`  
**Date**: September 27, 2026  
**Repository**: `Sanjaycmd/CameraGuard`  
**Authoritative Baseline**: Commit `9b61149` (Tag `phase8-complete`)  
**Frozen Historical Reference**: Commit `33db3e8` (Tags `v1.0.0-final`, `phase7-complete`)

---

## 1. Executive Summary

This report documents the public-facing repository reorganization, release asset preparation, and documentation hardening for **CameraGuard v1.1.0**. CameraGuard is now structured as an accessible, reproducible final-year academic research repository and production-ready Android security application. 

A first-time visitor, jury reviewer, student, or developer can immediately understand the project, download the pre-compiled Android application without needing an IDE or build tools, verify cryptographic hashes, and inspect all underlying empirical research artifacts.

---

## 2. Repository Organization Changes

The repository structure has been organized according to strict academic and open-source standards:

```text
CameraGuard/
│
├── README.md                               # Complete, professional public entry point
├── LICENSE                                 # MIT Open-Source License
│
├── release/                                # Release asset distribution mirror
│   ├── CameraGuard-v1.1.0.apk              # Primary application APK
│   ├── CameraGuard-AdversaryTest-v1.1.0.apk# Independent adversarial testbed APK
│   ├── CameraGuard-TestHarness-v1.1.0.apk  # Ground-truth research harness APK
│   ├── SHA256SUMS.txt                      # Cryptographic checksums
│   └── README.md                           # Release asset documentation
│
├── app/                                    # Production Android Application (:app)
│   ├── src/main/                           # Production source code
│   └── src/test/                           # 117 automated unit tests
│
├── adversary-test-app/                     # Independent Adversarial Caller (:adversary-test-app)
│   ├── src/main/                           # Completely decoupled camera caller app
│   └── src/test/                           # 17 automated unit tests
│
├── camera-test-harness/                    # Research & Evaluation Module (:camera-test-harness)
│   ├── src/main/                           # Scenario generators & reconstruction engine
│   └── src/test/                           # 156 automated unit tests
│
├── docs/                                   # Authoritative Research Documentation
│   ├── phase4/                             # ML dataset, features, models (Phase 4)
│   ├── phase5/                             # Physical empirical robustness reports (Phase 5)
│   ├── phase6/                             # Evaluation & metrics benchmark report (Phase 6)
│   ├── phase7/                             # Final research paper, architecture, jury defense (Phase 7)
│   ├── phase8/                             # Reliability & transition-aware attribution (Phase 8)
│   └── github/                             # Release and repository distribution reports
│
├── data/                                   # Research Data (Bitwise Immutable)
│   ├── raw/                                # 15 raw telemetry CSVs (1,193 events)
│   ├── derived/phase4/                     # Curated dataset, decision trees, manifests
│   ├── derived/phase5/                     # Physical execution logs & JSON telemetry
│   └── derived/phase6/                     # Metric evaluation outputs & confusion matrices
│
├── scripts/                                # Evaluation & Reproducibility Tooling
│   ├── evaluation/run_phase6_evaluation.py # Reproduces all Phase 6 tables & metrics
│   └── research/                           # Physical test execution and ADB runners
│
├── gradle/                                 # Gradle wrapper configuration
├── build.gradle.kts                        # Root project build configuration
└── settings.gradle.kts                     # Multi-module project definition
```

### Specific Structural Enhancements:
1. **Root `README.md`**: Completely rewritten to serve as an intuitive public front page answering key questions, featuring prominent one-click APK download links, architecture diagrams, privacy guarantees, empirical benchmarks, and audience-specific navigation paths.
2. **`LICENSE`**: Added standard MIT License to establish open-source licensing.
3. **`release/` Directory**: Created dedicated release folder containing pre-compiled binaries for all three applications, cryptographic SHA-256 checksums, and verification documentation.
4. **Docs Verification**: Verified 100% of internal Markdown links in `README.md` against the existing documentation tree with zero broken references.

---

## 3. Release & Versioning Decision

### Historical Version Baseline:
* `v1.0.0` (commit `7987c02`): Phase 2 baseline.
* `v1.0.0-final` (commit `33db3e8`): Phase 7 research release.
* `phase8-complete` (commit `9b61149`): Phase 8 completion freeze.

### Release Version: `v1.1.0`
The new public release is designated **`v1.1.0`**.
* **SemVer Justification**: Phase 8 introduced significant architectural enhancements—adaptive transition corroboration, alert severity decoupling, and the independent adversarial testbed (`:adversary-test-app`)—which substantially improve real-world reliability without breaking backward compatibility or data structures.
* **Tag Preservation**: Historical tags (`v1.0.0`, `v1.0.0-final`, `phase4-complete`, `phase6-complete`, `phase7-complete`, `phase8-complete`) remain 100% immutable and untouched.

---

## 4. Release Binaries & Cryptographic Verification

All three application APKs were compiled cleanly from source using Gradle (`assembleDebug`) and verified cryptographically:

| Asset Filename | Source Module | Target Package | File Size | SHA-256 Checksum |
|---|---|---|---|---|
| **`CameraGuard-v1.1.0.apk`** | `:app` | `org.cameraguard` | 12,257,024 B (12.3 MB) | `e2a8d8f4aace3076cc5e3389eefa360ea984a53ee17e4224a2b2311fda9d5c51` |
| **`CameraGuard-AdversaryTest-v1.1.0.apk`** | `:adversary-test-app` | `org.cameraguard.adversary` | 2,551,207 B (2.6 MB) | `33b4af8c8106d63daf21a1310e309aa1b7a6152d13018203185bae8e939afa52` |
| **`CameraGuard-TestHarness-v1.1.0.apk`** | `:camera-test-harness` | `org.cameraguard.testharness` | 12,044,576 B (12.0 MB) | `91d79177fd98e6d25c958d8dbb08efd4f1af800bdb6fccd6829b788e15dd4a59` |

### Checksum Verification:
```bash
$ cd release && sha256sum -c SHA256SUMS.txt
CameraGuard-v1.1.0.apk: OK
CameraGuard-AdversaryTest-v1.1.0.apk: OK
CameraGuard-TestHarness-v1.1.0.apk: OK
```

---

## 5. Download Paths & User Access

Visitors to the repository can obtain CameraGuard through multiple convenient paths:

1. **GitHub Releases (Recommended)**:
   * URL: `https://github.com/Sanjaycmd/CameraGuard/releases/tag/v1.1.0`
   * Direct APK: `https://github.com/Sanjaycmd/CameraGuard/releases/download/v1.1.0/CameraGuard-v1.1.0.apk`
2. **Repository Mirror**:
   * Local folder: `release/CameraGuard-v1.1.0.apk`
   * Direct Web Raw URL: `https://github.com/Sanjaycmd/CameraGuard/raw/master/release/CameraGuard-v1.1.0.apk`

---

## 6. Build & Test Suite Verification

The complete project build and unit test suites were executed with all tasks re-run:

```bash
$ ./gradlew test --rerun-tasks
```

### Test Suite Execution Summary:
* **`:app`**: 117 tests executed, 0 failures, 0 errors, 100% passing.
* **`:adversary-test-app`**: 17 tests executed, 0 failures, 0 errors, 100% passing.
* **`:camera-test-harness`**: 156 tests executed, 0 failures, 0 errors, 100% passing.
* **Total**: **290 unit tests executed, 0 failures, 0 errors, 0 skipped**.

### Build Verification:
```bash
$ ./gradlew assembleDebug
BUILD SUCCESSFUL in 14s
```
All modules compile cleanly without warnings or errors.

---

## 7. Historical Integrity Verification

To guarantee that past scientific evidence remains uncorrupted, a strict bitwise diff was executed against the Phase 7 frozen baseline (`33db3e8`):

```bash
$ git diff 33db3e8 -- data/derived/phase4 data/derived/phase5 data/derived/phase6 docs/phase4 docs/phase5 docs/phase6
(Empty output — 0 lines changed, 0 bytes difference)
```

Historical datasets, machine-learning models, training manifests, and evaluation outputs remain strictly bitwise immutable.

---

## 8. Production Code Invariance Result

**Zero lines of production code, algorithms, or test logic were altered.**
* `git diff 9b61149 -- app/src/main adversary-test-app/src/main camera-test-harness/src/main` produces 0 diffs.
* CameraGuard's detection logic, classification models, and alert rules are bitwise identical to the frozen Phase 8 commit (`9b61149`).

---

## 9. GitHub Release Publishing Package

The following information is formatted for direct entry into the GitHub web interface when publishing the official release:

### Release Details
* **Tag version**: `v1.1.0`
* **Target branch**: `master`
* **Release title**: `CameraGuard v1.1.0 — Reliability & Transition-Aware Attribution Release`

### Release Notes (Markdown):

```markdown
# CameraGuard v1.1.0

CameraGuard is an unprivileged, software-only Android camera privacy monitor and research framework. It independently observes hardware-level camera activation via native callbacks, attributes access to active or transitioning applications, and classifies access legitimacy using a two-tier hybrid decision engine.

## 🌟 What's New in v1.1.0 (Phase 8 Reliability Release)
* **Transition-Aware Attribution**: Eliminates false alarms caused by application launch races (e.g., stock Camera app launch delays) via a 2-stage adaptive corroboration window.
* **Decoupled Alert Policy**: Decouples internal classification from notification dispatch, reserving high-priority alarms for verified threats while logging transient app switches cleanly.
* **Independent Adversarial Camera Test Application**: Fully decoupled demonstration app (`org.cameraguard.adversary`) that genuinely opens the physical camera via Camera2 HAL to prove detection without synthetic events.
* **Expanded Test Suite**: 290 automated unit tests (up from 260 in Phase 7), 100% passing.
* **Public Repository Organization**: Streamlined documentation, quick APK download paths, and verified SHA-256 checksums.

## 📱 Release Assets & Downloads

| File | Purpose | Size | SHA-256 Checksum |
|---|---|---|---|
| **CameraGuard-v1.1.0.apk** | **Primary Privacy Monitor** (For users & jury) | 12.3 MB | `e2a8d8f4aace3076cc5e3389eefa360ea984a53ee17e4224a2b2311fda9d5c51` |
| **CameraGuard-AdversaryTest-v1.1.0.apk** | **Independent Adversary App** (For live demo) | 2.6 MB | `33b4af8c8106d63daf21a1310e309aa1b7a6152d13018203185bae8e939afa52` |
| **CameraGuard-TestHarness-v1.1.0.apk** | **Research Harness** (For automated testing) | 12.0 MB | `91d79177fd98e6d25c958d8dbb08efd4f1af800bdb6fccd6829b788e15dd4a59` |
| **SHA256SUMS.txt** | Cryptographic Checksums | 280 B | — |

## 📲 Quick Installation
1. Download `CameraGuard-v1.1.0.apk` onto your Android device.
2. Tap to install (allow "Install unknown apps" if prompted).
3. Open CameraGuard and grant:
   - **Notification Permission** (`POST_NOTIFICATIONS`)
   - **Usage Access** (`PACKAGE_USAGE_STATS`) via *Settings > Special App Access*
4. Tap **"Start Service"** to begin continuous monitoring.

## 🔬 Research & Verification
* Evaluated on physical **vivo V2202 (Android 15, API 35)**.
* Compatible with Android 8.0+ (API 26–35).
* 100.0% binary hardware detection accuracy on Corpus A; 95.12% on live physical telemetry (Corpus B).
* Sub-10ms mean detection latency (4.58 ms empirical mean).
* 100% zero-frame access; no camera permission or network permission requested.
```

---

## 10. Remaining Manual GitHub Actions

Because GitHub API authentication requires user interaction outside the local sandbox, the user can publish the release via the GitHub Web UI by completing these simple steps:

1. **Push Branch and Tags to GitHub**:
   ```bash
   git push origin master
   git push origin v1.1.0
   ```
2. **Navigate to GitHub Releases**:
   * Open: `https://github.com/Sanjaycmd/CameraGuard/releases/new`
3. **Select Tag**:
   * Choose existing tag: `v1.1.0`
4. **Enter Title & Description**:
   * Copy the Title and Release Notes provided in Section 9 of this document.
5. **Attach Binaries**:
   * Drag and drop the three files from the `release/` directory:
     - `release/CameraGuard-v1.1.0.apk`
     - `release/CameraGuard-AdversaryTest-v1.1.0.apk`
     - `release/CameraGuard-TestHarness-v1.1.0.apk`
     - `release/SHA256SUMS.txt`
6. **Click "Publish release"**.
