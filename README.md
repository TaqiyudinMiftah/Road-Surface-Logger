# Road Surface Logger (Android)

MVP Android native untuk akuisisi data kondisi jalan menggunakan sensor bawaan smartphone.
Aplikasi **tidak melakukan deteksi pothole otomatis**. Fokusnya adalah menyimpan data mentah IMU + GNSS dengan timestamp untuk analisis offline.

## Fitur

- Raw accelerometer
- Raw gyroscope (jika tersedia)
- Linear acceleration (jika tersedia)
- Gravity sensor (jika tersedia)
- GPS/GNSS latitude, longitude, altitude, speed, bearing, dan accuracy
- Target sampling IMU 100 Hz
- Foreground service: logging tetap berjalan saat layar mati
- Manual event marker opsional
- Satu folder per sesi
- Export sesi menjadi ZIP melalui Android Storage Access Framework
- Metadata perangkat dan karakteristik sensor otomatis
- Tidak membutuhkan server/cloud
- Tidak membutuhkan permission penyimpanan umum

## Struktur output

Setiap sesi dibuat sebagai:

```text
session_YYYYMMDD_HHMMSS_SSS/
├── imu.csv
├── gps.csv
├── markers.csv
└── metadata.txt
```

### `imu.csv`

```text
session_id,sensor_timestamp_ns,wall_time_ms,sensor_type,x,y,z,accuracy
```

`sensor_timestamp_ns` menggunakan basis waktu monotonic Android (nanoseconds since boot), sama dengan basis `elapsedRealtimeNanos`. Ini adalah timestamp utama yang disarankan untuk sinkronisasi sensor.

Unit:
- accelerometer / linear acceleration / gravity: m/s²
- gyroscope: rad/s

Android device coordinate system digunakan apa adanya. Jangan mengubah orientasi HP selama eksperimen jika Anda ingin membandingkan sumbu secara langsung.

### `gps.csv`

```text
session_id,elapsed_realtime_ns,wall_time_ms,provider,latitude,longitude,altitude_m,speed_mps,bearing_deg,accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg
```

Untuk sinkronisasi IMU ↔ GPS, gunakan `sensor_timestamp_ns` dari IMU dan `elapsed_realtime_ns` dari GPS karena keduanya berada pada basis waktu monotonic yang kompatibel.

### `markers.csv`

Tombol **MARK EVENT** menambahkan marker waktu manual. Fitur ini hanya untuk anotasi/ground truth dan bukan deteksi otomatis.

> Jangan mengoperasikan tombol ketika Anda sendiri sedang mengemudi. Gunakan penumpang/operator atau lakukan anotasi setelah eksperimen.

### `metadata.txt`

Berisi:
- manufacturer / model HP
- Android version
- start timestamp
- target sampling period
- nama/vendor/resolution/range/min-delay sensor
- unit data

## Membuka project

1. Install Android Studio versi modern dengan JDK 17 dan Android SDK 36.
2. Open folder `RoadSurfaceLogger`.
3. Tunggu Gradle Sync.
4. Hubungkan HP Android (USB debugging aktif) atau pilih emulator yang memiliki sensor/location simulation.
5. Run `app`.
6. Saat permission muncul, pilih **Precise location**.

Project menggunakan:
- Android Gradle Plugin 8.13.2
- Kotlin 2.2.21
- Gradle 8.13
- compileSdk / targetSdk 36
- minSdk 26

## Prosedur eksperimen yang disarankan

1. Pasang HP pada holder yang rigid.
2. Gunakan orientasi HP yang sama untuk seluruh pengujian.
3. Aktifkan GPS dan tunggu posisi stabil.
4. Tekan **START RECORDING** sebelum perjalanan.
5. Hindari memindahkan HP selama sesi.
6. Tekan **STOP RECORDING** setelah selesai.
7. Tekan **EXPORT LAST SESSION (.ZIP)** untuk menyimpan data ke folder yang Anda pilih.
8. Simpan metadata eksperimen eksternal seperti kendaraan, tekanan ban, posisi holder, cuaca, rute, dan jumlah penumpang.

## Catatan sampling

`10,000 us` diminta ke `SensorManager`, yang setara target sekitar 100 Hz. Android memperlakukan sampling period sebagai permintaan; frekuensi aktual dapat berbeda tergantung hardware/OS. Karena itu aplikasi menyimpan timestamp setiap event dan tidak mengasumsikan interval selalu 10 ms.

## Analisis cepat

File `tools/inspect_session.py` dapat digunakan untuk menghitung estimasi sampling rate accelerometer dan ringkasan GPS:

```bash
python tools/inspect_session.py /path/to/session_folder
```

Memerlukan Python 3 dan pandas.
