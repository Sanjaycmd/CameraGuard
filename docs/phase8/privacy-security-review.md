# CameraGuard — Phase 8.13 Privacy & Security Review

**Document ID:** `CG-DOC-P8-014-PRIVACY-SECURITY`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  
**Status:** APPROVED  

---

## 1. Threat Modeling & Security Invariant Verification

Phase 8 introduces two architectural changes:
1. An asynchronous 500 ms transition corroboration window in `CameraAvailabilityTracker`.
2. Severity decoupling of `TreeClassification.AMBIGUOUS` to `AccessClassification.AMBIGUOUS`.

This review evaluates whether these changes could be abused by an attacker to evade detection.

---

## 2. Threat Vector Analysis

### Threat Vector 1: Background Camera Acquisition During Active Screen Usage
- **Attack Scenario:** A malicious background app acquires the camera while the user is browsing the launcher or an unrelated app, hoping the 500 ms corroboration window conceals the access.
- **Defense Mechanism:**
  - When the camera is acquired, `inferForegroundPackage` identifies the foreground app (or none).
  - If the foreground app lacks camera permissions (e.g. Launcher or Social Media without camera open), the tracker initiates the 500 ms corroboration window.
  - During the window, `corroborateTransition` queries for newly resumed camera-capable applications.
  - Since the malicious app operates in the background, no legitimate camera app resumes.
  - At the expiration of the window, attribution remains with the unprivileged/unverified caller.
  - Deterministic Rule 3 fires: `UNEXPECTED`.
  - High-priority security alert is dispatched immediately upon window expiration.
- **Verdict: IMMUNE.**

### Threat Vector 2: Screen-Off or Device-Locked Covert Capture
- **Attack Scenario:** Spyware attempts covert camera acquisition while the phone is in the user's pocket or charging on a table with the display turned off.
- **Defense Mechanism:**
  - `ScreenStateTracker` reports `SCREEN_OFF` or `SCREEN_ON_LOCKED`.
  - The condition `screenState == SCREEN_ON_UNLOCKED` evaluates to **FALSE**.
  - Corroboration delay is strictly **BYPASSED** (0 ms delay).
  - Deterministic Rule 2 (Screen Off) or Rule 4 (Locked) fires immediately (< 5 ms latency).
  - High-priority security alert is dispatched immediately with vibrating heads-up notification.
- **Verdict: IMMUNE.**

### Threat Vector 3: Evading Detection via Ambiguity Exploitation
- **Attack Scenario:** An attacker attempts to craft camera telemetry to intentionally force Tier 2 Decision Tree into the `AMBIGUOUS` state to avoid a heads-up alert.
- **Defense Mechanism:**
  - To reach Tier 2 ML, the attacker must first bypass Tier 1 Deterministic Rules.
  - The attacker cannot bypass Rule 2 (Screen Off), Rule 4 (Locked), or Rule 3 (No Permission).
  - Therefore, the attacker can only reach Tier 2 on an unlocked, active display where the attacker already holds confirmed camera permissions.
  - Even if classified as `AMBIGUOUS`, the event is fully persisted in Room SQLite DB and rendered in the UI Audit Log with distinct Amber styling.
- **Verdict: RISK ACCEPTABLE & MITIGATED.**

---

## 3. Privacy & Data Minimization Audit

1. **Zero Network Egress:** CameraGuard continues to request zero Internet permissions (`android.permission.INTERNET` is absent from AndroidManifest.xml).
2. **Local Storage Isolation:** All database files (`cameraguard.db`) reside strictly within the application's private sandbox (`/data/user/0/org.cameraguard/`).
3. **UsageStats Data Minimization:** `ContextualInferenceEngine` queries only narrow sliding time windows (500 ms to 30 s) for `ACTIVITY_RESUMED` events and does not log user browsing history, activity titles, or application content.
