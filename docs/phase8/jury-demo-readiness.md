# CameraGuard — Phase 8.15 Jury Demonstration Readiness Review

**Document ID:** `CG-DOC-P8-016-JURY-READINESS`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** READY FOR DEMONSTRATION  

---

## 1. Demonstration Architecture & Setup

The demonstration operates using two completely separate Android packages on the physical test device (vivo V2202, Android 15):
1. **CameraGuard (`org.cameraguard`):** The software-only privacy monitor running as an active foreground service.
2. **Adversarial Camera Test (`org.cameraguard.adversarytest`):** A completely independent test application simulating user-initiated and adversarial camera acquisitions.

---

## 2. Four-Part Demonstration Script

### Part 1: Reliability & Zero False Alarms (Group A)
- **Action:** Demonstrator opens the stock device Camera app cold from the home screen.
- **Narrative:** "In Phase 7, cold launches on Android 15 caused a false alarm due to a 484 ms HAL-to-framework race condition. Phase 8 implements an adaptive transition corroboration layer. Watch how CameraGuard corroborates the launch and classifies it as `EXPECTED` without firing any disruptive alarms."
- **Visible Outcome:** CameraGuard UI logs `EXPECTED` transition; zero notification alerts appear.

### Part 2: Independent Adversarial Camera Detection (Group B)
- **Action:** Demonstrator opens the independent Adversarial Camera Test app, selects a 15-second duration, and taps **START CAMERA TEST**.
- **Narrative:** "A completely independent application acquires Camera ID 0. Notice that CameraGuard immediately intercepts the hardware unavailability callback, stores the session owner as `org.cameraguard.adversarytest`, and tracks the active session. When the 15-second timer elapses, CameraGuard detects hardware release and attributes closure to the session owner."
- **Visible Outcome:** Camera session tracked in real-time; green badge rendered; lifecycle closure cleanly audited.

### Part 3: Blocked Background Camera Access (A2)
- **Action:** Demonstrator taps **A2 — BLOCKED BACKGROUND ACCESS** in the adversary app.
- **Narrative:** "This demonstrates the Android 15 platform boundary. When an app attempts camera acquisition from the background without a Camera Foreground Service, the OS denies access (`ERROR_CAMERA_DISABLED`), while CameraGuard remains fully stable."
- **Visible Outcome:** Adversary app displays `ERROR_CAMERA_DISABLED (error code 2)`; CameraGuard maintains continuous monitoring.

### Part 4: High-Priority Red Alert on Security Violations
- **Action:** Demonstrate that unprivileged background access or screen-off acquisitions immediately produce the high-priority red heads-up notification with sound and vibration.
- **Visible Outcome:** Red alarm notification fires with full rationale and attribution.

---

## 3. UI & Audit Log Review

- **Main Dashboard:** Clean metrics cards (Total Events, Unexpected, Expected).
- **History Audit Log:** Full auditability with granular filters (`All`, `Unexpected`, `Expected`, `Unknown`, `Ambiguous`).
- **Copy to Clipboard:** One-tap export of formatted event evidence for forensic analysis.
