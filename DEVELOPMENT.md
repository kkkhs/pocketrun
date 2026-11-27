# PocketRun 开发与交接说明

这份文档面向继续开发项目的人或 agent，记录实现入口、工作方法、验证/发布流程和交接要求。项目目标与历次决定见 [`PROJECT_RECORD.md`](PROJECT_RECORD.md)，当前功能差距见 [Ditto 对照](website/docs/ditto-parity.md)，读者教程从[导读](website/docs/intro.md)开始。

**基线日期：2026-11-26。** 工具链和命令已按当前源码核对；未来版本以对应配置文件为准，改动时同时更新这里。

## 1. 开始前：确认版本和工作范围

先判断本次是在开发当前 main，还是复现某一章的历史快照。**分支名叫 main，不代表本地文件已是远端最新；chapter tag 则用于历史复现。**

在仓库根目录检查：

```bash
git status --short --branch
git branch --show-current
git log -1 --oneline
git fetch origin
git rev-list --left-right --count HEAD...origin/main
```

工作树没有待保留修改、且当前 main 能快进时才更新：

```bash
git switch main
git merge --ff-only origin/main
```

有未提交修改、分叉或外部工作正在运行时，先说明实际状态并保护成果；不使用 hard reset、强推或删除 stash 来“变干净”。修改在明确的新分支进行，例如：

```bash
git switch -c docs/example-update
```

- 同一文件只分配一个 writer；独审只读，不抢同一文件。
- 不为小任务拆出大量 agent；必要时一个范围明确的 worker，再按风险做独审。
- 先读任务和实际调用链，再复用已有能力；修改共享函数时检查所有调用者，修根因而非逐入口补丁。
- 本项目 App 和网站有独立 npm 项目，仓库根目录没有 package.json；不要在根目录执行 npm install/ci。
- 不修改无关工作副本，不混入他人未提交改动；不因文档修改重跑全部设备/模型验收。

## 2. 代码入口与调用链

### React 到 App Kotlin

- [`app/App.tsx`](app/App.tsx)：章节面板。
- [`app/specs/`](app/specs/)：TurboModule 的 TypeScript 调用约定。
- [`DeviceInfoPackage.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/DeviceInfoPackage.kt)：原生模块注册。
- [`AdbModule.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/AdbModule.kt)、[`WorkspaceModule.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/WorkspaceModule.kt)、[`LinuxModule.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxModule.kt)、[`PtyModule.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/PtyModule.kt)、[`TaskHostModule.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/TaskHostModule.kt)：对应原生入口。

React 与上述 Kotlin 在同一个普通 App 进程。原生方法、线程、类和独立后端不是同一概念。

### Android 显示通道

```text
AdbPanel / BackendPanel / DisplayPanel
  → NativePocketRunAdb → AdbModule
  → PrivilegedBackendController
  → typed Binder → PrivilegedBackendMain（独立 UID 2000）
  → VirtualDisplaySession / DisplayAutomation / DisplayInput
```

- [`BackendLaunchCommand.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/BackendLaunchCommand.kt)：固定 app_process 启动。
- [`IPrivilegedBackend.aidl`](app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl)：明确 Binder 方法。
- [`PrivilegedBackendController.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt)：App 侧连接、结果处理、死亡与失效。
- [`VirtualDisplaySession.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/VirtualDisplaySession.kt)：后端显示、ImageReader 和截图资源。
- [`DisplayAutomation.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayAutomation.kt)、[`DisplayInput.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayInput.kt)：目标显示上的树与输入。

ADB 是启动渠道，不是每动作的数据通道。launch、截图、树、输入必须是同一有效正 displayId；不增加主屏 fallback。App instrumentation 的 UiAutomation 与 shell 后端观察器身份和生命周期不同，不把一方的 hidden API/线程收尾方式套给另一方。

### 文件与 Linux 通道

```text
WorkspacePanel / LinuxPanel
  → WorkspaceModule / LinuxModule
  → WorkspaceSession / LinuxSession
  → CommandPipeline → ProcessBuilder 子进程
```

- [`WorkspaceSession.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/WorkspaceSession.kt)：持久演示文件、不覆盖发布。
- [`CommandPipeline.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/CommandPipeline.kt)：字节管道、输出/退出/期限/清理。
- [`LinuxSession.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxSession.kt)：固定 PRoot 参数、环境、绑定与结果解析。
- [`runtime.json`](app/android/app/src/main/assets/linux/runtime.json)：实际程序和数据资产清单。
- [`RootfsInstaller.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/RootfsInstaller.kt)、[`LinuxDirectoryLease.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/LinuxDirectoryLease.kt)：校验安装与目录所有权。

Android 已使用 Linux 内核；Alpine 是另一组用户态程序/库/目录，PRoot 做运行和路径兼容，不增添 UID 权限。当前最小分发不是最终完整功能范围，但扩展时仍须遵守 Android 可执行文件限制和对应源码/许可要求。

### 交互终端

```text
PtyPanel → PtyModule → PtySession → PtyNative
  → pocketrun_pty.cpp → /dev/ptmx 与固定 PRoot/BusyBox shell
```

[`PtySession.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/PtySession.kt)、[`PtyNative.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/PtyNative.kt)、[`pocketrun_pty.cpp`](app/android/app/src/main/cpp/pocketrun_pty.cpp) 管理实际终端与句柄。`startPty()` 的 Promise 在会话结束后返回，运行中由 `getPtyOutput()` 查询累计快照；不能把每次快照再追加一次。stdin/stdout/stderr 连到同一 slave，App 从 master 读合流输出。

### 工具、账本和模型

```text
TaskHostPanel / SingleTaskPanel / AgentLoopPanel
  → TaskHostModule
  → TaskHost / SingleTaskContext / AgentLoop
  → AndroidTaskCapabilities / BinderTaskHostDisplayPort
  → 原 LinuxSession 或 Binder 显示通道
```

- [`TaskHost.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskHost.kt)：请求校验、目标绑定、手机决定、实际 owner 与清理。
- [`SingleTaskContext.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/SingleTaskContext.kt)：有界单任务账本。
- [`AgentLoop.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AgentLoop.kt)：模型提议/工具结果循环，不是第二套执行器。
- [`AndroidTaskCapabilities.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AndroidTaskCapabilities.kt)、[`BinderTaskHostDisplayPort.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/BinderTaskHostDisplayPort.kt)：原能力适配。
- [`ModelToolSurface.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ModelToolSurface.kt)：模型工具白名单及允许发送的结果摘要。
- [`AnthropicModelTransport.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/AnthropicModelTransport.kt)、[`ModelCredentialStore.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/ModelCredentialStore.kt)：官方 SDK 与 Keystore 配置。

模型推理在 endpoint 服务；Kotlin 安排回合、校验并调用工具。`submitTool()` 返回请求快照，手机决定是独立调用，状态查询返回当前结果；原生执行不由前端是否 await 决定。全回合先检查工具名/参数结构/ID，目标有效性仍逐条核对，成功步骤不回滚。

### 用户记忆（第 12 章）

[`MemoryPanel.tsx`](app/MemoryPanel.tsx) → TaskHostModule → [`MemoryStore.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/MemoryStore.kt)。[`MemoryCodec.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/MemoryCodec.kt) 复用严格平面JSON，8条/每条256 UTF-8字节、整体4096编码字节；一次最多选择4条/2048编码字节。

配置与记忆复用 [`EncryptedSnapshotFile.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/EncryptedSnapshotFile.kt) 的原子密文机制，但文件、schema、别名和AAD分开。首次缺失创建稳定空快照；腐败/未知不当空库，显式重置只针对记忆。每次有效修改写新不透明版本，最后一条删除也不复用旧身份。

旧 `startAgent(goal)` 不带记忆；新入口明确绑定所选IDs、预期版本、endpoint和model。用户确认的数据进入初始user消息，后续作为历史回放，不进入授权。编辑/删除先取消旧循环并释放其本地上下文，不能撤回provider收到的数据或已提交副作用；同进程unproven owner仍保槽。提交后的读回错误是未确认，不保证回滚，UI必须核对新状态后再关闭/重启。

### 完整任务与运行事实（第 13 章）

[`CompleteTaskPanel.tsx`](app/CompleteTaskPanel.tsx) → `startCompleteAgent()` / `beginCompleteTask()` → `SingleTaskContext.beginComplete()` → 原 `TaskHost`。开始绑定预期 journal run、当前 displayId/epoch、模型目标，创建新的 run 和原生契约；自然语言、模型参数和旧记录不能切换执行模式。

[`CompleteTaskContract.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/CompleteTaskContract.kt) 管理 CSV 读取、获准 Demo 观察、真实输入、再观察、无覆盖发布和最终核验的阶段；手动 `finishTask()` 与模型 `end_turn` 都走同一完成门。最终 Demo/输出读取之后再次检查输入摘要，防止长观察期间输入已改变。普通模型任务保留原工具与元数据披露范围。

文件操作复用 WorkspaceSession 和 AtomicSamplePublisher，使用普通 App UID，不启动另一套 Linux 摘要执行器。`observe_demo` 复用 typed Binder，只披露自有 Demo 的两个必要节点；新读取/发布工具和观察都需原手机审批。已准确存在的输入/文件只核对，不重复输入或声称新写入。

[`TaskJournal.kt`](app/android/app/src/main/java/io/github/kkkhs/pocketrun/taskhost/TaskJournal.kt) 使用独立加密 AtomicFile `complete-task.bin`，保存最新 run、目标、摘要、确认阶段、尚未确认的意图及终态。模块首次加载把在途记录标成 interrupted；重启后不恢复 nonce、模型完整历史或句柄。继续前重新核对当前资源和文件，创建新任务；部分输入、不同输出、改变的输入、遗留临时文件或未知读取保留并阻断。同进程 unproven owner 不由 journal 重置释放。

## 3. 工具链与环境

| 项目 | 当前要求 | 配置来源 |
| --- | --- | --- |
| Node / npm | Node 24.18.0；App/网站各自 npm ci | [`.nvmrc`](.nvmrc)、[App package.json](app/package.json)、[网站 package.json](website/package.json) |
| App UI | React 19.3.0、RN 0.88.1、CLI 20.2.0 | App package.json/lock |
| 网站 | Docusaurus 3.10.2 | 网站 package.json/lock |
| Java / Gradle | 完整 JDK 17、Gradle 9.4.1 | Android 配置、wrapper、CI |
| Android SDK | compileSdk 37、targetSdk 36、minSdk 24、Build Tools 37.0.0 | [`app/android/build.gradle`](app/android/build.gradle) |
| native | NDK 27.1.12297006、CMake 3.22.1 | Android/CMake 配置 |
| Linux/PTY 实践 | API 34+、ARM64、64 位 App；既有专用验收基线 API 36 ARM64 | 原生运行检查与各章附录 |
| 模型 | 原生 runtime API 26+；Linux 工具仍受上述条件约束；官方 Java SDK 3.11.0 | App Gradle/lock 和模型模块 |

设置 `JAVA_HOME` 为完整 JDK、`ANDROID_HOME` 为实际 SDK；不是只安装 JRE。默认 Gradle 属性列多种 ABI，当前 Linux 及独立章节验收显式用 `-PreactNativeArchitectures=arm64-v8a`。

## 4. 开发与验证命令

### App 与 Android

在仓库根目录开始：

```bash
cd app
npm ci --registry=https://registry.npmjs.org
npm run typecheck
npm run lint
npm test -- --runInBand
npm run audit:ci
cd android
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2 \
  --no-daemon --console=plain
```

这与 [Android CI](.github/workflows/app.yml) 的核心检查一致。部分 Linux JVM 测试直接依赖 `/proc/self/stat`；在 macOS 上不能用关闭这些校验或忽略失败来声称全量通过。可移植测试与 Linux 专项分别记录，完整 Linux 回归由对应环境或 Ubuntu CI 核对。

当前 Debug 包内置 JS bundle，不要求 Metro；安装、启动和需要 Metro 的其他开发配置见 [App README](app/README.md#运行)。`assembleRelease` 当前仍使用 debug 签名，构建成功不等于可公开发布的正式签名 APK。

### 网站

另从仓库根目录开始：

```bash
cd website
npm ci --registry=https://registry.npmjs.org
npm run test:toolchain
npm run typecheck
DOCUSAURUS_NO_PERSISTENT_CACHE=1 npm run build
npm run audit:ci
npm run serve -- --host 127.0.0.1 --port 3000 --no-open
```

网站没有独立 lint/test 脚本；`test:toolchain` 检查实际构建兼容。断链、断锚和坏 Markdown 图片在构建中按错误处理；仍要看实际页面，编译不证明图解布局和教学内容正确。

### 门禁不等于验收

- Jest/mock 验证 UI 状态和接口，不是 Android/模型成功。
- JVM 验证核心逻辑或本环境真实进程，不替代 Android UID、Binder、JNI 和 procfs。
- Android 集成、实际 App 操作、模型网络与资源观察各有证明范围，不能跨批次合成“一轮全过”。
- [`lint-staged.config.cjs`](lint-staged.config.cjs) 配置 App lint/types/Jest、网站 toolchain/types/build，但不包含审计或 Android 构建。仓库没有跟踪的 `.husky` 配置，不假定新 clone 自动启用本机提交 hook；CI 和明确执行的检查是必需记录。
- [`audit-ci.cjs`](scripts/audit-ci.cjs) 只允许既有精确 picomatch 临时例外，截止 2026-12-18T00:00:00Z。其他 high/critical、例外变化、到期、非 JSON 或网络错误均阻断；通过不等于没有 moderate 问题。

## 5. 设备、模型与测试资源边界

仅使用自己拥有或明确授权的专用演示设备。既有验收使用过任务专用 API 36 ARM64 AVD；历史 serial/boot/PID 不是新会话的授权，操作前重新核对。

- 每条 ADB 都显式 `-s "$SERIAL"`，不使用默认设备。先确认所有权、系统、ABI、App/Test SHA，再安装或输入。
- 不操作个人实体手机，不绕过系统配对、设备管理或调试期限。配对码只用于当次流程，不存日志、源码或录屏。
- 不把主机 ADB 代启动后端描述为 App 自有启动成功；实际产品验证走按钮、JS、Codegen、原生和系统能力。
- HostDeath 类需要各自宿主协议和排他设备窗口，不直接作为普通全类 instrumentation 运行。默认排除不代表这些专项已被普通测试覆盖。
- UiAutomation 观察器有冲突风险；后端查询目标显示时不并行抢占另一个 instrumentation 观察器。后端持久连接建立后，UI 驱动也不再用另一条 `uiautomator dump` 接入；使用任务专用截图与离线识别，核对当前步骤再点击。层级读取失败不表示模型任务已经停止。
- 测试 mock/暂停夹具限定于测试范围，不能生成工具结果、替代手机批准或进入正常 Release；本次临时规则由本次清理。
- 进程/FD 身份或 procfs 读取未知不当作不存在。清理失败保留原 owner，不对可能复用的 PID/FD 重试危险操作。

模型只使用合法、明确可调用的 endpoint 和配置。Release HTTPS-only；Debug 额外只允许固定 `http://127.0.0.1:8790`。已有网关不擅自重启、改配置或另开公开端口；宿主认证留在宿主，不复制 CLI/OAuth 凭据进 Android。真实服务错误不记录原始正文或秘密。

公开发布只含允许公开的源码、对应许可和脱敏结果，不公开调试签名 APK。第三方来源见 [`adb/UPSTREAM.md`](app/android/adb/UPSTREAM.md)、[`THIRD_PARTY_NOTICES.md`](app/THIRD_PARTY_NOTICES.md) 和 [`linux/notice/`](app/third_party/linux/notice/)。

## 6. 教程写作规范

**第 5 章是已认可的样板。** 以[实际正文](website/docs/chapter-5.md)检查讲解效果，而不是只检查目录栏目是否齐全。

每章沿一个具体任务推进：

```text
上一章已有能力 → 本章为什么存在 → 具体输入与可见结果
  → 依次做什么 → 每步谁执行/数据怎样变化/真实代码
  → 怎样核对与停止 → 下一章需要什么
```

必须做到：

1. 从 React/TS 背景衔接；先说明任务和新概念，避免用长符号链代替原理。
2. 区分执行层（谁）、时间阶段（先后）、数据路径（结果怎样产生）；方法或语言层不等于新进程。
3. 中文动作标题、短段落、一个段落一个主要问题。必要代码放在对应解释旁，尽量小而完整。
4. 实际代码摘录链接固定章节 tag/明确提交，并与该快照匹配；概念示例和真实源码标明，不把 current main 新能力倒算进旧章。
5. 实际截图与操作录屏证明观察到的结果，不能替代原理；**每新增面向用户的产品章节（第 14 章起）合入前，必须显式使用 `-s "$SERIAL"` 在专用 Android 16 ARM64 设备/模拟器安装当版 Debug APK、走通核心产品交互、截取清晰 PNG 与 `screenrecord` 无剪辑 MP4 录屏并逐帧/逐图目视核对排版与脱敏，然后一并嵌入对应章节和验收档案。** 新练习若没重新设备验证明确标明，不借原截图宣称通过。
6. 协议头、隐藏 API、深层预算/生命周期、APK SHA/历史批次放折叠附录；必要权限、失败和停止规则留在相关动作旁。
7. 手机/桌面、实际深浅主题都看；检查图片解码、图中文字、连线方向、溢出和展开附录。SVG 用标题/描述、唯一 ID 和现有主题变量。
8. 文档质量、代码质量、设备行为、公开交付分别验收；“有标题/截图/CI/Release”不代表教程写法达标。
9. 对话里问到的概念与相邻误区写回正文；执行要求/决定写到项目总账，不只留在会话记忆。

图解参考 Feishu CLI 画板的空间、连线和排版；不自动创建或更新飞书内容。表达参考来源见 PROJECT_RECORD.md，不复制外部正文。

## 7. 提交与发布

开发可以先于发布推进，但公开完成需要单独核验：

1. 明确修改分支和范围，保护已有改动；校验代码、文档与必要实际行为。
2. Commit/PR 说明真实变化和证明范围；AI 生成内容注明来源，署名使用实际 agent 的规范身份，不伪造模型版本。
3. PR CI 对准最后的 head；head 更新后旧 CI 不自动算新版本通过。
4. 合并后核对 merge tree 与已验证源、main CI、Pages，以及线上实际内容。
5. 章节验收完成后建立注释 `chapter-N` 与同名源码 Release；全文档修订保留原 tag，不改写历史源码快照。
6. Release 是完整对应源码归档和 SHA256，不公开 debug APK；匿名下载校验字节、文件范围和第三方对应源码，不只检查 URL 存在。
7. 更新总账、相关章末 PR/Release 链接和状态。把本地 main 快进到远端最新，最终记录实际 branch/HEAD/工作树状态。

[Pages CI](.github/workflows/pages.yml) 在 PR 做验证，在 main 部署。发布鉴权或服务故障只阻塞真正依赖它的动作，保存待发布成果，继续其他已具备代码基础的开发；最终不能只因局部通过就宣布完整交付。

## 8. 交接和汇报模板

接手前读 PROJECT_RECORD.md 的有效目标、时间线和剩余工作，再读本文件、功能对照与相关章节；不要依赖旧终端 handle、旧资源身份或单次记忆。

每次交接至少给出：

```text
源码：branch / commit / 工作树与已存在改动
任务：本次范围、原目标、下一步依赖
实现：已完成入口与变更
验证：命令/环境/制品版本、通过范围、未测范围
问题：原失败、仍未知项、阻塞以及可以并行的工作
资源：只描述当前已核对的自有资源与清理状态，不含秘密
公开交付：PR / 精确 head CI / merge / Pages / 源码快照
接续：最小下一动作及完成判据
```

工作结束前完成或如实保留任务状态；只清理本次创建且不再需要的服务、转发、浏览器和测试规则，不删除历史失败、其他会话成果或仍由用户保留的资源。

## 9. 文档维护责任

- **PROJECT_RECORD.md**：有效要求、决策变化、阶段汇报、交接与未归档边界。
- **DEVELOPMENT.md**：开发入口、工具链、写作、验证、发布与交接方法。
- **ditto-parity.md**：功能覆盖、真实原产品证据、缺失/未核实项。
- **教程各章**：面向学习者的解释、原位代码、实践与对应历史验收。

新要求或实际状态改变时更新正确位置并交叉链接；同一份验收数字保留一个权威来源，其他文档摘要和链接，不维护多份容易漂移的复制表。
