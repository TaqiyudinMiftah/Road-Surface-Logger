# Road Surface Logger (Android) — v0.2

Aplikasi Android native untuk **akuisisi data penelitian kondisi jalan** menggunakan sensor bawaan smartphone. Aplikasi **tidak melakukan deteksi pothole otomatis**; fokusnya adalah merekam data mentah IMU + GNSS, metadata eksperimen, marker manual, dan laporan kualitas sesi untuk analisis offline.

## Fitur v0.2

- Raw accelerometer, gyroscope, linear acceleration, dan gravity (jika tersedia)
- GPS/GNSS latitude, longitude, altitude, speed, bearing, dan accuracy
- Form **Experiment Metadata** sebelum recording
- Sampling target yang dapat dipilih: **50 Hz / 100 Hz / 200 Hz / FASTEST**
- Permission `HIGH_SAMPLING_RATE_SENSORS` disertakan agar mode FASTEST tidak dibatasi oleh cap sensor high-rate ketika perangkat mendukungnya
- Live actual average sampling rate accelerometer & gyroscope
- Live GNSS status, accuracy, speed, sample count, durasi, dan ukuran sesi
- Marker manual: `pothole`, `rough_road`, `speed_bump`, `other`
- Foreground service + partial wake lock untuk menjaga proses logging tetap aktif saat layar mati
- **Recorded Sessions** untuk melihat sesi lama dan quality report
- Export sesi terpilih menjadi ZIP melalui Android Storage Access Framework
- `session_info.json` untuk metadata eksperimen
- `quality_report.json` untuk pemeriksaan kualitas data setelah STOP
- Metadata perangkat dan karakteristik sensor otomatis
- Tidak membutuhkan server/cloud atau permission penyimpanan umum

## Struktur output

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

- accelerometer / linear acceleration / gravity: `m/s²`
- gyroscope: `rad/s`
- `sensor_timestamp_ns` memakai basis waktu monotonic Android.

### `gps.csv`

```text
session_id,elapsed_realtime_ns,wall_time_ms,provider,latitude,longitude,altitude_m,speed_mps,bearing_deg,accuracy_m,vertical_accuracy_m,speed_accuracy_mps,bearing_accuracy_deg
```

Untuk sinkronisasi IMU ↔ GPS gunakan `sensor_timestamp_ns` dan `elapsed_realtime_ns`.

### `markers.csv`

```text
session_id,elapsed_realtime_ns,wall_time_ms,label,latitude,longitude
```

Marker adalah **ground truth manual**, bukan hasil deteksi otomatis. Jangan menekan marker ketika Anda sendiri sedang mengemudi.

### `session_info.json`

Menyimpan metadata penelitian seperti experiment ID, kendaraan, posisi mounting HP, orientasi HP, rute, cuaca/kondisi jalan, tekanan ban, jumlah penumpang, target kecepatan, catatan, pilihan sampling, model HP, dan versi Android.

### `quality_report.json`

Dibuat saat sesi dihentikan dan berisi duration, jumlah sample tiap sensor, actual average accelerometer/gyroscope Hz, jumlah GPS fixes, average GPS accuracy, largest GPS gap, ukuran file sesi, dan flag `interrupted`.

Laporan ini hanya untuk **data-quality checking**, bukan klasifikasi kerusakan jalan.

## Menjalankan di HP

1. Clone repository dan buka dengan Android Studio.
2. Aktifkan Developer Options + USB debugging di HP.
3. Pastikan `adb devices -l` menampilkan HP dengan status `device`.
4. Pilih HP sebagai deployment target.
5. Run `app`.
6. Berikan **Precise location** saat diminta.
7. Tekan **START NEW EXPERIMENT**, isi metadata, pilih sampling target, lalu mulai perjalanan.

## Prosedur eksperimen yang disarankan

1. Pasang HP pada holder yang rigid.
2. Gunakan posisi dan orientasi HP yang konsisten.
3. Catat konfigurasi kendaraan dan kondisi eksperimen melalui form metadata.
4. Tunggu GNSS mendapatkan fix sebelum mulai rute pengujian.
5. Hindari memindahkan HP selama recording.
6. Marker sebaiknya dioperasikan oleh penumpang/operator.
7. Tekan **STOP RECORDING** setelah selesai agar `quality_report.json` dibuat.
8. Periksa **Recorded Sessions** sebelum export.

## Catatan sampling

Sampling period yang dipilih dikirim sebagai permintaan ke `SensorManager`. Android tidak menjamin frekuensi aktual persis sama dengan target. Karena itu aplikasi menyimpan timestamp asli setiap event, menghitung actual average Hz dari timestamp sensor, dan tidak melakukan resampling atau interpolasi di aplikasi.

Untuk penelitian, preprocessing sebaiknya tetap dilakukan offline agar raw data tidak hilang.

## Build configuration

- Android Gradle Plugin 8.13.2
- Kotlin 2.2.21
- Gradle 8.13
- compileSdk / targetSdk 36
- minSdk 26
- JDK 17

## Analisis cepat

```bash
python tools/inspect_session.py /path/to/session_folder
```

Memerlukan Python 3 dan pandas. Script akan membaca `session_info.json` dan `quality_report.json` jika tersedia, tetapi tetap kompatibel dengan sesi v0.1.
