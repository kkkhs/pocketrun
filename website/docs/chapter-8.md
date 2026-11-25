---
sidebar_position: 3
sidebar_label: 第 8 章 · 交互式 PTY 终端
title: 第 8 章：通过 JNI 打造真正的交互式 PTY
description: 搞懂为什么普通管道撑不起交互式 shell，用 C++/JNI 打开 /dev/ptmx 创建伪终端，实现持续输入、动态改窗口大小、Ctrl-C 中断和防 PID 复用的严谨回收。
---

import ptyResultScreenshot from './assets/chapter-8-pty-result.png';
import ptyTimeoutScreenshot from './assets/chapter-8-pty-timeout.png';
import PtyRoundtrip from './assets/pty-roundtrip.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章我们已经能用 `ProcessBuilder` 启动 PRoot 里的 BusyBox shell 了。你可能会想：既然 `Process` 对象有 `outputStream`（stdin）和 `inputStream`（stdout），那我直接启动一个 `/bin/busybox sh -l`，不停往 `outputStream` 写命令、不停从 `inputStream` 读输出，不就做成交互终端了吗？

真试过你就会发现，**普通管道和真正的终端（TTY）完全是两个物种**：

1. **缓冲行为全乱了**：大多数 Linux 程序启动时会调 `isatty()` 检查自己是不是连在终端上。发现连的是普通管道后，它们会切到全缓冲模式——输出憋在内存里半天不吐出来，交互式提示符也直接消失。
2. **发 `Ctrl-C` 根本打不断程序**：往普通管道里写入字节 `0x03`，它只是个普普通通的数据字符，绝对不会变成发给正在跑的 `sleep 30` 的 `SIGINT` 中断信号！
3. **没有窗口尺寸概念**：`stty size`、`top`、`vim` 这类程序需要从终端驱动里查询当前有几行几列，并在窗口拉伸时收到 `SIGWINCH` 信号。管道压根没这个概念。

要让一个 shell 真正“活”起来，必须使用 Linux 内核的**伪终端（PTY, Pseudo-Terminal）**。Java/Kotlin 标准库没有暴露创建 PTY 的接口，所以这一章我们写一小段干净的 C++ JNI 代码，直接和内核对话。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={ptyResultScreenshot} alt="实际同一 PTY 会话中的尺寸查询、Ctrl-C、正常退出和清理" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>同一 PTY 会话内的连续交互：先查到 24×80，用 Ctrl-C 打断 sleep 30，动态改尺寸后查到 32×100，最后 exit 0 优雅退出并完成清理。</figcaption>
</figure>

## PTY 是什么：一对背靠背的软件端口

把 PTY 想象成**一根专门模拟真实硬件终端的软件管子**，它有一主一从两个端点：

- **Master 端（`/dev/ptmx`）**：握在 PocketRun App 手里。App 往 master 写字节，就像用户在键盘敲字；App 从 master 读字节，就是屏幕上该显示的内容。
- **Slave 端（`/dev/pts/N`）**：挂给 PRoot / BusyBox shell，同时充当它的 `stdin`、`stdout` 和 `stderr`。

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <PtyRoundtrip className={`pr-pty-roundtrip-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-pty-roundtrip-title" aria-describedby="pr-pty-roundtrip-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>一次 PTY 会话建立后，可以反复往返输入和输出；stdout 与 stderr 经由同一 slave 合流回到 master。</figcaption>
</figure>

因为 `stdout` 和 `stderr` 都连在了同一个 slave 上，App 从 master 读出来的是**一份合流输出**（和前两章把 stdout/stderr 分开收集不同），而且终端驱动默认开启回显——你写进去的命令本身也会被回显出来，后面跟着的才是命令真正的输出。

## 前端怎么和“长寿命会话”打交道？

对写 React 的开发者来说，这一章有个非常值得留意的异步模式变化。

第 6—7 章的命令跑完只要几百毫秒，`await runLinuxDemo()` 直接等最终结果就行了。但一个交互式 shell 会持续运行几十秒甚至两分钟——**`startPty()` 返回的 Promise 要等到整个 shell 退出才会 resolve！**

如果 React 傻等 `startPty()` 返回才允许用户操作，那就永远输入不了任何命令。看看 [PtyPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-8/app/PtyPanel.tsx) 是怎么把**生命周期 Promise** 和 **运行中交互** 拆开的：

```ts title="PtyPanel.tsx（启动后立刻开始轮询，同时等待会话终结）"
// 1. 先开启约 400ms 一次的快照轮询，用来实时刷新界面输出与 ready 状态
beginPolling(token, current);

// 2. 这个 await 等待的是“整个终端最终退出”，并不会阻塞用户点发送/改尺寸/Ctrl-C
const finalResult = await NativePocketRunPty.startPty(
  token,
  initialSize.columns,
  initialSize.rows,
);
```

在终端运行期间，前端并行调这几个独立入口：

| TurboModule 方法 | 作用 | 什么时候调 |
| --- | --- | --- |
| `startPty(token, cols, rows)` | 打开 PTY 并一路运行，直到 shell 退出或超时才 resolve 最终结果 | 点击“启动交互终端” |
| `getPtyOutput(token)` | 拉取当前状态快照（`ready`、**累计输出**、当前尺寸） | 运行中定时轮询 |
| `writePty(token, text, control)` | 往 PTY master 写普通文本或控制字节（如 `interrupt` → `0x03`） | 点击“发送文本”或“发送 Ctrl-C” |
| `resizePty(token, cols, rows)` | 通过 `TIOCSWINSZ` 通知内核改变终端行列数 | 切换 80×24 / 100×32 |
| `closePty(token)` | 强制终止并回收当前会话 | 点击“关闭 PTY” |

:::warning 前端状态管理小陷阱：覆盖快照，别重复追加！
`getPtyOutput(token)` 返回的是原生侧维护的**累计文本快照**（上限 64 KiB），而不是“自上次轮询以来的增量”。React 里应该直接 `setLiveOutput(snapshot.output)`，千万不要写成 `prev + snapshot.output`，否则屏幕内容会成倍重复。
:::

## 下潜到 C++ / JNI：一个生产级 PTY 的核心细节

整个 PTY 的硬核实现都在 [pocketrun_pty.cpp](https://github.com/kkkhs/pocketrun/blob/chapter-8/app/android/app/src/main/cpp/pocketrun_pty.cpp)（760 行 C++）和 [PtySession.kt](https://github.com/kkkhs/pocketrun/blob/chapter-8/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PtySession.kt) 里。它借鉴了 Termux 的底层机制，并补上了极严格的并发与回收防线，有 4 个设计特别值得细品：

### 1. 打开 PTY 与“绝对干净”的 `fork → execve`
打开 `/dev/ptmx`、执行 `grantpt` / `unlockpt` / `ptsname_r` 拿到 slave 路径并打开后，JNI 专门起一条 `launch_reaper` 线程来 `fork()` 子进程。在子进程里（`child == 0`），每一行都是 Unix 系统编程教科书级的写法：

```cpp title="pocketrun_pty.cpp（子进程建立控制终端与启动 PRoot）"
// 1. 父进程（App）万一崩溃，内核自动给子进程发 SIGKILL，绝不留僵尸
syscall(SYS_prctl, PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);

// 2. 新建会话并把 slave 设为控制终端、绑定前景进程组
setsid();
ioctl(slave, TIOCSCTTY, 0);
pid_t child_group = getpid();
ioctl(slave, TIOCSPGRP, &child_group);

// 3. 把标准输入、输出、错误全部重定向到 slave
dup2(slave, STDIN_FILENO);
dup2(slave, STDOUT_FILENO);
dup2(slave, STDERR_FILENO);

// 4. 关掉除 exec_write 外的所有多余文件描述符，然后启动 PRoot + /bin/busybox sh -l
ChildCloseFromExcept(maximum_fd, exec_write);
execve(executable, argv_pointer, envp_pointer);
```

注意那个精巧的 **`exec_pipe`（带 `O_CLOEXEC` 标志）**：父进程等在 `exec_read` 上——如果 `execve` 成功了，内核会自动关闭 `exec_write`，父进程立刻读到 **0 字节（EOF）**，铁证说明新程序已经真正跑起来；如果 `execve` 失败了，子进程把 `errno` 写进管道再退出，父进程就能精准拿到失败原因！

另外，环境里特意传了 `HISTFILE=`（空值），防止 BusyBox ash 把命令历史写进已校验的 rootfs 破坏清单完整性。

### 2. 用 `poll(master, wake)` 做到秒级响应关闭，还不丢最后一口输出
Kotlin 的 `PtySession.waitForExit()` 会持续循环调 `nativeRead()` 排空输出，再用 `PtyCodec` 拼接可能被 8 KiB 分块截断的多字节 UTF-8 字符。

在 C++ 里，`nativeRead()` 同时 `poll()` 两个描述符：**`master_fd`** 和一条内部唤醒管道 **`wake_read_fd`**。而且**优先排空 `master_fd` 里剩余的数据，再响应 `wake` 信号**——这样无论是用户点关闭还是子进程正常退出，既能瞬间打断阻塞读、又不会把进程临终前打印的最后几行日志弄丢！

### 3. `TIOCSWINSZ` 改尺寸 & `Ctrl-C` 发中断
- **改尺寸**：`nativeResize()` 直接对 `master_fd` 调用 `ioctl(master_fd, TIOCSWINSZ, &size)`，内核会自动更新终端行列数并向前台进程组发送 `SIGWINCH`（注意：`stty size` 打印的顺序是**行、列**，比如 80×24 会输出 `24 80`）。
- **发 `Ctrl-C`**：点击“发送 Ctrl-C”时，Kotlin 调用 `sendControl(op, Control.INTERRUPT)` 往 master 写入字节 `0x03`，终端行规程收到后自动向前台进程组（比如正在跑的 `sleep 30`）发送 `SIGINT`，打断当前命令而**不杀死外层 shell**！

### 4. 防 PID 复用竞态：`waitid(WNOWAIT)` + 互斥 `waitpid`
Linux 里有个极隐蔽的并发 Bug：如果子进程刚退出就被 `waitpid()` 回收了，它的 PID 瞬间可能被操作系统分配给另一个无关进程，此时另一条取消线程再调 `kill(child_pid, SIGKILL)` 就会误杀无辜。

`pocketrun_pty.cpp` 用了一条极优雅的手法彻底堵死这个洞：reaper 线程先用 **`waitid(P_PID, child, &info, WEXITED | WNOWAIT)`** 等待子进程结束——`WNOWAIT` 意味着“我看见它死了，但先不回收它的僵尸槽位，PID 绝不会被复用”；接着抢下 `handle->mutex`，在锁内完成真正的 `waitpid(WNOHANG)` 并标记 `waited = true`。任何 `kill()` 也都在同一把锁内先检查 `!handle->waited`，从而在数学上杜绝了对复用 PID 发信号的可能！

### 5. 120 秒总期限与严格的资源清理
每个 PTY 会话默认最长存活 **120 秒**（不因持续输入而顺延），到期后自动关闭输入、终止进程并标为 `timed_out`：

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={ptyTimeoutScreenshot} alt="实际产品超时、清理确认与未观测退出快照" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>达到 120 秒会话期限后自动收尾，标记 timed_out 并确认所有原生句柄与临时 RUN 目录回收完毕。</figcaption>
</figure>

和前几章一样，如果某次关闭未能确认回收干净，`cleanupStatus` 会标为 `unproven`，模块锁住槽位只允许“重试 PTY 清理”，确保不泄漏文件描述符和后台子进程。

:::info 当前 UI 定位
本章的 React 界面是一个有界（64 KiB）的纯文本交互预览面板，用来验证和理解真正的 PTY 机制，还不是支持 ANSI 配色、光标定位和全屏 `vim` 的完整终端模拟器。
:::

## 动手跑一遍

1. 在 API 34+ ARM64 演示设备上打开 **第 8 章 · 交互式 PTY** 面板。
2. 点击 **使用 80×24**，再点 **启动交互终端**，等待状态变成 `PTY ready`。
3. 在输入框里分别输入以下命令（**注意每条命令末尾必须带一个换行回车**，shell 才会把这一行作为命令提交执行），点击 **发送 PTY 文本**：
   - 查看第 6 章留下的同一份 CSV：`/bin/busybox cat items.csv`
   - 统计行数：`/bin/busybox cat items.csv | /bin/busybox wc -l`（留意区分命令回显与独立打印出的结果 `3`）
   - 查询终端尺寸：`stty size`（应输出 `24 80`）；接着点 **使用 100×32** 再敲一次 `stty size`，应立刻变成 `32 100`！
   - 测试中断：发送 `sleep 30`，等待一秒后点击 **发送 Ctrl-C**，确认 `sleep` 被立即打断且 shell 仍可继续输入。
4. 最后发送 `exit 0`（或点 **关闭 PTY**），核对最终状态为 `exited / 0` 且 `cleanupStatus: done`。

## 第二部分小结与源码

到第 8 章为止，PocketRun 的**两条底层执行通道已经全部就位**：
- **通道一（第 1—5 章）**：通过无线 ADB + UID 2000 Binder 后端，在隔离虚拟屏上看界面、操作 App。
- **通道二（第 6—8 章）**：在普通 App UID 下，拥有持久私有工作区、防死锁管道、PRoot + Alpine 用户态以及真正的 JNI 交互式 PTY。

现在我们手里握着两把锋利的刀——**千万别直接把它们裸交给大模型！** 从下一章进入**第三部分**，我们先给这些底层能力套上一层滴水不漏的**确定性工具宿主与手机审批网关（`TaskHost`）**，再接上任务账本与真实的大模型循环。

- **本章源码快照**：[Release `chapter-8`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-8) · [PR #13](https://github.com/kkkhs/pocketrun/pull/13)
- **延伸参考**：[Linux pty(7) 手册](https://man7.org/linux/man-pages/man7/pty.7.html) · [实机验收与工程档案](verification-records.md)
