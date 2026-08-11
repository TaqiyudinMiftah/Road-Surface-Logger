# Road Surface Logger (Android)

Research-oriented Android application for acquiring road-condition data from smartphone IMU and GNSS sensors. The application **does not perform automatic pothole detection**. Its role is to preserve raw sensor and location streams with timestamps for offline analysis.

## Features

- Raw accelerometer
- Raw gyroscope (if available)
- Linear acceleration (if available)
- Gravity sensor (if available)
- GPS/GNSS latitude, longitude, altitude, speed, bearing, and accuracy
- Configurable IMU target: 50 Hz, 100 Hz, 200 Hz, or FASTEST
- Live measured accelerometer/gyroscope sampling rate
- Live IMU dashboard showing X/Y/Z accelerometer, gyroscope, linear acceleration, and gravity values
- Live GNSS dashboard showing latitude, longitude, altitude, speed, bearing, accuracy, and fix age
- Experiment metadata form before recording
- Manual markers: pothole, rough road, speed bump, and other
- Foreground service and partial wake lock for field logging
- Session history and ZIP export
- Automatic `session_info.json` and post-session `quality_report.json`
- Device and sensor metadata
- No server/cloud required
- No general storage permission required

The live dashboard is intended only for operator monitoring and is refreshed at a much lower rate than the raw sensor stream. The CSV files continue to preserve the original sensor event timestamps and requested acquisition rate.

## Session output

Each experiment is stored as:

```text
session_YYYYMMDD_HHMMSS_SSS/
├── imu.csv
├── gps.csv
├── markers.csv
├── session_info.json
├── metadata.txt
└── quality_report.json
```

### `imu.csv`

```text
session_id,sensor_timestamp_ns,wall_time_ms,sensor_type,x,y,z,accuracy
```

`sensor_timestamp_ns` uses Android's monotonic sensor time base. Units:

- accelerometer / linear acceleration / gravity: m/s²
- gyroscope: rad/s

The Android device coordinate system is stored as-is. Keep phone mounting and orientation consistent across experiments when comparing axes directly.

### `gps.csv`

```text
session_id,elapsed_realtime_ns,wall_time_ms,provider,latitude,longitude,altitude_m,speed_mps,bearing_deg,accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg
```

Use `sensor_timestamp_ns` and `elapsed_realtime_ns` for IMU ↔ GNSS synchronization because both use compatible monotonic time bases.

### `markers.csv`

Manual ground-truth annotations created from the event buttons. Markers are not automatic road-damage classifications.

> Do not operate marker buttons while driving. Use a passenger/operator or annotate the experiment afterward.

### `session_info.json`

Contains experiment-level information such as:

- experiment ID
- vehicle
- phone mount position
- phone orientation
- route
- weather / road condition
- tire pressure
- passengers
- target speed
- notes
- requested sampling target
- phone / Android information

### `quality_report.json`

Generated after a normal stop or an interrupted recording. It summarizes:

- duration
- requested sampling target
- accelerometer and gyroscope sample counts
- measured average accelerometer / gyroscope rate
- linear acceleration / gravity sample counts
- GPS fix count
- average GPS accuracy
- largest GPS time gap
- file/session sizes
- whether the session ended unexpectedly

## Open the project

1. Install a modern Android Studio with JDK 17 and Android SDK 36.
2. Open the `Road-Surface-Logger` folder.
3. Wait for Gradle Sync.
4. Connect a physical Android phone with USB debugging enabled.
5. Run `app`.
6. When location permission appears, choose **Precise location**.

Project configuration:

- Android Gradle Plugin 8.13.2
- Kotlin 2.2.21
- Gradle 8.13
- compileSdk / targetSdk 36
- minSdk 26
- app version 0.2.1

## Suggested experiment workflow

1. Mount the phone rigidly.
2. Keep phone orientation consistent.
3. Enable GPS and wait for a stable fix.
4. Press **START NEW EXPERIMENT**.
5. Fill in experiment metadata and select a sampling target.
6. Confirm the live IMU and GNSS values are updating.
7. Record the route without moving the phone mount.
8. Use event markers only when safely operated.
9. Press **STOP RECORDING**.
10. Review the generated quality report.
11. Export the session ZIP.

## Sampling note

The requested sensor period is a target. The application does not assume the phone delivers exactly that frequency. It stores every event timestamp and reports the measured average accelerometer and gyroscope frequency.

## Quick analysis

Use:

```bash
python tools/inspect_session.py /path/to/session_folder
```

Requires Python 3 and pandas.

See `docs/DATA_DICTIONARY.md` for the dataset field definitions.
