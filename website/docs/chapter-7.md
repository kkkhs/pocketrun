---
sidebar_position: 2
sidebar_label: 第 7 章 · PRoot 与最小 Alpine
title: 第 7 章：在 App 里跑起 PRoot 与最小 Alpine 环境
description: 彻底搞懂为什么“Android 用着 Linux 内核却跑不了常规 Linux 程序”，通过 nativeLibraryDir 绕开 W^X 限制，用 PRoot 让 Alpine 工具直接读取 App 私有目录的 CSV。
---

import linuxResultScreenshot from './assets/chapter-7-linux-result.png';
import LinuxPathMapping from './assets/linux-path-mapping.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章我们用 Android 自带的 `toybox` 数出了 `items.csv` 的行数。但在真实世界里，Agent 迟早会需要标准 Linux 生态里的命令行工具和目录环境。

一提到“在 Android 上跑 Linux”，几乎所有人的第一反应都是：**Android 底层本来不就是 Linux 内核吗？那我直接把 ARM64 的 Linux 程序拷进 App 目录里执行不就行了？**

如果你真的去试一次，会连续撞上**三堵结结实实的墙**——而翻过这三堵墙的过程，正好就是 Termux、Ditto 以及 PocketRun 这类工具的核心原理。

这一章，我们继续读取第 6 章留下的那份 `items.csv`，但把执行者换成 **Alpine Linux 的 BusyBox + musl**，同时让你彻底看懂 PRoot 在中间施了什么魔法。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={linuxResultScreenshot} alt="独立第 7 章 App 读取同一 CSV，显示目录、身份、三行结果与清理" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>Alpine 3.22.8 环境成功读取同一份 items.csv：工作目录显示为 /workspace，真实 UID 仍是 App UID 10150，行数 3，清理完成。</figcaption>
</figure>

## 为什么“共用 Linux 内核”还不够？三堵墙与三把梯子

先理清最容易混淆的概念：**内核（Kernel）≠ 发行版（Distro）**。Android 手机确实运行着 Linux 内核，负责进程调度、文件系统和网络；但它上面的用户态世界和桌面 Linux 截然不同：

### 第一堵墙：C 运行库和目录树完全不兼容（Bionic vs musl）
Android 原生程序链接的是 Google 自己的 **Bionic** C 库，系统路径长成 `/system/bin/...`；而标准 Alpine Linux 编译出来的程序依赖 **musl** 动态链接器，写死要去 `/lib/ld-musl-aarch64.so.1` 找加载器、去 `/bin/busybox` 找命令、去 `/etc` 和 `/tmp` 找配置与临时文件。直接执行，内核连动态链接器都找不到。

**梯子**：自带一套精简的 Alpine 用户态（本例是 Alpine 3.22.8 派生的最小 **BusyBox + musl** 环境 + 极简数据目录树 rootfs）。

### 第二堵墙：Android 高版本禁止从可写目录执行二进制（W^X 限制）
从 Android 10（`targetSdk >= 29`）起，系统 SELinux 策略严格实行 **W^X（可写不可执行）**：普通 App 绝对不能把一个 ELF 二进制解压到自己的私有可写目录（`filesDir`），然后 `chmod +x` 去 `execve()` 它。

你总不能为了跑 Linux 去把 `targetSdk` 降到 28（应用商店根本不收，现代系统也会报警）。那 Ditto 和 PocketRun 是怎么合法解决的？

**梯子：代码与数据彻底分离！**
Android 允许 App 执行随 APK 安装在只读目录 **`nativeLibraryDir`** 里的原生库文件。于是我们把 4 个真正需要执行的 ARM64 ELF 文件，按 Android 约定命名放进 `app/android/app/src/main/jniLibs/arm64-v8a/`：

| 打包在 APK 里的文件名（只读可执行） | 实际身份 | 在 Linux 里被映射成的路径 |
| --- | --- | --- |
| `libproot.so` | PRoot 主程序 | 宿主机直接启动 |
| `libproot_loader.so` | PRoot 进程加载器 | 通过 `PROOT_LOADER` 传入 |
| `libalpine_bin_busybox.so` | Alpine BusyBox | `/bin/busybox` |
| `libalpine_lib_ld_musl_aarch64_so_1.so` | musl 动态链接器 | `/lib/ld-musl-aarch64.so.1` |

而解压进 App 私有可写目录 `filesDir` 里的 `alpine-data.bin`，**全是纯数据、目录和软链接**（比如 `/etc/alpine-release`），里面一个 ELF 和可执行权限位都没有！

### 第三堵墙：没有 Root，不能 `chroot` 也不能 `mount`
文件放好了，但问题来了：App 没有 Root 权限，没法调用 Linux 的 `chroot()` 把目录切换成根 `/`，也没法调用 `mount --bind` 把 `nativeLibraryDir` 里的 `libalpine_bin_busybox.so` 挂到 `/bin/busybox`，更没法把第 6 章的 `filesDir/workspaces/demo` 挂到 `/workspace`！

**梯子：PRoot —— 用户态的“路径翻译官”**
这就是 **PRoot** 登场的时刻。它不需要 Root，也不启动第二个内核，而是利用 Linux 的 `ptrace` 机制在用户态跟踪被启动的子进程：每当 BusyBox 发起打开文件、读取目录或执行程序的系统调用时，PRoot 就在半路把路径悄悄改写掉：

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <LinuxPathMapping className={`pr-linux-path-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-linux-path-title" aria-describedby="pr-linux-path-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>PRoot 路径映射：Linux 程序以为自己在访问 /bin/busybox 和 /workspace/items.csv，实际落到的分别是 APK 只读目录和 App 私有工作区。</figcaption>
</figure>

:::tip 彻底记住 PRoot 的本质
- **它不是虚拟机**，没有模拟 CPU，也没有启动第二个操作系统，所以启动耗时只有一百多毫秒。
- **它不赋予 Root 权限**。哪怕我们在环境里把 `HOME` 设成 `/root`，程序里执行 `id -u` 依然返回普通 App UID（例如 `10150`），真正的安全边界永远由 Android 沙箱守住。
- **它不拷贝你的 CSV**。`/workspace` 直接绑定到第 6 章的真实目录，两边看到的是磁盘上的同一份文件。
:::

## 顺着代码看完整链路

### 1. 准备环境：先校验、再原子安装

点击“准备 Linux 环境”时，[LinuxSession.prepare()](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt) 做两件事：
1. 先复用第 6 章的 `WorkspaceSession(filesDirectory).prepare()`——确保 `items.csv` 存在且不覆盖已有修改；
2. 再交给 [RootfsInstaller.kt](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/RootfsInstaller.kt) 校验 4 个 `.so` 和 `alpine-data.bin` 的 SHA-256（清单写在 `assets/linux/runtime.json`），在一个临时 stage 目录里解压纯数据归档（明确拒绝归档里混入任何 ELF、可执行位或路径穿越 `../`），写完 `installed.manifest` 清单后原子发布到最终目录。

以后再点“准备”，只要清单校验通过就直接复用，不必重复解压。

### 2. 启动 PRoot：把所有映射拼成参数数组

实际拉起 PRoot 的参数一目了然，位于 [LinuxSession.demoArgv()](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt)：

```kotlin title="LinuxSession.kt（启动参数与固定环境）"
listOf(
    File(native, "libproot.so").path,
    "--kill-on-exit",
    "-r", root.path,                    // 把解压出的 data-only rootfs 作为“根目录 /”
    "-w", "/workspace",                 // 设定工作目录
    "-b", "${File(native, "libalpine_bin_busybox.so").path}:/bin/busybox",
    "-b", "${File(native, "libalpine_lib_ld_musl_aarch64_so_1.so").path}:/lib/ld-musl-aarch64.so.1",
    "-b", "${workspace.path}:/workspace", // 映射第 6 章的私有工作区
    "-b", "${tmp.path}:/tmp",           // 本次运行专属的隔离临时目录
    "-b", "/proc:/proc",
    "-b", "/dev/null:/dev/null",
    "/bin/busybox", "sh", "-c", DEMO_SCRIPT,
)
```

同时清空继承自 Android 的杂乱环境变量，只注入精准的白名单变量（`PROOT_LOADER` 指向 `libproot_loader.so`，`PROOT_TMP_DIR` 指向本轮临时目录，`PATH=/bin:/usr/bin`），然后直接喂给**第 6 章写好的 `CommandPipeline.execute()`**！你看，上一章的管道引擎立刻派上了大用场。

### 3. 为什么脚本要输出 5 行，而不是只打印一个 `3`？

如果你直接跑 `cat items.csv | wc -l`，在 POSIX BusyBox `sh` 里没有 bash 的 `set -o pipefail`——哪怕 `cat` 失败了，只要 `wc` 读到空输入返回 `0`，整条命令的退出码还是 `0`！

所以 [LinuxSession.DEMO_SCRIPT](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt) 既把 5 项运行证据全部打出来，又用临时文件保住了 `cat` 的真实退出码：

```text title="一次正常运行的标准输出（5 行结构化证据）"
3.22.8                 ← /etc/alpine-release，证明真的跑在目标 Alpine rootfs 里
/workspace             ← pwd，证明工作目录映射生效
10150                  ← id -u，证明真实身份仍是当前 App UID
3                      ← cat items.csv | wc -l 的真实行数
PR_EXITS:0,0,0,0,0     ← 上述 5 个步骤各自的退出码，一个非零都不放过
```

Kotlin 的 `parseDemo()` 逐行核对：版本匹配、cwd 是 `/workspace`、guest UID 等于 App UID、5 个内部退出码和外层 PRoot 退出码全为 `0`，才把本次运行判为 `completed`。

### 4. 每次运行独立临时目录与目录租约（`LinuxDirectoryLease`）

每次跑完后，`LinuxSession` 会把本轮生成的临时目录 `linux/runs/<run-id>` 清理掉，同时保留完整 rootfs 和用户的 `items.csv`。

但如果 App 在 PRoot 跑到一半时被系统强杀（`SIGKILL`），`finally` 根本来不及执行怎么办？为此我们设计了 `LinuxDirectoryLease`：每个运行目录里都带着锁和进程身份记录（UID、PID、进程启动时间戳 `startTicks`）。下次恢复扫描时，只有**通过 `/proc` 明确确认原进程和所有子进程都不再占用这个目录**，才会安全删除；遇到无法确认的异常情况，就老实把 `cleanupStatus` 标成 `unproven` 并保留现场，绝不胡乱 `rm -rf`。

:::info 当前最小环境 vs 完整 Linux 发行版
本章刻意把打包体积和攻击面压缩到最精简的 **4 个 ELF（PRoot + loader + BusyBox + musl）**，重点是把 Android 跑 Linux 的核心原理讲透、跑稳。它目前还不带 `apk` 包管理器和网络装包能力——这部分扩充计划详见 [Ditto 功能对照与路线图](ditto-parity.md)。
:::

## 动手跑一遍

本章要求 **Android 14（API 34）及以上、64 位 ARM64** 演示设备（继续使用第 1 章的 API 36 ARM64 模拟器即可，无需启动 ADB 后端）：

1. 打开 PocketRun 的 **第 7 章 · PRoot 与 Alpine** 面板。
2. 点击 **准备 Linux 环境**，首次点击会完成校验与安装；如果第 6 章已经初始化过 `items.csv`，会提示“保留已有工作数据”。
3. 点击 **运行 Linux 演示**，观察输出：Alpine 版本 `3.22.8`、工作目录 `/workspace`、guest UID 等于 App UID、行数 `3`、5 个阶段退出码全 `0`、`cleanupStatus: done`。
4. 再点一次 **准备** 和 **运行**，验证已安装环境会被直接复用，耗时只需百毫秒级。

## 本章小结与源码

这一章我们翻过了 Android 跑 Linux 的三座大山（Bionic/musl 差异、W^X 限制、无 Root 路径映射），在普通 App UID 下稳稳跑起了 PRoot + Alpine，并和第 6 章无缝共享同一份工作区文件。

不过现在的运行方式依然是“一次性批处理”：发一条脚本进去，等它跑完退出，再拿结果。如果我们想在 App 里打开一个**常驻的交互式 shell**——输一行看一行输出、动态改窗口尺寸、还能按 `Ctrl-C` 打断正在跑的 `sleep 30`——普通管道就不够用了。下一章，我们下潜到 C++ / JNI，打造真正的伪终端 **PTY**！

- **本章源码快照**：[Release `chapter-7`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7) · [PR #12](https://github.com/kkkhs/pocketrun/pull/12)
- **延伸参考**：[PRoot 项目（termux/proot）](https://github.com/termux/proot) · [Alpine Minirootfs](https://alpinelinux.org/downloads/) · [实机验收与工程档案](verification-records.md)
