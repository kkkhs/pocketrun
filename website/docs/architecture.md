---
sidebar_position: 2
sidebar_label: 系统概览
title: 一次任务如何在手机上执行
description: 用一次任务理解 UI、Agent 与 Android / Linux 执行通道。
---

## 一次任务怎么走

沿用导读里的例子：读取 `items.csv`，整理数据，填入演示 App，把结果写到 `result.txt`。

第 1 章已跑通 React → Kotlin 的设备信息读取。下面的完整任务结构仍按后续章节逐步实现。

```text
用户任务 → UI → Agent → 执行层
                         ├─ Android：观察、启动、操作 App
                         └─ Linux：文件、命令
执行结果 → Agent / UI → 用户检查
```

Agent 决定下一步做什么；执行层检查请求并执行。**模型提出动作，不授予权限。**

## 三个角色做什么

| 角色 | 在这个任务中做什么 |
| --- | --- |
| React Native / TypeScript UI | 展示任务进度、确认提示、结果和取消入口 |
| Agent | 选择工具，依据文件与界面观察安排下一步 |
| 执行层 | 校验请求，调用 Android 能力或命令环境，返回结果 |

React 的组件和状态管理经验可以复用。Kotlin 承接原生调用；需要 PTY 等底层能力时，再经 JNI 调用。

## 手机上的两条通道

### Android：操作 App

应用通过自己的 ADB 连接，在设备用户确认调试授权后启动 shell 后端。量产设备上通常是 **shell UID 2000**，实际身份和能力仍要在设备上检查。

普通应用通过 Binder 请求后端执行受约束的操作，再接收结构化结果。

界面任务围绕 VirtualDisplay 和本次 `displayId` 组织：App 启动、输入、画面捕获与 UI 树读取都要对应目标显示，观察结果再交回 Agent。

### Linux：处理文件和命令

命令环境在普通应用 UID 下运行，承载 PRoot + Alpine 这类 Linux 用户态工具。工作目录限定在应用可访问的范围。

- 一次性非交互命令：使用 `ProcessBuilder` 管道，收集输出和退出码。
- 交互式 shell、编辑器：使用 PTY，提供终端输入输出和尺寸变化。

两条通道使用独立的工具入口。Linux 命令保持应用身份；系统操作交给授权后端。

## 先守住三个边界

### 身份与授权

Binder / JNI 传递调用，不改变客户端 UID。shell 后端仍受系统权限和 SELinux 限制。

执行层必须校验调用者、授权、工具名、参数和允许的路径或操作范围。校验失败就停止并返回错误。删除、提交等有副作用的动作还需要用户确认。

PRoot / `fakeroot` 只做用户态兼容，不授予 Android root，也不提供额外的安全沙箱；真正的权限边界来自应用 UID 和 Android 沙箱。

### 目标显示

启动、输入、画面和 UI 树必须指向同一个有效 `displayId`。显示销毁后，旧 ID 不能继续使用。

创建 VirtualDisplay 不等于获准操作任意 App。权限、系统版本和目标 App 的支持情况要逐项验证。

### 生命周期

连接、命令进程、PTY 和显示资源由 Kotlin 原生层管理。原生层承接后台会话、取消和后端死亡处理；UI 的 JS 状态不能保证任务存活。

结束或断连时，要关闭文件描述符、释放 VirtualDisplay 和 Surface。进程退出或设备重启后，重建会话前先确认授权与已完成动作，避免重复提交。

前台服务用于符合平台规则、用户可感知的持续任务，有通知和系统限制。**它不保证进程永生。**

练习：给示例的每一步标出执行身份、成功信号和取消动作。后续设备章节会用这些条件验证真实调用链。

<details>
<summary>命令环境与 Agent 宿主的更多细节</summary>

- 管道要及时消费 stdout / stderr，避免大量输出堵住进程；同时处理退出码、超时与取消。
- PTY 输出可能含终端控制序列，通常不能再独立区分 stdout / stderr；会话还要处理尺寸和句柄关闭。
- PRoot 使用 Android 内核，不是虚拟机。工具是否可用仍受 ABI、系统调用和用户态库兼容性限制。
- Termux 是为 Android 适配工具的另一条路线，包和目录假设不能与 Alpine 直接互换；复用方案还要核对支持方式和许可证。

Agent 是否需要独立 Node 进程、应用内 Linux 宿主或远端宿主，当前未定。React Native 的 JS 运行时不能直接当作 Node；后续会结合依赖、启动成本、凭据边界和恢复方式选择。

参考：[Android 沙箱](https://source.android.com/docs/security/app-sandbox)、[ADB](https://developer.android.com/tools/adb)、[Binder / AIDL](https://developer.android.com/develop/background-work/services/aidl)、[VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay)、[ProcessBuilder](https://developer.android.com/reference/java/lang/ProcessBuilder)、[前台服务](https://developer.android.com/develop/background-work/services/fgs)。

</details>
