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
`KEYSTORE_BASE64` / `KEY_PASSWORD` (PKCS12, alias `key0`, OpenBao `kv/apps/route-suggest`).
The user's Mac has a Claude session with Android Studio and an Android 9 arm64 emulator
(`KX11_API28`, the modded Navigator installed): ask it to build, install and check there.

## Architecture

- Pure, tested: `Suggester` (the rows and their order — the decision logic lives here),
  `TripDetector` (GPS fixes → drives), `YandexData` (DataSync snapshots → places), `CookieJar`,
  `EventFilter` (which events are worth a route), `ICal` (VEVENTs, RRULE expansion), the XML of
  `CalDav` / `Ews`, `Ntlm` (NTLMv2 + MD4), `RawHttp`, `Geocode.shortAddress`.
- Calendars are read directly, no server of ours (the user's decision): CalDAV with Basic
  (iCloud, Yandex, Mail.ru, any) and EWS with NTLM/Basic (on-premise Exchange). `RawHttp` is a
  socket HTTP/1.1 client because HttpURLConnection on Android refuses PROPFIND/REPORT and NTLM
  needs one connection. Passwords: `CalStore`, AES-GCM with an Android Keystore key. Google and
  Microsoft 365 need OAuth — not supported.
- `RouteService` (foreground) records drives and puts the question once per start (boot, or the
  first fix / screen-on after 10+ minutes off) — only when `Suggester.worthAsking` (an event or
  a routine; the user's requirement), and the card can be switched off. `ReplyReceiver` gets
  the wheel's answer.
- `RouteService` also rechecks every 10 minutes while the unit is on: an event or a routine not
  offered yet today (`Suggester.explicitKeys`) brings the card (the user's requirement).
- UI (`Ui`, `MainActivity`, `CalendarActivity`): the cluster's look (MSpeed: night blue, cyan
  accent), views in code; settings are toggles; Android's permissions are asked in dialogs on
  opening, once each (prefs `asked`), and a state line that needs something is tappable — no
  static grant buttons (the user's requirement).
- `Updater`: the latest GitHub Release of this (public) repo, installed by the button only (the
  user's choice): `pm install -r` as root through the unit's adb, else PackageInstaller with
  Android's confirmation. Same signing key.
- `ClusterMenu`: every place (`Suggester.menu`) on the cluster menu's navigation tab. ECarX's
  `DimMenuInteraction` by reflection (`onTabChanged`, tab 2), the left joystick as
  `ECARX_KEY_DIM*` broadcasts, our overlay on display 2 at 740/720, 414×284 — all learned from
  mNavi's APK (closed, no licence: only the API calls, no code). Only the car can test it: the
  emulator has no ECarX (the app's «Показать меню» shows it on the main display there).
- Data sources: own GPS for the drives (the Navigator exposes no destination — see README),
  `NaviListener` only for «is it guiding now», Yandex DataSync via the app's own Yandex login
  (magic link, like calendar-bot's `calbot/yandex.py`), the calendars (above).
- Routes (`Navigator`, all checked on the emulator, mod 30.3.0): coordinates →
  `build_route_on_map` (overview, AutoGo presses «Поехали»); a name/address only, or our own
  Nominatim point (the middle of the house) → Alice `ask_alice?text=Маршрут до … едем` (leads at
  once; needs network and ru locale). Never `map_search` for a route (a card, needs a finger),
  never `rtext` with text (ignored), never coordinates to Alice (she swaps lat/lon). The mNavi
  READY_TO_GO/LETS_GO handshake does not exist in mod 30.3.0.
- A place's point (`Suggester.point`): a Yandex place nearby, else the Navigator's own
  destination for drives there (`Trip.dest`), else the middle of the stops.
- A force-stop of the app drops AutoGoService from the enabled services (Android does it);
  `RouteService` turns it on again as root at start.
- Car Notification Service's contract is copied in `NotifyClient` (its docs/API.md).
- Logcat never gets coordinates, addresses or event titles.
