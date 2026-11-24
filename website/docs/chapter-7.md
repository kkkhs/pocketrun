---
sidebar_position: 8
sidebar_label: 第 7 章 · PRoot 与 Alpine
title: 让 Linux 命令读到 App 里的同一份 CSV
description: 从读取同一份 CSV 出发，理解 PRoot 的路径转换、BusyBox 与 musl，以及结果怎样从 Linux 子进程回到 React。
---

import linuxResultScreenshot from './assets/chapter-7-linux-result.png';
import LinuxPathMapping from './assets/linux-path-mapping.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

[上一章](chapter-6.md)，App 在私有目录里准备了 `items.csv`，用 Android 自带的 toybox 数出了三行。文件已经在手机上，不需要重新导入。

这一章继续做同一个任务：**让 Linux 的 `cat` 和 `wc` 读到这份 CSV，再把行数显示回 PocketRun。** 我们增加的是 Linux 用户态运行环境，不是新的工作数据，也不是另一台虚拟机。

```text
准备：保留原 CSV，校验固定 Linux 程序和目录
执行：转换程序看见的路径 → 读取同一文件 → 收集输出
核对：行数、目录、身份与退出结果
结束：回收本轮进程和临时目录，保留 CSV
```

这里有一个值得先想清楚的问题：Linux 命令使用 `/workspace/items.csv`，而 Android 把文件放在 App 私有目录里。**这两个名字怎样指向同一个文件？** 本章就沿着这个问题往下走。

## 1. 先运行，认识结果里的几行文字

在自己拥有或明确获授权的 **Android 14 / API 34 及以上、ARM64 的 64 位 App 进程**中，打开“第 7 章 · PRoot 与 Alpine”：

1. 点“准备 Linux 环境”。首次使用时安装固定环境；已有 CSV 应提示“保留已有工作数据”。
2. 点“运行 Linux 演示”。未修改的初始文件应得到行数 `3`。
3. 同时核对 cwd 为 `/workspace`、实际 UID 等于 App UID、五个内部步骤和外层进程的退出码都为 `0`，资源清理为 `done`。
4. 再次准备和运行。应复用已校验安装，读取同一份工作文件，而不是每次重建样本。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={linuxResultScreenshot} alt="独立第 7 章 App 读取同一 CSV，显示目录、身份、三行结果与清理" width="400" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>同一 CSV 的真实行数、运行目录、身份与清理结果。</figcaption>
</figure>

这张实际截图来自 2026-11-23 的独立章节包。原 CSV 被保留，App/guest UID 都是 10150，读取耗时 142ms。这个 UID 是该设备分配的，不是代码要求所有手机输出的常数。

演示的标准输出为：

```text
3.22.8
/workspace
10150
3
PR_EXITS:0,0,0,0,0
```

第一行是环境版本；第二行是程序认为自己所在的目录；第三行来自真实 `id -u`；第四行才是 CSV 行数。`3` 仍是一行表头加两行商品，且演示文件每行以换行结束，不是商品数量相加。

**为什么多读版本、目录和 UID？** 因为只拿到一个 `3`，还无法确认它来自正确的文件和运行环境。下面的实现会把这些观察一起检查，再生成给页面的结果。

## 2. 谁执行：换了程序，权限没有增加

| 负责者 | 在这次任务中做什么 | 进程与身份 |
| --- | --- | --- |
| React / TypeScript | 点击准备和运行，显示行数与状态 | 普通 App 进程 |
| App Kotlin | 校验环境、选择参数、启动并回收进程 | 同一个 App 进程、App UID |
| PRoot | 跟踪 Linux 程序，把它们使用的路径转换为实际文件路径 | App UID 下的子进程 |
| BusyBox / musl | 执行命令、提供命令所需的 C 运行库 | 同一 App UID 下的 Linux 用户态程序 |

**这些程序仍使用手机的 Android/Linux 内核。** PRoot 没有启动另一个内核，也没有给 App root 或 shell UID 2000。目录名 `/root`、`HOME=/root` 都只是程序看见的路径，不改变真实 UID。

从 React 视角看，TurboModule 仍是“调用 App 内的 Kotlin 方法”。只有 Kotlin 启动程序时才产生子进程；一个方法、一层语言和一个进程不是同一件事。

前章的手机操作通道是 App Kotlin → Binder → UID 2000 后端。本章直接使用普通 App 的文件和进程能力，**不需要先配对 ADB 或启动显示后端**。开发时的电脑 ADB 可以安装和测试 App，但不搬运产品运行中的 CSV 数据。

## 3. 准备环境：先让程序和数据各就各位

点准备按钮时，React 调用的仍是一个普通异步方法。下面摘自固定 [chapter-7 的 LinuxPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/LinuxPanel.tsx#L38-L43)：

```ts
const next = await NativePocketRunLinux.prepareLinux(token);
if (generation === epoch.current) {
  if (next.token !== token) { throw new Error('Linux 安装结果不属于当前操作。'); }
  setInfo(next);
}
```

`token` 把返回值和这次操作关联；`setInfo(next)` 才让页面显示安装、UID 和工作目录。它不是执行权限，也不是模型凭据。

Kotlin 接到请求后，先沿用第 6 章的文件准备，再准备 Linux 环境：[LinuxSession.prepare()](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt#L48-L64)。

```kotlin
val sample = WorkspaceSession(filesDirectory).use { it.prepare() }
val next = installer.prepare({ guard(operation) }) { action ->
    synchronized(lock) { guard(operation); action() }
}
```

第一行的关键是**复用同一个 `WorkspaceSession`**：已有 `workspaces/demo/items.csv` 就保留它，不另外造一份 Linux 专用 CSV。

接下来要准备的不是完整 Alpine 发行版，而是 Alpine 3.22.8 派生的最小 BusyBox/musl 环境：

- **BusyBox**：一个可执行程序提供 `cat`、`wc`、`id` 等命令。
- **musl**：这些 Linux 程序使用的基础 C 运行库。
- **PRoot / loader**：承接程序启动与路径兼容。
- **data-only rootfs**：提供版本文件、目录、账号数据与允许的链接。

四个可执行文件由 APK 携带，Android 安装到只读的 `nativeLibraryDir`；可写 rootfs 和 CSV 在 App 私有目录里。**可执行代码和工作数据分开，是因为 Android 对可写目录中的程序执行有真实限制。** 本例没有把下载的 ELF 放进私有目录再 `chmod +x`，也没有降低 `targetSdk` 绕开限制。

固定校验与完整安装由 [RootfsInstaller](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/RootfsInstaller.kt) 负责；归档和许可证细节放在文末。本章环境不提供 `apk add` 或动态包安装。

## 4. 让两个路径指向同一份文件

Linux 程序寻找 `items.csv` 时，先需要一个它能理解的工作目录。PRoot 的 `-w /workspace` 设定程序看见的 cwd，固定绑定再把 App 目录转换成 `/workspace`。

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <LinuxPathMapping className={`pr-linux-path-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-linux-path-title" aria-describedby="pr-linux-path-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>这张图回答“路径最后落到哪里”，不是任务的时间顺序。PRoot 转换路径；它没有给 App 内核挂载权限。</figcaption>
</figure>

实际启动参数在 [LinuxSession.demoArgv()](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt#L204-L208)：

```kotlin
listOf(File(native, "libproot.so").path, "--kill-on-exit", "-r", root.path, "-w", "/workspace") +
    bindings.flatMap { listOf("-b", "${File(native, it.name).path}:${it.guestPath}") } +
    listOf("-b", "${workspace.path}:/workspace", "-b", "${tmp.path}:/tmp", "-b", "/proc:/proc",
        "-b", "/dev/null:/dev/null", "/bin/busybox", "sh", "-c", DEMO_SCRIPT)
```

先只看与 CSV 有关的两处：

1. `-w /workspace`：相对文件名 `items.csv` 从这个目录解析。
2. `${workspace.path}:/workspace`：这里的 `workspace.path` 就是 App 的 `filesDir/workspaces/demo`。

所以 `cat items.csv` 读到的是第 6 章留下的文件。PRoot 不复制它；文件被合法修改后，下一次读取也会反映新内容。

程序本身同样使用路径绑定：Linux 看见的 `/bin/busybox`、`/lib/ld-musl-aarch64.so.1` 对应 APK 安装的固定程序。PRoot 跟踪程序的系统调用并处理路径转换，让 Linux 程序按自己的目录布局找到程序与数据；真正的访问仍受 App UID 和系统策略约束。

绑定、环境和脚本都由 Kotlin 选择。**React 只能说“运行这个演示”，不能提交新的目录、可执行文件或命令字符串。**

## 5. 运行命令：这次管道由 Linux shell 连接

第 6 章用 Kotlin 把两个 toybox 进程连接起来。本章的外层仍由 `ProcessBuilder` 启动，但里面运行的是 BusyBox shell；`cat` 与 `wc` 之间的管道由这个 shell 安排。

固定脚本的核心读取动作是：

```sh
cat items.csv | wc -l
```

这是脚本核心动作的说明，**不是完整生产脚本**。实际 [DEMO_SCRIPT](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt#L199-L203) 还读取版本、cwd 和 UID，分别保存版本、pwd、UID、cat、wc 的退出码，最后输出 `PR_EXITS`。这样最后的 `wc` 成功不会隐藏前面的文件读取失败。

这次数据传递是：

```text
App 私有 CSV
  → PRoot 转换后的 /workspace/items.csv
  → BusyBox cat 的 stdout
  → shell 建立的管道
  → BusyBox wc 的 stdin
  → 外层进程 stdout → App Kotlin
```

`LinuxSession.runDemo()` 复用前章 [CommandPipeline.execute()](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/CommandPipeline.kt) 收集外层输出。它使用固定参数数组，并提供固定环境、清除不必要的继承环境；内层的 `sh -c` 只执行 Kotlin 常量，不拼接 JS 输入。

## 6. 把文字变成页面里的“CSV 行数”

进程输出仍是一段文字。App 不能看到第四行是 `3` 就立即宣告成功，还要检查这段文字属于预期的环境。

[LinuxSession.runDemo()](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt#L103-L106) 中的检查是：

```kotlin
val parsed = parseDemo(result.stdout)
val valid = parsed != null && parsed.version == RootfsInstaller.ALPINE_VERSION &&
    parsed.uid == appUid && parsed.cwd == "/workspace" && parsed.exits.all { it == 0 }
val status = if (result.status == "completed" && !valid) "failed" else result.status
```

`parseDemo()` 把版本、cwd、UID、行数与内部退出码拆成结构化字段。外层进程成功但这些观察不符合约定，最终状态仍是 `failed`。

[LinuxModule.resultMap()](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxModule.kt#L106-L121) 将字段转成 TurboModule 可以返回的普通数据；React 等待的 Promise 随后得到这个结果。在 [LinuxPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/LinuxPanel.tsx#L45-L50) 中：

```ts
const next = await NativePocketRunLinux.runLinuxDemo(token);
if (generation === epoch.current) {
  if (next.token !== token || next.installationGeneration !== info?.installationGeneration) {
    throw new Error('Linux 运行结果已失效。');
  }
  setResult(next);
}
```

页面不仅匹配当前操作，还匹配准备时的安装版本。迟到的旧结果不能盖住新操作。真正显示行数的地方则很普通：[结果组件](https://github.com/kkkhs/pocketrun/blob/chapter-7/app/LinuxPanel.tsx#L105-L109)读取 `result.lineCount`，和日常 React 渲染业务数据一样。

到这里可以把整条链连起来了：**Linux 的 stdout → Kotlin 解析与校验 → 普通返回对象 → React state → 页面文字。** PRoot 进程、安装器和组件是不同职责，不是三个各自维护一份 CSV 的服务。

## 7. 结束时：收回临时资源，保留工作数据

每次运行有自己的临时 `/tmp` 和 RUN 目录。结束、取消或超时后，App 停止本次进程与 IO，确认没有活跃引用，再回收这一轮临时目录。完整安装和 `items.csv` 不属于本轮垃圾。

页面分开显示 `status` 与 `cleanupStatus`：

- 命令结果正确、退出码成功，说明读取完成。
- `cleanupStatus=done`，说明该清理路径获得了确认。
- `unproven`，说明清理不能确认；保留输出与资源，不用删除目录或清空 React state 冒充结束。

固定 CSV 通常很快读完。如果手动取消时提示“没有可取消的任务”，只说明已经结束，不能据此认定命中了取消场景。模块关闭、未知进程引用与死亡恢复的细节在下面另行解释。

## 8. 实践与进一步阅读

先完成章首的两次准备与读取，再回答：

1. `HOME=/root` 与 `id -u` 分别回答什么问题？为什么前者不能证明 root？
2. 如果 CSV 改成四个以换行结束的文本行，为什么不应再要求结果永远为 `3`？
3. 返回的行数正确但清理是 `unproven` 时，为什么不能直接删除 RUN 目录？

本章的固定任务仍是“启动 → 等结束 → 得到结果”。[下一章](chapter-8.md)会让同一 Linux shell 保持运行：你发一行输入，它执行并输出，然后还能继续接收下一行。为此需要真正的 PTY，而不是给普通管道换个名字。

<details>
<summary>深入：固定环境安装、数据归档与发布原子性</summary>

运行清单在 `app/android/app/src/main/assets/linux/runtime.json`，版本为 `alpine-3.22.8-minimal-proot-5.1.107.102-candidate2-prebound`，variant 为 `minimal-busybox-musl`。当前环境只有四个 ELF 与两个 guest 代码绑定，不是完整发行版候选的 19-ELF 范围。

当前 `linux/alpine-data.bin` 为 587 字节、34 个条目，SHA256 `8c4ac18ae5babcbd3f692e1c4bed6710eb965967da792fc6ad597def4a1eff06`。它是固定 gzip/ustar 数据归档，预置普通 0600、零字节的 `dev/null` 绑定占位，不是字符设备。历史正常演示的 570 字节资产属于另一快照，结果不能借给新资产。

`RootfsInstaller.verifyArtifacts()` 校验四个只读程序及 SHA256；`RootfsTar.extract()` 有界解压，只接受允许的数据、目录和链接，拒绝 ELF、可执行普通文件、重复路径、截断归档、特殊条目与路径逃逸。链接在普通文件写完后创建，并保证仍落在 rootfs 内。

安装先在本轮 stage 完成，同步安装清单，再通过 `AtomicSamplePublisher.publishDirectory()` 的 no-replace 发布。已有安装重新校验后复用，不把“目录存在”当成完成。`targetSdk` 保持 36，不通过降低 target、Android linker、memfd 或 RWX 内存绕过执行限制。

PRoot 启动参数还固定绑定本轮 `/tmp`、`/proc` 与 `/dev/null`；`PROOT_LOADER` 指向 APK loader，`PROOT_TMP_DIR` 指向本轮目录。脚本没有外部命令、环境、绑定路径、凭据或包管理入口。

</details>

<details>
<summary>深入：期限、取消与临时目录恢复</summary>

`LinuxResult.exitCodes` 是外层 PRoot 退出码，`stageExitCodes` 是内部五步退出码。运行沿用共享 5 秒截止时间与 64 KiB 输出预算，准备和每次实际启动也检查当前操作是否取消或过期。`token` 关联调用，`installationGeneration` 标识已校验版本，两者都不赋予新权限。

`LinuxOperationGate` 在 Promise 回调前释放当前操作槽；关闭使旧结果失效。UI 按钮禁用不是原生互斥和取消的替代。

`LinuxDirectoryLease` 是本地文件锁和目录所有权记录，不是云设备租约。记录包括类型、generation、UID、PID、启动时间与父目录身份。恢复先取得锁并校验记录，不能凭临时文件名删除。stage 和 RUN 的恢复条件不同；RUN 还检查 App UID 进程的 maps、cwd、FD 及活跃 PRoot/loader/guest。只有明确不存在才视为消失；权限、IO、损坏记录和超时都保守保留。

`cleanupResult()` 保留输出与退出码，独立标记 `done/unproven`。取消按本次 token 绑定操作，模块关闭后由原生队列收尾；硬死亡可能无法执行 finally，需要后续恢复确认。删除目录本身不能证明 guest、线程、FD 全部结束。

</details>

<details>
<summary>验收记录：本机正常流程、生命周期与独立章节包</summary>

2026-11-20，任务专用 `pocketrun-ch1-api36`（Android 16/API 36、ARM64、App UID 10150）验证了当前预置绑定点版本：

| 检查 | 实际结果 |
| --- | --- |
| 正常安装、目录映射与重复演示 | `Chapter7LinuxDemoTest`：`OK (1 test)`；Alpine 3.22.8、`/workspace`、三行与五阶段 0 |
| 取消、关闭、超时、安装与 RUN 库存 | `Chapter7LifecycleIntegrationTest`：`OK (12 tests)` |
| App/tracer/guest 硬死亡 | 默认与 fallback 各三场景，共 6 个；按 UID/PID/startTicks 选择自有对象，检查原生断言与 procfs 消失 |
| 安装发布前 App 死亡 | 同步后未发布 stage 保留，安装器恢复并保留 CSV，重跑成功 |
| 资源与数据 | 核对 FD、IO 线程、RUN 与原文件；无关 stage 未被当作垃圾删除 |

这一批 Debug APK SHA256 `58b62dda2ffb74f413164c152cfb01cd3b5da07e92d17ab71bd8fe9fcfe98b76`，Test APK SHA256 `6b96dbb4742acc58a6a36fec139e8f51815d905a6488b8cb2f2144fbfd52754f`。通过来自原生断言及对应进程证据，不是 host ADB 退出码。

2026-11-23，合入观察器修复后的独立第 7 章包再次完成正常演示、12 项生命周期与同样 7 个死亡恢复场景。Debug APK SHA256 `a1824358b31fa257f7087427465fdb33552198be574c39a146a246585abad674`；Test APK SHA256 `0e054a24e8c663c8da4c6cd77e458dc0ced795d6806c2d970610c448bc0ea7fa`。章首截图来自这份包。

这些结果只证明对应设备和构建的被测行为，不证明所有 Android/OEM、完整 Alpine 或全部异常分支兼容。

</details>

<details>
<summary>历史记录：2026-11-19 的批次与保留的原失败</summary>

在获授权的 Android 16/API 36、ARM64 云真机上，`Chapter7LinuxDemoTest.actualInstallationMappingAndRepeatedAlpinePipeline()` 执行安装、复用与两次演示：`OK (1 test)`、0.344 秒，两次都是版本 3.22.8、cwd `/workspace`、UID 10469、CSV 三行、五阶段及外层退出码 0、stderr 空。清理 `done` 由测试源码断言，原 JSON 没有单独打印这个字段；关闭后文件仍保留。

原 10 项/3 失败、11 项/2 失败保留。修复等待夹具重复打开 FIFO FD 后，新 12 项生命周期通过，但该历史包的 App 死亡后 rootfs 重用仍因不可读绑定占位失败；当时 587 字节预置版本、产品界面和公开发布尚待后续核验。之后的本机与公开结果见上一折叠区及文末，不改变这批失败。

原正常批次 App SHA256 前缀 `37ee35e17146`、Test `e7cdeb257046`；candidate2 正常批次 App `b74b47d7950c`、Test `462c18f18553`，其新生命周期为 11 项/2 失败。不同包、批次不拼成“一轮全过”。早期版本探测和 App UID 的 `id -u` 只证明启动链，不替代正常任务或生命周期。

固定 APK 包含四个 ELF 的清单校验和、许可证/版权索引；570 字节与 587 字节资产分别核对。安装退出 0 不是测试通过，也不生成或冒充 Case Center 原生报告。

</details>

<details>
<summary>实践：构建与运行独立正常演示检查</summary>

按[第 1 章](chapter-1.md)准备已有工具链。在仓库根目录执行：

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

仍在 `app/android/`，把 `AUTHORIZED_SERIAL` 设为自己已核对的演示设备。先确认系统、ABI、App/Test APK 校验和，再安装与测试，不使用默认设备：

```bash
adb -s "$AUTHORIZED_SERIAL" shell getprop ro.build.version.sdk
adb -s "$AUTHORIZED_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$AUTHORIZED_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$AUTHORIZED_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$AUTHORIZED_SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.Chapter7LinuxDemoTest \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
```

这只运行正常演示，不覆盖全部死亡与生命周期。硬死亡测试需要各自宿主协议和资源观察，不能无驱动运行。还需按待交付版本核对 JNI/ELF/16 KiB 对齐、许可证与对应源码；不能复用旧 APK 的设备结果。

网站在 `website/` 独立执行 `test:toolchain`、`typecheck`、`build`、`audit:ci`，再检查手机/桌面深浅主题、折叠内容和链接。网站能构建不证明 Linux 在设备上执行成功。

</details>

<details>
<summary>参考：程序来源、许可证与 Ditto 实物对照</summary>

PRoot 固定来源为 [termux/proot v5.1.107.102](https://github.com/termux/proot/tree/v5.1.107.102)，原 ZIP SHA256 `30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852`。对应修改、构建脚本和完整源码记录一并保留，不以上游未修改源码代替实际分发程序的对应源码。

Alpine 来源为[官方 3.22.8/aarch64 minirootfs](https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.8-aarch64.tar.gz)，SHA256 `36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4`。本例只分发最小派生内容，不能把原发行版的 16 个安装包数据库当成已经具备的功能。

匹配范围在 `app/third_party/linux/notice/MINIMAL-DISTRIBUTION.txt`、`LICENSE-SCOPE.txt`、`source-provenance.json` 和 `licenses/`：PRoot 为 GPLv2-or-later，与 LGPLv3-or-later 的 talloc 组合后的可执行文件按 GPLv3 条件分发；BusyBox 为 GPLv2-only，静态包含的 utmps/skalibs 另保留 ISC 版权与源码；musl 为 MIT 并保留第三方许可。Alpine 不是整体一个 MIT 许可证。

APK 包含 17 份许可证/版权文本与索引；源码 Release 提供版本匹配的对应源码、修改、构建及复现说明，入口是 `app/third_party/linux/matching_sources/`，公开下载与校验和已核对。最小环境不包含 apk/libapk、ssl_client、OpenSSL 等完整候选程序；范围减小不免除剩余程序的分发义务。

对 Ditto 0.1.14 APK 的部分重建方法所作实物对照表明，它也使用 `nativeLibraryDir` 下的 PRoot/loader 和 Termux 风格 JNI/PTY 路径。这不是对原始 App 源码、设备真实 UID、所有降级或取消行为的完整核验。其 APK 是 Alpine 3.24.3、515 个归档条目并包含 `apk`，与本例最小范围不同。

JADX 1.5.1 重建有 32 个错误，缺失方法不能补猜。[ditto-termux-packages](https://github.com/WindySha/ditto-termux-packages) 尚未核为整个 App 原始源码或该 APK 的对应版本；PocketRun 选择 React Native/TurboModule，也不能反推 Ditto 使用同样框架。实物对照不扩大任何历史设备批次的结论。

</details>

## 本章源码

本文小段代码固定对照已发布的 `chapter-7`，不是把当前主树的后续改动倒算进旧快照。

- [PR #12：PRoot 与最小 Alpine 环境](https://github.com/kkkhs/pocketrun/pull/12)
- [合并提交 `2c49a78`](https://github.com/kkkhs/pocketrun/tree/2c49a78e7e26f5f3edd711ab66f7c1d5e577a91a)
- [chapter-7 源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7)：完整归档、SHA256SUMS、第三方对应源码与复现说明，公开下载已核验。

本章 main CI、Pages、注释 tag 与源码公开交付均已完成；Release 提供源码，不提供公开调试签名 APK。
