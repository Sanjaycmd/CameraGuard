# CameraGuard — Phase 8.10 Physical Validation Report

**Document ID:** `CG-DOC-P8-011-PHYSICAL-VALIDATION`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Device:** vivo V2202 (Android 15 / API 35)  
**Dataset Artifact:** `data/derived/phase8/validation/phase8_physical_validation.json`  
**Status:** VALIDATED ON PHYSICAL HARDWARE  

---

## 1. Test Environment & Configuration

- **Target Device:** vivo V2202
- **OS Version:** Android 15 (VanillaIceCream) / API Level 35
- **Build ID:** V2202_WW
- **Camera Sensors:** Rear (ID: 0), Front (ID: 1)
- **Monitoring Service:** `CameraMonitoringService` (Active Foreground Service)
- **Permissions Verified:** `PACKAGE_USAGE_STATS` (granted), `POST_NOTIFICATIONS` (granted), `CAMERA` (granted)

---

## 2. Live Physical Test Scenarios & Results

### Group A: Normal User Interaction (False Positive Elimination)

#### Scenario 1: Cold Launch of Stock Camera
- **Method:** `com.android.camera` was force-stopped via ADB; device returned to Launcher3 (`com.android.launcher3`). User/ADB invoked `android.media.action.STILL_IMAGE_CAMERA`.
- **Pre-Phase 8 Behavior:** Emitted `UNEXPECTED` false alarm due to 484 ms attribution delay attributing initial event to Launcher3.
- **Phase 8 Behavior:**
  - `UsageStats` fallback / transition lookahead engaged.
  - Attribution successfully resolved to `com.android.camera` with verified camera permission.
  - Classification: **`EXPECTED`** (`TIER_1_RULE`).
  - Notification Status: **Silent (0 alerts fired)**.

#### Scenario 2: Warm Launch of Stock Camera
- **Method:** With `com.android.camera` cached in RAM, user switched to Home and immediately reopened the Camera.
- **Phase 8 Behavior:**
  - Candidate resolved immediately to `com.android.camera`.
  - Classification: **`EXPECTED`** (`TIER_1_RULE`).
  - Latency: 467 ms.
  - Notification Status: **Silent (0 alerts fired)**.

#### Scenario 3: Accidental Google Assistant / Search to Camera Transition
- **Method:** User invoked Google Assistant (`android.intent.action.ASSIST`), bringing `com.google.android.googlequicksearchbox` to front, then immediately launched Camera.
- **Phase 8 Behavior:**
  - Transition corroboration successfully resolved final acquisition to `com.android.camera`.
  - Classification: **`EXPECTED`** (`TIER_1_RULE`).
  - Notification Status: **Silent (0 alerts fired)**.

---

### Group B: Adversarial Hardware Access & Closure Attribution

#### Scenario 4: Intentional Foreground Adversarial Camera Acquisition
- **Method:** User launched the independent Adversarial Camera Test application (`org.cameraguard.adversarytest`) and started a controlled 15-second camera capture session.
- **Physical Telemetry:**
  - Timestamp: `1790520299163` (`20:14:59`)
  - Camera ID: `0`
  - Inferred Package: `org.cameraguard.adversarytest`
  - Classification: **`EXPECTED`** (`TIER_2_ML`, `CONTROLS`)
  - Notification Status: **Silent**.

#### Scenario 5: Full Lifecycle Closure Attribution
- **Method:** Adversarial camera session completed after 15 seconds, releasing Camera ID 0.
- **Physical Telemetry:**
  - Event: `CAMERA_BECAME_AVAILABLE`
  - Closure Attribution: Active session owner `org.cameraguard.adversarytest`.
  - Classification: **`EXPECTED`** (`TIER_1_RULE`, Rule 5 match).
  - Latency: 2 ms.
  - Notification Status: **Silent**.

#### Scenario 6: Blocked Background Camera Access Attempt (A2)
- **Method:** Adversarial test app moved to background and executed A2 test scenario without a Camera Foreground Service.
- **Physical Result:**
  - Android 15 platform security policy strictly blocked camera session: `ERROR_CAMERA_DISABLED (error code 2)`.
  - Hardware camera was protected by OS sandbox.

---

### Group C: Notification Policy & System Integrity Verification

- **Alert Notification Audit (`dumpsys notification --noredact`):**
  - Notification ID `1001` (Service Ongoing Notification): **Active**.
  - Notification ID `1002` (High-Priority Red Alert): **Not Fired (0 instances)** across all normal stock camera operations and benign controls.
- **Database Persistence (`cameraguard.db`):**
  - All 6 physical events successfully committed to Room SQLite.
  - Zero database schema migrations required.

---

## 3. Summary of Physical Validation Verdict

| Physical Scenario | Pre-Phase 8 Outcome | Phase 8 Verified Outcome | Verdict |
| :--- | :--- | :--- | :--- |
| Stock Camera Cold Launch | `UNEXPECTED` (False Alarm) | `EXPECTED` (Silent) | **RESOLVED** |
| Stock Camera Warm Launch | `EXPECTED` | `EXPECTED` (Silent) | **VERIFIED** |
| Google Search $\to$ Camera | `UNEXPECTED` (False Alarm) | `EXPECTED` (Silent) | **RESOLVED** |
| Adversary Foreground Test | `EXPECTED` (Controls) | `EXPECTED` (Controls) | **VERIFIED** |
| Adversary Session Closure | `EXPECTED` (Rule 5) | `EXPECTED` (Rule 5) | **VERIFIED** |
| A2 Blocked Background Attempt | Blocked by OS (Error 2) | Blocked by OS (Error 2) | **VERIFIED** |
| Notification Gating | Red alert on ambiguity | Red alert strictly on `UNEXPECTED` | **RESOLVED** |
