# PocketRun：从零构建 Android AI 助手

面向 **React / TypeScript** 开发者的端到端实战教程与示例工程：在**无需 Root** 的 Android 手机上，从零搭起一个能看见并操作 App、能运行 Linux 工具与交互终端、并由真实大模型驱动（同时受手机端人工审批约束）的 AI Agent。

在线阅读：[https://kkkhs.github.io/pocketrun/](https://kkkhs.github.io/pocketrun/)

## 你会学到什么、做出什么

在电脑上写一个 AI Agent 很容易——Node.js 调个大模型接口，再 `child_process` 跑几条命令就行了。但在 Android 手机上，普通 App 关在严格的沙箱里，既不能随便看别的 App 的屏幕，也无法直接像 Linux 桌面那样跑现成的命令行工具。

PocketRun 用 13 章渐进式实战，带你一层层拆开这些系统限制：

| 部分 | 章节 | 你会亲手搭通的核心机制 |
| --- | --- | --- |
| **一、打通 Android 权限与屏幕控制** | 第 1—5 章 | TurboModule 桥接 Kotlin → App 自连本机无线 ADB → 拉起 UID 2000 独立 `app_process` 并交换 Binder → 创建隔离的 VirtualDisplay → 共享内存截图、UiAutomation 控件树与定向输入注入 |
| **二、在 App 里跑起 Linux 与终端** | 第 6—8 章 | 私有工作目录与 `ProcessBuilder` 管道 → 绕开 Android W^X 限制跑起 PRoot + 最小 Alpine（BusyBox/musl）→ 通过 JNI 与 `/dev/ptmx` 实现真正的交互式 PTY 终端 |
| **三、从受控工具到 AI Agent 完整闭环** | 第 9—13 章 | 确定性审批网关 `TaskHost` → 多步任务账本 `SingleTaskContext` → 真实模型循环 `AgentLoop` → 可管理的加密跨任务记忆 `MemoryStore` → 跨通道完整任务完成门与预写日志中断恢复 `TaskJournal` |

第 0—13 章全部对应真实源码实现与可检出的 [章节源码快照（GitHub Releases）](https://github.com/kkkhs/pocketrun/releases)；后续通用工具与完整产品能力演进见 [Ditto 功能对照](website/docs/ditto-parity.md)。

## 目录结构

本仓库包含两个**各自独立管理依赖**的子项目（根目录没有 `package.json`）：

- [`website/`](website/)：Docusaurus 3.10.2 教程站（正文位于 [`website/docs/`](website/docs/)）。
- [`app/`](app/README.md)：React Native 0.88.1 + Kotlin + C++/JNI + AIDL 的完整 Android 工程。

## 本地运行教程站

需要 Node **24.18.0**（见 `.nvmrc`）与 npm：

```bash
git clone https://github.com/kkkhs/pocketrun.git
cd pocketrun
nvm use
cd website
npm ci --registry=https://registry.npmjs.org
npm start -- --host 127.0.0.1 --port 3000 --no-open
```

访问 <http://127.0.0.1:3000/pocketrun/> 即可实时预览。完整构建与校验命令：

```bash
npm run test:toolchain
npm run typecheck
npm run build
npm run audit:ci
```

Android 工程的构建、安装与测试步骤见 [`app/README.md`](app/README.md) 与 [第 1 章](website/docs/chapter-1.md)。

## 项目维护与开发交接文档

如果你是参与后续章节开发的贡献者或协作者，可以先看以下工程记录（普通读者直接按教程顺序阅读即可）：

- [项目总账（PROJECT_RECORD.md）](PROJECT_RECORD.md)：项目演进目标、关键决策时间线与阶段交付摘要。
- [开发与交接说明（DEVELOPMENT.md）](DEVELOPMENT.md)：代码入口、工具链要求、验证门禁与发布流程。
- [Ditto 功能对照（ditto-parity.md）](website/docs/ditto-parity.md)：完整产品能力目标、当前阶段差距与待核实项。
- [实机验收与工程档案（verification-records.md）](website/docs/verification-records.md)：各章实机验证记录、产物校验与第三方开源许可汇总。
