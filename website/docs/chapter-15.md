---
sidebar_position: 2
sidebar_label: 第 15 章 · 双协议、受控 Shell 与文件编辑器
title: 第 15 章：双协议模型、受控 Shell、工作区编辑器与 Markdown 渲染
description: 把 PocketRun 推向日常可用产品：接入 OpenAI + Anthropic 双协议流式适配、开放经手机审批的 PRoot/Alpine Shell 工具、内置工作区文件编辑器与零依赖 Markdown 渲染。
---

上一章我们把 PocketRun 装进了 Gemini 风格的多会话对话外壳。但如果你真的把它装到自己手机上试五分钟，马上会撞上四个非常现实的“产品级卡点”：

1. **手头的 API Key 不一定是 Anthropic 协议**：很多人日常用的是 DeepSeek、OpenAI、火山方舟/豆包、OpenRouter 或 Gemini 的 OpenAI 兼容接口（`/v1/chat/completions`），而且慢思考模型经常要思考超过 25 秒。
2. **Linux 环境还藏在实验室里，聊天助手用不上**：我们在第 7 章费尽心力搭好的 PRoot + Alpine，还没变成聊天里能调用的 `linux.run_shell` 工具。
3. **只能看文件名，自己改不了工作区文件**：设置页只列出文件名和大小，用户没法像 Ditto 的文件管理器那样亲自打开、编辑、新建或删除私有工作区里的文件。
4. **纯文本回复读着累**：模型输出的代码块、标题、加粗和列表挤成一团纯文字，缺少最基本的 Markdown 排版。

这一章，我们一口气把这四块产品级能力全部补齐——**而且零新增第三方 Gradle / npm 依赖，全程复用现有安全内核！**

## 1. 一套状态机通吃双协议：OpenAI + Anthropic 流式适配

支持两种大模型协议，最蠢的做法是把 `ChatEngine` 复制一份写成 `OpenAiChatEngine`——那样每改一次审批逻辑、流式取消或预算守卫，都得改两处。

正确的架构是**适配器模式**：保持 `ChatEngine` 和 `ModelTransport` 接口纹丝不动，只在传输层新增一个 [`OpenAiModelTransport.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/OpenAiModelTransport.kt)，直接复用依赖树里现成的 OkHttp 和 Jackson：

```text
ChatEngine（一套状态机、一套预算与取消逻辑）
    │  ModelTransport.requestStreaming(history, onTextDelta, completion)
    ▼
ModelTransports.create(credential, mode)
 ├── protocol == ANTHROPIC ──▶ AnthropicModelTransport（官方 Java SDK SSE）
 └── protocol == OPENAI    ──▶ OpenAiModelTransport（OkHttp SSE + 协议适配）
```

`OpenAiModelTransport` 在底层默默扛下了四个容易踩坑的细节：

1. **智能地址归一化**：用户可能填 `https://api.openai.com`、`https://api.openai.com/v1`、`https://ark.cn-beijing.volces.com/api/v3` 或 `https://generativelanguage.googleapis.com/v1beta/openai`。`resolveChatCompletionsUrl()` 自动识别路径中是否已含版本号或 `/openai`，统一拼出正确的 `.../chat/completions`，再也不怕多写或少写 `/v1`。
2. **消息与工具双向翻译**：发出请求时，把内部历史里的 `tool_use` 转成 OpenAI 的 `assistant.tool_calls`、把 `tool_result` 转成 `role: "tool"` + `tool_call_id`；收到流式响应时，一边把 `choices[0].delta.content` 实时回调给 `onTextDelta` 驱动界面打字机效果，一边按 `index` 拼接被 SSE 切碎的 `delta.tool_calls[].function.arguments` JSON 片段。
3. **统一收敛回 `Message` 模型**：整轮流结束后，把拼好的文本、`tool_use` 块、`stop_reason` 和 Token 用量转换成统一的 `Message` 对象交回 `ChatEngine`——上层的整回合校验、工具审批和历史回放一行代码都不用改！
4. **放宽合理超时 + 保持安全红线**：对话模式读超时放宽到 **60 秒**（控制器单回合等待 65 秒），从容应对慢思考模型；同时依然死守 Release 仅限 HTTPS、禁用 HTTP 自动重定向（防止 API Key 泄露给跳转主机）、512 KiB 流预算以及 `call.cancel()` 秒级取消。

在 `ModelCredentialStore` 里，旧版配置文件自动向后兼容为 `anthropic`，并在「设置」页提供了 **Anthropic / OpenAI 兼容** 协议切换和 4 个一键预设芯片（Anthropic、OpenAI、DeepSeek、本地 Debug）。

## 2. 把 PRoot/Alpine 接进对话：受控 `linux.run_shell`

在 Ditto 等完整产品里，AI 助手之所以能干活，很大程度靠的就是能在手机本地的 Linux 环境里跑一条命令处理文件。

我们在 [`LinuxSession.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt) 中新增了 `runShell(operation, command, timeoutMillis)`，并把它收束成一个新的受控工具 **`linux.run_shell`**：

```kotlin title="ToolContract.kt（严格的命令与超时边界）"
PocketRunTool.LINUX_RUN_SHELL -> {
    val allowed = if (copied.containsKey("timeout_sec")) setOf("command", "timeout_sec") else setOf("command")
    exactKeys(copied, allowed)
    val command = strictString(copied, "command", 1024)
    reject(command.isNotBlank() && !command.contains('\u0000'), "INVALID_COMMAND", "Shell command must be non-empty UTF-8 text.")
    val timeoutSec = if (copied.containsKey("timeout_sec")) strictInt(copied, "timeout_sec") else 10
    reject(timeoutSec in 1..15, "INVALID_TIMEOUT", "timeout_sec must be between 1 and 15.")
    ParsedAction.RunLinuxShell(command, timeoutSec)
}
```

它把“能干活”和“不失控”平衡得非常干净：
- **首次使用自动就绪**：`AndroidTaskCapabilities.ensureLinuxShellTarget()` 会在你第一次在聊天里用 Shell 工具时自动校验并准备好 PRoot + Alpine，不再要求用户先跑去第 9 章实验室点“准备环境”。
- **100% 强制手机审批**：`approvalRequired` 固定为 `true`。聊天流里的内联审批卡片会把**完整命令正文与超时秒数**清清楚楚亮出来，你确认无误点「允许执行」，PRoot 才会启动。
- **沙箱边界一步不退**：命令在普通 App UID 的 PRoot + BusyBox 环境里执行，工作目录锁在 `/workspace`（映射 `workspaces/demo`），清空继承环境变量，每次运行使用独立临时目录和 `LinuxDirectoryLease`，最长 15 秒自动终止，回传给模型的输出也受 8 KiB stdout / 4 KiB stderr 上限约束，并核验 `cleanupStatus == "done"`。

:::info 为什么 `TaskHost` 要单独给 `RunLinuxShell` 扩展执行期限？
第 9—13 章的默认工具执行超时是 5 秒；如果用户在聊天里批准了一条 `timeout_sec = 10` 的 Shell 命令，沿用 5 秒死线会在命令跑一半时误杀它。因此 `TaskHost` 只对 `RunLinuxShell` 动态把执行窗口扩为 `timeoutSec + 3` 秒，既不破坏旧章节的 5 秒契约，又能让批准的命令完整收尾和清理。
:::

## 3. 内置工作区文件管理器与编辑器：`WorkspaceFilesScreen`

从这一章开始，顶部导航栏升级为四个真正像产品的标签：**`对话` | `文件` | `设置` | `实验室`**。

点进新建的 **[`WorkspaceFilesScreen.tsx`](https://github.com/kkkhs/pocketrun/blob/main/app/WorkspaceFilesScreen.tsx)**，你可以直接管理 App 私有工作区（也就是 PRoot 里的 `/workspace`）：

- **浏览与新建**：列出所有文件及字节数，支持一键初始化演示 `items.csv`，或直接输入相对路径（如 `notes/todo.md`）创建新文件。
- **内置等宽编辑器**：点开任意 UTF-8 文本文件直接查看和修改，顶部实时显示 UTF-8 字节数（上限 12 KiB），保存时走 `WorkspaceSession.writeTextFile()` 的原子临时文件 + `fsync` + 写后读回校验；删除走 `deleteTextFile()` 且经 `CanonicalChildPath` 严格拒绝越界路径和符号链接。
- **一键联动对话**：编辑器底部提供 **「在对话中处理此文件」** 按钮，点一下直接切回「对话」页并把提示词预填进输入框，让助手读文件或用 PRoot 命令加工它！

## 4. 零依赖 Markdown 与代码块渲染：`MarkdownView`

为了让助手的回答真正具备 Gemini / Claude 级别的阅读质感，我们没有引入沉重的 WebView 或复杂原生依赖，而是用 200 行干净的 TypeScript 写出了 [`MarkdownView.tsx`](https://github.com/kkkhs/pocketrun/blob/main/app/MarkdownView.tsx)：

- 支持一级到三级标题（`#` / `##` / `###`）、无序与有序列表、引用块（`>`）；
- 支持行内 **`**加粗**`** 与 **`` `行内代码` ``** 高亮；
- 支持围栏代码块（```` ```kotlin ... ``` ````），自带语言角标头栏、独立底色卡片、等宽字体与横向滚动；
- **流式友好**：就算模型正在流式输出代码块、结束的 ```` ``` ```` 还没到达，也能立刻优雅地渲染为代码块，不会把页面闪乱！

## 动手试一遍

1. 构建并安装最新 ARM64 APK（`app-debug.apk`），打开 PocketRun：
2. 进入 **「设置」**：
   - 点一个快捷预设（比如 **OpenAI**、**DeepSeek** 或 **Anthropic**），确认协议标签切对，填入你的 API Key 并点击 **加密保存**。
3. 进入 **「文件」**：
   - 点 **准备演示 CSV** 或 **+ 新建** 写一份 `notes/plan.md` 保存；再点开它，点底部 **在对话中处理此文件**。
4. 回到 **「对话」**：
   - 试试发送 `用 PRoot Linux 命令查看 /etc/alpine-release 和工作区文件列表`；
   - 看聊天流弹出内联审批卡片，核对要执行的 shell 命令后点 **允许执行**；
   - 欣赏流式渲染出来的 Markdown 标题、列表和代码块总结！

## 本章小结与下一步

第 15 章补齐了成为“日常可用本地 AI 助手”最关键的四块拼图：**OpenAI + Anthropic 双协议流式支持、受控 `linux.run_shell`、内置工作区文件编辑器、以及清晰的 Markdown 排版**。

现在，PocketRun 已经是一个在手机上真正好用的**多协议流式对话 + 本地文件与 Linux 沙箱助手**。下一步（第 16 章）将直击 Ditto 最惊艳的核心体验：**通用第三方 App 操控（打开指定 App、滑动/返回、中文输入）+ 视觉/UI 安全观察协议 + 对话页内嵌虚拟屏实时预览**，以及更省心的引导式无线 ADB 配对！

- **核心源码入口**：[OpenAiModelTransport.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/OpenAiModelTransport.kt) · [LinuxSession.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt) · [MarkdownView.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/MarkdownView.tsx) · [WorkspaceFilesScreen.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/WorkspaceFilesScreen.tsx) · [SettingsScreen.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/SettingsScreen.tsx)
- **测试与验证档案**：[实机验收与工程档案](verification-records.md)
