---
sidebar_position: 2
sidebar_label: 系统全景
title: 系统全景：一个任务如何在手机上跑通
description: 在进入代码细节前，用一张图看清 PocketRun 的进程边界、两条执行通道和三个设计底线。
---

import ExecutionDomains from './assets/execution-domains.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

在动手写任何一行代码之前，最容易把人绕晕的往往不是某个 API 怎么调，而是**“这段代码到底跑在哪个进程里、用的是谁的权限、数据是怎么穿过去的”**。

这一页就是整套教程的地图。不需要现在记住所有类名，只要先建立起**两个进程、两条通道、三个底线**的心智模型，后面读第 1 到第 13 章时，你一眼就能知道每一块拼图插在哪里。

## 一张图看懂全部角色

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="PocketRun 模块与权限架构图">
    <ExecutionDomains className={`pr-domains-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-domains-title" aria-describedby="pr-domains-desc" style={{display: 'block', width: '100%', minWidth: 640, height: 'auto'}} />
  </div>
  <figcaption>上半部分是代码职责分工（都在普通 App 进程内），下半部分是两条真正干活的执行通道——分别使用普通 App UID 与 shell UID 2000。</figcaption>
</figure>

很多刚从 Web 转过来的开发者，第一眼会把图里的 **React UI / AgentLoop / Kotlin 执行层** 误解成三个独立服务（像前端、BFF 和后端那样）。在 PocketRun 里不是这样：

- **React Native（TypeScript）**、**Kotlin 原生模块**、**TaskHost** 以及 **AgentLoop**，全部运行在**同一个普通 Android App 进程**里，共享同一个普通 App UID（例如 `10150`）。
- 真正独立出去的进程只有两类：
  1. **shell 后端（`app_process`）**：由 App 通过本机无线 ADB 拉起，运行在 **shell UID 2000** 下，专门干普通 App 没权限干的“看屏幕、点屏幕”的活。
  2. **Linux 命令子进程（toybox / PRoot + BusyBox）**：由普通 App 直接 `fork`/`ProcessBuilder` 出来，**依然是普通 App UID**，专门处理 App 私有目录里的文件和命令。
- 至于**大模型推理本身**，跑在远端 HTTPS 接口（或本地调试网关）里——手机上运行的是官方 SDK、消息循环和工具宿主，并没有把几十 GB 的模型权重塞进手机。

## 三层协作，各自干什么

| 角色 | 所在位置 | 职责 | 前端类比 |
| --- | --- | --- | --- |
| **React Native UI** | App 进程（JS 层） | 展示界面、收用户输入、弹出“同意 / 拒绝”审批按钮、展示结果与取消入口 | 浏览器前端组件 |
| **AgentLoop + SingleTaskContext** | App 进程（Kotlin） | 管住多步任务账本，发请求给大模型，解析 `tool_use`，把工具摘要回传给模型 | 跑在本地的 Agent 编排器 |
| **TaskHost + 两条执行通道** | App Kotlin + 独立子进程/后端 | 校验工具参数、卡住高风险审批、调用 Android 虚拟屏或 Linux 环境、负责超时与清理 | 带权限网关的工具执行引擎 |

:::info 为什么把 AgentLoop 写在 Kotlin，而不是 TypeScript？
这是一个非常务实的工程选择，不是说“Agent 必须写 Kotlin”：
1. React Native 的 JS 引擎不是 Node.js，Anthropic 官方 TypeScript SDK 并不直接支持 RN，硬套需要引入一堆脆弱的 polyfill。
2. 工具宿主 `TaskHost`、进程取消、Keystore 凭据存储和原生超时原本就在 Kotlin 层；把循环也放在 Kotlin（使用官方 Java SDK），能让网络流取消、工具状态机和内存凭据留在同一套可控生命周期里，避免 JS 界面重载或休眠时把任务弄丢。
:::

## 手机上的两条执行通道

PocketRun 故意把“操作 Android 应用”和“处理文件/跑命令”拆成了两条完全独立的通道，**最小权限原则**是核心原因：读自己目录里的一个 CSV 文件，凭什么要惊动 UID 2000 的高权限后端？

### 通道一：操作 Android App（第 1—5 章）

普通 App 天生看不到别的窗口，也不能往别的窗口注入触摸事件。PocketRun 的解法借鉴了 Shizuku 等经典方案，分两步走：

```text
启动阶段：App Kotlin ──(本机无线 ADB)──▶ 系统 adbd ──▶ 拉起 app_process（UID 2000）
                                                            │
                                          通过 ContentProvider 把 Binder 递回 App
                                                            ▼
运行阶段：App Kotlin ◀─────────── Typed Binder（AIDL）───────────▶ shell 后端（UID 2000）
                                                                       │
                                                          VirtualDisplay / 截图 / UI 树 / 输入
```

这里有三个很容易搞混的点，提前记牢：
1. **ADB 只是“点火钥匙”，不是日常数据线**。后端一旦拉起来并把 Binder 句柄交回 App，哪怕立刻断开 ADB 连接，App 依然能通过 Binder 丝滑地截图、查树、发点击。
2. **用强类型 AIDL，绝不留一个 `execute(String cmd)` 后门**。跨进程接口只暴露明确的方法（创建演示虚拟屏、截图、查 UI 树、注入点击/文本、释放），每个方法入口都严格检查调用者 UID 必须是当前 App。
3. **一切操作锁死在虚拟屏上**。后端用 `ImageReader` 承接一块 720×1280 的无实体面板 `VirtualDisplay`，截图、查树、点击、打字全都必须带上同一个正数 `displayId`。

### 通道二：处理文件与运行 Linux 命令（第 6—8 章）

相比之下，文件处理和 Linux 命令完全不需要 UID 2000：

- **非交互命令（第 6—7 章）**：App 直接用 `ProcessBuilder` 启动子进程，先是 Android 自带的 `toybox`，接着升级成 `PRoot + Alpine 最小 BusyBox/musl 环境`。App 负责把私有目录映射成 Linux 里的 `/workspace`，通过字节管道收集 stdout、stderr 和退出码。
- **交互式终端（第 8 章）**：当你需要启动一个常驻的 shell，反复敲命令、调整窗口大小或按 `Ctrl-C` 打断 `sleep 30` 时，普通管道就不够用了。我们在 C++/JNI 里打开 `/dev/ptmx` 创建真正的伪终端（PTY），由 master/slave 两端承载合流输出、信号和终端尺寸。

记住一个反直觉的事实：**PRoot 既不是虚拟机，也不提供 Root 权限**。它只是在用户态拦截系统调用、帮程序“翻译路径”，让依赖 `/bin/busybox` 和 `/lib/ld-musl-aarch64.so.1` 的 Linux 程序以为自己住在标准 Linux 根目录里——真正的内核依然是手机自己的 Android/Linux 内核，权限依然是普通 App UID。

## 贯穿全书的三个工程底线

看懂架构之后，你会发现 PocketRun 代码里写了很多“看起来很较真”的校验逻辑。它们不是为了显得代码复杂，而是移动端 Agent 一旦踩坑就会直接翻车的三条生死线：

### 1. 身份与审批边界：模型只提议，不授权
大模型收到界面内容、网页文本或文件内容后，随时可能遭遇提示词注入（Prompt Injection）或者产生幻觉。所以架构上死守一条红线：**模型只能提出“我想调哪个工具、传什么参数”，绝不能自己批准执行**。

所有工具调用都会进入 `TaskHost`：
- 先过白名单与严格 Schema 校验（多一个多余字段直接拒绝）；
- 绑定当前具体的目标（哪个 App UID、哪一代 rootfs、哪个 `displayId` 和 `displayEpoch`）；
- 有副作用的操作（读 CSV、点击虚拟屏输入）一律停在 `PENDING_APPROVAL`，生成一次性 `requestNonce`，必须由用户在手机界面亲手点击同意。

### 2. 目标显示边界：绝不向主屏降级
主屏的 `displayId` 永远是 `0`，虚拟屏则是系统分配的正数（比如 `10`、`25`）。

想象一个场景：Agent 正准备往虚拟屏的输入框里点一下，恰巧虚拟屏在上一毫秒被释放了。如果代码写了个“找不到虚拟屏就默认发给主屏 0”的容错，这一击就会直接点在用户正在使用的主屏上。因此在 PocketRun 里，**所有显示操作必须显式携带正数 `expectedDisplayId`，不匹配或已释放就立刻报错终止，绝不做主屏 fallback**。

### 3. 资源与生命周期边界：原生层说了算
React 组件随时可能卸载、重新渲染，甚至把 state 清空——但**把 React state 设成 `null`，底层的 VirtualDisplay、子进程、文件描述符（FD）并不会自己消失**。

因此在 PocketRun 里，所有原生资源（ADB socket、Binder 代理、VirtualDisplay、PRoot 子进程、PTY master FD）都由 Kotlin/C++ 层持有所有权，统一负责单调时钟超时、取消回收和死亡监听。如果某次清理因为异常没能确认完成，宿主会标为 `UNPROVEN` 并锁住执行槽，迫使显式重试清理，而不是假装无事发生继续开新任务。

---

带着这张全景图，我们正式进入第一部分——从 [第 1 章：让界面调用 Kotlin](chapter-1.md) 开始，敲下第一条跨越 TypeScript 和 Android 原生层的调用链。
