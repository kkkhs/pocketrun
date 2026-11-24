---
sidebar_position: 10
sidebar_label: 第 9 章 · 工具、审批与宿主
title: 申请读一次 CSV，等手机批准后再执行
description: 沿一次固定 CSV 请求，理解工具参数、目标绑定、手机批准、真实 Linux 执行和结果返回。
---

import csvResultScreenshot from './assets/chapter-9-csv-result.png';
import ToolApprovalFlow from './assets/tool-approval-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

[上一章](chapter-8.md)，读者可以在自己的终端输入命令。接 Agent 之前，还要解决另一个问题：**程序提出一个动作时，怎样确保它只在允许的目标上、得到必要批准后执行？**

这一章先不让模型选动作，只做一件事：**申请读取固定演示 CSV 的行数，在手机查看并批准这次请求，再核对它实际读出了什么。** 然后拒绝下一次相同请求，观察两次请求的区别。

```text
准备固定环境 → 申请读 CSV → 显示这次请求
                             ↓
                      手机同意 / 拒绝
                             ↓
             同意且目标仍有效：执行 → 结果 → 清理
             拒绝、过期或目标失效：停止
```

这里的“宿主”是 App Kotlin 中负责这段流程的程序，不是新增的服务器。**工具请求描述想做什么，手机批准决定这一次能否做；两者都不是执行结果。**

## 1. 先在手机上完成一次批准和一次拒绝

只用自己拥有或明确获授权的演示设备与本 App 私有 CSV。Linux 工具沿用第 7 章的 Android 14/API 34、ARM64 64 位 App 和固定最小环境。

打开“第 9 章 · 确定性工具宿主与手机审批”：

1. 点“准备固定 Linux 工具”，确认显示已校验的 Linux 安装版本和当前 App UID。
2. 点“申请读取固定演示 CSV 行数”。先停下来查看 `pending_approval`、`/workspace/items.csv`、安装版本与本次请求的信息。
3. 在手机当前请求面板点“同意当前请求”。等它执行，再检查行数、实际目录与身份、退出码和清理。
4. 再申请一次，点“拒绝当前请求”。这次应为 `rejected`，不会启动这一轮 CSV 读取。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={csvResultScreenshot} alt="实际手机批准后的三行 CSV 结果、运行来源和资源清理" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>手机批准当前请求后，真实 Linux 读取和清理结果回到页面。</figcaption>
</figure>

独立第 9 章包的实际正常结果是 `succeeded / approved_by_app_ui`：未修改 CSV 为 3 行，App/guest UID 10150、cwd `/workspace`，五阶段和 PRoot 退出码都为 0，来源完成、清理确认。

**准备环境与批准读取是不同动作。** 准备可能安装固定环境、校验或准备私有文件；拒绝当前读取不能被描述为“之前从未触碰文件系统”。

## 2. 谁执行：宿主增加规则，原能力仍做实际工作

| 负责者 | 在这次请求中做什么 | 进程与身份 |
| --- | --- | --- |
| React / TypeScript 手机 UI | 显示请求、同意/拒绝、持续观察结果 | 普通 App 进程 |
| App Kotlin 的 TaskHost | 校验参数、绑定目标、等待批准、持有本次资源 | 同一个 App 进程、App UID |
| AndroidTaskCapabilities | 把固定工具接到已有 LinuxSession | App Kotlin，不是新后端 |
| PRoot / BusyBox | 真实读取 CSV，返回输出与退出码 | 普通 App UID 下的子进程 |

对于本次 CSV，请求和结果都不走 UID 2000 后端。另一个虚拟屏工具才走 App 自有 Binder → shell 后端；它的例子放在文末延伸，不打断这次读文件任务。

从 React 经验看，TaskHost 类似给一个异步动作增加“允许调用哪些操作、何时可启动、什么时候可以结束”的管理层。但它还要持有真实进程资源，不只是管理一个 loading 状态。

手机审批是产品动作，不能把开发流程的自主决策当成自动同意，也不能由电脑入口代批敏感操作。本章只用任务专用演示数据检查手机 UI 流程；不宣称建立了硬件级可信 UI。

## 3. 提出请求：传工具名，不传 shell 命令

“申请读取固定演示 CSV 行数”按钮进入 [TaskHostPanel.submit()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/TaskHostPanel.tsx#L128-L147)。它先读取新鲜的就绪状态，Linux 分支只选择固定工具：

```ts
if (!fresh.linuxReady) throw new Error('固定 Linux 工具尚未准备。');
return ['linux.count_demo_csv', '{}'] as const;
```

随后同一个方法提交这组参数：

```ts
const next = await native.submitTool(call[0], call[1]);
return { state: next, readiness: fresh };
```

`'{}'` 是空 JSON 参数。这里没有让页面传 `items.csv` 路径、argv、环境或可执行文件；实际 CSV 由 Kotlin 固定选择。也不能附带 `approved: true`，因为批准根本不是工具参数。

[TaskHostModule.submitTool()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt#L50-L56) 先严格解析 JSON，再把 `RawToolCall` 交给宿主。JSON 语法正确不等于工具有效；运行时仍检查工具名、字段和类型。

[ToolContract.kt](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ToolContract.kt#L90-L99) 中，CSV 只接受空参数：

```kotlin
PocketRunTool.LINUX_COUNT_DEMO_CSV -> {
    exactKeys(copied, emptySet())
    ParsedAction.CountDemoCsv
}
```

未知工具、多余 path/argv/env、错误类型或超预算输入都在实际启动前被拒绝。TypeScript 类型帮助写代码，却不能替代 Kotlin 的信任边界检查。

## 4. 暂停执行：先把“这一次”请求显示出来

宿主校验工具后，取得已验证环境的快照，绑定 App UID、安装版本和固定 CSV。然后生成本次 `requestNonce` 和 request hash，进入等待手机的状态。[TaskHost.submit()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskHost.kt#L109-L115) 的 CSV 分支是：

```kotlin
if (action.approvalRequired) {
    request.approvalDeadlineNanos = deadline(clock.nowNanos(), approvalTtlNanos)
    status = view(request, HostPhase.PENDING_APPROVAL,
        deadline = request.approvalDeadlineNanos, actor = APP_PHONE_UI_ACTOR)
} else {
    launch = beginExecutionLocked(request)
}
```

CSV 的 `approvalRequired` 固定为 true，所以这里只创建请求，不启动本轮 Linux 读取。提交方法的 Promise 可以返回 `pending_approval`；它不是等待最终工具结果的 Promise。

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <ToolApprovalFlow className={`pr-tool-approval-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-tool-approval-title" aria-describedby="pr-tool-approval-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>这张图表示一次请求的阶段。只有手机同意、且本次目标和期限仍有效，才进入实际执行。</figcaption>
</figure>

可以把 nonce 理解成“这张申请单的编号”。同一工具再申请一次，也有新编号；第一次同意不能授权第二次。request hash 标识本次工具和绑定字段，不是一个可以替代手机批准的秘密。

绑定也没有把 CSV 内容冻结。它约束的是固定工具、文件位置和环境；读取时仍要检查实际文件，所以文件合法改变后结果不必一直是 `3`。

## 5. 在手机同意：批准后再检查目标

[手机同意按钮](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/TaskHostPanel.tsx#L254-L264) 传入当前状态里的 nonce，调用原来的手机决策入口：

```ts
perform(() => taskHostModule().decideFromAppUi(state.requestNonce!, 'approve'))
```

它没有再提交一遍工具，也没有让调用者选择审批角色。原生 [onAppUiDecision()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskHost.kt#L131-L149) 检查的是当前待审批请求：

```kotlin
if (status.phase != HostPhase.PENDING_APPROVAL || request.nonce != requestNonce ||
    request.approvalConsumed) return false
```

通过后消费这次决定，再进入执行检查。**为什么同意后还检查？** 因为等待期间环境可能换代、文件失效、请求被取消或期限已经过去。不能拿着一张旧申请单，在新的目标上继续运行。

默认等待批准是 30 秒，实际执行期限是 5 秒，使用单调时间。页面轮询用于显示，原生在决定、启动和完成时也检查；不依赖读者先点“刷新过期状态”才保护执行。

## 6. 真正读取：复用 LinuxSession，而不是生成一个数字

得到有效批准后，[TaskHost.launch()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskHost.kt#L282-L344) 选择 Linux 适配器，取得本次 `OwnedExecution`。宿主先保存 owner，再允许 `start()`，避免动作开始却无人负责取消和回收。

[AndroidTaskCapabilities.startLinux()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AndroidTaskCapabilities.kt#L158-L182) 在自己的执行队列里调用：

```kotlin
result = session.runDemo(operation)
linuxOutcome(checkNotNull(result))
```

这里的 `session` 就是第 7 章的真实 LinuxSession。版本、cwd、UID 和 `cat/wc` 都由固定 PRoot/BusyBox 环境读取，不是 JVM 直接填一个 `3`，也不是电脑 ADB 代 App 执行。

适配器把原输出变成带来源的工具结果。在 [linuxOutcome()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AndroidTaskCapabilities.kt#L326-L349) 中，成功条件包含：

```kotlin
val valid = result.status == "completed" && result.cleanupStatus == "done" &&
    result.lineCount != null && result.guestUid == result.appUid && result.cwd == "/workspace" &&
    result.exitCodes.size == 1 && result.exitCodes.all { it == 0 } &&
    result.stageExitCodes.size == 5 && result.stageExitCodes.all { it == 0 }
```

所以行数只是其中一个字段。外层和内部退出成功、身份和目录正确、来源清理确认，才能向宿主交付成功；失败仍保留已经取得的输出，不能用残留 stdout 盖住错误。

## 7. 接回页面：批准、执行、结果和清理分开看

执行回调先回到宿主，确认它仍属于同一个请求和执行代际；宿主完成收尾后更新状态。React [每 250ms 查询](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/TaskHostPanel.tsx#L87-L101)当前请求，不是自己算工具结果。

```ts
const [nextState, nextReadiness] = await Promise.all([
  NativeTaskHost.getTaskState(), NativeTaskHost.getHostReadiness(),
]);
```

原生 [stateMap()](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt#L124-L139) 返回任务阶段、批准状态、清理状态，以及字段构成的 `resultJson`。页面解析后，用普通 JSX 渲染实际行数：[结果行](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/TaskHostPanel.tsx#L274-L283)。

```tsx
{'lineCount' in result && <Text style={{ color: colors.text }}>固定 /workspace/items.csv 行数：{String(result.lineCount)}</Text>}
```

回到章首截图，应分别读懂这几项：

- `approved_by_app_ui`：手机批准了当前请求。
- `succeeded`：这次工具执行满足宿主的成功条件。
- `sourceStatus=completed`：真实 Linux 来源正常完成。
- `cleanupStatus=done`、宿主 `cleanupState=proven`：来源与宿主各自确认资源收尾。

完整返回路径是：**Linux 输出 → 适配器的 ToolResult → 宿主的当前状态 → 原生普通数据 → React 查询和展示。** 手机同意不是这些步骤的替代。

## 8. 拒绝下一次请求，再检查停止方式

再点一次申请，这次点“拒绝当前请求”。新 nonce 只属于这次请求；拒绝后状态为 `rejected`，不会创建本轮 CSV 执行资源。

取消与拒绝不同：取消可能发生在已经执行之后，宿主要关闭当前 `OwnedExecution`。如果失败后清理是 `unproven`，它保留 owner 和槽，只允许“重试资源清理”，不接受新工具覆盖旧请求。

**清理恢复成功，不会把原任务改成成功。** `CLEANUP_RECOVERED` 表示终于完成收尾，不代表之前的动作从头到尾成功。原输出、错误和失败结论仍保留。

练习：同一工具的第二次请求为什么要新 nonce？如果只看到 `approved_by_app_ui`，还需要哪几条结果才能确认读完 CSV？

这章仍只管理一个请求。下一章把设备观察和这次 CSV 读取放进一个有界任务，保留两条结果；再下一章才让真实模型选择工具。**请求来自哪里可以改变，参数校验、手机批准和资源规则不变。**

<details>
<summary>延伸：另外两个工具与目标虚拟屏</summary>

宿主固定识别三个工具，没有动态注册或任意 shell：

| 工具 | 能力与规则 |
| --- | --- |
| `android.device_info` | 读取当前 App 的 Android/API/ABI 与包身份，无需审批 |
| `linux.count_demo_csv` | 空参数，固定私有 CSV，每次手机批准 |
| `android.virtual_display_observe_or_demo_input` | 观察无需审批；受限演示输入需手机批准，必须匹配自有 displayId/epoch |

显示工具从 `TaskHostDisplayPortRegistry` 取得 App 自有 typed-Binder 端口。未就绪时 UI 和原生均明确拒绝，不回退显示 0，不把电脑已连接 ADB 当作 App 内配对、后端和端口就绪。正显示编号和当前 epoch 一起绑定；编号相同、会话版本不同也不能复用旧批准。

独立第 9 章实际 App 已完成 TLS ADB → UID2000 Binder → display25/epoch413，观察得到真实截图和 9 个节点；手机批准固定文字后，再次观察确认同一目标上的 `PocketRun_TaskHost_9`。输入事件接受与页面实际改变是两次检查，前章旧截图不能替代本章结果。

![手机批准演示文字后，再次观察同一虚拟屏和 UI 树](assets/chapter-9-display-result.png)

</details>

<details>
<summary>深入：JSON、请求绑定、期限与清理所有权</summary>

`TaskHostJson` 拒绝坏 JSON、重复键及超预算内容；工具 schema 再检查工具、字段和类型；能力快照与实际适配器再检查目标。`CanonicalChildPath.existingRegularFile()` 要求 canonical 工作目录和真实普通文件，拒绝绝对路径、逃逸和符号链接；CSV 没有外部路径参数也仍复查文件。

nonce 使用安全随机来源和请求序号，防止重复来源授权后续请求；request hash 采用字段和长度编码，绑定工具、UID、rootfs/display 版本与路径。手机决策仅接受 approve/reject 并消费当前 nonce，不接受外部 actor 或自动确认参数。批准绑定位置和环境，不冻结 CSV 内容。

默认 30 秒批准、5 秒执行；过期批准、执行前/完成时的晚结果都停止。`APPROVAL_EXPIRED` 与 `EXECUTION_TIMEOUT` 的原因不同，不能只看共同的 expired 阶段。状态和清理由核心维护，页面 polling 不制造成功。

Linux 来源使用 `done/unproven`，宿主使用 `none/pending/proven/unproven`。`ExecutionOutcome.Failure` 可以带真实结果用于诊断，不表示成功。关闭失败保留 owner；后端死亡回调需同时匹配 nonce 和 epoch，旧事件不能取消新请求。

</details>

<details>
<summary>验收记录：独立章节包、实际手机流程与失败边界</summary>

| 范围 | 已核实结果 |
| --- | --- |
| 原生工具集成 | `Chapter9TaskHostIntegrationTest` 共 4 项通过 |
| 设备信息、CSV、同意/拒绝/取消/过期 | 实际 App 操作完成；CSV 三行、UID10150、`/workspace`、五阶段 0、来源与清理确认 |
| 显示观察和演示输入 | 上述 App 自有 Binder 目标上的真实截图、9 节点及输入后重新观察 |
| 待批准时后端死亡 | 核对自有 UID/PID/startTicks 后终止后端，当前请求 `invalidated / BACKEND_DEATH` |
| 执行中取消与原五秒期限 | 手机批准后实际启动 PRoot，再由专属夹具暂停；取消为 `USER_CANCELLED`，期限为 `EXECUTION_TIMEOUT`，资源清理 proven |
| 关闭失败、旧回调与换代 | 核心回归及组合矩阵分别核对，旧结果不能覆盖新请求 |

2026-11-23 独立第 9 章 Debug APK SHA256 `911c97addec0e0bfaa5cadf00e7f8813df20d56b4934119efb6e95e931245b0e`；Test `e3bb6922c041cc8f1bfd9fdb78250b7e3d89ae9ad7efa990118bf67e344d3c3f`。原生四项、实际 UI 与进程/RUN 观察各有范围，不混成一份证据。

`ProductAdbBootstrapIntegrationTest` 通过，114.549s：真实按钮完成配对 → TLS UID2000 → Binder → 目标显示。系统分屏保持配对弹窗和 App 可见；最后创建显示前清空配对输入，由系统独立核对显示与 Demo 路由，不直接调用原生模块替代按钮。

实际 JS → Codegen → Kotlin 还逐项拒绝坏 JSON、重复键、未知工具、额外 path/argv/env、display0、越界坐标、非法文字和旧 epoch，`unexpected count=0`。这使用测试专属开发 JS bundle；正常 App/Release 不启用诊断入口，临时构建覆盖已移除，正常 APK 恢复。

![实际 JS 桥的固定参数拒绝与旧显示代际检查](assets/chapter-9-bridge-rejections.png)

</details>

<details>
<summary>验收细节：怎样证明取消发生在实际执行中</summary>

CSV 通常很快读完，没来得及取消不能算通过。`Chapter9ProductLinuxHoldTest` 先核对任务专用模拟器、UID、boot、APK SHA 和一次性私有回执，观察新 RUN，取得已启动 PRoot 的内核 pidfd。确认身份与停止状态后，真实手机 UI 取消或等待原五秒期限；夹具不批准请求、不生成结果。

结束还检查原进程身份消失、RUN 回收和实际 App 清理状态。Debug 薄 JNI 仅为测试提供 STOP/CONT 的内核句柄操作，Release 不带该导出，也没有裸 PID 信号重试降级。不同结果按实际原因保留，不把拒绝、没启动、自然退出或 unknown procfs 冒充执行中取消。

</details>

<details>
<summary>实践：逐层验证与固定源码阅读</summary>

按已有工具链执行 App typecheck、lint、Jest、audit，Android 相关 JVM 和 ARM64 Debug/Release/AndroidTest 构建，再核对 native、对齐、许可证与源码范围。实际设备先明确授权和 serial，核对系统、ABI、UID、App/Test APK SHA，再分别运行工具、手机决策与死亡清理场景；每条 ADB 显式选择目标。

观察工具真实来源：设备信息来自当前 App；CSV 来自固定 Linux 与真实文件；显示来自当前 Binder 目标。测试 pending_approval 不等于手机批准；mock、旧 AVD、前章 APK、电脑 ADB 或单张截图都不能跨层替代。只用自有演示数据，不代批外部账号或敏感用户数据。

按本章动作读 [TaskHostPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/TaskHostPanel.tsx)、[NativePocketRunTaskHost.ts](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/specs/NativePocketRunTaskHost.ts)、`TaskHostModule`，再读 `taskhost/ToolContract.kt`、`TaskHost.kt`、`CapabilityAdapters.kt` 与 `AndroidTaskCapabilities.kt`。Kotlin 目录为 `app/android/app/src/main/java/io/github/kkkhs/pocketrun/`，原生测试为 `Chapter9TaskHostIntegrationTest`。

网站独立执行 test:toolchain、typecheck、build、audit:ci，检查折叠内容、链接和手机/桌面深浅主题；页面能渲染不证明工具已执行。

</details>

## 本章源码

本文小段代码固定到 `chapter-9`，与后续任务上下文、模型循环的实现分开对照。

- [PR #14：固定工具、手机审批与宿主](https://github.com/kkkhs/pocketrun/pull/14)
- [chapter-9 源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-9)：完整源码和 SHA256，公开下载已核验，不发布调试签名 APK。
- [下一章：单任务上下文](chapter-10.md)；[第 11 章：真实模型循环](chapter-11.md)。

本章独立实际验收、main CI、Pages、注释 tag 与源码公开交付均已完成；各个设备和故障结果保持自己的记录范围。
