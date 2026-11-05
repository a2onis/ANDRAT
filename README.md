# Andrat Control

A small, consent-based Android remote-control demo with a static web panel. The panel is designed to be published by **GitHub Pages**; a Firebase Realtime Database is used as the live relay because GitHub Pages itself cannot accept live device connections.

> This project intentionally does **not** implement a hidden Android RAT. The app has a visible UI and a user-started foreground service, and it exposes only the explicitly documented demo actions: ping, bounded vibration, rear flashlight, a visible notification, and status. It has no screen capture, accessibility control, contacts, SMS, file browser, stealth persistence, or background boot receiver.

## What is included

- `app/` — native Java Android app with no third-party runtime SDKs.
- `panel/` — responsive, dependency-free GitHub Pages control panel.
- `firebase.rules.json` — starter Realtime Database rules for the capability-token demo.
- `.github/workflows/deploy-panel.yml` — publishes `panel/` to GitHub Pages.
- `.github/workflows/build-android.yml` — builds and uploads a debug APK as a GitHub Actions artifact.

## Architecture

```text
Android app                  Firebase Realtime Database              GitHub Pages
────────────                 ─────────────────────────               ────────────
visible foreground service ── polls inbox/{token} every 3 seconds ──┐
status + acknowledgements ──────────────────────────────────────────┤
                                                                   └─ static panel
                                                                      writes commands
```

The control token is a randomly generated, 32-character capability. It is part of the database path rather than being embedded in the web site. The panel stores the endpoint, device ID, and token in that browser's local storage.

## 1. Create the relay database

1. Create a Firebase project and enable **Realtime Database**.
2. Copy the database URL. It normally looks like `https://your-project-default-rtdb.firebaseio.com` or `https://your-project-default-rtdb.europe-west1.firebasedatabase.app`.
3. In the Realtime Database **Rules** tab, paste the contents of [`firebase.rules.json`](firebase.rules.json) and publish them.
4. If you deploy a production service, replace the starter unauthenticated capability rules with Firebase Authentication and narrower per-user rules. The starter rules are intended for a private demo with high-entropy device tokens; anyone who obtains a token can read or write that device's command path.

There is no Firebase API key in this repository and no service-account credential is needed for the REST transport.

## 2. Publish the panel on GitHub Pages

1. Push or merge the repository to its `main` branch.
2. In the repository, open **Settings → Pages** and set the source to **GitHub Actions**.
3. The `Deploy control panel` workflow publishes the contents of `panel/` at the resulting Pages URL.
4. Either edit [`panel/config.js`](panel/config.js) before publishing to pre-fill the public database URL, or enter the URL in the panel's pairing form. The form stores it only in the current browser.

For local panel work, serve the directory over HTTP rather than opening the HTML as a `file://` URL:

```bash
python3 -m http.server 8080 --directory panel
```

Then open `http://localhost:8080`.

## 3. Build and install the Android app

The GitHub Actions **Build Android APK** workflow builds `app-debug.apk` and uploads it as an artifact. Download that artifact from the workflow run and install it on a test device or emulator.

To build locally, use Android Studio or a machine with:

- JDK 17
- Android SDK platform 35 and build tools
- Gradle 8.7 or a compatible Gradle installation

```bash
gradle assembleDebug
```

The project uses only Android framework APIs, so no `google-services.json` or Firebase Android SDK setup is required.

## 4. Pair and control a device

1. Open **Andrat Control** on the Android device.
2. Paste the same database URL into **Connection endpoint** and tap **Save endpoint**.
3. Copy the displayed **Device ID** and **Control token** into the GitHub Pages panel.
4. Grant camera permission if you want to use the flashlight, and notification permission if you want to show notices.
5. Tap **Start listening** in the app. Android displays an ongoing foreground-service notification while the connection is active.
6. In the panel, tap **Connect to device**. Once the heartbeat arrives, the action cards become available.

The panel can queue commands while the app is temporarily offline. The app polls them when its visible connection service is running, executes the supported action, posts an acknowledgement, and removes the command from its inbox.

## Supported command protocol

Commands are written below the paired device's capability path:

```text
/devices/{deviceId}/inbox/{controlToken}/{commandId}
```

Supported types are:

| Type | Payload | Result |
| --- | --- | --- |
| `ping` | `{}` | Acknowledgement only |
| `vibrate` | `{ "durationMs": 350 }` | Bounded to 50–2000 ms |
| `flashlight` | `{ "enabled": true }` | Uses the rear torch after camera permission |
| `notify` | `{ "message": "..." }` | Visible Android notification, max 200 characters |
| `status` | `{}` | Immediate status heartbeat |

The app posts acknowledgements under `/devices/{deviceId}/acks/{controlToken}/{commandId}` and publishes a small status document under `/devices/{deviceId}/status`.

## Operational notes

- This first version uses 3-second polling instead of a persistent socket. It is easy to host with static GitHub Pages plus Firebase, but it is not intended for low-latency or fleet-scale operation.
- Android may restrict network work when the user stops the foreground service, force-stops the app, or applies aggressive battery optimization. The UI makes the service state explicit; there is no boot receiver or stealth restart.
- The control token is stored in Android private preferences and in the panel browser's local storage. Treat it like a password and rotate it by clearing app data and pairing again.
- Firebase's Realtime Database REST API must be reachable from the device and browser. A `Permission denied` response usually means the database rules were not published or the database is not in the expected region URL.

## License

No license has been selected yet. Add one before distributing the project beyond your own test devices.
