---
sidebar_position: 3
sidebar_label: 第 3 章 · shell 后端与 Binder
title: 第 3 章：启动独立的 shell 后端，改用 Binder 通信
description: 用本机 ADB 拉起一个 UID 2000 的独立 app_process 进程，通过一次性 ContentProvider 交换把 Binder 句柄递回 App，之后断开 ADB 也能继续调用。
---

import beforeScreenshot from './assets/chapter-3-before.png';
import binderScreenshot from './assets/chapter-3-binder.png';
import BackendBootstrapFlow from './assets/backend-bootstrap-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章我们拿到了一条能以 UID 2000 跑命令的本机 ADB 连接。听起来好像已经够用了——可一旦开始琢磨后面要做的事，你马上会发现靠“发 shell 命令”根本行不通：

- **没法常驻对象**：下一章我们要创建一块 `VirtualDisplay` 和一个 `ImageReader`，这些都是活在内存里的 Android Java 对象，一条 `adb shell` 命令跑完进程就退了，对象根本留不住。
- **没法高效传二进制和结构化数据**：后面每次截图都是几百 KB 的 JPEG，还有复杂的 UI 控件树，靠拼命令行参数、解析 stdout 文本既慢又脆弱。

怎么破局？答案是 Android 圈最精妙的架构之一：**只用 ADB 发最后一条命令——让系统的 `app_process` 直接加载我们自己 APK 里的 Kotlin 类，启动一个独立的常驻后台进程（继承 UID 2000）！然后让它把自己的 Binder 接口“递回”给普通 App。**

一旦 Binder 握手成功，ADB 的历史使命就结束了。你甚至可以立刻断开 ADB 连接，App 依然能通过 Android 原生的 **typed Binder（AIDL）** 直接调这个 UID 2000 进程里的方法，就像调用本地接口一样快、一样安全。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>启动前</strong></figcaption>
    <img src={beforeScreenshot} alt="PocketRun 的 shell 后端面板，启动按钮可用，Binder 检查和停止按钮尚未启用" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>断开 ADB 后，通过 Binder 依然 ping 通同一个 PID</strong></figcaption>
    <img src={binderScreenshot} alt="PocketRun 通过 Binder 收到 pong，并显示后端 UID 2000 与 PID" width="360" />
  </figure>
</div>

## 先分清两个进程、两条通信链

到这里，PocketRun 正式从**单进程**演进成了**双进程协作**：

| 角色 | 所在进程 | UID | 职责 |
| --- | --- | --- | --- |
| **React UI**（`BackendPanel.tsx`） | PocketRun 主进程 | 普通 App UID | 按钮交互、显示状态与 PID |
| **App Kotlin**（`AdbModule` + `PrivilegedBackendController`） | PocketRun 主进程 | 普通 App UID | 通过 ADB 点火、持有 Binder 代理、监听后端死亡 |
| **shell 后端**（`PrivilegedBackendMain`） | 独立 `app_process` 进程 | **shell UID 2000** | 暴露强类型 Binder 服务，承接系统级操作 |

记住这两条完全不同的通路：

```text
启动时（只用一次）：App Kotlin ──(本机 ADB)──▶ adbd ──▶ 启动 app_process（UID 2000）
运行时（日常调用）：App Kotlin ◀─────── Typed Binder (AIDL) ───────▶ 同一个后端进程
```

## 四步完成“启动 → 交接 → 脱钩”

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="启动后端、交换 Binder、检查与停止的阶段顺序">
    <BackendBootstrapFlow className={`pr-backend-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-backend-title" aria-describedby="pr-backend-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>先准备好交换窗口，再通过 ADB 拉起进程并收回 Binder 句柄；之后即可断开 ADB，用 Binder 检查和停止后端。</figcaption>
</figure>

每一步背后都有一个很讲究的设计，我们拆开看：

### 1. 先张网，再放鹰：准备一次性交换窗口

你可能会问：两个完全独立的进程，刚拉起来的 `app_process` 怎么把它的 `IBinder` 对象交给普通 App 呢？写文件？不行，Binder 是内核管理的跨进程对象引用，没法序列化成文本写进磁盘！

Android 里最稳妥的跨进程“递句柄”方式，是在 App 里声明一个用途单一的 `ContentProvider`（[BinderExchangeProvider.kt](https://github.com/kkkhs/pocketrun/blob/chapter-3/app/android/app/src/main/java/io/github/kkkhs/pocketrun/BinderExchangeProvider.kt)），借助它的 `call(method, arg, extras: Bundle)`——因为 Android 的 `Bundle` 支持 `putBinder()`，内核会自动把 Binder 引用跨进程传递过去！

前端类比的话，这就像**主进程先开好一个带一次性临时密钥的本地回调端点，等子进程启动后主动把连接句柄 POST 回来注册**。

因此在发 ADB 命令之前，[PrivilegedBackendController.start()](https://github.com/kkkhs/pocketrun/blob/chapter-3/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt) 先生成一把 32 字节随机一次性 token 和一个 `appToken`（用来让后端监听 App 存活），调用 `BinderExchangeRegistry.prepare(token, appToken)` 创建一个 10 秒有效期的 `CompletableFuture`，**先把网张好，再发启动命令**，防止后端启动太快导致回调扑空。

### 2. 用 `app_process` 从 APK 里直接跑起 Kotlin

接下来，[BackendLaunchCommand.build()](https://github.com/kkkhs/pocketrun/blob/chapter-3/app/android/app/src/main/java/io/github/kkkhs/pocketrun/BackendLaunchCommand.kt) 构造出唯一一条固定的启动命令（所有参数严格 shell 转义，绝不拼接来自 JS 的任意字符串）：

```sh title="BackendLaunchCommand 构造的启动命令（示意）"
export CLASSPATH='/data/app/.../base.apk'
(
  exec /system/bin/setsid /system/bin/app_process /system/bin \
    --nice-name=pocketrun:backend \
    io.github.kkkhs.pocketrun.PrivilegedBackendMain \
    --token '<32字节随机token>' \
    --authority 'io.github.kkkhs.pocketrun.binder_exchange' \
    --app-uid '<当前App UID>' --user '0'
) </dev/null >/dev/null 2>&1 &
```

这条命令里藏着三个非常经典的 Linux/Android 技巧：
- **`CLASSPATH` 直接指向自己的 APK**：Android 的 APK 本质上就是打包了 `classes.dex` 的压缩包，`app_process` 可以直接用它启动一个独立的 ART 虚拟机，跑里面的 `main()` 函数——**完全不需要单独编译第二个可执行文件！**
- **`setsid` + 重定向标准流 + `&`**：让新进程脱离当前的 ADB shell 会话。这样后面我们断开 ADB 连接时，系统不会顺手把后台进程给杀掉。
- **短暂探活标记**：命令最后会等 1 秒检查 `kill -0 "$backend_pid"` 并输出 `pocketrun-backend-launched`，但这只是初步信号；Controller 只有真正收到 Binder 并调通 `getUid() == 2000`，才认定启动成功。

### 3. 双向握手与双向死亡监听

新启动的 [PrivilegedBackendMain.main()](https://github.com/kkkhs/pocketrun/blob/chapter-3/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendMain.kt) 首先检查 `Process.myUid() == Process.SHELL_UID`（2000），接着初始化主 Looper、实例化 `BackendBinder`，然后通过系统的外部 ContentProvider 接口调用我们 App 里的 `BinderExchangeProvider.call()`，把 `token` 和 `backend.asBinder()` 递过去。

App 这边的 [BinderExchangeRegistry.exchange()](https://github.com/kkkhs/pocketrun/blob/chapter-3/app/android/app/src/main/java/io/github/kkkhs/pocketrun/BinderExchangeProvider.kt) 会同时卡住四道闸门：

```kotlin title="BinderExchangeProvider.kt（交换校验核心）"
fun exchange(callingUid: Int, token: String, backend: IBinder): IBinder {
    if (callingUid != Process.SHELL_UID) throw SecurityException("Binder exchange 被拒绝。")
    // ... 检查 10 秒窗口是否过期 ...
    if (!MessageDigest.isEqual(current.token, requestToken) ||
        backend.interfaceDescriptor != IPrivilegedBackend.DESCRIPTOR
    ) {
        throw SecurityException("Binder exchange 被拒绝。")
    }
    pending = null                 // 一次性消耗，立即作废
    current.token.fill(0)          // 擦除内存中的 token
    current.future.complete(backend)
    return current.client          // 同时把 App 的 client Binder 交回后端
}
```

为什么要做“双向交换”？因为交换完成后，**两边各自调用了 `linkToDeath()`**：
- **App 盯住后端**：如果后端被系统杀掉或崩溃，App 侧的 `DeathRecipient` 立刻触发，自动清空代理并更新状态。
- **后端盯住 App**：如果用户把 PocketRun App 滑掉杀死了，后端立刻收到死亡回调并主动 `Looper.quitSafely()` 退出，绝不在手机后台留下僵尸孤儿进程！

<details>
<summary>深入一点：裸 app_process 没有 Context，怎么调 ContentProvider？</summary>

普通 App 进程是系统 `ActivityManager` 启动的，天生自带 `Application` 和 `Context`。但 `app_process` 是 `adbd` 直接拉起的“裸”进程，手头没有 `Context`。

因此 `PrivilegedBackendMain.callProvider()` 通过反射调用系统隐藏接口 `ActivityManager.getService().getContentProviderExternal(authority, userId, externalToken, tag)` 获取 Provider 句柄，再构造一个声明为 `com.android.shell` 的 `AttributionSource`（Android 12+）调用 `call()`，最后在 `finally` 里调 `removeContentProviderExternal` 归还引用。这层反射只用来完成这“临门一脚”的握手，握手之后的所有业务调用全部走标准 AIDL。

</details>

### 4. 用强类型 AIDL 守住特权边界

拿到 `IBinder` 后，Controller 用 `IPrivilegedBackend.Stub.asInterface(binder)` 把它包装成强类型接口。第 3 章结束时的 [IPrivilegedBackend.aidl](https://github.com/kkkhs/pocketrun/blob/chapter-3/app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl) 极其克制：

```java title="IPrivilegedBackend.aidl（第 3 章接口）"
package io.github.kkkhs.pocketrun;

interface IPrivilegedBackend {
    int getUid();
    int getPid();
    String ping();
    void shutdown();
}
```

这里故意**没有** `execute(String command)`。哪怕将来扩展虚拟屏、截图、查树、输入，也会把每个能力收口成一个参数受控的强类型方法。

而且在后端的每一个 Binder 方法入口，都有一道必不可少的防线——检查调用者是不是当初启动它的那个 App：

```kotlin title="PrivilegedBackendMain.kt（身份校验与权限切换）"
private fun enforceClient() {
    val caller = Binder.getCallingUid()
    if (caller != appUid) {
        throw SecurityException("后端调用者 UID 不匹配。")
    }
}

private inline fun <T> asShell(action: () -> T): T {
    val identity = Binder.clearCallingIdentity()
    try {
        check(Process.myUid() == Process.SHELL_UID) { "后端不是 shell UID 2000。" }
        return action()
    } finally {
        Binder.restoreCallingIdentity(identity)
    }
}
```

:::tip 一个超级经典的 Android Binder 知识点：`clearCallingIdentity()`
在 Android 的 Binder IPC 机制里，当普通 App 调用后端方法时，系统会把当前线程的“调用者身份”记成 **App 的 UID**。因此：
1. 第一时间调 `enforceClient()`，用 `Binder.getCallingUid()` 拦截任何其他恶意 App 的越权调用；
2. 校验通过后，在后续章节调用系统服务（比如创建虚拟屏或注入输入）前，必须用 `Binder.clearCallingIdentity()` 把线程身份临时切回后端自己的 **UID 2000**，用完再在 `finally` 里恢复——否则系统服务还会按普通 App 的低权限来拒绝你！
:::

## 动手跑一遍

先按[第 2 章](chapter-2.md)完成无线 ADB 连接，然后滚动到第 3 章的 **shell 后端** 面板：

1. 点击 **启动 shell 后端**，几秒内会看到返回 `Binder pong`、`UID：2000` 和一个后端 `PID`（记下这个数字）。
2. 回到第 2 章面板，点击 **取消 / 断开**，把 App 的 ADB 连接彻底关掉。
3. 回到第 3 章面板，点击 **通过 Binder 检查 shell 后端**——你会看到依然返回 `pong`，而且 **PID 和刚才一模一样**！这就铁证如山地证明：现在走的是纯 Binder 通信，跟 ADB 已经没关系了。
4. 最后点击 **停止 shell 后端**，后端收到 `shutdown()` 后优雅退出，面板回到初始状态。

## 本章小结与源码

到这里，PocketRun 的“特权底座”已经完全建好了：我们拥有了一个独立常驻、运行在 **UID 2000**、通过强类型 **AIDL Binder** 通信、并且自带双向死亡清理的后台服务。

从下一章开始，这个后端要真正开始干活了——先为我们的 AI 助手造一块**看不见的 720×1280 虚拟副屏**，把目标页面装进去。

- **本章源码快照**：[Release `chapter-3`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-3) · [PR #6](https://github.com/kkkhs/pocketrun/pull/6)
- **延伸参考**：[Android Binder 概览](https://source.android.com/docs/core/architecture/ipc/binder-overview) · [AIDL 官方文档](https://developer.android.com/develop/background-work/services/aidl) · [实机验收与工程档案](verification-records.md)
