---
sidebar_position: 6
sidebar_label: 第 5 章：看见并操作虚拟屏
title: 第 5 章：看见界面，再操作它
description: 用一次填写输入框的任务，理解 TypeScript、Kotlin、Binder 与 Android 系统如何协作。
---

import observeScreenshot from './assets/chapter-5-observe.png';
import afterInputScreenshot from './assets/chapter-5-after-input.png';
import TaskExecutionLayers from './assets/task-execution-layers.svg';
import ObserveActFlow from './assets/observe-act-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章，我们把 Demo 放进了一块虚拟屏，但还看不到里面的画面。

这一章只做一个任务：**找到虚拟屏里的输入框，填入 `PocketRun_Ditto_5`，再确认它真的填进去了。**

```text
准备：连接后端，创建虚拟屏
执行：观察 → 定位 → 输入 → 核对
结束：释放虚拟屏，停止后端
```

现在由用户依次点按钮驱动这些步骤。后续接入 Agent 时，Agent 负责根据观察选择下一步；这里先把它需要调用的能力做出来。

## 1. 先在界面跑一遍

沿用第 2—4 章的准备：连接本机 ADB，启动 shell 后端，创建演示虚拟屏。然后依次点击：

1. **读取虚拟屏截图**：看到 Demo 的实际画面。
2. **读取 UI 树**：看到节点信息，并提示已定位输入框。
3. **点击演示输入框**：让这个输入框获得焦点。
4. **输入固定 ASCII 文本**：向它输入 `PocketRun_Ditto_5`。
5. **再次读取截图和 UI 树**：核对输入框和结果文字。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>输入前：已经能看见和定位</strong></figcaption>
    <img src={observeScreenshot} alt="PocketRun 面板显示虚拟屏截图和 available UI 树，输入框尚未填字" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>输入后：重新观察到结果</strong></figcaption>
    <img src={afterInputScreenshot} alt="虚拟屏的输入框和结果 TextView 都显示 PocketRun_Ditto_5，UI 树状态为 available" width="360" />
  </figure>
</div>

第一张图里的灰色文字只是输入提示，下面的 `input:` 仍为空。第二张图里，输入框和 `input:` 后面都有了真实文字。

**“发出了输入”与“已经填对”是两件事。** 所以最后两次读取不能省。当前页面不会自动替你重新观察，需要再次点按钮。

## 2. 谁在执行：两个进程，四层职责

先把“层”与“进程”分开。**React 和 App Kotlin 是同一个 App 里的两层代码；shell 后端才是另一个进程。**

<figure style={{margin: '1.25rem 0', maxWidth: 800}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="四层执行职责与两个 PocketRun 进程，可横向查看">
    <TaskExecutionLayers className={`pr-layers-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-layers-title" aria-describedby="pr-layers-desc" style={{display: 'block', width: '100%', minWidth: 640, height: 'auto'}} />
  </div>
  <figcaption>这张图只回答“谁负责执行”。向下是请求，虚线是结果返回；不表示任务有四个步骤。</figcaption>
</figure>

- **React / TypeScript**：用户点哪个按钮，就发哪种请求；收到结果后更新预览和文字。
- **App Kotlin**：接收原生调用，检查参数，通过 Binder 请求后端，再把结果转换成前端能用的数据。
- **shell 后端 Kotlin**：以 UID 2000 运行，校验调用者和目标显示，持有虚拟屏、读图和查树的资源。
- **Android 系统能力**：真正管理显示、提供窗口信息、把输入事件交给目标窗口。

TurboModule 是 **React → App Kotlin** 的桥。Binder 是 **App Kotlin → 独立后端** 的通信通道。它们都不会把普通 App 自己的 UID 变成 2000。

Demo 也仍在普通 App 进程里运行。**把 Activity 显示在虚拟屏上，只改变显示位置，不会把它变成 shell 进程。** 后端负责操作环境，Demo 负责像普通页面一样接收输入和绘制。

## 3. 开始前：接通后端，创建虚拟屏

操作输入框之前，需要先有一个可调用的后端和一块目标显示。这就是前几章各自在解决的问题：

| 准备工作 | 得到什么 | 对应章节 |
| --- | --- | --- |
| App 与本机无线 ADB 配对、连接 | 可以在本机以 shell 身份启动进程 | 第 2 章 |
| 通过 ADB 启动 `app_process`，交换 Binder | App 能调用独立的 UID 2000 后端 | 第 3 章 |
| 后端创建 VirtualDisplay，打开 Demo | Demo 所在显示的 `displayId` | 第 4 章 |

**ADB 是启动入口，Binder 是启动后的通信通道。** 后端建立好之后，本章的截图、查树和输入请求都走 Binder，不需要每次重新连 ADB、重新启动后端。

`displayId` 可以理解成显示器编号。默认主屏是 `0`，本章使用 Android 分配的正数编号，例如 `11`。

每次请求都带 `expectedDisplayId`，意思是“我期望操作这块屏”。后端只接受它当前持有的编号；屏释放后，旧编号立即失效。这样就不会在虚拟屏不可用时转去操作主屏。

## 4. 一次任务怎样推进

下面从上到下看时间顺序。它与上一张分层图回答的是不同的问题：**任务先做什么，再做什么。**

<figure style={{margin: '1.25rem 0', maxWidth: 800}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="填写输入框任务的阶段顺序，可横向查看">
    <ObserveActFlow className={`pr-oa-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-oa-title" aria-describedby="pr-oa-desc" style={{display: 'block', width: '100%', minWidth: 640, height: 'auto'}} />
  </div>
  <figcaption>先按需准备会话，再执行观察、定位、输入、核对；后端已就绪时，可以直接开始下一轮。</figcaption>
</figure>

### 看界面：取得画面和控件

**截图告诉我们“页面长什么样”，UI 树告诉我们“页面里有哪些控件”。** 查树不是从截图做 OCR，而是读取 Android 提供的控件信息。

它们是两条独立路径：

```text
画面：Demo 绘制 → 虚拟屏的 Surface → ImageReader → JPEG 图片
控件：Demo 的窗口 → Android 无障碍接口 → UiAutomation → 节点数据
```

ImageReader 承接虚拟屏的像素。后端取出最新可用帧，转成 JPEG，通过共享内存交给 App Kotlin；App 将图片放进自己的私有缓存，给 React 一个图片 URI。

UiAutomation 则向系统查询窗口，选择同一个 `displayId` 下的窗口，再读取控件。App Kotlin 把节点数据转成 JSON 给 React。

所以 React 最后收到的是**一张可预览的图片，以及一份可解析的控件数据**，不是 Android 的 Bitmap、窗口对象或内存句柄。

### 找输入框：算出点击坐标

这一步在 TypeScript 中完成。`DisplayPanel.tsx` 解析 UI 树，寻找描述为 `演示输入框`、可编辑且已启用的唯一控件。

节点的 `bounds` 是它在**目标显示**上的矩形范围。举一个示意值：

```ts
{
  contentDescription: '演示输入框',
  flags: { editable: true, enabled: true },
  bounds: { left: 64, top: 700, right: 656, bottom: 780 },
}
```

知道四条边，就能算出点击中心：

```ts
const x = Math.floor((bounds.left + bounds.right) / 2);  // 360
const y = Math.floor((bounds.top + bounds.bottom) / 2);  // 740
```

这不是在主屏上点击图片预览的位置，而是在虚拟屏的 720 × 1280 坐标系里点击控件。找不到唯一目标、树不完整或坐标越界时，页面不会启用输入按钮。

### 点击并填字：发送输入事件

定位之后，React 发起两个请求：

```ts
await NativeAdb.injectTap(displayId, x, y);
await NativeAdb.injectText(displayId, 'PocketRun_Ditto_5');
```

两次请求都经过 **App Kotlin → Binder → shell 后端**。后端把点击变成按下、抬起两个触摸事件，把文字变成字符按键事件，再通过 Android 输入服务发送。

每个事件都设置相同的 `displayId`。API 36 验证路径还带上 PocketRun App UID，约束输入接收者。系统把事件交给 Demo 的输入框，Demo 按普通 Android 控件的逻辑更新文字。

**这一章没有直接调用 `EditText.setText()` 来替代输入。** 我们要验证的是“程序能从外部操作这个页面”，所以要走真实输入分发。

当前仅支持 1—64 个可打印 ASCII 字符，固定例子也使用 ASCII。中文输入属于后续能力扩展，不是本章已验证的能力。

### 检查结果：确认文字改变

输入方法返回 `true`，只说明这一组注入调用被系统接受。它不能单独证明目标控件出现了预期文字。

所以再次点击“读取虚拟屏截图”和“读取 UI 树”，检查两件事：

1. 截图里的输入框与结果行显示 `PocketRun_Ditto_5`。
2. 新 UI 树里，EditText 的真实 `text` 和结果 TextView 都包含这个值。

Demo 的 `TextWatcher` 会把输入值同步到结果行。这一行是可观察的反馈，帮助区分“仍然只是 hint”与“实际收到输入”。

截图和树不是一份原子快照，截图也只取当前最新可用帧。若页面尚未重绘，就再次读取；不要把旧预览或旧 JSON 当成操作成功的证据。

## 5. 结束时：把资源收回来

本次演示最后点“释放虚拟屏”，再点“停止 shell 后端”。

释放时，后端使旧 `displayId` 失效，结束查树连接、释放显示、关闭 ImageReader；App 删除这次截图缓存并清空预览。

**React 清空 state 不等于原生资源已经释放。** 显示、连接和图片资源由 Kotlin 管理；后端退出或 App 进程死亡时也需要清理。重新开始时必须取得新的有效显示编号，不能继续使用旧结果。

## 6. 对照代码读一遍

先记住这条公共调用链。下面从仓库的 `app/` 目录起算：

```text
DisplayPanel.tsx                         用户点按钮、处理结果
  ↓ TurboModule
android/.../AdbModule.kt                  接收调用、检查参数
  ↓ 普通 Kotlin 调用
android/.../PrivilegedBackendController.kt App 侧后端客户端
  ↓ Binder，接口定义在 IPrivilegedBackend.aidl
android/.../PrivilegedBackendMain.kt      独立后端接收请求
  ↓
android/.../VirtualDisplaySession.kt      选中本次虚拟屏，执行对应操作
```

`NativePocketRunAdb.ts` 是 React 这边的调用约定；AIDL 是两个进程之间的调用约定。它们不是又多出了两个执行阶段。

顺着一个按钮看完这条链，再看另外三个，结构是一样的：

| 用户动作 | React 调用 | 后端做什么 | 回到 React 的结果 |
| --- | --- | --- | --- |
| 读取截图 | `captureScreenshot(id)` | ImageReader 取帧，编码 JPEG | 图片 URI、尺寸、时间戳 |
| 读取 UI 树 | `dumpUiTree(id)` | `DisplayAutomation` 查询目标窗口 | 状态、节点数、JSON |
| 点击输入框 | `injectTap(id, x, y)` | `DisplayInput` 发送触摸事件 | 注入调用是否被接受 |
| 输入文字 | `injectText(id, text)` | `DisplayInput` 发送字符按键 | 注入调用是否被接受 |

**建议阅读顺序：** [DisplayPanel.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/DisplayPanel.tsx) → [AdbModule.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/AdbModule.kt) → [PrivilegedBackendController.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt) → [PrivilegedBackendMain.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendMain.kt) → [VirtualDisplaySession.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/VirtualDisplaySession.kt)。

<details>
<summary>进阶：截图、UI 树怎样跨进程传递</summary>

**截图：共享内存中装什么？**

`ScreenshotCodec.kt` 编码一个 32 字节的头部，后面跟 JPEG：

| 偏移 | 字段 | 字节数 |
| --- | --- | --- |
| 0 | magic `0x50525353` | 4 |
| 4 | version `1` | 4 |
| 8 | displayId | 4 |
| 12 | width | 4 |
| 16 | height | 4 |
| 20 | frameTimestampNanos | 8 |
| 28 | JPEG 长度 | 4 |
| 32 | JPEG 数据 | 按长度读取 |

格式采用 big-endian。接收方校验格式、总长度、尺寸、时间戳和显示编号后，才复制 JPEG。共享内存设为只读；App 映射、复制后解除映射并关闭句柄。后端在 Binder 回复写入文件描述符之后关闭自己一侧的句柄。

图像平面有真实的 `pixelStride`、`rowStride` 和裁剪范围，不能把带行尾填充的字节直接当成紧凑位图。当前按有效 RGBA 像素复制，JPEG 质量为 90，最多 2 MiB；临时 Bitmap 回收，旧 Image 在替换或释放时关闭。

App 使用只允许自身 UID 读写的私有缓存。React 收到的是 `file://` URI；纳秒时间戳用十进制字符串传递，避免 JavaScript 整数精度丢失。释放、停止或后端死亡时使旧缓存失效。

**UI 树：为什么限制大小？**

`UiTreeCodec.kt` 传输有界二进制数据，App 再转成 JSON。上限是 512 个节点、深度 32、每个字段 256 UTF-8 字节、总计 256 KiB；截断用 `truncated` 表示。这避免把任意大的树塞进 Binder。

查询只遍历 `getWindowsOnAllDisplays()[displayId]`。连接使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，不主动停用已有无障碍服务；窗口和节点在读取结束后回收。

返回状态包括 `available`、`no_window`、`root_unavailable`、`timeout`、`busy`、`query_failed`。查树等待限时 1800 ms；这些失败状态不等于“成功拿到空页面”。当前只有完整、可用且目标唯一的树才能用于输入。

Binder 接口只开放截图、查树、点击、文字等明确的方法。每次校验 App 调用者 UID、正数且仍有效的目标显示；TypeScript 不能指定任意 shell 命令或输入目标 UID。默认显示 `0`、不匹配与已释放的编号都拒绝。

</details>

## 7. 实践与验证

2026-11-18，在任务专用 Android 16 / API 36 ARM64 AVD `pocketrun-ch1-api36` 上真实执行了本章流程。

| 验证 | 已记录结果 |
| --- | --- |
| 产品界面 | 连接后端、创建显示、截图、查树、定向输入、再观察均通过 |
| Android instrumentation | `Chapter5DittoIntegrationTest`：`OK (1 test)`，无跳过 |
| App TypeScript / ESLint / Jest | 通过；30 个 Jest 测试 |
| ADB / App JVM | 通过；27 个测试 |
| ARM64 Debug / Release / AndroidTest 构建 | 通过 |
| 网站类型检查、构建与审计 | 通过 |

真实设备测试不仅检查“文字填对了”，还检查旧编号被拒绝、释放与后端死亡后显示/任务/线程/缓存被清理。Jest 和 JVM 适合检查状态与数据处理，不能替代真实 Android 测试。这里的通过代表这台 AVD，不是所有品牌设备都已支持。

<details>
<summary>实践：在自己的 API 36 ARM64 演示设备上运行测试</summary>

**准备设备与构建。** 先按第 1 章准备 SDK、JDK 与依赖。使用自有专用 AVD 或明确授权的演示真机，确认系统为 API 36、ABI 包含 `arm64-v8a`；不要对未选中的设备安装或输入。ARM64 AVD 需要匹配的宿主或实际可用的模拟环境，x86_64 KVM 不等于可以加速 ARM64 guest。

在 `app/` 执行：

```bash
npm run typecheck
npm run lint
npm test -- --runInBand
npm run audit:ci
cd android
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

回到仓库根目录，显式选择设备。以下 AVD 名称和 serial 都是示例，必须换成自己选择的设备：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$SERIAL" install -r app/android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

**授权并配对。** 在该设备开启开发者选项和无线调试，记录主页连接端口，再打开配对码弹窗，取得配对端口和本次六位码。连接端口不等于配对端口。

交互读取配对码，避免字面量进入命令历史；不要开启 `set -x`。runner 参数会暂时出现在本机进程命令行，仅在可信主机执行，不保留含码的录屏或日志。

```bash
read -r -p 'Connection port: ' CONNECT_PORT
read -r -p 'Pairing port: ' PAIR_PORT
read -r -s -p 'Current six-digit code: ' PAIR_CODE
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.Chapter5DittoIntegrationTest \
  -e ownedAvdName pocketrun-ch5-api36 \
  -e adbHost 127.0.0.1 \
  -e adbConnectPort "$CONNECT_PORT" \
  -e adbPairPort "$PAIR_PORT" -e adbPairCode "$PAIR_CODE" \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
unset PAIR_CODE PAIR_PORT
```

`ownedAvdName` 必须等于自己选择的 AVD 名称。真机改用 `authorizedSerial`，值为确认过的 `ro.serialno`，两者不能同时提供。fixture 在配对之前用固定、只读的 `getprop` 子进程核对设备身份；读取超时、拒绝或空值时停止，不绕过授权。部分真机不允许 App UID 读取序列号，这条验收路径会受限。

测试用独立的 `pocketrun_adb_test_identity`，与产品 UI 的身份不同。只有这份测试身份已经获得系统授权时，才能省略 pair 参数并改传 `-e adbAlreadyPaired true`。

</details>

<details>
<summary>验证细节：怎样确认资源清理与并发安全</summary>

[Chapter5DittoIntegrationTest.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/androidTest/java/io/github/kkkhs/pocketrun/Chapter5DittoIntegrationTest.kt) 使用真实 TLS ADB、UID 2000 后端和 Binder。测试先检查目标显示/任务归属、共享内存只读与 JPEG 可解码、UI 树可用，再依据控件 bounds 注入并核对实际文字。

它分别测试释放显示、正常停止后端与杀死本轮后端 PID。检查系统显示和 Demo task/window 消失，旧编号的四个 RPC 拒绝，缓存删除；后端死亡时还检查 Binder death 与 PID 消失。

线程观察通过固定 `ps -T -p <本轮PID> -o TID,CMD`：`CMD` 显示线程的 kernel comm，`NAME` 仅重复进程名。先记录真实帧/查询/事件线程 TID，再有界观察释放后这些线程与异步关闭线程消失；名字可能截为 15 字节。命令错误、缺 header 或活进程没有线程行不当作清理成功。

并发测试让真实 `dumpUiTree(oldId)` 与 `releaseDisplay()` 从同一起点发起，要求两调用在共享 5 秒期限内结束。只允许旧 ID 的合法结果或明确失效异常，不接受新 ID、解码错误或权限失败。清理完成后重建，确认新树可用、旧 ID 继续拒绝。这不强制命中 `busy` / `timeout`，不代表所有晚回包分支已实测。

测试期间不用另一条 instrumentation UiAutomation 查询界面，以免与后端的 UiAutomation 冲突。后端全部退出后才用 UiDevice 检查主屏没有 Demo 输入控件或固定文字；不把预览 JSON 中的文字误判成主屏被输入。

审计沿用第 0 章记录的精确 picomatch 临时例外，截止 2026-12-18；其他 high/critical 根公告仍阻断。第 5 章记录 App 5 项 moderate、网站 0 项 moderate，没有新增依赖或豁免。截图正文、UI 树正文、配对码和交换 token 不写日志。

</details>

## 8. 本章小结与源码

我们接通了三个能力：**看见画面、读取控件、向目标显示输入。** 它们让程序具备操作页面的基础；“做哪一步、何时继续或停止”仍由当前用户操作，后续才交给 Agent。

下一章开始另一条通道：在普通 App UID 下准备工作目录，通过命令管道处理文件。

练习：点击按钮后，如果 `injectText()` 返回 `true`，但新 UI 树的输入值仍为空，你会直接宣布完成，还是继续观察并报告未确认？为什么？

- [PR #8：第 5 章实现](https://github.com/kkkhs/pocketrun/pull/8)
- [PR #9：任务主线与分层图解](https://github.com/kkkhs/pocketrun/pull/9)
- [Release `chapter-5`：本章结束时的源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-5)
