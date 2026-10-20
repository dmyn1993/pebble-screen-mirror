# 构建产物说明

本仓库**不提交** APK/PBW 二进制；每次版本发布由 GitHub Actions 重新构建并
上传到 [Releases](https://github.com/dmyn1993/pebble-screen-mirror/releases)。

Release 资产：

- `PebbleMirror-vX.Y.Z-debug.apk`：Android 手机端，debug 签名，可直接侧载；
- `ScreenMirror-vX.Y.Z.pbw`：Pebble 手表端（emery / Pebble Time 2）；
- `SHA256SUMS.txt`：以上文件的 SHA-256。

本地构建输出在：

- `android-app/app/build/outputs/apk/debug/app-debug.apk`
- `watchapp/build/watchapp.pbw`

安装：

```powershell
adb install -r PebbleMirror-vX.Y.Z-debug.apk
```

```bash
pebble install --adb <设备序列号> ScreenMirror-vX.Y.Z.pbw
# 或
pebble install --phone <手机局域网 IP>
```

首次使用请确认官方 Pebble 应用（`coredevices.coreapp`）已安装、手表已配对，
并保持 **Legacy PebbleKit** 开启。详细说明见仓库根目录 `README.md`。
