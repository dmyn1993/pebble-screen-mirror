# Pebble 屏幕镜像 Android 应用

纯 Java Android 应用。核心入口：

- `MainActivity`：录屏授权、状态、设置、Shizuku / 无障碍入口。
- `MirrorService`：前台服务，持有 MediaProjection、ScreenCapture 和
  LegacyPebbleLink。
- `MirrorController`：会话状态、帧编码/发送调度、触摸映射。
- `LegacyPebbleLink`：与官方 Pebble 应用的 PebbleKit Classic 广播桥。

## 构建

```powershell
cd android-app
.\gradlew.bat :app:assembleDebug
```

或使用仓库根目录的 `scripts/build-android.ps1`。

环境：JDK 17、Android SDK Platform 36、Gradle 8.11.1。

## 安装

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 包名

`applicationId` 是 `com.pebblemirror.companion`。官方 Pebble 应用通过
PebbleKit Classic 广播通信，不依赖 watchapp 的 `companionApp` 声明；因此改包名
不需要改 watchapp。但 `MirrorController.WATCHAPP_UUID` 必须与
`watchapp/package.json` 的 `uuid` 保持一致。

## 官方 Pebble 应用

- 包名：`coredevices.coreapp`（`LegacyPebbleLink.PEBBLE_APP_PACKAGE`）。
- 需要开启 **Legacy PebbleKit**（默认开启）。
- 如果官方应用开启了实验性插件，可能需要在应用详情页允许 Screen Mirror 的
  `LegacyPebbleKit` 权限。

## 权限

- MediaProjection（每次开始镜像时系统授权）
- 前台服务 / 通知（Android 13+）
- 可选：无障碍服务、Shizuku、Root

无障碍服务只执行手势和全局操作，不读取窗口内容。
