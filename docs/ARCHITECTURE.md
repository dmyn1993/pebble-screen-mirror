# 架构说明

## 1. 组件

### watchapp（C，`watchapp/src/c/main.c`）

- 一个全屏 `Window` + 自绘 `Layer`。
- 两块 `GBitmapFormat1Bit`：
  - `s_staging`：接收中的帧；
  - `s_display`：正在显示的上一个完整帧。
- `app_message_open(app_message_inbox_size_maximum(), ...)`；emery 上 inbox 上限
  是 8K 级（实测 8232），足够一次传 7250 字节整帧 tile 数据。
- `touch_service_subscribe()` 获取原始触摸；只在 window appear/disappear 期间
  订阅，退出时退订。
- 每 3 秒的 `AppTimer` 做两件事：连接超时检测、每 6 秒发 `HELLO`。
- 按键：上/下短按平移视口，长按上/下跳到视口顶/底；Select 单击 Android 主页，
  双击最近任务，长按切换竖屏视口/整屏缩放；返回键发送 Android 返回，
  长按返回键由系统退出 watchapp。
- 手机停止镜像时发送 `STATUS(flags=0)`，手表清空最后一帧并回到启动页；
  连接超时也会回到启动页。
### Android 伴侣应用（`android-app`，纯 Java）

| 类 | 职责 |
|---|---|
| `MirrorApp` | 初始化 `MirrorController`、通知渠道、Root 探测 |
| `MainActivity` | 录屏授权、状态、设置、Shizuku/无障碍入口 |
| `MirrorService` | 前台服务；持有 MediaProjection、`ScreenCapture`、`LegacyPebbleLink` |
| `ScreenTimeoutManager` | 镜像时临时把系统息屏时间设为“永不”，停止/异常退出后恢复原值 |
| `TouchIndicatorManager` | 镜像时通过 Shizuku/Root 打开系统触摸小白点，停止后恢复原值 |
| `ScreenCapture` | VirtualDisplay + ImageReader；缩放；回调 ARGB 像素 |
| `FrameEncoder` | 1-bit 抖动、LSB-first 打包、8×8 tile、帧差分 |
| `ViewportMapper` | 视口/整屏几何、触摸坐标映射 |
| `MirrorController` | 会话状态、帧发送、触摸手势、心跳、UI 通知 |
| `LegacyPebbleLink` | PebbleKit Classic 广播、JSON、ACK/NACK、发送队列 |
| `WatchProtocol` | AppMessage key/kind、tile 分块、CONFIG/STATUS |
| `WatchValue` | 轻量 AppMessage 值模型 |
| `AccessibilityInjector` / `ShellInjector` / `InjectorFactory` | 三种注入方式 |
| `PathInjectorMain` | app_process helper：Shizuku/Root 单进程、按真实时长注入路径 |
| `MirrorAccessibilityService` | 可选的无障碍手势服务 |
| `ShizukuCompat` | Shizuku binder/权限/shell 封装 |
| `TouchGesture` | down/move/up → tap/long-press/swipe（手表已先判 tap/long 时作为回退） |

## 2. 数据流

```
MediaProjection
  → ImageReader (480×H)
  → Bitmap
  → createScaledBitmap (watchW × scaledH，竖屏视口下 200×444)
  → int[] ARGB
  → MirrorController.onScaledFrame
  → FrameEncoder.encode
       ├─ 灰度
       ├─ Atkinson / 阈值
       ├─ LSB-first 打包
       └─ 与上一帧比较，生成 changed tiles
  → WatchProtocol.frameMessages（8K 下整帧一条，小 inbox 才分块）
  → LegacyPebbleLink 队列
  → SEND 广播
  → 官方 Pebble 应用 PebbleKitClassic
  → AppMessage
  → 手表 staging bitmap
  → FINAL：staging → display → layer_mark_dirty
```

反向：

```
手表触摸 / 按键
  → AppMessage
  → 官方应用 PebbleKitClassic
  → RECEIVE 广播
  → LegacyPebbleLink
  → MirrorController.onWatchMessage
       ├─ HELLO → 配置/心跳
       ├─ TOUCH → TouchGesture → 坐标映射 → 注入
       ├─ PAN → 重编码当前缓存帧
       ├─ KEY → 注入返回/主页/最近任务
       └─ REQUEST → 强制关键帧
  → ACK 广播
  → 官方应用 → 手表
```

## 3. 为什么不用 PebbleKit2 / PKJS

### PebbleKit2

`mobileapp/libpebble3/.../PebbleKit2.kt` 里，官方应用在 watchapp 声明
`companionApp` 时会创建 `PebbleKit2`。它通过 `DefaultPebbleListenerConnector`
把 **手表 → 伴侣应用** 的消息送到伴侣应用的 `BasePebbleListenerService`，这部分
是完整的。

但反向的 **伴侣应用 → 手表** 需要伴侣应用绑定官方应用里一个 action 为
`io.rebble.pebblekit2.SEND_DATA_TO_WATCH` 的 `BasePebbleSenderReceiver` 服务。
在当前 `mobileapp` 仓库的 manifest 和代码里没有找到这个服务的具体实现；
`server` AAR 只提供抽象基类。因此 `DefaultPebbleSender.sendDataToPebble()` 很可能
返回 null，无法推画面。

### PKJS

PKJS 可以在手机端运行 JS，并通过 `Pebble.sendAppMessage()` 推消息，也能反向。
但它需要一个本地 HTTP/WebSocket 服务，且官方应用把 PKJS 放在 WebView 里；这增加
了一层不必要的桥接和权限/明文流量问题。更重要的是：一旦 watchapp 带 PKJS，官方
应用仍会同时创建 `PebbleKitClassic`（`appMessageToMultipleCompanions` 默认 true），
两条路径都可能收到手表消息，需要去重。

### PebbleKit Classic

`PebbleKitClassic` 在官方应用里同时实现两个方向：

- `launchIncomingAppMessageHandler`：手表消息 → `com.getpebble.action.app.RECEIVE`
  广播；
- `launchOutgoingAppMessageHandlers`：监听 `SEND` 广播 → 发到手表，监听
  `ACK`/`NACK` → 回复手表，发 `RECEIVE_ACK`/`RECEIVE_NACK`。

watchapp 不声明 `companionApp`、不带 PKJS 时，`CompanionAppLifecycleManager` 会走
`legacyPebbleKitEnabled` 分支，创建 `PebbleKitClassic`（默认开启）。这是当前
唯一能稳定双向通信的官方路径。

## 4. 线程模型

| 线程 | 工作 |
|---|---|
| Android main | `MirrorController` 状态、编码/发送调度、UI 回调、广播接收 |
| `pebble-mirror-capture` HandlerThread | ImageReader 回调、Bitmap 转换、缩放 |
| `PebbleMirrorShell` 单线程池 | Shizuku/Root shell 命令串行执行 |
| 无障碍主线程 | `dispatchGesture` / `performGlobalAction` |

- `onScaledFrame` 在采集线程把 `int[]` 的所有权交给 controller（每次采集新数组），
  避免大数组拷贝。
- `LegacyPebbleLink` 的队列和 `pending` 都在 main 线程访问；`FrameJob` 串行发完
  一条消息等 ACK 再发下一条。
- 编码在 main 线程执行；200×228 的 Atkinson 约几毫秒到十几毫秒，1–3 fps 下可以
  接受。若以后要更高帧率，可把 `FrameEncoder.encode` 移到单独线程。

## 5. 带宽/内存预算

- 单帧原始 1-bit：`ceil(200/8) * 228 = 5700` 字节。
- tile 形式：`25 * 29 * 8 = 5800` 字节（最后一 tile 行有 4 行 padding）。
- 关键帧消息：7250 字节 DATA + 字典开销，仍在 8K 内；小 inbox 时按上限分块。
- 手表 bitmap：`28 * 228 * 2 ≈ 12.8KB`；app RAM 约 5.8KB + 位图堆。
- 手机缩放图：200×444×4 ≈ 355KB；采集 bitmap：480×1067×4 ≈ 2MB，单帧生命周期。
- 帧差分后，静态页面通常 0 tile；滚动/翻页时才发送大量 tile。

## 6. 权限与注入

```
InjectorFactory.create(settings.injector)
  ├─ 无障碍：MirrorAccessibilityService.dispatchGesture / performGlobalAction
  ├─ Shizuku：Shizuku.newProcess("sh -c input ...")
  └─ Root：ProcessBuilder("su", "-c", "input ...")
```

- 无障碍优先：无需 root、延迟低，支持完整折线路径手势（画圈/S 形）。
- Shizuku/Root：点击/按键仍用 `input`；路径用 `PathInjectorMain` 通过
  `app_process` 以 shell/root 身份运行，在**一个进程**里用
  `InputManagerGlobal.injectInputEvent` 按真实时间间隔注入所有点。避免每个点
  启动一个 `input` 进程（约 20ms/点）导致回放变慢；helper 不可用时才回退到
  限制点数的 `input motionevent`。
- 触摸首先由手表用本地时长/位移分类：快速点击直接发 `TAP`，长按发 `LONG`，
  明显拖动则在松手时把整条折线放进一条 `PATH` 消息；手机端不依赖 AppMessage
  到达时间差，避免帧传输繁忙时把快速点击误判成长按。
- `TouchGesture` 回退分类为 tap（<14px 且 <500ms）、long-press（<14px 且
  ≥500ms）、swipe（其余）。长按用 700ms 的 `input swipe x y x y 700` 或 700ms
  的无障碍 stroke 重放。
- 路径是“松手后回放完整折线”（最多 256 个点），不是实时指针；无障碍模式一条
  `GestureDescription` 连续重放，能保留圆圈/S 形。Shizuku/Root 的
  `PathInjectorMain` 也用一个 `app_process` 进程按 `DURATION` 分段时间注入，
  不再受逐点 shell 启动开销影响；`input motionevent` 只作为兼容回退。

## 7. 可扩展点

- 支持 `flint`（144×168）只需在 `package.json` 的 `targetPlatforms` 增加
  `"flint"`；C 代码已用动态宽高，触摸 API 在 flint 上也可用。
- 支持横屏/折叠屏：`ScreenCapture` 已监听 `DisplayListener` 并在旋转后重建
  VirtualDisplay；需要真机验证。
- 实时拖动：见 6。
- 更高帧率：把编码移出 main 线程；在 AppMessage 之外考虑 DataLogging（仅手表→
  手机，不适合画面）。
- 颜色：emery 是 64 色，但 1-bit 最省带宽；若要做 4 级灰度，需要 2-bit tile 和
  更大的带宽预算。
