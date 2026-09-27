# CameraGuard — Phase 8 Selected Architecture Specification

**Document ID:** `CG-DOC-P8-007-SELECTED-ARCH`  
**Date:** September 27, 2026  
**Investigator:** Lead Systems & Research Engineer  

---

## 1. Selected Architecture: Candidate E (Adaptive Corroboration + Severity Decoupling)

Phase 8 selects **Candidate E**, addressing both independently identified failure mechanisms without compromising detection recall or whitelisting package names.

```mermaid
flowchart TD
    HAL["Camera Hardware State (onCameraUnavailable)"] --> Track["CameraAvailabilityTracker"]
    
    subgraph Attribution_Layer["Attribution Layer (Transition-Aware)"]
        Track --> ScreenCheck{"Screen State?"}
        ScreenCheck -->|OFF or LOCKED| ImmediateAttr["Immediate Attribution (0ms delay)"]
        ScreenCheck -->|ON UNLOCKED| QuickQuery["Immediate UsageStats Query"]
        QuickQuery --> ConfCheck{"High Conf & Holds CAMERA Perm?"}
        ConfCheck -->|YES (Warm Camera)| FastPath["Fast-Path Attribution (0ms delay)"]
        ConfCheck -->|NO (Candidate unprivileged or LOW)| Corroborate["Adaptive Corroboration Window (450ms)"]
        Corroborate --> Recheck{"Camera-Capable App Resumed?"}
        Recheck -->|YES| PromotedAttr["Attribution updated to Resumed Camera App"]
        Recheck -->|NO| ConfirmedAttr["Attribution confirmed to Unprivileged Caller"]
    end
    
    subgraph Decision_Layer["Decision & Classification Layer"]
        ImmediateAttr --> Tier1["Tier 1 Deterministic Rules"]
        FastPath --> Tier1
        PromotedAttr --> Tier1
        ConfirmedAttr --> Tier1
        Tier1 -->|UNKNOWN| Tier2["Tier 2 Decision Tree (T0 ML)"]
    end
    
    subgraph Policy_Layer["Notification & Persistence Policy"]
        Tier1 -->|UNEXPECTED| SecurityAlert["HIGH-PRIORITY SECURITY ALERT (camera_alerts)"]
        Tier1 -->|EXPECTED| Silent["Persist in Room SQLite (Silent)"]
        Tier2 -->|LEGITIMATE / CONTROLS| Silent
        Tier2 -->|AMBIGUOUS| AuditLog["Persist in Room SQLite (Audit Trail, No Heads-Up Alarm)"]
    end
```

---

## 2. Technical Implementation Specifications

### 2.1 Attribution Enhancement in `ContextualInferenceEngine.kt`
* **Adaptive Corroboration Method:**
  Add `corroborateForegroundPackage(eventTimestamp: Long, initialContext: InferredPackageContext): InferredPackageContext`
  - When `initialContext` on active display has `hasCameraPermission == false` or `confidence == LOW`, wait for an asynchronous coroutine delay window of **450 ms** (covering the 484 ms cold launch delta).
  - Re-query `UsageStatsManager` for events in `[eventTimestamp, eventTimestamp + 450ms]`.
  - If a package holding `CAMERA` permission or a known camera application resumes during this window, return the updated context with `HIGH` confidence.
  - If no camera application resumes, return the initial context, maintaining genuine detection of background probes.

### 2.2 Severity Decoupling in `HybridCameraEvaluator.kt` and `AccessClassification.kt`
* **Direct Escalation Elimination:**
  Modify `HybridCameraEvaluator.kt` to map `TreeClassification.AMBIGUOUS` to `AccessClassification.UNKNOWN` or `AccessClassification.AMBIGUOUS` (with an explicit `isSecurityAlert` predicate).
* **Notification Gating in `CameraMonitoringService.kt`:**
  - `showUnexpectedActivityAlert()` is triggered **only** when `classification == AccessClassification.UNEXPECTED`.
  - Inconclusive `AMBIGUOUS` events remain fully persisted in Room SQLite with `mlResult = AMBIGUOUS`, preserving scientific transparency while eliminating spurious heads-up alerts during user multitasking.

---

## 3. Strict Guarantees

1. **Screen-Off Camera Access:** Zero delay. Immediate `UNEXPECTED` classification and high-priority alarm.
2. **Device Locked Access:** Zero delay. Immediate `UNEXPECTED` classification and alarm.
3. **Warm Camera Usage:** Zero delay (9–15 ms latency).
4. **Adversary Test App:** Continued 100% detection and classification.
5. **No Whitelisting:** Zero hardcoded package string matching.
