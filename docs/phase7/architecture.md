# CameraGuard — Phase 7.6: Final System Architecture Documentation

## 1. High-Level Architecture Overview

CameraGuard is an unprivileged, software-only Android camera privacy framework that monitors camera hardware availability, correlates multi-source device context, attributes active sessions to foreground or background applications, and evaluates access legitimacy using a two-tier hybrid classifier.

```mermaid
flowchart TD
    subgraph PlatformLayer [Android Platform Layer]
        HAL[Camera HAL / cameraserver]
        USM[UsageStatsManager]
        BR[BroadcastReceivers (Screen Interactivity)]
    end

    subgraph MonitoringLayer [CameraGuard Monitoring Layer]
        CMS[CameraMonitoringService (Foreground Service)]
        CAT[CameraAvailabilityTracker]
        CIE[ContextualInferenceEngine]
        SST[ScreenStateTracker]
    end

    subgraph EvaluationLayer [Two-Tier Classification Engine]
        HCE[HybridCameraEvaluator]
        CRE[Tier 1: CameraRuleEvaluator (Deterministic Rules)]
        PDT[Tier 2: ProductionDecisionTree (Native ML Resolver)]
    end

    subgraph DataAndUILayer [Persistence & UI]
        NH[NotificationHelper]
        DB[(CameraGuard Room DB)]
        UI[Jetpack Compose Dashboard & History]
    end

    HAL -->|onCameraUnavailable / onCameraAvailable| CAT
    USM -->|queryEvents 30s window| CIE
    BR -->|SCREEN_ON / OFF / LOCKED| SST

    CMS --> CAT
    CAT -->|Raw transition| CIE
    CAT -->|State updates| SST
    CAT -->|Telemetry tuple| HCE

    HCE --> CRE
    CRE -->|Definitive EXPECTED / UNEXPECTED| HCE
    CRE -->|UNKNOWN| PDT
    PDT -->|LEGITIMATE / AMBIGUOUS / CONTROLS| HCE

    HCE -->|Persist CameraAccessEvent| DB
    HCE -->|If UNEXPECTED| NH
    DB -->|Flow reactive stream| UI
```

---

## 2. Core Architectural Subsystems

### 2.1 Platform Monitoring Subsystem
- **`CameraMonitoringService`**: The core execution host. Runs as a sticky Android Foreground Service (`foregroundServiceType="specialUse"`). Maintains a persistent ongoing notification to prevent OS process reaping under memory pressure.
- **`CameraAvailabilityTracker`**: Manages interaction with `android.hardware.camera2.CameraManager`. Registers `AvailabilityCallback` to receive push updates when any physical camera sensor becomes unavailable (opened by a process) or available (released).
  - *Deduplication*: Implements state-based filtering. The first callback establishes the sensor baseline. Subsequent callbacks with identical state are suppressed.
  - *Session Ownership*: Caches the active caller package on `CAMERA_BECAME_UNAVAILABLE` and automatically attributes the subsequent `CAMERA_BECAME_AVAILABLE` closure event to the same session owner.

### 2.2 Contextual Telemetry & Attribution Subsystem
- **`ScreenStateTracker`**: Dynamically tracks device display interactivity (`SCREEN_OFF`, `SCREEN_ON_UNLOCKED`, `SCREEN_ON_LOCKED`) via system broadcast intents.
- **`ContextualInferenceEngine`**: Resolves which package accessed the camera when a transition occurs.
  - Queries `UsageStatsManager` for `ACTIVITY_RESUMED` events in the preceding 30-second window.
  - Implements a 5000 ms transition lookback window to catch applications that launched background camera capture immediately upon leaving the foreground.
  - Computes `InferenceConfidence`: `HIGH` ($\le 500\text{ ms}$), `MEDIUM` ($\le 2000\text{ ms}$), `LOW` ($> 2000\text{ ms}$), `NONE` (no candidate).
  - **Anti-Self-Attribution Invariant**: Explicitly filters out `org.cameraguard` to prevent false positive alerts against itself.

### 2.3 Two-Tier Hybrid Classification Engine
- **`HybridCameraEvaluator`**: Coordinates decision flow between deterministic security rules and machine learning:
  1. Calls Tier 1 (`CameraRuleEvaluator`).
  2. If Tier 1 produces a definitive result (`EXPECTED` or `UNEXPECTED`), returns immediately. The ML model is bypassed.
  3. If Tier 1 produces `UNKNOWN`, constructs a production $T_0$ feature vector and invokes Tier 2 (`ProductionDecisionTree`).
- **`CameraRuleEvaluator` (Tier 1)**: Frozen Phase 2 deterministic baseline:
  - Screen Off + Camera Unavailable $\to$ `UNEXPECTED`
  - High confidence foreground camera app + Screen Unlocked $\to$ `EXPECTED`
  - Camera Available (session closure) $\to$ `EXPECTED`
  - Inconclusive context $\to$ `UNKNOWN`
- **`ProductionDecisionTree` (Tier 2)**: Native, zero-dependency Kotlin tree with $depth \le 5$, trained on the audited Phase 4 research dataset:
  - Resolves unknown context into `LEGITIMATE` ($\to$ `EXPECTED`), `AMBIGUOUS` ($\to$ `UNEXPECTED`), or `CONTROLS` ($\to$ `EXPECTED`).

### 2.4 Data Persistence & Presentation Subsystem
- **`CameraGuardDatabase` & `CameraEventDao`**: Thread-safe SQLite Room database storing full event telemetry (~256 bytes/row).
- **`NotificationHelper`**: Manages two notification channels:
  - `CameraGuard Monitoring Service` (`IMPORTANCE_LOW`): Persistent service notification.
  - `Suspicious Camera Alerts` (`IMPORTANCE_HIGH`): Heads-up alert with vibration triggered exclusively on `UNEXPECTED` access.
- **`MainScreen` & `HistoryScreen`**: Modern Jetpack Compose UI with reactive Kotlin `Flow` data streams, real-time counters, filtering chips, and clipboard export.
