# Route Map Setup

Road Surface Logger v0.3 uses **Google Maps SDK for Android** to display the raw GNSS route stored in `gps.csv`.

## 1. Create / choose a Google Cloud project

Open Google Cloud Console, enable **Maps SDK for Android**, and create an API key. Google Maps Platform may require a billing-enabled Cloud project.

## 2. Restrict the API key

For production/research deployments, restrict the key to:

- Android apps
- package name: `com.example.roadsurfacelogger`
- the SHA-1 certificate fingerprint of the app that will use the key

Also restrict the API key to **Maps SDK for Android** where appropriate.

## 3. Store the key locally

Create this file in the repository root:

```text
secrets.properties
```

Add:

```properties
MAPS_API_KEY=YOUR_REAL_API_KEY
```

`secrets.properties` is listed in `.gitignore` and must not be committed.

The committed `local.defaults.properties` contains only:

```properties
MAPS_API_KEY=DEFAULT_API_KEY
```

This placeholder allows Gradle sync/build before a real key is configured, but the map itself will not authenticate until a valid key is supplied.

## 4. Sync and run

In Android Studio:

1. **Sync Project with Gradle Files**
2. Rebuild the app
3. Run on the physical Android device
4. Record a session until at least one GNSS fix is available
5. Tap **OPEN ROUTE MAP**

During recording the screen is refreshed from `gps.csv`. After recording, a route can also be opened from **RECORDED SESSIONS → View Route**.

## What the route means

The map draws consecutive raw GNSS fixes as a polyline. It does **not** currently perform map matching or snap-to-road processing. Therefore GPS uncertainty can make the line appear beside the road, especially near buildings, tunnels, trees, or locations with poor satellite visibility.

Manual event markers from `markers.csv` are also displayed on the map.
