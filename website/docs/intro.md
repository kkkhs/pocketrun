---
sidebar_position: 1
sidebar_label: 课程导读
slug: /
title: 从这里开始
description: 从一个手机任务出发，学习构建 Android AI 助手。
---

import TaskFlow from './assets/task-flow.svg';
import CourseRoadmap from './assets/course-roadmap.svg';
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

## 先沿一条主线走

前三章只解决一个问题：**普通 App 怎样得到一个可以安全调用的 shell 后端？**

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="四阶段课程路线图，可横向查看">
    <CourseRoadmap className={`pr-roadmap-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-roadmap-title" aria-describedby="pr-roadmap-desc" style={{display: 'block', width: '100%', minWidth: 660, height: 'auto'}} />
  </div>
  <figcaption>先搭通身份和通信，再增加手机控制、Linux，最后才接 Agent。</figcaption>
</figure>

按这个顺序开始：

1. [第 1 章](chapter-1.md)：React 调 Kotlin，先看清普通 App UID。
2. [第 2 章](chapter-2.md)：App 自己连接本机 ADB，拿到 shell 启动入口。
3. [第 3 章](chapter-3.md)：用 ADB 启动后端，再改用 Binder 通信。
4. [第 4 章](chapter-4.md)：让 shell 后端创建虚拟屏，把 Demo 路由到目标 `displayId`。
5. [第 5 章](chapter-5.md)：看见虚拟屏里的页面，找到输入框、填入文字，再确认结果。
6. [第 6 章（已发布）](chapter-6.md)：在 App 私有目录准备 CSV、运行固定命令管道，再检查输出和两个退出码；云真机专项与真实界面的正常演示分别记录，[源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-6)已发布。
7. [第 7 章（已发布）](chapter-7.md)：在普通 App UID 下用最小 Alpine 环境读取同一份 CSV；实际界面、正常演示、12 项生命周期与 7 个死亡恢复场景通过，[源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7)可下载。
8. [第 8 章（已发布）](chapter-8.md)：用真正的 PTY 实时输入、改变尺寸、正常退出，并验证 120 秒期限后的自有资源清理；[源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-8)可下载。
9. [第 9 章（验收通过，发布中）](chapter-9.md)：固定工具校验、手机逐次审批与来源/资源核对，包含真实执行中取消和原五秒期限。

读图时分开两个问题：**层是“谁执行”，阶段是“先做什么、再做什么”。** React 与 App Kotlin 是同一个 App 中的两层，shell 后端是独立进程；一次任务可以多次经过这些层，并不是每层只对应一个步骤。

[系统概览](architecture.md)是一张全局参考图，不必先读懂所有模块。[第 0 章](chapter-0.md)只讲教程站工程与发布，也不是 Android 主线的前置。

<details>
<summary>查看第 6—13 章开发状态与计划</summary>
- 第 6 章：工作目录与命令管道，[教程已发布](chapter-6.md)，[PR #11](https://github.com/kkkhs/pocketrun/pull/11) 已合并，[源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-6)已发布。
- 第 7 章：[PR #12](https://github.com/kkkhs/pocketrun/pull/12)、main CI、Pages 与 [源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7)已完成。
- 第 8 章：[PR #13](https://github.com/kkkhs/pocketrun/pull/13)、main CI/Pages 和 [源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-8)已完成。
- 第 9 章：工具校验、审批与 Agent 宿主验证，代码与教程见 [Draft PR #14](https://github.com/kkkhs/pocketrun/pull/14)；待新真机验收，未正式发布。
- 第 10 章：单任务上下文。
- 第 11 章：真实 Agent 循环与配置入口。
- 第 12 章：可查看、纠正和删除的记忆。
- 第 13 章：完整任务与故障恢复。

第 0—7 章教程与源码快照已发布，各章的实际设备和发布记录可独立核对。第 8 章的独立章节包、产品 PTY 与资源验收已通过，正式发布推进中；第 9 章仍按 [Draft PR #14](https://github.com/kkkhs/pocketrun/pull/14) 收口。第 10、11 章已有 [单任务上下文 PR #17](https://github.com/kkkhs/pocketrun/pull/17) 与 [模型循环 PR #18](https://github.com/kkkhs/pocketrun/pull/18)，在后续章节整合发布。第 12—13 章是计划。每次只增加一项能力，并在引入资源的同一章验证取消、死亡和清理。

</details>

## 需要准备什么

**现在运行网站：** Git、Node 24.18.0 / npm、编辑器和浏览器。具体命令见第 0 章；本地构建无需登录 GitHub。

**阅读基础：** React 组件与状态、TypeScript、异步调用。Android 原生和终端知识会随实现讲解，不必先学完整移动端栈。

**后续设备实践：** 自己拥有或明确获授权的 Android 11 及以上、ARM64 设备，以及可恢复的演示数据。使用 ADB 前要确认设备调试授权；无线调试按系统流程配对，无需 root。

第 1 章列出了已验证的 JDK / SDK / 模拟器版本。无需先安装完整 Android Studio，也不要求操作个人真机。

带着一个问题继续读：前面的任务里，每一步由谁执行，怎样判断成功，用户取消后该停止什么？
