# INAV Configurator for Android

An experimental Android build of [INAV Configurator](https://github.com/iNavFlight/inav-configurator), the configuration tool for the [INAV](https://github.com/iNavFlight/inav) flight control system. It is aimed at tablets: plug the flight controller into the tablet with a USB OTG cable and configure it in the field, without a laptop.

> For the desktop app (Windows, Linux, macOS), installation instructions, map providers, fonts and everything else, see the **[original INAV Configurator README](https://github.com/iNavFlight/inav-configurator/blob/master/README.md)**.

This is the same configurator: the unmodified desktop UI runs inside the Android WebView, and small native plugins provide what a browser cannot do (USB serial, TCP/UDP sockets, file access). Desktop builds are unaffected.

## Requirements

- Android 11 or newer
- A tablet in landscape. The UI is the desktop layout, so it needs about 1280×800 dp; phones are not a target.
- USB host (OTG) support and a USB OTG cable for wired connections
- An up-to-date Android System WebView is recommended. Stock Android 11 WebView (Chrome 83) is supported.

## Installing

1. Download the APK:
   - from the **Build Android** workflow run of a pull request (Actions → run → Artifacts), or
   - build it yourself (see [Building](#building)).
2. Allow installing apps from unknown sources and open the APK.
3. Plug in the flight controller. Android offers to open INAV Configurator for it; accepting also grants USB access. Otherwise the app asks for permission when you press **Connect**.

CI and local builds without a release key are signed with a debug key: fine for testing, not for distribution. Android won't install an update signed with a different key, so uninstall the test build before switching to a properly signed one.

## Connecting

| Connection | How |
|---|---|
| USB | The port appears as a USB device path, e.g. `/dev/bus/usb/001/002`. Select it and press **Connect**. |
| TCP | Select **TCP** and enter `IP:port`. For example SITL on a PC (`192.168.1.10:5760`) or a WiFi/ESP serial bridge. |
| UDP | Select **UDP** and enter `IP:port`. |

BLE, SITL and Demo mode are hidden in the port list on Android because they are not supported.

## What works

**Tested on real hardware.** Galaxy Tab 4 10.1 (LineageOS, Android 11, Adreno 305 GPU) with a HOBBYWING XRotor F7 on INAV 9.1.0:

- The app starts in landscape with the desktop layout. Launcher icon and splash use the INAV logo.
- USB serial connection to the FC (STM32 virtual COM port): the USB permission prompt, plus live attitude, sensors, dataflash info and pre-arming checks on the Status tab
- The 3D craft model on the Status tab, lit and in colour
- TCP over WiFi to INAV SITL running on a PC
- The CLI tab, including command output
- The optimized release build (R8)

**Tested on the Android 11 emulator** (stock WebView 83):

- App startup and TCP connection to SITL
- The Status, Configuration and OSD tabs render correctly at tablet size
- Writing, appending, reading, listing and deleting files in the app's backup folder

## Expected to work, not tested yet

These use the same code paths as the tested features, but nobody has tried them on Android yet:

- **The other configuration tabs** (Ports, Receiver, Modes, Mixer, Outputs, PID tuning, Programming, …). They only talk MSP to the FC, which works.
- **UDP connections**
- **Saving and loading files through the Android file picker:** CLI save/load, backup and restore, mission files, OSD font files, and data/blackbox logs
- **Downloading onboard (blackbox) logs** from dataflash
- **USB-serial adapters:** FTDI, CP210x, CH34x and PL2303 are supported by the driver library but untested.
- **Flashing over the serial bootloader** (not DFU, see below)
- **The Magnetometer tab's 3D view**
- **Mission Control**

## What does not work

- **USB DFU flashing.** Most F4/F7/H7 flight controllers are flashed in DFU mode, so firmware flashing generally won't work yet. Use the desktop configurator to flash.
- **Bluetooth (BLE) connections**
- **Local SITL and Demo mode.** Android can't run the SITL binary. Connecting over TCP to SITL running on another machine works.
- **The update notification.** It's disabled, because it points to desktop downloads.
- **"Open backup folder".** It only shows the folder path. Automatic backups are stored in `Android/data/com.inavflight.configurator/files/inav-backups`, which recent Android versions hide from most file managers. Use **backup to file** to save a copy somewhere visible.

## Known issues

- **Content loaded from GitHub inside the app can be blocked by CORS.** The settings documentation (`Settings.md`, used for help links) is blocked. The firmware list and firmware downloads in the flasher may be affected too (untested).
- **OpenStreetMap tiles may be refused.** The desktop app sends a custom Referer/User-Agent for them, which the Android build doesn't do yet (untested).
- **Latency is higher than on desktop on slow tablets.** HW round trip was about 45–115 ms on the Galaxy Tab 4, so screens refresh more slowly.
- **3D models are rendered with simpler (matte) materials on Android.** This works around Adreno 3xx driver bugs that made them render black.
- **Phone screens are cramped.** The app is locked to landscape and laid out for tablets.

Please report Android-specific problems with the device model, Android version and WebView version. The WebView version is shown in the log line at the top of the app.

## Building

Requirements: Node.js 22+, Yarn, JDK 21, Android SDK (platform 36).

```bash
yarn install
yarn android:apk           # debug APK
yarn android:apk-release   # optimized release APK (R8 + resource shrinking)
yarn android:open          # open the project in Android Studio
```

APKs are written to `android/app/build/outputs/apk/{debug/app-debug,release/app-release}.apk`.

Release signing uses `INAV_ANDROID_KEYSTORE`, `INAV_ANDROID_KEYSTORE_PASSWORD`, `INAV_ANDROID_KEY_ALIAS` and `INAV_ANDROID_KEY_PASSWORD`. Without them, the release APK is signed with the local debug key.

CI (`.github/workflows/android.yml`) builds both APKs for every pull request. Manual runs sign with the release key when the `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` secrets are set.

## How it works

- The renderer reaches the host OS only through `js/platform/`. On desktop that is the Electron preload API; on Android it is `js/platform/capacitor.js`. Both expose the same functions.
- [Capacitor](https://capacitorjs.com/) packages the Vite web bundle (`vite.web.config.js`, output in `www/`) into the Android app.
- The native plugins are in `android/app/src/main/java/com/inavflight/configurator/`:
  - `InavSerial`: USB serial via [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
  - `InavTcp` and `InavUdp`: network connections
  - `InavFile`: file picker and app storage
- Parts of the native code follow the approach of the [Betaflight App](https://github.com/betaflight/betaflight-configurator) Android build (GPL-3.0).

## License

GPL-3.0.
