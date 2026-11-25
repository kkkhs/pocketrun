---
sidebar_position: 3
sidebar_label: 第 11 章 · 真实模型 Agent 循环
title: 第 11 章：接上大模型，让 Agent 自己选下一步
description: 用官方 Anthropic Java SDK 在 Kotlin 中实现有界 AgentLoop：流式调用与即时取消、整回合校验、完整消息回放、白名单观测摘要与 Keystore 加密配置。
---

import ModelToolCycle from './assets/model-tool-cycle.svg';
import modelResult from './assets/chapter-11-model-result.png';
import toolLedger from './assets/chapter-11-tool-ledger.png';
import csvSource from './assets/chapter-11-csv-source.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

前十章，我们像搭积木一样，一块一块拼出了手机上的执行通道、工具网关和任务账本。但直到第 10 章，决定“先调设备信息、再调 CSV”的还是屏幕前的你。

这一章，我们终于把**大模型大脑**插进这具身体里！

任务目标保持不变：**“先读取设备信息，再读取固定演示 CSV 行数，用真实结果总结；不要访问其他数据。”** 只不过这一次，你只需要点一次“启动 Agent”，接下来模型会自己判断该先调哪个工具、读完结果后再调下一个工具、最后汇总出答案——而当模型提出读取 CSV 时，程序依然会在手机上停下来，等你亲手点下**同意**。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={modelResult} alt="实际 task-1 三个模型回合完成，模型总结 Android16、API36、arm64-v8a 和 CSV 三行" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>一次真实的 3 回合模型任务完成截图：模型依次请求设备信息与 CSV（经手机批准），最后基于真实返回结果给出总结。</figcaption>
</figure>

## 先看清：模型跑在哪？循环跑在哪？

先把三个概念彻底拆开，避免混淆：

| 组件 | 运行位置 | 干什么 |
| --- | --- | --- |
| **大模型推理服务** | 远端 HTTPS 服务（或 Debug 专用的本机调试网关） | 读取对话历史与工具定义，推理出下一条 `tool_use` 或最终文字总结 |
| **SDK + `AgentLoop` + `SingleTaskContext` + `TaskHost`** | **PocketRun 普通 App 进程的 Kotlin 层** | 管理消息历史、发流式 HTTP 请求、校验模型提议、通过原账本与宿主执行工具 |
| **React Native UI** | 同一个普通 App 进程的 JS 层 | 填写模型配置、下发目标、轮询显示进度、点击手机审批或取消 |

:::tip 为什么把 AgentLoop 和 SDK 写在 Kotlin？
1. React Native 不是 Node.js，官方 TypeScript SDK 并不直接支持 RN 运行时；而在 Kotlin 里可以直接引入官方的 `com.anthropic:anthropic-java:3.11.0`（支持 Android API 26+），无需折腾脆弱的 JS polyfill。
2. 凭据加密（Android Keystore）、`TaskHost`、`SingleTaskContext` 和取消生命周期原本就都在 Kotlin 层；把循环放在同一层，可以保证网络流关闭和工具取消严丝合缝，不依赖 JS 页面是否存活。
:::

## 一次 3 回合任务的真实轨迹

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <ModelToolCycle className={`pr-model-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-model-title" aria-describedby="pr-model-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>标准的 ReAct / Tool-Use 循环：每一回合都带着完整历史重新请求模型，而真正动手机器的是复用自前章的 TaskHost。</figcaption>
</figure>

把刚才截图里的那次完整执行摊开看，其实就是一个极其干净的 3 回合对话：

| 回合 | 模型返回什么 | 手机本地实际发生了什么 |
| --- | --- | --- |
| **第 1 回合** | `stop_reason: "tool_use"` → 调用 `android_device_info` | 校验通过 → 经 `SingleTaskContext` 免审批执行 → 拿到 Android 16 / API 36 / `arm64-v8a` → 把白名单摘要装成 `tool_result` 发回模型 |
| **第 2 回合** | `stop_reason: "tool_use"` → 调用 `linux_count_demo_csv` | 校验通过 → 停在 **`PENDING_APPROVAL`** → 用户在手机点“同意” → 真实 PRoot/BusyBox 读出 3 行并清理完毕 → 把摘要发回模型 |
| **第 3 回合** | `stop_reason: "end_turn"` → 给出最终文本总结 | 核对任务已有成功步骤且清理完成 → 调用 `task.finish(taskId)` → 界面展示最终总结 |

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <img src={toolLedger} alt="同一完成任务保留设备信息，CSV审批为approved_by_app_ui、清理为proven" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>账本视角：模型驱动的两条步骤照旧记在第 10 章的同一份账本里，CSV 明确记录为手机批准。</figcaption>
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <img src={csvSource} alt="同一次CSV来源记录为三行、五阶段退出码0、source completed、cleanup done，耗时143ms" style={{display: 'block', width: '100%', height: 'auto'}} />
    <figcaption>来源视角：向下滚动即可看到真实 PRoot/Alpine 的 3 行结果、5 阶段退出码 0 与清理确认。</figcaption>
  </figure>
</div>

注意：3 回合不是写死的流程！大模型完全有可能在一个回合里同时提出两个 `tool_use`——我们的循环会先把整回合校验通过，再按顺序一条一条串行提交给 `SingleTaskContext`。

## 拆解工业级 AgentLoop 的 5 个关键设计

如果你以前只写过 30 行脚本版的 `while (true)` Agent Demo，读一遍 PocketRun 的 [`AgentLoop.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AgentLoop.kt)、[`AnthropicModelTransport.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AnthropicModelTransport.kt)、[`ModelToolSurface.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ModelToolSurface.kt) 和 [`ModelCredentialStore.kt`](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ModelCredentialStore.kt)，会立刻感受到“能跑的 Demo”和“可控的移动端工程”之间的区别：

### 1. 用流式传输换“秒级取消”，而不是为了打字机效果
在 [AnthropicModelTransport.kt](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AnthropicModelTransport.kt) 里，我们用的是官方异步客户端的 `createStreaming()` + `MessageAccumulator`：

```kotlin title="AnthropicModelTransport.kt（流式请求、预算护栏与即时取消）"
val params = MessageCreateParams.builder()
    .model(credential.settings.model)
    .maxTokens(4096L)
    .thinking(ThinkingConfigAdaptive.builder().build())
    .system(ModelToolSurface.SYSTEM_PROMPT)
    .messages(history)
    .apply { ModelToolSurface.tools().forEach { addTool(it) } }
    .build()

// 禁用自动重定向，防止把 API Key 意外转给陌生主机
val stream = client.messages().createStreaming(
    params,
    RequestOptions.builder().followRedirects(false).build(),
)

val accumulator = MessageAccumulator.create()
stream.subscribe { event ->
    check(!request.cancelled.get())
    wireBytes += SdkJsonBudget.bytes(event)
    check(++events <= 2048 && wireBytes <= 262_144) { "MODEL_RESPONSE_BUDGET" }
    accumulator.accumulate(event)
}
```

为什么要流式接收，又攒满一整条 `Message` 才交给循环？两个大好处：
- **随时可取消**：`AsyncStreamResponse` 在响应头还没回来之前就能被 `stream.close()` 立刻掐断，用户点“取消”不用干等 20 秒超时。
- **边收边限流**：如果远端异常吐出海量事件，超过 2048 条 SSE 或 256 KiB 立刻掐断，不给 OOM 留机会。同时我们绝不会在流还没收完时就去执行半截 JSON 参数的工具！

### 2. 整回合先全量校验，再执行第一条
收到完整 `Message` 后，[AgentLoop.acceptMessageLocked()](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AgentLoop.kt) 会先像安检仪一样把整条回复扫一遍：

```kotlin title="AgentLoop.kt（整回合校验）"
val reason = message.stopReason().orElseThrow().asString()
if (reason !in setOf("tool_use", "end_turn")) {
    stopLocked(AgentPhase.FAILED, if (reason == "refusal") "MODEL_REFUSAL" else "INCOMPLETE_MODEL_RESPONSE")
    return
}
// 整回合所有工具调用必须全部通过白名单、Schema、ID 唯一性校验，才开始执行第一条
calls.forEach { ModelToolSurface.parse(it) }
require(calls.map { it.id() }.distinct().size == calls.size)
require(calls.none { it.id() in seenIds })
```

如果模型因为 `max_tokens` 被截断、拒答（`refusal`）、编造了一个不存在的工具名、或者复用了一个用过的 `tool_use` ID，整个回合在动任何一根手指之前就会被干脆拦下。

### 3. 原样回放历史消息 + 精准配对 `tool_use_id`
大模型本身是**无状态**的——手机上执行完工具，模型并不会心灵感应知道结果，必须由我们把结果重新拼进 `history` 再发下一轮请求。

这里有两个新手极易写错的协议坑：
1. **必须原样保留完整的 assistant 消息**：代码里用的是官方 SDK 的 `val replay = message.toParam()`。当模型开启了自适应思考（`ThinkingConfigAdaptive`）时，回复里会夹带着带签名的 `thinking` 块和 `tool_use` 块；如果你只把文本抽出来保存，下一轮发回去模型服务会直接报错！
2. **分清 `tool_use_id` 和 `requestNonce`**：
   - `tool_use_id` 是模型在协议里生成的调用 ID，我们回传 `ToolResultBlockParam` 时必须原样填上它，告诉模型“这份结果对应你刚才哪个请求”；
   - `requestNonce` 则是手机本地 `TaskHost` 签发的审批单号，只在手机 UI 点击“同意 / 拒绝”时使用，绝不会交给模型，模型更不可能自己伪造审批。

### 4. 白名单观测摘要：不让隐私和原始 stdout 裸奔出手机
看看 [ModelToolSurface.summary()](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ModelToolSurface.kt)——这是移动端 Agent 极重要的一道隐私与防注入防线：

```kotlin title="ModelToolSurface.kt（只放行极小的白名单观测字段）"
fun summary(step: ContextStep): String {
    val allow = when (step.call.tool) {
        PocketRunTool.ANDROID_DEVICE_INFO.wireName -> setOf("androidVersion", "apiLevel", "supportedAbis")
        PocketRunTool.LINUX_COUNT_DEMO_CSV.wireName -> setOf("lineCount", "exitCodes", "stageExitCodes", "sourceStatus", "cleanupStatus")
        else -> setOf("displayId", "displayEpoch", "action", "sourceStatus")
    }
    val fields = step.status.result?.fields.orEmpty().filterKeys { it in allow }
    require(fields.values.all { it.toByteArray(Charsets.UTF_8).size <= 256 }) { "OBSERVATION_BUDGET" }
    return (listOf("phase=${step.status.phase.name}", "cleanup=${step.status.cleanupState.name}") +
        fields.toSortedMap().map { (key, value) -> "$key=$value" }).joinToString("\n")
}
```

只有极少数明确允许的结构化字段能穿过网络发给模型；原始 stdout、本地绝对路径、截图 URI、原始 UI 正文和任何凭据一律留在手机本地。

### 5. API Key 怎么存？Keystore AES-GCM + 禁止 JS 读回
在手机 App 里千万别把用户的 API Key 丢进明文 `AsyncStorage`！[ModelCredentialStore.kt](https://github.com/kkkhs/pocketrun/blob/chapter-11/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ModelCredentialStore.kt) 的做法干净利落：
- 在 `AndroidKeyStore` 里生成不可导出的 **AES-256-GCM** 密钥；
- 用带 AAD 认证的密文信封，通过 `AtomicFile` 写入 **`noBackupFilesDir`**（防止被系统自动备份到云端）；
- JS 侧提交凭据后立刻清空输入框；且**没有任何 JS 接口能把 API Key 读回去**——`getModelConfiguration()` 只返回 `endpoint`、`model` 和布尔值 `hasCredential`。
- 网络协议上，**Release 包强制 HTTPS-only**；Debug 包额外仅放行精确的本机调试地址 `http://127.0.0.1:8790`（方便本地通过 `adb reverse` 连接本机兼容网关进行开发联调）。

### 6. 整条循环的硬预算护栏
`AgentLoop` 在单调时钟上层层设防，绝不跑飞：最多 **8 个模型回合**、最多 **8 个工具步骤**、单次模型请求 **25 秒**（SDK 20 秒）、整条任务总期限 **120 秒**、累计输出最多 **32,768 tokens**、序列化后的完整消息历史最多 **64 KiB**。任何一项越界，立刻停机并取消活跃工具。

## 动手跑一遍

1. 先在第 9 章面板点一次 **准备固定 Linux 工具**。
2. 滚动到 **第 11 章 · 真实模型循环** 面板，填入你有权调用的 Anthropic 兼容 `endpoint`（正式构建须为 HTTPS；Debug 构建也可通过 `adb reverse tcp:8790 tcp:8790` 连接本机 `http://127.0.0.1:8790`）、模型名和 API Key，点击 **加密保存模型配置**（确认输入框自动清空，状态变为“已加密保存”）。
3. 点击 **启动演示 Agent**，观察整个闭环如何自动运转：
   - 第 1 回合模型自动调用设备信息，无需你干预即完成；
   - 第 2 回合模型申请读取演示 CSV，任务稳稳停在 `pending_approval`；
   - 到第 10 章当前步骤面板查看绑定目标，点击 **同意任务当前步骤**；
   - Linux 执行完后结果摘要送回模型，第 3 回合模型给出总结，面板展示最终文字，且下方账本完整保留了两条真实工具来源！

:::tip 永远交叉核对账本，不要只信模型说的话
哪怕模型在最后一句自信地写着“CSV 共有 3 行”，也要回头核对第 10 章账本里是不是真的有一条 `linux.count_demo_csv` 的成功记录。**模型的总结是语言产出，账本里的来源和退出码才是事实证据。**
:::

## 全书阶段总结与下一步

从第 1 章点下第一个“检查设备”按钮，到第 11 章看着大模型在手机上自主串联设备查询与受控 Linux 工具、并在关键一步停下来等你批准——你已经亲手把一个 **Android 本机 AI Agent 的完整骨架** 从零建了出来：

- **第一部分（第 1—5 章）**：打通 TurboModule、自有无线 ADB、UID 2000 `app_process` Binder 后端与隔离虚拟屏的看和点。
- **第二部分（第 6—8 章）**：在普通 App 沙箱内建起私有工作区、防死锁管道、PRoot + Alpine 用户态和真正的 JNI PTY 终端。
- **第三部分（第 9—11 章）**：用 `TaskHost`、`SingleTaskContext` 和 `AgentLoop` 把底层能力收束成安全、有界、可审批的真实模型闭环。

这 11 章打下了最关键的地基，但离一个完整的日常 Android AI 助手还有不少激动人心的拼图等待补齐——比如跨任务的可编辑记忆、把“读 CSV → 填虚拟屏 App → 写 `result.txt`”全自动串联起来、更完整的 Linux 包管理、给模型看截图/UI 树的观察协议等。欢迎继续查看 [Ditto 功能对照与后续路线](ditto-parity.md)！

- **本章源码快照**：[Release `chapter-11`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-11) · [PR #18](https://github.com/kkkhs/pocketrun/pull/18) / [PR #20](https://github.com/kkkhs/pocketrun/pull/20)
- **延伸参考**：[Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) · [实机验收与工程档案](verification-records.md)
