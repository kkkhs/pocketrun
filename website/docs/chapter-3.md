---
sidebar_position: 4
sidebar_label: 第 3 章：shell 后端与 Binder
title: 第 3 章：启动 shell 后端
description: 用本机 ADB 启动 UID 2000 的 app_process 后端，交换 Binder，再断开 ADB 并检查同一个进程。
---

import beforeScreenshot from './assets/chapter-3-before.png';
import binderScreenshot from './assets/chapter-3-binder.png';
import BackendBootstrapFlow from './assets/backend-bootstrap-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章，App 已经能连接本机 `adbd`，执行固定探测得到 shell UID 2000。但一条命令结束后，还没有我们自己的后端来持有资源。

这一章只做一个任务：**通过 ADB 启动独立的 Kotlin 后端，拿到它的 Binder；断开 ADB 后，再检查同一个后端，最后停止它。**

```text
准备：已有 ADB 连接，启动后端
接通：交换 Binder，取得后端客户端
检查：断开 ADB → Binder ping → 停止后端
```

可见结果是 `pong`、UID 2000 和一个后端 PID。断开 ADB 后 PID 应保持不变；停止后端才会结束这个进程。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>启动前</strong></figcaption>
    <img src={beforeScreenshot} alt="PocketRun 的 shell 后端面板，启动按钮可用，Binder 检查和停止按钮尚未启用" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>断开 ADB 后仍能检查</strong></figcaption>
    <img src={binderScreenshot} alt="PocketRun 通过 Binder 收到 pong，并显示后端 UID 2000 与 PID" width="360" />
  </figure>
</div>

右图来自专用 Android 16 模拟器。PocketRun 已先关闭 ADB 连接，再通过 Binder 得到同一个后端 PID。

## 先在界面跑一遍

前提是已经按[第 2 章](chapter-2.md)完成配对，并连接无线调试主页的连接端口。

1. 点“启动 shell 后端”。结果应显示 `Binder pong`、UID 2000 和一个 PID。
2. 点第 2 章的“取消 / 断开”，关闭 PocketRun 的 ADB transport。
3. 回到第 3 章点“通过 Binder 检查 shell 后端”。PID 应保持不变。
4. 点“停止 shell 后端”。按钮回到只能启动的状态。

这四步分别证明：后端真实启动、身份正确、Binder 不依赖 ADB 连接、进程能够主动关闭。仅看到“启动命令已发送”不能算成功。

## 分清谁在执行

**React 和 App Kotlin 是同一个 App 里的两层代码；shell 后端才是另一个进程。**

| 执行层 | 身份与职责 | 代码 |
| --- | --- | --- |
| React / TypeScript | 普通 App，按钮、状态、结果展示 | `BackendPanel.tsx` |
| App Kotlin | 同一 App 进程、App UID，启动客户端与 Binder proxy | `AdbModule.kt`、`PrivilegedBackendController.kt` |
| shell 后端 Kotlin | 独立 `app_process`、UID 2000，接收受约束的方法调用 | `PrivilegedBackendMain.kt` |

TurboModule 接通前两层。**Binder 是 Android 本机跨进程通信通道，不是后端；ADB 也不是后端。** ADB 负责请求系统 `adbd` 启动 `app_process`，后端建立好后，日常调用只走 Binder。

```text
启动时：App Kotlin → 本机 ADB / adbd → app_process 后端
运行时：App Kotlin ←──── Binder ────→ 同一个后端
```

普通 App 的 UID 没有改变，独立进程才以 shell 身份运行。shell 也不是 root，仍受系统权限和 SELinux 限制。

## 按顺序接通后端

下面的图按时间顺序回答“先做什么、再做什么”；各步骤由谁执行，见上一节的分工。

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="启动后端、交换 Binder、检查与停止的阶段顺序，可横向查看">
    <BackendBootstrapFlow className={`pr-backend-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-backend-title" aria-describedby="pr-backend-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>先通过 ADB 启动，再交换 Binder；之后可以断开 ADB，用 Binder 检查和停止同一个进程。</figcaption>
</figure>

### 先准备交换

点击按钮后，[BackendPanel.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/BackendPanel.tsx) 调用 `startBackend()`，经 [AdbModule.startBackend()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/AdbModule.kt#L76) 进入 App 侧 [PrivilegedBackendController.start()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt#L54)。

Controller 生成一次性 token，调用 `BinderExchangeRegistry.prepare()` 开始等待，然后才发送启动命令。先准备等待，是为了后端启动得很快时也能接住它送回的 Binder。

token 由 `SecureRandom` 生成，只在 10 秒交换窗口内有效，不写入日志或存储。它约束的是“这次启动的后端”，不是长期凭据。

### 用 ADB 启动

[BackendLaunchCommand.build()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/BackendLaunchCommand.kt#L7) 构造固定命令；`AdbModule` 通过已有 ADB 连接发送它。

关键是用 `app_process` 从当前 APK 加载 [PrivilegedBackendMain.main()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendMain.kt#L21)。后端先检查自身 UID 必须是 2000，再创建 Binder 服务。

这不是“ADB 自己变成后端”，也不是启动另一个 React App。它是独立进程里的一段 Java / Kotlin 程序，之后可以持续持有虚拟屏等资源，不必为每个动作拼命令、解析文本。

<details>
<summary>启动细节：CLASSPATH、setsid 与启动标记</summary>

固定命令的启动部分如下（省略后面的存活检查）：

```sh
export CLASSPATH='<当前 APK>'
(
  exec /system/bin/setsid /system/bin/app_process /system/bin \
    --nice-name=pocketrun:backend \
    io.github.kkkhs.pocketrun.PrivilegedBackendMain \
    --token '<一次性 token>' \
    --authority 'io.github.kkkhs.pocketrun.binder_exchange' \
    --app-uid '<当前 App UID>' --user '<当前 Android 用户 ID>'
) </dev/null >/dev/null 2>&1 &
```

- `CLASSPATH` 指向 PocketRun APK，供 `app_process` 加载后端类。
- `setsid` 和标准流重定向让后端脱离本次 ADB shell stream。
- APK 路径与参数在 Kotlin 中校验并引用；不接受 JavaScript 或用户命令字符串。
- 用户 ID 从 App UID 推导；本章专用模拟器使用用户 `0`。
- 完整命令短暂检查子进程存活，再返回固定 `pocketrun-backend-launched` 标记。这个标记仍不是 Binder 就绪证据。

Controller 必须真正收到 Binder，并调用 `getUid()` 得到 2000，才把启动判为成功。

</details>

### 把 Binder 交回 App

两个进程不能直接共享一个 Kotlin 对象。[PrivilegedBackendMain.exchange()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendMain.kt#L56) 带着 token 和后端 Binder 调用用途单一的 [BinderExchangeProvider.call()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/BinderExchangeProvider.kt#L20)。

Provider 内的 `BinderExchangeRegistry.exchange()` 检查调用 UID 必须是 2000、token 匹配且未过期、Binder descriptor 正确。成功一次后立即清空 token，把后端 Binder 交给等待的 Controller。

Provider 同时返回 App client Binder。后端用 `linkToDeath` 观察 App 是否消失；App 侧也监听后端 Binder 死亡。两个方向各有一份观察，不是由 React state 判断进程是否活着。

Provider 必须跨 UID 导出，但不提供 query / insert / update / delete。普通 App UID 冒充 shell 调用 exchange 的真实设备测试会被拒绝。

<details>
<summary>系统接口：裸 app_process 怎样取得 Provider</summary>

`app_process` 是由 `adbd` 直接启动的 shell 进程，不是 ActivityManager 创建的普通 App 进程，没有已经登记的 `Application` / `ActivityThread` 身份。

后端因此通过 Android 的 external-content-provider Binder API 获取目标 Provider，再使用 `com.android.shell` 的 AttributionSource 调用。Android 11 与 Android 12+ 的隐藏接口参数不同，代码按系统版本选择对应签名；本章设备实测的是 API 36 分支。

这层反射只负责取得本 App 的 exchange Provider；业务 Binder 接口本身仍由 AIDL 生成。不能仅凭编译通过宣称所有 Android / OEM 版本都兼容。

</details>

### 断开 ADB 再检查

取得句柄后，Controller 用 `IPrivilegedBackend.Stub.asInterface()` 得到 typed Binder proxy。`pingBackend()` 经 [PrivilegedBackendController.ping()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt#L113) 查询 UID、PID 和 `ping()`。

后端 [BackendBinder.ping()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendMain.kt#L171) 返回 `pong`。结果经过 App Kotlin 转成 `WritableMap`，再由 Promise 回到 React；TypeScript 不直接持有 Android `IBinder`。

先关闭 ADB，再检查得到同一个 PID，才证明此时用的是 Binder，而不是重新发了一条 ADB 命令。

<details>
<summary>接口边界：第 3 章的四个方法与调用者校验</summary>

AIDL 是两个进程之间的调用约定，不是新的执行阶段。第 3 章结束时，[IPrivilegedBackend.aidl](https://github.com/kkkhs/pocketrun/blob/chapter-3/app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl) 只有：

```aidl
interface IPrivilegedBackend {
    int getUid();
    int getPid();
    String ping();
    void shutdown();
}
```

这里故意没有 `execute(String command)`。第 4 章再增加参数固定的虚拟屏方法；后续能力都以边界明确的方法加入，不能绕过原生校验变成任意 shell 入口。

`PrivilegedBackendMain` 在每个方法前检查调用者，规则是：

```kotlin
if (Binder.getCallingUid() != appUid) {
  throw SecurityException("后端调用者 UID 不匹配。")
}
```

拿到 Binder 句柄仍不等于任何进程都可以使用它。

</details>

### 停止并处理死亡

“停止 shell 后端”调用 `stopBackend()`，经 [PrivilegedBackendController.stop()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt#L270) 发出 Binder `shutdown()`，等待进程退出。后端让主 Looper 结束，再收回资源；App 侧清空 proxy 和状态。

意外死亡时，Controller 的 `DeathRecipient` 将状态改回 stopped。启动等待被取消时，pending token 被清除；后端随后 exchange 只会失败并退出。

当前规则是：**App 进程死亡，shell 后端也退出。** 持久后端的重附着与升级后旧进程退出，会在故障恢复章节加入；这里不把“进程还在”误当作“任务可以安全恢复”。

## 顺着文件找一遍

```text
app/
├── BackendPanel.tsx                     按钮与 Promise 结果
├── specs/NativePocketRunAdb.ts          React → App Kotlin 的约定
└── android/app/src/main/
    ├── aidl/.../IPrivilegedBackend.aidl App Kotlin → 后端的约定
    ├── AndroidManifest.xml             导出 exchange Provider
    └── java/io/github/kkkhs/pocketrun/
        ├── AdbModule.kt                App 侧原生入口
        ├── PrivilegedBackendController.kt 启动、ping、stop 与死亡处理
        ├── BackendLaunchCommand.kt     固定启动命令
        ├── BinderExchangeProvider.kt   App 侧接收 Binder
        └── PrivilegedBackendMain.kt    独立后端入口和 Binder Stub
```

第 3 章继续复用 `PocketRunAdb` TurboModule，因为 ADB 连接和后端启动由同一条原生工作队列串行管理。Binder 句柄从不传给 TypeScript；前端只收到结构化状态。上面的代码链接指向当前文件；历史能力范围以文末 `chapter-3` 快照为准。

## 验证启动与退出

<details>
<summary>实践命令：构建与专用模拟器真实协议测试</summary>

在 `app/` 运行：

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

本章快照的真实协议测试只能运行在第 1 章创建的专用模拟器上。以下继续在 `app/android/` 执行，先核对目标，再安装：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name  # 必须是 pocketrun-ch1-api36
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.AdbPairingIntegrationTest \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试不会把配对码或 token 输出到主机。它真实验证 TLS 配对和连续 ADB stream、`app_process` UID 2000 与不同 PID、App UID 不能冒充 shell 调用 exchange、关闭 ADB 后 Binder ping、kill 后 Binder death、重启和正常 shutdown，以及 pending 启动立即取消。

</details>

2026-11-17 的结果：

- 9 个 React UI 测试、ADB JVM 测试和后端启动命令测试通过。
- ARM64 Debug、Release、AndroidTest APK 与 AIDL / Codegen 构建通过。
- 专用 Android 16 / API 36 模拟器设备测试为 `OK (2 tests)`。
- 真实产品界面完成“ADB 连接 → 启动 UID 2000 后端 → 断开 ADB → Binder ping 同一 PID → shutdown”。
- App 的 high-severity npm audit 门禁通过；6 个 `minimatch` moderate 传播条目仍按第 1、2 章记录。

源码检查与命令单元测试不能替代真实 Android 上的进程和 Binder 验证。这里记录的是本章快照与这台模拟器的结果，不代表所有设备都已验证；测试数量也不随 `main` 的后续扩展更新。

下一章让这个后端创建一块固定用途的 VirtualDisplay，把 Demo 放进去，再验证释放后旧 `displayId` 失效。

练习：按截图顺序操作一次，然后解释为什么“ADB 已断开”和“shell 后端已停止”是两个不同状态。

参考：[Binder 概览](https://source.android.com/docs/core/architecture/ipc/binder-overview)、[AIDL](https://developer.android.com/develop/background-work/services/aidl)、[ContentProvider.call](https://developer.android.com/reference/android/content/ContentProvider#call(java.lang.String,%20java.lang.String,%20android.os.Bundle))。

## 本章源码

- [PR #6：shell 后端、Binder 通信与第 3 章](https://github.com/kkkhs/pocketrun/pull/6)
- [PR #10：前序章节的任务主线与图解](https://github.com/kkkhs/pocketrun/pull/10)
- [Release `chapter-3`：本章结束时的源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-3)
