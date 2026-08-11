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

## markers.csv

Manual event annotations. `label` currently supports `pothole`, `rough_road`, `speed_bump`, and `other`. Latitude/longitude store the latest available fix at the time of the marker.

## session_info.json

Experiment-level metadata entered before recording, requested sampling target, and device information.

## quality_report.json

Post-session summary of actual sample counts/rates, GPS availability and accuracy, gaps, session size, and interrupted/normal completion status.

## Live dashboard

The live IMU and GNSS panels are operator previews only. They display the most recently received accelerometer, gyroscope, linear acceleration, gravity, and GNSS values. The preview refreshes more slowly than raw acquisition and does not resample, filter, or replace the CSV data.

`Fix age` is the elapsed time between the most recently received location fix and the current monotonic clock. It is useful for noticing stale GNSS data during an experiment.
