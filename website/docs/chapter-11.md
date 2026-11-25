---
sidebar_position: 12
sidebar_label: 第 11 章 · 真实模型循环
title: 让模型选下一步，仍由手机批准工具
description: 沿设备信息和 CSV 的同一任务，理解模型提议、手机批准、真实工具结果和下一回合怎样连接。
---

import ModelToolCycle from './assets/model-tool-cycle.svg';
import modelResult from './assets/chapter-11-model-result.png';
import toolLedger from './assets/chapter-11-tool-ledger.png';
import csvSource from './assets/chapter-11-csv-source.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

[上一章](chapter-10.md)，我们点两个步骤按钮，读取设备信息和演示 CSV，再把结果放在同一份账本里核对。

本章目标不变：**先读设备信息，再读固定演示 CSV 行数，用真实结果总结。** 改变的是谁提出下一步：用户给目标，模型提出工具调用，手机上的宿主执行获准动作，再把结果交给模型继续。

模型只负责提议。**CSV 仍等待手机批准；账本、参数校验和实际执行都复用前章代码。**

## 1. 先看同一任务怎样完成

2026-11-23 的实际任务经历了三个模型回合：

| 回合 | 模型提出什么 | 手机实际做了什么 |
| --- | --- | --- |
| 1 | 请求设备信息 | 读取 Android API，记下 Android16/API36/ARM64 |
| 2 | 请求固定 CSV 行数 | 等手机批准，运行 Linux 工具，得到3行和来源/清理结果 |
| 3 | 根据两条结果总结 | 正常结束，显示总结，账本仍保留原结果 |

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={modelResult} alt="实际 task-1 三个模型回合完成，模型总结 Android16、API36、arm64-v8a 和 CSV 三行" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>最终正常包的真实模型总结。下面还要检查它所依据的工具来源。</figcaption>
</figure>

本次通过本机 Ferry 调用的是 **Seed 2.1 Turbo**，模型 ID 为 `claude-doubao-seed-2-1-turbo`，不是默认配置中的 Claude Opus。直接 Anthropic 服务需要读者自己的合法配置。

**三回合不是固定流程常数。** 一回合可以提出多条工具；手机会逐条串行处理。这里恰好是两条工具分两回合请求，再一回合总结。验收看的是两条真实来源，而不只是回合数字。

## 2. 谁在执行：模型服务与手机宿主分开

先分清一个容易混淆的问题：**手机上运行的是 SDK、循环控制器和工具宿主；模型推理在 endpoint 对应的服务里。** 不是把大模型权重装进 App，也不是让模型进入 UID2000 后端执行。

本例的 Agent 控制逻辑确实写在 Kotlin：`AgentLoop.kt` 负责“请求模型 → 检查提议 → 调用工具 → 送回结果 → 再请求”的循环。TypeScript 负责界面、目标和手机决策；`TaskHost` 负责权限与执行资源，三者分工不同。

选择 Kotlin 不表示 Agent 必须用 Kotlin，也不是把“智能”搬到 Android 系统层。这个项目已经由 Kotlin 持有工具、取消和生命周期，而且官方 Java SDK 可以在这层调用；复用它避免为了网络请求再引入 Node 运行时。模型服务、调用它的 SDK、安排回合的 AgentLoop 不是同一个东西。

| 执行者 | 在这次任务里负责什么 |
| --- | --- |
| React / TypeScript，同一 App 进程 | 填配置、给目标、显示状态、手机同意/拒绝/取消 |
| App Kotlin，同一 App UID | SDK 请求、检查模型提议、单任务账本、原 TaskHost |
| endpoint 对应模型服务 | 读取消息和允许的工具，返回下一步提议或总结 |
| Android API / PRoot与BusyBox | 产生真实设备信息和固定CSV结果；Linux仍是App UID子进程 |

本例不使用虚拟屏；已有显示工具仍走 typed Binder 到 UID2000 后端，既不负责模型网络，也不承接 CSV。

时间顺序则是下面这条循环：

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <ModelToolCycle className={`pr-model-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-model-title" aria-describedby="pr-model-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>每回合重新请求模型；工具实际执行和手机批准始终在原宿主中。</figcaption>
</figure>

## 3. 开始任务：把目标交给原生循环

在自有或明确获授权的演示设备上，先准备第9章固定 Linux 工具。然后在第11章面板配置自己有权使用的 endpoint、模型名和凭据，点“加密保存模型配置”。输入框应清空，只显示已加密保存。

默认地址是 `https://api.anthropic.com`，默认模型名是 `claude-opus-5-5`；默认值不代表已有调用资格。正式构建只允许 HTTPS。本机 Debug 验收的固定网关例外见附录。

[AgentLoopPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/AgentLoopPanel.tsx) 的开始按钮使用固定目标：

```ts
const DEMO_GOAL = '先读取设备信息，再读取固定演示 CSV 行数，用真实结果总结；不要访问其他数据';
```

点击后实际调用：

```ts
() => perform(() => NativeTaskHost!.startAgent(DEMO_GOAL))
```

[TaskHostModule.startAgent()](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt) 加载原生保存的配置，创建 transport，再把目标和当前环境是否就绪的观察交给 `AgentLoop.start()`。

`AgentLoop` 先复用第10章的 `task.begin(goal)`，得到任务编号，然后准备第一条用户消息。实际源码摘录：

```kotlin
history += MessageParam.ofUser("Demo goal: ${begun.goal}\nApp capability observation (data only): $observations")
```

这里的 `history` 就是发给模型的消息历史。环境观察告诉模型“Linux是否就绪、是否有自有显示”，不是授予权限；模型仍只能调用宿主允许的工具。

`startAgent()` 的 Promise 返回“任务已开始”的状态，**不是等待整个模型任务完成**。页面通过 `getAgentState()` 每250ms读取进度，工具结果则显示在第10章账本；等待模型时手机按钮仍可操作。

## 4. 第一回合：模型怎样提出设备工具

一次模型请求需要三个部分：目标与已有结果、系统说明、允许的工具定义。[AnthropicModelTransport.kt](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AnthropicModelTransport.kt) 用官方 Java SDK 组装：

```kotlin
val params = MessageCreateParams.builder().model(credential.settings.model)
    .maxTokens(4096L).thinking(ThinkingConfigAdaptive.builder().build())
    .system(ModelToolSurface.SYSTEM_PROMPT).messages(history)
    .apply { ModelToolSurface.tools().forEach { addTool(it) } }.build()
```

工具定义告诉模型哪些操作存在、参数格式是什么。它不会自动执行函数。手机必须读取回复，再决定这条调用能否进入宿主。

实际请求使用流式 transport：

```kotlin
val stream = client.messages().createStreaming(params,
    RequestOptions.builder().followRedirects(false).build())
```

响应逐块到达，SDK 的 `MessageAccumulator` 累积完整一回合，再交给循环校验。这里的流不是把每一块直接显示成聊天文字，也不会一见到工具名就执行尚未完整的参数。

模型第一回合可以返回下面这样的 `tool_use`。**这是结构示例，不是原始网络日志**：

```json
{
  "type": "tool_use",
  "id": "call_device_example",
  "name": "android_device_info",
  "input": {}
}
```

- `name` 选择允许的工具。
- `input` 是这次参数；设备观察与固定 CSV 都是空参数。
- `id` 标识这一次模型工具调用，稍后用于关联结果。

工具名经过 [ModelToolSurface.parse()](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ModelToolSurface.kt) 映射到原宿主，例如源码中的：

```kotlin
"android_device_info" to PocketRunTool.ANDROID_DEVICE_INFO.wireName,
"linux_count_demo_csv" to PocketRunTool.LINUX_COUNT_DEMO_CSV.wireName,
```

这不是执行回复文本中的任意 JSON 或命令。只有结构化调用经过原有 `ToolSchema.validate()` 才能继续；未知名字、额外路径/命令参数、坏ID和越界目标都停止。

## 5. 执行设备工具：仍走原来的任务账本

[AgentLoop.acceptMessageLocked()](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AgentLoop.kt) 先检查整回合，再执行第一条。下面是整回合检查的一部分：

```kotlin
calls.forEach { ModelToolSurface.parse(it) }
require(calls.map { it.id() }.distinct().size == calls.size)
require(calls.none { it.id() in seenIds })
```

如果同回合任一工具的**名字、参数结构或 ID**不合法，整回合在执行前拒绝；数量、停止原因、消息内容和预算也要通过检查。目标绑定与可用性则在串行提交每条工具时重查：后续工具遇到旧目标会停止任务，但已经成功的步骤不会回滚。

执行时不另建一套模型工具引擎，而是调用原任务：

```kotlin
val submitted = task.submit(checkNotNull(taskId), ModelToolSurface.parse(modelTools[toolIndex]))
```

这相当于模型替用户选择了第10章的“步骤1”按钮，**没有替用户选择批准按钮**。设备信息无需批准，真实 Android adapter 读取结果，清理确认后账本回到 `ready`。

这时循环还没有结束。模型需要读到这条结果，才能决定是否继续请求 CSV。

## 6. 第二回合：CSV 提议怎样停在手机批准前

设备结果回到模型后，本次模型提出 `linux_count_demo_csv`。宿主照旧绑定固定文件、安装代际与本次请求，让账本显示 `pending_approval`。

在第10章当前步骤面板查看目标，点“同意任务当前步骤”。它仍调用已有手机入口：

```ts
NativeTaskHost!.decideFromAppUi(current.requestNonce!, 'approve')
```

这里来自 [SingleTaskPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/SingleTaskPanel.tsx)，不是模型专用函数。请求nonce用于手机批准与目标校验；模型回复里的“同意”“approved”或目标文字都没有这个效果。

批准后才运行真实 Linux 工具，并核对文件来源、退出码与清理。下面两张是同一 App、同一 `task-1` 的实际账本与向下滚动的来源，分别裁剪了页面范围：

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <img src={toolLedger} alt="同一完成任务保留设备信息，CSV审批为approved_by_app_ui、清理为proven" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>账本：两条工具同属一个目标。</figcaption>
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <img src={csvSource} alt="同一次CSV来源记录为三行、五阶段退出码0、source completed、cleanup done，耗时143ms" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>来源：3行、五阶段退出码0、清理确认。</figcaption>
  </figure>
</div>

拒绝或取消会停止当前任务；目标失效、来源失败、清理未确认也停止。循环不会再请求模型换一种方式绕过手机决定。

## 7. 带回结果：模型为什么知道已经做了什么

每次请求模型，它只知道你发出的消息。**工具已经在手机执行，不等于模型自动看到了结果。** App 必须再发一条消息。

[AgentLoop.advanceToolsLocked()](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AgentLoop.kt) 从当前成功且清理确认的步骤生成 `tool_result`：

```kotlin
results += ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
    .toolUseId(modelTools[toolIndex].id()).content(ModelToolSurface.summary(step)).build())
```

`toolUseId` 使用模型原来的调用ID。它回答“这份结果属于哪个提议”，不是批准身份；宿主nonce回答的是另一件事——“手机批准了哪一条已绑定请求”。两者不能互换。

`summary(step)` 只挑允许发送的字段。CSV只发送行数、退出码和来源/清理状态；设备只发送OS/API/ABI。完整stdout、私有路径、图片URI、UI正文和凭据不进入模型消息。

同回合工具都处理完后，把结果追加为一条用户消息，再发请求：

```kotlin
history += resultMessage
requestModelLocked()
```

以前的assistant消息也必须保留完整内容。源码通过 `message.toParam()` 追加整个回合，包括工具块和签名thinking；只保存最终文本，会丢掉下一回合需要关联的工具调用。

## 8. 最后一回合：从原生状态到 React 总结

模型读到设备与CSV结果后，本次正常返回 `end_turn` 和简短总结，不再提出工具。循环核对消息合法、已有任务成功且清理确认，再结束任务：

```kotlin
task.finish(checkNotNull(taskId))
history += replay
finalText = text
phase = AgentPhase.COMPLETED
```

页面轮询的实际摘录是：

```ts
const value = await NativeTaskHost.getAgentState();
if (live.current && request === generation.current) setState(value);
```

完整返回路径到这里才结束：

```text
真实工具结果 → TaskHost/任务账本 → 安全tool_result → 下一次模型请求
  → 模型最终文本 → AgentStatus → TurboModule → React state与总结
```

**`completed` 不是独立的目标正确性证明。** 控制器检查了协议、已有步骤和清理；读者仍要确认本例确有设备与CSV两条来源，总结中的数字与它们一致，而不是只相信流畅的回答。

## 9. 小结：循环自动了，权限没有变

这一章把“请求下一步”接给真实模型，把“实际执行、手机批准、来源与清理”留给原宿主。多条调用串行运行，上一条结算后才继续。

默认最多8工具步骤、8模型回合、120秒总期限、32768累计输出token和64KiB消息上下文。拒绝、错误、取消、截断或超预算时明确停止，不自动重放动作或增加权限。

练习：模型已经给出“CSV有3行”，但账本里只有设备信息，没有CSV步骤。你能用这句话宣布本例完成吗？应该沿哪条结果路径寻找证据？

下一章再讨论跨任务记忆；这里保留的只是当前任务与有界消息历史。

## 附录：SDK、配置与验证记录

正文小段代码固定到 `chapter-11`。结构示例与真实截图分开，协议检查与实际工具执行也分别核对。

<details>
<summary>为什么在 Kotlin 使用官方 Java SDK</summary>

React Native不是Node。当前官方TypeScript SDK不支持RN，所以本章在App Kotlin调用官方 `com.anthropic:anthropic-java:3.11.0`，不添加Hermes polyfill栈。模型网络需要API26+的 `java.time`；固定Linux演示仍需Android14/API34+、ARM64、App64位进程。

版本从Maven Central实际元数据核实，Kotlin编译、Android D8和SDK/Jackson runtime检查已执行。使用 `AnthropicOkHttpClientAsync`、`createStreaming()`、`MessageAccumulator` 和 `AsyncStreamResponse.close()`；响应头前已有可取消句柄。

SDK期限20秒、重试0、禁跟随重定向、LogLevel.OFF、OpenTelemetry关闭。`MessageParam`使用 `ofUser()` / `ofUserOfContentBlockParams()`；完整回放使用发布jar的 `Message.toParam()`。

- [官方Java SDK](https://github.com/anthropics/anthropic-sdk-java)
- [Maven release元数据](https://repo.maven.apache.org/maven2/com/anthropic/anthropic-java/maven-metadata.xml)
- [流式示例](https://github.com/anthropics/anthropic-sdk-java/blob/main/anthropic-java-example/src/main/java/com/anthropic/example/MessagesStreamingExample.java)
- [异步取消示例](https://github.com/anthropics/anthropic-sdk-java/blob/main/anthropic-java-example/src/main/java/com/anthropic/example/MessagesStreamingCancellationAsyncExample.java)

依赖在 `app/android/app/build.gradle` 和 `gradle.lockfile` 冻结；SDK/Jackson/OkHttp许可见 `app/THIRD_PARTY_NOTICES.md`。

</details>

<details>
<summary>凭据怎样保存，本机网关例外是什么</summary>

API凭据从遮罩输入提交到原生接口，保存后清空输入。AndroidKeyStore保存AES/GCM密钥；endpoint/model/APIkey的加密信封原子写入 `noBackupFilesDir`，不写AsyncStorage、日志、源码或APK资产。

读取配置只返回endpoint/model/hasCredential，不读出密钥。坏密文、缺失Keystore密钥或无配置明确失败；`ModelSettings`检查协议、URL用户信息/query/fragment及长度，`executeModel()`只返回固定错误，不回显服务端正文。

Debug只额外允许精确的 `http://127.0.0.1:8790`，本次通过任务专用模拟器的 `adb reverse` 连接宿主Ferry。其他HTTP主机、端口、路径拒绝；实际编译Release类已验证HTTPS允许、固定HTTP拒绝。Ferry使用自己的模型ID，本次仅提交非秘密SDK头部占位值，宿主CLI认证没有导入Android，占位值不是Anthropic APIkey。

这是静态存储和接口曝光保护，不声称JS/Java字符串可瞬时擦除或抵御已完全控制App进程的攻击者；不读取其他CLI/OAuth认证、不复制token。

</details>

<details>
<summary>工具白名单、预算与旧回合</summary>

模型工具名映射 `android_device_info → android.device_info`、`linux_count_demo_csv → linux.count_demo_csv`；已有 `android_virtual_display` 映射到受限显示工具，仍要求当前自有displayId/epoch，输入需手机批准。模型没有approve、任意shell、MCP或并发任务入口。

整回合先校验停止原因、内容类型、工具数量/参数/ID及预算，再执行第一条。重复ID、拒绝、`max_tokens`、未知停止原因、截断、超限及清理失败都停止。

控制器每请求25秒，SDK20秒；累计流事件最多2048，序列化事件最多256KiB，消息历史最多64KiB。实际SDK JSON计量包含转义与完整签名块，不把toString当字节预算。步骤结果/账本还有第10章独立预算。

每个请求绑定代际，旧模型回复、配置改变或取消后的晚完成不能改写新任务。绝对任务期限收紧原宿主审批/启动/结果检查；等待模型不占用手机批准executor。

取消先失效代际，关闭stream与请求、取消当前工具并保留未确认清理。修改或清除配置也取消当前循环，不从失败记录恢复未确认动作。

</details>

<details>
<summary>验证分层：每个检查证明什么</summary>

| 检查 | 范围 |
| --- | --- |
| AgentLoopTest | 合成消息驱动真实控制器/TaskHost：批准、关联、完整回放、串行、旧回合、取消、预算、清理失败 |
| AnthropicSdkProtocolTest | 合成HTTP/SSE检查官方SDK累积、响应头前取消、禁重定向、流中取消与旧结果隔离；不是实际模型或Android执行 |
| AgentLoopPanel.test.tsx | 遮罩/清空、固定目标、取消、错误不回显、初始化晚返回不覆盖编辑；不是GUI验收 |
| Chapter11CredentialStoreTest / Chapter11SdkRuntimeTest | API36 ARM64已执行，隔离别名/演示凭据；runtime用合成事件、不请求网络 |
| Chapter11LocalGatewayIntegrationTest | 真实模型首轮与三回合完整回放；后者只提交合成工具观察，不执行/批准工具 |
| 真实App任务 | 正常包三模型回合、两真实工具、手机批准CSV3行、来源和清理核对；早期新任务/拒绝/取消/过期另记 |

App typecheck/lint/Jest/audit、相关JVM与ARM64 Debug/Release/Test构建分别运行；参数 `-PreactNativeArchitectures=arm64-v8a --max-workers=2`。网站另跑toolchain/typecheck/build/audit与实际页面，隔离构建设置 `DOCUSAURUS_NO_PERSISTENT_CACHE=1`。文档改写不代表重新执行设备/模型验收。

设备验收冻结源码与App/Test SHA，使用自有或授权演示设备、合法endpoint与非敏感演示数据。mock、HTTP401、编译或JVM通过不写成真实工具闭环；不发布调试签名APK。

</details>

<details>
<summary>实际任务批次与原失败记录</summary>

2026-11-20：源码检查点 `749199f`；Debug APK SHA256 `58b62dda2ffb74f413164c152cfb01cd3b5da07e92d17ab71bd8fe9fcfe98b76`，Test APK `6b96dbb4742acc58a6a36fec139e8f51815d905a6488b8cb2f2144fbfd52754f`。本机Ferry/Seed2.1Turbo三回合、累计201输出token，两条来源成功且清理确认。

2026-11-23：合入已发布章节历史的正常包完成 `task-1`，三回合、520输出token、3157字节上下文；CSV读取143ms、3行、五阶段0，设备与CSV同账本。Debug APK `134329e82f2a2abda257b9fd7da78305baae8ab37a3137625e795f164377502b`，Test APK `924cd2b1e36c4bdd10b2c2124f727650b34c361f3b3ecc5c4679cc159bd304b3`；90taskhost JVM零失败/跳过，三种构建通过，同包Keystore/runtime/首轮/三回合回放/context共5项Android通过。

原两次审批过期、两次第三回合 `MODEL_TRANSPORT_ERROR` 保留。独立Android三回合协议检查通过；临时错误类型诊断运行完成两回合，未产生错误类型。恢复原production transport并确认正常包无诊断logger后，取得章首三回合成功。成功不证明此前网络错误根因已查明，不给旧失败补写成功。

项目没有Release单元测试task；改用实际Release编译类验证HTTPS允许、Loopback HTTP拒绝，不把不存在的task记为通过。PR#20完整Linux Android CI与main CI/Pages通过；历史页面的实际图片已解码并与源码SHA核对，源码归档已匿名下载/全文件核对。

</details>

## 本章源码

- [PR #18：原生模型循环与加密配置](https://github.com/kkkhs/pocketrun/pull/18)
- [PR #22：任务主线、原位代码与图解](https://github.com/kkkhs/pocketrun/pull/22)
- [PR #20：固定Debug策略与真实模型/工具验收](https://github.com/kkkhs/pocketrun/pull/20)
- [chapter-11 源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-11)：注释tag固定验收合并 `8f95aa7`，完整源码和SHA256已核对。
- [AgentLoopPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/AgentLoopPanel.tsx) → [TaskHostModule.kt](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt) → [AgentLoop.kt](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AgentLoop.kt)：从开始按钮沿一回合阅读。
