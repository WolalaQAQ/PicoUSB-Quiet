# PicoUSB-Quiet

[English](README.md)

面向 **PICO OS（Android 10）** 的 Magisk/Zygisk 模块。USB 设备重新插拔时（例如拔插扩展坞的充电线），不再让 USB 辅助应用打断正在运行的 VR 应用。

## 问题

PICO 4 只有一个 Type-C 口。接扩展坞后，拔插充电线会让所有 USB 设备重新枚举。凡是给 `USB_DEVICE_ATTACHED` 注册了 Activity 的应用（Cymple Link、Paper 眼追等）都会被系统拉起，PICO 会有两种打扰：

1. **「退出当前应用」确认框。** SystemExt 把辅助 Activity 当成应用切换，要求你退出正在玩的 VR 应用。
2. **面板闪一下。** 即使解决了第 1 点，PICO 自己的 `UsbConfirmActivity` 仍会作为 2D 面板出现约 0.2 秒。它会自动确认，但不会勾选"总是使用"，所以每次插入都闪。

## 模块做了什么

模块在 `system_server` 里运行一个小助手：不用方法 hook，不改任何 APK。

| 规则类型 | 作用 | 改动位置 |
|---|---|---|
| **Activity 规则** | 把某个 Activity 标成*对话框类*窗口（manifest 键 `pico.vr.position`）。SystemExt 会直接放行这类窗口，不再弹"退出应用"。 | 只改内存。卸载模块并重启即恢复原状。 |
| **USB 默认规则** | 第一次看到匹配设备时，把应用保存为该设备的默认处理应用（系统自己的"总是使用"设置）。之后 PICO 直接启动该应用，跳过 `UsbConfirmActivity`。 | 系统正常的 USB 设置文件（`/data/system/users/0/usb_device_manager.xml`）。 |

两种规则都从 `rules.txt` 读取，添加其他应用无需重新编译。

## 支持的应用

| 应用 | 规则 | 状态 |
|---|---|---|
| **Cymple Link**（`com.example.cymple_link`） | Activity + USB 默认 | **已验证**：PICO 4 Pro、PICO OS 5.13.7、Cymple Link 1.9.4 和 1.9.5 |
| **Paper 眼追扩展坞**（`com.bridge.papertracker`） | 仅 Activity | **未测试**：维护者没有 Paper 设备。包名和类名来自 [hhhbwc/pico-paper-noplug-dialog](https://github.com/hhhbwc/pico-paper-noplug-dialog)。这里是让 Activity 免弹窗启动，而不是把它拦下。因为不知道 Paper 的 USB VID:PID，所以没有提供 USB 默认规则。欢迎在 issue 里反馈结果。 |

Paper 规则在 `rules.txt` 里默认启用，没装 Paper 时会被忽略。想禁用就删掉那一行。

## 要求

- **Android 10** 的 PICO 头显（在 PICO 4 Pro、PICO OS 5.13.7 上测试）。其他 Android 版本上助手会拒绝运行。
- 已 root，**Magisk 27+** 且**已启用 Zygisk**。

## 安装

1. 从 [Releases](../../releases) 下载 `PicoUSB-Quiet-v<版本>.zip`，用 `SHA256SUMS.txt` 校验。
2. Magisk 应用 -> 模块 -> 从本地安装 -> 选择 ZIP -> 重启。
3. 可选：编辑 `/data/adb/modules/pico_usb_quiet/rules.txt`（见下）后重启。

重启后 `adb logcat -s PicoUSB-Quiet` 应该有类似输出：

```
PicoUSB-Quiet: started with 2 target(s), 1 usb rule(s)
PicoUSB-Quiet: com.example.cymple_link/... position far -> system_dialog ok
```

第一次接上匹配的 USB 设备时，可能还会闪一次，模块此时才学到这个设备并记录 `default handler for usb ...`，之后就安静了。默认应用按"具体设备"保存（包含序列号），换一块板子第一次会再闪一次。

## `rules.txt`

```
# Activity 规则：<包名> <Activity 类名或 .相对名> <position>
#   position = system_dialog | global_dialog | near_dialog
com.example.cymple_link com.example.cymple_link.UsbDeviceAttachedActivity system_dialog

# USB 默认规则：usb-default <包名> <vid:pid> [<vid:pid> ...]   （十进制或 0x 十六进制）
usb-default com.example.cymple_link 1027:24577 12346:4097
```

查应用的 Activity：运行 `adb shell dumpsys package <包名>`，找带 `android.hardware.usb.action.USB_DEVICE_ATTACHED` 过滤器的 Activity。查设备的 VID:PID：看应用的 `device_filter.xml`（其中是十进制），或设备接入时用 root 读 `cat /sys/bus/usb/devices/*/idVendor` 和 `.../idProduct`（十六进制，要加 `0x` 前缀）。改完需要重启。升级时会保留你改过的 `rules.txt`，新版自带的另存为 `rules.txt.new`。

## 卸载

在 Magisk 里移除模块并重启，Activity 的改动随之消失。已经保存的 USB 默认应用仍留在 `usb_device_manager.xml` 里，无害；想清掉就删除该文件里对应的 `<preference>` 项，或在设置里清除应用的默认设置。

## 原理

- `src/native/module.cpp`：Zygisk 模块。在 `system_server` 里等 `sys.boot_completed`，用 `InMemoryDexClassLoader` 加载内嵌 DEX 并调用 `QuietServer.start`；其他进程立即卸载它。
- `src/java/org/picousbquiet/QuietServer.java`：每秒一次，(a) 在 `PackageManagerService` 的包数据里给匹配的 Activity 设置 `pico.vr.position`；(b) 列出已接入的 USB 设备，对新出现的匹配设备调用系统的 `UsbService.setDevicePackage`。两者都用反射访问已对照 PICO OS 5.13.7 核实过的类，缺少成员时助手会停止。
- PICO 的 SystemExt 会放行窗口类为 `system_dialog`、`global_dialog`、`near_dialog` 的 Activity，规则类型 1 靠这一点避免弹框。

## 限制

- 只适用于 Android 10 的 PICO 固件，只检查过 PICO OS 5.13.7，其他版本可能不同。
- Activity 的改动在开机后以及应用安装或更新后约一秒内生效。
- 这是针对 PICO 行为的变通方案，不是官方功能，固件更新可能改变行为。

## 构建

需要 PowerShell 7、JDK 17，以及装有 `ndk;26.1.10909125`、`cmake;3.22.1`、`platforms;android-35`、`build-tools;35.0.0` 的 Android SDK。

```powershell
$env:ANDROID_SDK_ROOT = 'C:\path\to\sdk'; $env:JAVA_HOME = 'C:\path\to\jdk17'
pwsh -File tools/build.ps1                                   # -> dist/PicoUSB-Quiet-v<版本>.zip
pwsh -File tests/check-package.ps1 -ZipPath dist/PicoUSB-Quiet-v<版本>.zip
```

## 发布

修改 `module/module.prop` 里的 `version` 和 `versionCode`，提交后推送标签 `v<版本>`。GitHub Actions 会检查标签与版本一致、构建、校验 ZIP，并发布带 ZIP 和 `SHA256SUMS.txt` 的 Release。带后缀的标签（如 `v1.1.0-rc.1`）会作为预发布。

## 许可

[MIT](LICENSE)。`src/native/zygisk.hpp` 按其自身许可证引入，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
