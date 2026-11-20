---
sidebar_position: 12
sidebar_label: 第 11 章 · 真实模型循环
title: 让模型选下一步，仍由手机批准工具
description: 原生 Kotlin 使用官方 Anthropic Java SDK，把真实模型回复与受控工具结果接成有界循环，并用 Keystore 保存配置。
---

[上一章](chapter-10.md)把设备信息和演示 CSV 两条步骤放进同一个任务。本章仍做这件事，但不再由两个手动步骤按钮选择工具：**真实模型读取目标，提出工具调用，宿主执行获准动作，再把真实结果送给模型继续。**

读者应看到模型回合数、同一个任务编号，以及第 10 章面板里的两条实际工具结果。CSV 仍停在手机审批前；模型回复里的“同意”没有任何授权效果。

> **源码已提交 [Draft PR #18](https://github.com/kkkhs/pocketrun/pull/18)，真实模型与手机验收待完成。** 目前尚未配置运行所需凭据，未执行真实模型请求；合成协议和控制器测试不能替代它。网站与源码 Release 在验收后发布。

## 先配置，再看它请求工具

本例只用本 App 私有演示 CSV，不访问账号、用户文件或外部网页。模型功能使用普通 App Kotlin 进程，要求 Android API 26+ 的 `java.time`；整体 Linux 演示仍沿用前章 Android 14/API 34、ARM64 条件。下面是具备合法配置和设备窗口后应执行的步骤。

1. 在第 9 章准备固定 Linux 工具，确认安装 generation 和就绪状态。
2. 打开“第 11 章 · 真实模型与受控工具循环”，填写自己有权使用的 HTTPS endpoint、模型名和 API 凭据。默认 endpoint 是 `https://api.anthropic.com`，模型名是 `claude-opus-5-5`；有默认地址不等于已有调用资格。
3. 点“加密保存模型配置”。应只显示“已加密保存（不能读取）”，输入框清空，不展示保存的密钥。
4. 点“开始真实模型两步任务”。目标固定为先读设备、再读演示 CSV；应看到 `model`（等待模型）和真实回合数。网络错误或超时必须停止，不生成工具成功记录。
5. 在第 10 章账本查看模型提出的实际步骤。设备观察无需审批；CSV 应进入 `pending_approval`（待批准），显示原宿主绑定的固定目标与 request hash。
6. 在手机点“同意任务当前步骤”。核对真实来源和 `proven`（清理确认），再看模型进入下一回合并给出简短总结。验收本例必须看见设备和 CSV 两条真实记录，不能只看总结里的数字。

对应代码逐步是 `app/AgentLoopPanel.tsx` → `TaskHostModule.saveModelConfiguration()` / `startAgent()` → `taskhost/AgentLoop.start()` → `ModelToolSurface.parse()` → `SingleTaskContext.submit()` → 原有 `TaskHost`。审批仍调用 `TaskHostModule.decideFromAppUi()`，没有模型专用批准入口。

点“取消模型与工具任务”会同时取消模型请求和当前受控步骤。拒绝 CSV、目标失效、失败或清理未确认都会终止循环；不会再问模型“换个方式偷偷执行”。修改或清除配置也会取消当前循环，旧配置的晚回复不能接到新任务。

## 一回合只提议，不执行

模型通过 Messages API 返回结构化 `tool_use` 块。工具名先从模型允许的下划线形式映射到原宿主名字；文本中的命令、JSON 示例或“approved”都不会被执行。

| 模型工具名 | 原 TaskHost 工具 | 授权规则 |
| --- | --- | --- |
| `android_device_info` | `android.device_info` | 只读设备观察，不审批 |
| `linux_count_demo_csv` | `linux.count_demo_csv` | 固定空参数，每条手机审批 |
| `android_virtual_display` | `android.virtual_display_observe_or_demo_input` | 当前自有 displayId/epoch；观察无需审批，演示输入需要审批 |

对应 `ModelToolSurface.tools()` 和 `parse()`。模型输入仍经过原生 `ToolSchema.validate()`；路径、命令、argv、额外字段、未知动作或越界输入一律拒绝。模型工具列表没有 `approve`、MCP、产品 subagent 或任意 shell。

同一模型回合可能返回多条工具调用。先验证整回合的工具名、参数、数量与 ID，再逐条串行执行；上一条清理确认才处理下一条，不为它们启动并发任务。

## 结果回到原来的调用 ID

每条工具结果用模型返回的 `tool_use.id` 关联，不按“刚好第几个结果”猜测。宿主请求还有自己的 nonce 和 execution/backend epoch，两组标识用途不同：模型 ID 用于协议关联，宿主标识用于批准、目标与资源所有权。

```kotlin
results += ContentBlockParam.ofToolResult(
    ToolResultBlockParam.builder()
        .toolUseId(modelTools[toolIndex].id())
        .content(ModelToolSurface.summary(step))
        .build()
)
```

同一回合的所有成功结果放进一条 user 消息，再请求下一模型回合。设备信息只送 OS/API/ABI；CSV 只送行数、退出码和来源/清理状态；显示只送有限动作和目标编号，不送私有路径、`file://` URI、原始 stdout、UI 文本或凭据。

完整 assistant 内容通过 SDK 的 `Message.toParam()` 原样追加，包括签名 thinking 和 tool_use；不能只取最终文本，也不能改写已有消息。重复 ID、旧回合回调、拒绝和截断响应都会停止或被代际门拒绝。

对应代码是 `AgentLoop.acceptMessageLocked()`、`advanceToolsLocked()` 与 `ModelToolSurface.summary()`。`completed` 表示模型正常结束、已有成功且清理确认的工具步骤；它不是独立的目标正确性证明，仍要核对本例的两条来源结果。

## 谁运行模型，谁运行工具

| 执行层级 | 负责什么 |
| --- | --- |
| React / TypeScript，同一普通 App 进程 | 配置入口、状态、手机同意/拒绝/取消；不是 Node |
| App Kotlin，同一 App UID | 官方 Java SDK transport、单任务循环与原有宿主；模型网络 executor 与手机批准 executor 分开 |
| PRoot / BusyBox，普通 App UID 子进程 | 固定 CSV 实际读取 |
| typed Binder → 独立 UID 2000 后端 | 已有受限虚拟屏工具，未就绪即拒绝；不运行模型或 Linux |

阶段顺序则是另一件事：

```text
模型提出 tool_use → 宿主校验与绑定 → 必要时等手机批准
  → 实际工具结果与清理 → 同 ID 的 tool_result → 下一模型回合
  → 正常结束 / 取消 / 失败 / 预算终止
```

等待网络不能占住手机批准线程。请求和循环有自己的取消所有者；取消不仅是隐藏按钮，也不靠下一次页面轮询才停止网络。

## 用实际平台支持的 SDK

这里不把 React Native 当 Node。当前官方 TypeScript SDK 不支持 React Native，本章不增加 Hermes polyfill 栈；模型在 Kotlin 调用官方 `com.anthropic:anthropic-java:3.11.0`。

这个版本已从 Maven Central 的实际元数据核实，并通过本项目 Kotlin 编译与 Android D8 构建。**编译兼容不是 Android 真机运行成功**：SDK/Jackson 的 Android runtime smoke 与 Keystore 检查只有可运行源码和 AndroidTest 构建，实际执行仍待设备窗口。

`AnthropicModelTransport` 使用官方 `AnthropicOkHttpClientAsync`、`createStreaming()`、`MessageAccumulator.create()/accumulate()/message()` 和 `AsyncStreamResponse.close()`。异步 stream handle 在等待响应头时也能取消；SDK 请求期限 20 秒、重试为 0、每请求禁跟随重定向，日志与 OpenTelemetry 关闭。

<details>
<summary>官方 API 来源与依赖冻结</summary>

- [官方 Java SDK](https://github.com/anthropics/anthropic-sdk-java)
- [正式 Maven release 元数据](https://repo.maven.apache.org/maven2/com/anthropic/anthropic-java/maven-metadata.xml)
- [官方流式示例](https://github.com/anthropics/anthropic-sdk-java/blob/main/anthropic-java-example/src/main/java/com/anthropic/example/MessagesStreamingExample.java)
- [官方异步取消示例](https://github.com/anthropics/anthropic-sdk-java/blob/main/anthropic-java-example/src/main/java/com/anthropic/example/MessagesStreamingCancellationAsyncExample.java)

3.11 的 `MessageParam` 是 union，使用 `ofUser()` / `ofUserOfContentBlockParams()`，不是旧资料里的 `MessageParam.builder()`。完整回放用实际发布 jar 的 `Message.toParam()`；未知 SDK 方法不凭旧示例猜。

依赖只加入 PocketRun 的 `app/android/app/build.gradle`，相关 compile/runtime 配置锁在 `app/android/app/gradle.lockfile`。SDK 和 Jackson/OkHttp 等保留自己的许可，见 `app/THIRD_PARTY_NOTICES.md`；没有复制 SDK 执行框架或安装另一个 Node 环境。

</details>

## 秘密留在 App 私有存储

API 凭据只从遮罩输入框提交一次到原生配置入口。AndroidKeyStore 保存 AES/GCM 的加密密钥；endpoint/model/API key 的加密信封写入 App `noBackupFilesDir`，不写 AsyncStorage、日志、源码常量或包内资源。

读取配置只回传 endpoint、model 与 `hasCredential`，没有读出 API key 的方法。坏密文、丢失的 Keystore key 或无配置都会明确失败，不自动丢弃坏数据后装作可以继续请求。

对应 `ModelSettings` 检查 HTTPS、URL 用户信息/query/fragment 和字段长度，`ModelCredentialStore.load()/save()` 校验 AES/GCM 密文并原子保存，`TaskHostModule.executeModel()` 只返回固定错误，不回显服务端错误体。

这保护的是静态保存和接口曝光，不声称 JS/Java 字符串能被可靠瞬时擦除，也不宣称抵御已完全控制 App 进程的攻击者。本章不读取其他 CLI/OAuth 会话认证文件，不复制 token，不索要聊天账号密钥。

## 达到预算，就停止

默认最多 8 个工具步骤、8 个模型回合、120 秒总时间、32768 个累计输出 token、64 KiB 序列化消息上下文。每条工具结果和任务账本还受上一章的独立预算约束。

模型说“再试一次”不能增加预算。`max_tokens`、refusal、未知停止原因、异常内容、结果超限或清理失败都不能进入工具执行；本章采用安全终止，不自动换模型或重放动作。

<details>
<summary>更细的期限和晚返回检查</summary>

单请求控制器期限为 25 秒，SDK 本身为 20 秒；累计流事件最多 2048 个、序列化事件内容最多 256 KiB。全消息使用 SDK JSON 序列化计量，包含转义、签名和所有 assistant 块，不把日志 `toString()` 当字节预算。

每次请求有独立代际。旧模型回合、取消后的回复或配置失效后的晚完成不能改写新回合；工具的旧 nonce/epoch 仍由原宿主拒绝。任务绝对期限同时收紧原宿主的审批、实际启动和结果提交检查门。

取消先失效代际，再关闭当前 stream 与请求，取消 TaskHost 资源并保留未确认清理。配置改变时当前循环停止；不从缓存或失败记录恢复未确认工具动作。

</details>

## 验证，不混用证据

| 检查 | 证明范围 |
| --- | --- |
| `AgentLoopTest` | 合成模型消息驱动真实控制器/TaskHost 核心：审批、关联、完整块回放、串行步骤、拒绝、旧回合、取消、预算与清理失败 |
| `AnthropicSdkProtocolTest` | 本地合成 SSE：官方 SDK 全块累积、响应头前取消、禁重定向，以及实际生产包装层的流中取消与旧结果隔离；**不是模型调用或 Android 运行** |
| `AgentLoopPanel.test.tsx` | 凭据遮罩与清空、固定目标、取消、错误不回显，以及初始配置晚返回不会覆盖编辑；**不是手机 GUI 验收** |
| `Chapter11CredentialStoreTest` / `Chapter11SdkRuntimeTest` | 可运行 Android 检查，隔离测试别名和演示凭据；**当前仅构建，未执行** |
| 真实模型与工具闭环 | **未执行；缺合法配置和授权设备窗口** |

<details>
<summary>最终验证与公开交付前的清单</summary>

App：typecheck、lint、Jest、audit；Android：相关 JVM、ARM64 Debug/Release/AndroidTest 构建，参数 `-PreactNativeArchitectures=arm64-v8a --max-workers=2`。网站：test:toolchain、typecheck、build、audit，隔离构建设置 `DOCUSAURUS_NO_PERSISTENT_CACHE=1`。

实际验收需要冻结源码/APK SHA、真实手机配置、真实模型回合、手机逐次同意/拒绝/取消、动作后的来源结果、晚返回和失败收尾。只用演示数据；HTTP 401、fixture、mock、JVM 通过或 SDK 编译不能写成真实 Agentloop 通过。

2026-11-20 已实际检查本地第 10、11 章网页在桌面、390px 手机与深浅主题下的渲染，无整页横向溢出。App GUI 与真实模型/设备仍未验收；源码 Draft PR 已公开，Pages、注释 tag 与源码 Release 在最终验收后补齐，不公开 Debug 签名 APK。

</details>

## 本章没有增加模型的权限

本章增加的是“模型提出下一步 → 获准工具产生结果 → 下一回合”的真实 transport 与受控循环。工具权限、目标重检和资源所有权仍属于原宿主；完成文字仍需要来源结果支持。

下一章才讨论跨任务记忆。本章只保留当前有界任务，不加入长期记忆、产品框架、MCP、subagent 或多任务并发。

## 本章源码

- [Draft PR #18：原生模型循环与加密配置](https://github.com/kkkhs/pocketrun/pull/18)
- [固定实现与修复提交 `15cabc4`](https://github.com/kkkhs/pocketrun/commit/15cabc461d407591140877c0bb20f202325eb6bb)
- [官方模型标识与能力](https://platform.claude.com/docs/en/about-claude/models/overview)

本章源码 Release 在实际模型/设备验收与合并后建立。
