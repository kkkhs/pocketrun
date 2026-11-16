---
sidebar_position: 1
sidebar_label: 课程导读
slug: /
title: 从这里开始
description: 从一个手机任务出发，学习构建 Android AI 助手。
---

PocketRun 要把 Android 手机变成 Agent 的执行环境：操作 App、处理文件、执行命令，并把结果交给用户检查。

这套教程面向熟悉 React / TypeScript 的开发者。我们会从 UI 开始，逐步接上 Android 原生能力和 Linux 命令环境。

## 先看一个任务

在自己拥有或获授权的设备上，使用演示数据提出任务：

> 读取工作目录里的 `items.csv`，整理数据，填入演示 App，再把处理结果保存到 `result.txt`。

完成任务不只看一条“成功”消息，还要检查三件事：文件是否读对、App 是否填对、结果是否保存。

**动作要有可观察的结果。** 后续每章都会给出运行步骤、通过条件和失败时的停止方式。

## 从哪开始

教程站已上线；Android 应用尚未实现，应用开发章节按计划推进。

1. 读[系统概览](architecture.md)，看一次任务如何经过 UI、Agent 和执行层。
2. 做[第 0 章](chapter-0.md)，在本地运行教程站，再完成一次文档更新。

<details>
<summary>后续章节计划</summary>

- 第 1 章：React Native / Kotlin 工程与原生桥接。
- 第 2 章：授权 ADB、Android 后端与虚拟显示。
- 第 3 章：工作目录、命令管道与交互式终端。
- 第 4 章：Agent 工具调用与宿主选择。
- 第 5 章：生命周期、恢复与端到端验证。

章节随实现和验证结果补充。

</details>

## 需要准备什么

**现在运行网站：** Git、Node 24.18.0 / npm、编辑器和浏览器。具体命令见第 0 章；本地构建无需登录 GitHub。

**阅读基础：** React 组件与状态、TypeScript、异步调用。Android 原生和终端知识会随实现讲解，不必先学完整移动端栈。

**后续设备实践：** 自己拥有或明确获授权的 Android 11 及以上、ARM64 设备，以及可恢复的演示数据。使用 ADB 前要确认设备调试授权；无线调试按系统流程配对，无需 root。

Android Studio、SDK Platform Tools 和 JDK 的版本将在应用工程章节确定。

带着一个问题继续读：前面的任务里，每一步由谁执行，怎样判断成功，用户取消后该停止什么？
