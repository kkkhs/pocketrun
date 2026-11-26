---
sidebar_position: 14
sidebar_label: 第 13 章 · 完整任务与恢复
title: 读一份 CSV，填进 Demo，再保存可核对的报告
description: 沿私有CSV、模型整理、虚拟屏真实输入和文件读回，理解原生完成门、运行事实与中断后重新核对。
---

import CompleteTaskFlow from './assets/complete-task-flow.svg';
import interruptedPublish from './assets/chapter-13-interrupted-publish.png';
import recovery from './assets/chapter-13-recovery.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章让下一次任务明确使用一条用户偏好。这一章把已有能力串起来：**读取私有 `items.csv`，整理一行报告，输入自有虚拟屏 Demo，再把同一份报告保存为 `result.txt`。**

```text
CSV 的真实字节 → 模型计算 → Demo 的真实输入与结果 → 文件的真实字节
```

为什么还要专门做这一章？第 11 章的模型能提出工具、等待手机决定、继续下一回合，但“几个工具都成功了”还不等于这个目标完成。这里需要原生核对三端数据一致，并在中断后分清**做过什么、确认了什么、哪些动作不能再做一遍**。

> **本章已完成实际完整任务与中断恢复验收。** 六步任务、三处中断、新run核对和准确现状不重复输入分别验证；[PR #28](https://github.com/kkkhs/pocketrun/pull/28)、main CI/Pages 与 [chapter-13 源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-13)已核验发布，各批次的证明范围见[工程档案](verification-records.md)。

## 1. 先看这次任务的输入和结果

默认演示文件是：

```csv
name,quantity
apple,2
banana,3
```

这里有 **2 条数据**，数量之和是 **5**，报告为 `items=2;quantity=5`。第 6/11 章数的是包含表头的 3 行；这次要理解和整理内容，问题已经不同。

预期有两个可见结果：

- 自有虚拟屏 Demo 的编辑框显示 `items=2;quantity=5`，结果控件显示 `input: items=2;quantity=5`。
- App 私有 `workspaces/demo/result.txt` 的字节是这份报告加一个换行，读回后与预期完全相同。

使用自有或明确获授权的演示设备。先在第 4—5 章打开虚拟屏 Demo，在第 11 章保存合法模型配置，再打开“第 13 章 · CSV → Demo → 文件”：

1. 点“准备完整任务私有目录”。已有 CSV 会保留，不用初始化覆盖工作数据。
2. 点“核对并开始完整模型任务”，确认当前模型地址、模型名、虚拟屏和上一运行记录。
3. 按当前步骤逐次批准：CSV 披露、Demo 观察、必要的点击/输入、输入后的观察、文件发布。开始目标不是一次批准全部动作。
4. 查看“原生核验”“已确认阶段”“尚未确认的意图”和步骤结果，再与模型总结对照。

已有准确报告时只核对；不同输出、部分输入、失效目标或未知清理会停止并保留，不自动清空或覆盖。

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <CompleteTaskFlow className={`pr-complete-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-complete-title" aria-describedby="pr-complete-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>先沿数据读，再沿每个节点的身份读：文件用普通 App UID，显示用原 UID 2000 后端，模型推理仍在 endpoint。</figcaption>
</figure>

## 2. 先读取文件，让模型根据真实内容整理

CSV 在普通 App 的私有目录，不在独立 shell 后端，也不需要为这次任务启动 Alpine。`WorkspaceSession` 复用第 6 章的目录，再有界读取固定文件；Kotlin 经原工具结果把内容返回给 `AgentLoop`。

新工具 `workspace.read_demo_csv` 没有任意路径参数。它先进入原 `TaskHost` 的手机审批槽，批准绑定本次输入摘要与 App UID；只有成功、清理确认后的内容才进入模型的下一回合。

输入摘要是这些字节的 SHA256，回答的是“现在还是不是刚才那份输入”。它不是批准码，也不是模型计算出的报告。其他面板可能改变文件，所以每次输入或发布前还要重查，不能假定 TaskHost 的锁冻结了整个 App 文件系统。

`CompleteTaskContract.accept()` 收到读取结果后，原生也独立解析这份 CSV：

```kotlin
val report = DemoCsvReport.parse(checkNotNull(fields["csvText"]).toByteArray(Charsets.UTF_8))
```

模型根据读取内容整理报告；原生按固定演示 schema 计算预期值，之后核对模型提出的文字。这不是让原生替模型编造一个成功结果，而是给实际执行加一个可检查的条件：`items=2;quantity=6` 即使格式合法也不能输入。

本例只接受精确表头、两列无引号、有限 ASCII 名称与规范非负数量。普通 CSV 还可能有引号、逗号和多行字段；这些不属于本章 parser，遇到不支持的输入会拒绝，不猜着读。

## 3. 再观察目标，点击和输入还不是“填对了”

显示操作仍走原路径：

```text
App Kotlin → typed Binder → UID 2000 后端 → 当前正 displayId
```

`observe_demo` 与原来的免批准 `observe` 分开。前者经过手机批准，只向当前模型披露自有 Demo 的两个必要节点及输入框位置/焦点；第 11 章的普通模型任务向模型仍只发送原来的小型元数据。原 `observe` 返回 App 的截图 URI 和 UI 树文字不因此进入网络；披露白名单位于 `ModelToolSurface.summary()`。

`OwnedDemoObservation` 选择 App 自己的包名、稳定 `android:id/edit` 和 `android:id/text1`，要求节点唯一、树可用且未截断、同一窗口和同一显示目标。它不是在整份 UI 文本中搜索“报告出现过”，也不会把其他 App 的内容发给模型。

### 确认空值，才能决定是否输入

空编辑框的 UI 树有时返回 hint `PocketRun_Ditto_5`。**hint 不能单独证明编辑框为空**；这里还要结果节点精确显示 `input: `：

```kotlin
val value = if (result.text == "input: " && (input.text.isEmpty() || input.text == "PocketRun_Ditto_5")) "" else input.text
```

模型拿到真实 bounds 和中心，提出 `demo_tap`。手机批准后，原生再次检查输入摘要、当前 displayId/epoch、节点位置与值，才发送真实点击事件。

随后 `demo_text` 必须是原生核对的准确报告，而且编辑框确定为空、已经聚焦。已有完整正确报告时只核对，不追加第二份；非空但不同的内容会停止，交给手机处理。

### 输入被接受，不等于内容已经核对

真实事件可能被系统接受，但控件还没更新或只输入一部分。因此阶段先到 `text_accepted`，不能直接发布文件。模型必须再提出一次获准 `observe_demo`，确认两个节点同时满足：

```text
编辑框 = items=2;quantity=5
结果框 = input: items=2;quantity=5
```

只有这次观察确认后，阶段才是 `demo_verified`。本章没有增加 `EditText.setText()` 的捷径；输入和再观察复用已有 Binder 路线。

## 4. 保存文件，再由原生决定整个目标是否完成

模型提出 `workspace.publish_demo_result`，参数是准确报告和读取结果的 `inputDigest`。原生先检查已确认的 Demo、同一份输入和参数，再等待手机批准；成功后才发布 `result.txt`。

`WorkspaceSession.publishDemoResult()` 的关键顺序是：

```kotlin
val created = commit(temporary, target)
if (created) syncDirectory(root)
val actual = checkNotNull(readDemoResultBytes()) { "RESULT_PUBLICATION_UNCONFIRMED" }
require(actual.contentEquals(expected)) { "RESULT_PUBLICATION_UNCONFIRMED" }
guard()
```

前面已经写入本轮私有临时文件并同步；`commit` 复用已有 no-replace 原子发布。读回确认后，本轮临时文件的清理也必须证明。

- 没有旧文件：发布完整报告，再读回核对。
- 旧文件准确相同：返回 `already_present`，表示**本轮观察到正确现状**，不是补称旧任务写入成功。
- 旧文件不同，或并发产生了不同文件：保留并失败，不覆盖用户数据。

文件步骤成功后还没有自动宣布整个目标完成。模型 `end_turn` 和手动 `finishTask()` 都进入同一 `SingleTaskContext.finish()`：

```kotlin
try { complete?.finish { check(clock.nowNanos() - deadline < 0) { "TIME_BUDGET" } } }
```

这里要再次核对输入摘要、真实 Demo 两节点、文件字节/摘要、已结算的成功步骤和完成 journal 提交。失败会终止上下文；模型一句“已完成”不能替代它。

## 5. 中断后读事实，开始的是新任务

第 12 章保存的是用户愿意跨任务保留的笔记。这里的 `TaskJournal` 保存的是**某次运行的意图和已确认事实**，文件、schema、密钥别名和 AAD 都与笔记、模型配置分开。

每步启动前，`CompleteTaskContract.beforeStart()` 先写意图：

```kotlin
update(record.copy(phase = "running", pendingEffect = effect(action), code = ""))
```

收到真实结果、清理确认并持久提交之后，才更新已确认阶段、清掉这条意图。比如 `text_accepted / pendingEffect=observe` 表示输入事件已被接受，但下一次观察还没有确认；不能把它读成“填对了”。

### 重启不会把旧任务接着跑

App/上下文重新创建时，在途记录显示为 `interrupted`。它不保存模型完整历史、Binder、PID、FD 或可复用 nonce，也不会重建这些句柄。

点“核对并开始完整模型任务”时，原生核对输入、当前自有 Demo、已有输出和工作目录现状，然后创建**新 run ID、新上下文、新目标绑定、新期限和新批准**。模型重新读取获准内容，不盲目重放旧工具。

- Demo 已准确存在：新观察确认现状，跳过输入。
- 文件已准确存在：读回确认，报告 `already_present`。
- 部分输入、不同输出、输入改变、遗留临时文件或读取未知：保留并停止，先在手机核对。
- journal 不可读：不能当作“没有记录”。只有明确放弃不可读记录的重置操作才清除它；这不删除工作数据、知识记忆或模型凭据。

下面是实际发布窗口中断与新任务核对。左图中，文件已经出现在目录，但旧记录仍停在 `demo_verified / publish`；右图是重新创建目标、逐次批准后的新 run，完成核验时报告 `OUTPUT_ALREADY_PRESENT`。文件的 inode、大小、修改时间与字节均保持一致，不给旧发布补记成功。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '1 1 280px', minWidth: 0, maxWidth: 360}}>
    <img src={interruptedPublish} alt="发布后文件已存在，重启原生记录仍为interrupted、demo_verified和未确认publish意图" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>旧任务：有文件现状，但发布结果未确认。</figcaption>
  </figure>
  <figure style={{margin: 0, flex: '1 1 280px', minWidth: 0, maxWidth: 360}}>
    <img src={recovery} alt="新run完成原生output_verified核验，明确报告OUTPUT_ALREADY_PRESENT且文件不变" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>新任务：重新核对，确认已有文件。</figcaption>
  </figure>
</div>

同一进程里的 `unproven` owner 仍占用原槽；重置 journal 不会让它消失。已经提交的副作用也不会因为取消、重启或重置而回滚。

## 检查与源码

练习：若报告已经出现在 Demo，但 App 在观察前退出，下次能否直接输入？若 `result.txt` 存在但旧 journal 仍显示发布意图，为什么只能说“观察到已有文件”？

<details>
<summary>演示预算与分批验收范围</summary>

CSV 最多 1024 字节、0—32 条记录；数量每条最多 1,000,000，求和检查溢出。报告最多 64 个可打印 ASCII 字节，文件多一个 LF。运行 journal 最多 4096 明文编码字节，复用 Keystore/AES-GCM 与 AtomicFile，并严格检查字段和状态。

源码检查点 `955f77a`：100 项 UI 回归、133 项选定 taskhost/本章 workspace JVM、App typecheck/lint/audit，以及 ARM64 Debug/Release/AndroidTest 构建通过。Jest 和 fake source 的 JVM 检查验证状态与原生策略，不是 Binder/模型集成成功。

同一冻结 App/Test 包在任务专用 API36 ARM64 模拟器通过 7 项隔离 Android 检查：4项加密 journal、3项真实 App UID 文件/JNI无覆盖发布与遗留 staging 阻断。这些机制检查与真实App/模型任务、三处中断和新任务核对分开记录，不互相替代。

首轮 UI 检查发现不完整完成记录与解析失败后旧批准按钮仍可见的问题；修正后的完整 UI 回归通过。新增原生测试夹具的工具名/SDK必填字段错误、Android公开常量编译错误分别保留，后续结果不改写首次失败。

真实本机模型完成六个获准步骤，文件和Demo报告一致；待批准、输入后未观察、发布后未确认三处中断均核对。发布窗口用仅AndroidTest包含的FileObserver/pidfd观察助手暂停本任务App，独立检查真实文件和journal；助手未进入正常App/Release，不生成结果或批准。

[工程档案](verification-records.md)按源码/App/Test SHA区分初始机制、实际模型与最后制品批次，保留测试夹具、观察器和审批过期的原失败。本章注释 tag `chapter-13` 固定合并提交 `4310efa`；[完整源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-13)的564个文件、匿名下载与SHA256已核验，不包含APK。

</details>

本章实现与文档见 [PR #28](https://github.com/kkkhs/pocketrun/pull/28)，实现检查点为 [`955f77a`](https://github.com/kkkhs/pocketrun/tree/955f77ac55a854766c1625392b04df680dd73467)。可沿 [CompleteTaskPanel.tsx](https://github.com/kkkhs/pocketrun/blob/955f77ac55a854766c1625392b04df680dd73467/app/CompleteTaskPanel.tsx) → [TaskHostModule.kt](https://github.com/kkkhs/pocketrun/blob/955f77ac55a854766c1625392b04df680dd73467/app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt) → [CompleteTaskContract.kt](https://github.com/kkkhs/pocketrun/blob/955f77ac55a854766c1625392b04df680dd73467/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/CompleteTaskContract.kt) 阅读开始、批准与完成。

完整 Ditto 能力还要继续补 Linux 分发、通用工具、一般 App 操作和模型观察等差距，见[功能对照](ditto-parity.md)。这个固定 Demo 闭环是一个可读、可核对的学习阶段，不是最终工具范围。
