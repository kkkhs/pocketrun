---
sidebar_position: 1
sidebar_label: 课程导读
slug: /
title: 从这里开始
description: 从一个手机任务出发，学习构建 Android AI 助手。
---

import TaskFlow from './assets/task-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

PocketRun 要把 Android 手机变成 Agent 的执行环境：操作 App、处理文件、执行命令，并把结果交给用户检查。

这套教程面向熟悉 React / TypeScript 的开发者。我们会从 UI 开始，逐步接上 Android 原生能力和 Linux 命令环境。

## 先看一个任务

在自己拥有或获授权的设备上，使用演示数据提出任务：

> 读取工作目录里的 `items.csv`，整理数据，填入演示 App，再把处理结果保存到 `result.txt`。

<figure style={{margin: '1.25rem 0', maxWidth: 680}}>
  <TaskFlow className={`pr-task-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-task-title" aria-describedby="pr-task-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>这是后续章节要串起的完整任务。先分别做出每一段，再交给 Agent 调用。</figcaption>
</figure>

最后检查三件事：文件是否读对、App 是否填对、结果是否保存。

**动作要有可观察的结果。** 后续每章都会给出运行步骤、通过条件和失败时的停止方式。

## 从哪开始

教程站和前两章已上线：第 1 章跑通 React → Kotlin，第 2 章完成 App 自有 ADB 配对、连接和 shell UID 检查。shell 后端、手机控制、Linux 和 Agent 能力按后续章节推进。

1. 读[系统概览](architecture.md)，了解 UI、Agent 和执行层的分工。
2. [第 0 章](chapter-0.md)讲网站与更新流程。
3. [第 1 章](chapter-1.md)做出第一个 Android App，并让 React 调用 Kotlin。
4. [第 2 章](chapter-2.md)让 App 在系统无线调试中配对和连接。

<details>
<summary>完整学习路线</summary>

- 第 1 章：React 调用 Kotlin，读取设备信息。
- 第 2 章：App 自有 ADB 配对与连接。
- 第 3 章：shell 后端与 Binder 通信。
- 第 4 章：创建虚拟屏、启动演示 App。
- 第 5 章：截图、UI 树与定向输入。
- 第 6 章：工作目录与命令管道。
- 第 7 章：接入 PRoot / Alpine。
- 第 8 章：交互式 PTY 终端。
- 第 9 章：工具校验、审批与 Agent 宿主验证。
- 第 10 章：单任务上下文。
- 第 11 章：真实 Agent 循环与配置入口。
- 第 12 章：可查看、纠正和删除的记忆。
- 第 13 章：完整任务与故障恢复。

第 3—13 章尚未实现。UI、取消和资源清理会随每章一起完善。

</details>

## 需要准备什么

**现在运行网站：** Git、Node 24.18.0 / npm、编辑器和浏览器。具体命令见第 0 章；本地构建无需登录 GitHub。

**阅读基础：** React 组件与状态、TypeScript、异步调用。Android 原生和终端知识会随实现讲解，不必先学完整移动端栈。

**后续设备实践：** 自己拥有或明确获授权的 Android 11 及以上、ARM64 设备，以及可恢复的演示数据。使用 ADB 前要确认设备调试授权；无线调试按系统流程配对，无需 root。

第 1 章列出了已验证的 JDK / SDK / 模拟器版本。无需先安装完整 Android Studio，也不要求操作个人真机。

带着一个问题继续读：前面的任务里，每一步由谁执行，怎样判断成功，用户取消后该停止什么？
