---
sidebar_position: 11
sidebar_label: 第 10 章 · 单任务上下文
title: 把两次工具调用放进同一个任务
description: 沿读取设备信息和演示 CSV 的两步任务，理解 taskId、步骤账本、手机批准和返回页面的结果。
---

import TaskLedgerFlow from './assets/task-ledger-flow.svg';
import taskResult from './assets/chapter-10-task-result.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

[上一章](chapter-9.md)，我们能请求一个固定工具，在手机批准后查看来源结果。现在提出一个稍长的目标：**先读取设备信息，再数出固定演示 CSV 的行数，最后把两条结果放在一起核对。**

单个工具只负责一次动作。我们还需要记住“这两次调用属于同一个目标”，并保留第一步的结果，而不是让第二步把它覆盖。

这就是本章的单任务上下文：**一个目标、一份步骤账本、一个当前运行的工具**。下一步仍由用户选择；模型选择留到第 11 章。

## 1. 先跑一次两步任务

在自有或明确获授权的演示设备上，先到第 9 章点“准备固定 Linux 工具”，再打开“第 10 章 · 一个目标，多个工具步骤”。

1. 点“开始两步演示任务”，看到目标、新的 `taskId` 和 `ready`。
2. 点“步骤 1：读取设备信息”，看到 Android 版本、API、ABI 和工具成功状态。
3. 点“步骤 2：申请读取演示 CSV”，它停在 `pending_approval`；第一条结果仍在页面上。
4. 在手机点“同意任务当前步骤”，看到 CSV 行数、来源和清理结果。
5. 核对两条结果后，点“核对两步结果并结束任务”，任务变成 `completed`。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={taskResult} alt="同一 task-1 已完成，账本保留设备步骤和手机批准的 CSV 步骤" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>实际独立章节包：设备为 Android 16/API 36，CSV 为 3 行；两条结果属于同一个 task-1。</figcaption>
</figure>

这条流程已实际完成。**一条工具成功不等于整个目标完成**；第二条失败时，第一条结果可以保留，但任务不能显示为完成。

## 2. 谁执行，任务怎样推进

上下文并不是又启动一个后端。下面几个 Kotlin 对象都在**同一个普通 App 进程**里：

| 执行者 | 本章分工 |
| --- | --- |
| React / TypeScript | 目标、步骤列表、手机按钮 |
| App Kotlin 的 `SingleTaskContext` | 记住目标、顺序、结果和当前步骤 |
| 同一 App 的 `TaskHost`、Android adapters | 复用第 9 章校验、批准、执行与清理 |
| PRoot / BusyBox 子进程 | 执行固定 CSV 读取，仍是 App UID |

设备信息直接来自 Android API；CSV 才需要启动 Linux 程序。本例不需要 UID 2000 的显示后端，也不走 Binder 或电脑 ADB。

**时间顺序**则是另一张地图：

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <TaskLedgerFlow className={`pr-ledger-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-ledger-title" aria-describedby="pr-ledger-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>两步复用同一个宿主。图表示任务顺序，不是多出几个执行进程。</figcaption>
</figure>

## 3. 开始目标：得到一个 taskId

先从 React 按钮看起。[SingleTaskPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/SingleTaskPanel.tsx) 固定了本例目标：

```ts
const DEMO_GOAL = '读取设备信息和固定演示 CSV 行数，核对两步结果';
```

开始按钮的实际代码是：

```tsx
{button('开始两步演示任务', busy || active || !NativeTaskHost,
  () => perform(() => NativeTaskHost!.beginTask(DEMO_GOAL)))}
```

这里的 `button()` 是页面自己的按钮辅助方法，底层仍是 React Native `Pressable`。点击后经 TurboModule 到 [TaskHostModule.kt](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt)：

```kotlin
override fun beginTask(goal: String, promise: Promise) = execute(promise) {
    contextMap(task.begin(goal))
}
```

真正记住目标的是 [SingleTaskContext.begin()](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/SingleTaskContext.kt)。下面摘取它创建本次任务的部分：

```kotlin
taskId = "task-${Math.addExact(sequence, 1L).also { sequence = it }}"
goal = nextGoal
steps.clear()
```

`taskId` 用来识别**这一次任务**，不是批准凭据；`steps.clear()` 只在开始新目标时清空旧账本，提交下一步时不会清空。

原生返回任务状态，页面随后用 `getSingleTaskState()` 读取它。这样 React 展示的是 Kotlin 实际保存的目标和编号，不是自己猜出来的“已开始”。

## 4. 读取设备：把第一条结果记下来

设备按钮传入刚才保存的任务编号、固定工具名和空参数：

```ts
() => perform(() => NativeTaskHost!.submitTaskStep(state!.taskId!, 'android.device_info', '{}'))
```

`SingleTaskContext.submit()` 先确认 `taskId` 仍是当前任务、当前没有未结束的步骤，再把调用交给原有 `TaskHost`。**账本不会自己读取 Android 信息，也不会跳过工具校验。**

设备 adapter 使用真实 Android API。例如 [AndroidTaskCapabilities.kt](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AndroidTaskCapabilities.kt) 的设备结果包含：

```kotlin
"androidVersion" to Build.VERSION.RELEASE,
"apiLevel" to Build.VERSION.SDK_INT.toString(),
"supportedAbis" to Build.SUPPORTED_ABIS.joinToString(","),
```

这些值来自当前设备，不是页面固定的示例文字。设备观察无需审批，宿主执行、确认清理后，把实际结果返回。

上下文为每次调用新增一个记录。`submit()` 中的实际摘录是：

```kotlin
steps += ContextStep(number, copied, status)
pendingNumber = number
phase = ContextPhase.RUNNING
```

`ContextStep` 保存步骤号、工具调用和宿主状态；`pendingNumber` 表示现在跟踪的是哪一条。后续结果只更新这条记录，不会覆盖整个列表。

## 5. 请求 CSV：同一个目标，仍要单独批准

第二个按钮还是提交到同一个任务：

```ts
() => perform(() => NativeTaskHost!.submitTaskStep(state!.taskId!, 'linux.count_demo_csv', '{}'))
```

它与第一步的区别不是任务编号，而是工具的授权规则。固定 CSV 仍经过第 9 章的目标绑定、手机审批与真实 Linux 执行；“我的目标是读 CSV”不等于“我已批准这条请求”。

页面只在当前步骤为待批准且有 nonce 时显示批准按钮：

```ts
const current = steps[steps.length - 1];
```

```ts
const pending = current?.phase === 'pending_approval' && current.requestNonce;
```

“同意任务当前步骤”最终调用的仍是原手机入口：

```ts
NativeTaskHost!.decideFromAppUi(current.requestNonce!, 'approve')
```

这里带的是**当前请求 nonce**，不是整个任务的 `taskId`。用户只批准这一条已绑定动作；以后加入第三步时，也不能借用这次同意。

拒绝会使整个任务停止推进，不是忽略失败继续下一条。批准后，adapter 复用第 7 章的 `LinuxSession.runDemo()`，核对行数、五个阶段退出码、来源状态和清理；它不从目标描述里提取任意路径或命令。

## 6. 等结果回到页面：为什么要读状态

与第 6 章“等待一条管道的最终 Promise”不同，`submitTaskStep()` 返回的是当前任务状态；CSV 可能还在等待批准或执行。页面因此持续读取账本：

```ts
const value = await NativeTaskHost.getSingleTaskState();
if (live.current && request === generation.current) setState(value);
```

这段来自 `SingleTaskPanel` 的轮询，每 250ms 查询一次。**查询负责显示，不负责授权或执行**；安全检查在原生层，不能依赖页面刚好刷新。

结果的完整返回路径是：

```text
Android API / LinuxSession 产生结果
  → TaskHost 保存当前请求结果与清理状态
  → SingleTaskContext 更新对应步骤
  → TaskHostModule 把 steps 转成 stepsJson
  → React 解析列表、渲染每一条结果
```

页面遍历的是整份 `steps`，而不是只显示最新一个工具。实际渲染摘录：

```tsx
{Object.entries(step.result).map(([key, value]) =>
  <Text key={key} selectable style={{ color: colors.text }}>{key}：{value}</Text>)}
```

所以第一步的 `apiLevel` 与第二步的 `lineCount` 同时存在。你可以对照来源，而不是只能相信“任务完成”的文字。

## 7. 核对后结束：ready 和 completed 不一样

任务有自己的阶段，工具也有自己的阶段。工具执行成功且清理确认后，上下文回到 `ready`：表示**可以提交下一条步骤，或明确结束**。

`SingleTaskContext.refreshLocked()` 的判断是：

```kotlin
if (current.phase == HostPhase.SUCCEEDED && current.cleanupState == CleanupState.PROVEN) {
    phase = ContextPhase.READY
} else {
    phase = ContextPhase.FAILED
    code = current.code ?: "TOOL_FAILED"
}
```

这段只在当前工具已经结算后运行；待审批、执行中或尚在清理时不会进入这个判断。新步骤必须等前一条成功及清理确认，不并发占用原宿主的执行槽。

用户核对两条真实结果后，结束按钮调用：

```ts
() => perform(() => NativeTaskHost!.finishTask(state!.taskId!))
```

`finish()` 确认是当前任务、至少有一条成功步骤且没有待完成步骤，然后置为 `completed`。两步演示的页面还要求恰好有两条记录，才启用这个按钮。

取消会终止当前工作，保留已经完成的记录；清理 `unproven` 时保留原 owner，只重试资源收尾。清理恢复不把失败任务改成完成，也不重新执行不确定的动作。

## 8. 小结：上下文保存的是什么

对这次任务，“上下文”不是一段聊天历史，而是：**目标 + 任务编号 + 两条带来源的步骤 + 当前进度**。

它把连续动作归到一起，却没有增加权限。默认 8 步、120 秒、64 KiB 账本、每条 16 KiB 结果，使这份记录保持有界；等待手机批准也算在总期限里。

练习：第一步已经成功，第二步被拒绝。页面是否应删掉第一步？整个任务是否应显示完成？分别从“事实记录”和“目标完成”解释。

下一章保留这份账本和原宿主，只把“下一步按钮由用户点击”换成“模型提出下一条工具调用”。

## 附录：预算、并发与实际验证

正文代码固定到 `chapter-10`，不会把第 11 章的模型入口写进本章源码。

<details>
<summary>账本预算与晚返回：实现细节</summary>

`ContextBudget` 默认 8 步、120000ms、65536 字节上下文、16384 字节单结果；允许的配置上限是 16 步、300 秒、128 KiB、32 KiB。目标限 1024 UTF-8 字节；每步先预留 768 字节元数据，避免执行后才发现放不下结果。

超限停止并显示 `RESULT_BUDGET`、`CONTEXT_BUDGET` 或对应原因，不把截断结果标为成功。账本只保留工具有限字段和来源引用，不复制整图、UI 树或凭据；这里的 UTF-8 字节数不是模型 token 数。

账本把同一单调时钟的绝对 deadline 传给 `TaskHost.submit()`，收紧审批、实际启动和结果提交。晚来的批准不能开始过期工具；状态轮询不是安全门。

`refreshLocked()` 用 nonce 和工具名核对当前结果；旧任务编号不能提交新步骤，旧回调不能接到新记录。第 9 章独立入口仍保留，但 `withoutActiveTask()` 阻止它向活跃任务插入另一条请求；当前同意、拒绝、取消、查询和清理重试不受这个新请求门阻挡。

模块失效时关闭账本和宿主；closed 不可逆。清理未确认保留 owner/请求槽，恢复清理不恢复任务成功。账本只在本次 App 会话内保存，不是跨进程死亡可恢复的任务调度器或长期记忆。

</details>

<details>
<summary>测试分层与设备批次</summary>

`SingleTaskContextTest` 使用测试能力驱动真实 TaskHost 核心，检查两步关联、等待批准、拒绝、目标换代、旧入口共存、取消/晚返回、旧 taskId、数量/字节/时间预算、清理失败与恢复；不是实际 Android/Linux 成功。

`SingleTaskPanel.test.tsx` 检查同 taskId、固定参数、显式 nonce 与卸载后的晚返回，不替代手机 GUI 操作。

2026-11-20，在任务专用 API36 ARM64 模拟器实际点击完成两步：App UID10150、Android16/API36；CSV 为 `/workspace`、guest UID10150、3 行、五阶段退出码0、source completed、cleanup done。最后核对按钮使任务 completed。`Chapter10ContextIntegrationTest` 的两次设备请求另有通过记录，不替代 CSV 手机批准。

2026-11-23 独立第10章包再次完成相同实际界面流程：`task-1`、两条记录、最终 completed，CSV 来源完成、清理确认、202ms、3 行。Debug APK SHA256 `a61950af5b3c7c2bb2dac1355a558d0b386739559520b746973aba03597f87b5`，Test APK SHA256 `2fc3f213e2f2cbb02611282e0b7f906ea598f6197a0892c0bbc39375036c6a7a`；82项相关 JVM 零失败/跳过，一项 Android context 集成通过。

这些批次分别核对，不用源码存在或界面 completed 证明其他设备、模型或所有异常分支通过。

</details>

<details>
<summary>实践与发布：可运行检查</summary>

按第1章准备已有工具链。在 `app/` 跑 `typecheck`、`lint`、`test -- --runInBand`、`audit:ci`；Android 跑相关 JVM、Debug/Release/AndroidTest 构建，参数 `-PreactNativeArchitectures=arm64-v8a --max-workers=2`。

网站在 `website/` 跑 `test:toolchain`、`typecheck`、`build`、`audit:ci`，隔离构建设置 `DOCUSAURUS_NO_PERSISTENT_CACHE=1`，检查折叠内容、链接与手机/桌面深浅主题。历史网页渲染不替代 App 执行；文档改写也不代表重新做了设备测试。设备实践只用自有或明确授权的演示设备和私有样本，显式选择安装目标，不公开调试签名 APK。

PR #17 和验收 PR #20 已合并，main CI/Pages 通过。注释 tag `chapter-10` 固定 `a2e3679`；源码归档与校验值已匿名下载、按完整文件核对。

</details>

## 本章源码

- [PR #17：单任务上下文与两步演示](https://github.com/kkkhs/pocketrun/pull/17)
- [PR #20：独立任务与最终模型包验收](https://github.com/kkkhs/pocketrun/pull/20)
- [chapter-10 源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-10)：完整源码归档与 SHA256。
- [SingleTaskPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/SingleTaskPanel.tsx) → [TaskHostModule.kt](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt) → [SingleTaskContext.kt](https://github.com/kkkhs/pocketrun/blob/chapter-10/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/SingleTaskContext.kt)：从按钮到账本的阅读顺序。
