# KIVO Fork Notes

This is a fork of [`@capgo/background-geolocation`](https://github.com/Cap-go/capacitor-background-geolocation) (MPL-2.0) maintained for the KIVO driver app.

## Why a fork

The upstream plugin (and its parent `@capacitor-community/background-geolocation`) delivers GPS fixes to a JavaScript callback. The app then issues HTTP POSTs from JS (axios/fetch inside the WebView). On aggressive Android OEMs (Xiaomi/MIUI/HyperOS, Huawei EMUI, OPPO ColorOS), the WebView gets throttled when the app is backgrounded — even with a foreground service running. Result: GPS chip stays alive, but no pings reach the backend.

Capgo already has native HTTP POST machinery, but only for **geofence enter/exit events**. KIVO needs the same pattern for **every location update** during an active trip.

## Changes vs upstream

- **NEW Android class** `LocationUploadWorker` + `LocationUploadStore` — clones capgo's geofence HTTP pipeline (`GeofenceTransitionWorker` + `GeofenceStore`) and binds it to the location callback in `BackgroundGeolocationService`.
- **Android hook** at `BackgroundGeolocationService.locationCallback` — every fix triggers an HTTP POST enqueued via WorkManager (offline buffer + exponential backoff) in addition to the existing JS callback (still used for live UI updates).
- **Custom HTTP headers** support (JWT auth, etc.) via new `configureUpload` plugin method — capgo's geofence path hardcodes `Content-Type: application/json` only.
- **NEW iOS** SQLite-backed persistence queue + URLSession background config for location uploads — capgo's iOS `postGeofenceTransition` is fire-and-forget which is not acceptable for ride-hailing.
- **Throttle** at native level (min interval between POSTs) configurable per-app.

## Things explicitly NOT renamed (to keep upstream merges clean)

- Android Java package: stays `com.capgo.capacitor_background_geolocation.*`
- iOS framework name: stays `CapgoBackgroundGeolocation`
- Class names of upstream files: unchanged
- File layout of upstream files: unchanged

Only the JS-side `package.json` `name` changed to `kivo-background-geolocation`.

## Syncing with upstream

```bash
cd ~/Documents/kivo-background-geolocation
git fetch upstream
git merge upstream/main
# resolve conflicts (rare since we only added new files), commit, push
```

## License

MPL-2.0 — same as upstream. Modifications to upstream files remain MPL-2.0; new files added by KIVO are also MPL-2.0 unless otherwise noted.
