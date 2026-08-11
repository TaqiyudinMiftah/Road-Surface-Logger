# Data Dictionary

## imu.csv

| Field | Unit | Description |
|---|---|---|
| session_id | - | Session identifier |
| sensor_timestamp_ns | ns | Monotonic Android sensor timestamp |
| wall_time_ms | ms | Estimated Unix wall clock mapped from the sensor timestamp |
| sensor_type | - | `accelerometer`, `gyroscope`, `linear_acceleration`, or `gravity` |
| x | sensor-specific | X-axis value |
| y | sensor-specific | Y-axis value |
| z | sensor-specific | Z-axis value |
| accuracy | integer | Android-reported sensor accuracy code |

Accelerometer, linear acceleration, and gravity use m/s². Gyroscope uses rad/s.

## orientation.csv

Generated when a `TYPE_ROTATION_VECTOR` sensor is available.

| Field | Unit | Description |
|---|---|---|
| session_id | - | Session identifier |
| sensor_timestamp_ns | ns | Monotonic rotation-vector timestamp |
| wall_time_ms | ms | Estimated Unix wall clock mapped from sensor timestamp |
| quat_x | unitless | Quaternion/vector X component |
| quat_y | unitless | Quaternion/vector Y component |
| quat_z | unitless | Quaternion/vector Z component |
| quat_w | unitless | Quaternion scalar component |
| azimuth_deg | degrees | Device azimuth derived from rotation matrix |
| pitch_deg | degrees | Device pitch derived from rotation matrix |
| roll_deg | degrees | Device roll derived from rotation matrix |
| heading_accuracy_rad | radians | Estimated heading accuracy when supplied by Android |

The 3D phone preview is driven by these orientation angles. It is a visualization aid; `orientation.csv` remains the analysis source.

## gps.csv

| Field | Unit | Description |
|---|---|---|
| session_id | - | Session identifier |
| elapsed_realtime_ns | ns | Monotonic Android location timestamp |
| wall_time_ms | ms | Location wall-clock timestamp |
| provider | - | Android location provider |
| latitude | degrees | WGS84 latitude |
| longitude | degrees | WGS84 longitude |
| altitude_m | m | Altitude when available |
| speed_mps | m/s | GNSS/location speed when available |
| bearing_deg | degrees | Travel bearing when available |
| accuracy_m | m | Horizontal accuracy estimate |
| vertical_accuracy_m | m | Vertical accuracy when available |
| speed_accuracy_mps | m/s | Speed accuracy when available |
| bearing_accuracy_deg | degrees | Bearing accuracy when available |

The Route Map connects consecutive valid latitude/longitude rows as a polyline. It does not modify `gps.csv` and does not perform map matching or snap-to-road processing.

## markers.csv

Manual event annotations. `label` currently supports `pothole`, `rough_road`, `speed_bump`, and `other`. Latitude/longitude store the latest available fix at the time of the marker. Valid marker coordinates are also shown on the Route Map.

## session_info.json

Experiment-level metadata entered before recording, requested sampling target, and device information.

## quality_report.json

Post-session summary of actual sample counts/rates, orientation sample count, GPS availability and accuracy, gaps, session size, and interrupted/normal completion status.

## Live dashboard

The live IMU, orientation, and GNSS panels are operator previews only. They display the most recently received values. The preview refreshes more slowly than raw acquisition and does not resample, filter, or replace the CSV data.

`Fix age` is the elapsed time between the most recently received location fix and the current monotonic clock. It is useful for noticing stale GNSS data during an experiment.
