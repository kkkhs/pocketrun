---
sidebar_position: 2
sidebar_label: 第 2 章 · App 自有无线 ADB
title: 第 2 章：让 App 自己连接无线 ADB
description: 揭秘无 Root 提权的第一步：把无线 ADB 客户端嵌进 App，直连本机 127.0.0.1 的 adbd，拿到 shell UID 2000 的启动入口。
---

import setupScreenshot from './assets/chapter-2-setup.png';
import connectedScreenshot from './assets/chapter-2-connected.png';
import AdbPairConnectFlow from './assets/adb-pair-connect-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章我们看到，不管 Kotlin 写得多花哨，普通 App 始终被锁在自己的沙箱 UID（比如 `10150`）里，碰不到别的 App 的窗口和输入事件。

但你平时用电脑连手机调试时，敲一条 `adb shell id -u`，得到的却是 **`2000`（`shell` 用户）**——这个身份由 Android 系统的 `adbd` 守护进程赋予，能截图、能读无障碍控件树、也能创建高权限的 `VirtualDisplay`。

问题来了：**手机总不能一直拖着一根 USB 线连着电脑跑吧？**

Android 11 引入的**无线调试（Wireless Debugging）**打开了一扇极为巧妙的窗：开启无线调试后，手机里的 `adbd` 会监听一个本机 TCP 端口。既然是 TCP 端口，那**凭什么只能电脑上的 `adb` 客户端去连它？手机上我们自己的 App，难道不能直接去连 `127.0.0.1` 吗？**

答案是：完全可以！这也是 Shizuku 等经典工具能够在**不 Root 手机**的情况下拿到 shell 能力的核心秘诀。

这一章我们的目标很明确：**把无线 ADB 客户端直接嵌进 PocketRun，让 App 自己和同一台手机的 `adbd` 完成配对、TLS 连接，执行固定的 `id -u`，并把 `UID：2000` 显示回 React 界面。**

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>无线 ADB 配对与连接面板</strong></figcaption>
    <img src={setupScreenshot} alt="PocketRun 无线 ADB 面板，分别输入配对端口、六位配对码和连接端口" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>连接成功，探测到 shell UID 2000</strong></figcaption>
    <img src={connectedScreenshot} alt="PocketRun 使用连接端口验证 ADB，界面显示连接端 UID 2000" width="360" />
  </figure>
</div>

## 配对和连接为什么是两件事？

打开 Android 的“开发者选项 → 无线调试”，你会发现系统给了**两个不同的端口**，很多第一次折腾无线 ADB 的人都会在这里填串：

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="系统授权、配对、TLS 连接与固定探测的阶段顺序">
    <AdbPairConnectFlow className={`pr-adb-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-adb-title" aria-describedby="pr-adb-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>配对负责把 App 的公钥交给系统信任；连接负责用这把受信任的密钥建立 TLS 通道，随后通过固定命令探测执行身份。</figcaption>
</figure>

你可以把这两步类比成 **SSH 配置免密登录**：

| 阶段 | 在系统哪里看端口 | 干什么用 | 类比 |
| --- | --- | --- | --- |
| **1. 配对（Pair）** | 点进“使用配对码配对设备”弹窗里的端口 + 6 位配对码 | 用一次性 6 位码做认证，把 PocketRun 的 RSA 公钥登记到系统信任列表 | 把 `id_rsa.pub` 写进 `authorized_keys` |
| **2. 连接（Connect）** | 无线调试主页的“IP 地址和端口” | 用已经配对好的 RSA 密钥和 `adbd` 建立 TLS 1.3 加密连接 | `ssh localhost -p <port>` |

几个非常实用的细节：
- **配对码是一次性的**：弹窗一关，配对端口和 6 位码就立刻失效。而一旦配对成功，系统就记住了 PocketRun 的公钥，下次只要无线调试还开着，**直接用主页的连接端口就能连，不需要重新配对**。
- **断开连接 ≠ 撤销配对**：App 点“断开”只是关掉当前 TCP socket；要彻底撤销授权，得去系统“无线调试 → 已配对设备”列表里删除。
- **App 自己的身份始终没变**：连上 `adbd` 以后，PocketRun 自身依然是普通 App UID（例如 `10150`）；只是通过这条连接让 `adbd` 派生出来的 shell 进程，跑在 **UID 2000** 下。

## 把 ADB 客户端嵌进 App：不需要额外装 Shizuku

为了让读者装一个 APK 就能跑起来，我们在 `app/android/adb/` 独立出了一个精简的 `:adb` Gradle 子模块，直接复用 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 中经过实战检验的 Android ADB 客户端核心：

- **C++ / BoringSSL（`adb_pairing.cpp`）**：实现无线配对协议要求的 **SPAKE2** 密码认证密钥交换与加密上下文。
- **Conscrypt + Bouncy Castle**：提供 Android 无线 ADB 要求的 TLS 1.3（含 exporter）与客户端证书生成。
- **Kotlin 传输层**：封装 ADB 协议帧收发、socket 读写和命令流。

### App 的 ADB 密钥怎么存？

第一次配对时，`AdbModule` 会生成一把 2048 位的软件 RSA 密钥对。我们绝不能把私钥明文丢进文件里——私钥先由 **Android Keystore** 里托管的 AES-256-GCM 密钥加密，密文再存入 App 私有的 `SharedPreferences`（`pocketrun_adb_identity`）。

之后每次连接都复用这把身份；如果检测到本地密文损坏，会直接明确报错，绝不会静默重新生成一把新密钥——否则系统里存的还是旧公钥，连接会莫名其妙一直被拒。至于用户输入的 6 位配对码，只在当次内存交换时使用，无论成功或失败都会立刻从 React state 清空，且绝不打日志。

## 顺着代码看关键路径

本章新增的核心文件结构非常干净：

```text
app/
├── AdbPanel.tsx                         # 配对/连接表单与状态展示
├── specs/NativePocketRunAdb.ts          # TurboModule 接口定义
└── android/
    ├── adb/                             # 嵌入的无线 ADB 与配对子模块
    └── app/src/main/java/io/github/kkkhs/pocketrun/
        └── AdbModule.kt                 # 单线程队列、15 秒超时、取消与探测
```

### 1. TurboModule 接口：只暴露清晰动作

在 [NativePocketRunAdb.ts](https://github.com/kkkhs/pocketrun/blob/chapter-2/app/specs/NativePocketRunAdb.ts) 里，第 2 章只开放 5 个方法：

```ts title="app/specs/NativePocketRunAdb.ts（第 2 章接口）"
export interface Spec extends TurboModule {
  pair(port: number, code: string): Promise<void>;
  connect(port: number): Promise<number>;
  probe(): Promise<number>;
  disconnect(): Promise<void>;
  getStatus(): Promise<AdbStatus>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunAdb');
```

注意：**没有 `runShell(cmd: string)`！** 我们不把任意 shell 执行口直接敞开给 JS 层。第 2 章只做一件事：验证能不能连上 `adbd` 并拿到 UID 2000。

### 2. 原生状态机：单线程队列 + 15 秒总期限

网络 IO 和 JNI 加密上下文绝对不能在 UI 线程跑，也不能放任多个按钮点击并发抢同一个 socket。看看 [AdbModule.kt](https://github.com/kkkhs/pocketrun/blob/chapter-2/app/android/app/src/main/java/io/github/kkkhs/pocketrun/AdbModule.kt) 是怎么用两条极简规则把并发治得服服帖帖的：

```kotlin title="AdbModule.kt（队列与超时核心）"
private val worker = Executors.newSingleThreadExecutor()
private val timer = Executors.newSingleThreadScheduledExecutor()

// 每次发起操作：检查互斥状态，挂载 15 秒强制超时
val deadline = timer.schedule({
    synchronized(lock) {
        if (active === operation) {
            operation.timedOut = true
            operation.transport.cancel() // 直接关掉底层 socket，瞬间解阻塞
        }
    }
}, 15, TimeUnit.SECONDS)

worker.execute { runOperation(operation, code, promise, deadline) }
```

这段设计藏着两个很实用的并发技巧：
- **单线程管理 JNI 生命周期**：配对用的 C++ `PairingContext` 始终在 `worker` 这唯一一条线程里创建、使用和销毁；外部点取消或超时时，只去关底层 `transport` 的 socket 让阻塞读立刻抛错返回，绝不跨线程抢着释放 JNI 指针，从根源消灭 C++ UAF（释放后使用）崩溃。
- **全链路共用 15 秒截止时间**：连接、TLS 握手和命令读取共享同一份 15 秒超时，不会因为某个阶段卡死就把按钮永远冻在“加载中”。

### 3. 连接本机并探测身份

真正发起配对和连接的代码非常直观——目标主机写死为 **`127.0.0.1`**，且强制要求 TLS：

```kotlin title="AdbModule.kt（配对、连接与固定探测）"
// 配对：交换 6 位码并把 App 公钥登记给系统
AdbPairingClient("127.0.0.1", operation.port, code, key, operation.transport).use {
    if (!it.start()) throw AdbInvalidPairingCodeException()
}

// 连接：强制 TLS 1.3 直连本机 adbd
val client = AdbClient("127.0.0.1", operation.port, key, operation.transport).also {
    it.connect(requireTls = true)
}

// 探测：只发固定 id -u，限制输出最多 1024 字节
private fun probeUid(client: AdbClient): Int {
    val output = ByteArrayOutputStream()
    client.shellCommand("id -u") { chunk ->
        require(output.size() + chunk.size <= 1024) { "连接验证输出过大。" }
        output.write(chunk)
    }
    val uid = output.toString("UTF-8").trim().toIntOrNull()
    require(uid != null && uid >= 0) { "连接端未返回有效 UID。" }
    return uid
}
```

当 `connect(port)` 或 `probe()` 的 Promise 返回 `2000` 时，前端 `AdbPanel.tsx` 更新界面，我们也就正式拿到了通往 shell 世界的“点火钥匙”。

## 动手跑一遍

1. 在你的 Android 11+ 演示设备（推荐第 1 章建好的 API 36 模拟器）里打开 **设置 → 开发者选项 → 无线调试** 并开启。
2. 点击 **使用配对码配对设备**，记下弹窗里的 **配对端口** 和 **6 位配对码**（建议用系统分屏，一边开设置一边开 PocketRun，防止切后台弹窗关闭导致配对码失效）。
3. 切回 PocketRun 的无线 ADB 面板，填入配对端口和 6 位码，点击 **配对**。
4. 配对成功后回到无线调试主页，把“IP 地址和端口”里的端口号填入 **连接端口**，点击 **连接**。
5. 观察结果变为 **`UID：2000`**；再点 **检查连接**，验证复用同一条 TLS 连接能正常打开第二条流；最后点 **取消 / 断开**，确认连接干净关闭。

:::tip 排错小抄
- **配对一直失败**：90% 是因为切回 App 时系统的配对码弹窗自动关掉了（配对端口和 6 位码随弹窗关闭立即作废）。用分屏或悬浮通知查看即可。
- **连接报超时或拒绝**：检查是不是把“配对端口”误填进了“连接端口”，或者无线调试开关重启后系统换了新的连接端口。
:::

## 本章小结与源码

这一章我们没用 Root、也没依赖电脑，让普通 App 直接通过 `127.0.0.1` 连上了自己的 `adbd`，拿到了执行 **UID 2000** 命令的入口。

但现在手里只有一条发单次命令的 ADB 通道——如果要持续管理一块虚拟屏、高频截图、查 UI 树、注入触摸，总不能每次动作都通过 ADB 敲一遍命令行吧？

下一章，我们就用这把“点火钥匙”在手机上拉起一个**真正属于 PocketRun 自己的 UID 2000 后台进程**，然后优雅地把通信切换到 Android 原生最快的跨进程通道：**Binder**。

- **本章源码快照**：[Release `chapter-2`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-2) · [PR #5](https://github.com/kkkhs/pocketrun/pull/5)
- **延伸参考**：[Android 无线调试官方文档](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi) · [Android Keystore 系统](https://developer.android.com/privacy-and-security/keystore) · [实机验收与工程档案](verification-records.md)
