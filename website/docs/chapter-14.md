---
sidebar_position: 1
sidebar_label: 第 14 章 · 对话产品外壳与通用工作区工具
title: 第 14 章：把内核装进真正的对话助手产品
description: 从 13 个开发者调试面板迈向真正的用户产品：构建 Gemini 风格多会话流式对话界面、独立加密会话存储与通用的私有工作区读写工具。
---

走完第 1—13 章，PocketRun 已经拥有了一颗相当硬核的“执行内核”：无线 ADB、UID 2000 虚拟屏后端、PRoot/Alpine 与 JNI 终端、带手机审批的 `TaskHost`、多步账本、官方大模型 SDK 循环、加密记忆和预写运行日志。

但老实说，如果你把第 13 章的 App 交给一个普通用户，对方打开屏幕看到的是一条竖着排开 13 个测试面板的长页面，满屏都是“初始化工作目录”“读取固定演示 CSV”“刷新完整任务记录”——**它还只是一个开发者实验室，而不是一个真正能用来日常交流的产品。**

参考同类完整产品（例如我们在[Ditto 功能对照](ditto-parity.md)中梳理的 Ditto 0.1.14 架构）以及 Gemini / Claude App 的交互体验，这一章我们正式开启**第二阶段：产品化演进**，一口气补齐成为“可用对话助手”的四块核心拼图：

1. **像 Gemini 一样的产品主界面**：默认打开就是干净的多轮自由对话页，带历史会话抽屉、快捷提问、独立「设置」页，以及把第 1—13 章原封不动收好供学习的「实验室」入口。
2. **真·流式打字与多轮上下文**：从官方 Anthropic Java SDK 的 SSE 流里实时提取 `TextDelta`，一边生成一边显示，同时保留随时点击「停止」秒断请求的能力。
3. **加密的多会话本地存储（`ConversationStore`）**：支持新建、切换、自动命名和删除多段聊天，重启 App 对话记录依然稳稳保留。
4. **通用私有工作区文件工具**：告别写死的 `items.csv`，给模型开放真正能干活的 `workspace.list_files`、`workspace.read_file` 和 `workspace.write_file`——同时依然死守路径沙箱与手机端内联审批！

## 架构升级：产品外壳之下，复用全部安全内核

这一章没有推翻前 13 章的任何安全边界，而是在 `TaskHost` 和 `SingleTaskContext` 之上架起了一层面向真实对话场景的编排器 **`ChatEngine`** 与持久化仓库 **`ConversationStore`**：

```text
React Native 产品外壳（App.tsx）
 ├── 对话（ChatScreen）   ──▶ 多会话抽屉、实时流式气泡、内联工具轨迹与审批卡片
 ├── 设置（SettingsScreen）──▶ 模型 Keystore 配置、工作区文件一览、长期记忆管理
 └── 实验室（LabScreen）   ──▶ 完整保留第 1—13 章教学与调试面板
          │
          ▼ TurboModule（PocketRunTaskHost）
 App Kotlin（同一普通 App 进程）
 ├── ConversationStore   ──▶ 独立 Keystore 别名与 AAD 域加密保存多段聊天
 ├── ChatEngine          ──▶ 多轮历史装配 + SSE 实时文本增量 + 工具循环
 └── SingleTaskContext + TaskHost（复用第 9—10 章）
      ├── android.device_info / linux.count_demo_csv / virtual_display
      └── 新增通用工作区工具：workspace.list_files / read_file / write_file
```

下面我们顺着四块新代码，看清每一环是怎么设计落地的。

## 1. 多会话加密存储：`ConversationCodec` 与 `ConversationStore`

为了像主流 AI 应用一样同时管理多段聊天，我们在 Kotlin 层新增了 [`ConversationCodec.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ConversationCodec.kt) 与 [`ConversationStore.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ConversationStore.kt)。

它直接复用第 12 章抽出来的 `EncryptedSnapshotFile`，但在密码学上彻底分域：

- **文件路径**：`noBackupFilesDir/conversations.bin`（权限 `0600`，`fsync` + 原子替换 + 写后读回校验）。
- **独立密钥域**：Keystore 别名与 AES-GCM AAD 均为 `pocketrun.conversations.v1`，与模型凭据、偏好记忆、运行日志互不串扰。
- **数据结构**：每段 `ChatConversation` 包含唯一 `id`（`conv_<32hex>`）、`title`（用户发出第一句消息时自动从首句截取清洁的 UTF-8 标题）、时间戳以及有序的 `messages`（每条助手回复连同它调过的 `tools` 轨迹和 `outputTokens` 一起持久化）。
- **优雅的预算保护**：单库硬上限 **64 KiB**、最多 24 段会话、每段最多 48 条消息。每次写入时 `pruneToBudget()` 会保护当前活跃会话，按时间优先淘汰最老的非活跃会话或最老消息，绝不让历史记录越滚越大拖垮加密存储。

## 2. 真·流式输出与多轮对话循环：`ChatEngine`

在第 11 章，我们虽然已经用了 `AnthropicModelTransport` 的 `createStreaming()`，但当时只用 `MessageAccumulator` 攒完整条消息，并没有把中间文字推给界面。

这一章，我们在 [`AnthropicModelTransport.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AnthropicModelTransport.kt) 里只加了 4 行代码，就从官方 SDK 的 SSE 事件流里直接接出了**实时文本增量**：

```kotlin title="AnthropicModelTransport.kt（从官方 SSE 流实时提取 TextDelta）"
stream.subscribe { event ->
    check(!request.cancelled.get())
    wireBytes += SdkJsonBudget.bytes(event)
    check(++events <= 2048 && wireBytes <= 262_144) { "MODEL_RESPONSE_BUDGET" }
    accumulator.accumulate(event)
    if (event.isContentBlockDelta()) {
        val delta = event.asContentBlockDelta().delta()
        if (delta.isText()) onTextDelta(delta.asText().text())
    }
}
```

而在上层的 [`ChatEngine.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ChatEngine.kt) 中，一轮完整的多轮对话这样运转：

1. **拼接多轮上下文**：从 `ConversationStore` 取出当前会话最近的历史消息（控制在 24 KiB 内，为本轮工具调用留足 64 KiB 总上下文余量），按 `user / assistant` 交替顺序装入 `MessageParam`，再附上本轮的新提问、本机能力状态和用户勾选开启的长期记忆（明确标记为不可信偏好数据）。
2. **实时流式更新**：每收到一块 `TextDelta`，立刻追加到受保护的 `streamingText`；React 界面轮询 `getChatRuntimeState()` 把新字实时刷到对话气泡里。
3. **无缝接管工具与内联审批**：
   - 如果模型决定直接回答（纯聊天，没有调用任何工具），`ChatEngine` 在 `end_turn` 时安全释放未使用的空 `SingleTaskContext`，把助手回复落盘，整轮完成。
   - 如果模型返回了 `tool_use`，整回合先经过 `ModelToolSurface.parse(block, ModelMode.CHAT)` 严格校验，再逐条提交给原来的 `SingleTaskContext` + `TaskHost`。
   - 当某条工具停在 `HostPhase.PENDING_APPROVAL` 时，`ChatEngine` 把阶段切为 **`WAITING_APPROVAL`**，暴露出 `pendingNonce`、`pendingTool`、`pendingTarget` 和参数摘要——聊天界面立刻在对话流原地渲染一张琥珀色**手机审批卡片**！用户点「允许执行」或「拒绝」，调用的依然是第 9 章唯一的原生审批入口 `decideFromAppUi(nonce, decision)`。

:::tip 拒绝工具之后怎么处理？
如果用户在聊天流里拒绝了某次文件读取或写入，本轮循环立刻停止（绝不尝试绕过），并往对话记录里如实写入一条带工具拒绝标记的助手说明（“已取消：你拒绝了本次工具请求”），让整段对话的来龙去脉清晰可查。
:::

## 3. 从固定 CSV 迈向通用私有工作区工具

为了让对话助手真正能帮用户处理文件，这一章在 `ToolContract.kt`、`WorkspaceSession.kt`、`TaskHost.kt` 和 `AndroidTaskCapabilities.kt` 中新增了 3 个通用私有工作区工具（仅在 `ModelMode.CHAT` 对模型开放，不破坏前 13 章的严格隔离契约）：

| 工具名 | 作用 | 需要手机审批吗？ | 安全护栏 |
| --- | --- | --- | --- |
| `workspace.list_files` | 列出私有工作区 `workspaces/demo` 下的相对路径与文件大小 | 否（只读目录元数据） | 深度 ≤ 4、最多 64 项、自动跳过符号链接与 `.pocketrun-*` 临时文件 |
| `workspace.read_file` | 读取工作区内任意 UTF-8 文本文件（上限 12 KiB） | **是**（数据披露必审） | `CanonicalChildPath.existingRegularFile` 防路径穿越与符号链接 |
| `workspace.write_file` | 在工作区内创建或更新任意 UTF-8 文本文件（上限 12 KiB） | **是**（文件写入必审） | `CanonicalChildPath.targetRegularFile` + `0600` 临时文件 `fsync` + 原子移动 + 写后读回校验 |

为什么把单文件读写上限设成 **12 KiB** 而不是更大？因为 `SingleTaskContext` 默认的单步结果预算是 **16 KiB**、整轮模型上下文预算是 **64 KiB**；把文件载荷控制在 12 KiB，既足够读写日常脚本、Markdown 笔记和小型表格，又能为 JSON 转义和元数据留出充裕空间，不会动不动撞爆预算。

同时，所有路径参数都必须过 [`CanonicalChildPath.targetRegularFile()`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/CapabilityAdapters.kt) 的严格检查——无论是 `../secret.txt`、`/etc/hosts`、`./items.csv`、符号链接还是 `.pocketrun-` 内部临时文件，在弹出审批卡片之前就会在原生层直接被拦下。

## 4. Gemini 风格的产品外壳：`ChatScreen` 与 `SettingsScreen`

打开重构后的 [`App.tsx`](https://github.com/kkkhs/pocketrun/blob/main/app/App.tsx)，顶部是一个清爽的三段式产品导航：

- **对话（默认主页，[`ChatScreen.tsx`](https://github.com/kkkhs/pocketrun/blob/main/app/ChatScreen.tsx)）**：
  - 顶部栏提供**会话列表抽屉**（切换、查看预览、删除）、当前标题和 **`+ 新对话`**；
  - 首次进入若尚未配置 API Key，会友好提示一键跳去「设置」；空会话展示欢迎语与 4 个**一键快捷提问芯片**；
  - 底部输入栏带**长期记忆一键开关**（显示已保存条数，按需勾选带入当前回合）、当前模型名标签以及**发送 / 停止**按钮；
  - 对话流内完整呈现右对齐用户气泡、带折叠式工具执行徽章（`✓ 列出工作区文件 · 共 1 个文件`）的助手卡片、实时流式输出区以及**内联手机审批卡片**。
- **设置（[`SettingsScreen.tsx`](https://github.com/kkkhs/pocketrun/blob/main/app/SettingsScreen.tsx)）**：集中管理大模型 Keystore 加密配置、查看私有工作区当前文件列表与设备就绪状态、以及快速增删长期偏好记忆。
- **实验室（`LabScreen`）**：完整保留第 1—13 章的全部逐章演示与调试面板，既方便老读者按章动手练习，也便于在需要无线 ADB 配对或虚拟屏调试时一键直达。

## 动手体验一下

1. 按第 1 章工具链构建并安装最新的 ARM64 Debug APK（或运行前端与 JVM 单元测试验证）。
2. 打开 PocketRun，默认直接进入全新的 **「对话」** 首页：
   - 点「前往设置」，填入你有权调用的模型地址、模型名和 API Key 并点击 **加密保存**；顺手点一下 **准备演示工作区**，再加一条你喜欢的偏好记忆（例如“回答尽量精炼，优先用中文”）。
3. 切回 **「对话」** 页：
   - 先随便聊一句普通问题（比如“用三句话解释 Android Binder 是什么”），体验实时流式输出；
   - 再点底部打开 **记忆开关**，点击快捷提示 **`读取 items.csv 并把汇总结果写入 summary.md`**；
   - 观察助手自动调 `workspace.list_files`（免审批），随后在聊天流原地弹出**手机审批卡片**请求读取 `items.csv` 和写入 `summary.md`；依次点击 **允许执行**，看它把结果汇总进新文件并流畅回复；
   - 切到「设置」刷新一下，就能亲眼看到 `summary.md` 已经稳稳躺在私有工作区里；彻底关掉重启 App，刚才的完整多轮聊天记录和工具轨迹也全都还在！

## 本章小结与下一步

第 14 章是 PocketRun 从**“技术教学内核”**跨向**“真正可用的个人 Android AI 助手”**的关键转折点：我们保留了前 13 章全部安全、审批、隔离与可验证底线，同时第一次把它们装进了一个干净、流畅、支持多轮流式对话与通用工作区文件工具的现代产品外壳里。

接下来顺着 [Ditto 功能对照与路线](ditto-parity.md) 继续往前走，两个最令人期待的升级方向已经水到渠成：
- **第 15 章**：为聊天助手开放受控的 **PRoot / Alpine 通用 Shell 命令工具**（经手机审批执行命令并安全回传有界输出），让它在手机里真正能跑脚本处理数据；
- **第 16 章**：把虚拟屏通道从固定 Demo 升级为**通用第三方 App 操控 + 多模态观察协议 + 对话页内嵌虚拟屏实时预览**，再配合引导式一键无线 ADB 配对，让它真正成为装在口袋里的全能 AI 助手！

- **核心源码入口**：[App.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/App.tsx) · [ChatScreen.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/ChatScreen.tsx) · [SettingsScreen.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/SettingsScreen.tsx) · [ChatEngine.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ChatEngine.kt) · [ConversationStore.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ConversationStore.kt)
- **测试与验证档案**：[实机验收与工程档案](verification-records.md)
