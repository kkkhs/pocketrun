---
sidebar_position: 1
sidebar_label: 第 9 章 · 工具宿主与手机审批
title: 第 9 章：确定性工具宿主与手机端审批
description: 在接入大模型之前先装好刹车：设计带严格 Schema 校验、目标绑定、一次性 nonce 手机审批和可证明清理的 TaskHost。
---

import csvResultScreenshot from './assets/chapter-9-csv-result.png';
import displayResultScreenshot from './assets/chapter-9-display-result.png';
import bridgeRejectionsScreenshot from './assets/chapter-9-bridge-rejections.png';
import ToolApprovalFlow from './assets/tool-approval-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

到第 8 章为止，PocketRun 已经同时拥有了“操作虚拟屏”和“运行 Linux / PTY”两套底层能力。很多人写到这里就忍不住想直接调大模型 API 了。

先慢一步！**底层能力不等于可以直接喂给大模型的工具。**

大模型既可能产生参数幻觉，也可能在读完某个网页或 App 文本后中了提示词注入（Prompt Injection）。如果直接把一个能跑任意 shell 命令、或者能随便往屏幕发点击的接口裸露出去，Agent 一旦失控后果不堪设想。

所以在接入模型之前，我们需要在 App Kotlin 里先建一道**确定性的安全网关——`TaskHost`**。你可以把它理解为手机本地的“工具审批与执行总管”：

- **白名单工具与严格 Schema**：只认明确登记的工具，多传一个字段、类型差一点、坐标越界，全在执行前挡掉。
- **目标绑定 + 手机端逐次审批**：只要动作有副作用，就先暂停下来，把“具体要干什么、操作哪个文件/哪块屏”展示在手机界面，等用户亲手点**同意**才放行。
- **单调超时与可证明清理**：管住每一次执行的所有权，清理失败绝不假装成功。

这一章我们先不接模型，而是通过界面按钮向 `TaskHost` 提出一次“读取演示 CSV 行数”的请求，亲手走一遍**申请 → 手机同意 → 真实 Linux 执行 → 核对结果与清理**，再试一次**拒绝**，看清审批网关是如何工作的。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={csvResultScreenshot} alt="实际手机批准后的三行 CSV 结果、运行来源和资源清理" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>在手机上批准当前请求后，TaskHost 复用第 7 章的 LinuxSession 完成真实读取，并返回行数 3、退出码与清理确认。</figcaption>
</figure>

## 收束能力：只暴露 3 个受控工具

在 [ToolContract.kt](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ToolContract.kt) 里，我们故意把整个工具面收得非常紧——没有动态注册，也没有任意 shell：

| 工具名 | 干什么 | 参数要求 | 需要手机审批吗？ |
| --- | --- | --- | --- |
| `android.device_info` | 读取当前 App 的 Android 版本、API、ABI 与包 UID | 必须为空对象 `{}` | 否（纯只读） |
| `linux.count_demo_csv` | 复用第 7 章 PRoot/Alpine 统计私有 `items.csv` 行数 | 必须为空对象 `{}` | **是（每次必审）** |
| `android.virtual_display_observe_or_demo_input` | 对当前虚拟屏截图/查树（`observe`）或点击/输入（`demo_tap` / `demo_text`） | 必须带 `action`、`expectedDisplayId`、`expectedDisplayEpoch` | `observe` 否；**输入动作是** |

注意参数校验有多严：`TaskHostJson` 会拒绝格式错误或含**重复键**的 JSON，`ToolSchema.validate()` 则对每个工具执行 `exactKeys(...)` 检查——哪怕有人偷偷在参数里多塞一个 `"approved": true` 或 `"path": "/etc/passwd"`，都会在第一步直接被拒之门外。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={bridgeRejectionsScreenshot} alt="实际 JS 桥的固定参数拒绝与旧显示代际检查" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>JS → Codegen → Kotlin 边界校验实测：坏 JSON、重复键、未知工具、多余 path/argv/env、displayId=0 与过期 epoch 全部被逐项拦截。</figcaption>
</figure>

## 一次审批请求的完整生命周期

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <ToolApprovalFlow className={`pr-tool-approval-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-tool-approval-title" aria-describedby="pr-tool-approval-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>有副作用的请求在校验与目标绑定后停在待审批状态；只有拿到有效手机同意、且目标与期限仍未变化，才真正启动底层执行。</figcaption>
</figure>

### 前端心智模型：为什么不是“一个 `await` 等到底”？

写惯了前端 `async/await` 的同学，第一直觉可能会想把审批设计成一个长 Promise：`const result = await submitTool(...)`，在里面卡住等用户点同意再 resolve。

但在真实 App 里，这样做会带来一堆麻烦（线程被长时间占用、页面刷新重渲染状态丢失、很难区分“等待审批”和“正在执行”）。因此 `TaskHost` 采用的是**“原生状态机 + 三个独立异步入口”**：

1. **`submitTool(tool, argsJson)`**：校验参数、绑定目标、生成申请单后**立刻返回**（对于需要审批的工具，返回状态就是 `PENDING_APPROVAL`）。此时底层 Linux 进程**完全没有启动**，也没有线程在后台傻等。
2. **`decideFromAppUi(requestNonce, 'approve' | 'reject')`**：手机界面上的“同意 / 拒绝”按钮调用的**唯一入口**。它不接受外部传入审批人参数，也不能被工具参数绕过。
3. **`getTaskState()`**：React 面板每 250 ms 轮询一次，用来把原生状态机里的最新阶段（待审批 / 执行中 / 成功 / 失败 / 清理状态）同步到画面上。

:::tip 轮询只管展示，不管安全！
30 秒审批超时和 5 秒执行超时全部由 `TaskHost` 内部基于单调时钟（`System.nanoTime()`）严格卡控。就算前端页面关了、轮询停了，到了点原生层依然会拒绝过期审批并终止超时执行。
:::

## 深入 `TaskHost.kt` 的四个关键设计

打开 [TaskHost.kt](https://github.com/kkkhs/pocketrun/blob/chapter-9/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskHost.kt)，你会看到它把很多工业级可靠性细节收得极其工整：

### 1. 一次性 `requestNonce` 与 `requestHash`
每提交一次工具请求，宿主会生成一个带递增序号的加密随机编号 `nonce = "${requestSequence}_$sourceNonce"`，并把工具名、App UID、具体目标（比如 rootfs 代际 + canonical 文件路径，或 `displayId` + `displayEpoch` + 点击坐标）一起算出 SHA-256 `requestHash`。

把 `nonce` 想成一张**一次性审批单号**：
- 同一工具连续申请两次，单号不同——同意第 1 次绝不能顺带放行第 2 次；
- 一旦用户点了同意、拒绝或取消，`approvalConsumed` 立刻置为 `true`，这张单号永久作废。

### 2. 批准之后再查一次目标（防 TOCTOU 竞态）
用户看着手机弹窗思考了 5 秒钟才点“同意”。在这 5 秒里，万一虚拟屏被释放重建了（`displayEpoch` 变了）、或者 Linux 环境重装换代了、或者后端进程崩了怎么办？

所以 `TaskHost` 在**提交时**绑定目标快照，在**点击同意进入执行前**、以及**执行完成结算前**都会再调一次 `targetMismatch(request)` 重新核对！只要目标有任何变动，立刻把请求标为 `INVALIDATED` 并终止，绝不拿着旧申请单去操作一个新目标。

### 3. 复用原执行器，而不是重造轮子
拿到有效批准后，`TaskHost` 并不是另起炉灶，而是通过 `AndroidTaskCapabilities` 适配层直接调用前面章节已经打磨好的真实能力：
- `linux.count_demo_csv` → 调用第 7 章的 **`LinuxSession.runDemo()`**；
- `android.virtual_display_observe_or_demo_input` → 通过 `BinderTaskHostDisplayPort` 调用第 4—5 章的 **UID 2000 Binder 后端**（同样锁死在正数 `displayId` + 当前 `displayEpoch`，绝不降级到主屏 `0`）。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={displayResultScreenshot} alt="手机批准演示文字后，再次观察同一虚拟屏和 UI 树" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>虚拟屏工具同样走 TaskHost：观察直接放行，输入固定文字经手机批准后执行，再重新观察确认页面更新为 PocketRun_TaskHost_9。</figcaption>
</figure>

### 4. `ExecutionLease` 与“清理未证明就锁槽”
每个运行中的工具都会被包进一个 `ExecutionLease`，用同一把锁串行化 `start()` 和 `requestClose()`——如果取消抢在启动前一步发生，底层执行会被直接拦住，永不晚启动。

更硬核的是清理策略：无论工具正常结束、超时还是被用户取消，宿主都会执行底层清理并检查返回值。如果 `close()` 抛错，宿主的 `cleanupState` 会变成 **`UNPROVEN`**，**继续占住执行槽拒绝接收新任务**，直到用户或系统调用 `retryCleanup()` 确认清理干净为止。而且即便清理最终重试成功，状态也只会记成失败的 `CLEANUP_RECOVERED`，绝不会把一个出过故障的任务改写成 `SUCCEEDED`。

## 动手跑一遍

1. 打开 **第 9 章 · 确定性工具宿主与手机审批** 面板，先点 **准备固定 Linux 工具**，确认显示已校验的 Linux 版本与 App UID。
2. 点击 **申请读取固定演示 CSV 行数**，先别急着点同意——停下来看一眼面板里的 `pending_approval`、绑定的 `/workspace/items.csv`、rootfs 版本和本次 `requestNonce`。
3. 点击 **同意当前请求**，观察状态流转到 `succeeded / approved_by_app_ui`，核对行数 `3`、退出码全 `0`、`sourceStatus=completed` 和 `cleanupState=proven`。
4. 再点一次 **申请读取固定演示 CSV 行数**，这次点 **拒绝当前请求**——确认状态立即变为 `rejected`，底层完全没有启动这一轮读取。
5. （可选）如果你已经按第 2—4 章启动了 shell 后端和虚拟屏，也可以在同面板试试虚拟屏的“观察”和“需审批输入”。

## 本章小结与源码

这一章我们建起了一道坚固的**工具安全闸门（`TaskHost`）**：不管未来是谁在发起调用，都要老老实实过 Schema 校验、目标绑定、一次性手机审批、单调超时和可证明的资源清理。

不过现在的 `TaskHost` 是个“单槽健忘症”——每次只能管**一条**工具请求，下一条一来就把上一条覆盖了。现实里的任务往往要连续走好几步，还得记住每一步拿到的数据。下一章，我们在 `TaskHost` 外面包一层**单任务上下文与步骤账本（`SingleTaskContext`）**！

- **本章源码快照**：[Release `chapter-9`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-9) · [PR #14](https://github.com/kkkhs/pocketrun/pull/14)
- **延伸参考**：[实机验收与工程档案](verification-records.md)
