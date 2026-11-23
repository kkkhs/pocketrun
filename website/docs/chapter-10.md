---
sidebar_position: 11
sidebar_label: 第 10 章 · 单任务上下文
title: 把两次工具调用放进同一个任务
description: 在原有工具宿主上维护一个目标、有界步骤账本、手机审批与失败停止边界。
---

[上一章](chapter-9.md)可以申请一个固定工具、在手机批准它、查看来源结果和清理。现在我们把两个动作连起来：**读取设备信息，再读取固定演示 CSV，最后一起核对。**

读者应看到同一个任务编号、一个目标，以及两条各自带有审批和结果的步骤。第一条的 Android API 信息不会被第二条 CSV 行数覆盖；第二条失败也不会把整个任务显示为完成。

> **实现已通过本地门禁；手机验收与公开发布待完成。** 下文是操作步骤和成功条件，不是已取得的设备记录。

下面是账本的结构示例，不是设备截图；实际 API 和行数由工具返回：

```text
一个任务目标：读取设备信息与演示 CSV
  步骤 1：设备信息 → 保留 Android / API / ABI 结果
  步骤 2：固定 CSV → 等手机批准 → 保留行数和清理结果
两步一起核对 → 结束这个任务
```

## 先跑两步演示

只用本 App 私有演示数据。在第 9 章面板先点“准备固定 Linux 工具”，看到已校验的安装 generation，再打开“第 10 章 · 一个目标，多个工具步骤”。

1. 点“开始两步演示任务”。应看到目标“读取设备信息和固定演示 CSV 行数，核对两步结果”、新的 `taskId` 和 `ready`（可开始下一步）。对应 `SingleTaskPanel.tsx` 开始按钮 → `TaskHostModule.beginTask()`。
2. 点“步骤 1：读取设备信息”。等它变成 `succeeded`（工具成功），清理为 `proven`（清理确认）；Android 版本、API 和 App UID 应来自当前设备。对应 `SingleTaskContext.submit()` → `TaskHost.submit()` → `AndroidTaskCapabilities.prepareDeviceInfo()`。
3. 点“步骤 2：申请读取演示 CSV”。第一步仍在页面中，第二步停在 `pending_approval`（待批准），显示固定路径、安装代际与本次 request hash。对应 `SingleTaskPanel.tsx` CSV 按钮 → `submitTaskStep()`。
4. 在手机点“同意任务当前步骤”。结果应由真实 Linux 来源返回；未修改的初始 CSV 行数应为 `3`，来源与清理也必须满足成功条件。对应 `TaskHostModule.decideFromAppUi()` → `TaskHost.onAppUiDecision()` → `AndroidTaskCapabilities.prepareCountDemoCsv()`。
5. 核对两步数据后点“核对两步结果并结束任务”。只有这时任务变成 `completed`（任务完成）。对应 `TaskHostModule.finishTask()` → `SingleTaskContext.finish()`。

拒绝第二步会停止这整个任务，而不是悄悄跳过它。点“取消整个任务”也会停止当前工作；如果清理显示 `unproven`，只重试当前资源清理，不用一个新任务覆盖它。

对应代码：`app/SingleTaskPanel.tsx` 的 `perform()` 和两个步骤按钮调用同一个 `NativePocketRunTaskHost` 模块；`TaskHostModule.beginTask()`、`submitTaskStep()` 和 `finishTask()` 把动作交给 `taskhost/SingleTaskContext.kt`。

<details>
<summary>源码位置与发布状态</summary>

本章新增源码在 `app/SingleTaskPanel.tsx` 和 `app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/SingleTaskContext.kt`；原生桥接在该包上一级的 `TaskHostModule.kt`。当前尚无公开固定源码快照；公开发布后补实际 PR、commit/tag 与源码 Release，不造未来链接。

</details>

## 目标不是批准

“帮我读 CSV”是任务目标，不是对读取动作的授权。任务只把连续步骤归到一起；每个工具仍经过第 9 章的参数校验、目标绑定和手机审批。

`SingleTaskContext.submit()` 调用原有 `TaskHost.submit()`。CSV 仍只有空参数 `{}`，不能附带命令、路径、`approved: true` 或审批角色。模型、网页内容和任务描述都没有同意入口。

```kotlin
val status = host.submit(copied, taskDeadlineNanos = deadline)
steps += ContextStep(number, copied, status)
pendingNumber = number
phase = ContextPhase.RUNNING
```

一条步骤记录自己的工具名、参数、请求 nonce/hash、backend epoch、审批、结果和清理。账本记录的是发生了什么，不是新的权限系统。

## 谁执行，先做什么

**执行层级**仍沿用上一章，没有多出一个 Node 服务或执行后端：

| 层级 | 本章分工 |
| --- | --- |
| React / TypeScript，普通 App 进程 | 展示目标、步骤结果与手机按钮 |
| App Kotlin，同一 App UID | 单任务账本 → 原有 TaskHost → 校验、审批和资源所有权 |
| PRoot / BusyBox，普通 App UID 子进程 | 读取固定私有 CSV |
| typed Binder → 独立 UID 2000 后端 | 已有目标虚拟屏能力；本例不需要调用 |

**执行阶段**则是这条顺序，不是另外几个进程：

```text
开始目标 → 读取设备 → 查看结果 → 请求 CSV
       → 手机批准 → 查看来源和清理 → 核对并结束
```

步骤可以多次经过同样的执行层。手机批准发生在 React 界面，宿主把当前 nonce 与已绑定的目标重新校验；Binder 不承接 CSV 工具，ADB 也不替 App 执行或代批。

## 下一步必须等上一条结束

任务阶段与工具阶段分开。`ready` 表示可以请求下一条步骤，`running` 表示当前步骤仍占用宿主；工具自己的阶段可能是待审批或执行中。

`SingleTaskContext.refreshLocked()` 只吸收当前请求 nonce 与工具相匹配的结果。当前工具成功且资源清理为 `proven`，任务才回到 `ready`；拒绝、失效、超时、来源失败和清理未确认都停止推进。

```kotlin
if (current.phase == HostPhase.SUCCEEDED &&
    current.cleanupState == CleanupState.PROVEN) {
    phase = ContextPhase.READY
} else {
    phase = ContextPhase.FAILED
    code = current.code ?: "TOOL_FAILED"
}
```

第 9 章单工具入口仍能独立使用，但任务进行中不能提交另一条不属于账本的请求。`withoutActiveTask()` 只挡新请求和环境准备，不挡当前请求的手机同意、拒绝、取消、查询和清理重试。

## 为上下文设上限

上下文就是这一个目标与它的步骤账本。它不是聊天消息越积越多的容器，也不是跨任务知识库。

默认最多 **8 个工具步骤、120 秒、64 KiB 上下文和每条 16 KiB 结果**。目标最多 1024 UTF-8 字节；请求前预留步骤元数据空间，避免已经执行了工具才发现没有空间存账本。

结果超过上限时停止任务，明确显示 `RESULT_BUDGET` 或 `CONTEXT_BUDGET`，不把截断后的字符串当完整成功结果。原有 TaskHost 来源结果仍受它自己的边界管理；账本不复制整份图片、UI 树或凭据，只保留工具返回的有限字段和来源引用。

达到步骤数量时不能再请求工具，但已成功完成的任务仍可明确结束。时间期限包含等待手机审批；超时会取消当前请求，不能用晚来的同意继续执行。

<details>
<summary>预算、取消和晚返回的实现细节</summary>

`ContextBudget` 约束配置本身：最多 16 步、128 KiB 上下文、32 KiB 单结果、300 秒，调用者不能放开无限预算。元数据按每步 768 字节保守预留；小型固定参数及结果字段按 UTF-8 长度计量。这不是模型 tokenizer 的 token 数。

期限使用与宿主相同的单调时钟与纳秒单位。账本把绝对总期限交给 `TaskHost.submit()`，收紧这一条请求的审批和执行期限；手机批准、实际启动和结果提交都复用同一检查门。即使页面没有先查询状态，过期批准也不能开始工具。

页面每 250 ms 查询状态用于展示，不是授权安全门。模块失效时关闭账本与宿主；关闭标志不可逆，后续刷新、清理重试或旧回调不能把它变回可开始新目标的状态。它不宣称是进程死亡后还能恢复的后台任务调度器。

整个任务有递增 `taskId`，工具各有原有 nonce/execution epoch。取消后仍保留已完成结果及当前清理状态；底层 TaskHost 拒绝旧回调，旧 taskId 不能提交新步骤。

清理未确认时宿主保留 owner 和请求槽。重试成功只是证明资源收尾，不把已经失败的任务改成完成；未确认动作也不会自动重放。

</details>

## 检查的是哪一层

`SingleTaskContextTest` 是可运行的 JVM 检查：两步关联、等待审批、拒绝与目标换代、旧入口共存、取消后迟到回调、旧任务编号、数量/字节/时间预算、清理失败及恢复。它用测试能力驱动真实 TaskHost 核心，不是实际 Android/Linux 或真实模型成功证据。

`SingleTaskPanel.test.tsx` 检查 RN 按钮提交同一个 taskId、固定工具参数、显式 nonce 决策和组件卸载后的晚返回。它不替手机点击，也不证明 GUI 像素渲染或设备执行正确。

<details>
<summary>构建与实际验收清单</summary>

在 App 跑 `npm run typecheck`、`npm run lint`、`npm test -- --runInBand`、`npm run audit:ci`；Android 跑相关 JVM、Debug / Release / AndroidTest 构建，ARM64 参数为 `-PreactNativeArchitectures=arm64-v8a --max-workers=2`。Debug 签名 APK 不公开发布。

网站另跑 `test:toolchain`、`typecheck`、`build`、`audit:ci`。隔离构建用 `DOCUSAURUS_NO_PERSISTENT_CACHE=1`；正文目录、折叠内容与链接也应检查。桌面/手机深浅主题实际渲染截图仍待补齐。

真实设备验收需要合法设备授权与排他窗口：核对这版 APK SHA、两步真实来源、手机批准/拒绝、超时与取消、旧 nonce、失败清理。当前未操作设备，尚未取得本章完整 GUI/设备通过记录。

</details>

## 得到的是一个有边界的任务

本章把多条工具步骤放进同一个目标，保留结果、审批、失败原因和资源收尾。它没有长期记忆、MCP、产品 subagent、多任务并发或自动恢复。

第 11 章再让真实模型选择下一条受限工具调用；本章手动按钮仅用于先看清任务边界。即使模型选择了一个工具，参数校验与手机审批也不能省掉。
