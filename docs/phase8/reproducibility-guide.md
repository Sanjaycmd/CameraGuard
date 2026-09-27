# CameraGuard — Phase 8.14 Reproducibility Guide

**Document ID:** `CG-DOC-P8-015-REPRODUCIBILITY`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** VERIFIED & REPRODUCIBLE  

---

## 1. Overview

This document specifies the exact instructions required to reproduce all Phase 8 build, test, and physical validation artifacts from source.

---

## 2. Prerequisites

- **Development Environment:** Linux (x86_64) / Kali / Ubuntu
- **Java Runtime:** JDK 17 or JDK 21
- **Android SDK:** Platform 35 (Android 15), Build-tools 35.0.0
- **Physical Test Device (Optional for live telemetry):** Android 15 device (e.g. vivo V2202) connected via ADB with USB debugging enabled.

---

## 3. Automated Build & Test Reproduction

### 3.1 Clean Full Compilation
```bash
./gradlew clean assembleDebug
```
Expected output: `BUILD SUCCESSFUL` across all 3 modules (`:app`, `:adversary-test-app`, `:camera-test-harness`).

### 3.2 Regression Test Suite Execution
```bash
./gradlew test --rerun-tasks
```
Expected output:
- Total unit tests executed: 290
- Total failures: 0
- Total skipped: 0
- Pass rate: 100.0%

---

## 4. Physical Device Reproduction Protocol

### 4.1 Installation & Permissions
```bash
./gradlew :app:installDebug
adb shell pm grant org.cameraguard android.permission.POST_NOTIFICATIONS
adb shell pm grant org.cameraguard android.permission.CAMERA
adb shell appops set org.cameraguard GET_USAGE_STATS allow
```

### 4.2 Start Monitoring Service
```bash
adb shell am start -n org.cameraguard/.MainActivity
# Tap "Start Service" or start via UI
```

### 4.3 Cold Launch Verification
```bash
adb shell am force-stop com.android.camera
adb shell input keyevent KEYCODE_HOME
adb logcat -c
adb shell am start -a android.media.action.STILL_IMAGE_CAMERA
adb logcat -d -s CameraGuard:D
```
Expected log output:
`UsageStats fallback created unified CameraAccessEvent: package=com.android.camera, class=EXPECTED`
Zero `UNEXPECTED` alerts.
