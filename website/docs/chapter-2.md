---
sidebar_position: 3
sidebar_label: 第 2 章：App 自有 ADB
title: 第 2 章：让 App 自己连接 ADB
description: 在 Android 无线调试中完成本机配对、连接、身份检查和断开。
---

import setupScreenshot from './assets/chapter-2-setup.png';
import connectedScreenshot from './assets/chapter-2-connected.png';
import AdbPairConnectFlow from './assets/adb-pair-connect-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

这一章完成一条真实链路：PocketRun 在系统无线调试中配对，连接本机 `adbd`，执行固定的 `id -u`，把 **shell UID 2000** 显示回 React 界面。

**主线位置：** 上一章的普通 App UID → **本章：ADB 启动钥匙** → 下一章：`app_process` shell 后端与 Binder。

普通 App 调多少次 Kotlin / JNI 都不会变成 shell。本章让 App 像电脑上的 `adb` 客户端一样，连接**同一台手机**的 `adbd`；电脑只用于开发安装，不在运行链路里。

这条连接的用途不是长期传每个动作，而是让下一章能启动 UID 2000 的独立后端。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>配对与连接入口</strong></figcaption>
    <img src={setupScreenshot} alt="PocketRun 无线 ADB 面板，分别输入配对端口、六位配对码和连接端口" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>连接验证成功</strong></figcaption>
    <img src={connectedScreenshot} alt="PocketRun 使用连接端口验证 ADB，界面显示连接端 UID 2000" width="360" />
  </figure>
</div>

截图来自本章的专用 Android 16 模拟器。端口由系统临时分配，你看到的数字通常不同；截图没有包含配对码。

本章只开放这一个身份检查，不提供任意命令。`app_process`、Binder 后端和受约束的系统工具从下一章开始。

## 两个端口做两件事

Android 无线调试把配对和连接分开：

| 系统界面 | PocketRun 输入 | 用途 |
| --- | --- | --- |
| “Pair device with pairing code” 弹窗 | 配对端口 + 六位配对码 | 让系统接受 PocketRun 的 ADB 公钥 |
| 无线调试主页的 IP 地址与端口 | 连接端口 | 使用已配对身份建立 ADB 连接 |

配对弹窗关闭后，配对码和配对端口会失效。连接端口也可能在无线调试重启后变化，所以不能把两者写死或互换。

## 一次配对和连接怎么走

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="ADB 配对与连接流程图，可横向查看">
    <AdbPairConnectFlow className={`pr-adb-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-adb-title" aria-describedby="pr-adb-desc" style={{display: 'block', width: '100%', minWidth: 700, height: 'auto'}} />
  </div>
  <figcaption>配对保存公钥，连接建立执行通道。两条路径都固定访问本机 127.0.0.1。</figcaption>
</figure>

这里有两个不同身份：

- PocketRun 的 React、Kotlin 和密钥存储仍在普通 **App UID** 下运行。
- 固定命令由系统 `adbd` 创建的流执行，本次模拟器返回 **shell UID 2000**。

ADB 连接没有把 App 进程变成 shell。下一章会在这条连接上启动独立后端，再通过受约束的 IPC 使用它。

## 加入最小调用面

本章新增的主要文件：

```text
app/
├── AdbPanel.tsx
├── specs/NativePocketRunAdb.ts
└── android/
    ├── adb/                         # 可嵌入的配对与 ADB 客户端
    └── app/src/main/java/io/github/kkkhs/pocketrun/
        └── AdbModule.kt             # 状态、超时、取消和固定探测命令
```

### 第 2 章只声明五个动作

`NativePocketRunAdb.ts` 在本章先加入下面五个动作；第 3 章会在同一模块上继续增加“启动后端”方法：

```ts
export interface Spec extends TurboModule {
  pair(port: number, code: string): Promise<void>;
  connect(port: number): Promise<number>;
  probe(): Promise<number>;
  disconnect(): Promise<void>;
  getStatus(): Promise<AdbStatus>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunAdb');
```

`DeviceInfoPackage` 继续作为模块目录，同时提供第 1 章的 `PocketRunDevice` 和本章的 `PocketRunAdb`。注册、按名查找和 Codegen 的关系与第 1 章相同。

`AdbPanel.tsx` 分别保存两个端口和配对码。操作开始后禁用冲突按钮；配对码无论成功或失败都会从 React state 清空。

### Kotlin 拥有连接

网络、密钥和生命周期不放在 React state。`AdbModule` 使用一条工作线程保存当前操作和当前连接：

```kotlin
private val worker = Executors.newSingleThreadExecutor()
private var active: Operation? = null
private var connection: AdbClient? = null

val deadline = timer.schedule({
  synchronized(lock) {
    if (active === operation) operation.transport.cancel()
  }
}, 15, TimeUnit.SECONDS)
```

每个入口都会再次校验 Android 版本、整数端口、六位配对码和当前状态。前端按钮禁用只改善交互，不代替原生边界检查。

连接成功后，模块只发送：

```kotlin
client.shellCommand("id -u") { chunk ->
  require(output.size() + chunk.size <= 1024)
  output.write(chunk)
}
```

输出限制为 1024 字节，并且必须能解析成非负 UID。没有把命令字符串暴露给 JavaScript。

## 嵌入经过固定的 ADB 源码

`android/adb/` 使用 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 的 Android ADB 客户端子集，固定在提交 [`b844bc4`](https://github.com/RikkaApps/Shizuku/tree/b844bc491f1790c72328e1a8e5b2349f8978f0ea)。它直接编进 PocketRun，不要求用户另外安装 Shizuku App。

保留的协议路径很小：

- Kotlin 负责 ADB 消息、TLS、密钥和套接字。
- C++ / BoringSSL 负责配对所需的 SPAKE2 与加密上下文。
- Conscrypt 提供 TLS 1.3 和 exporter；Bouncy Castle 生成客户端证书。

本地修改集中在边界上：连接和读取共用 15 秒截止时间；取消只关闭套接字；创建配对 JNI 上下文的工作线程负责销毁它；ADB 包长度和命令输出都有上限；日志不包含密钥、配对码或协议 payload。

完整来源、改动和许可证位于 `app/android/adb/UPSTREAM.md`、`LICENSE` 与 `src/main/assets/third-party/`。对应 notice 也会打进 APK。

### 身份怎样保存

第一次配对时，App 生成 2048 位软件 RSA 身份。私钥先用 Android Keystore 中的 AES-256-GCM 密钥加密，再写入 App 私有的 SharedPreferences。

后续连接复用同一 RSA 身份。已有密文损坏时会明确失败，不会静默换一把新密钥，让系统授权和 App 身份悄悄失配。

六位配对码只参与本次配对交换，不写入偏好设置或日志。系统服务的临时 TLS 证书按所选上游实现接受；配对码认证本次交换，PocketRun 不把它描述成持久服务端证书固定。

## 取消必须停掉底层工作

React 使用递增序号忽略取消后才返回的旧 Promise，但这只能保护界面状态。

真正的取消发生在 Kotlin：关闭当前 transport，让阻塞的连接、TLS 握手或读取立即返回。单线程队列保证只有创建 `PairingContext` 的工作线程销毁 JNI 状态，避免“取消线程释放、工作线程仍在使用”的竞态。

`取消 / 断开` 会关闭 PocketRun 的当前连接。Android 已保存的配对授权仍由系统设置管理；需要撤销时，在无线调试的已配对设备列表中操作。

## 在自己的设备上跑通

只在自己拥有或明确获授权的 Android 11 及以上设备上练习。打开“开发者选项 → 无线调试”，保持该页面可见。

1. 点系统的“使用配对码配对设备”。
2. 把弹窗中的端口和六位码填入 PocketRun，点“配对”。不要截图、记录或复用配对码。
3. 回到无线调试主页，把 IP 地址后的端口填入“连接端口”。
4. 点“连接”，结果应为 `UID：2000`；再点“检查连接”，确认保留的连接还能打开第二条流。
5. 点“取消 / 断开”，结果应变成“连接已关闭”。

错误配对码、过期端口、关闭无线调试和超时都应返回错误，而不是一直停在忙碌状态。

## 构建与检查

在 `app/` 运行源码检查：

```bash
npm run typecheck
npm run lint
npm test -- --runInBand
npm audit --audit-level=high --registry=https://registry.npmjs.org
```

再构建 ADB 单元测试、App 和设备测试 APK：

```bash
cd android
./gradlew :adb:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

`AdbTransportTest` 使用本机测试套接字验证读取截止时间、取消解阻塞、重复关闭和协议消息边界，不需要 Android 配对。

### 可选：在专用模拟器跑真实协议测试

设备测试会打开系统设置并新增 ADB 配对，只能在第 1 章创建的专用模拟器上运行。测试自身也会核对 AVD 名为 `pocketrun-ch1-api36`，不会接受真机或其他模拟器。

先逐个确认安装目标，再显式指定序列号：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.AdbPairingIntegrationTest \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
```

测试在设备进程内读取当次六位码，不把它输出或传回主机。测试身份使用独立的 App 私有存储；它通过真实 TLS 配对、连接，并在同一连接上连续两次检查 `id -u == 2000`，最后关闭资源。

## 本章验证结果

2026-11-17 的检查结果：

- TypeScript、ESLint 和 ADB 面板 UI 测试通过。
- JVM 套接字生命周期测试通过；ARM64 Debug、Release 和 AndroidTest APK 构建通过。
- APK 与 `libpocketrun_adb.so` 通过 16 KB 对齐检查；五份第三方许可证 / notice 已打包。
- 专用 Android 16 / API 36 模拟器完成真实系统配对、TLS 连接和连续两次 shell UID 检查，设备测试为 `OK (1 test)`。
- 生产 `AdbModule` 与 React 界面完成连接、再次检查和断开；结果均为 UID 2000，配对码未写入主机输出或截图。
- App 的高危依赖审计门禁通过；当前仍报告 6 个由 `minimatch` 传播的 moderate 条目，因此不是零告警。

测试覆盖协议实现和界面状态，但不会替代授权边界。当前只有固定探测命令；下一章再把 shell 后端与普通 App 之间的通信接起来。

练习：在代码中分别找到配对端口、连接端口、普通 App UID 和 shell UID 的产生位置，再说明“断开连接”和“撤销配对”为什么不是同一动作。

参考：[Android 无线调试](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)、[ADB 协议](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/OVERVIEW.TXT)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

## 本章代码

- [PR #5：App 自有 ADB 配对、连接与第 2 章](https://github.com/kkkhs/pocketrun/pull/5)
