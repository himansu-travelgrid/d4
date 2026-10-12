# Drive Distance
Android app (Kotlin) that tracks multi-stop driving trips (A → B → C → D → A) with per-leg distance,
live OpenStreetMap route and place names. No API keys needed.

## Build the APK (GitHub Actions)
Upload this whole folder's contents to the ROOT of a GitHub repo (keep `.github/` and `DD-repo-root/`).
Each push builds the app; the APK is published at:
`https://github.com/<user>/<repo>/raw/build-logs/app-debug.apk`

## Build locally
Open `DD-repo-root/` in Android Studio -> Build -> Build APK(s).
