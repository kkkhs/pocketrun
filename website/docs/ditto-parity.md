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
- **工具与模型编排**：确定性审批网关 `TaskHost` → 内存单任务账本 `SingleTaskContext` → 基于 Anthropic Java SDK 的有界 `AgentLoop` → 可管理的加密记忆 `MemoryStore` → 跨通道原生完成门 `CompleteTaskContract` 与预写日志 `TaskJournal`。

## 已知的 Ditto 0.1.14 静态对照依据

我们在对齐同类产品时坚持**只认直接静态事实、绝不搬运闭源源码**的原则。针对 **Ditto 0.1.14** 安装包的静态结构分析（Manifest、Room 数据库表结构、资源与组件清单）确认了以下高层次架构特征：

- **产品形态**：以多会话 AI 对话为主入口（类似 Gemini / Claude App），配合 Room 数据库持久化 `conversations`、`chat_messages`、`global_memories`、`routines`（定时/快捷例程）与 `token_usage`（按小时统计输入/输出/缓存 Token）。
- **核心页面**：主对话流与虚拟屏实时预览（`VdSurfaceView`）、文件管理器与代码编辑器（内置 TextMate 语法）、交互终端与扩展快捷键栏、记忆管理、MCP 服务配置、Skills 管理、定时例程、引导式无线 ADB 配对（`SetupActivity` / `AdbPairingService`）以及多通道接入（如远程 IM 控制）。
- **底层通道**：同样使用 `nativeLibraryDir` 下的 `libproot.so` / `libproot-loader.so` 与 `libtermux.so` 承载 Alpine 3.24.3（`rootfs-aarch64.tgz`）Linux 环境，通过 `BinderExchangeProvider` + `PrivilegedServiceMain` 提供高权限后端，并通过前台 `AgentService` 保持任务运行。
- **工具面**：涵盖通用工作区文件读写编辑（`read_file` / `edit_file`）、受控 Shell（`run_shell`）、通用 Android 界面操作（`open_app` / `click` / `long_press` / `input_text` / `scroll` / `screenshot` 与端侧 ML Kit OCR）、用户提问（`ask_user`）、记忆写入、例程保存、MCP 工具以及在手机 Linux 内运行外部 CLI Agent 的模式。

> 上述静态结构分析仅用于明确产品演进方向与能力对照；PocketRun 所有实现均在 React Native + Kotlin 架构下独立从零编写，并始终坚持更严格的手机端人工审批与沙箱边界。

## 能力缺口与演进清单

| 能力维度 | PocketRun 当前状态（第 1—13 章） | 差距与后续推进方向 |
| --- | --- | --- |
| **Linux 用户态分发** | Alpine 3.22.8 派生的最小 BusyBox/musl（4 个 ELF、2 个代码绑定） | 显著小于完整 rootfs 范围；需按实际任务需求补齐常用工具、动态库和目录结构，并同步维护对应源码与开源许可 |
| **包管理能力** | 暂不含 `apk`、软件源与动态装包入口 | 需先核实目标产品真实的装包、更新、网络与持久化流程，并在遵守 Android W^X 限制的前提下实现 |
| **Agent 工具集** | 3 个基础工具，加第 13 章针对固定演示的 CSV 读取、Demo 观察与结果发布 | 固定 CSV 与自有 Demo 仍是教学探针，并非通用文件/目录/命令工具；需在守住审批与沙箱边界的前提下扩充通用工具协议 |
| **Android 通用 App 操作** | 仅针对固定 Demo 页完成截图、UI 树、点击与可打印 ASCII 输入 | 尚未覆盖一般第三方 App 的启动、复杂控件选择、滚动导航、中文输入与授权边界 |
| **终端交互界面** | 真实 PTY 后端 + React 64 KiB 纯文本预览 | 尚不具备 ANSI 颜色、光标定位和全屏 TUI 程序渲染能力；需按目标体验决定是否接入完整终端渲染组件 |
| **完整跨能力闭环** | 第 13 章已跑通“私有 CSV → 模型计算 → 自有虚拟屏 Demo 输入与复查 → 无覆盖写入 `result.txt`”及中断恢复 | 目前仅针对固定 Schema 与自有 Demo；通用第三方 App、任意工作区文件与复杂多步编排待后续扩充 |
| **模型视觉与界面观察** | 普通任务仅回传白名单元数据；第 13 章经手机批准后向模型披露固定 CSV 与自有 Demo 两个关键控件 | 尚未建立面向多模态模型的通用截图与脱敏 UI 树安全观察协议，需结合隐私与防提示词注入边界设计 |
| **持久化、恢复与记忆** | 第 12 章已支持用户可管理的加密偏好笔记；第 13 章已实现独立加密的单运行 `TaskJournal` 与中断后幂等核对 | 当前不含自动学习/向量检索、跨任务并发调度或一般任意任务的恢复策略，原产品对应行为仍待进一步取证 |
| **设备与 OEM 兼容** | 已在 API 36 ARM64 专用环境验证核心路径 | 尚未覆盖广泛 Android 版本与各品牌 OEM 定制系统的兼容分支和降级策略 |

:::note 关于 MCP、Skills、定时例程与多 CLI 代理
Ditto 0.1.14 的静态结构中确实包含 MCP 服务管理（`McpListActivity` / `mcp__`）、Skills 管理、定时/快捷例程（`routines` 表）以及在手机 Linux 中安装运行外部编码 CLI 的入口。这些能力会在完成 PocketRun 的核心对话产品外壳、通用文件/命令工具与通用 App 操控之后，按优先级分章节逐步引入。
:::

## 后续章节与验收原则

**第 12 章（可管理的跨任务记忆）**与 **[第 13 章（完整闭环与中断恢复）](chapter-13.md)** 均已验收并发布源码快照（[chapter-12](https://github.com/kkkhs/pocketrun/releases/tag/chapter-12) / [chapter-13](https://github.com/kkkhs/pocketrun/releases/tag/chapter-13)）。接下来将按上述缺口清单继续规划第 14 章及后续章节，逐步把固定演示演进为通用的工作区文件/命令工具与一般 App 操控能力。

无论后续功能如何扩充，全书始终守住三条不变的验收标准：
1. **逐项对齐**：每个新能力明确记录“预期协议 → PocketRun 实现入口 → 真实场景验证 → 剩余边界”。
2. **不放宽底线**：从 Demo 演进到通用工具时，绝不放松 UID 边界、正数 `displayId` 隔离、手机端审批和可证明清理约束。
3. **写给人看**：每一项新增能力的原理和工程权衡，都会同步写成清晰、自洽的教程章节。
