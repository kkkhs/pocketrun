---
sidebar_position: 3
sidebar_label: 第 16 章 · 通用第三方 App 操控与实时预览
title: 第 16 章：让 AI 在后台虚拟屏操控任意第三方 App
description: 打破固定 Demo 限制：扩展 UID 2000 Binder 后端支持启动、观察、点击、平滑滑动、按键与中文输入任意第三方 App，在对话页内嵌实时虚拟屏预览窗，并加入断网重试与免费模型预设。
---

import useBaseUrl from '@docusaurus/useBaseUrl';
import AppAutomationFlow from './assets/app-automation-flow.svg';
import openApprovalImg from './assets/chapter-16-app-open-approval.png';
import observePreviewImg from './assets/chapter-16-app-observe-preview.png';
import doneTopImg from './assets/chapter-16-app-done-top.png';
import doneBottomImg from './assets/chapter-16-app-done-bottom.png';
import swipeLiveImg from './assets/chapter-16-app-swipe-live.png';
import freePresetsImg from './assets/chapter-16-free-presets.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

走完第 15 章，PocketRun 已经能用双协议流畅对话、读写私有工作区文件、跑 PRoot/Alpine Linux 命令。但如果你问它一句：**“帮我打开手机里的系统设置看看”**，它还是束手无策。

原因我们在第 5 章埋下过伏笔：底层 UID 2000 虚拟屏后端其实早就打通了，但为了教学安全我们加了两把锁——它写死了只能启动 PocketRun 自带的 `DisplayDemoActivity`，而且触摸和按键注入通过 `injectInputEventToTarget(..., appUid)` 锁死了只能发给 PocketRun 自己。

这一章，我们正式参考 Ditto（含 v0.1.23 的断网等待恢复与省电停帧思路），在不放松隔离与审批底线的前提下，解锁 Android AI 助手最核心的能力：**在后台 720×1280 隔离虚拟屏里，让大模型亲手打开、看见并操控手机上任意第三方 App，同时把画面实时嵌在对话页顶部给你看！**

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <AppAutomationFlow className={`pr-ch16-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-ch16-title" aria-describedby="pr-ch16-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>第 16 章完整链路：普通 App UID 负责列出可启动应用目录，UID 2000 Binder 后端负责在独立虚拟屏运行与操控第三方 App，全部高敏动作经 TaskHost 手机审批，并把最新帧实时回传对话页预览窗。</figcaption>
</figure>

## 1. 解锁 UID 2000 后端：为什么能操作第三方 App 又不抢主屏？

我们在 [`IPrivilegedBackend.aidl`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl) 末尾非破坏性地追加了 5 个方法（保留第 3—13 章原有方法顺序和语义纹丝不动）：

- **`launchAppOnDisplay(expectedDisplayId, packageName)`**：先通过 `/system/bin/cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER <pkg>` 解析出目标应用的启动 Activity（例如 `com.android.settings/.Settings`），再用 `/system/bin/am start -W --display <displayId> -n <component>` 把它精准拉起在后台虚拟屏上。
- **`injectAppTap` / `injectAppSwipe` / `injectAppKey`**：与第 5 章锁死 `appUid` 的 `DisplayDemoActivity` 路径不同，通用第三方 App 通道在调用 `IInputManager.injectInputEvent` 时**放开了目标包 UID 限制，但依然死守 `expectedDisplayId > 0` 与 `event.getDisplayId() == expectedDisplayId` 双重核验**——事件只能落在后台虚拟屏里当前显示的第三方窗口，绝不会误点你的手机主屏。其中 `appSwipe` 按 16–20ms 步长做多段 `ACTION_MOVE` 插值，让 Android 的 `RecyclerView` 和列表能顺滑滚动。
- **`injectAppText`（无需额外输入法即可输入中文）**：第 5 章的 `KeyCharacterMap` 只能敲 ASCII 字母。本章在 [`DisplayAutomation.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayAutomation.kt) 里新增 `setFocusedText(text)`，直接在目标虚拟屏的 `UiAutomation` 窗口树中找到已获焦的可编辑输入框，调用原生无障碍动作 **`ACTION_SET_TEXT`**——不管是中文、英文还是标点都能一步填进第三方 App 的输入框；如果遇到不响应 `ACTION_SET_TEXT` 的自绘控件且文本为 ASCII，再自动回退到键盘事件注入。

同时，我们在 [`AndroidManifest.xml`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/AndroidManifest.xml) 中声明了标准的 `ACTION_MAIN + CATEGORY_LAUNCHER` `<queries>`，让普通 App UID 侧就能直接用 `PackageManager` 查到所有可启动应用的中英文名与包名（例如 `Settings (com.android.settings)`），并做了关键词未命中自动回退全量列表的贴心优化——避免英文系统里搜“设置”返回空结果白白浪费一轮对话。

## 2. 一次真实的 Android 16 踩坑实录：为什么 `com.android.settings` 一开始返回 0 个控件？

这一章在 Android 16（API 36）实机上第一次真正打开第三方「系统设置」时，我们立刻碰到了一个**靠单测永远测不出来的真实系统坑**：

预览窗里明明已经清清楚楚画出了完整的 `Network & internet`、`Apps`、`Display`，但 `app.observe` 返回的竟然是 **`0 个控件`**！

扒开 `logcat` 和 [`DisplayAutomation.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayAutomation.kt) 一看，真相瞬间大白：
- 前 13 章自写的 `DisplayDemoActivity` 只有 3 个静态控件，全在屏幕内，每个节点的 `boundsInScreen` 都满足 `top <= bottom`；
- 而真实的第三方 **`com.android.settings` 使用了可滚动的 `RecyclerView`**，Android 16 无障碍框架会对滚出视野的屏外裁剪子节点返回**倒置矩形**（例如 `Rect(63, 290 - 1017, -1333)`，连系统自带的 `AccessibilityNodeInfoDumper` 都会打日志 `Skipping invisible child`）；
- 原版 `DisplayAutomation.kt` 见一个节点就 `check(bounds.left <= bounds.right && bounds.top <= bounds.bottom)`，遇到第一个屏外子节点直接抛异常，把整棵 UI 树整棵判成了 `QueryFailed`！

修复方法非常干净：遍历无障碍树时，非根节点遇到倒置裁剪边界直接跳过不可见子树，并把复杂页面的树查询超时从 1.8 秒放宽至 3.5 秒：

```kotlin title="DisplayAutomation.kt（跳过 Android 16 RecyclerView 屏外裁剪节点）"
val bounds = Rect()
node.getBoundsInScreen(bounds)
if (bounds.left > bounds.right || bounds.top > bounds.bottom) {
    if (parent >= 0) return
    bounds.right = maxOf(bounds.left, bounds.right)
    bounds.bottom = maxOf(bounds.top, bounds.bottom)
}
```

紧接着，[`UiTreeSummaryFormatter.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/UiTreeSummaryFormatter.kt) 会把原始上百个嵌套布局容器过滤压缩成**最多 80 条、上限 7.5 KiB** 的人类与大模型都一目了然的精简清单，只保留屏内有文字、有描述、可编辑或带 ID 的可点控件，并自动算好中心坐标：

```text
[1] "Search Settings" id=search_action_bar center=(360,118) [clickable]
[2] "Network & internet" id=title center=(340,262) [clickable]
[3] "Mobile, Wi-Fi, hotspot" id=summary center=(340,305)
```

修完重装一跑，模型瞬间读出 17 个清晰控件，并且立刻自己找准坐标完成了点击和滑动！

## 3. 7 个对话级 `app.*` 工具、自动绑定与手机端审批

为了让大模型不必每次手动背诵 `expectedDisplayId` 和 `expectedDisplayEpoch`，我们在 [`ToolContract.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ToolContract.kt) 和 [`TaskHost.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskHost.kt) 中设计了 7 个干净直观的对话工具，由原生层在提交时**自动快照绑定当前活跃虚拟屏的 `displayId` 与 `displayEpoch`**，并写入审批 SHA-256 哈希，执行前再次核验防失配：

| 工具名 | 作用 | 是否需要手机审批 | 典型参数 |
| --- | --- | --- | --- |
| `app.list_apps` | 列出手机可启动应用名与包名 | 否（只读应用清单） | 可选 `query` |
| `app.open` | 在后台虚拟屏启动第三方 App | **是** | `package_name` |
| `app.observe` | 读取当前虚拟屏精简交互控件与坐标 | **是** | 无 |
| `app.tap` | 在虚拟屏指定坐标点击 | **是** | `x`（0..719）、`y`（0..1279） |
| `app.swipe` | 在虚拟屏执行多步平滑滑动 | **是** | `x1, y1, x2, y2`、可选 `duration_ms` |
| `app.input_text` | 向焦点输入框填入中英文文本 | **是** | `text`（1..256 字符） |
| `app.press_key` | 按下导航或编辑键 | **是** | `BACK` / `HOME` / `ENTER` / `DEL` 等 |

## 4. 内嵌虚拟屏实时预览、断网重连与免费模型预设

结合 Ditto v0.1.23 的实际产品打磨经验，本章在体验层同步补齐了四项关键细节：

1. **对话页顶部内嵌实时预览窗（[`ChatScreen.tsx`](https://github.com/kkkhs/pocketrun/blob/main/app/ChatScreen.tsx)）**：只要虚拟屏活跃，聊天顶部就会显示绿色状态条与可展开/收起的实时缩略窗；每当 `app.open`、`app.observe`、`app.tap`、`app.swipe` 完成，后端顺手捕回一帧 JPEG 刷新预览——**平时空闲不做高频后台轮询**，既能眼见为实，又避免发热耗电和阻挡熄屏。
2. **全面屏软键盘适配**：外层加上 `KeyboardAvoidingView`，解决 Android 16 edge-to-edge 模式下弹出输入法遮挡底部输入框的问题。
3. **瞬时断网自动退避重试（[`ChatEngine.kt`](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ChatEngine.kt)）**：遇到 `IOException` 或 `5xx` / `429` 瞬时网络波动时，不再直接把整轮多步任务判死，而是提示 `网络波动，2 秒后自动重连（第 1/2 次）…` 并按 2 秒 / 4 秒退避自动重连（鉴权类 `4xx` 错误仍立即快速失败，且重试期间随时可点「停止」取消）。
4. **免费模型一键预设（[`SettingsScreen.tsx`](https://github.com/kkkhs/pocketrun/blob/main/app/SettingsScreen.tsx)）**：新增 **智谱免费（`glm-4-flash`）** 与 **Gemini 免费（`gemini-2.5-flash`）** 两个一键预设芯片，手头没有付费订阅也能零费用把全套功能跑起来。

## 实机运行截图与无剪辑录屏

以下 6 张截图与 33.6 秒无剪辑录屏全部实拍于专用 **Android 16（API 36 ARM64）模拟器 `pocketrun-ch1-api36`**（经应用内真实按钮完成无线 ADB 连接 → 启动 UID 2000 后端 → 创建隔离虚拟屏，经本机兼容网关调用 `claude-doubao-seed-2-1-turbo`）：

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '1 1 260px', minWidth: 0, maxWidth: 340}}>
    <img src={openApprovalImg} alt="对话页显示虚拟屏 #37 已就绪，弹出批准打开 com.android.settings 的手机确认卡" style={{display: 'block', width: '100%', height: 'auto', borderRadius: 12, border: '1px solid var(--ifm-color-emphasis-300)'}} />
    <figcaption>顶栏亮起「虚拟屏 #37 已就绪」，模型请求启动 <code>com.android.settings</code> 前先停在手机审批卡。</figcaption>
  </figure>
  <figure style={{margin: 0, flex: '1 1 260px', minWidth: 0, maxWidth: 340}}>
    <img src={observePreviewImg} alt="允许启动后对话页顶部实时预览窗立刻出现 Android 系统设置主页，同时弹出观察界面审批卡" style={{display: 'block', width: '100%', height: 'auto', borderRadius: 12, border: '1px solid var(--ifm-color-emphasis-300)'}} />
    <figcaption>点击「允许执行」后，第三方「系统设置」已在虚拟屏 #37 跑起，顶部实时预览窗立即刷新出真实画面。</figcaption>
  </figure>
</div>

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '1 1 260px', minWidth: 0, maxWidth: 340}}>
    <img src={doneTopImg} alt="观察读出 17 个设置控件后，模型开始输出带编号的中文 Markdown 汇总" style={{display: 'block', width: '100%', height: 'auto', borderRadius: 12, border: '1px solid var(--ifm-color-emphasis-300)'}} />
    <figcaption><code>app.observe</code> 精准读出 <code>com.android.settings</code> 的 17 个界面控件，模型立刻用中文 Markdown 分条汇报。</figcaption>
  </figure>
  <figure style={{margin: 0, flex: '1 1 260px', minWidth: 0, maxWidth: 340}}>
    <img src={doneBottomImg} alt="完整汇总 Display、Notifications、Sound、Modes 等全部可见设置项（731 output tokens）" style={{display: 'block', width: '100%', height: 'auto', borderRadius: 12, border: '1px solid var(--ifm-color-emphasis-300)'}} />
    <figcaption>完整汇总当前屏内 7 大设置入口（731 output tokens），全程主屏始终留在 PocketRun 对话页，零抢屏干扰。</figcaption>
  </figure>
</div>

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '1 1 260px', minWidth: 0, maxWidth: 340}}>
    <img src={swipeLiveImg} alt="在另一次多步滑动实测中，模型在虚拟屏 #35 连续执行点击返回与平滑滑动，预览窗实时滚出 Storage、Battery 与 System" style={{display: 'block', width: '100%', height: 'auto', borderRadius: 12, border: '1px solid var(--ifm-color-emphasis-300)'}} />
    <figcaption>多步点击与滑动实测（虚拟屏 #35 批次）：<code>app.swipe (360,1000)→(360,400)</code> 实时把「设置」滚到 <code>Storage</code>、<code>Battery (100%)</code> 与 <code>System</code>。</figcaption>
  </figure>
  <figure style={{margin: 0, flex: '1 1 260px', minWidth: 0, maxWidth: 340}}>
    <img src={freePresetsImg} alt="设置页新增智谱免费与 Gemini 免费一键预设，下方显示虚拟屏已就绪" style={{display: 'block', width: '100%', height: 'auto', borderRadius: 12, border: '1px solid var(--ifm-color-emphasis-300)'}} />
    <figcaption>「设置」页新增「智谱免费」「Gemini 免费」一键预设，底部实时显示当前连接的虚拟屏状态。</figcaption>
  </figure>
</div>

<figure style={{margin: '1.5rem 0', maxWidth: 380}}>
  <video
    controls
    playsInline
    preload="metadata"
    poster={doneTopImg}
    style={{display: 'block', width: '100%', height: 'auto', borderRadius: 12, border: '1px solid var(--ifm-color-emphasis-300)', background: '#000'}}
  >
    <source src={useBaseUrl('/media/chapter-16-demo.mp4')} type="video/mp4" />
  </video>
  <figcaption>Android 16 ARM64 无剪辑实机录屏（33.6 秒）：从对话首页发起请求 → 审批打开第三方「系统设置」→ 顶部实时预览窗即时出现设置界面 → 审批观察 17 个控件 → 流式输出中文 Markdown 汇总 → 切入「设置」页查看免费模型预设。</figcaption>
</figure>

## 本章小结与下一步

第 16 章真正跨过了 Android AI 助手最关键的门槛：**从“只能操作自己的教学 Demo”升级为“能在后台隔离虚拟屏里打开、看懂、点击、滑动、输入中文操作任意第三方 App，并把画面实时嵌在对话页供用户监看与审批”**，同时补齐了断网退避重试与免费模型一键预设。

对照最新的 [Ditto 功能对照（含 v0.1.23 特性）](ditto-parity.md)，后续第 17 章可以按需继续推进：
- **引导式一键无线 ADB 配对向导 + 前台保活服务（`AgentService`）**：把连接无线调试从「实验室」做成面向普通用户的分步引导卡片；
- **可选的受控主屏模式 / 终端 `adb_shell` 桥接 / 多模态截图直传**：支持无法在虚拟屏运行的特殊应用（带显式授权与视觉边框提示）以及把压缩后的虚拟屏画面直接送给视觉大模型。

- **核心源码入口**：[IPrivilegedBackend.aidl](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl) · [VirtualDisplaySession.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/VirtualDisplaySession.kt) · [DisplayInput.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayInput.kt) · [DisplayAutomation.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayAutomation.kt) · [UiTreeSummaryFormatter.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/UiTreeSummaryFormatter.kt) · [ChatScreen.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/ChatScreen.tsx)
- **测试与验证档案**：[实机验收与工程档案](verification-records.md)
