# RDMNS Android app

Full-screen WebView app for https://rdmns.hesn.xyz with an iOS-style tab bar:

| Tab | URL |
|---|---|
| Home | https://rdmns.hesn.xyz/ |
| Map | https://rdmns.hesn.xyz/v1map |
| Radar | https://rdmns.hesn.xyz/v1radar |
| Timetable | https://rdmns.hesn.xyz/v1timetable |

## Features
- Each tab keeps its own page and history (switching tabs doesn't reload).
- Tap the active tab again → back to that tab's start page, or reload if already there.
- Edge-to-edge, light/dark follows the phone, RDMNS colours, splash screen, adaptive icon.
- Tab labels in English / සිංහල / தமிழ் (follows phone language).
- Login session (cookies) kept between launches.
- GPS sharing: asks Android location permission when the page requests it.
- File uploads (`<input type=file>`), downloads via the system download manager.
- `tel:`, `mailto:`, WhatsApp, Google Maps and any non-RDMNS links open in their own apps.
- Styled offline page that reloads automatically when the connection returns.
- `https://rdmns.hesn.xyz/...` links open straight in the app (e.g. `/v1map/1536` opens the Map tab).
- Back: goes back in the page → then to Home tab → press twice to exit.
- User agent ends with `RDMNSApp/1.0`, so PHP can detect the app:
  `if (strpos($_SERVER['HTTP_USER_AGENT'] ?? '', 'RDMNSApp') !== false) { ... }`

## Build
**Android Studio:** File → Open → this folder → Run ▶ (or Build → Build APK(s)).

**GitHub (no Android Studio needed):** push this folder to a GitHub repo → Actions tab →
"Build APK" → download `RDMNS-debug-apk` from the run.

**Release (Play Store):** create a keystore, add `keystore.properties` in the root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), then `./gradlew bundleRelease`.

## Change things
- URLs / tabs: `tabs` list at the top of `MainActivity.kt`.
- Domains that stay inside the app: `INTERNAL_HOSTS` in `MainActivity.kt`.
- Colours: `res/values/colors.xml` (light) and `res/values-night/colors.xml` (dark).
- Package name: `applicationId` in `app/build.gradle.kts`.
