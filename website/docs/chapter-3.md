---
sidebar_position: 4
sidebar_label: 第 3 章：shell 后端与 Binder
title: 第 3 章：启动 shell 后端
description: 用 ADB 启动 app_process，再通过带鉴权的 Binder 与 UID 2000 后端通信。
---

import beforeScreenshot from './assets/chapter-3-before.png';
import binderScreenshot from './assets/chapter-3-binder.png';
import BackendBootstrapFlow from './assets/backend-bootstrap-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

这一章把第 2 章的 ADB 连接真正用起来：**ADB 启动一次后端，后续操作改走 Binder。**

**主线位置：** 普通 App UID → ADB 启动钥匙 → **本章：UID 2000 后端 + Binder** → 下一章：让后端创建虚拟屏。

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

## 先看最终关系

```text
启动时：PocketRun → ADB → adbd → app_process（UID 2000）
运行时：PocketRun ←────── Binder ──────→ app_process
```

第 2 章若一直用 `adb shell <命令>`，每个动作都要重新拼字符串、解析文本，也很难形成稳定的资源生命周期。

本章改为启动自己的 Java / Kotlin 后端。以后创建的虚拟屏、截图和输入资源都能由这个进程持有，再通过 typed Binder 方法调用。

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="ADB 启动 app_process 并交换 Binder 的流程图，可横向查看">
    <BackendBootstrapFlow className={`pr-backend-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-backend-title" aria-describedby="pr-backend-desc" style={{display: 'block', width: '100%', minWidth: 720, height: 'auto'}} />
  </div>
  <figcaption>ADB 是启动钥匙；一次性交换完成后，日常通信只走 Binder。</figcaption>
</figure>

## 先在界面跑一遍

前提是已经按第 2 章完成配对，并连接无线调试主页的连接端口。

1. 点“启动 shell 后端”。结果应显示 `Binder pong`、UID 2000 和一个 PID。
2. 点第 2 章的“取消 / 断开”，关闭 PocketRun 的 ADB transport。
3. 回到第 3 章点“检查 Binder 后端”。PID 应保持不变。
4. 点“停止 shell 后端”。按钮回到只能启动的状态。

这四步分别证明：后端真实启动、身份正确、Binder 不依赖 ADB 连接、资源能够主动关闭。

## ADB 只发送固定启动命令

`BackendLaunchCommand.kt` 生成的结构固定为：

```sh
export CLASSPATH='<当前 APK>'
(
  exec /system/bin/setsid /system/bin/app_process /system/bin \
    --nice-name=pocketrun:backend \
    io.github.kkkhs.pocketrun.PrivilegedBackendMain \
    --token '<一次性 token>' \
    --authority 'io.github.kkkhs.pocketrun.binder_exchange' \
    --app-uid '<当前 App UID>' --user '0'
) </dev/null >/dev/null 2>&1 &
```

- `CLASSPATH` 让 `app_process` 从 PocketRun APK 加载后端类。
- `setsid` 和标准流重定向让后端脱离本次 ADB shell stream。
- 命令没有接收 JavaScript 或用户输入；APK 路径与参数在 Kotlin 中校验并引用。
- token 由 `SecureRandom` 生成，只在 10 秒交换窗口内存活，不写入日志或存储。

shell 返回只说明进程还活着。PocketRun 必须真正收到 Binder，并调用 `getUid()` 得到 2000，才把启动判为成功。

## 后端只暴露四个 Binder 方法

AIDL 是 Android 对 Binder 接口的声明。`IPrivilegedBackend.aidl` 目前只有：

```aidl
interface IPrivilegedBackend {
    int getUid();
    int getPid();
    String ping();
    void shutdown();
}
```

这里故意没有 `execute(String command)`。后续能力会以参数明确的方法加入，不能绕过原生校验变成任意 shell 入口。

`PrivilegedBackendMain` 在每个方法前检查：

```kotlin
if (Binder.getCallingUid() != appUid) {
  throw SecurityException("后端调用者 UID 不匹配。")
}
```

拿到 Binder 句柄仍不等于任何进程都可以使用它。

## Binder 怎样回到 App

两个进程不能把 Kotlin 对象直接互相传递。PocketRun 注册一个用途单一的 `BinderExchangeProvider`：

1. App 生成 token，并开始等待。
2. shell 后端带 token 调用 Provider，把自己的 Binder 放进 `Bundle`。
3. Provider 检查调用 UID 必须是 2000、token 必须匹配且未过期、Binder descriptor 必须正确。
4. Provider 把 Binder 交给等待中的 App，并立即清空 token。
5. Provider 返回一个 App client Binder；后端用 `linkToDeath` 观察 App 是否消失。

Provider 虽然必须跨 UID 导出，但不提供 query / insert / update / delete。普通 App UID 调用 exchange 的真实设备测试会被拒绝。

<details>
<summary>为什么后端没有直接使用普通 ContentResolver？</summary>

`app_process` 是由 `adbd` 直接启动的 shell 进程，不是 ActivityManager 创建的普通 App 进程。它没有已经登记的 `Application` / `ActivityThread` 身份。

因此后端通过 Android 的 external-content-provider Binder API 获取目标 Provider，再使用 `com.android.shell` 的 AttributionSource 调用。Android 11 与 Android 12+ 的隐藏接口参数不同，代码按系统版本选择对应签名；本章设备实测的是 API 36 分支。

这层反射只负责取得本 App 的 exchange Provider；业务 Binder 接口本身仍由 AIDL 生成。

</details>

## 谁负责死亡和取消

`PrivilegedBackendController` 留在普通 App 进程中，持有：

- typed Binder proxy；
- App client Binder token；
- `DeathRecipient`；
- 最近一次 UID、PID 和 ping 结果。

后端意外死亡时，`DeathRecipient` 将状态改回 stopped。启动等待被取消时，pending token 会被清除；后端随后调用 exchange 只会失败并退出。

当前教学实现选择简单、可验证的规则：**App 进程死亡，shell 后端也退出。** 持久后端的重附着与升级后旧进程退出，会在故障恢复章节加入；这里不把“进程还在”误当作“任务可以安全恢复”。

## 关键文件

```text
app/
├── BackendPanel.tsx
├── specs/NativePocketRunAdb.ts
└── android/app/src/main/
    ├── aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl
    ├── AndroidManifest.xml
    └── java/io/github/kkkhs/pocketrun/
        ├── BackendLaunchCommand.kt
        ├── BinderExchangeProvider.kt
        ├── PrivilegedBackendController.kt
        ├── PrivilegedBackendMain.kt
        └── AdbModule.kt
```

第 3 章继续复用 `PocketRunAdb` TurboModule，因为 ADB 连接和后端启动必须由同一条原生工作队列串行管理。Binder 句柄从不传给 TypeScript；前端只收到结构化状态。

## 构建与验证

在 `app/` 运行：

```bash
npm run typecheck
npm run lint
npm test -- --runInBand
npm audit --audit-level=high --registry=https://registry.npmjs.org

cd android
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

真实协议测试只能运行在第 1 章创建的专用模拟器上：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name  # 必须是 pocketrun-ch1-api36
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.AdbPairingIntegrationTest \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
```

设备测试不会把配对码或 token 输出到主机。它真实验证：

- TLS 配对和连续 ADB stream；
- `app_process` UID 2000 与不同 PID；
- App UID 不能冒充 shell 调用 exchange；
- 关闭 ADB 后 Binder ping 仍成功；
- kill 后收到 Binder death；
- 重新启动和正常 shutdown；
- pending 启动可以立即取消。

## 本章验证结果

2026-11-17 的结果：

- 9 个 React UI 测试、ADB JVM 测试和后端启动命令测试通过。
- ARM64 Debug、Release、AndroidTest APK 与 AIDL / Codegen 构建通过。
- 专用 Android 16 / API 36 模拟器设备测试为 `OK (2 tests)`。
- 真实产品界面完成“ADB 连接 → 启动 UID 2000 后端 → 断开 ADB → Binder ping 同一 PID → shutdown”。
- App 的 high-severity npm audit 门禁通过；6 个 `minimatch` moderate 传播条目仍按第 1、2 章记录。

本章只完成 Android 权限后端和通信骨架。下一章会让该后端创建一个固定用途的 VirtualDisplay，并验证释放后旧 `displayId` 失效。

练习：按截图顺序操作一次，然后解释为什么“ADB 已断开”和“shell 后端已停止”是两个不同状态。

参考：[Binder 概览](https://source.android.com/docs/core/architecture/ipc/binder-overview)、[AIDL](https://developer.android.com/develop/background-work/services/aidl)、[ContentProvider.call](https://developer.android.com/reference/android/content/ContentProvider#call(java.lang.String,%20java.lang.String,%20android.os.Bundle))。

## 本章代码

- [PR #6：shell 后端、Binder 通信与第 3 章](https://github.com/kkkhs/pocketrun/pull/6)
