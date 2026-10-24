# 通信协议

watchapp 与 Android 伴侣应用通过 **AppMessage** 通信，中间由官方 Pebble 应用的
`PebbleKitClassic` 广播桥转发。所有 key 都是数字，C 端和 Java 端直接使用同一组
常量（`watchapp/src/c/main.c` 与 `WatchProtocol.java`）。

## 1. AppMessage keys

| Key | 名称 | 类型 | 方向 | 说明 |
|---:|---|---|---|---|
| 1 | `KIND` | uint8 | 双向 | 消息类型 |
| 2 | `SEQ` | uint16 | 表→手机 | 会话 ID（每次 watchapp 启动变化） |
| 3 | `FRAME` | uint16 | 手机→表 | 帧号 |
| 4 | `TILE_START` | uint16 | 手机→表 | 本块第一个 tile 索引（调试用） |
| 5 | `TILE_COUNT` | uint16 | 手机→表 | 本块 tile 数 |
| 6 | `FLAGS` | uint8 | 双向 | 帧标志 / 镜像状态 |
| 7 | `DATA` | bytes | 手机→表 | tile 记录 |
| 8 | `CMD` | uint8 | 双向 | 触摸动作 / 按键 / 请求 |
| 9 | `X` | uint16 | 双向 | 触摸 x |
| 10 | `Y` | uint16 | 双向 | 触摸 y / 平移偏移 |
| 11 | `WIDTH` | uint16 | 双向 | 手表宽 / 配置宽 |
| 12 | `HEIGHT` | uint16 | 双向 | 手表高 / 配置高 |
| 13 | `MAX_OFF` | uint16 | 手机→表 | 视口最大平移量（缩放像素） |
| 14 | `MODE` | uint8 | 手机→表 | 0=竖屏视口，1=整屏缩放 |
| 15 | `TEXT` | string | 手机→表 | 英文状态文字（Pebble 字体不支持中文） |
| 16 | `VERSION` | uint16 | 表→手机 | 协议版本（当前 1） |
| 17 | `MAX_INBOX` | uint32 | 表→手机 | watchapp 的 AppMessage inbox 上限 |
| 18 | `DURATION` | uint16 | 表→手机 | 触摸持续时间（毫秒；`UP`/`PATH` 时携带） |
| 19 | `POINT_COUNT` | uint16 | 表→手机 | `PATH` 的点数 |

## 2. 消息类型

### 表 → 手机

| KIND | 名称 | 字段 | 说明 |
|---:|---|---|---|
| 10 | `HELLO` | `SEQ`, `WIDTH`, `HEIGHT`, `VERSION`, `MAX_INBOX` | 启动/每 6 秒发送一次；`SEQ` 是本次启动的会话 ID |
| 11 | `TOUCH` | `CMD`, `X`, `Y`, `DURATION` | `CMD`: 1=down, 2=move, 3=up, 4=tap, 5=long；`DURATION` 仅 `up` 时携带 |
| 12 | `PAN` | `Y` | 请求手机把视口平移到 `Y`（缩放像素） |
| 13 | `KEY` | `CMD` | 1=返回, 2=主页, 3=最近任务 |
| 14 | `REQUEST` | `CMD` | `CMD=1` 请求关键帧 |
| 15 | `PATH` | `POINT_COUNT`, `DURATION`, `DATA` | 完整触摸折线；`DATA` 为 `count` 个 `uint16 x, uint16 y`（小端） |
| 16 | `MODE` | `CMD` | 请求切换取景：0=竖屏视口，1=整屏缩放（长按 Select） |
| 17 | `PREVIEW` | `CMD`, `X`, `Y` | 触摸预览（不注入）；`CMD`: 1=down, 2=move, 3=up；手机把它画进镜像帧 |

### 按键映射

- 上/下短按：`PAN` 步进 24；
- 长按上/下：`PAN` 到 0 / `MAX_OFF`（仅竖屏视口模式）；
- Select 单击：`KEY` `CMD=2`（Android 主页）；
- Select 双击：`KEY` `CMD=3`（最近任务）；
- Select 长按：`MODE` 切换 0/1；
- 返回：`KEY` `CMD=1`。

### 触摸轨迹

`PREVIEW` 只用于视觉反馈，不触发触摸注入；真正的手势仍由 `TAP` / `LONG` /
`PATH` 完成。手表在按下、移动（约 50ms 节流）和抬起时发 `PREVIEW`，手机把坐标
映射到手机屏幕后，用悬浮窗在**手机屏幕**上画黑边白点/白色轨迹；编码镜像帧前
会擦除同一轨迹区域，避免轨迹出现在手表上。最后一个点保留约 3 秒。

### 手机 → 表

| KIND | 名称 | 字段 | 说明 |
|---:|---|---|---|
| 1 | `CONFIG` | `WIDTH`, `HEIGHT`, `MAX_OFF`, `MODE`, `FLAGS`, `TEXT` | 屏幕尺寸、视口、镜像状态；收到后手表重置帧号并请求关键帧 |
| 2 | `FRAME` | `FRAME`, `TILE_START`, `TILE_COUNT`, `FLAGS`, `DATA` | 一帧的一部分 |
| 3 | `STATUS` | `FLAGS`, `TEXT` | 心跳/状态；`FLAGS` bit0=正在镜像 |

`CONFIG` / `STATUS` 的 `FLAGS` bit0 表示手机端是否正在采集。`FRAME` 的 `FLAGS`：

- bit0 `FINAL`：本帧最后一块，手表把 staging 位图复制到显示位图并重绘。
- bit1 `KEYFRAME`：本帧是关键帧，手表清空 staging 后应用所有 tile。

`STATUS` 的 `FLAGS` bit0 变成 0 时，手表清空最后一帧并回到启动页。手机停止镜像时
会在关闭 `LegacyPebbleLink` 之前直接广播一次 `STATUS(flags=0)`，避免队列清空
导致这条消息丢失；正常心跳仍走带 ACK 的队列。

## 3. 帧与 tile

- 画布：`WIDTH × HEIGHT`，1-bit；0=黑，1=白。
- 8×8 tile：每 tile 8 字节，每字节是一行 8 个像素。
- **位序是 LSB-first**：字节 bit0 是最左边像素，bit7 是最右边。这一点由 PebbleOS
  的 1-bit 合成代码决定，和常见的 PNG/MSB 顺序相反。
- tile 索引：`index = tile_y * tiles_x + tile_x`，`tiles_x = ceil(WIDTH/8)`。
- 一帧的 `DATA` 是若干条记录：

```
uint16 little-endian tile_index
uint8  row0
uint8  row1
...
uint8  row7
```

共 10 字节/记录。

手表端 `GBitmapFormat1Bit` 的行 stride 会按 4 字节对齐；例如 200px 宽时
`gbitmap_get_bytes_per_row()` 返回 28（25 字节有效 + 3 字节 padding）。C 端用
stride 写入，而不是假设 `WIDTH/8`。

## 4. 分块与关键帧

- 8K 手机（手表报告 `MAX_INBOX` ≈ 8200）下，一帧 725 个 tile 直接放进 **一条**
  AppMessage（约 7.25KB），避免分块乱序/丢首块。
- 小 inbox 时按 `MAX_INBOX - 320` 字节分块，手机端保证同一条帧的消息按顺序发送；
  手表若发现某帧不是从 `TILE_START=0` 开始，会丢弃并回 `REQUEST` 要求新关键帧。
- 只有最后一条带 `FINAL`；只有第一条带 `KEYFRAME`。
- 手机端维护上一帧的 1-bit 画布，只发送变化的 tile。变化超过 60%、切换视口、
  切换模式、手表请求关键帧时发送整帧。
- 手表端在 `FINAL` 前只写 staging，不重绘；如果一帧丢了 `FINAL`，下一次
  `CONFIG` 或关键帧会恢复。

## 5. 触摸坐标

- 手表发送的是 **手表屏幕坐标**（0..199, 0..227）。
- 手机端根据当前缩放/视口把它映射到手机原生坐标：
  - 竖屏视口：`scale = watchW / phoneW`；
    `phoneX = watchX / scale`，`phoneY = (offset + watchY) / scale`。
  - 整屏缩放：`scale = min(watchW/phoneW, watchH/phoneH)`，
    先减去黑边再除以 `scale`。
- **快速点按和长按由手表本地分类**（用触摸按下/抬起时间，不依赖消息到达时间）：
  - `duration <= 480ms` 且位移 < 8 手表像素 → 直接发 `TOUCH_TAP`；
  - 更长且几乎不动 → 直接发 `TOUCH_LONG`（用按下点坐标）；
  - 有明显位移 → 松手时发一条 `PATH`，`DATA` 是完整折线（最多 256 点），
    手机端用这些点构建一条连续的 `GestureDescription`，画圈/S 形不会退化成直线。
  这样 BLE/帧传输再忙，也不会把快速点按误判成长按。
- `TOUCH_UP` 带 `DURATION`，手机优先用它，而不是用两条消息的到达时间差。
- 手机端把 down/move/up 归类为 tap / long-press / swipe，然后用无障碍、
  Shizuku 或 root 注入。

## 6. Legacy PebbleKit JSON

官方应用的 `PebbleKitClassic` 使用旧的 PebbleKit JSON 数组格式。手机端把它放在
`msg_data` extra 里：

```json
[
  {"key":1,"type":"uint","length":1,"value":2},
  {"key":3,"type":"uint","length":2,"value":10},
  {"key":7,"type":"bytes","length":0,"value":"AAEC..."}
]
```

- `type`: `"uint"` / `"int"` / `"bytes"` / `"string"`
- `length`: 整数的字节宽度（1/2/4）；bytes/string 为 0
- `value`: bytes 为 Base64（带 padding），string 为原文，整数为数字

广播：

| 方向 | Action | extras |
|---|---|---|
| 手机 → 官方应用 | `com.getpebble.action.app.SEND` | `uuid`, `transaction_id`, `msg_data` |
| 官方应用 → 手机 | `com.getpebble.action.app.RECEIVE` | `uuid`, `transaction_id`, `msg_data` |
| 手机 → 官方应用 | `com.getpebble.action.app.ACK` / `NACK` | `transaction_id` |
| 官方应用 → 手机 | `com.getpebble.action.app.RECEIVE_ACK` / `RECEIVE_NACK` | `transaction_id` |

`transaction_id` 是 0..255 的整数，每条 SEND 分配一个；手机端等待对应的
`RECEIVE_ACK`/`RECEIVE_NACK` 后再发送下一条。超时 10 秒。

另外，手机端可以用以下广播让官方应用打开/关闭 watchapp（幂等）：

| Action | extras | 说明 |
|---|---|---|
| `com.getpebble.action.app.START` | `uuid` | 打开 Screen Mirror |
| `com.getpebble.action.app.STOP` | `uuid` | 关闭 Screen Mirror |

`MirrorService` 在开始镜像时会发一次 START；界面上的“在手表上打开 Screen Mirror”
按钮也会发。

## 7. 心跳与超时

- 手表每 6 秒发一次 `HELLO`，让后启动的手机应用能发现它，也让手机判断
  watchapp 是否还开着。
- 手机每 5 秒发一次 `STATUS`（链路空闲时），手表 9 秒收不到任何消息就显示
  连接断开。
- 手机 12 秒收不到手表任何消息就认为 watchapp 已关闭。

## 8. 版本兼容

`VERSION=1`。如果以后修改 key/位序，必须同时更新 C 端和 Java 端，并提升版本。
