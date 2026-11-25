---
sidebar_position: 1
sidebar_label: 第 6 章 · 工作目录与命令管道
title: 第 6 章：工作目录与命令管道
description: 告别 UID 2000，回到普通 App 进程：原子初始化私有工作目录，用 ProcessBuilder 串起外部命令管道，并避开子进程编程的经典死锁坑。
---

import WorkspacePipelineFlow from './assets/workspace-pipeline-flow.svg';
import workspaceResult from './assets/chapter-6-workspace-result.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

前五章我们走完了第一条大通道——操控 Android 屏幕。从这一章开始，我们切换赛道，搭建 Agent 的**第二条通道：文件与命令执行环境**。

先问一个很实在的问题：这一章的演示任务是“读取 App 私有目录里的 `items.csv`，数出它有几行，把 `3` 显示到页面上”。

```csv title="items.csv"
name,quantity
apple,2
banana,3
```

你可能会纳闷：**数一个三行文件的行数，用 Kotlin 写一句 `file.readLines().size` 不就完事了吗？为什么大费周章拉起 `cat` 和 `wc -l` 两个外部子进程？**

原因很简单——今天是 `cat | wc -l`，明天就是 PRoot、BusyBox、Python 脚本和各种数据转换工具。`wc` 只是一个极小的探针，这一章真正要沉淀下来、并且下一章会直接复用的，是两块通用基础设施：

1. **私有工作目录（`WorkspaceSession`）**：给 Agent 一个安全、持久、不会在重复初始化时覆盖用户数据的沙箱工作区。
2. **健壮的命令管道引擎（`CommandPipeline`）**：搞定外部子进程的输入输出流转发、退出码收集、输出字节上限、5 秒超时、用户取消和彻底的资源回收。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={workspaceResult} alt="实际 App 的私有目录、行数 3 与两个成功退出码" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>初始化私有目录并运行固定管道：状态 completed、stdout 为 3（1 行表头 + 2 行数据）、两个命令退出码均为 0。</figcaption>
</figure>

## 不走 Binder，不提权：普通 App 就能直接起子进程

在进入代码前先摆正一个概念：**这一章完全用不到第 2—5 章的无线 ADB 和 UID 2000 后端。**

| 角色 | 所在进程 | 身份 | 职责 |
| --- | --- | --- | --- |
| **React UI**（`WorkspacePanel.tsx`） | PocketRun 主进程 | 普通 App UID | 按钮交互、展示路径与运行结果 |
| **App Kotlin**（`WorkspaceSession` + `CommandPipeline`） | PocketRun 主进程 | 普通 App UID | 管理私有目录、启动子进程、搬运字节、处理超时与取消 |
| **`cat` 与 `wc` 两个子进程** | 由 `ProcessBuilder` 派生 | **同一个普通 App UID** | 读文件并统计换行数 |

不要因为可执行程序路径是 `/system/bin/toybox` 就误以为它有了 shell 或 root 权限。在 Linux/Android 里，子进程默认继承父进程的 UID——所以这两个子进程天生只能访问 PocketRun 自己的私有目录，越界去读别的 App 数据会被内核直接拦下。

## 第一步：原子初始化私有工作目录

打开 [WorkspaceSession.kt](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/java/io/github/kkkhs/pocketrun/WorkspaceSession.kt)，工作目录定在 Android 分配给当前 App 的私有空间下：

```kotlin title="WorkspaceSession.kt（目录布局与首次发布）"
private val directory = File(File(filesDirectory.canonicalFile, "workspaces"), "demo")
private val sample = File(directory, "items.csv")

// 目录权限锁死为 0700（仅当前 UID 可读可写可进入），同时拒绝任何符号链接逃逸
val created = if (sample.exists()) false else publishSample()
validateSample()
```

写文件看似简单，但在真实移动端环境里藏着两个常见的坑：
1. **写到一半 App 被杀，留下半截坏文件**：`publishSample()` 会先在同目录创建一个 `.pocketrun-sample-*.tmp` 临时文件，设成仅属主可读写（`0600`），写入完整内容并调用 `output.fd.sync()` 刷盘，最后才发布成 `items.csv`。下次启动时，如果发现残留的 `.tmp` 文件会自动清理。
2. **绝不能覆盖用户已经改过的文件**：如果两个并发请求同时初始化，普通 `rename()` 会直接覆盖已有目标。在 Android 上，我们通过一段极简的 JNI 代码（[pocketrun_fs.cpp](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/cpp/pocketrun_fs.cpp)）调用 Linux 原生系统调用 **`renameat2(..., RENAME_NOREPLACE)`**——目标不存在才原子改名，目标已存在直接返回 `EEXIST` 并保留原文件！

## 第二步：用 `ProcessBuilder` 搭管道，避开两个经典死锁

在终端里写 `cat items.csv | wc -l` 时，shell 帮你把两个进程连了起来；而在 Kotlin 里，我们不经由 `sh -c`（避免命令注入风险），而是直接用参数数组分别拉起两个进程，自己充当中间的“水管工”：

```kotlin title="WorkspaceSession.kt（固定命令数组）"
private val DEMO_COMMANDS = listOf(
    listOf("/system/bin/toybox", "cat", "items.csv"),
    listOf("/system/bin/toybox", "wc", "-l"),
)
```

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <WorkspacePipelineFlow className={`pr-pipe-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-pipe-title" aria-describedby="pr-pipe-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>数据流向：cat 读出文件字节，App Kotlin 搬运进 wc 的标准输入并关闭输入流，最后收集 wc 的输出与两个退出码。</figcaption>
</figure>

在 Java / Kotlin 里操作 `Process` 的三个流，名字是**站在父进程（App）视角**命名的，第一次写极容易反着来：
- `process.outputStream` → App 往里写 → 接到子进程的 **stdin**
- `process.inputStream` → App 从里读 → 接到子进程的 **stdout**
- `process.errorStream` → App 从里读 → 接到子进程的 **stderr**

用代码把 `cat` 和 `wc` 接起来时，新手几乎必踩**两个经典死锁坑**，看看 [CommandPipeline.kt](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/java/io/github/kkkhs/pocketrun/CommandPipeline.kt) 是怎么一一化解的：

### 死锁坑 1：搬完数据忘了关下游的 stdin

```kotlin title="CommandPipeline.kt（搬运字节并在结束后关闭 wc 的 stdin）"
// cat 不需要标准输入，启动后立刻关闭
run.processes.first().outputStream.close()

// 在独立 IO 线程里把 cat 的 stdout 搬运到 wc 的 stdin
run.processes[1].outputStream.use { destination ->
    copyLimited(run.processes[0].inputStream, destination)
}
```

重点就在那个 `.use { ... }`！数据全部写完后，必须立刻关闭 `wc` 的标准输入流，`wc` 才会收到 **EOF（文件结束信号）**，从而打印出 `3` 并退出。如果你只是停止写入却没关流，`wc` 会一直傻等下一行数据，直到 5 秒超时被强杀。

### 死锁坑 2：先等进程退出，再读输出

如果你写出 `process.waitFor(); val text = process.inputStream.readText()` 这样的代码，小文件看着没事，一旦子进程输出超过操作系统的管道缓冲区（通常 64 KiB），子进程就会卡在 `write()` 上等你把数据读走，而你正卡在 `waitFor()` 上等它退出——双方永久死锁！

因此 `CommandPipeline` 创建了一个 4 线程的 `PocketRun-PipeIO` 线程池，**进程一启动就并发消费**所有进程的 `stderr`、上游到下游的字节搬运以及最终进程的 `stdout`，最后才统一等待所有进程和 IO 线程结束。

## 第三步：输出上限、UTF-8 边界与退出码核对

真正跑在手机上的命令引擎，还必须防住“失控命令”和“隐蔽失败”：

1. **64 KiB 字节上限与安全截断**：中间传递、stdout、stderr 各限制最多 64 KiB，超限立刻终止进程并返回状态 `output_limit`，绝不让一个死循环 `yes` 命令把 App 内存撑爆。更有意思的是，无效二进制转 UTF-8 替换字符（`U+FFFD`）时字节数可能膨胀，`LimitedOutput.text()` 在截断时还会回退跳过 UTF-8 续字节（`(byte and 0xc0) == 0x80`），确保不会把一个多字节字符从中间劈开。
2. **检查全部阶段的退出码**：`CommandPipeline` 会把管道里**每一个进程**的退出码放进 `exitCodes` 数组（本例为 `[0, 0]`）。哪怕 `wc -l` 对空输入照样返回 `0`，只要前面的 `cat` 因为文件丢失返回了非零退出码，整条管道就会老老实实判为 `failed`。
3. **预留槽位再取消**：点击“运行”前，Kotlin 先调用 `pipeline.reserve()` 占住唯一执行槽；即便用户在子进程真正 `start()` 之前就飞快点了取消，取消标记也能精准命中这次运行，绝不会漏网。

## 动手跑一遍

1. 打开 PocketRun 的 **第 6 章 · 工作目录与命令管道** 面板（无需配对 ADB 或启动虚拟屏）。
2. 点击 **初始化工作目录**，看到私有目录路径、`items.csv` 路径以及“首次写入演示 CSV”。
3. 点击 **运行演示管道**，核对结果为：`status: completed`、`stdout: 3`、`stderr: (empty)`、`退出码: 0, 0`。
4. 再点一次 **初始化工作目录**，确认提示变为“保留已有演示文件，没有覆盖”——验证了 `RENAME_NOREPLACE` 的保护逻辑。

## 本章小结与源码

这一章我们没借助任何特权，就在普通 App 进程里搭出了**持久化私有工作目录**和**防死锁、带限流、可取消的命令管道引擎**。

现在我们跑的是 Android 自带的 `toybox`，命令极少、功能有限。如果想在手机上真正跑起标准的 Linux 工具链，该怎么做？下一章，我们揭开 Android 跑 Linux 的全部真相——把 **PRoot 与最小 Alpine（BusyBox/musl）** 装进 App，让它们无缝读到本章留下的同一份 `items.csv`！

- **本章源码快照**：[Release `chapter-6`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-6) · [PR #11](https://github.com/kkkhs/pocketrun/pull/11)
- **延伸参考**：[Android 应用沙箱说明](https://source.android.com/docs/security/app-sandbox) · [Java ProcessBuilder 文档](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ProcessBuilder.html) · [实机验收与工程档案](verification-records.md)
