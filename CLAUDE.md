# CLAUDE.md

## What this is

Android app for the Geely Monjaro head unit (KX11, Android 9, arm64): when the car starts it
offers «Куда едем?» on the cluster through Car Notification Service (a `list` card, the wheel
answers) and starts the chosen route in Yandex Navigator. Pure Java, `minSdk 28`,
`targetSdkVersion 30` (as car-notification-service: the boot receiver starts a foreground
service), Java 21, GPL-3.0.

## Commands

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

No Android SDK or JDK on the Pi that runs the agents: CI (`.github/workflows/ci.yml`) is the build.
Tag `v*` → `release.yml` → GitHub Release with `route-suggest-<tag>.apk`. Repo secrets:
`KEYSTORE_BASE64` / `KEY_PASSWORD` (PKCS12, alias `key0`, OpenBao `kv/apps/route-suggest`),
`CAR_API_URL` / `CAR_API_TOKEN` (calendar-bot's car endpoint, baked into BuildConfig).

## Architecture

- Pure, tested: `Suggester` (the rows and their order — the decision logic lives here),
  `TripDetector` (GPS fixes → drives), `YandexData` (DataSync snapshots → places), `CookieJar`,
  `CalEvent.parse`, `Geocode.shortAddress`.
- `RouteService` (foreground) records drives and puts the question once per start (boot, or the
  first fix / screen-on after 10+ minutes off). `ReplyReceiver` gets the wheel's answer.
- Data sources: own GPS for the drives (the Navigator exposes no destination — see README),
  `NaviListener` only for «is it guiding now», Yandex DataSync via the app's own Yandex login
  (magic link, like calendar-bot's `calbot/yandex.py`), calendar-bot `GET /v1/car/events`.
- Car Notification Service's contract is copied in `NotifyClient` (its docs/API.md).
- Logcat never gets coordinates, addresses or event titles.
