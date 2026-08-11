#!/usr/bin/env python3
import sys
from pathlib import Path
import pandas as pd


def main(session_dir: str):
    root = Path(session_dir)
    imu = pd.read_csv(root / "imu.csv")
    gps = pd.read_csv(root / "gps.csv")

    print(f"Session: {root.name}")
    print(f"IMU events: {len(imu):,}")
    print(f"GPS fixes: {len(gps):,}")
    print()

    for sensor_type, group in imu.groupby("sensor_type"):
        ts = group["sensor_timestamp_ns"].sort_values().to_numpy()
        if len(ts) > 1:
            dt = (ts[1:] - ts[:-1]) / 1e9
            median_dt = pd.Series(dt).median()
            hz = 1.0 / median_dt if median_dt > 0 else float("nan")
            print(f"{sensor_type:22s}: {len(group):8,d} events, median ~ {hz:7.2f} Hz")
        else:
            print(f"{sensor_type:22s}: {len(group):8,d} events")

    if not gps.empty:
        print()
        print(f"Start lat/lon: {gps.iloc[0].latitude:.7f}, {gps.iloc[0].longitude:.7f}")
        print(f"End lat/lon  : {gps.iloc[-1].latitude:.7f}, {gps.iloc[-1].longitude:.7f}")
        print(f"Median accuracy: {gps.accuracy_m.median():.2f} m")
        if gps.speed_mps.notna().any():
            print(f"Max GNSS speed: {gps.speed_mps.max() * 3.6:.2f} km/h")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: inspect_session.py /path/to/session_folder")
    main(sys.argv[1])
