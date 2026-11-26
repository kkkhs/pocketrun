---
sidebar_position: 5
sidebar_label: 第 13 章 · 完整闭环与中断恢复
title: 第 13 章：串联完整任务，并搞定中途崩溃与恢复
description: 把读取 CSV、模型计算、虚拟屏真实输入和原子写文件串成端到端闭环，并用原生完成门与预写运行日志（TaskJournal）解决移动端中断恢复难题。
---

import CompleteTaskFlow from './assets/complete-task-flow.svg';
import interruptedPublish from './assets/chapter-13-interrupted-publish.png';
import recovery from './assets/chapter-13-recovery.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

还记得[课程导读](intro.md)开头那张北极星路线图吗？这一章，我们终于要把前面 12 章攒下的所有能力真正拼成一台完整运转的机器：

> **读取私有目录里的 `items.csv` → 让大模型算出统计报告 `items=2;quantity=5` → 在 UID 2000 虚拟屏里定位 Demo 输入框并把报告敲进去 → 再次观察确认画面更新 → 把同一份报告原子保存为 `result.txt` 并读回核验。**

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <CompleteTaskFlow className={`pr-complete-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-complete-title" aria-describedby="pr-complete-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>跨越两条执行通道的完整闭环：私有文件读写走普通 App UID，虚拟屏观察与输入走 UID 2000 Binder 后端，大模型只负责决策与计算。</figcaption>
</figure>

你可能会觉得：“工具都有了，大模型循环也有了，这一章不就是多加两个工具定义吗？”

真做下去，你会立刻碰到所有严肃 Agent 系统最硬核的两道工程题：

1. **怎么定义“真的做完了”？** 模型完全可能算错数（把 `2 + 3` 算成 `6`），或者发完按键事件还没确认文字填进去就自信地喊“已完成”。我们不能听模型的一面之词，必须有一道**原生完成门（Completion Gate）**，把输入、屏幕和输出文件三端咬死核对一致。
2. **任务跑到一半 App 被系统杀了怎么办？** 比如刚刚把 `result.txt` 写进磁盘，还没来得及记录“成功”，App 就闪退了。重启之后，绝不能傻傻地把整个任务重跑一遍——那样要么在 Demo 输入框里重复敲出两份 `items=2;quantity=5`，要么把已有文件粗暴覆盖。

这一章，我们就围绕 **`CompleteTaskContract`**（原生完成门）和 **`TaskJournal`**（预写运行日志），把这两道题彻彻底底解决掉。

## 一次完整任务的 6 个受控步骤

默认的私有 `items.csv` 还是我们熟悉的那份 3 行文件：

```csv title="workspaces/demo/items.csv"
name,quantity
apple,2
banana,3
```

第 6、11 章只是粗略数行数（得到 `3`），这一章要真正解析内容：**2 条商品记录、数量之和 5**，整理出的规范报告是 **`items=2;quantity=5`**。整条任务一共会流经 6 个由手机逐次审批的步骤：

```text
1. workspace.read_demo_csv        → 读出 CSV 字节并计算输入 SHA-256 摘要
2. observe_demo（首次观察）         → 定位虚拟屏 Demo 的输入框坐标、焦点与当前值
3. demo_tap                       → 点击虚拟屏输入框中心，让它获焦
4. demo_text                      → 敲入模型算出的 items=2;quantity=5
5. observe_demo（二次复查）         → 确认编辑框与结果框都真实显示 items=2;quantity=5
6. workspace.publish_demo_result  → 原子无覆盖写入 result.txt 并读回核验字节
```

每一步都复用第 9 章的 `TaskHost` 审批与清理机制，但外层套上了 [`CompleteTaskContract.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-13/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/CompleteTaskContract.kt) 的强阶段约束——必须按顺序过关，不能跳步。

## 第一关：三端一致性校验与“原生完成门”

### 1. 读文件：锁死 `inputDigest`，独立复算预期值
模型调用 `workspace.read_demo_csv` 经手机批准后，原生不仅把 CSV 文本返回给模型，还会用 [`DemoCsvReport.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-13/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/DemoCsvReport.kt) 独立算出两样东西：
- **`inputDigest`**：原始 CSV 字节的 SHA-256 摘要，用来盯住“中途有没有别的操作把 `items.csv` 改掉了”；
- **`expectedText`**：根据规则独立算出的预期报告 `items=2;quantity=5`。

这样一来，大模型依然要自己读内容、算结果、发起工具调用；但如果模型产生了算术幻觉（比如提出输入 `items=2;quantity=6`），原生前置守卫 `CompleteTaskContract.validate()` 会立刻拒绝，绝不把错误数据写进 App 或文件。

### 2. 看屏幕与敲字：一个经典的 Android 无障碍坑
在这一章的完整任务里，我们新增了需要手机批准的 `observe_demo` 动作（专门用来向模型披露自有 Demo 页的两个固定控件坐标和值，避免把任意 App 的整棵 UI 树发往网络）。

写 [`OwnedDemoObservation.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-13/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/OwnedDemoObservation.kt) 时，我们踩到了一个非常真实的 Android 细节：**当一个 `EditText` 为空时，无障碍控件树读出来的 `input.text` 有时候并不是空字符串，而是它的占位提示（hint）`PocketRun_Ditto_5`！**

怎么区分“空框里的 hint”和“用户真的输入了内容”？同时核对页面里同步展示结果的那一行 `android:id/text1`：

```kotlin title="OwnedDemoObservation.kt（双控件联合判断输入框是否为空）"
// 只有当下方结果框严格显示 "input: " 时，EditText 里的空值或已知 hint 才被认定为空
val value = if (result.text == "input: " &&
    (input.text.isEmpty() || input.text == "PocketRun_Ditto_5")
) "" else input.text
```

敲完字后，阶段也只推进到 **`text_accepted`**（“系统接收了按键事件”），**此时依然禁止发布文件**！模型必须再调一次 `observe_demo`，亲眼核实编辑框为 `items=2;quantity=5` 且结果框为 `input: items=2;quantity=5`，阶段才正式晋升为 **`demo_verified`**。

### 3. 写文件与最终完成门
到了最后一步 `workspace.publish_demo_result`，模型必须同时带上报告正文和第 1 步的 `inputDigest`。`WorkspaceSession.publishDemoResult()` 先把完整报告加换行写入私有临时文件并 `fsync`，再复用第 6 章的 `renameat2(RENAME_NOREPLACE)` 无覆盖原子发布成 `result.txt`，并**立刻从磁盘把 `result.txt` 读回来比对字节和 SHA-256**：

- 如果目标文件原本不存在：原子发布成功，返回 `published`；
- 如果目标文件已经存在、且字节和预期**一字不差**：绝不覆盖，返回 `already_present`；
- 如果目标文件已经存在、但内容**不同**：立刻报错并完整保留原文件，绝不覆盖用户数据。

最后，当大模型发回 `end_turn` 总结准备收工时，`SingleTaskContext.finish()` 会把关口交给 **`CompleteTaskContract.finish()`**——在落笔标记 `completed` 之前，把**输入 CSV 摘要、虚拟屏两控件现状、磁盘上 `result.txt` 字节与摘要**三端再全部现场核验一遍！三端全都对得上，任务才算真正完成。

## 第二关：如果中途 App 崩溃了怎么办？预写日志 + 现状核对

移动端和后端分布式系统一样，进程随时可能被系统杀掉。解决崩溃恢复的经典思想叫 **WAL（Write-Ahead Logging，预写日志）+ 幂等核对**，本章由 [`TaskJournal.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-13/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskJournal.kt) 落地。

它把当前运行的事实与未决意图加密保存在独立文件 `noBackupFilesDir/complete-task.bin` 里（独立 Keystore 别名 `pocketrun.task-journal.v1` 和 AAD，绝不和第 12 章的用户笔记混在一起）。每一步真正动身前，`CompleteTaskContract` 严格按**先写意图、再干活、确认后再销账**的顺序更新日志：

```kotlin title="CompleteTaskContract.kt（每步启动前先落盘记录 pendingEffect）"
// 1. 动手之前：把正在进行的意图（read / observe / tap / text / publish）写入加密日志
fun beforeStart(action: ParsedAction) = synchronized(lock) {
    guard(action)
    update(record.copy(phase = "running", pendingEffect = effect(action), code = ""))
}

// 2. 收到真实结果并确认清理后：推进已确认阶段 stage，同时把 pendingEffect 清回 "none"
update(next.copy(pendingEffect = "none"))
```

### 为什么重启后绝不“续跑旧任务”，而是“查清现状、开新任务”？

App 被强杀再重启后，旧进程里的 Binder 代理、PID、文件描述符和审批 `nonce` 全都已经灰飞烟灭。因此在重启加载日志时，任何未终结的运行都会被诚实标记为 **`interrupted`**——它只是一份**历史事实清单**（告诉你上次死在了哪个 `stage`、当时正在尝试什么 `pendingEffect`），**绝不是可以跳过审批继续执行的持久授权**！

当你点击“核对并开始完整模型任务”时，系统会创建一个**全新的 `runId`、新任务上下文、新目标绑定和新一轮审批**，并借助现状检查自动做到**幂等不重复**：

| 中断发生时留在现场的真实状态 | 新任务核对后的处理方式 |
| --- | --- |
| 死在审批前（啥也没改） | 从头正常走完 6 步 |
| 死在 `demo_text` 之后、`observe_demo` 之前（但字其实已经填对了） | 新任务首次 `observe_demo` 发现画面已经是 `items=2;quantity=5`，直接晋升 `demo_verified`，**自动跳过点击和输入**，绝不重复敲两遍！ |
| 死在 `publish` 窗口（`result.txt` 已经原子落盘，但 journal 来不及写 `output_verified`） | 新任务读回磁盘文件发现字节完全一致，保留原文件（inode 和时间戳丝毫不动），如实报告 **`OUTPUT_ALREADY_PRESENT`**！ |
| 现场残留了半截输入、不匹配的输出文件或中断留下的临时文件 | **立刻停机并保留现场**，提示用户人工检查，绝不自作聪明清空或覆盖 |

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '1 1 280px', minWidth: 0, maxWidth: 360}}>
    <img src={interruptedPublish} alt="发布后文件已存在，重启原生记录仍为interrupted、demo_verified和未确认publish意图" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>崩溃瞬间复现：文件已落盘但 App 在确认前中断，重启后日志如实标出 interrupted / demo_verified / 意图 publish。</figcaption>
  </figure>
  <figure style={{margin: 0, flex: '1 1 280px', minWidth: 0, maxWidth: 360}}>
    <img src={recovery} alt="新run完成原生output_verified核验，明确报告OUTPUT_ALREADY_PRESENT且文件不变" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>新 run 恢复完成：核对三端一致且识别已有文件 OUTPUT_ALREADY_PRESENT，原文件字节与 inode 保持不变。</figcaption>
  </figure>
</div>

## 动手跑一遍

1. 先按第 2—4 章启动 UID 2000 后端并创建演示虚拟屏（打开 `DisplayDemoActivity`），再在第 11 章确认模型配置已加密保存。
2. 滚动到 **第 13 章 · CSV → Demo → 文件** 面板：
   - 点击 **准备完整任务私有目录**（已有 `items.csv` 会保留）；
   - 点击 **核对并开始完整模型任务**，确认弹窗中的模型地址、虚拟屏编号与上一条记录后启动；
   - 按步骤逐次点 **同意完整任务当前步骤**（读 CSV → 首次观察 → 点击 → 输入 → 二次观察 → 发布 `result.txt`）；
   - 核对原生面板显示 `completed / output_verified / items=2;quantity=5`，且虚拟屏和 `workspaces/demo/result.txt` 内容完全一致！
3. **进阶体验（中断与幂等恢复）**：
   - 在演示页已经正确显示 `items=2;quantity=5` 且 `result.txt` 已经存在的情况下，再点一次 **核对并开始完整模型任务**，观察这次新任务只需 3 步（读 CSV → 观察确认 Demo 已准确 → 核对已有文件）就干净收官，完全没有往输入框里重复打字，也没有覆盖原文件！

## 阶段收官与下一步

恭喜你走完了 **PocketRun 第 1—13 章的完整主线**！回头看一遍，你已经从第一个 React Native 按钮出发，一路打通了：

1. **Android 权限与隔离虚拟屏**（第 1—5 章）：TurboModule → 本机无线 ADB → UID 2000 `app_process` + Typed Binder → 隔离 `VirtualDisplay` → 截图、UI 树与定向输入。
2. **App 内的 Linux 与交互终端**（第 6—8 章）：私有工作区与防死锁管道 → 绕开 W^X 的 PRoot + 最小 Alpine → C++/JNI 真实 PTY。
3. **可控的 AI Agent 闭环**（第 9—13 章）：`TaskHost` 安全审批网关 → `SingleTaskContext` 多步账本 → Kotlin 官方 SDK `AgentLoop` → 可管理的加密记忆 → 跨通道原生完成门与 WAL 中断恢复。

这套坚实的执行底座和固定 Demo 闭环已经全部验收交付；接下来，我们将对照[Ditto 功能对照与路线图](ditto-parity.md)，从固定示例一步步迈向**通用工作区文件/命令工具、一般第三方 App 操作、更完整的 Linux 包管理、以及面向多模态模型的安全观察协议**！

- **本章源码快照**：[Release `chapter-13`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-13) · [PR #28](https://github.com/kkkhs/pocketrun/pull/28)
- **延伸阅读**：[CompleteTaskPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-13/app/CompleteTaskPanel.tsx) → [CompleteTaskContract.kt](https://github.com/kkkhs/pocketrun/blob/chapter-13/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/CompleteTaskContract.kt) → [TaskJournal.kt](https://github.com/kkkhs/pocketrun/blob/chapter-13/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskJournal.kt) · [实机验收与工程档案](verification-records.md)
