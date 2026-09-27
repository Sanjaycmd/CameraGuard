# CameraGuard — Phase 7.14: Live Demonstration Guide

This guide provides an end-to-end, step-by-step procedure to reliably demonstrate the CameraGuard application to evaluators, research juries, or peer reviewers.

---

## 1. Demonstration Setup & Preconditions

### Hardware Requirements
- Android smartphone running Android 8.0 to Android 15 (Tested baseline: `vivo V2202`, API 35).
- USB cable for ADB installation or standalone APK sideloading.

### APK Installation
```bash
cd /home/sanjay/Projects/CameraGuard
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 2. Step-by-Step Live Demonstration Protocol

### Step 1: Initial Launch & Permissions Setup (1 Minute)
1. Open **CameraGuard** from the device launcher.
2. The **MainScreen** displays:
   - Status: `Status: STOPPED` (Red)
   - Real-time counters: `Total Events: 0`, `Unexpected: 0`, `Expected: 0`.
3. In the **"Permissions & Privacy Context"** card:
   - Tap **"Grant Notification Permission"** $\to$ select **Allow** (Required on Android 13+).
   - Tap **"Enable Usage Access in Settings"** $\to$ select **CameraGuard** $\to$ toggle **Permit usage access** $\to$ return to CameraGuard.
4. Verify that the permission card displays `GRANTED` for both items.

### Step 2: Start Background Monitoring Service (30 Seconds)
1. Tap the green **"Start Service"** button.
2. **Observed Behavior**:
   - The status badge transitions to `Status: ACTIVE (Foreground Service)` (Green).
   - A persistent status bar notification appears: *"CameraGuard Active — Monitoring camera availability and privacy context"*.
   - CameraGuard is now actively listening for hardware sensor availability callbacks.

### Step 3: Demonstrate Legitimate Foreground Camera Access (1 Minute)
1. Press the device **Home** button or open the stock **Camera** application (or WhatsApp Camera).
2. Take a photo or preview for 3–5 seconds, then close the Camera app.
3. Return to **CameraGuard**:
   - **Total Events** counter increments by **2** (1 for `CAMERA_BECAME_UNAVAILABLE`, 1 for `CAMERA_BECAME_AVAILABLE`).
   - **Expected** counter increments by **2**.
   - **Unexpected** counter remains **0** (Zero false alarms).
   - **Latest Observed Transition** displays:
     - Event: `CAMERA_BECAME_AVAILABLE`
     - Inferred App: e.g. `com.google.android.GoogleCamera` (or OEM camera package)
     - Classification: `EXPECTED` (Green badge).

### Step 4: Demonstrate Suspicious / Covert Camera Detection (1 Minute)
To demonstrate detection of unexpected camera access, choose either Method A (On-Device Synthetic Pipeline) or Method B (Physical Adversary App):

#### Method A (Integrated On-Device Pipeline — No ADB required):
1. Scroll down to the **"Research & Test Pipeline"** card on `MainScreen`.
2. Tap **"Simulate: Screen-Off Camera Access (Unexpected)"**.
3. **Observed Behavior**:
   - An immediate high-priority heads-up notification with vibration pops up:
     **"Suspicious Camera Activity"** — *"Unexpected camera access detected while screen was off."*
   - **Unexpected** counter increments by **1** (Red).
   - **Latest Observed Transition** badge displays `UNEXPECTED` (Red).

#### Method B (Physical Background Camera Capture via ADB):
```bash
# Launch background camera service from adversary test app
adb shell am start-foreground-service -n org.cameraguard.adversarytest/.AdversaryCameraService
```
CameraGuard detects the background capture within 4 ms, routes to `UNEXPECTED`, and fires a heads-up alert.

### Step 5: Audit Event History & Telemetry Breakdown (1 Minute)
1. Tap **"View Detailed Event History →"**.
2. The **HistoryScreen** displays a timeline of logged events:
   - Tap **UNEXPECTED** filter chip to filter exclusively suspicious entries.
   - Expand an event card to review:
     - Exact timestamp
     - Camera ID (`Camera 0` Back / `Camera 1` Front)
     - Screen Interactivity State (`SCREEN_OFF` / `SCREEN_ON_UNLOCKED`)
     - Inferred Caller & Confidence (`HIGH`, `MEDIUM`, `LOW`, `NONE`)
     - Classification Justification
     - Detection Latency (`< 10 ms`)
3. Tap the **Copy** icon on an event card:
   - A Toast appears: *"Copied event summary to clipboard"*.
   - Paste into any notes app or terminal to show formatted JSON telemetry.

---

## 3. Recovery & Cleanup
1. On `HistoryScreen`, tap **"Purge Test Events"** to delete synthetic test items while preserving genuine hardware records.
2. Return to `MainScreen` and tap **"Stop Service"** when demonstration is complete.
