---
sidebar_position: 6
sidebar_label: 第 5 章：截图、UI 树与输入
title: 第 5 章：只观察和操作同一块虚拟屏
description: 在 API 36 ARM64 设备上验证目标显示截图、有界 UI 树、定向输入与资源清理。
---

import observeScreenshot from './assets/chapter-5-observe.png';
import afterInputScreenshot from './assets/chapter-5-after-input.png';
import ObserveActFlow from './assets/observe-act-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

第 4 章证明 Demo 的 task 位于一块 `displayId > 0` 的 VirtualDisplay。这一章完成可见闭环：**读取这块屏的画面和 UI 树，用树里的 bounds 定向输入，再读取新画面和新树确认结果。**

**Ditto 主线位置：** App 自有 ADB 配对 → UID 2000 后端 → VirtualDisplay → **截图 / UI 树 / 定向输入**。本章仍不增加任意 shell 方法；Linux / PRoot、Agent 宿主与模型循环留给后续章节。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>观察目标显示</strong></figcaption>
    <img src={observeScreenshot} alt="PocketRun 第 5 章面板显示 displayId 11 的虚拟屏截图、available UI 树和唯一输入框" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>定向输入后再次观察</strong></figcaption>
    <img src={afterInputScreenshot} alt="同一虚拟屏内的输入框和结果文本都显示 PocketRun_Ditto_5，UI 树状态为 available" width="360" />
  </figure>
</div>

截图中的 `displayId` 由 Android 动态分配，不要求等于 `11`。第一张图里输入框显示的是 hint，结果仍为空；第二张图中 EditText 和结果 TextView 才都包含真实输入值。

## 先看完整闭环

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="同一虚拟屏观察、操作和再次观察的流程图，可横向查看">
    <ObserveActFlow className={`pr-oa-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-oa-title" aria-describedby="pr-oa-desc" style={{display: 'block', width: '100%', minWidth: 720, height: 'auto'}} />
  </div>
  <figcaption>截图、UI 树和输入是三种独立能力，但都由同一个正数 `expectedDisplayId` 路由；动作结果还要重新观察确认。</figcaption>
</figure>

## 同一份任务资源，同一个 expectedDisplayId

```text
React / TypeScript：按钮、预览、状态
  ↓ TurboModule
App Kotlin（App UID）：Binder proxy、codec、私有预览缓存
  ↓ typed Binder，逐 RPC 检查 App UID
shell 后端（UID 2000）：当前 VirtualDisplay session
  ├─ ImageReader → 有界 JPEG / SharedMemory
  ├─ UiAutomation → 当前显示的有界 UI 树
  └─ IInputManager → 当前显示、App target UID 的事件
```

资源所有权仍在原生层。TypeScript 只收到普通数据，不持有 SharedMemory、FD、ImageReader、UiAutomationConnection 或 Binder 句柄。

每个操作都带 `expectedDisplayId`，后端先验证：

1. Binder 调用者 UID 等于启动时绑定的 PocketRun App UID；有句柄不等于有权限。
2. `expectedDisplayId > 0`。
3. 它严格等于当前活动 session 的 ID；不是“系统里存在任意同名显示就可以”。
4. 资源未释放、未关闭，调用没有与清理交错使用失效对象。

默认显示 `0`、不匹配的 ID、释放后的旧 ID 都必须拒绝。禁止 default-display fallback，也不允许创建屏失败时镜像主屏来凑出截图。

## 四个有边界的 Binder 方法

```aidl
SharedMemory captureScreenshot(int expectedDisplayId);
byte[] dumpUiTree(int expectedDisplayId);
boolean injectTap(int expectedDisplayId, int x, int y);
boolean injectText(int expectedDisplayId, String text);
```

没有 `execute(String command)`，也没有让调用者指定任意 package、component、输入 target UID 或显示降级选项。ADB 只承担第 2、3 章已有的 TLS 配对、`id -u` 探测和固定后端启动命令；后续动作只走 Binder。

## 从最新 RGBA 帧到 JPEG

第 4 章的 ImageReader 已经提供了 VirtualDisplay 的 Surface；现在读取的是它的 **latest image**，不是主显示截屏：

1. ImageReader 的专用线程反复调用 `acquireLatestImage()`，只保留最新一帧并关闭被替换的旧帧；尚无帧时明确失败，不让 Binder 调用永久等待。
2. `RGBA_8888` plane 同时有 `pixelStride` 和 `rowStride`。逐行按真实 stride 取有效像素，不能把含行尾 padding 的 buffer 直接当成紧凑位图。
3. 得到实际宽高的 Bitmap，以 **JPEG quality 90** 编码。
4. 临时 Bitmap 在 `finally` 中回收；保留的最新 Image 在下一帧替换或 session 释放时关闭，ImageReader 也随 session 清理。
5. JPEG payload 限制为 **2 MiB**，放入有界 SharedMemory，并将保护属性改成只读。

### 32 字节 header 不是图片的一部分

`ScreenshotCodec` 使用 big-endian，布局固定：

| 偏移 | 长度 | 字段 |
| --- | --- | --- |
| 0 | 4 | magic `0x50525353` |
| 4 | 4 | version `1` |
| 8 | 4 | `displayId` |
| 12 | 4 | `width` |
| 16 | 4 | `height` |
| 20 | 8 | `frameTimestampNanos` |
| 28 | 4 | JPEG `payloadLength` |
| 32 | payloadLength | JPEG bytes |

接收方先检查总长度、magic/version、正数 ID / 尺寸 / 时间戳，以及 payload 长度与剩余字节完全一致。截掉 header 后才可以交给 `BitmapFactory`。只断言“收到了一串 bytes”不够：真实验收还必须 JPEG 解码成功且宽高匹配。

### App 只读映射，再复制到私有 cache

App 用 `mapReadOnly()` 读取，decode 后复制 JPEG 到自身 `cacheDir`，在 `finally` 中 unmap 并 **close SharedMemory**。不要把跨进程 FD 长期留给 React 页面，也不要把 SharedMemory 对象塞入 Promise。

前端预览信息约定为：

```ts
type ScreenshotInfo = {
  displayId: number;
  width: number;
  height: number;
  frameTimestampNanos: string;
  uri: string;
};
```

纳秒时间戳保持十进制字符串，避免 JavaScript `number` 的整数精度丢失。`uri` 指向当前 App 私有 cache 文件，不是外部存储、公共媒体库或可跨 App 读取的 exported provider。图片不申请分享授权；验收检查路径、文件 App UID 所有权和仅 owner 可读写。释放、停止和 Binder death 应使本任务的预览文件失效并删除，不能只把按钮禁用。

## UI 树要先选 display，再遍历 node

shell 后端通过反射取得 `UiAutomationConnection`，以 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 建立自己的 UiAutomation，不停用用户已有的无障碍服务；读取 `getWindowsOnAllDisplays()`，**只选 `expectedDisplayId` 对应的 windows**。不能用 `rootInActiveWindow` 猜测当前屏，更不能无窗口时改读 display 0。

遍历预算固定为：

- 最多 **512 nodes**；
- depth 最多 **32**；
- 编码后的 binary tree 最多 **256 KiB**；
- 单个字符串采用有界编码，截断以 `truncated` 明确告知。

树携带 `displayId`、typed status、`truncated` 和 node 数据，包括 parent/window、class/package/view ID、文本、contentDescription、bounds、editable/enabled/clickable/focused。节点与窗口读取结束后及时回收；专用工作线程有界等待，不把整个查询无限挂在 Binder / App UI 线程上。

`UiTreeCodec` 负责 binary tree 解码，App 再转换成 JSON。面向 TypeScript 的同名约定为：

```ts
type UiTreeInfo = {
  displayId: number;
  status: string;
  truncated: boolean;
  nodeCount: number;
  json: string;
};
```

实际 typed 状态包括 `available`、`no_window`、`root_unavailable`、`timeout`、`busy`、`query_failed`。`no_window` 不意味着页面为空；`busy` 不意味着这次读取成功；权限不足、反射失败或目标窗口不可见不能返回一棵伪造的成功树。真实测试可在有界时间内等待 `available`；期限内仍不可用就是失败 / 阻塞，不是通过或跳过。

## 定向输入不是“调用返回 true 就完成”

Demo 增加一个 `EditText`，其 **contentDescription 精确为 `演示输入框`**；结果 TextView 展示当前输入。验收固定使用 printable ASCII：

```text
PocketRun_Ditto_5
```

输入路线是 `IInputManager`。每一个触摸 DOWN / UP 和字符按键事件都设置当前目标 `displayId`，注入的 **target UID 为 PocketRun App UID**。不把 target UID 传给 JavaScript，也不以 `input tap` / `input text` shell 字符串代替 typed RPC。

- 点击坐标为整数，必须在 session 的 `[0, width)`、`[0, height)` 内；负数、边界外和溢出值都拒绝。
- 文本仅接受 printable ASCII；中文、换行、Tab、NUL、DEL 等拒绝。
- 使用真实 UI 树找到唯一 editable Demo input，取得 bounds 后点击中心，再注入固定文本。
- 注入返回成功后**重新读取树**：EditText 的真实 text 必须等于固定值，非 editable 的结果 TextView 也必须包含该值。
- 操作期间通过已配对的真实 AdbClient，仅执行测试内固定的 dumpsys，检查 Demo task 的 display 归属；不在 shell UiAutomation 活跃时建立另一条 instrumentation UiAutomation / UiDevice 连接。
- 所有 tree / input 已结束且后端显式 stop 或死亡后，才第一次使用真实 UiDevice 的 display 0 selector 交叉检查：主屏没有 Demo 标题 / `演示输入框` 控件，也没有 text 精确等于固定值的 EditText。测试不对 Panel JSON 使用 `textContains`，因为展示操作结果的 JSON 本来就可以含该值；这不是输入污染。主屏断言后不再重连后端 tree。

如果输入系统服务不能提供显示路由或 App target UID 约束，就停止并报告能力失败，不能偷偷改成默认屏输入。

## Owned cleanup 与死亡

这里的任务 context 是这一次 App/backend 会话的原生资源范围，不是提前声称实现了后续章节的完整 Agent TaskContext。

正常 release、shutdown、App client Binder death 和 backend death 都要关联到同一份 owned cleanup：

- 先失效当前 session ID，拒绝新的旧 ID RPC；
- 不遗留未完成的触摸序列 / 等待中的输入任务；
- 取消有界 tree 查询，请求专用线程 quit；UiAutomationConnection 在 owned 异步 close 路径 disconnect，并以有界观察确认线程真的退出，不能把发出 quit 当成已经退出；
- release VirtualDisplay，close ImageReader / Image、临时 FD / SharedMemory；
- 清理 App 私有截图 cache 与旧预览状态；
- Demo 不得回落到主显示 0，系统 task/window/display 均消失。

仅看到 `displayId = -1` 不足以证明所有资源已关闭。仪器测试检查系统显示和 Demo window/task 消失、已持有的旧 RPC 全部失败、cache 删除；shutdown / 精确 kill 后还检查 Binder death 和 owned PID 消失。

线程验收使用已配对的真实 AdbClient 执行固定 `ps -T -p <本轮后端PID> -o TID,CMD`，因为 Android `ps` 的 `NAME` 列只重复进程名，`CMD` 才显示各线程的 kernel comm。先确实观察到 `PocketRun-DisplayFrames`、`PocketRun-UiQuery-<displayId>`、`PocketRun-UiEvents-<displayId>`，记录 TID。kernel comm 可能将名称截为 15 字节，测试用确切前缀并绑定 owned PID/TID；release 后有界轮询这些线程和异步 `PocketRun-UiClose-<displayId>` 全部消失，不要求 release 返回瞬间即完成。ps 不支持、无有效 header 或活进程没有真实线程行都会失败，不能把观察错误当清理成功。输入资源与 UiAutomation 必须和这个 session 关联，不能用 JVM mock 的 close 调用计数代替设备资源验收。

### 真 Binder 的 dump / release 并发准备

同一个 instrumentation `@Test` 在第一次正常释放后，再创建一个新的 expected ID，先真实读到 `available` 树并观察本轮 PID 的资源线程。两个 Future 通过同起 barrier 分别调用真实 `dumpUiTree(expectedId)` 与 `releaseDisplay()`，共享 **5 秒** deadline；结束后 fixture worker 也必须在有界时间内退出。没有产品 delay / test hook、mock 或第二次配对。

并发 dump 只允许返回这一旧 expected ID 的合法 typed 树（`available`、`no_window`、`root_unavailable`、`query_failed`、`timeout`、`busy`），或者由释放造成的明确 stale-session 参数 / 状态异常。解码错误、非法数据、权限失败或返回其他 session ID 均不能当作 stale 成功。随后以真实 DisplayManager / 固定 ADB dumpsys / PID-TID 观察确认 display 和线程清理；只有清理完成才创建下一 ID，验证新树恢复 `available`，两个旧 ID 的四个 RPC 继续拒绝。

本章的 API 36 ARM64 instrumentation 已真实执行这组并发调用，并在共享 5 秒期限内完成清理与重建。同起 barrier 不保证调度顺序，也不强制命中 `busy` / `timeout`；本次通过不表示这些分支都已命中。这里验证的是无论 dump 先完成还是 release 先失效 session，都不能串到新 ID，且资源最终清理完成。

## 在自己的 API 36 ARM64 设备复现

使用能实际运行 API 36 ARM64 guest 的自有 AVD，或明确授权的 ARM64 真机。只使用演示设备和演示数据；Android 11+ 无线调试是前提。instrumentation fixture 明确校验 **API 36 和 `arm64-v8a`**，其他系统或 ABI 需要各自的真实设备证据。

### 1. 明确选择设备

在与 ARM64 guest 匹配、具备对应平台加速能力的机器上，使用已安装的 SDK / API 36 ARM64 system image 创建专用 AVD。仅有 x86_64 KVM 不代表能硬件加速 ARM64 AVD；不满足时改用明确授权的 API 36 ARM64 真机。例如：

```bash
avdmanager create avd -n pocketrun-ch5-api36 \
  -k 'system-images;android-36;google_apis;arm64-v8a' --device pixel_6
emulator -avd pocketrun-ch5-api36 -port 5556 -no-snapshot -no-boot-anim
```

SDK / 镜像没有准备好时，由用户先准备环境；本章任务不安装依赖。`5556` 只是显式选择的示例端口，不得复用正在承载别的任务的模拟器。另开终端，检查设备后安装本轮构建制品：

```bash
SERIAL=emulator-5556  # 真机则填入 adb devices 中的已授权 serial
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$SERIAL" emu avd name  # 只适用于 AVD，必须与选择的 ownedAvdName 一致
adb -s "$SERIAL" install -r app/android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

以上 APK 路径从仓库根目录起算。只有用户确认归属和授权后才继续；真机的 runner 参数要匹配 `getprop ro.serialno`，不能只依赖主机默认设备。

测试在创建测试 key / 发送配对请求之前，以 App UID 启动固定参数数组 `[/system/bin/getprop, ro.boot.qemu.avd_name]` 或 `[/system/bin/getprop, ro.serialno]` 做只读身份核对；不使用 shell 解释器或 UiDevice。观察限时 3 秒、输出至多 1 KiB，`finally` 回收子进程和 streams。空值、SELinux 拒绝、非零退出或超时均明确失败 / 阻塞，不能悄悄改在配对后检查。部分真机禁止 App UID 读取 `ro.serialno`；这种设备的 `authorizedSerial` 路径当前受限，推荐使用可读 owned AVD 路径，不通过放宽权限或默认选择设备来规避。

### 2. 系统授权和真实配对

1. 在所选设备启用 Developer options，打开 Wireless debugging，完成系统授权。
2. 无线调试主页记录**连接端口**。
3. 点“Pair device with pairing code”，保持弹窗打开，读取本次**配对端口**与六位配对码。
4. runner 默认连本设备 `127.0.0.1`。连接端口不等于配对端口；不硬编码端口或凭证。

从本地终端交互读取本次值，避免把配对码字面量写进 shell 历史。不要打开 `set -x`，不要上传含码的终端录屏或 runner 参数；参数会临时出现在本机进程命令行，执行期间应使用可信独占主机。

```bash
read -r -p 'Wireless debugging connection port: ' CONNECT_PORT
read -r -p 'Pairing dialog port: ' PAIR_PORT
read -r -s -p 'Current six-digit pairing code: ' PAIR_CODE
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.Chapter5DittoIntegrationTest \
  -e ownedAvdName pocketrun-ch5-api36 \
  -e adbHost 127.0.0.1 \
  -e adbConnectPort "$CONNECT_PORT" \
  -e adbPairPort "$PAIR_PORT" -e adbPairCode "$PAIR_CODE" \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
unset PAIR_CODE PAIR_PORT
```

真机将 `-e ownedAvdName pocketrun-ch5-api36` **替换**为 `-e authorizedSerial '<用户确认的 ro.serialno>'`，两者不能同时提供。此参数是用户明确选设备的安全门，不是自动授权任意在线设备。

测试沿用已有 fixture 的独立 `pocketrun_adb_test_identity` key store，真实配对后用 TLS 连接并连续探测两次 `id -u == 2000`，再启动实际后端。已有这份**测试身份**的真实系统授权时，可省去 pair 参数，改为 `-e adbAlreadyPaired true`；PocketRun 产品 UI 的另一个 key 已配对不等于测试 key 已配对。没有真实授权时 TLS 失败必须如实失败。

### 3. 验收证据必须覆盖结果与死亡

`Chapter5DittoIntegrationTest` 不使用 mock 或 `Assume`。它检查：

- 配对 / TLS connect / shell UID 2000 / backend UID、PID、pong；关闭 ADB 后 Binder 仍工作。
- 系统 display 和 Demo task 对应同一正数 ID。
- 32-byte screenshot header、尺寸 / timestamp、非空真实 JPEG、只读 SharedMemory 和私 cache URI。
- exact-display `available` 树，以及 256 KiB / 512 nodes / depth 32 的预算。
- 唯一 `演示输入框` 的真实 bounds、tap center、固定 ASCII text，重新读树确认 EditText 和结果 TextView。
- 主显示 0 无 Demo / 演示输入 / 固定文本污染。
- display 0、错误 ID、stale ID、非 printable ASCII 和非法 coordinates 的拒绝。
- release 后旧 ID 四个 RPC 全部失败；同一起点并发 dump/release 必须有界结束、结果不能串到其他 session，display/线程清理后再建屏且 tree 可恢复。
- 重建、正常 stop 与意外 backend death 分别验证 display/task/window/cache/process 清理。

需保留所选设备的 runner 原始结果、显示 ID / session / PID 关联、JPEG/树/输入与 release/death 观察证据；不保留配对码。只有完整 instrumentation 成功且资源观察一致才登记真实通过，单张预览、RPC `true` 或系统命令退出 `0` 不能替代上述验收。

## 验证结果

2026-11-18 先在 Devbox 完成编译与静态门禁，再在任务专用的 ARM64 Android 16 / API 36 AVD `pocketrun-ch1-api36` 冷启动并执行真实 instrumentation 与产品界面闭环：

```bash
# app/，已有依赖，不安装新依赖
npm run typecheck
npm run lint
npm test -- --runInBand
npm run audit:ci

# app/android/
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2

# website/
npm run typecheck
npm run build
npm run audit:ci
```

| 门禁 | 结果 |
| --- | --- |
| App typecheck / lint / Jest | 通过；4 suites / 30 tests，退出码 0 |
| ADB / App JVM tests | 通过；ADB 4 + App 23，共 27 tests，无 failure/error/skip |
| ARM64 Debug / Release / AndroidTest compile | 通过；三类 APK 均生成，Gradle 退出码 0 |
| App / website `audit:ci` | 两者退出码 0；沿用精确 picomatch 临时豁免至 2026-12-18；App 5 项 moderate，未新增豁免或改依赖 |
| 网站 typecheck / build | 通过，退出码 0 |
| API 36 ARM64 instrumentation | 通过；`Chapter5DittoIntegrationTest` 为 `OK (1 test)`，无 skip |
| 产品 UI 闭环 | 通过；真实连接 UID 2000 后端，创建目标显示，读取截图与 `available` 树，定向点击并输入，再观察到 EditText 与结果 TextView 同步更新 |

instrumentation 覆盖真实 TLS ADB、UID 2000 `app_process`、Binder、ImageReader、UiAutomation、IInputManager、stale ID 拒绝，以及 release / shutdown / backend death 清理。Jest / JVM 仍只负责按钮状态、编解码预算和参数拒绝；它们不能替代这次设备结果。

## 对应代码

- `app/android/app/src/androidTest/java/io/github/kkkhs/pocketrun/Chapter5DittoIntegrationTest.kt`：真实设备准备、结果断言、release / shutdown / backend death。
- `app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl`：typed capability 边界。
- `app/android/app/src/main/java/io/github/kkkhs/pocketrun/ScreenshotCodec.kt`：有界 screenshot header 与 SharedMemory codec。
- `app/android/app/src/main/java/io/github/kkkhs/pocketrun/UiTreeCodec.kt`：有界 binary tree 与 typed 状态。
- `PrivilegedBackendController.kt`、`PrivilegedBackendMain.kt`、`VirtualDisplaySession.kt` 与 `DisplayDemoActivity.kt`：App/backend/resource owner 和固定 Demo。

练习：解释为什么“JPEG 可解码”“tree available”“injectText 返回 true”“树里真实显示输入值”是四条独立证据，以及释放后为什么必须再次调用旧 ID 验证拒绝。

## 本章代码

- [PR #8：截图、UI 树、定向输入与第 5 章](https://github.com/kkkhs/pocketrun/pull/8)
- [Release `chapter-5`：本章结束时的源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-5)
