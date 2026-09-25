# CameraGuard Phase 4.1: Dataset & Feature Specification

**Document Version:** 1.0.0  
**Project:** CameraGuard — Autonomous Android Privacy & Camera Access Detection Engine  
**Module Context:** `:app` (Production Monitor) & `:camera-test-harness` (Controlled Telemetry)  
**Baseline Git Tag:** `v1.0.0` (Commit `7987c02`)  
**Current Checkpoint:** `0e581f2` (Phase 3.5.1 Completed Baseline)  
**Phase Objective:** Formal Technical Specification for Session Aggregation, Feature Engineering, Ground-Truth Separation, and ML Evaluation Strategy.

---

## 1. Executive Summary & Research Foundation

CameraGuard monitors Android camera hardware availability transitions (`CameraManager.AvailabilityCallback`) and correlates external system signals to assess whether camera activations are consistent with legitimate user intent.

### The Fundamental Classification Challenge
A naive classification policy based on binary application visibility:
$$\text{Decision} = \begin{cases} \text{SAFE}, & \text{if foreground} \\ \text{UNEXPECTED}, & \text{if background} \end{cases}$$
is demonstrably flawed in modern mobile operating systems. Legitimate real-world applications—including video conferencing (Google Meet, Zoom), navigation with camera assistance, and augmented reality background tracking—routinely maintain active camera streaming while the user multitasks, receives incoming notifications, or switches to another application. Conversely, sophisticated background attacks or misbehaving applications may briefly flash an activity or abuse background services to acquire camera hardware.

Therefore, CameraGuard's intelligence must be grounded in **multi-signal contextual reasoning**, evaluating:
1. Application identity & verified permissions,
2. User interaction recency & UI lifecycle state at initiation,
3. Continuity of camera sessions across visibility changes,
4. System-level display, lock, and power interactivity states,
5. Foreground service declarations and execution constraints.

Phase 4.1 establishes the mathematical, architectural, and data engineering foundation to convert raw, event-level telemetry into structured machine learning feature vectors without circular reasoning or target leakage.

---

## 2. Dataset Architecture: 3-Level Data Hierarchy

To bridge the gap between low-level asynchronous Android callbacks and supervised machine learning, the dataset pipeline is partitioned into three hierarchical tiers:

```
+-------------------------------------------------------------------------+
| Level A: Raw Event Telemetry Stream                                      |
| (Individual CSV rows recorded upon discrete OS/App/UI callbacks)        |
+-------------------------------------------------------------------------+
                                    │
                                    ▼ [Session Aggregation & Windowing]
+-------------------------------------------------------------------------+
| Level B: Session / Experiment Aggregate Dataset                          |
| (One unified record per logical camera experiment/access session)       |
+-------------------------------------------------------------------------+
                                    │
                                    ▼ [Feature Engineering & Sandbox Mapping]
+-------------------------------------------------------------------------+
| Level C: ML Feature & Vector Dataset                                     |
| (Normalized, leak-free numerical/categorical vectors ready for training)|
+-------------------------------------------------------------------------+
```

### Level A: Raw Event Telemetry (Existing Stream)
* **Granularity:** One record per observed callback or state mutation.
* **Schema (20 Columns):**
  `sample_id, timestamp, scenario_id, ground_truth_context, user_action, camera_event, camera_id, package_name, camera_permission, activity_state, app_visibility, foreground_service_active, foreground_service_type, screen_state, session_state, session_duration_ms, recent_user_interaction, lifecycle_event, camera_availability, notes`
* **Role:** Preserves complete chronological fidelity and auditability of the underlying system lifecycle.

### Level B: Experiment / Session Dataset (Logical Aggregates)
* **Granularity:** One record per logical experiment session (`sample_id` / `session_id`).
* **Boundary:** Initiated at session creation (`startNewSession` / initial intent) and finalized at physical release (`CAMERA_CLOSED` / `stopSession`).
* **Role:** Resolves intra-session temporal progressions (e.g., transition from foreground capture to background continuation) into a structured unit.

### Level C: ML Feature Dataset (Inference Vectors)
* **Granularity:** One fixed-length vector per decision evaluation point.
* **Separation:** Explicitly isolates features available at **camera activation time** from retrospective summary features.
* **Role:** Input to machine learning classifiers, free from label leakage and unsupported cross-sandbox primitives.

---

## 3. Telemetry Signal Analysis: Current State & Limitations

An inspection of the Phase 3.5.1 Test Harness and Phase 2 Production `:app` reveals a critical distinction between **internal ground-truth instrumentation** and **externally observable production signals**:

| Telemetry Signal | Test Harness Reliability | Production `:app` Observable? | Production Observation Mechanism | Reliability / Caveats |
| :--- | :--- | :--- | :--- | :--- |
| **`camera_event`** (`CAMERA_OPENED`, `CAMERA_CLOSED`) | 100% (Direct Camera2 callbacks) | **Yes** (Indirect via AvailabilityCallback) | `CameraManager.AvailabilityCallback` | **Highly Reliable**: Hardware-level notification of availability. |
| **`screen_state`** (`ON_UNLOCKED`, `OFF`, `LOCKED`) | Intermittent (Defaults to `UNKNOWN` unless queried) | **Yes** (Direct OS API) | `PowerManager.isInteractive`, `KeyguardManager.isKeyguardLocked` | **100% Reliable**: Real-time OS query with zero latency. |
| **`user_action`** (`USER_PRESSED_START`, `USER_PRESSED_STOP`) | 100% (Instrumented UI clicks) | **NO** (Strictly Sandbox Blocked) | *None* (OS prevents monitoring touch events in external apps) | **Research Only**: Production cannot detect touch inside 3rd-party apps. |
| **`package_name`** (`inferredPackageName`) | 100% (Self-identity) | **Yes** (Heuristic Correlation) | `UsageStatsManager.queryEvents` lookback window (30s) | **Conditionally Reliable**: Requires `PACKAGE_USAGE_STATS` user grant; latency up to several seconds. |
| **`camera_permission`** (`GRANTED`, `DENIED`) | 100% (Direct ContextCompat query) | **Yes** (Cross-App PackageManager query) | `PackageManager.checkPermission(CAMERA, pkg)` | **Highly Reliable** once package name is resolved. |
| **`foreground_service_active`** | 100% (Internal Service tracker) | **NO** (Restricted on Android 14+) | `ActivityManager.getRunningAppProcesses` (deprecated/isolated) | **Research Only**: Public Android SDK forbids querying external service status without Accessibility/Root. |
| **`app_visibility`** (`FOREGROUND`, `BACKGROUND`) | 100% (Activity lifecycle hooks) | **Partial / Heuristic** | `UsageEvents.Event.ACTIVITY_RESUMED` vs `PAUSED`/`STOPPED` | **Lagged**: UsageStats events can lag by 500ms–2000ms. |
| **`session_duration_ms`** | 100% (Monotonic clock delta) | **Yes** (Monitored internally) | Timer between `CAMERA_BECAME_UNAVAILABLE` and `CAMERA_BECAME_AVAILABLE` | **100% Reliable** for retrospective analysis; zero at onset. |

---

## 4. Ground-Truth Definition & Elimination of Circularity

### The Non-Circularity Principle
A critical error in security ML is circular labeling: defining the ground-truth label using the same heuristics or rules that the ML model is trained on (e.g., labeling an event "unexpected" because the screen was off, and then asking the model to learn that screen-off implies unexpected).

In CameraGuard Phase 4, **Ground Truth is established exclusively by the controlled physical test protocol**:

| Ground Truth Label | Formal Protocol Definition | Physical Demonstration Condition |
| :--- | :--- | :--- |
| **`USER_INITIATED_FOREGROUND`** | User explicitly interacted with a foreground UI immediately preceding camera opening, and maintained app visibility. | Test Harness or Legitimate Camera App actively open and focused on display. |
| **`USER_INITIATED_BACKGROUND_CONTINUATION`** | Camera was acquired while foreground UI was active with user intent, and subsequent background transition occurred while streaming continued via foreground service. | User starts camera in Test Harness / Video Call app, then navigates Home or switches apps. |
| **`NO_CAMERA_ACTIVITY`** | Application is active or backgrounded holding permissions, but deliberately omits hardware acquisition. | App running without calling `openCamera()`. |
| **`PERMISSION_DENIED`** | Application attempts to evaluate camera flow while camera permission is revoked or missing; physical camera hardware is never touched. | Android permission dialog denied or revoked in Settings. |
| **`AMBIGUOUS_CONTEXT`** | Telemetry does not contain sufficient empirical evidence to establish user intent (e.g. observation-only edge states, missing UsageStats, unresolvable package owner). | Observation recorded without active user initiation. |
| **`POTENTIALLY_UNEXPECTED`** *(Phase 4.2+ Target)* | Camera hardware transitions to unavailable without prior user interaction, outside legitimate foreground context, or under conflicting security state. | Automated background service trigger without UI interaction (requires controlled synthetic/malicious test harness). |

> **Crucial Rule:** We do **not** manufacture or fake an `UNEXPECTED` label in Phase 4.1. Unsupervised outlier detection or controlled unauthorized simulators in Phase 4.2+ will provide empirical samples for unexpected access.

---

## 5. Candidate Target Formulations

We evaluate three potential machine learning target structures:

### Formulation 1: Binary Classification (Operational Security Triage)
* **Target Classes:** `LEGITIMATE` ($Y=0$) vs. `POTENTIALLY_UNEXPECTED` ($Y=1$)
* **Pros:** Directly aligns with end-user alert decision (alert user vs. remain silent). Simple decision boundary.
* **Cons:** Conflates subtle sub-categories (e.g., background continuation vs. standard foreground use), making debugging and explainability difficult.

### Formulation 2: Multi-Class Context Classification (5-Class Detailed Intent)
* **Target Classes:**
  1. `USER_INITIATED_FOREGROUND`
  2. `USER_INITIATED_BACKGROUND_CONTINUATION`
  3. `NO_CAMERA_ACTIVITY`
  4. `PERMISSION_DENIED`
  5. `AMBIGUOUS_CONTEXT`
* **Pros:** Models distinct operational states; enables granular explanations in CameraGuard History UI.
* **Cons:** Greater risk of class imbalance; requires more training data to achieve high precision across all classes.

### Formulation 3: Hierarchical Multi-Stage Architecture (Recommended)
* **Stage 1 (Deterministic Invariant Filter):** Hard rules check for hardware state, permission grants, and baseline suppression.
* **Stage 2 (Binary Legitimate vs. Anomaly Triage):** Rapid ML classifier evaluates whether correlated signals match known legitimate patterns.
* **Stage 3 (Contextual Sub-classifier / Explainer):** If legitimate, classifies whether it is active foreground or background continuation for transparency.

---

## 6. Comprehensive Feature Specification Table

The following table specifies all proposed Level C features. Features are strictly divided into **Research-Only** (harness internal) and **Production-Available** (observable by external CameraGuard monitor).

### Feature Inventory

| ID | Feature Name | Data Type | Primary Source | Production Available? | Temporal Window | Leakage Risk | Description & Value Domain | Missing Representation |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **F01** | `screen_state_code` | Categorical | `PowerManager` / `Keyguard` | **YES** | Pre-Activation | Low | Screen interactivity state at trigger: `[ON_UNLOCKED, ON_LOCKED, OFF, UNKNOWN]` | String `"UNKNOWN"` |
| **F02** | `is_screen_interactive` | Boolean | `PowerManager.isInteractive` | **YES** | Pre-Activation | Low | `true` if display is powered on; `false` otherwise. | `UNKNOWN` (tri-state) |
| **F03** | `is_device_locked` | Boolean | `KeyguardManager.isKeyguardLocked` | **YES** | Pre-Activation | Low | `true` if device keyguard is locked; `false` otherwise. | `UNKNOWN` (tri-state) |
| **F04** | `has_camera_permission` | Boolean | `PackageManager.checkPermission` | **YES** | Pre-Activation | Low | Whether candidate package holds `android.permission.CAMERA`. | `UNKNOWN` |
| **F05** | `is_known_camera_app` | Boolean | `ContextualInferenceEngine` heuristic | **YES** | Pre-Activation | Medium | Matches standard system camera package names or manifest intent. | `false` |
| **F06** | `package_inference_confidence` | Ordinal | `ContextualInferenceEngine` | **YES** | Early-Session | Medium | Confidence of owner package identification: `[NONE=0, LOW=1, MEDIUM=2, HIGH=3]` | `NONE` (0) |
| **F07** | `inference_method_code` | Categorical | `InferenceMethod` | **YES** | Early-Session | Medium | Method: `[USAGE_STATS_RESUMED, CAMERA_APP_HEURISTIC, PERMISSION_CHECK, SESSION_OWNER, NONE]` | `"NONE"` |
| **F08** | `delta_resumed_to_trigger_ms` | Numerical (ms) | `UsageStatsManager` | **YES** | Pre-Activation | Low | Milliseconds elapsed between candidate `ACTIVITY_RESUMED` and camera unavailable event. $\ge 0$. | `-1.0` (indicates missing) |
| **F09** | `recent_activity_count_30s` | Numerical (count) | `UsageStatsManager` query | **YES** | Pre-Activation | Low | Number of distinct app foreground switches observed within lookback window. | `0` |
| **F10** | `camera_hardware_id` | Categorical | `AvailabilityCallback` | **YES** | Activation Point | Low | Physical sensor ID: `["0" (back), "1" (front), "external", "unknown"]` | `"UNKNOWN"` |
| **F11** | `is_back_camera` | Boolean | Camera characteristics | **YES** | Activation Point | Low | `true` if facing back; `false` if front/other. | `UNKNOWN` |
| **F12** | `session_duration_ms` | Numerical (ms) | Hardware monitor timer | **YES** | Retrospective | **CRITICAL** | Total duration camera remained unavailable. $\ge 0$. Available ONLY post-closure. | `0.0` at trigger |
| **F13** | `explicit_user_start_action` | Boolean | `user_action == USER_PRESSED_START` | **NO (Research)** | Pre-Activation | **HIGH** | Explicit tap on camera start UI in test harness. | `UNKNOWN` |
| **F14** | `explicit_user_stop_action` | Boolean | `user_action == USER_PRESSED_STOP` | **NO (Research)** | Retrospective | **CRITICAL** | Explicit user tap to release camera. | `UNKNOWN` |
| **F15** | `internal_harness_state` | Categorical | `HarnessState` | **NO (Research)** | Continuous | **HIGH** | Internal harness state machine: `[IDLE, RUNNING, APP_BACKGROUND, ERROR]` | `"UNKNOWN"` |
| **F16** | `harness_fg_service_active` | Boolean | Service binder state | **NO (Research)** | Continuous | Medium | Verified camera foreground service actively bound. | `UNKNOWN` |
| **F17** | `time_to_session_close_ms` | Numerical (ms) | Harness telemetry | **NO (Research)** | Retrospective | **CRITICAL** | Duration from start until physical camera close. | `-1.0` |
| **F18** | `app_visibility_at_trigger` | Categorical | Activity lifecycle | **NO (Research)** | Activation Point | Medium | Exact internal visibility: `[FOREGROUND, BACKGROUND, UNKNOWN]` | `"UNKNOWN"` |
| **F19** | `backgrounded_during_session`| Boolean | Lifecycle events | **NO (Research)** | Retrospective | Medium | `true` if `ON_PAUSE`/`ON_STOP` occurred while camera remained active. | `false` |
| **F20** | `returned_to_foreground` | Boolean | Lifecycle events | **NO (Research)** | Retrospective | Low | `true` if app resumed while camera stream was ongoing. | `false` |

---

## 7. Temporal Windows & Leakage Prevention

### Preventing Temporal & Label Leakage
To ensure realistic real-time inference, machine learning features must strictly respect causality:

```
─────────────────────────────────────────────────────────────────────────────► Time
  Pre-Activation Window       Trigger Point ($t_0$)      Active Streaming        Post-Closure
  ($t < t_0$)                 (Camera Unavailable)       ($t_0 < t < t_{close}$)  ($t \ge t_{close}$)
─────────────────────────────────────────────────────────────────────────────►
  Observable:                 Observable:                Observable:              Observable:
  - Screen state              - Hardware Camera ID       - Real-time duration     - Total duration
  - UsageStats resume history - Package inference delta  - Ongoing availability   - User stop tap
  - Permission grant status   - Screen interactive state - Notification state     - Closure events
  - Pre-activation user taps  - Trigger timestamp        - Interruption events    - Exit code / error
```

### Strict Feature Partitioning Rules
1. **Real-Time Detection Model (Operational Mode):**  
   May **ONLY** use features from Pre-Activation and Trigger Point ($t \le t_0 + \epsilon$, where $\epsilon \le 500\text{ ms}$).  
   *Forbidden in Real-Time:* `session_duration_ms` (total), `explicit_user_stop_action`, `CAMERA_CLOSED`.
2. **Retrospective Audit Model (Forensic Mode):**  
   May utilize full-session metrics to categorize completed interactions for privacy logging and behavioral auditing.
3. **Target Leakage Prohibition:**  
   `scenario_id` (e.g. `"NORMAL_FOREGROUND_CAMERA"`) and `ground_truth_context` must **NEVER** be input features. They are strictly target labels.

---

## 8. Missing-Value & Uncertainty Strategy

In mobile systems, telemetry is inherently imperfect due to OEM aggressive power management, permission restrictions, and sandbox isolation.

### Representation Rules
* **No Premature Binarization:** Never map `UNKNOWN` to `false` or `null` to `0`. A missing permission check is distinct from a verified denied permission.
* **Tri-State Categorical Encoding:** Boolean signals subject to observation gaps (`is_device_locked`, `has_camera_permission`, `is_screen_interactive`) are modeled as three-state categorical values:
  $$\text{Domain} = \{\text{TRUE}, \text{FALSE}, \text{UNKNOWN}\}$$
* **Missing Value Indicators:** Numerical latency features (such as `delta_resumed_to_trigger_ms`) use explicit sentinel missing values (`-1.0`) accompanied by a binary indicator feature:
  $$\text{has\_usage\_stats\_correlation} = \begin{cases} 1, & \text{if }\Delta t \ge 0 \\ 0, & \text{if missing/unavailable} \end{cases}$$

---

## 9. Evaluation Methodology: Candidate Algorithms & Benchmarking

Phase 4.1 does **not** assume Random Forest or SVM is the final model. An evidence-based comparative evaluation protocol is established across diverse model families:

### Candidate Model Families

| Model Family | Representative Algorithm | Primary Strengths | Mobile Deployment Considerations |
| :--- | :--- | :--- | :--- |
| **Linear / Margin** | Regularized Logistic Regression, Linear SVM | Ultra-fast inference (< 1ms), tiny memory footprint (< 10KB), highly interpretable weights. | Trivial to implement in pure Kotlin with zero external libraries. |
| **Tree Ensembles** | Random Forest, Gradient Boosted Trees (LightGBM/XGBoost) | Non-linear feature interactions, handles mixed categorical/numerical data without scaling, robust to outliers. | Transpiled directly to if-else rule trees in Kotlin or exported to lightweight TFLite model (< 100KB). |
| **Instance-Based** | $k$-Nearest Neighbors ($k$-NN) | Excellent for anomaly detection and finding nearest baseline session. | High memory footprint (must store training samples); unsuitable for large-scale mobile execution. |
| **Probabilistic** | Calibrated Gaussian / Categorical Naive Bayes | Provides genuine probability calibration; handles missing features naturally. | Low CPU overhead, highly transparent. |
| **Kernel Methods** | RBF Kernel SVM | Powerful non-linear separation for complex boundaries. | High inference cost proportional to number of support vectors; tricky on constrained Android runtime. |

### Evaluation Criteria Matrix (10 Pillars)
Every candidate algorithm will be scored across 10 empirical dimensions:
1. **Balanced F1-Score & ROC-AUC** across legitimate vs unexpected contexts,
2. **False Positive Rate (FPR)** (Target: $< 0.1\%$ to eliminate user alert fatigue),
3. **False Negative Rate (FNR)** (Target: $0.0\%$ on stealth/unauthorized background activations),
4. **Generalization across unseen session IDs**,
5. **Robustness to missing UsageStats telemetry**,
6. **Inference latency** on low-end Android hardware (Target: $< 25\text{ ms}$),
7. **RAM memory allocation during inference** (Target: $< 2\text{ MB}$),
8. **Binary model artifact size** (Target: $< 500\text{ KB}$),
9. **Explainability of decision** (ability to output human-readable justification),
10. **Android deployment feasibility** (pure Kotlin vs. TFLite C++ runtime).

---

## 10. Data Splitting & Cross-Validation Strategy

### The Session-Grouping Imperative
Randomly shuffling raw telemetry rows into training and testing sets causes catastrophic data leakage because adjacent rows belong to the same camera session and share identical session identifiers and context.

### Recommended Evaluation Protocol
1. **Grouped Splitting by Session:**  
   Splits are performed strictly at the **`sample_id` / `session_id` level**. No session may have rows in both training and test partitions.
2. **Stratified Group $K$-Fold Cross-Validation:**  
   Given the current dataset size (preliminary controlled experiments), standard fixed 80/10/10 splitting risks extreme sample variance. The system will employ:
   $$\text{StratifiedGroupKFold}(k=5)$$
   grouping by `sample_id` and stratifying across `scenario_id`.
3. **Out-of-Device Generalization Test:**  
   The final hold-out test set will evaluate sessions recorded on a completely distinct physical Android device and OEM build to measure cross-OEM generalization.

---

## 11. Comparison Against Phase 2 Baseline Engine

CameraGuard Phase 2 established a deterministic, transparent rule-based detection engine (`CameraRuleEvaluator.kt`):
* **Rule 1:** Screen OFF $\rightarrow$ `UNEXPECTED`
* **Rule 2:** Device Locked $\rightarrow$ `UNEXPECTED`
* **Rule 3:** Missing Camera Permission $\rightarrow$ `UNEXPECTED`
* **Rule 4:** Screen ON + Permission Verified + High/Med Correlation $\rightarrow$ `EXPECTED`
* **Rule 5:** Inconclusive telemetry $\rightarrow$ `UNKNOWN`

### Comparative Benchmarking Protocol
Every candidate ML model will run in parallel against the Phase 2 baseline:

$$\text{Improvement Delta} = \text{Metric}_{\text{ML}} - \text{Metric}_{\text{Phase 2 Baseline}}$$

The ML system is justified **if and only if**:
1. It resolves legitimate background continuations (which Phase 2 flags as `UNKNOWN` or `UNEXPECTED`) into verified `EXPECTED` with high recall without alert fatigue.
2. It reduces the rate of `UNKNOWN` classifications without increasing the False Positive Rate.
3. It respects baseline safety checks while evaluating holistic context rather than treating isolated signals as definitive proof of malice.

---

## 12. Architectural Synthesis: The Contextual Hybrid Engine

Rather than replacing deterministic security guarantees with an unexplainable black box, the system adopts a principled **Contextual Decision Pipeline**:

```
                         [ Camera Activation Detected ]
                                       │
                                       ▼
                   [ Telemetry & Hard-Constraint Validation ]
                   (Validates sensor ID, permission grant,
                    screen interactivity, and observation validity)
                                       │
                                       ▼
                     [ Context & Intent Assessment ]
                     (Lookback window: UsageStats resumption,
                      timing delta, active foreground service)
                                       │
                                       ▼
                     [ ML / Hybrid Classifier ]
                     (Empirically calibrated model evaluates
                      intent consistency across multi-signal features)
                                       │
                                       ▼
                 ┌───────────────────────────────────────────┐
                 │ Tri-Partite Output Space                  │
                 │ 1. LEGITIMATE                             │
                 │ 2. UNKNOWN / INSUFFICIENT_EVIDENCE        │
                 │ 3. POTENTIALLY_UNEXPECTED                 │
                 └─────────────────────┬─────────────────────┘
                                       │
                                       ▼
                                [ Alert Policy ]
                       (Silent log / History record /
                        User alert for high-confidence unexpected)
```

### Critical Ground-Truth Principles
1. **Permission Denied is NOT Unauthorized Access:** When permission is denied, camera acquisition is withheld; this is not evidence of unauthorized access, but of a blocked/withheld state.
2. **Screen-Off is NOT Dispositive Proof of Malice:** A screen-off transition can occur due to pocket sensors, power button taps, or display timeouts during legitimate calls. It is an environmental feature, not an automatic proof of attack.
3. **Absence of User Action != Malicious Attack:** When an automated background trigger is evaluated, the lack of an explicit user click records `explicit_user_start_action = FALSE` (or `NO_EXPLICIT_USER_ACTION`). It must **not** be fabricated as "malicious" or "hacked", which represents unsupported attacker intent.
4. **Empirical Model Selection:** No algorithm (including Random Forest or SVM) is predetermined as the winner. Selection must be based on rigorous metrics.
5. **No Label or Temporal Leakage:** Real-time decisions at activation point $t_0$ must never use future events (e.g. final session duration or user stop taps).
6. **Auditable Telemetry:** UNKNOWN / UNVERIFIED telemetry states must remain explicitly distinguishable from FALSE / DENIED states.

---

## 13. Data Collection Gaps & Concrete Next Steps (Phase 4.2 Roadmap)

The Phase 3.5.1 Test Harness validated single-app telemetry with high integrity. However, before training supervised production models, the following empirical gaps must be resolved:

### Identified Data Gaps
1. **Single-Application Bias:** Current dataset contains only `org.cameratestharness`. Telemetry from mainstream production apps (WhatsApp, Google Meet, Instagram, Zoom) is missing.
2. **Absence of Negative (Unauthorized) Samples:** While legitimate and edge scenarios are collected, an empirical collection of actual unauthorized/unprompted background acquisitions (simulated in a controlled, non-malicious sandbox) is required.
3. **Cross-OEM UsageStats Variability:** Samsung OneUI, Google Pixel, and Xiaomi MIUI report `UsageEvents` with varying timing granularity and battery-saver throttling.

### Recommended Phase 4.2 Data Collection Experiments
* **Experiment Series D1 (Third-Party Baseline):** Record external CameraGuard telemetry while running real-world camera sessions with top-5 video-conferencing and camera applications.
* **Experiment Series D2 (Controlled Unexpected Simulator):** Implement a dedicated test routine in `CameraTestHarness` that acquires camera strictly upon a background alarm or delayed timer without user presence, establishing verified positive unexpected samples.
* **Experiment Series D3 (Multitasking & PIP Variations):** Collect sessions where camera is used in Picture-in-Picture (PIP) mode and split-screen mode to train multi-window boundaries.
