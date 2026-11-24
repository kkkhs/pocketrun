---
sidebar_position: 7
sidebar_label: 第 6 章 · 工作目录与命令管道
title: 在私有工作目录里数出 CSV 的行数
description: 沿一次读取 CSV 的任务，理解 App 私有文件、子进程、字节管道和返回 React 的结果。
---

import WorkspacePipelineFlow from './assets/workspace-pipeline-flow.svg';
import workspaceResult from './assets/chapter-6-workspace-result.png';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

[上一章](chapter-5.md)，我们通过截图和 UI 树找到输入框，再发送输入、重新观察。那条通道解决的是“操作一个页面”。

现在换一个任务：**读取 PocketRun 私有目录里的 `items.csv`，数出包含表头的行数，并把结果显示在页面上。** 文件不需要先显示在屏幕上，App 可以直接启动读取它的程序。

第一次准备的文件是：

```csv
name,quantity
apple,2
banana,3
```

我们沿这条顺序走一遍：

```text
准备文件 → 点击运行 → 启动 cat 和 wc → 传递文件内容
        → 收集行数与两个退出码 → 把结果交回 React
```

本章只增加**文件与命令通道**。选择什么任务仍由用户点按钮决定；下一章才把命令环境换成 Linux 用户态工具。

## 1. 先在界面跑一遍

使用[第 6 章源码快照](https://github.com/kkkhs/pocketrun/tree/chapter-6/app)构建 App，在自己拥有或明确获授权的演示设备上打开“第 6 章 · 工作目录与命令管道”。

1. 点“初始化工作目录”：看到私有目录、`items.csv` 和“首次写入演示 CSV”。
2. 点“运行演示管道”：看到 `completed`、stdout 为 `3`、stderr 为空、退出码为 `0, 0`。
3. 再点初始化：应提示“保留已有演示文件，没有覆盖”。

<figure style={{margin: '1.25rem 0', maxWidth: 400}}>
  <img src={workspaceResult} alt="实际 App 的私有目录、行数 3 与两个成功退出码" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>先准备私有文件，再运行固定管道，检查两个命令的结果。</figcaption>
</figure>

**`3` 是三行文本，不是三条商品，也不是数量之和。** `wc -l` 数的是换行符；本例是一行表头、两行商品，最后一行也有换行。

截图来自实际初始化和运行。正常流程已在授权设备上完成；取消、超时和进程死亡有另外的测试记录，不能只凭这张图推断它们都通过。

## 2. 谁在执行：App 和两个子进程

这次仍有 React 和 Kotlin，但没有调用第 3—5 章的 UID 2000 后端。

| 执行者 | 在这次任务里负责什么 |
| --- | --- |
| React / TypeScript，同一 App 进程 | 按钮、等待 Promise、显示结果 |
| App Kotlin，同一 App 进程 | 准备文件、选定命令、启动进程、搬运和收集字节 |
| `cat`、`wc` 两个子进程 | 一个读文件，一个数换行；两者仍是普通 App UID |

**方法调用和启动进程不同。** `WorkspaceSession.runDemo()` 是 App 内的一次 Kotlin 调用；`ProcessBuilder.start()` 才会创建新的进程。新进程继承 App 的执行身份，不因为程序位于 `/system/bin` 就变成 shell 或 root。

从前端经验衔接：你可以把 TurboModule 看成 TypeScript 调用平台能力的接口。它在 App 内跨语言；本章的数据不经过电脑，也不经过 Binder。

## 3. 准备文件：给命令一个明确的工作目录

先看按钮如何进入原生层。[WorkspacePanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/WorkspacePanel.tsx) 的 `perform('prepare')` 中，真正的调用是：

```ts
const next = await NativePocketRunWorkspace.prepareWorkspace();
if (epoch === generation.current) { setInfo(next); }
```

`next` 是目录信息，不是文件内容。`setInfo()` 让 React 展示路径和是否首次创建；`epoch` 检查让已经失效的页面操作不能用晚返回覆盖当前状态。

原生入口 [WorkspaceModule.kt](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/java/io/github/kkkhs/pocketrun/WorkspaceModule.kt) 把 Android 提供的私有目录交给会话：

```kotlin
private val session = WorkspaceSession(context.filesDir)
```

[WorkspaceSession.kt](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/java/io/github/kkkhs/pocketrun/WorkspaceSession.kt) 在这个目录下选择固定位置：

```kotlin
private val directory = File(File(filesDirectory.canonicalFile, "workspaces"), "demo")
private val sample = File(directory, "items.csv")
```

所以命令的工作目录是 App 私有区域里的 `workspaces/demo`，不是电脑当前目录，也不是外部存储。它只允许所属 UID 读写和进入。

准备时，已有文件和首次写入分开处理：

```kotlin
val created = if (sample.exists()) false else publishSample()
validateSample()
Info(directory.path, sample.path, created)
```

再次初始化不会把读者改过的 CSV 恢复成三行样本。`validateSample()` 检查它仍是预期位置的普通文件、没有符号链接逃逸且不超过 64 KiB；不能把“路径存在”直接当成可以执行任务。

首次创建也不能留下半份文件：先写同目录临时文件、同步内容，再以**不替换已有目标**的方式发布。即使另一个操作刚刚创建了 `items.csv`，也保留它。具体原子发布机制放在文末。

## 4. 点击运行：启动两个固定命令

“运行演示管道”进入同一个页面的 `perform('run')`：

```ts
const next = await NativePocketRunWorkspace.runDemoPipeline();
if (epoch === generation.current) { setResult(next); }
```

这次 Promise 返回的是运行结果。React 没有把命令或文件路径传给 Kotlin；[NativePocketRunWorkspace.ts](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/specs/NativePocketRunWorkspace.ts) 只开放固定操作。

为什么要用两个命令？`cat` 负责读取文件，`wc -l` 负责统计输入中的换行。本例不是要选最短的数行命令，而是用一份小文件看清**一个程序的输出怎样成为另一个程序的输入**。

命令由 `WorkspaceSession` 固定选择：

```kotlin
private val DEMO_COMMANDS = listOf(
    listOf("/system/bin/toybox", "cat", "items.csv"),
    listOf("/system/bin/toybox", "wc", "-l"),
)
```

toybox 是 Android 提供的一组命令工具。这里启动两次：第一次选择 `cat`，第二次选择 `wc`。这是参数数组，不是交给 `sh -c` 解释的 shell 源码。

运行方法把目录和参数交给 [CommandPipeline.kt](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/java/io/github/kkkhs/pocketrun/CommandPipeline.kt)：

```kotlin
fun runDemo(run: CommandPipeline.Run) =
    pipeline.execute(run, directory, commands, validate = ::validateSample)
```

在第 6 章快照中，创建子进程的核心一行是：

```kotlin
run.processes += ProcessBuilder(command).directory(directory).start()
```

`.directory(directory)` 很重要：`items.csv` 是相对路径，它从这里解析。两个命令都使用这个目录；不能依赖一个碰巧正确的默认目录。

## 5. 传递内容：字节怎样从 cat 到 wc

先看数据路径。**图里的箭头表示内容流转，不表示创建进程的先后顺序**；两个进程都启动后，App 才把它们连接起来。

<figure style={{margin: '1.25rem 0', maxWidth: 560}}>
  <WorkspacePipelineFlow className={`pr-pipe-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-pipe-title" aria-describedby="pr-pipe-desc" style={{display: 'block', width: '100%', height: 'auto'}} />
  <figcaption>CSV 内容经过管道；最后回到页面的只是行数、状态和退出码。</figcaption>
</figure>

进程通常有三个字节通道：标准输入 stdin、标准输出 stdout、错误输出 stderr。Java 的名字站在 **App 的视角**：

- **给进程输入：** 子进程的 stdin，对应 App 的 `process.outputStream`。App 往这里写字节。
- **读进程输出：** 子进程的 stdout，对应 App 的 `process.inputStream`。App 从这里读结果。
- **读进程错误：** 子进程的 stderr，对应 App 的 `process.errorStream`。App 从这里读错误输出。

因此，连接两个进程的实际代码是：

```kotlin
run.processes[1].outputStream.use { destination ->
    copyLimited(run.processes[0].inputStream, destination)
}
```

`processes[0]` 是 `cat`，`processes[1]` 是 `wc`。`copyLimited()` 分块读取 CSV 字节、写给 `wc`，不需要先把文件变成 React 字符串再传回来。

**传完后必须关闭 wc 的输入。** `use` 在这段搬运结束时关闭 `destination`，`wc` 于是读到 EOF——“不会再有数据了”，才能输出最终行数。只是不再写字节，却保持输入打开，会让它继续等待。

与此同时，Kotlin 读取最后一个进程的 stdout，以及每个进程的 stderr。它不会先阻塞等待进程退出、等结束后才读取：缓冲区如果塞满，程序可能在写输出时等待 App，App 又在等待程序结束，双方都会卡住。

## 6. 接回结果：Promise 返回后页面显示什么

字节传完不等于任务已成功。Kotlin 还要等两个命令结束，检查各自退出码。不能只看最后一个 `wc` 返回 `0`，否则可能漏掉前面的文件读取失败。

原生模块把内部结果转换成 TypeScript 能接收的普通数据。下面摘取 `WorkspaceModule.runDemoPipeline()` 中的结果字段：

```kotlin
putString("status", result.status)
putString("stdout", result.stdout)
putString("stderr", result.stderr)
putArray("exitCodes", codes)
```

worker 完成后结算原生 Promise；前面的 `await` 得到 `next`，`setResult(next)` 再触发 React 渲染。[WorkspacePanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/WorkspacePanel.tsx) 的实际结果行是：

```tsx
<Text style={{ color: colors.text }}>状态：{result.status}</Text>
<Text selectable style={{ color: colors.text }}>stdout：{result.stdout.trim()}</Text>
<Text selectable style={{ color: colors.text }}>stderr：{result.stderr || '(empty)'}</Text>
<Text style={{ color: colors.text }}>退出码：{result.exitCodes.join(', ')}</Text>
```

到这里，完整返回路径才接上：

```text
wc 输出字节 → Kotlin 收集字符串与退出码 → Module 结算 Promise
           → TypeScript 的 await 得到结果 → setResult → 页面重绘
```

核对本例的三个问题：**是否正常结束、行数是否为 3、两个退出码是否都为 0**。stderr 单独展示；本例正常时为空，但不能给所有未来命令规定“stderr 有文字就一定失败”。

## 7. 结束与取消：进程收回来，文件留下来

这次命令默认共用 5 秒执行期限，中间数据、stdout 和 stderr 各有 64 KiB 预算。取消、超时、输出超限和正常结束都会进入原生收尾，而不是只把 React 的“运行中”文字隐藏。

运行前 `WorkspaceModule.runDemoPipeline()` 就调用 `session.reserve()` 预留本次操作。最终进入 `CommandPipeline.reserve()`：

```kotlin
fun reserve(): Run = synchronized(lock) {
    check(!closed) { "命令管道已关闭。" }
    check(current == null) { "已有命令管道正在运行。" }
    Run().also { current = it }
}
```

`current` 先指向本轮 `Run`，原生 worker 才开始执行。这样即使用户在子进程真正启动前取消，标记仍属于同一次运行，不会在稍后启动时被清除。

页面的取消请求是：

```ts
const accepted = await NativePocketRunWorkspace.cancelPipeline();
```

它表示是否找到本次可取消运行，**不是两个进程已经退出的最终结果**。页面继续等待原来的 `runDemoPipeline()` Promise。固定三行文件通常很快读完，来不及取消时应显示已无运行中的任务；这不证明测到了取消效果。

重新初始化保留 `items.csv`；关闭的是本轮进程和流，不是删除工作数据。模块失效时也要关闭原生会话。React 用代际忽略晚返回，只保护页面状态，不能代替原生清理。

## 8. 小结：同一任务的三张地图

- **谁执行：** React 和 Kotlin 在同一个 App 进程；`cat`、`wc` 是同 UID 的两个子进程。
- **按什么顺序：** 准备文件、点击运行、启动命令、等待数据与退出、展示结果。
- **数据怎么回来：** 文件字节进 `cat → wc`，行数与退出码经 Kotlin Promise 变成 React state。

现在我们具备了一个固定文件任务。下一章继续读取同一份 CSV，只把 Android toybox 换成 PRoot / Alpine 的 Linux 用户态工具；它仍不会增加 App 的权限。

练习：如果 `wc` 没有输出、两个进程又一直不退出，你会先检查“文件有没有三行”，还是检查 App 有没有在搬运结束后关闭 `wc` 的输入？为什么？

## 附录：深入机制与验证记录

正文代码链接固定到 `chapter-6`。后续章节对共享管道的清理逻辑有改进；这里的历史设备结果只属于对应批次，不借给后来重新构建的 APK。

<details>
<summary>首次文件发布：原子改名与中断恢复</summary>

`WorkspaceSession.publishSample()` 先创建 `.pocketrun-sample-*.tmp`、设置私有权限、写入完整 CSV，再调用 `output.fd.sync()`。Android 路径是 `AtomicSamplePublisher.publish()` → `renameNoReplace()` → `pocketrun_fs.cpp` 中的 JNI；它使用 `renameat2(RENAME_NOREPLACE)`。成功才产生目标，`EEXIST` 保留已有目标，其他错误明确失败，不降级为覆盖式改名。

早期 Android 实现调用 `Files.createLink()` 曾返回 `AccessDeniedException`；这是旧实现失败记录。非 Android JVM 分支用硬链接检查相同的 no-replace 约定，不替代 Android JNI 验证。

App 在同步后、发布前被 `SIGKILL` 时，`finally` 不会执行。下次 `prepare()` 只清理演示目录中符合内部临时命名且没有符号链接的普通残留文件，再准备完整样本，已有 CSV 不覆盖。这不证明突然断电后的持久性。

</details>

<details>
<summary>输出、期限、取消与资源观察的细节</summary>

第 6 章的 `CommandPipeline` 最多两个阶段，每阶段参数最多八项；中间传输、stdout 和合并 stderr 各限 64 KiB。超限返回 `output_limit`，不能把截断结果写成 `completed`。

返回的是文字，不是无损二进制。无效 UTF-8 转成替换字符后可能膨胀，因此 `LimitedOutput.text()` 再检查编码后的长度，在字符边界截断；实际二进制 stdout/stderr 用例均返回 65535 UTF-8 字节，没有超过 65536 字节预算。

执行期限共享 5 秒，不是每命令五秒。每次启动前检查当前运行、取消与期限；清理的有界等待是另外一段时间，不承诺所有资源在第 5 秒瞬间消失。状态包括 `completed`、`failed`、`timeout`、`cancelled`、`output_limit`；忙碌、关闭或初始化失败也可能直接拒绝 Promise。

清理会结束本轮持有进程、关闭标准流、停止 IO worker。真实回收须观察 PID、FD 和线程，不能由 `close()` 调用次数推断。第 6 章返回接口没有独立的清理确认字段，不把后续章节的 `cleanupStatus` 写进本章快照。

`Chapter6HostDeathTest` 依赖按方法执行的宿主协议：核对设备、UID、APK、PID/启动时间，在 fixture 就绪阶段对自有目标发 `SIGKILL`，然后独立恢复。自然退出、`force-stop` 或 `/proc` 读取失败不替代硬死亡证据。固定 `sh -c` 只用于测试重定向与 PID 记录，不是产品对 JS 开放的命令接口。

</details>

<details>
<summary>实际设备与发布记录：批次分别核对</summary>

2026-11-18—19，在获授权的 Android 16 / API 36、ARM64 云真机留下以下记录。界面截图与原生专项分别记录，不是 Case Center 原生报告。

| 验证范围 | 已记录结果 |
| --- | --- |
| 私有目录、真实 UID、固定管道、退出码、路径拒绝、取消、超时、输出上限与重跑 | 生命周期 `OK (11 tests)`，5.63 秒 |
| 无效 UTF-8 的 stdout / stderr | 均为 `output_limit`，返回文字各 65535 UTF-8 字节 |
| 重复运行后的资源 | 同一测试进程 FD 105 → 105，`PocketRun-PipeIO` 存活线程 0；不是总 FD 为 0 |
| App 被 `SIGKILL` 后恢复 | 原 App/子进程消失；独立恢复 `OK (1 test)`，文件保留并重跑成功 |
| 同步文件后、发布前 App 被杀 | 恢复 `OK (1 test)`，确认完整临时文件和无目标，再初始化并运行 |
| 宿主杀死本轮子进程 | 独立原生 `OK (1 test)`，检查失败、子进程消失与重跑；未观察父进程 FD/IO，不能称父进程 FD 清零 |
| 实际界面 | 初始化、运行、`completed / 3 / empty / 0,0`，再次初始化保留文件；不覆盖所有界面异常分支 |
| 发布 | [PR #11](https://github.com/kkkhs/pocketrun/pull/11) 合并；[main Android CI](https://github.com/kkkhs/pocketrun/actions/runs/44534812395)、[Pages](https://github.com/kkkhs/pocketrun/actions/runs/44534812843)、线上教程、注释 tag 与源码 Release 完成 |

这批 App APK SHA256 前缀为 `b3cad89d3347`。生命周期及最后宿主子进程死亡测试的 Test APK 前缀为 `ce4ed7cd3c6f`；App/首次发布死亡恢复使用较早的 `350106fb0f50`。两组测试包不描述为“一轮全过”；arm 阶段按设计被杀，结论来自真实死亡观察及随后独立恢复，不来自 ADB 退出 0。旧硬链接失败保留为失败。

原始设备绑定、runner、PID 与文件记录保留在私有验收目录，不公开连接信息、token 或完整日志。JVM 与 Jest 检查逻辑和状态，不替代 Android UID、文件系统、JNI、死亡观察，也不证明所有 OEM 支持。

</details>

<details>
<summary>实践：构建与运行生命周期检查</summary>

按[第 1 章](chapter-1.md)准备完整 JDK 17、仓库指定 Node 与已有依赖，从仓库根目录执行：

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

`AUTHORIZED_SERIAL` 必须是自己拥有或明确授权且已核对的设备。仍在 `app/android/`：

```bash
adb -s "$AUTHORIZED_SERIAL" shell getprop ro.build.version.sdk
adb -s "$AUTHORIZED_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$AUTHORIZED_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$AUTHORIZED_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$AUTHORIZED_SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.Chapter6WorkspaceIntegrationTest \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
```

先核对系统、ABI、App/Test SHA 与安装目标；不用默认设备。fixture 只管理自己创建的数据，不删除产品工作目录。检查 runner 的 `OK`、数量、无失败/跳过，而非只检查宿主退出码；死亡测试另需宿主协议，不直接运行整个类。

调试包配置在 `app/android/app/build.gradle` 中打入 JS bundle，云设备不用连开发机 Metro。安装成功不等于 App 可运行，仍需实际按钮与结果观察。

网站在 `website/` 独立运行 `test:toolchain`、`typecheck`、`build`、`audit:ci` 并检查手机/桌面深浅主题；文档改写不代表重新做了设备验收。

</details>

## 本章源码

- [PR #11：私有工作目录与命令管道](https://github.com/kkkhs/pocketrun/pull/11)
- [chapter-6 源码 Release](https://github.com/kkkhs/pocketrun/releases/tag/chapter-6)：完整源码归档与 SHA256 附件，不发布调试签名 APK。
- [固定提交 `90d9d4f`](https://github.com/kkkhs/pocketrun/commit/90d9d4f009066cd5de22f20c87fa963530d681c6)：正文链接对应的本章快照。
- [AtomicSamplePublisher.kt](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/java/io/github/kkkhs/pocketrun/AtomicSamplePublisher.kt) 与 [pocketrun_fs.cpp](https://github.com/kkkhs/pocketrun/blob/chapter-6/app/android/app/src/main/cpp/pocketrun_fs.cpp)：首次文件原子发布。

[线上第 6 章](https://kkkhs.github.io/pocketrun/chapter-6/)已发布。代码与设备记录均以本章固定版本及各自批次为准。
