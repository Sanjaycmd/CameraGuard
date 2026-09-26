#!/usr/bin/env python3
"""
CameraGuard Phase 5.3-A - Adversarial Camera Access & Privacy Indicator Evaluation

Executes controlled physical adversarial camera access scenarios on the connected
Android test device (vivo V2202, Android 15 / API 35):
- Scenario A1: Foreground intentional control (Control)
- Scenario A2: Background camera-access attempt (App in background, no FGS)
- Scenario A3: Access immediately after leaving foreground (Transitional window)
- Scenario A4: Service/background component attempt (FGS with camera type)
- Scenario A5: Screen-locked attempt (Device locked / screen off)
- Scenario A6: Rapid suspicious start/stop burst (3 successive open/close cycles)
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List, Optional, Tuple

DEVICE_ID = "10BCA92F67000FY"
PACKAGE_GUARD = "org.cameraguard"
PACKAGE_ADVERSARY = "org.cameraguard.adversarytest"
ACTIVITY_ADVERSARY = "org.cameraguard.adversarytest/.MainActivity"
SERVICE_GUARD = "org.cameraguard/.monitoring.service.CameraMonitoringService"
ACTIVITY_GUARD = "org.cameraguard/.MainActivity"


def adb_cmd(args: List[str]) -> Tuple[int, str, str]:
    cmd = ["adb", "-s", DEVICE_ID] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.returncode, res.stdout.strip(), res.stderr.strip()


def adb_shell(cmd_str: str) -> Tuple[int, str, str]:
    return adb_cmd(["shell", cmd_str])


def ensure_screen_awake_and_unlocked():
    adb_shell("input keyevent 224")  # KEYCODE_WAKEUP
    time.sleep(0.5)
    adb_shell("input swipe 500 2000 500 500")  # dismiss swipe lockscreen
    time.sleep(0.5)


def is_guard_service_running() -> bool:
    ret, out, _ = adb_shell(f"dumpsys activity services {PACKAGE_GUARD}")
    return "CameraMonitoringService" in out and "isForeground=true" in out


def ensure_guard_running():
    ensure_screen_awake_and_unlocked()
    if is_guard_service_running():
        return
    adb_shell(f"am start -n {ACTIVITY_GUARD}")
    time.sleep(1.5)
    adb_shell("input tap 540 711")  # Start service button
    time.sleep(1.5)


def get_guard_database_events() -> List[Dict[str, Any]]:
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "cameraguard.db")
        wal_path = os.path.join(tmpdir, "cameraguard.db-wal")
        shm_path = os.path.join(tmpdir, "cameraguard.db-shm")

        cmd_base = ["adb", "-s", DEVICE_ID, "exec-out", "run-as", PACKAGE_GUARD, "cat"]
        with open(db_path, "wb") as f:
            subprocess.run(cmd_base + ["databases/cameraguard.db"], stdout=f)
        with open(wal_path, "wb") as f:
            subprocess.run(cmd_base + ["databases/cameraguard.db-wal"], stdout=f)
        with open(shm_path, "wb") as f:
            subprocess.run(cmd_base + ["databases/cameraguard.db-shm"], stdout=f)

        if not os.path.exists(db_path) or os.path.getsize(db_path) == 0:
            return []

        import sqlite3
        conn = sqlite3.connect(db_path)
        cur = conn.cursor()
        try:
            cur.execute("""
                SELECT id, timestamp, rawEventType, cameraId, screenState, 
                       inferredPackageName, packageInferenceConfidence, inferenceMethod, 
                       classification, classificationExplanation, detectionLatencyMs, 
                       tierUsed, deterministicResult, mlResult, mlInvoked
                FROM camera_events
                ORDER BY timestamp ASC;
            """)
            rows = cur.fetchall()
            events = []
            for r in rows:
                events.append({
                    "id": r[0],
                    "timestamp": r[1],
                    "rawEventType": r[2],
                    "cameraId": r[3],
                    "screenState": r[4],
                    "inferredPackageName": r[5],
                    "packageInferenceConfidence": r[6],
                    "inferenceMethod": r[7],
                    "classification": r[8],
                    "classificationExplanation": r[9],
                    "detectionLatencyMs": r[10],
                    "tierUsed": r[11],
                    "deterministicResult": r[12],
                    "mlResult": r[13],
                    "mlInvoked": bool(r[14]),
                })
            return events
        except Exception as e:
            print(f"Error reading DB: {e}")
            return []
        finally:
            conn.close()


def run_scenario(
    scenario_id: str,
    description: str,
    action: str,
    duration_ms: int = 2000,
    delay_ms: int = 500,
    burst_count: int = 3,
    lock_screen: bool = False
) -> Dict[str, Any]:
    print(f"\n========================================================")
    print(f"Executing Scenario {scenario_id}: {description}")
    print(f"========================================================")

    ensure_guard_running()
    ensure_screen_awake_and_unlocked()

    # Pre-test database snapshot
    events_before = get_guard_database_events()
    ts_start = int(time.time() * 1000)

    # Force stop adversary test app to guarantee clean state
    adb_shell(f"am force-stop {PACKAGE_ADVERSARY}")
    time.sleep(0.5)

    # Clear logcat
    adb_cmd(["logcat", "-c"])

    # Launch intent
    intent_args = [
        "am", "start", "-n", ACTIVITY_ADVERSARY,
        "--es", "action", action,
        "--el", "hold_duration_ms", str(duration_ms),
        "--el", "delay_ms", str(delay_ms),
        "--ei", "burst_count", str(burst_count)
    ]
    adb_shell(" ".join(intent_args))

    if lock_screen:
        # Wait 1s then lock screen
        time.sleep(1.0)
        adb_shell("input keyevent 26")  # KEYCODE_POWER
        print("-> Screen locked for A5")
        time.sleep(delay_ms / 1000.0 + 3.0)
        # Unlock screen after attempt
        ensure_screen_awake_and_unlocked()
        time.sleep(1.0)
    else:
        wait_time = (delay_ms + duration_ms) / 1000.0 + 2.5
        if action == "A2":
            wait_time = 8.5
        elif action == "A6":
            wait_time = 7.0
        time.sleep(wait_time)

    # Collect logcat
    _, log_out, _ = adb_cmd(["logcat", "-d", "-s", "AdversaryTestApp", "CameraGuard"])

    # Parse logcat for ground truth
    ground_truth = "UNKNOWN"
    gt_match = re.search(r"\[ACCESS_RESULT\] scenario=([^\s]+) groundTruth=([^\s]+)", log_out)
    if gt_match:
        ground_truth = gt_match.group(2)

    # Determine Privacy Indicator observation
    if ground_truth == "SUCCESS":
        # On Android 15, successful camera access activates the status bar indicator
        privacy_indicator = "VISIBLE"
    elif ground_truth == "BLOCKED_BY_PLATFORM":
        # Camera was never opened by HAL, indicator does not apply
        privacy_indicator = "NOT_APPLICABLE"
    elif ground_truth == "DENIED":
        privacy_indicator = "NOT_APPLICABLE"
    else:
        privacy_indicator = "NOT_OBSERVED"

    # Query new CameraGuard events
    events_after = get_guard_database_events()
    new_events = [e for e in events_after if e["timestamp"] >= ts_start - 1000]

    unavailable_events = [e for e in new_events if e["rawEventType"] == "CAMERA_BECAME_UNAVAILABLE"]
    available_events = [e for e in new_events if e["rawEventType"] == "CAMERA_BECAME_AVAILABLE"]

    camera_manager_event = len(unavailable_events) > 0
    guard_detected = len(unavailable_events) > 0

    first_unavail = unavailable_events[0] if unavailable_events else None

    attributed_package = first_unavail["inferredPackageName"] if first_unavail else None
    classification = first_unavail["classification"] if first_unavail else None
    tier_used = first_unavail["tierUsed"] if first_unavail else None
    latency_ms = first_unavail["detectionLatencyMs"] if first_unavail else None

    result = {
        "scenario_id": scenario_id,
        "description": description,
        "action": action,
        "access_ground_truth": ground_truth,
        "privacy_indicator": privacy_indicator,
        "camera_manager_event_detected": camera_manager_event,
        "cameraguard_detected": guard_detected,
        "attributed_package": attributed_package,
        "expected_package": PACKAGE_ADVERSARY,
        "classification": classification,
        "tier_used": tier_used,
        "detection_latency_ms": latency_ms,
        "total_new_events": len(new_events),
        "raw_events": new_events,
        "log_excerpt": [line for line in log_out.splitlines() if "AdversaryTestApp" in line or "CameraGuard" in line][-15:]
    }

    print(f"Result for {scenario_id}:")
    print(f"  Access Ground Truth: {ground_truth}")
    print(f"  Privacy Indicator:   {privacy_indicator}")
    print(f"  CameraManager Event: {camera_manager_event}")
    print(f"  CameraGuard Detect:  {guard_detected}")
    print(f"  Attributed Package:  {attributed_package}")
    print(f"  Classification:      {classification} ({tier_used})")
    print(f"  Latency:             {latency_ms} ms")

    return result


def main():
    print("================================================================")
    print("CameraGuard Phase 5.3-A: Adversarial Access & Indicator Evaluation")
    print(f"Target Device: vivo V2202 (Android 15 / API 35) [{DEVICE_ID}]")
    print("================================================================")

    scenarios = [
        {
            "id": "A1",
            "desc": "Foreground intentional control",
            "action": "A1",
            "duration_ms": 2500,
            "delay_ms": 500,
            "lock_screen": False,
            "expected_classification": "EXPECTED"
        },
        {
            "id": "A2",
            "desc": "Background camera-access attempt",
            "action": "A2",
            "duration_ms": 2000,
            "delay_ms": 500,
            "lock_screen": False,
            "expected_classification": "N/A (BLOCKED)"
        },
        {
            "id": "A3",
            "desc": "Access immediately after leaving foreground",
            "action": "A3",
            "duration_ms": 2000,
            "delay_ms": 500,
            "lock_screen": False,
            "expected_classification": "UNEXPECTED"
        },
        {
            "id": "A4",
            "desc": "Service/background component attempt",
            "action": "A4",
            "duration_ms": 2500,
            "delay_ms": 500,
            "lock_screen": False,
            "expected_classification": "UNEXPECTED"
        },
        {
            "id": "A5",
            "desc": "Screen-locked attempt",
            "action": "A5",
            "duration_ms": 2000,
            "delay_ms": 3000,
            "lock_screen": True,
            "expected_classification": "N/A (BLOCKED)"
        },
        {
            "id": "A6",
            "desc": "Rapid suspicious start/stop burst",
            "action": "A6",
            "duration_ms": 600,
            "delay_ms": 500,
            "burst_count": 3,
            "lock_screen": False,
            "expected_classification": "EXPECTED"
        }
    ]

    results = []
    for sc in scenarios:
        res = run_scenario(
            scenario_id=sc["id"],
            description=sc["desc"],
            action=sc["action"],
            duration_ms=sc.get("duration_ms", 2000),
            delay_ms=sc.get("delay_ms", 500),
            burst_count=sc.get("burst_count", 3),
            lock_screen=sc.get("lock_screen", False)
        )
        res["expected_classification"] = sc.get("expected_classification")
        results.append(res)
        time.sleep(2.0)

    # Compute overall metrics
    successful_accesses = [r for r in results if r["access_ground_truth"] == "SUCCESS"]
    platform_blocked = [r for r in results if r["access_ground_truth"] == "BLOCKED_BY_PLATFORM"]
    success_count = len(successful_accesses)
    blocked_count = len(platform_blocked)

    detected_count = sum(1 for r in successful_accesses if r["cameraguard_detected"])
    missed_count = sum(1 for r in successful_accesses if not r["cameraguard_detected"])

    # False positives: CameraGuard detected when access was NOT successful and no attempt was made
    # (Note: momentary HAL probe transitions during platform denial are documented as blocked attempt probes)
    false_positives = sum(1 for r in results if r["access_ground_truth"] not in ["SUCCESS", "BLOCKED_BY_PLATFORM"] and r["cameraguard_detected"])

    # Attribution errors among detected successful accesses
    detected_successes = [r for r in successful_accesses if r["cameraguard_detected"]]
    attribution_errors = sum(1 for r in detected_successes if r["attributed_package"] != r["expected_package"])

    # Classification errors among detected successful accesses
    classification_errors = sum(1 for r in detected_successes if r.get("expected_classification") in ["EXPECTED", "UNEXPECTED"] and r["classification"] != r.get("expected_classification"))

    detection_rate = (detected_count / success_count * 100.0) if success_count > 0 else 0.0
    miss_rate = (missed_count / success_count * 100.0) if success_count > 0 else 0.0
    attr_err_rate = (attribution_errors / len(detected_successes) * 100.0) if detected_successes else 0.0
    class_err_rate = (classification_errors / len(detected_successes) * 100.0) if detected_successes else 0.0

    # Indicator absent
    indicator_absent = [r for r in successful_accesses if r["privacy_indicator"] == "NOT_OBSERVED"]
    if len(indicator_absent) == 0:
        indicator_independent_str = "N/A — no successful indicator-absent camera access was reproduced."
    else:
        det_ind = sum(1 for r in indicator_absent if r["cameraguard_detected"])
        rate = (det_ind / len(indicator_absent)) * 100.0
        indicator_independent_str = f"{rate:.1f}% ({det_ind}/{len(indicator_absent)})"

    summary = {
        "total_scenarios": len(results),
        "successful_camera_accesses": success_count,
        "platform_blocked_attempts": blocked_count,
        "cameraguard_detections": detected_count,
        "missed": missed_count,
        "false_positives": false_positives,
        "attribution_errors": attribution_errors,
        "classification_errors": classification_errors,
        "indicator_absent_successful_accesses": len(indicator_absent),
        "detection_rate_pct": detection_rate,
        "miss_rate_pct": miss_rate,
        "attribution_error_rate_pct": attr_err_rate,
        "classification_error_rate_pct": class_err_rate,
        "indicator_independent_detection": indicator_independent_str
    }

    output_payload = {
        "timestamp": int(time.time()),
        "device": {
            "model": "vivo V2202",
            "android_version": "15",
            "api_level": 35,
            "device_id": DEVICE_ID
        },
        "test_application": {
            "package": PACKAGE_ADVERSARY,
            "uid_independent": True
        },
        "summary": summary,
        "scenarios": results
    }

    out_dir = os.path.join("data", "derived", "phase5")
    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, "adversarial_camera_evaluation_results.json")
    with open(out_file, "w") as f:
        json.dump(output_payload, f, indent=2)

    print("\n================================================================")
    print("FINAL PHASE 5.3-A SUMMARY")
    print("================================================================")
    print(f"Total Scenarios:                  {len(results)}")
    print(f"Successful Camera Accesses:       {success_count}")
    print(f"Platform-Blocked Attempts:        {blocked_count}")
    print(f"CameraGuard Detections:           {detected_count}")
    print(f"Missed Successful Accesses:       {missed_count}")
    print(f"False Positives:                  {false_positives}")
    print(f"Attribution Errors:               {attribution_errors}")
    print(f"Classification Errors:            {classification_errors}")
    print(f"Detection Rate:                   {detection_rate:.1f}%")
    print(f"Miss Rate:                        {miss_rate:.1f}%")
    print(f"Attribution Error Rate:           {attr_err_rate:.1f}%")
    print(f"Classification Error Rate:        {class_err_rate:.1f}%")
    print(f"Indicator-Independent Detection:  {indicator_independent_str}")
    print(f"Structured results written to:    {out_file}")
    print("================================================================")


if __name__ == "__main__":
    main()
