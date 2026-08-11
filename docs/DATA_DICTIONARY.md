# Data Dictionary

## imu.csv

| Column | Unit / meaning |
|---|---|
| session_id | internal timestamp-based session ID |
| sensor_timestamp_ns | monotonic Android sensor timestamp, nanoseconds |
| wall_time_ms | estimated Unix wall-clock time in milliseconds |
| sensor_type | accelerometer, gyroscope, linear_acceleration, gravity |
| x, y, z | raw three-axis sensor values |
| accuracy | Android sensor accuracy code |

Accelerometer, linear acceleration, and gravity use `m/s²`. Gyroscope uses `rad/s`.

## gps.csv

| Column | Unit / meaning |
|---|---|
| elapsed_realtime_ns | monotonic Android location timestamp, nanoseconds |
| wall_time_ms | Unix location time in milliseconds |
| provider | Android location provider |
| latitude / longitude | decimal degrees |
| altitude_m | meters, NaN if unavailable |
| speed_mps | meters/second, NaN if unavailable |
| bearing_deg | degrees, NaN if unavailable |
| accuracy_m | horizontal accuracy radius in meters |
| vertical_accuracy_m | meters, NaN if unavailable |
| speed_accuracy_mps | meters/second, NaN if unavailable |
| bearing_accuracy_deg | degrees, NaN if unavailable |

## markers.csv

Manual annotations. `label` is one of `pothole`, `rough_road`, `speed_bump`, or `other` in the v0.2 UI. Coordinates are the latest GNSS fix available when the marker is pressed.

## session_info.json

Human-entered experiment metadata plus phone/build information and requested sampling configuration.

## quality_report.json

Post-session integrity summary. Sampling-rate fields are measured from actual monotonic sensor timestamps and should not be interpreted as a guarantee of constant instantaneous sampling frequency.
