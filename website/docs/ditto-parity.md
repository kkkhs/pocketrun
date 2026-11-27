---
sidebar_position: 2
sidebar_label: Ditto 功能对照与路线
title: Ditto 功能对照与后续演进路线
description: 明确 PocketRun 的最终能力目标、当前第 1—13 章已经打通的底座，以及与完整 Android AI 助手之间的明确差距和待核实项。
---

PocketRun 的第 1—13 章把一套本机 Android AI 助手的骨架搭了出来，但这只是起点——**我们的最终目标，是在保持这套本机执行架构的前提下，逐步覆盖 Ditto 整体背后的核心能力，同时用清晰可读的教程把每一层原理讲透。**

固定 Demo、最小 BusyBox/musl、三个固定工具和两步模型任务都是循序渐进的教学阶段，绝不是最终的功能上限。这一页如实记录当前做到哪一步、还差什么，以及哪些原产品行为仍需要进一步取证。

## 当前进度：已经建好的底座（第 1—13 章）

- **Android 控制通道**：App 自有无线 ADB → UID 2000 独立 `app_process` → typed Binder（AIDL）→ 后端持有的 720×1280 隔离 `VirtualDisplay`、共享内存截图、有界 UI 树和定向输入。
- **Linux 与终端通道**：普通 App UID 私有工作目录 → `ProcessBuilder` 管道 → 基于 `nativeLibraryDir` + PRoot 的最小 Alpine（BusyBox/musl）→ 基于 JNI `/dev/ptmx` 的真实交互式 PTY。
- **工具、对话产品外壳与模型编排**：确定性审批网关 `TaskHost` → 单任务账本 `SingleTaskContext` → 基于 Anthropic Java SDK 的流式 `AgentLoop` 与多轮 `ChatEngine` → 独立域加密的多会话存储 `ConversationStore`、偏好记忆 `MemoryStore` 与运行日志 `TaskJournal` → Gemini 风格多会话对话界面、内联审批卡片与通用私有工作区文件读写工具（`list / read / write`）。

## 已知的 Ditto 0.1.14 → 0.1.23 静态对照依据

我们在对齐同类产品时坚持**只认直接静态事实、绝不搬运闭源源码**的原则。针对 **Ditto 0.1.14** 及 **0.1.23** 安装包的高层静态结构分析（Manifest、数据库表结构、资源与组件清单）确认了以下产品架构演进特征：

- **产品形态**：以多会话 AI 对话为主入口（类似 Gemini / Claude App），配合 Room 数据库持久化 `conversations`、`chat_messages`、`global_memories`、`routines`（定时/快捷例程）与 `token_usage`（按小时统计输入/输出/缓存 Token）。
- **核心页面**：主对话流与虚拟屏实时预览（`VdSurfaceView`）、文件管理器与代码编辑器（内置 TextMate 语法）、交互终端与扩展快捷键栏、记忆管理、MCP 服务配置、Skills 管理、定时例程、引导式无线 ADB 配对（`SetupActivity` / `AdbPairingService`）以及多通道接入（如远程 IM 控制）。
- **底层通道**：同样使用 `nativeLibraryDir` 下的 `libproot.so` / `libproot-loader.so` 与 `libtermux.so` 承载 Alpine Linux 环境，通过 `BinderExchangeProvider` + 高权限后端提供系统能力，并通过前台服务保持任务运行。
- **工具面**：涵盖通用工作区文件读写编辑（`read_file` / `edit_file`）、受控 Shell（`run_shell`）、通用 Android 界面操作（`open_app` / `click` / `long_press` / `input_text` / `scroll` / `screenshot` 与端侧 ML Kit OCR）、用户提问（`ask_user`）、记忆写入、例程保存、MCP 工具以及在手机 Linux 内运行外部 CLI Agent 的模式。
- **v0.1.23 新增产品方向**：
  - **可选主屏幕操控**：新增显式授权的主屏采集服务与视觉边框提示（`MainScreenCaptureService`、`MainScreenPermissionActivity`、`GlowBorderView`），用于整理桌面、编辑快捷设置或操作无法在虚拟屏运行的应用；
  - **横屏虚拟屏全屏**：虚拟屏支持横屏应用与游戏全屏展示；
  - **稳定性与功耗打磨**：网络中断时等待恢复而不直接判任务失败、修复偶发发热耗电与无法熄屏问题；
  - **特权命令升级**：终端内的特权桥接命令升级为 `adb_shell`（用法对齐桌面 `adb shell`，如 `adb_shell dumpsys battery`）。

> 上述静态结构分析仅用于明确产品演进方向与能力对照；PocketRun 所有实现均在 React Native + Kotlin 架构下独立从零编写，并始终坚持更严格的手机端人工审批与沙箱边界。

## 能力缺口与演进清单

| 能力维度 | PocketRun 当前状态（截至第 16 章） | 差距与后续推进方向 |
| --- | --- | --- |
| **Linux 用户态分发** | Alpine 3.22.8 派生的最小 BusyBox/musl（4 个 ELF、2 个代码绑定） | 显著小于完整 rootfs 范围；需按实际任务需求补齐常用工具、动态库和目录结构，并同步维护对应源码与开源许可 |
| **包管理与 `adb_shell`** | 暂不含 `apk` 包管理与终端 `adb_shell` 特权桥接 | 可在遵守 W^X 与手机审批的前提下，分章补齐受控包安装与面向诊断的 `adb_shell` 命令入口 |
| **对话产品体验** | 第 14—16 章已落地多会话流式对话、Markdown 渲染、双协议 + 免费模型一键预设、内置「文件」编辑器、**对话页内嵌实时虚拟屏预览窗**、软键盘适配与**断网自动退避重试** | 待补齐：图片/文件附件、语音输入、前台服务保活与引导式无线 ADB 配对向导 |
| **Agent 工具集** | 设备信息 + 工作区 `list / read / write` + PRoot `linux.run_shell` + **第 16 章 7 个通用第三方 App 操控工具（`app.list_apps / open / observe / tap / swipe / input_text / press_key`）** | 尚未开放通用网页抓取、用户主动提问（`ask_user`）、定时例程（`routines`）与 MCP 外部工具接入 |
| **Android 通用 App 操作** | **第 16 章已打通后台虚拟屏操控任意第三方 App**：解析启动、跳过屏外倒置裁剪的控件树摘要、点击、多步平滑滑动、按键与 `ACTION_SET_TEXT` 中文输入（已实机跑通系统「设置」） | 尚未支持**可选主屏幕操控模式**（用于拒绝虚拟屏的应用）、横屏虚拟屏自适应与长按/拖拽手势 |
| **终端交互界面** | 真实 PTY 后端 + React 64 KiB 纯文本预览 | 尚不具备 ANSI 颜色、光标定位和全屏 TUI 程序渲染能力；需按目标体验决定是否接入完整终端渲染组件 |
| **完整跨能力闭环** | 第 13 章跑通固定 Demo 闭环与崩溃恢复；第 14—16 章已跑通真实多轮文件读写、PRoot Shell 与第三方系统设置操控 | 复杂跨应用 + 文件 + 定时例程的多阶段自动编排与通用任务崩溃恢复待后续扩充 |
| **模型视觉与界面观察** | 第 16 章已支持手机审批后向模型回传压缩后的第三方 App 交互控件树与坐标（上限 7.5 KiB），同时将 JPEG 帧供前端实时预览 | 尚未把压缩截图直接作为多模态视觉块发给 VLM，也未接入端侧 OCR（用于无障碍树为空的游戏/自绘界面） |
| **持久化、恢复与记忆** | 已支持独立密钥域加密的 `ConversationStore`、`MemoryStore`、`ModelCredentialStore` 与 `TaskJournal` | 当前不含自动记忆提取/向量检索或跨进程断点续跑策略 |
| **设备与 OEM 兼容** | 已在 Android 16（API 36 ARM64）专用模拟器实机验证全套主流程（含截图与无剪辑录屏） | 尚未覆盖更多 Android 版本与主流 OEM 机型真机适配 |

:::note 关于 MCP、Skills、定时例程与多 CLI 代理
Ditto 0.1.14 的静态结构中确实包含 MCP 服务管理（`McpListActivity` / `mcp__`）、Skills 管理、定时/快捷例程（`routines` 表）以及在手机 Linux 中安装运行外部编码 CLI 的入口。这些能力会在完成 PocketRun 的核心对话产品外壳、通用文件/命令工具与通用 App 操控之后，按优先级分章节逐步引入。
:::

## 后续章节与验收原则

**第 12 章（可管理的跨任务记忆）**与 **[第 13 章（完整闭环与中断恢复）](chapter-13.md)** 均已验收并发布源码快照（[chapter-12](https://github.com/kkkhs/pocketrun/releases/tag/chapter-12) / [chapter-13](https://github.com/kkkhs/pocketrun/releases/tag/chapter-13)）。接下来将按上述缺口清单继续规划第 14 章及后续章节，逐步把固定演示演进为通用的工作区文件/命令工具与一般 App 操控能力。

无论后续功能如何扩充，全书始终守住三条不变的验收标准：
1. **逐项对齐**：每个新能力明确记录“预期协议 → PocketRun 实现入口 → 真实场景验证 → 剩余边界”。
2. **不放宽底线**：从 Demo 演进到通用工具时，绝不放松 UID 边界、正数 `displayId` 隔离、手机端审批和可证明清理约束。
3. **写给人看**：每一项新增能力的原理和工程权衡，都会同步写成清晰、自洽的教程章节。
