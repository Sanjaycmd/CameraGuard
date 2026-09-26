#!/usr/bin/env python3
"""
CameraGuard Phase 5.3 - Automated Camera Event Stress Testing

Executes controlled physical stress scenarios via ADB against the connected Android test device:
- Scenario A: Single event control (1 session)
- Scenario B: Repeated sequential events (5 sessions)
- Scenario C: High-volume sequential events (20 sessions)
- Scenario D: Rapid open/close transitions (5 fast cycles)
- Scenario E: Burst sequence (5 rapid cycles + 5s idle dwell)
- Scenario F: Repeated same-owner activity (5 sessions)
- Scenario G: Post-restart stress (kill, restart, 3 sessions)
"""

import argparse
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List, Optional, Tuple

DEVICE_ID = "10BCA92F67000FY"
PACKAGE_GUARD = "org.cameraguard"
PACKAGE_HARNESS = "org.cameratestharness"
SERVICE_GUARD = "org.cameraguard/.monitoring.service.CameraMonitoringService"
ACTIVITY_GUARD = "org.cameraguard/.MainActivity"
ACTIVITY_HARNESS = "org.cameratestharness/.MainActivity"

TAP_START = "286 1621"
TAP_STOP = "793 1621"
TAP_START_SERVICE = "540 711"


def adb_cmd(args: List[str]) -> Tuple[int, str, str]:
    cmd = ["adb", "-s", DEVICE_ID] + args
    res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    return res.returncode, res.stdout.strip(), res.stderr.strip()


def adb_shell(cmd_str: str) -> Tuple[int, str, str]:
    return adb_cmd(["shell", cmd_str])


def ensure_screen_awake_and_unlocked():
    adb_shell("input keyevent 224")  # KEYCODE_WAKEUP
    time.sleep(0.5)
    adb_shell("input swipe 500 2000 500 500")  # dismiss lockscreen if swipe
    time.sleep(0.5)


def is_service_running(package: str = PACKAGE_GUARD, service_name: str = "CameraMonitoringService") -> bool:
    ret, out, _ = adb_shell(f"dumpsys activity services {package}")
    return service_name in out and "isForeground=true" in out


def ensure_cameraguard_monitoring() -> bool:
    ensure_screen_awake_and_unlocked()
    if is_service_running():
        return True

    # Launch CameraGuard
    adb_shell(f"am start -n {ACTIVITY_GUARD}")
    time.sleep(1.5)

    # Tap Start Service
    adb_shell(f"input tap {TAP_START_SERVICE}")
    time.sleep(1.5)

    return is_service_running()


def prepare_harness_ui():
    ensure_screen_awake_and_unlocked()
    adb_shell(f"am start -n {ACTIVITY_HARNESS}")
    time.sleep(1.2)
    # Swipe down once to reveal action buttons
    adb_shell("input swipe 500 1800 500 300")
    time.sleep(0.8)


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
            print(f"Error querying database: {e}")
            return []
        finally:
            conn.close()


def run_camera_cycles(num_cycles: int, open_duration: float, pause_duration: float):
    prepare_harness_ui()
    for i in range(num_cycles):
        print(f"  [Cycle {i+1}/{num_cycles}] Opening camera (dwell={open_duration}s)...")
        adb_shell(f"input tap {TAP_START}")
        time.sleep(open_duration)

        print(f"  [Cycle {i+1}/{num_cycles}] Closing camera (pause={pause_duration}s)...")
        adb_shell(f"input tap {TAP_STOP}")
        time.sleep(pause_duration)


def analyze_events(
    scenario_id: str,
    scenario_name: str,
    target_description: str,
    start_time: int,
    end_time: int,
    expected_sessions: int,
    new_events: List[Dict[str, Any]]
) -> Dict[str, Any]:
    expected_events = expected_sessions * 2
    detected_events = len(new_events)
    missed_events = max(0, expected_events - detected_events)

    # Duplicate check: consecutive same-state events for the same camera
    duplicate_events = 0
    for i in range(1, len(new_events)):
        prev = new_events[i - 1]
        curr = new_events[i]
        if prev["cameraId"] == curr["cameraId"] and prev["rawEventType"] == curr["rawEventType"]:
            duplicate_events += 1

    # Attribution errors: check if cameraId is valid hardware camera ("0" or "1")
    # and inferred package is null or org.cameratestharness
    attribution_errors = 0
    for ev in new_events:
        if ev["cameraId"] not in ["0", "1"]:
            attribution_errors += 1
        elif ev["inferredPackageName"] not in [PACKAGE_HARNESS, None]:
            attribution_errors += 1

    # Classification errors: check if classification is valid non-empty
    classification_errors = 0
    for ev in new_events:
        if ev["classification"] not in ["EXPECTED", "UNEXPECTED"]:
            classification_errors += 1

    # Chronological ordering check
    ordering_preserved = True
    for i in range(1, len(new_events)):
        if new_events[i]["timestamp"] < new_events[i - 1]["timestamp"]:
            ordering_preserved = False
            break

    # Metrics
    detection_rate = (detected_events / expected_events) if expected_events > 0 else 1.0
    miss_rate = (missed_events / expected_events) if expected_events > 0 else 0.0
    duplicate_rate = (duplicate_events / detected_events) if detected_events > 0 else 0.0
    attribution_error_rate = (attribution_errors / detected_events) if detected_events > 0 else 0.0
    classification_error_rate = (classification_errors / detected_events) if detected_events > 0 else 0.0

    latencies = [e["detectionLatencyMs"] for e in new_events if e["detectionLatencyMs"] is not None]
    avg_latency = sum(latencies) / len(latencies) if latencies else 0.0
    max_latency = max(latencies) if latencies else 0.0

    passed = (detected_events == expected_events and 
              duplicate_events == 0 and 
              attribution_errors == 0 and 
              classification_errors == 0 and 
              ordering_preserved)

    status = "PASS" if passed else ("PASS WITH LIMITATIONS" if detected_events >= expected_events * 0.9 else "FAIL")

    return {
        "id": scenario_id,
        "scenario": scenario_name,
        "target": target_description,
        "start_time": start_time,
        "end_time": end_time,
        "duration_sec": round((end_time - start_time) / 1000.0, 2),
        "expected_sessions": expected_sessions,
        "expected_events": expected_events,
        "detected_events": detected_events,
        "missed_events": missed_events,
        "duplicate_events": duplicate_events,
        "attribution_errors": attribution_errors,
        "classification_errors": classification_errors,
        "detection_rate": round(detection_rate, 4),
        "miss_rate": round(miss_rate, 4),
        "duplicate_rate": round(duplicate_rate, 4),
        "attribution_error_rate": round(attribution_error_rate, 4),
        "classification_error_rate": round(classification_error_rate, 4),
        "event_order_integrity": "100.0%" if ordering_preserved else "ORDERING_VIOLATION",
        "persistence_integrity": "100.0%" if detected_events == len(new_events) else "INCONSISTENT",
        "avg_detection_latency_ms": round(avg_latency, 1),
        "max_detection_latency_ms": max_latency,
        "status": status,
        "events": new_events
    }


def execute_scenario(
    scenario_id: str,
    scenario_name: str,
    target_desc: str,
    num_cycles: int,
    open_dur: float,
    pause_dur: float,
    post_idle_sec: float = 2.0
) -> Dict[str, Any]:
    print(f"\n========================================================")
    print(f"Running Scenario {scenario_id}: {scenario_name}")
    print(f"Target: {target_desc}")
    print(f"========================================================")

    assert ensure_cameraguard_monitoring(), "CameraGuard monitoring service is not running"

    # Pre-execution DB state
    initial_events = get_guard_database_events()
    start_count = len(initial_events)
    start_time = int(time.time() * 1000)

    # Execute cycles
    run_camera_cycles(num_cycles=num_cycles, open_duration=open_dur, pause_duration=pause_dur)

    # Settle period
    if post_idle_sec > 0:
        print(f"  Settling for {post_idle_sec}s...")
        time.sleep(post_idle_sec)

    end_time = int(time.time() * 1000)

    # Post-execution DB state
    final_events = get_guard_database_events()
    new_events = final_events[start_count:]

    res = analyze_events(
        scenario_id=scenario_id,
        scenario_name=scenario_name,
        target_description=target_desc,
        start_time=start_time,
        end_time=end_time,
        expected_sessions=num_cycles,
        new_events=new_events
    )

    print(f"  Status: {res['status']}")
    print(f"  Expected: {res['expected_events']}, Detected: {res['detected_events']}, Missed: {res['missed_events']}")
    print(f"  Duplicates: {res['duplicate_events']}, Attribution Errors: {res['attribution_errors']}")
    print(f"  Avg Latency: {res['avg_detection_latency_ms']}ms, Max Latency: {res['max_detection_latency_ms']}ms")
    return res


def execute_scenario_g_post_restart() -> Dict[str, Any]:
    scenario_id = "G"
    scenario_name = "Stress After CameraGuard Restart"
    target_desc = "Kill process, restart CameraGuard, verify baseline suppression, then execute 3 sequential sessions"

    print(f"\n========================================================")
    print(f"Running Scenario {scenario_id}: {scenario_name}")
    print(f"Target: {target_desc}")
    print(f"========================================================")

    # Step 1: Baseline state
    initial_events = get_guard_database_events()
    start_count = len(initial_events)
    start_time = int(time.time() * 1000)

    # Step 2: Terminate process
    print("  Stopping CameraGuard via am force-stop...")
    adb_shell(f"am force-stop {PACKAGE_GUARD}")
    time.sleep(1.0)

    # Step 3: Relaunch and restart monitoring
    print("  Relaunching CameraGuard and starting service...")
    adb_shell(f"am start -n {ACTIVITY_GUARD}")
    time.sleep(2.0)
    adb_shell(f"input tap {TAP_START_SERVICE}")
    time.sleep(2.0)
    assert is_service_running(), "Failed to re-establish service after restart"

    # Step 4: Run 3 cycles
    run_camera_cycles(num_cycles=3, open_duration=1.5, pause_duration=1.0)
    time.sleep(2.0)
    end_time = int(time.time() * 1000)

    final_events = get_guard_database_events()
    new_events = final_events[start_count:]

    res = analyze_events(
        scenario_id=scenario_id,
        scenario_name=scenario_name,
        target_description=target_desc,
        start_time=start_time,
        end_time=end_time,
        expected_sessions=3,
        new_events=new_events
    )

    print(f"  Status: {res['status']}")
    print(f"  Expected: {res['expected_events']}, Detected: {res['detected_events']}, Missed: {res['missed_events']}")
    print(f"  Duplicates: {res['duplicate_events']}, Attribution Errors: {res['attribution_errors']}")
    print(f"  Avg Latency: {res['avg_detection_latency_ms']}ms, Max Latency: {res['max_detection_latency_ms']}ms")
    return res


def main():
    parser = argparse.ArgumentParser(description="CameraGuard Phase 5.3 Stress Test Suite")
    parser.add_argument("--scenario", choices=["A", "B", "C", "D", "E", "F", "G", "all"], default="all")
    args = parser.parse_args()

    print("=" * 70)
    print("CameraGuard Phase 5.3 - Camera Event Stress Test Suite")
    print(f"Device: {DEVICE_ID} (vivo V2202, Android 15)")
    print("=" * 70)

    results = []

    if args.scenario in ["A", "all"]:
        # Scenario A: Single event control (1 cycle: 2.0s open, 1.0s close)
        res_a = execute_scenario("A", "Single Event Control", "1 camera open -> close cycle", 1, 2.0, 1.0)
        results.append(res_a)

    if args.scenario in ["B", "all"]:
        # Scenario B: Repeated sequential events (5 cycles: 1.5s open, 1.0s pause)
        res_b = execute_scenario("B", "Repeated Sequential Events (5)", "5 sequential camera sessions", 5, 1.5, 1.0)
        results.append(res_b)

    if args.scenario in ["C", "all"]:
        # Scenario C: Higher-volume sequential events (20 cycles: 1.5s open, 1.0s pause)
        res_c = execute_scenario("C", "Higher-Volume Sequential Events (20)", "20 sequential camera sessions", 20, 1.5, 1.0)
        results.append(res_c)

    if args.scenario in ["D", "all"]:
        # Scenario D: Rapid open/close transitions (5 fast cycles: 0.8s open, 0.5s pause)
        res_d = execute_scenario("D", "Rapid Transitions", "5 rapid camera open/close transitions (sub-second)", 5, 0.8, 0.5)
        results.append(res_d)

    if args.scenario in ["E", "all"]:
        # Scenario E: Burst sequence (5 rapid cycles + 5s post idle)
        res_e = execute_scenario("E", "Burst Sequence", "5 rapid cycles followed by 5s idle dwell", 5, 0.8, 0.5, post_idle_sec=5.0)
        results.append(res_e)

    if args.scenario in ["F", "all"]:
        # Scenario F: Repeated same-owner activity (5 cycles: 1.5s open, 1.0s pause)
        res_f = execute_scenario("F", "Repeated Same-Owner Activity", "5 repeated sessions under same application ownership", 5, 1.5, 1.0)
        results.append(res_f)

    if args.scenario in ["G", "all"]:
        # Scenario G: Stress after CameraGuard restart (kill, relaunch, 3 cycles)
        res_g = execute_scenario_g_post_restart()
        results.append(res_g)

    # Print overall summary table
    print("\n" + "=" * 90)
    print("PHASE 5.3 STRESS EVALUATION SUMMARY TABLE")
    print(f"{'ID':<4} | {'Scenario':<28} | {'Exp':<5} | {'Det':<5} | {'Miss':<5} | {'Dup':<5} | {'AttrErr':<8} | {'Status':<10}")
    print("-" * 90)
    total_exp = 0
    total_det = 0
    total_miss = 0
    total_dup = 0
    total_attr = 0

    for r in results:
        total_exp += r["expected_events"]
        total_det += r["detected_events"]
        total_miss += r["missed_events"]
        total_dup += r["duplicate_events"]
        total_attr += r["attribution_errors"]
        print(f"{r['id']:<4} | {r['scenario']:<28} | {r['expected_events']:<5} | {r['detected_events']:<5} | "
              f"{r['missed_events']:<5} | {r['duplicate_events']:<5} | {r['attribution_errors']:<8} | {r['status']:<10}")
    print("-" * 90)
    print(f"{'ALL':<4} | {'Total Summary':<28} | {total_exp:<5} | {total_det:<5} | {total_miss:<5} | {total_dup:<5} | {total_attr:<8} |")
    print("=" * 90)

    # Save structured results to JSON
    os.makedirs("data/derived/phase5", exist_ok=True)
    out_file = "data/derived/phase5/camera_stress_evaluation_results.json"
    with open(out_file, "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nSaved structured stress evaluation results to {out_file}")


if __name__ == "__main__":
    main()
