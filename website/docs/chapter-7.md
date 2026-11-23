---
sidebar_position: 8
sidebar_label: 第 7 章 · PRoot 与 Alpine
title: 用 Linux 命令读取同一份工作文件
description: 在普通 App UID 下启动固定的 Alpine 派生命令环境，核对版本、目录、CSV 行数与命令及清理结果。
---

> **本机原生验收已通过，正式发布继续收口。** 2026-11-20，任务专用 API 36 / ARM64 模拟器上已执行正常演示、12 项生命周期和 7 个 App/tracer/guest/安装死亡恢复场景。产品界面与最终组合回归仍需逐项核对；[Draft PR #12](https://github.com/kkkhs/pocketrun/pull/12) 提供源码基线。

[上一章](chapter-6.md)，我们在 App 私有目录里准备了 `items.csv`，用 Android 自带的 toybox 读取它，得到包含表头的三行。

这一章继续读**同一份文件**，但把执行环境换成真正的 Linux 用户态程序：**启动基于 Alpine 的固定小环境，读取版本，检查当前目录和真实 UID，再数出 CSV 的三行。** 不重建工作数据，也不改用 shell UID 2000。

在本次设备上的正常演示中，真实标准输出为：

```text
3.22.8
/workspace
10469
3
PR_EXITS:0,0,0,0,0
```

前四行分别是 Alpine 版本、命令看见的工作目录、实际 UID 和行数。最后一行记录五个内部步骤的退出码；外层 PRoot 进程也必须退出 `0`。

`10469` 是这台设备分配给 PocketRun 的 App UID，不是所有设备都应输出的常数。`3` 仍然是一行表头加两行商品，不是商品数量之和。

## 先在界面跑一遍

本章需要自己拥有或明确获授权的 **Android 14 / API 34 及以上、ARM64 设备，并且 App 运行在 64 位进程中**。这比前章的基础设备条件更严格；代码会拒绝不支持的运行环境，不降级成其他执行身份。

使用本地第 7 章源码构建的 App，打开“第 7 章 · PRoot 与 Alpine”：

1. 点“准备 Linux 环境”。应看到 `Alpine 3.22.8`、App UID，以及私有工作目录映射到 `/workspace` 的说明。
2. 第一次完整安装应提示“首次完整安装”；已有工作文件应提示“保留已有工作数据”，不能把已有 CSV 覆盖成初始样本。
3. 点“运行 Linux 演示”。检查版本为 `3.22.8`、cwd 为 `/workspace`、实际 UID 与 App UID 一致；未修改的初始 CSV 应得到行数 `3`。
4. 检查五个阶段退出码都为 `0`、PRoot 退出码为 `0`，且“资源清理”为 `done`。不能只看“命令状态：completed”。
5. 再次准备，应提示“复用已校验安装”；再次运行，再核对目录、UID、文件结果与清理状态。

操作尚未结束时可以点“取消 Linux 操作”。固定演示可能很快结束，来不及取消；“没有可取消的任务”不能算测到了取消。

初始化失败、校验失败或清理为 `unproven` 时，停止核对错误，不改权限重试、不手工删除仍可能被进程使用的目录，也不把有输出当作完整成功。改过的 CSV 不一定再得到 `3`。

对应代码：`app/LinuxPanel.tsx` 的 `perform()` 调用 `prepareLinux()` 或 `runLinuxDemo()`，`cancel()` 调用 `cancelLinux()`，并分别展示命令状态与清理状态。`app/specs/NativePocketRunLinux.ts` 只接受本次操作的关联 token，**没有命令、环境变量、绑定目录或任意文件路径参数**。

## 谁在执行，权限有没有改变

上一章的普通 App 身份保留下来：

| 代码或进程 | 做什么 | 实际身份 |
| --- | --- | --- |
| React / TypeScript | 发起固定操作、展示版本与结果 | App 进程 |
| App Kotlin | 校验安装与工作目录，启动进程，管理取消和清理 | 同一个 App 进程、App UID |
| PRoot 及 Linux 程序 | 调整程序看见的路径，运行 BusyBox 命令 | 独立进程，仍是普通 App UID |

Linux 程序使用的是 Android 设备的内核，不是另一台虚拟机。**PRoot 做用户态兼容，不提供 Android root，也不是额外的安全沙箱。** 真正的权限边界仍来自 App UID、Android 沙箱与系统策略。

目录里有 `/root`、环境变量里有 `HOME=/root`，也不会把执行身份变成 root。本章用真实 `id -u` 核对身份，不通过模拟 UID 的选项制造 `0`。

前章的 Android 操作通道仍是 App Kotlin → Binder → UID 2000 的 shell 后端。本章文件与 Linux 命令不走那条通道，不需要先启动它或完成无线 ADB 配对。电脑上的 ADB 只用于开发安装和测试，不参与产品的运行数据路径。

下面的 Kotlin 文件位于 `app/android/app/src/main/java/io/github/kkkhs/pocketrun/`。源码尚未公开发布，文末给出本地阅读入口和待建快照策略，不链接不存在的 `chapter-7` tag。

## 准备一个固定的小环境

这里的“Alpine 环境”是**从官方 Alpine 3.22.8 / aarch64 派生的最小 BusyBox / musl 环境**，不是完整发行版，也没有 `apk add` 或动态安装程序的能力。

BusyBox 用一个程序提供 `cat`、`wc`、`id` 等命令；musl 提供这些 Linux 程序使用的基础 C 运行库。PRoot 让它们按预期的 Linux 路径查找程序和数据。

可执行文件和可写数据分开放置：

| 内容 | 放在哪里 | App 如何使用 |
| --- | --- | --- |
| PRoot、PRoot loader、BusyBox、musl 四个可执行文件 | APK 携带，由 Android 安装到 `nativeLibraryDir` | 校验固定 SHA256，再启动或绑定；App 不写这里 |
| 版本文件、账号数据、目录与相对链接 | App 私有目录中的 data-only rootfs | 校验、解压和检查；普通文件没有执行权限 |
| `items.csv` | 前章的 `filesDir/workspaces/demo` | 保留内容，映射给本次命令读取 |

**不是把下载的程序放进可写目录再 `chmod +x`。** 安装到 App 私有目录的压缩包只含数据和链接，不含可执行二进制文件；四个程序来自 Android 安装的只读位置。

对应代码：`LinuxModule.createSession()` 读取 `linux/runtime.json`，取得系统提供的 `nativeLibraryDir`；`LinuxRuntimeManifest.read()` 校验固定版本与清单；`RootfsInstaller.prepare()` 校验四个文件和数据包，在独立临时目录完成安装，再发布完整安装。

<details>
<summary>安装内容怎样校验，怎样避免半安装与路径逃逸</summary>

当前运行清单是 `app/android/app/src/main/assets/linux/runtime.json`，版本为 `alpine-3.22.8-minimal-proot-5.1.107.102-candidate2-prebound`，variant 为 `minimal-busybox-musl`。只包含四个 ELF 可执行文件和两个 guest 代码绑定，不是曾研究的完整 19-ELF 候选。

当前数据资产为 `linux/alpine-data.bin`，587 字节、34 个条目，SHA256 为 `8c4ac18ae5babcbd3f692e1c4bed6710eb965967da792fc6ad597def4a1eff06`。它预置普通的 0600、零字节 `dev/null`，供固定 `/dev/null` 绑定使用，不是字符设备。`.bin` 只是打包名称，内容仍是固定的 gzip / ustar 数据归档；清单、实际字节与安装库存都要核对，不能只信文件名。先前正常演示使用的 570 字节资产是另一份历史快照，不能把它的设备结果借给新资产。

`RootfsInstaller.verifyArtifacts()` 检查四个只读文件及各自 SHA256；`RootfsTar.extract()` 有界解压，只接受普通数据文件、目录与允许的链接，拒绝 ELF、可执行普通文件、重复路径、截断归档、特殊条目和路径逃逸。归档链接在普通文件写入完成后才创建，最终变为仍落在 rootfs 内的相对链接。

安装先在本次拥有的 stage 目录完成，写入同步后的安装清单，再通过 `AtomicSamplePublisher.publishDirectory()` 的 no-replace 发布约定变为完整版本。已有版本重新校验后复用，不覆盖另一个安装者的结果，不把目录存在当作安装完成。

本例 App 的 `targetSdk` 保持 36。没有通过降低 target、可写目录执行、显式调用 Android linker、memfd 或 RWX 内存绕开系统执行限制；运行条件不满足时明确失败。这些限制与普通目录需要 owner 的读写/进入权限是两回事。

</details>

## 让 Linux 读到原来的 CSV

`LinuxSession.prepare()` 复用第 6 章的 `WorkspaceSession.prepare()`，所以两个面板使用同一个 `workspaces/demo/items.csv`。第一次没有文件时准备样本，已有文件则保留，不另外复制一份可能过期的 CSV。

运行时，`LinuxSession.demoArgv()` 固定把这个私有工作目录映射为 `/workspace`，并把命令的工作目录设成 `/workspace`。因此 Linux 命令中的相对路径 `items.csv` 指向前章的真实工作文件。

这是 PRoot 提供的路径映射，不是获得 Android 内核的 mount 权限，也不是给程序开放任意宿主目录。映射由 Kotlin 选择，JavaScript 不能提交新的绑定路径。

同理，Linux 看见的 `/bin/busybox` 与 `/lib/ld-musl-aarch64.so.1` 对应 APK 的两个固定只读文件。私有 rootfs 中的空占位文件不是实际执行的程序。

## 执行固定脚本，再核对每一步

固定演示按下面的顺序观察环境，再读取文件。这是内部脚本的可读形式，不是用户输入框：

```sh
cat /etc/alpine-release
pwd
id -u
cat items.csv | wc -l
```

与上一章不同，管道现在由 Linux BusyBox 的 shell 执行；App Kotlin 仍用 `ProcessBuilder` 的参数数组启动外层 PRoot，并用非交互字节管道收集输出。

`LinuxSession.demoEnvironment()` 提供固定环境，清除不需要的继承环境；`demoArgv()` 固定选择 PRoot、rootfs、映射和脚本。存在内部 `sh -c` 不等于对外开放任意 shell：脚本是 Kotlin 常量，没有拼入 JavaScript 命令文本。

实际脚本还分别保存版本、pwd、UID、cat、wc 的退出码，再输出 `PR_EXITS` 记录。这样 `wc` 的成功不能隐藏前面的文件读取失败。

对应代码：`LinuxSession.runDemo()` 校验安装与工作目录，调用前章的 `CommandPipeline.execute()`；`parseDemo()` 检查返回格式，确认版本、实际 UID、目录与五个阶段退出码，再生成给界面的结构化结果。

**输出、命令退出和资源清理是三个不同问题。** PRoot 返回 `0` 但内容不符合约定时仍判为失败；即便命令成功，清理未确认也不能作为完整验收通过。

<details>
<summary>内部参数与命令结果的边界</summary>

PRoot 参数包含固定的 `--kill-on-exit`、rootfs 路径、`-w /workspace`、清单里的 BusyBox / musl 绑定、私有 `/workspace` 与本轮 `/tmp`，以及固定的 `/proc`、`/dev/null` 映射。不能把这些示例泛化为任意目录授权。

`PROOT_LOADER` 指向 APK 安装的 loader；`PROOT_TMP_DIR` 指向本轮临时目录；其余环境由 Kotlin 固定。本章没有下载运行时、交互输入、凭据注入或包管理工具入口。

`LinuxResult.exitCodes` 是外层 PRoot 进程的退出码，`stageExitCodes` 是内部五个步骤的退出码。命令使用共享的 5 秒截止时间与 64 KiB 输出预算，沿用前章对输出超限和 UTF-8 返回文字的处理；准备与运行也各自检查当前操作是否取消或过期。

`token` 用于关联一次调用与取消，不是授予新权限的凭据。`installationGeneration` 表示经过校验的安装版本；页面拒绝属于旧操作或不同安装版本的迟到结果。`LinuxOperationGate` 在 Promise 返回前释放当前操作槽，关闭时使旧结果失效，不用 UI 按钮禁用代替原生检查。

</details>

## 清理临时资源，不删工作数据

每次运行都使用自己持有的临时目录。命令结束、取消或超时之后，先结束本次进程和 IO，再检查是否能安全回收本轮目录；`items.csv` 与完整安装不是本轮临时输出，不因此删除。

`LinuxSession.cleanupResult()` 单独返回 `cleanupStatus`。`done` 表示这条清理路径已获得确认；`unproven` 表示不能确认，保留命令输出与退出码，同时让界面显示警告，不伪装成成功清理。

对应代码：`LinuxDirectoryLease.allocate()` 持有本轮目录的文件锁与所有权记录；`close()` 和 `recover()` 负责有界清理与恢复。`LinuxRecoveryProbe.isUnusedByAppProcesses()` 检查实际进程对目录和运行程序的引用。

**删除目录本身不证明 guest 已经全部退出。** 活跃 PRoot / guest、目录引用、读取错误或观察超时都不能当作不存在；不确定时保留目录，等待可以安全确认的恢复机会。

<details>
<summary>目录所有权、进程死亡与恢复为什么要一起检查</summary>

这里的 directory lease 是本地文件锁，不是云设备租约。记录包含目录名、类型、进程 generation、UID、PID、启动时间和父目录身份；完整记录同步后才创建临时目录。恢复先取得锁、校验记录和所有权，不凭名字像临时文件就删除。

stage 和 run 的恢复条件不同。run 目录还需检查 App UID 相关进程的 maps、cwd 和 FD，以及仍活跃的 PRoot / loader / guest 程序。只有明确的不存在才当作不存在；权限或 IO 错误、损坏记录、未知属性与预算耗尽都保守保留。

取消按本次 token 绑定已经预留的操作，模块关闭使当前操作失效，并让原生工作队列收尾。App、tracer 或 guest 的硬死亡仍须在专门设备测试中观察 PID、线程、FD、文件锁和目录恢复；不能用启动参数、JVM mock 或一轮正常退出代替这些证明。

</details>

## 已经验证什么

2026-11-20，任务专用 `pocketrun-ch1-api36` 模拟器（Android 16 / API 36、ARM64、App UID 10150）执行了当前预置绑定点版本：

| 检查 | 实际结果 |
| --- | --- |
| 正常安装、目录映射、重复演示 | `Chapter7LinuxDemoTest`：`OK (1 test)`；Alpine 3.22.8、`/workspace`、行数 3、五阶段退出码 0 |
| 取消、关闭、超时、安装/RUN 库存等生命周期 | `Chapter7LifecycleIntegrationTest`：`OK (12 tests)` |
| App / tracer / guest 硬死亡 | 默认及 fallback 各三场景，共 6 场景通过；host 按 UID/PID/startTicks 选定自有对象，原生断言和显式 procfs 消失观察都通过 |
| 安装发布前 App 死亡 | fsync 后未发布 stage 保留，生产安装器恢复并保留工作 CSV，重跑成功 |
| 资源与数据 | 原生检查 FD/读写线程/RUN 收尾；死亡恢复重新运行成功，原工作文件与无关 stage 未被当作垃圾删除 |

这批死亡恢复使用 Debug APK SHA256 `58b62dda2ffb74f413164c152cfb01cd3b5da07e92d17ab71bd8fe9fcfe98b76`、Test APK SHA256 `6b96dbb4742acc58a6a36fec139e8f51815d905a6488b8cb2f2144fbfd52754f`。原生结果与 host 进程证据是同一批检查；不以 host ADB 退出码代替 JUnit 成功。

<details>
<summary>2026-11-19 的历史批次与原失败记录</summary>

2026-11-19，在本任务获授权的 Android 16 / API 36、ARM64 云真机上，`Chapter7LinuxDemoTest.actualInstallationMappingAndRepeatedAlpinePipeline()` 实际执行了安装、复用安装和两次正常演示：

| 检查 | 已记录结果 |
| --- | --- |
| 原生正常演示测试 | `OK (1 test)`，0.344 秒；同一测试内重复运行两次 |
| 实际 Linux 输出 | 两次均为版本 `3.22.8`、cwd `/workspace`、UID `10469`、初始 CSV 行数 `3` |
| 内部步骤与外层进程 | 五个步骤均为 `0`，PRoot 退出码为 `0`，stderr 空 |
| 本轮正常清理与工作数据 | 两次断言 `cleanupStatus == done`；关闭会话后工作文件仍保留 |
| 固定 APK 的静态内容 | 四个 Linux ELF 与清单 SHA256 一致；历史正常批次为 570 字节资产，当前预置绑定点版本为 587 字节且实际 APK entry SHA 一致；包含许可证/版权文本及索引 |
| 完整生命周期 | 原 10 项／3 失败、11 项／2 失败保留；修复等待夹具重复打开 FIFO FD 后，新 12 项生命周期通过，默认和 fallback 回收均有原生断言。但显式 App 死亡后的 rootfs 重用仍失败于运行后出现的不可读绑定占位；587 字节预置版本尚未取得新真机结果。本章仍未完整验收 |
| 真实产品界面与网站 | 产品界面仍待本章完整操作验收；此前正文已有手机／桌面深浅主题渲染检查，新增对照内容仍需最终版本统一复核 |
| 公开发布 | **本章 PR、CI / Pages、注释 tag、源码 Release 与对应源码公开交付均待完成** |

正常演示的 JSON 输出没有单独打印 `cleanupStatus`；这一项由实际通过的测试源码断言。不能把它描述成原始 JSON 已包含的字段。

早期启动探测还真实观察到 PRoot 版本与 Linux `id -u` 返回 App UID，但它只说明基本启动链可用，与正常演示不是同一批证据。上表不把探测、静态检查、界面计划和生命周期计划拼成“一轮全过”，也不冒充 Case Center 原生报告。

</details>

<details>
<summary>证据对应的构建与可运行检查</summary>

上表原正常演示对应 App APK SHA256 前缀 `37ee35e17146`、AndroidTest APK 前缀 `e7cdeb257046`。安装记录均退出 `0`，但通过结论来自原始 runner 的 `OK (1 test)` 和实际断言，不来自“安装成功”。candidate2 另一次真实正常演示也通过，对应 App `b74b47d7950c`、AndroidTest `462c18f18553`；其新生命周期专项为 11 项／2 失败。不同包、不同批次分别保留，不把旧包证据借给新源码。

先按[第 1 章](chapter-1.md)准备已有工具链与依赖。源码检查、ARM64 构建和审计从仓库根目录起算：

```bash
cd app
npm run typecheck
npm run lint
npm test -- --runInBand
npm run audit:ci
cd android
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

在已确认的自有或授权演示设备上安装对应 APK。下面仍从 `app/android/` 起算，`AUTHORIZED_SERIAL` 必须是显式核对过的目标：

```bash
adb -s "$AUTHORIZED_SERIAL" shell getprop ro.build.version.sdk
adb -s "$AUTHORIZED_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$AUTHORIZED_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$AUTHORIZED_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$AUTHORIZED_SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.Chapter7LinuxDemoTest \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
```

这个命令只运行正常演示，不覆盖全部生命周期。异常死亡测试需要各自的宿主协议与资源观察，不能无驱动地运行或借主机退出码宣布成功。

最终还需 JNI / ELF / 16 KiB 对齐、实际 APK 范围、许可证与对应源码交付检查。网站在 `website/` 独立运行 `test:toolchain`、`typecheck`、`build` 和 `audit:ci`，再检查手机/桌面渲染与链接。这里列的是应执行的检查，不声称本轮写作重新运行了它们。

</details>

<details>
<summary>固定来源、许可证与公开分发边界</summary>

PRoot 来源固定为 [termux/proot 的 v5.1.107.102](https://github.com/termux/proot/tree/v5.1.107.102)，原始 ZIP SHA256 为 `30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852`。本例保留针对实际运行边界的修改、构建脚本和完整对应源码记录，不能把上游未修改版本的源码链接当作修改后可执行文件的全部源码。

Alpine 原始来源是[官方 3.22.8 / aarch64 minirootfs](https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.8-aarch64.tar.gz)，SHA256 为 `36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4`。实际分发范围是前述最小派生内容，不能把原始 rootfs 的 16 个安装包数据库当作本例已经安装的功能。

匹配范围与许可证记录位于 `app/third_party/linux/notice/MINIMAL-DISTRIBUTION.txt`、`LICENSE-SCOPE.txt`、`source-provenance.json` 以及同级 `licenses/`：

- PRoot 原始源码为 GPLv2-or-later；与 LGPLv3-or-later 的 talloc 组合后，可执行文件采用 GPLv3 分发条件，保留各自版权与修改说明。
- 原始 Alpine BusyBox 为 GPLv2-only；其静态包含的 utmps / skalibs 也需要匹配的 ISC 版权与源码，不因没有独立 `.so` 就忽略。
- musl 为 MIT，并保留其第三方许可与版权。Alpine 不是整体一个“MIT 许可证”。

当前最小环境不携带 apk/libapk、ssl_client、OpenSSL 等完整候选中的程序，不提供包安装或完整发行版能力。移除未使用程序不等于所有公开分发义务已完成。

实际 APK 已打入 17 份许可证/版权文本与索引，但**私有对应源码归档、上游主页和将来准备创建的 tag 都不是已完成的公开源码交付**。发布时需提供版本匹配的完整对应源码、修改与构建/安装/必要重链接说明，核对可访问下载及校验和，不先公开二进制或派生 rootfs 再补来源。本教程不提供公开调试签名 APK 下载。

</details>

<details>
<summary>Ditto 实物对照：原理相近，不等于完整复刻</summary>

最初采用的是“Ditto 类似执行核心”的架构意图，不是已经逐项读过 Ditto 原始源码。后来对 Ditto 0.1.14 APK 与可读的重建方法做了实物对照：它也使用 App 的 `nativeLibraryDir` 中的 PRoot / loader 来承接 Linux 命令，并有 Termux 风格的 JNI 子进程、PTY 尺寸与 IO 路径。这能说明实现结构，不能单独证明 Ditto 设备上的实际 UID、所有降级分支或完整取消行为。

范围有明确差异：Ditto APK 内是 Alpine **3.24.3**、**515 个归档条目**，包含 `apk`；PocketRun 本章是 Alpine **3.22.8 派生的最小 BusyBox / musl**，只有四个 Linux ELF、两个 guest 代码绑定，不支持 `apk add`。路径兼容、程序来源与权限边界可借鉴，但不能称为完整发行版或全部能力已对齐。

JADX 1.5.1 的重建结果有 **32 个错误**，属于部分可读的反编译代码，不是原始源码；出错或缺失的方法不能补猜。可访问的[Ditto 包源码候选](https://github.com/WindySha/ditto-termux-packages)尚未核为整个 App 的原始源码或本次 APK 的对应版本，也不替代修改后程序的对应源码与许可证交付。React Native / TurboModule 是 PocketRun 的选择，不能据此反推 Ditto 同样使用它们。

这份对照不扩大历史批次的结论：candidate1 的正常演示是原批次 `OK (1 test)`、0.344 秒、重复两次，原生命周期失败仍保留；candidate2 的正常路径和生命周期另有独立记录。当前预置绑定点版本的完整本机检查见“已经验证什么”，其中默认/fallback 的 App、tracer、guest 死亡恢复和安装死亡恢复属于 2026-11-20 的新批次。不同批次不拼成一轮结果。

</details>

## 下一章再做交互终端

本章得到的是一个固定的非交互 Linux 任务：同一份 CSV 在新的用户态环境中可读，版本、目录、实际身份、结果与正常清理可以核对。

它还不证明完整发行版兼容，不提供任意命令、包管理、交互终端或 Agent。下一章讲真正的 PTY，让输入、输出和终端尺寸变化成为可交互会话；它有独立的实现与验收边界，本章的 `ProcessBuilder` 管道不能冒充它。

开发继续依赖已经验证的代码与能力，不依赖前章是否已发布。PR、Pages、tag 和 Release 是独立的发布待办；它们未完成时继续开发，但不把本地验证写成线上已交付。

练习：如果 Linux 命令返回 `completed`，但清理是 `unproven`，为什么不能直接删除临时目录并宣布完成？`/workspace` 与 `/root` 分别说明路径还是权限？

### 本地源码与待建快照

| 本地阅读入口 | 重点 |
| --- | --- |
| `app/LinuxPanel.tsx`、`app/specs/NativePocketRunLinux.ts` | 固定入口、token、命令与清理分开展示 |
| `LinuxModule.kt`、`LinuxOperationGate.kt` | 环境门禁、原生队列、关闭与结果失效 |
| `LinuxSession.kt` | `prepare()`、`runDemo()`、`demoArgv()`、`parseDemo()`、`cleanupResult()` |
| `RootfsInstaller.kt`、`RootfsTar.kt`、`LinuxRuntimeManifest.kt` | 固定资产、路径校验、完整安装与复用 |
| `LinuxDirectoryLease.kt`、`LinuxRecoveryProbe.kt` | 所有权、文件锁、保守恢复与真实引用观察 |
| `app/android/app/src/androidTest/java/io/github/kkkhs/pocketrun/Chapter7LinuxDemoTest.kt` | 实际安装和重复两次演示断言 |

表中的 Kotlin 生产文件均在前文给出的 `app/android/app/src/main/java/io/github/kkkhs/pocketrun/` 下。公开仓库尚无本章已发布快照，暂不生成指向不存在文件或 tag 的成功链接。

验收与实际发布完成后，再登记真实 PR、已核验提交上的 `chapter-7` 注释 tag、源码 Release 和匹配的第三方对应源码下载，将本地路径补为可访问的固定快照链接。已发布的前章日期与测试数字保持各自历史快照，不随本章更新。
