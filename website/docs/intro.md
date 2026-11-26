---
sidebar_position: 1
sidebar_label: 课程导读
slug: /
title: 从零构建一个 Android AI 助手
description: 面向 React / TypeScript 开发者，从第一行桥接代码走到真机虚拟屏、Linux 环境与大模型工具循环。
---

import TaskFlow from './assets/task-flow.svg';
import CourseRoadmap from './assets/course-roadmap.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

如果你平时写 React 和 TypeScript，在电脑上搭一个 AI Agent 其实并不难：写个循环调用大模型 API，挂几个 Node.js 工具，读写本地文件、跑跑 shell 命令，一个原型很快就能跑起来。

但把战场搬到 **Android 手机** 上，几乎所有习以为常的前提都会瞬间失效：

- **没有 Node.js 运行时**：React Native 里跑的是 Hermes 引擎，你不能直接 `import fs` 或 `child_process`。
- **普通 App 摸不到别的 App**：出于沙箱安全，你的 App 既不能截别的页面，也不能给别的输入框发点击和打字事件。
- **就算手机用的是 Linux 内核，你也跑不了常规 Linux 程序**：Android 用的是 Bionic 运行库，高版本系统还禁止 App 从自己的可写目录直接执行二进制文件。
- **手机端的操作风险高得多**：电脑上最多改坏个测试目录，手机上一次失控的点击可能会误发消息、误删数据甚至触发支付。

**PocketRun** 要做的，就是带你**不用 Root 手机**，亲手把这些系统关卡一关一关打通——不只是把功能拼出来，更要让你真正看懂 Android AI 助手（例如 Ditto 这类本机执行路线）背后的进程模型、通信协议、权限边界和工程取舍。

## 贯穿教程的示例任务

整套教程围绕一个非常具体的场景展开，你可以一直带着这个画面来读后面的每一章：

> 读取工作目录里的 `items.csv`，整理其中的数据，在手机的一块**虚拟副屏**里填进目标 App，最后把处理结果保存到 `result.txt`。

<figure style={{margin: '1.25rem 0', maxWidth: 680}}>
  <TaskFlow className={`pr-task-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-task-title" aria-describedby="pr-task-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>整套教程的北极星任务：把“处理文件”与“操作 App”两条能力汇入同一个受控的 Agent 循环。</figcaption>
</figure>

拆开看，完成这样一件事其实需要三块拼图：

1. **一双手和一双眼睛**：能看见目标 App 的画面和控件，能在不打扰用户主屏的前提下精准点击和输入。
2. **一个本地工作台**：能存放文件、跑命令脚本，甚至在需要时打开一个真正的交互式 Linux 终端。
3. **一个有刹车的大脑**：能把目标和观测结果交给大模型，让它决定下一步调什么工具；但凡涉及高风险动作，必须停下来等用户在手机上点“同意”。

目前发布的**第 1—13 章**已经完整走通了这三块核心基础设施、真实大模型循环、可管理的加密跨任务记忆，以及导读里这整条“读 CSV → 填虚拟屏 Demo → 原子写 `result.txt`”跨通道任务与中断恢复闭环。接下来如何从固定演示扩充成通用 Android AI 助手，请查看[Ditto 功能对照与后续路线](ditto-parity.md)。

## 全书路线图

整套教程分成三个递进阶段，每一章只啃透一个核心问题，并且立刻给你可运行、可肉眼核对的代码和界面：

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="PocketRun 课程路线图">
    <CourseRoadmap className={`pr-roadmap-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-roadmap-title" aria-describedby="pr-roadmap-desc" style={{display: 'block', width: '100%', minWidth: 660, height: 'auto'}} />
  </div>
  <figcaption>先打通 Android 原生与特权通道，再搭起本机 Linux 工作台，最后接上工具宿主与大模型循环。</figcaption>
</figure>

### 第一部分：打通 Android 权限与屏幕控制

| 章节 | 核心问题 | 你会解锁的能力 |
| --- | --- | --- |
| [第 1 章：让 React 调用 Kotlin](chapter-1.md) | JS 怎么跨越语言边界调原生 API？ | 用 TurboModule + Codegen 写第一个原生模块，看清普通 App 的 UID 沙箱 |
| [第 2 章：让 App 自己连接无线 ADB](chapter-2.md) | 没 Root 的手机，App 怎么获得 shell 入口？ | 把无线调试客户端嵌进 App，直连本机 `127.0.0.1` 的 `adbd`，拿到 UID 2000 |
| [第 3 章：启动独立的 shell 后端与 Binder 通信](chapter-3.md) | 总不能每次点击都敲命令行吧？ | 用 `app_process` 拉起独立 Kotlin 后端，通过 ContentProvider 换回 Binder，断开 ADB 也能调 |
| [第 4 章：给 Agent 造一块看不见的虚拟屏](chapter-4.md) | 怎么在不抢用户主屏的情况下操作 App？ | 后端创建 720×1280 的 `VirtualDisplay`，把 Demo App 定向启动到指定 `displayId` |
| [第 5 章：看见界面，再精准操作它](chapter-5.md) | Agent 怎么“看懂”画面并把字填进去？ | 共享内存传输 JPEG 截图、UiAutomation 抓取控件树、计算坐标并定向注入点击与按键 |

### 第二部分：在 App 里跑起 Linux 与交互终端

| 章节 | 核心问题 | 你会解锁的能力 |
| --- | --- | --- |
| [第 6 章：私有工作目录与命令管道](chapter-6.md) | App 怎么安全地管文件和外部子进程？ | 原子写入私有 CSV、用 `ProcessBuilder` 串起 `cat \| wc`，避开管道死锁与输出失控 |
| [第 7 章：PRoot 与最小 Alpine 环境](chapter-7.md) | Android 用着 Linux 内核，为啥跑不了 Linux 程序？ | 理解 Bionic vs musl 与 W^X 限制，打包 ELF 到 `nativeLibraryDir`，用 PRoot 做无 Root 路径映射 |
| [第 8 章：通过 JNI 打造真正的交互式 PTY](chapter-8.md) | 管道和真正的终端到底差在哪？ | 在 C++ 中打开 `/dev/ptmx`、管理会话与前景进程组，实现实时输入、resize、Ctrl-C 与严格清理 |

### 第三部分：从受控工具到 AI Agent 循环

| 章节 | 核心问题 | 你会解锁的能力 |
| --- | --- | --- |
| [第 9 章：确定性工具宿主与手机端审批](chapter-9.md) | 怎样确保工具调用绝不越界？ | 设计 `TaskHost`：严格 schema 校验、nonce 一次性审批、目标绑定、单调超时与可证明清理 |
| [第 10 章：把多步调用组织成一份任务账本](chapter-10.md) | 多步操作之间怎么共享状态与证据？ | 用 `SingleTaskContext` 管住任务编号、步骤账本、预算上限与串行状态流转 |
| [第 11 章：接上大模型，让 Agent 自己选下一步](chapter-11.md) | 手机上的 LLM 循环到底怎么写？ | 在 Kotlin 中接入官方 Anthropic Java SDK，实现流式取消、完整消息回放、白名单摘要与 Keystore 加密 |
| [第 12 章：用户可查看、纠正与删除的跨任务记忆](chapter-12.md) | 偏好怎么跨重启留下来，又不变成隐私黑盒？ | 版本号 CAS 校验、独立密钥域加密、发送前目的地确认、修改即取消旧循环 |
| [第 13 章：串联完整任务，并搞定中途崩溃与恢复](chapter-13.md) | 怎么证明任务真做完了，崩溃了怎么不重复干活？ | 跨通道完整闭环、原生三端完成门、预写运行日志 `TaskJournal` 与幂等现状核对 |

:::tip 阅读建议
- **只想先懂原理**：按第 1 章到第 13 章顺着读下来就行。每章都会用你熟悉的 React / TypeScript 概念做类比，再带你走一遍真实源码，**不装环境也能完全读懂**。想先鸟瞰全局，可以先花 5 分钟看看[系统全景](architecture.md)。
- **想边读边动手跑**：每一章的源码都固定在 GitHub 对应的 `chapter-N` Release 快照里，正文末尾会给出清晰的操作步骤。
- **想了解本教程站怎么本地启动或部署**：请看[第 0 章：运行与发布教程站](chapter-0.md)，它不是学习 Android 主线的前置条件。
:::

## 动手实践的环境要求

如果你准备在自己拥有或获授权的演示设备上跟着敲、跟着跑，提前准备好下面这些工具即可（无需一开始就装完整的 Android Studio，也不用拿自己的主力机冒险）：

- **基础环境**：Git、Node **24.18.0**、完整 **JDK 17**（注意要有 `javac`，不能只有 JRE）。
- **Android 工具链**：Android SDK（Platform 37、Build Tools 37.0.0 / 36.0.0、NDK 27.1.12297006、CMake 3.22.1）。
- **演示设备**：推荐创建一个 **Android 16（API 36）ARM64** 专用模拟器。无线 ADB 最低要求 Android 11+，第 7—12 章涉及的内置 Linux 环境要求 **Android 14（API 34+）且为 64 位 ARM64 进程**。

所有工具的安装命令都收在[第 1 章的准备折叠区](chapter-1.md)里，需要时再展开即可。

准备好了吗？我们先去[系统全景](architecture.md)看一眼整台机器的蓝图，或者直接翻开[第 1 章](chapter-1.md)，点下第一个调用 Kotlin 的按钮。
