#!/usr/bin/env python3
import json
import sys
from pathlib import Path

import pandas as pd


def load_json(path: Path):
    if not path.exists():
        return None
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def main(session_dir: str):
    root = Path(session_dir)
    imu = pd.read_csv(root / "imu.csv")
    gps = pd.read_csv(root / "gps.csv")
    info = load_json(root / "session_info.json")
    quality = load_json(root / "quality_report.json")

    print(f"Session: {root.name}")
    if info:
        print(f"Experiment: {info.get('experiment_id') or '-'}")
        print(f"Vehicle: {info.get('vehicle') or '-'}")
        print(f"Route: {info.get('route') or '-'}")
        print(f"Requested sampling: {info.get('sampling_label') or '-'}")
    print(f"IMU events: {len(imu):,}")
    print(f"GPS fixes: {len(gps):,}")
    print()

    for sensor_type, group in imu.groupby("sensor_type"):
        ts = group["sensor_timestamp_ns"].sort_values().to_numpy()
        if len(ts) > 1:
            dt = (ts[1:] - ts[:-1]) / 1e9
            median_dt = pd.Series(dt).median()
            median_hz = 1.0 / median_dt if median_dt > 0 else float("nan")
            avg_hz = (len(ts) - 1) / ((ts[-1] - ts[0]) / 1e9) if ts[-1] > ts[0] else float("nan")
            print(f"{sensor_type:22s}: {len(group):8,d} events, avg {avg_hz:7.2f} Hz, median {median_hz:7.2f} Hz")
        else:
            print(f"{sensor_type:22s}: {len(group):8,d} events")

    if not gps.empty:
        print()
        print(f"Start lat/lon: {gps.iloc[0].latitude:.7f}, {gps.iloc[0].longitude:.7f}")
        print(f"End lat/lon  : {gps.iloc[-1].latitude:.7f}, {gps.iloc[-1].longitude:.7f}")
        print(f"Median accuracy: {gps.accuracy_m.median():.2f} m")
        if gps.speed_mps.notna().any():
            print(f"Max GNSS speed: {gps.speed_mps.max() * 3.6:.2f} km/h")

    if quality:
        print("\nQuality report")
        print(f"Duration: {quality.get('duration_ms', 0) / 1000:.1f} s")
        print(f"Accelerometer avg: {quality.get('accelerometer_avg_hz') or '-'} Hz")
        print(f"Gyroscope avg: {quality.get('gyroscope_avg_hz') or '-'} Hz")
        print(f"GPS avg accuracy: {quality.get('gps_avg_accuracy_m') or '-'} m")
        print(f"Largest GPS gap: {quality.get('gps_max_gap_ms', 0)} ms")
        print(f"Interrupted: {quality.get('interrupted', False)}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: inspect_session.py /path/to/session_folder")
    main(sys.argv[1])
