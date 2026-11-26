---
sidebar_position: 3
sidebar_label: 实机验收与工程档案
title: 实机验收记录与工程档案
description: 集中归档第 0—11 章的实机与模型验收批次、产物 SHA-256、历史失败留痕、第三方源码来源与依赖审计策略。
---

为了保持教程正文清晰可读，我们将各章的**实机测试批次、APK 校验和、历史失败留痕、Instrumentation 命令、第三方开源许可与 npm 审计记录**统一集中归档在这里。

教程里的每一项能力都经过对应批次的源码与设备核验；同时这里严格区分**单元测试（Jest / JVM）**、**Android 设备集成测试**与**真实产品界面 / 模型运行**的证明范围，不跨批次拼凑结论，也不抹去历史失败记录。

## 网站与依赖审计记录

- **审计门禁（[`scripts/audit-ci.cjs`](https://github.com/kkkhs/pocketrun/blob/main/scripts/audit-ci.cjs)）**：阻断一切未豁免的 `high` / `critical` 安全公告，同时在非 JSON 或网络异常时失败退出。
- **唯一临时例外**：2026-11-18 npm 新增 `picomatch` 高危公告 [GHSA-fc5q-39xc-vjpf](https://github.com/advisories/GHSA-fc5q-39xc-vjpf)（截至 4.0.7 暂无修复版本，需攻击者控制的 repeated-extglob pattern 才可触发；PocketRun 仅在构建与测试中使用仓库固定 glob）。门禁仅对该精确 source / URL / range 放行至 **2026-12-18**，到期自动失效阻断。
- **其他已知条目**：`CVE-2026-93753` 已被官方 [CVE 记录](https://www.cve.org/CVERecord?id=CVE-2026-93753)标记为 `REJECTED`；App 侧来自 `minimatch`（[GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249)）的 moderate 传播条目按基线留痕。
- **发布原则**：所有章节 GitHub Release 仅公开完整源码归档、对应第三方源码与 `SHA256SUMS`，**不公开调试签名 APK**，也不提交任何配对码、API Key、连接信息或 OAuth 凭据。

## 第 1—5 章：Android 权限与虚拟屏通道

核心设备基线为任务专用的 **Android 16（API 36）ARM64** 模拟器（`pocketrun-ch1-api36`）。所有 ADB 命令均显式指定 `-s "$SERIAL"`，不触碰个人真机。

| 章节 | 源码快照与 PR | 已记录验证范围与结果 |
| --- | --- | --- |
| **第 1 章** | [chapter-1](https://github.com/kkkhs/pocketrun/releases/tag/chapter-1) · [#2](https://github.com/kkkhs/pocketrun/pull/2) / [#3](https://github.com/kkkhs/pocketrun/pull/3) / [#4](https://github.com/kkkhs/pocketrun/pull/4) | TS / ESLint / 3 项 UI 测试、ARM64 Debug APK 构建；API 36 模拟器真实点击核对 Android 版本、API、ABI、App UID 与 logcat 一致 |
| **第 2 章** | [chapter-2](https://github.com/kkkhs/pocketrun/releases/tag/chapter-2) · [#5](https://github.com/kkkhs/pocketrun/pull/5) | 2026-11-17：`AdbTransportTest` JVM 生命周期测试、`libpocketrun_adb.so` 16 KB 对齐、5 份第三方 notice 打包；`AdbPairingIntegrationTest` 真实无线配对 + TLS + 连续两次 `id -u == 2000`（`OK (1 test)`）；真实界面完成连接、复检与断开 |
| **第 3 章** | [chapter-3](https://github.com/kkkhs/pocketrun/releases/tag/chapter-3) · [#6](https://github.com/kkkhs/pocketrun/pull/6) | 2026-11-17：9 项 UI 测试、JVM 启动命令测试、Debug/Release/AndroidTest 构建；`AdbPairingIntegrationTest` 真实验证 UID 2000、拒绝 App UID 冒充 exchange、断 ADB 后 Binder ping、kill 后端触发 Binder death 与取消（`OK (2 tests)`）；真实界面完成全链路 |
| **第 4 章** | [chapter-4](https://github.com/kkkhs/pocketrun/releases/tag/chapter-4) · [#7](https://github.com/kkkhs/pocketrun/pull/7) | 2026-11-18：12 项 UI 测试与 JVM 测试；API 36 设备测试 `OK (2 tests)`，系统交叉核对 `DisplayManager` ID、`DisplayDemoActivity` task 归属、显式释放与 kill 后端后无残留显示/进程 |
| **第 5 章** | [chapter-5](https://github.com/kkkhs/pocketrun/releases/tag/chapter-5) · [#8](https://github.com/kkkhs/pocketrun/pull/8) / [#9](https://github.com/kkkhs/pocketrun/pull/9) | 2026-11-18：30 项 Jest、27 项 JVM；`Chapter5DittoIntegrationTest`（`OK (1 test)`）真实验证共享内存只读 JPEG 解码、UI 树定位输入 `PocketRun_Ditto_5` 并复查、旧 ID 拒绝、并发 `dumpUiTree` vs `releaseDisplay`、`ps -T` 线程清理与主屏无污染 |

<details>
<summary>常用设备构建与 Instrumentation 验证命令参考</summary>

```bash
# 在 app/ 运行前端检查与 Android 构建
npm run typecheck
npm run lint
npm test -- --runInBand
npm run audit:ci

cd android
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2 --no-daemon --console=plain
```

运行设备集成测试前，务必先核对设备身份与 ABI，且显式使用 `-s "$SERIAL"`：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# 示例：运行第 6 章工作目录生命周期测试
adb -s "$SERIAL" shell am instrument -w \
  -e class io.github.kkkhs.pocketrun.Chapter6WorkspaceIntegrationTest \
  io.github.kkkhs.pocketrun.test/androidx.test.runner.AndroidJUnitRunner
```

> 注意：`HostDeath` 系列测试（`Chapter6HostDeathTest` / `Chapter7HostDeathTest` / `Chapter8HostDeathTest`）与 `Chapter9ProductLinuxHoldTest` 依赖对应的宿主协调协议与排他设备窗口，不能直接无驱动整类执行。另见 [PR #16](https://github.com/kkkhs/pocketrun/pull/16) 对 instrumentation 观察器框架线程收尾的修复。

</details>

## 第 6—8 章：工作目录、PRoot 与交互式 PTY

| 章节 | 源码快照与 PR | 核心实机验收与产物记录 | 保留的历史失败 / 边界说明 |
| --- | --- | --- | --- |
| **第 6 章** | [chapter-6](https://github.com/kkkhs/pocketrun/releases/tag/chapter-6)（`90d9d4f`）· [#11](https://github.com/kkkhs/pocketrun/pull/11) / [#15](https://github.com/kkkhs/pocketrun/pull/15) | 2026-11-18—19 授权 API 36 ARM64 云真机：`Chapter6WorkspaceIntegrationTest` `OK (11 tests)`（含无效 UTF-8 截断至 65535 B、同进程 FD `105 → 105`、IO 线程归零）；App `SIGKILL` 恢复、发布前被杀恢复、宿主杀子进程恢复均通过；App APK SHA 前缀 `b3cad89d3347`，Test APK `ce4ed7cd3c6f` / `350106fb0f50` | 早期 Android `Files.createLink()` 返回 `AccessDeniedException` 的旧实现失败保留；界面正常流程与异常专项分别记录 |
| **第 7 章** | [chapter-7](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7)（`2c49a78`）· [#12](https://github.com/kkkhs/pocketrun/pull/12) | 2026-11-20 & 2026-11-23 专用 API 36 ARM64 模拟器：`Chapter7LinuxDemoTest`（`OK (1 test)`）、`Chapter7LifecycleIntegrationTest`（`OK (12 tests)`）、6 个 App/tracer/guest 硬死亡 + 1 个安装发布前死亡恢复、独立章节包实际 UI 均通过；2026-11-23 独立包 Debug APK `a1824358b31fa257f7087427465fdb33552198be574c39a146a246585abad674`，Test APK `0e054a24e8c663c8da4c6cd77e458dc0ced795d6806c2d970610c448bc0ea7fa`；预置绑定点 `alpine-data.bin` 为 587 B、34 条目、SHA256 `8c4ac18ae5babcbd3f692e1c4bed6710eb965967da792fc6ad597def4a1eff06` | 2026-11-19 云真机早期批次中 10 项/3 失败、11 项/2 失败以及旧包 App 死亡后 rootfs 重用因不可读绑定占位失败的记录保留 |
| **第 8 章** | [chapter-8](https://github.com/kkkhs/pocketrun/releases/tag/chapter-8) · [#13](https://github.com/kkkhs/pocketrun/pull/13) | 2026-11-23 独立第 8 章包（Debug APK `bf34565809a4524c0adc41888750908fca9743d81fd86dd6cfc9bd8bb814a39b`，Test APK `ae018b6f9e5a7bbe78734dd224009a7279c04bdd347cd82b4c082db651ea50d3`）：`Chapter8PtyIntegrationTest`、`Chapter8LifecycleIntegrationTest`（`OK (6 tests)`）、2 项 App/tracer 硬死亡恢复、实际界面（`24 80` → Ctrl-C 中断 `sleep 30` → `32 100` → `exit 0`）以及 120 秒真实超时自有资源精准检查通过 | 早期批次 rootfs 库存原失败、一次产品超时 FD `150→151` 失败，以及独立包 120s 超时总 FD `143→147` 断言失败（差额全为 Android 图形 `anon_inode:sync_file` `19→23`）均如实保留；教程新增的 CSV 交互阅读练习与既有的尺寸/Ctrl-C 验收截图区分标明 |

## 第 9—11 章：工具宿主、任务账本与真实模型循环

| 章节 | 源码快照与 PR | 核心实机验收与产物记录 | 保留的历史失败 / 边界说明 |
| --- | --- | --- | --- |
| **第 9 章** | [chapter-9](https://github.com/kkkhs/pocketrun/releases/tag/chapter-9) · [#14](https://github.com/kkkhs/pocketrun/pull/14) | 2026-11-23 独立第 9 章包（Debug APK `911c97addec0e0bfaa5cadf00e7f8813df20d56b4934119efb6e95e931245b0e`，Test APK `e3bb6922c041cc8f1bfd9fdb78250b7e3d89ae9ad7efa990118bf67e344d3c3f`）：`Chapter9TaskHostIntegrationTest`（4 项）、`ProductAdbBootstrapIntegrationTest`（114.549s 真实按钮配对→虚拟屏）、`Chapter9ProductLinuxHoldTest`（内核 pidfd 暂停已启动 PRoot，验证真实执行中取消与原 5 秒超时）、虚拟屏批准输入 `PocketRun_TaskHost_9` 及 JS 桥参数拒绝矩阵全部通过 | 桥接拒绝矩阵使用临时诊断开发 bundle 运行，验收后已移除覆盖并恢复正常 APK；Debug 专用 pidfd 测试 JNI 不导出至 Release |
| **第 10 章** | [chapter-10](https://github.com/kkkhs/pocketrun/releases/tag/chapter-10)（`a2e3679`）· [#17](https://github.com/kkkhs/pocketrun/pull/17) / [#20](https://github.com/kkkhs/pocketrun/pull/20) | 2026-11-23 独立第 10 章包（Debug APK `a61950af5b3c7c2bb2dac1355a558d0b386739559520b746973aba03597f87b5`，Test APK `2fc3f213e2f2cbb02611282e0b7f906ea598f6197a0892c0bbc39375036c6a7a`）：82 项 JVM 零失败、1 项 `Chapter10ContextIntegrationTest` 通过；实际界面完成 `task-1` 设备信息 + 手机审批 CSV（3 行、202ms、清理确认）并完成核对 | 单任务上下文仅存活于当前 App 进程内存，不跨进程死亡持久化 |
| **第 11 章** | [chapter-11](https://github.com/kkkhs/pocketrun/releases/tag/chapter-11)（`8f95aa7`）· [#18](https://github.com/kkkhs/pocketrun/pull/18) / [#20](https://github.com/kkkhs/pocketrun/pull/20) | 2026-11-23 最终正常集成包（Debug APK `134329e82f2a2abda257b9fd7da78305baae8ab37a3137625e795f164377502b`，Test APK `924cd2b1e36c4bdd10b2c2124f937a361f3b3ecc5c4679cc159bd304b3`）：90 项 `taskhost` JVM 零失败、Keystore / SDK runtime / 网关回放 / context 等 5 项 Android 测试通过；经本机兼容网关调用 Seed 2.1 Turbo（`claude-doubao-seed-2-1-turbo`）真实跑通 3 回合、2 条真实工具、手机批准 CSV（3 行、143ms、5 阶段退出码 0、清理确认）、520 输出 token、3157 字节上下文；已验证编译后的 Release 类仅允许 HTTPS 并拒绝 loopback HTTP | 早期 2 次审批过期与 2 次第 3 回合 `MODEL_TRANSPORT_ERROR` 保留；本次实测使用本地兼容网关而非默认 Anthropic 官方服务 |

## 第 12 章：用户可管理的跨任务记忆

源码检查点 [`de9cf23`](https://github.com/kkkhs/pocketrun/commit/de9cf23b75676dae52ee78de2eac3ac522ae904d)，[PR #27](https://github.com/kkkhs/pocketrun/pull/27)。同一冻结正常包：Debug APK `26b16f6e0d97d47b99afa466f1644b767884c1a803c5ec4dd8189705985db847`，Test APK `a1111bcefc964bcef430a95ddc4cffe02ac2f5644d99b070bb5e469cfde01793`。

- **源码与机制：** 100项taskhost JVM、90项UI通过；ARM64 Debug/Release/AndroidTest构建通过。API36 ARM64专用模拟器的6项隔离记忆存储，加旧凭据兼容/SDK/runtime/网关/context，共11项Android检查通过；不把它们当手机完整任务。
- **真实App/模型（2026-11-25—26）：** 明确选择/目的地确认后Report A；同noteID纠正后版本变化，App重启、重新选择得到Report B（两次121记忆编码字节）；等新空快照确认后删除/重启仍为空，旧入口真实任务完成且无记忆、IDs为空、0记忆编码字节。均为本机Ferry/Seed2.1Turbo，不是Claude Opus。
- **取消与越权边界：** 实际恶意“自动批准CSV”笔记仍停在工具阶段等待手机，未点批准；删笔记后实际`cancelled / MEMORY_CHANGED`并清除本地记忆上下文。坏参数/旧回调/unproven保槽另由核心回归检查，不拼成全部实机安全分支通过。
- **原失败保留：** 空JSON应计2字节的初次断言错误、两次UI驱动批准过期、UI层读取超时、无记忆任务首轮传输失败、点删除确认后立即停App未等提交而仍保留B，分别保留。后续通过不补记原删除或模型失败为成功，也不宣称传输根因已修复。

[第12章教程](chapter-12.md)保留任务主线和实际截图；源码Release在最终CI/Pages核验后建立。共享的credential/memory密文机制保持独立schema/文件/别名/AAD，无新增provider凭据导入。

## 第三方源码来源与开源许可

PocketRun 严格保留所有嵌入第三方组件的固定上游来源、修改记录与对应源码（随章节源码 Release 一并分发）：

| 组件 | 固定上游来源与校验 | 许可与仓库内位置 |
| --- | --- | --- |
| **无线 ADB 客户端子集** | [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 提交 `b844bc491f1790c72328e1a8e5b2349f8978f0ea`（含 BoringSSL / Conscrypt / Bouncy Castle / LLVM 声明） | Apache-2.0 等；见 `app/android/adb/UPSTREAM.md`、`LICENSE` 与 `src/main/assets/third-party/` |
| **PRoot & loader** | [termux/proot v5.1.107.102](https://github.com/termux/proot/tree/v5.1.107.102)（原 ZIP SHA256 `30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852`）+ `talloc` | PRoot（GPLv2+）与 `talloc`（LGPLv3+）组合按 **GPLv3** 分发；对应修改与构建脚本见 `app/third_party/linux/matching_sources/` |
| **最小 Alpine BusyBox / musl** | [Alpine 3.22.8 aarch64 minirootfs](https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.8-aarch64.tar.gz)（SHA256 `36091efacd083c1cf5a8c837272530bff4fc72db1fbfdef559f52de0d890bca4`）派生 | BusyBox 为 **GPLv2-only**（含 `utmps`/`skalibs` ISC 版权），musl 为 **MIT**；APK 打包 17 份许可证/版权文本，详见 `app/third_party/linux/notice/` 与 `licenses/` |
| **Anthropic Java SDK** | `com.anthropic:anthropic-java:3.11.0`（锁定于 `app/android/app/gradle.lockfile`） | 许可与声明汇总见 `app/THIRD_PARTY_NOTICES.md` |

完整的项目要求、决策时间线和跨会话交接基线，请参阅仓库根目录的 [`PROJECT_RECORD.md`](https://github.com/kkkhs/pocketrun/blob/main/PROJECT_RECORD.md) 与 [`DEVELOPMENT.md`](https://github.com/kkkhs/pocketrun/blob/main/DEVELOPMENT.md)。
