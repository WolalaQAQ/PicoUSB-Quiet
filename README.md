# PicoUSB-Quiet

[中文说明](README_zh.md)

A Magisk/Zygisk module for **PICO OS (Android 10)** that stops USB helper apps from interrupting a VR app
when a USB device re-attaches (for example when you plug or unplug the charging cable of a dock).

## The problem

The PICO 4 has one Type-C port. With a USB dock, plugging or unplugging the charger makes every USB device
re-enumerate. Apps that register an activity for `USB_DEVICE_ATTACHED` (Cymple Link, the Paper tracker, ...)
are then started by the system, and PICO reacts in two annoying ways:

1. **"Exit current app" prompt.** SystemExt treats the helper activity as an app switch and asks you to leave the
   VR app you are in.
2. **A brief panel flash.** Even once (1) is solved, PICO shows its own `UsbConfirmActivity` as a 2D panel for
   about 0.2 s. It auto-confirms, but never ticks "always use", so it flashes on every attach.

## What this module does

The module runs a small helper inside `system_server`. It uses no method hooks and changes no APK.

| Rule kind | Effect | Where the change lives |
|---|---|---|
| **Activity rule** | Marks an activity as a *dialog-class* window (the manifest key `pico.vr.position`). SystemExt quick-allows those, so no "exit app" prompt. | In memory only. Removing the module and rebooting restores stock behaviour. |
| **USB default rule** | The first time a matching device is seen, stores the app as that device's default handler (the system's own "always use" setting). PICO then starts the app directly and skips `UsbConfirmActivity`. | The system's normal USB settings file (`/data/system/users/0/usb_device_manager.xml`). |

Both rules are read from `rules.txt`, so other apps can be added without rebuilding.

## Supported apps

| App | Rules | Status |
|---|---|---|
| **Cymple Link** (`com.example.cymple_link`) | activity + USB default | **Verified** on PICO 4 Pro, PICO OS 5.13.7, Cymple Link 1.9.4 and 1.9.5 |
| **Paper eye-tracking dock** (`com.bridge.papertracker`) | activity only | **Not tested.** The maintainers have no Paper hardware. The names come from [hhhbwc/pico-paper-noplug-dialog](https://github.com/hhhbwc/pico-paper-noplug-dialog). The activity is allowed to start without the prompt, not blocked. No USB default rule is shipped because we do not know Paper's USB VID:PID. Please report results in an issue. |

The Paper rule is enabled by default in `rules.txt` and is ignored when Paper is not installed. Delete its line to
disable it.

## Requirements

- PICO headset on **Android 10** (tested on PICO 4 Pro, PICO OS 5.13.7). The helper refuses to run on other
  Android versions.
- Root with **Magisk 27+** and **Zygisk enabled**.

## Install

1. Download `PicoUSB-Quiet-v<version>.zip` from [Releases](../../releases) and check it against `SHA256SUMS.txt`.
2. Magisk app -> Modules -> Install from storage -> pick the ZIP -> reboot.
3. Optional: edit `/data/adb/modules/pico_usb_quiet/rules.txt` (see below) and reboot.

After the reboot, `adb logcat -s PicoUSB-Quiet` should show lines like:

```
PicoUSB-Quiet: started with 2 target(s), 1 usb rule(s)
PicoUSB-Quiet: com.example.cymple_link/... position far -> system_dialog ok
```

The first time a matching USB device is attached you may still see one flash. The module learns the device then
and logs `default handler for usb ...`. Later attaches should be quiet. The default is stored per exact device
(including its serial number), so a different board shows one flash on first use.

## `rules.txt`

```
# Activity rule: <package> <activity class or .RelativeName> <position>
#   position = system_dialog | global_dialog | near_dialog
com.example.cymple_link com.example.cymple_link.UsbDeviceAttachedActivity system_dialog

# USB default rule: usb-default <package> <vid:pid> [<vid:pid> ...]   (decimal or 0x hex)
usb-default com.example.cymple_link 1027:24577 12346:4097
```

To find an app's activity, run `adb shell dumpsys package <package>` and look at the activity filtered for
`android.hardware.usb.action.USB_DEVICE_ATTACHED`. To find a device's VID:PID, read the app's `device_filter.xml`
(values there are decimal) or, as root while the device is attached, `cat /sys/bus/usb/devices/*/idVendor`
and `.../idProduct` (hex, so prefix `0x`). Edits take effect after a reboot. When you upgrade, your edited
`rules.txt` is kept and the shipped one is saved next to it as `rules.txt.new`.

## Uninstall

Remove the module in Magisk and reboot. Activity changes disappear. USB default handlers already stored stay in
`usb_device_manager.xml` and are harmless. Delete the `<preference>` entries from that file, or clear the app's
defaults in settings, if you want them gone.

## How it works

- `src/native/module.cpp`: a Zygisk module. In `system_server` it waits for `sys.boot_completed`, loads the
  embedded DEX with `InMemoryDexClassLoader` and calls `QuietServer.start`. Every other process unloads it.
- `src/java/org/picousbquiet/QuietServer.java`: once per second it (a) sets `pico.vr.position` on the matching
  activities in `PackageManagerService`'s package data, and (b) lists attached USB devices and calls the system's
  `UsbService.setDevicePackage` for new matching ones. Both use reflection on classes verified against PICO OS
  5.13.7. The helper stops if the members are missing.
- PICO's SystemExt quick-allows activities whose window class is `system_dialog`, `global_dialog` or
  `near_dialog`. That is how rule kind 1 avoids the prompt.

## Limitations

- Android 10 PICO firmware only. Only PICO OS 5.13.7 was inspected. Other builds may differ.
- Activity changes are applied about one second after boot and after a package is installed or updated.
- This is a workaround for PICO's behaviour, not an official feature. A firmware update may change it.

## Build

Needs PowerShell 7, JDK 17 and an Android SDK with `ndk;26.1.10909125`, `cmake;3.22.1`, `platforms;android-35`
and `build-tools;35.0.0`.

```powershell
$env:ANDROID_SDK_ROOT = 'C:\path\to\sdk'; $env:JAVA_HOME = 'C:\path\to\jdk17'
pwsh -File tools/build.ps1                                   # -> dist/PicoUSB-Quiet-v<version>.zip
pwsh -File tests/check-package.ps1 -ZipPath dist/PicoUSB-Quiet-v<version>.zip
```

## Release

Bump `version` and `versionCode` in `module/module.prop`, commit, then push a tag `v<version>`. GitHub Actions
checks that the tag equals the version, builds, verifies the ZIP and publishes a Release with the ZIP and
`SHA256SUMS.txt`. A tag with a suffix such as `v1.1.0-rc.1` becomes a pre-release.

## License

[MIT](LICENSE). `src/native/zygisk.hpp` is vendored under its own license, see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
