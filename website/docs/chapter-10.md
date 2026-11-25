---
sidebar_position: 2
sidebar_label: 第 10 章 · 单任务上下文与账本
title: 第 10 章：把多步调用组织成一份任务账本
description: 在 TaskHost 之上搭建 SingleTaskContext，用统一的 taskId、步骤账本、共享总期限和字节预算串联“读设备信息 + 经审批读 CSV”的两步任务。
---

import TaskLedgerFlow from './assets/task-ledger-flow.svg';
import taskResult from './assets/chapter-10-task-result.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章的 `TaskHost` 把单次工具调用管得滴水不漏，但它天生只管“当前这一条”——执行完设备信息，再去申请读 CSV，前面设备信息的结果就被新请求顶掉了。

可稍微像样一点的任务，至少都包含好几个连续动作。比如我们这一章的目标：

> **先读取设备信息，再数出私有演示 CSV 的行数，最后把两条真实结果放在一起核对，才宣布任务完成。**

为了把多次调用拧成一股绳，同时又不破坏第 9 章辛辛苦苦搭好的安全边界，我们在 `TaskHost` 外面加上一层轻量编排器——**`SingleTaskContext`（单任务上下文）**。

这一章依然先由你点按钮推进每一步，重点看清楚**目标（`goal`）、任务编号（`taskId`）、步骤账本（`steps`）和预算**是怎么协作的；到第 11 章，我们只需要把“谁点下一步按钮”换成大模型，整个 AI Agent 就水到渠成！

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={taskResult} alt="同一 task-1 已完成，账本保留设备步骤和手机批准的 CSV 步骤" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>同一个 task-1 的完整账本：第 1 步（设备信息）与第 2 步（手机审批后的 CSV 3 行）同时保留，核对后任务才标记 completed。</figcaption>
</figure>

## 同样跑在 App 进程里，只是多了一本“账本”

别被“上下文”这个词唬住——`SingleTaskContext` 既不是数据库，也不是新进程，它就是和 `TaskHost` 住在同一个普通 App Kotlin 进程里的一个状态对象：

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <TaskLedgerFlow className={`pr-ledger-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-ledger-title" aria-describedby="pr-ledger-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>两步调用共用同一个 taskId 与同一个 TaskHost 槽位：每一步依次流转，所有经过验证的结果都追加进账本。</figcaption>
</figure>

核心分工极其清爽：
- **`SingleTaskContext`**：管宏观——保存当前 `taskId`、`goal`、按顺序记录下来的 `steps` 列表、总时间期限和总字节预算。
- **`TaskHost`（第 9 章）**：管微观——依然负责每一条工具的参数校验、目标绑定、手机审批、实际执行与清理。账本**绝不绕过宿主**，也不替用户做主。

## 顺着两步任务走一遍代码

打开 [SingleTaskContext.kt](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/SingleTaskContext.kt)，整套多步流转只有四个核心动作：

### 1. 开启任务：拿到 `taskId` 与总倒计时

点击“开始两步演示任务”后，React 调用 `beginTask(goal)`，进入 `SingleTaskContext.begin()`：

```kotlin title="SingleTaskContext.kt（开启新任务）"
taskId = "task-${Math.addExact(sequence, 1L).also { sequence = it }}"
goal = nextGoal
steps.clear()
pendingNumber = null
phase = ContextPhase.READY
deadline = clock.nowNanos() + TimeUnit.MILLISECONDS.toNanos(budget.maxDurationMillis)
```

此时任务进入 **`READY`** 状态，并立下了一个默认 **120 秒** 的单调时钟总截止时间（`deadline`）——注意，后面等待用户在手机上点“同意”的时间，也算在这 120 秒总预算里。

### 2. 提交步骤：把总期限传给 `TaskHost`

步骤 1（读取设备信息）和步骤 2（读取 CSV）走的是完全一样的入口 `submitTaskStep(taskId, tool, '{}')`：

```kotlin title="SingleTaskContext.kt（向原 TaskHost 委派步骤）"
val number = steps.size + 1
// 把整个任务的绝对 deadline 传给 TaskHost，收紧审批与执行窗口
val status = host.submit(copied, taskDeadlineNanos = deadline)
steps += ContextStep(number, copied, status)
pendingNumber = number
phase = ContextPhase.RUNNING
```

两步的唯一区别，完全由 `TaskHost` 原本的规则决定：
- **步骤 1（`android.device_info`）**：纯只读，免审批，立刻读回当前设备的 Android 版本、API Level 和 ABI，记录到 `steps[0]`。
- **步骤 2（`linux.count_demo_csv`）**：需要审批，停在 `PENDING_APPROVAL`。前端面板检测到最后一步待审批，亮起“同意任务当前步骤”按钮——点下去时，传入的依然是**这一步专属的 `requestNonce`**（不是 `taskId`！）：

```ts title="SingleTaskPanel.tsx（审批依然精准绑定到单步 nonce）"
NativeTaskHost!.decideFromAppUi(current.requestNonce!, 'approve');
```

所以“用户开启了一个读 CSV 的任务目标”并不等于“用户已经批准执行 CSV 工具”——**目标归目标，每一步有副作用的动作依然逐次审批**。如果用户拒绝了第 2 步，第 1 步已经查到的设备信息会作为事实保留在账本里，但整个任务状态会立刻停在 `FAILED`，绝不会稀里糊涂继续往下走。

### 3. 区分两个状态：`READY` 不等于 `COMPLETED`

这是多步 Agent 设计里特别容易写乱的一点——**单步成功了，不代表整个目标完成了**。看看 `SingleTaskContext.refreshLocked()` 是怎么区分它们的：

```kotlin title="SingleTaskContext.kt（单步结算后的状态流转）"
if (current.phase == HostPhase.SUCCEEDED && current.cleanupState == CleanupState.PROVEN) {
    phase = ContextPhase.READY     // 这步干净结束了，可以提交下一步，或者显式结束任务
} else {
    phase = ContextPhase.FAILED
    code = current.code ?: "TOOL_FAILED"
}
```

- 只有前一步既 **`SUCCEEDED`** 又确认 **`CleanupState.PROVEN`**，任务才会回到 `READY`，允许提交下一步（杜绝并发抢占 `TaskHost` 单槽）。
- 全部步骤完成后，需要显式调用 **`finishTask(taskId)`**，确认至少有一条成功步骤且当前无未决步骤，任务状态才会真正落定为 **`COMPLETED`**。

### 4. 给账本装上硬预算，防止内存失控

一个真实的 Agent 循环如果不设上限，跑偏后几分钟就能把手机内存塞爆。`SingleTaskContext` 默认内置了一组清晰的硬预算（`ContextBudget`）：

| 预算维度 | 默认上限 | 允许配置上限 | 超限时行为 |
| --- | --- | --- | --- |
| 最多步骤数 | 8 步 | 16 步 | 停止推进，返回 `BUDGET_EXHAUSTED`（`STEP_BUDGET`） |
| 任务总时长 | 120 秒 | 300 秒 | 取消活跃步骤，返回 `TIME_BUDGET` |
| 账本总字节数 | 64 KiB | 128 KiB | 提交前就预留 768 B 元数据，超限返回 `CONTEXT_BUDGET` |
| 单步结果大小 | 16 KiB | 32 KiB | 丢弃超限结果，返回 `RESULT_BUDGET` |

此外，账本还用 `withoutActiveTask()` 守住了一个容易被忽视的边界：当第 10 章的任务正在活跃运行时，第 9 章的独立面板不能趁机往同一个 `TaskHost` 里塞一条“加塞请求”，确保任务账本的每一步都干净、可追溯。

:::info 内存态账本的当前边界
`SingleTaskContext` 目前保存在 App 内存中，用来支撑单次会话内的多步编排与下一章的模型循环——如果 App 进程被彻底杀死，内存账本不会自动恢复。跨任务持久化与可编辑的长期记忆，会在后续章节展开。
:::

## 动手跑一遍

1. 先在第 9 章面板点一次 **准备固定 Linux 工具**。
2. 滚动到 **第 10 章 · 单任务上下文** 面板：
   - 点 **开始两步演示任务** → 拿到 `task-1` 与 `ready` 状态；
   - 点 **步骤 1：读取设备信息** → 账本出现第 1 条记录，显示真实的 Android 版本、API 与 ABI；
   - 点 **步骤 2：申请读取演示 CSV** → 账本出现第 2 条记录并停在 `pending_approval`，同时第 1 条结果依然稳稳留在页面上；
   - 点 **同意任务当前步骤** → 触发真实 Linux 读取，第 2 条记录更新为行数 `3`、来源完成、清理确认；
   - 核对两条结果无误后，点 **核对两步结果并结束任务** → 整个任务变为 `completed`！

## 本章小结与源码

到这里，PocketRun 的骨架已经全部立起来了：底层有虚拟屏和 Linux/PTY，中间有 `TaskHost` 守门，上层有 `SingleTaskContext` 记账。

回过头看刚才的操作——其实你只是扮演了一回“人肉决策器”：看一眼账本，点一下“步骤 1”，再看一眼，点一下“步骤 2”，最后点“结束”。

**下一章，全书的收官高潮来了**：我们把官方大模型 SDK 接进 App Kotlin，把这个“看账本 → 选下一步”的活交给真正的 LLM——同时把“手机审批”这个刹车踏板，牢牢留在你自己手里！

- **本章源码快照**：[Release `chapter-10`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-10) · [PR #17](https://github.com/kkkhs/pocketrun/pull/17) / [PR #20](https://github.com/kkkhs/pocketrun/pull/20)
- **延伸参考**：[实机验收与工程档案](verification-records.md)
