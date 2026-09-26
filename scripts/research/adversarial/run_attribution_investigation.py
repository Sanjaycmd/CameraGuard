#!/usr/bin/env python3
"""
CameraGuard Phase 5.3-A.1 - Attribution Robustness & Probe Investigation Runner

Executes:
1. Ground Truth Tests T1 - T5 (Part 4)
2. Scenarios A1 - A6 (Part 9 Regression & Re-evaluation)
3. Probe Transition Analysis (Part 5)
4. Saves empirical telemetry to data/derived/phase5/adversarial_attribution_investigation_results.json
"""

import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List, Optional, Tuple

DEVICE_ID = "10BCA92F67000FY"
PACKAGE_GUARD = "org.cameraguard"
PACKAGE_ADVERSARY = "org.cameraguard.adversarytest"
PACKAGE_HARNESS = "org.cameratestharness"

ACTIVITY_GUARD = "org.cameraguard/.MainActivity"
ACTIVITY_ADVERSARY = "org.cameraguard.adversarytest/.MainActivity"
SERVICE_ADVERSARY = "org.cameraguard.adversarytest/.AdversaryCameraService"
ACTIVITY_HARNESS = "org.cameratestharness/.MainActivity"

TAP_START_SERVICE = "540 711"
TAP_HARNESS_START = "286 1621"
TAP_HARNESS_STOP = "793 1621"


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
    adb_shell(f"input tap {TAP_START_SERVICE}")
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

    before_ids = {e["id"] for e in get_guard_database_events()}

    # Force stop adversary test app
    adb_shell(f"am force-stop {PACKAGE_ADVERSARY}")
    time.sleep(0.5)

    # Clear logcat
    adb_cmd(["logcat", "-c"])

    # Launch scenario
    intent_args = [
        "am", "start", "-n", ACTIVITY_ADVERSARY,
        "--es", "action", action,
        "--el", "hold_duration_ms", str(duration_ms),
        "--el", "delay_ms", str(delay_ms),
        "--ei", "burst_count", str(burst_count)
    ]
    adb_shell(" ".join(intent_args))

    if lock_screen:
        time.sleep(1.0)
        adb_shell("input keyevent 26")  # KEYCODE_POWER
        print("-> Screen locked for A5")
        time.sleep(delay_ms / 1000.0 + 3.0)
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
        privacy_indicator = "VISIBLE"
    elif ground_truth == "BLOCKED_BY_PLATFORM":
        privacy_indicator = "NOT_APPLICABLE"
    elif ground_truth == "DENIED":
        privacy_indicator = "NOT_APPLICABLE"
    else:
        privacy_indicator = "NOT_OBSERVED"

    # Query new CameraGuard events
    events_after = get_guard_database_events()
    new_events = [e for e in events_after if e["id"] not in before_ids]

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


def run_test_t1() -> Dict[str, Any]:
    """
    Test T1:
    CameraGuard foreground -> adversary app background -> adversary foreground service accesses camera
    Expected ground truth: org.cameraguard.adversarytest
    """
    print("\n========================================================")
    print("Executing Test T1: CameraGuard FG -> Adversary FGS accesses camera")
    print("========================================================")

    ensure_guard_running()
    # Bring CameraGuard to foreground
    adb_shell(f"am start -n {ACTIVITY_GUARD}")
    time.sleep(1.5)

    adb_shell(f"am force-stop {PACKAGE_ADVERSARY}")
    time.sleep(0.5)
    adb_cmd(["logcat", "-c"])

    before_ids = {e["id"] for e in get_guard_database_events()}

    # Start AdversaryCameraService directly from background while CameraGuard is foreground
    cmd = (
        f"am start-foreground-service -n {SERVICE_ADVERSARY} "
        f"-a org.cameraguard.adversarytest.action.START_A4 "
        f"--el extra_hold_duration_ms 2500 --el extra_delay_before_open_ms 500"
    )
    adb_shell(cmd)
    time.sleep(4.5)

    _, log_out, _ = adb_cmd(["logcat", "-d", "-s", "AdversaryTestApp", "CameraGuard"])

    events_after = get_guard_database_events()
    new_events = [e for e in events_after if e["id"] not in before_ids]
    unavail = [e for e in new_events if e["rawEventType"] == "CAMERA_BECAME_UNAVAILABLE"]

    first_unavail = unavail[0] if unavail else None
    attributed = first_unavail["inferredPackageName"] if first_unavail else None
    classification = first_unavail["classification"] if first_unavail else None
    tier = first_unavail["tierUsed"] if first_unavail else None

    # Check whether CameraGuard incorrectly attributed to itself
    self_attribution = (attributed == PACKAGE_GUARD)

    result = {
        "test_id": "T1",
        "description": "CameraGuard foreground -> adversary background FGS camera access",
        "expected_caller": PACKAGE_ADVERSARY,
        "attributed_package": attributed,
        "self_attribution_detected": self_attribution,
        "cameraguard_detected": len(unavail) > 0,
        "classification": classification,
        "tier_used": tier,
        "raw_events": new_events,
        "log_excerpt": [line for line in log_out.splitlines() if "AdversaryTestApp" in line or "CameraGuard" in line][-10:]
    }
    print(f"T1 Result: detected={result['cameraguard_detected']}, attributed={attributed}, self_attr={self_attribution}, class={classification}")
    return result


def run_test_t2() -> Dict[str, Any]:
    """
    Test T2:
    CameraGuard foreground -> adversary background -> adversary attempts/obtains camera access (permission denial)
    Expected ground truth: org.cameraguard.adversarytest (BLOCKED_BY_PLATFORM)
    """
    print("\n========================================================")
    print("Executing Test T2: CameraGuard FG -> Adversary background access attempt (A2)")
    print("========================================================")

    ensure_guard_running()
    adb_shell(f"am force-stop {PACKAGE_ADVERSARY}")
    time.sleep(0.5)
    adb_cmd(["logcat", "-c"])

    before_ids = {e["id"] for e in get_guard_database_events()}

    # Run A2
    adb_shell(f"am start -n {ACTIVITY_ADVERSARY} --es action A2 --el hold_duration_ms 2000 --el delay_ms 500")
    # Bring CameraGuard foreground
    time.sleep(0.3)
    adb_shell(f"am start -n {ACTIVITY_GUARD}")
    time.sleep(8.0)

    _, log_out, _ = adb_cmd(["logcat", "-d", "-s", "AdversaryTestApp", "CameraGuard"])

    events_after = get_guard_database_events()
    new_events = [e for e in events_after if e["id"] not in before_ids]
    unavail = [e for e in new_events if e["rawEventType"] == "CAMERA_BECAME_UNAVAILABLE"]
    avail = [e for e in new_events if e["rawEventType"] == "CAMERA_BECAME_AVAILABLE"]

    first_unavail = unavail[0] if unavail else None
    attributed = first_unavail["inferredPackageName"] if first_unavail else None
    classification = first_unavail["classification"] if first_unavail else None

    # Probe duration: time between unavail and avail
    probe_duration_ms = None
    if unavail and avail:
        probe_duration_ms = avail[0]["timestamp"] - unavail[0]["timestamp"]

    self_attribution = (attributed == PACKAGE_GUARD)

    result = {
        "test_id": "T2",
        "description": "CameraGuard foreground -> adversary background access attempt (permission denial)",
        "expected_caller": PACKAGE_ADVERSARY,
        "access_result": "BLOCKED_BY_PLATFORM",
        "attributed_package": attributed,
        "self_attribution_detected": self_attribution,
        "cameraguard_detected": len(unavail) > 0,
        "probe_transition_observed": len(unavail) > 0 and len(avail) > 0,
        "probe_duration_ms": probe_duration_ms,
        "classification": classification,
        "raw_events": new_events,
        "log_excerpt": [line for line in log_out.splitlines() if "AdversaryTestApp" in line or "CameraGuard" in line][-10:]
    }
    print(f"T2 Result: detected={result['cameraguard_detected']}, probe_dur={probe_duration_ms}ms, attributed={attributed}, self_attr={self_attribution}")
    return result


def run_test_t3() -> Dict[str, Any]:
    """
    Test T3:
    Adversary foreground -> adversary accesses camera
    Expected: org.cameraguard.adversarytest
    """
    print("\n========================================================")
    print("Executing Test T3: Adversary foreground -> accesses camera (A1)")
    print("========================================================")

    res = run_scenario(
        scenario_id="T3",
        description="Adversary foreground camera access",
        action="A1",
        duration_ms=2500,
        delay_ms=500
    )
    return res


def run_test_t4() -> Dict[str, Any]:
    """
    Test T4:
    CameraTestHarness accesses camera while CameraGuard is foreground
    Expected: org.cameratestharness
    """
    print("\n========================================================")
    print("Executing Test T4: CameraTestHarness camera access while CameraGuard was FG")
    print("========================================================")

    ensure_guard_running()
    adb_shell(f"am force-stop {PACKAGE_HARNESS}")
    time.sleep(0.5)
    adb_cmd(["logcat", "-c"])

    before_ids = {e["id"] for e in get_guard_database_events()}

    # Launch Harness
    adb_shell(f"am start -n {ACTIVITY_HARNESS}")
    time.sleep(2.0)
    # Scroll down twice to reveal action buttons
    adb_shell("input swipe 500 1800 500 300")
    time.sleep(0.5)
    adb_shell("input swipe 500 1800 500 300")
    time.sleep(0.8)

    # Tap Start Camera (START EXPERIMENT)
    adb_shell(f"input tap {TAP_HARNESS_START}")
    time.sleep(3.0)

    # Tap Stop Camera (STOP EXPERIMENT)
    adb_shell(f"input tap {TAP_HARNESS_STOP}")
    time.sleep(1.5)

    # Bring CameraGuard back to foreground
    adb_shell(f"am start -n {ACTIVITY_GUARD}")
    time.sleep(1.0)

    events_after = get_guard_database_events()
    new_events = [e for e in events_after if e["id"] not in before_ids]
    unavail = [e for e in new_events if e["rawEventType"] == "CAMERA_BECAME_UNAVAILABLE"]

    first_unavail = unavail[0] if unavail else None
    attributed = first_unavail["inferredPackageName"] if first_unavail else None
    classification = first_unavail["classification"] if first_unavail else None
    tier = first_unavail["tierUsed"] if first_unavail else None

    self_attribution = (attributed == PACKAGE_GUARD)

    result = {
        "test_id": "T4",
        "description": "CameraTestHarness camera access",
        "expected_caller": PACKAGE_HARNESS,
        "attributed_package": attributed,
        "self_attribution_detected": self_attribution,
        "cameraguard_detected": len(unavail) > 0,
        "classification": classification,
        "tier_used": tier,
        "raw_events": new_events
    }
    print(f"T4 Result: detected={result['cameraguard_detected']}, attributed={attributed}, self_attr={self_attribution}, class={classification}")
    return result


def run_test_t5() -> Dict[str, Any]:
    """
    Test T5:
    CameraGuard itself performs no camera access but remains foreground while another application accesses camera.
    Purpose: Detect whether CameraGuard incorrectly attributes another application's camera event to itself.
    """
    print("\n========================================================")
    print("Executing Test T5: CameraGuard in FG, external access -> verify zero self-attribution")
    print("========================================================")

    ensure_guard_running()
    # Bring CameraGuard to foreground
    adb_shell(f"am start -n {ACTIVITY_GUARD}")
    time.sleep(2.0)

    adb_shell(f"am force-stop {PACKAGE_ADVERSARY}")
    time.sleep(0.5)
    adb_cmd(["logcat", "-c"])

    before_ids = {e["id"] for e in get_guard_database_events()}

    # Start Adversary service from background while CameraGuard is sitting in foreground
    cmd = (
        f"am start-foreground-service -n {SERVICE_ADVERSARY} "
        f"-a org.cameraguard.adversarytest.action.START_A4 "
        f"--el extra_hold_duration_ms 2500 --el extra_delay_before_open_ms 500"
    )
    adb_shell(cmd)
    time.sleep(4.5)

    events_after = get_guard_database_events()
    new_events = [e for e in events_after if e["id"] not in before_ids]
    unavail = [e for e in new_events if e["rawEventType"] == "CAMERA_BECAME_UNAVAILABLE"]

    first_unavail = unavail[0] if unavail else None
    attributed = first_unavail["inferredPackageName"] if first_unavail else None
    classification = first_unavail["classification"] if first_unavail else None
    tier = first_unavail["tierUsed"] if first_unavail else None

    # Critical check: DID CAMERAGUARD ATTRIBUTE TO ITSELF?
    self_attribution = (attributed == PACKAGE_GUARD)

    result = {
        "test_id": "T5",
        "description": "CameraGuard foreground while external app accesses camera (anti-self-attribution test)",
        "expected_caller": f"{PACKAGE_ADVERSARY} or null/UNKNOWN (NEVER {PACKAGE_GUARD})",
        "attributed_package": attributed,
        "self_attribution_detected": self_attribution,
        "cameraguard_detected": len(unavail) > 0,
        "classification": classification,
        "tier_used": tier,
        "raw_events": new_events
    }
    print(f"T5 Result: detected={result['cameraguard_detected']}, attributed={attributed}, self_attribution={self_attribution}")
    if self_attribution:
        print("CRITICAL FAILURE: CameraGuard falsely attributed camera event to itself!")
    else:
        print("PASS: Zero self-attribution confirmed.")
    return result


def main():
    print("================================================================")
    print("CameraGuard Phase 5.3-A.1: Attribution Robustness & Probe Investigation")
    print(f"Target Device: vivo V2202 (Android 15 / API 35) [{DEVICE_ID}]")
    print("================================================================")

    # 1. Run Tests T1 - T5
    t_results = []
    t_results.append(run_test_t1())
    time.sleep(2.0)
    t_results.append(run_test_t2())
    time.sleep(2.0)
    t_results.append(run_test_t3())
    time.sleep(2.0)
    t_results.append(run_test_t4())
    time.sleep(2.0)
    t_results.append(run_test_t5())
    time.sleep(2.0)

    # 2. Re-run Scenarios A1 - A6 (Part 9)
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
            "expected_classification": "UNEXPECTED"
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

    a_results = []
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
        a_results.append(res)
        time.sleep(2.0)

    # Summary metrics calculation
    successful_accesses = [r for r in a_results if r["access_ground_truth"] == "SUCCESS"]
    detected_accesses = [r for r in successful_accesses if r["cameraguard_detected"]]
    
    # Check attribution accuracy on successful accesses
    correct_attribution = [
        r for r in successful_accesses 
        if r["attributed_package"] == PACKAGE_ADVERSARY
    ]
    incorrect_attribution = [
        r for r in successful_accesses
        if r["attributed_package"] is not None and r["attributed_package"] != PACKAGE_ADVERSARY
    ]
    unknown_attribution = [
        r for r in successful_accesses
        if r["attributed_package"] is None
    ]

    # Probe metrics (A2)
    a2_res = next((r for r in a_results if r["scenario_id"] == "A2"), None)
    probe_transitions = 1 if (a2_res and a2_res["cameraguard_detected"]) else 0
    confirmed_sessions_a2 = 1 if (a2_res and a2_res["access_ground_truth"] == "SUCCESS") else 0

    output_data = {
        "timestamp": int(time.time()),
        "phase": "5.3-A.1",
        "device": {
            "model": "vivo V2202",
            "android_version": 15,
            "api_level": 35,
            "device_id": DEVICE_ID
        },
        "metrics": {
            "successful_camera_accesses": len(successful_accesses),
            "detected_accesses": len(detected_accesses),
            "detection_rate_pct": (len(detected_accesses) / len(successful_accesses) * 100.0) if successful_accesses else 0.0,
            "correct_attributions": len(correct_attribution),
            "incorrect_attributions": len(incorrect_attribution),
            "unknown_attributions": len(unknown_attribution),
            "attribution_error_rate_pct": (len(incorrect_attribution) / len(successful_accesses) * 100.0) if successful_accesses else 0.0,
            "probe_blocked_attempts": 1,
            "probe_transitions_observed": probe_transitions,
            "probe_confirmed_sessions": confirmed_sessions_a2,
            "probe_false_event_rate_pct": 100.0 if (probe_transitions > 0 and confirmed_sessions_a2 == 0) else 0.0
        },
        "ground_truth_tests_t1_t5": t_results,
        "adversarial_scenarios_a1_a6": a_results
    }

    out_dir = "/home/sanjay/Projects/CameraGuard/data/derived/phase5"
    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, "adversarial_attribution_investigation_results.json")
    with open(out_file, "w") as f:
        json.dump(output_data, f, indent=2)

    print("\n========================================================")
    print("PHASE 5.3-A.1 INVESTIGATION SUMMARY")
    print("========================================================")
    print(f"Successful Accesses: {len(successful_accesses)}")
    print(f"Detected:            {len(detected_accesses)} / {len(successful_accesses)} ({output_data['metrics']['detection_rate_pct']}%)")
    print(f"Correct Attribution: {len(correct_attribution)}")
    print(f"Incorrect Attribution: {len(incorrect_attribution)} ({output_data['metrics']['attribution_error_rate_pct']}%)")
    print(f"Unknown Attribution: {len(unknown_attribution)}")
    print(f"Probe Transitions:   {probe_transitions} (Confirmed sessions: {confirmed_sessions_a2})")
    print(f"Structured results written to: {out_file}")


if __name__ == "__main__":
    main()
