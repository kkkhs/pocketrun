# PocketRun 项目记录

这份文档集中记录项目目标、持续要求、关键决策与阶段汇报，供开发和交接使用。读者教程在 [`website/docs/`](website/docs/intro.md)，开发规范在 [`DEVELOPMENT.md`](DEVELOPMENT.md)。

**归档日期：2026-11-25。** 当前成果依据是合并提交 [`4b96eaf`](https://github.com/kkkhs/pocketrun/commit/4b96eaf4868448af004ad468c950fa95c760b1a9)。本记录从既有会话要求、已入仓文档和公开 PR/Release 整理，不是逐字聊天记录，也不声称全部原始验收材料已经入仓。

## 1. 当前有效目标

**从零做出 Android AI 助手，最终覆盖 Ditto 整体背后的功能，并通过教程理解框架、模块协作与运行逻辑。**

- 面向有 React / TypeScript 背景的学习者；代码是验证机制的手段，不能只交付功能而不解释原理。
- 保持 Ditto 类似的本机执行路线；按阶段扩充能力，不中途用另一套产品框架替换执行核心。
- 固定 Demo、最小 BusyBox/musl、三个固定工具、受控两步任务是教学阶段，不是可以自行缩小的最终范围。
- 完整功能对照见 [`ditto-parity.md`](website/docs/ditto-parity.md)。只有原产品证据确认的功能才能写成 Ditto 对照项；未经核实的功能和 PocketRun 已知边界分别记录。
- 每章包含实现、可运行检查、清楚的原理与代码对应、实际结果、PR 及章节源码快照。文档质量与功能验收是两个独立条件。

后续要求有变化时，先更新本节的有效基线，再在第 3 节追加日期、理由和影响范围；旧阶段记录保留，不用新目标改写旧事实。

## 2. 执行架构基线

| 约定 | 当前路径与边界 |
| --- | --- |
| UI 与原生能力 | React Native / TypeScript → TurboModule → 同一 App 中的 Kotlin；不是额外 Node 后端 |
| App 自有启动入口 | App 自己与同一 Android 设备的无线 ADB 配对、TLS 连接；电脑 ADB 只用于授权开发/验收 |
| 独立系统操作后端 | ADB 启动 `app_process`，校验实际 UID 2000；启动后的操作使用 typed Binder |
| 显示观察与输入 | 后端持有 ImageReader-backed VirtualDisplay；启动、截图、UI 树、输入都绑定同一有效正 displayId，不能失败后转向主屏 |
| Linux 工作环境 | PRoot / Alpine 派生程序使用普通 App UID 和同一 Android/Linux 内核，不赋予 root 或 shell 权限 |
| 非交互与交互 | 非交互用 ProcessBuilder 字节管道；交互用 JNI 和真正 PTY，分别管理数据与退出状态 |
| 工具与模型 | TaskHost → SingleTaskContext → Kotlin AgentLoop；新控制层复用原执行器和手机批准，不绕过校验 |
| 生命周期 | 原生所有者负责取消、期限、死亡与资源清理；未知或未确认不当作成功，保留 owner 直到可以安全收尾 |

模型推理在 endpoint 对应的服务，手机运行 SDK、回合控制和工具宿主。当前 Kotlin 编排是实现选择，不是“Agent 只能写 Kotlin”的要求。更详细的现状见[系统概览](website/docs/architecture.md)和[开发调用链](DEVELOPMENT.md#2-代码入口与调用链)。

## 3. 要求与关键决策时间线

下列“要求记录”依据当时明确的会话要求整理；“交付记录”由 PR/Release 可独立核对。部分早期研究没有完整原始材料，不能据此补造原文或推断未确认的产品功能。

| 日期/阶段 | 要求或决定 | 理由与后续影响 |
| --- | --- | --- |
| 2026-11-13 | 确立 Android AI 助手与配套教程项目；重点学习框架和运行逻辑 | 讲清调用链和实现过程，不只产出 App；从 React/TS 经验衔接 |
| 早期研究阶段，具体日期未统一归档 | 研究 Ditto 的文章、APK 与可读实现；采用相近执行核心 | 原始材料的证据范围单列，静态重建不是原始源码或实际设备结果 |
| 2026-11-13 | 参考 riba2534 博客的技术表达 | 学习短段落、具体例子和图文组织，不复制正文，不据此更换应用架构 |
| 2026-11-16—17 | 加强章节递进、配图和概念解释；聊天里澄清的问题写回教程 | 让读者知道上一章得到什么、本章为什么存在、下一步能做什么 |
| 2026-11-17 | 每章附实际 PR；验收发布后建立注释 tag 与源码 Release | 后续 main 继续演进时，读者仍能检出当章结束状态；不公开调试签名 APK |
| 2026-11-18 | 第 5 章按一个具体任务重写，区分执行层、时间阶段和数据路径；成为认可样板 | 中文动作标题、原位代码、必要说明；协议和详细验收放附录。标准持续适用于前后所有章节 |
| 2026-11-18 | 先沿 Ditto 执行核心推进，不让 Eta/Mika 的产品化候选中途替换路线 | 开发次序与最终功能范围不同；MCP/subagent 等是否属于 Ditto 需要直接证据 |
| 2026-11-18—20 | Devbox 承担到第 9 章的开发与交付；主会话随后推进第 10—11 章 | 中途方案由负责人判断，必要时有界委派与独审；不能多人同时写同一文件或操作同一设备 |
| 2026-11-19 | 开发与发布分开推进，不因 GitHub 发布阻塞就停止所有后续工作 | 下一章依赖已验证代码，不依赖前章已建立 Release；保留待发布清单，最后仍完成发布验收 |
| 2026-11-20 | 主会话接管本地第 7—11 章剩余设备/模型验收与发布 | 使用任务专用模拟器与合法模型配置，保留 Devbox 成果，停止重复云验收；不操作个人手机 |
| 2026-11-23 | 第 7—11 章阶段验收与源码交付收口 | 各章制品、正常/失败批次、实际 UI/模型与资源证据分开；见第 4 节和各章附录 |
| 2026-11-24 | 用户指出第 6 章起未持续落实认可写法，要求重新审校 | 不能把有截图、栏目、CI/Release 当作文档已达标；[PR #22](https://github.com/kkkhs/pocketrun/pull/22) 重整第 6—11 章 |
| 2026-11-24 | 阅读准备度检查后修正共享参考和前置条件 | [PR #23](https://github.com/kkkhs/pocketrun/pull/23) 同步架构/路线图，明确 Linux 条件、standalone Debug 与模型预检范围 |
| 2026-11-25 | 明确最终复刻 Ditto 整体背后功能，并记录缺失 | 最小环境不是终局；[PR #24](https://github.com/kkkhs/pocketrun/pull/24) 增加功能对照与待核实项 |
| 2026-11-25 | 补清第 6/7/9/11 章概念：进程基础、内核/用户态/PRoot、异步批准、Kotlin Agent | 同一 PR #24 把对话解释写回正文；不只在聊天中回答 |
| 2026-11-25 | 要求项目要求、汇报和开发文档存入仓库，并同步本地 main | 建立本文件与 DEVELOPMENT.md；以后接手不依赖单个会话记忆或临时终端状态 |

写作参照：[博客入口](https://blog.riba2534.cn/)；[DeepSeek Harness 架构文章](https://blog.riba2534.cn/blog/2026/agent%E7%9A%84%E5%BF%92%E4%BF%AE%E6%96%AF%E4%B9%8B%E8%88%B9-deepseek-harness%E6%9E%B6%E6%9E%84%E8%AE%BE%E8%AE%A1/)。技术配图参考 Feishu CLI 画板的布局、连线与排版；参考图规范不等于获准写入飞书。

## 4. 阶段成果与公开证据

第 0—11 章教程和源码 Release 已公开；这表示对应章节的实现与记录已交付，不表示 Ditto 完整功能覆盖或所有 Android/OEM 通过。

| 章节 | 阶段产物 | 实现/相关 PR | 源码快照 |
| --- | --- | --- | --- |
| 0 | 教程站与发布流程 | [#1](https://github.com/kkkhs/pocketrun/pull/1) | [chapter-0](https://github.com/kkkhs/pocketrun/releases/tag/chapter-0) |
| 1 | React → Kotlin 与设备信息 | [#2](https://github.com/kkkhs/pocketrun/pull/2)、[#3](https://github.com/kkkhs/pocketrun/pull/3)、[#4](https://github.com/kkkhs/pocketrun/pull/4) | [chapter-1](https://github.com/kkkhs/pocketrun/releases/tag/chapter-1) |
| 2 | App 自有无线 ADB | [#5](https://github.com/kkkhs/pocketrun/pull/5) | [chapter-2](https://github.com/kkkhs/pocketrun/releases/tag/chapter-2) |
| 3 | UID 2000 后端与 Binder | [#6](https://github.com/kkkhs/pocketrun/pull/6) | [chapter-3](https://github.com/kkkhs/pocketrun/releases/tag/chapter-3) |
| 4 | VirtualDisplay 与 Demo 路由 | [#7](https://github.com/kkkhs/pocketrun/pull/7) | [chapter-4](https://github.com/kkkhs/pocketrun/releases/tag/chapter-4) |
| 5 | 截图、UI 树、定向输入及再观察 | [#8](https://github.com/kkkhs/pocketrun/pull/8)、[#9](https://github.com/kkkhs/pocketrun/pull/9) | [chapter-5](https://github.com/kkkhs/pocketrun/releases/tag/chapter-5) |
| 6 | 私有 workspace 与命令管道 | [#11](https://github.com/kkkhs/pocketrun/pull/11)、[#15](https://github.com/kkkhs/pocketrun/pull/15) | [chapter-6](https://github.com/kkkhs/pocketrun/releases/tag/chapter-6) |
| 7 | 最小 PRoot/BusyBox/musl 与所有权恢复 | [#12](https://github.com/kkkhs/pocketrun/pull/12) | [chapter-7](https://github.com/kkkhs/pocketrun/releases/tag/chapter-7) |
| 8 | 真实 JNI/PTY、输入、resize、退出与清理 | [#13](https://github.com/kkkhs/pocketrun/pull/13) | [chapter-8](https://github.com/kkkhs/pocketrun/releases/tag/chapter-8) |
| 9 | 三个固定工具与手机逐次批准 | [#14](https://github.com/kkkhs/pocketrun/pull/14) | [chapter-9](https://github.com/kkkhs/pocketrun/releases/tag/chapter-9) |
| 10 | 一个目标、两条保留的真实步骤 | [#17](https://github.com/kkkhs/pocketrun/pull/17)、[#20](https://github.com/kkkhs/pocketrun/pull/20) | [chapter-10](https://github.com/kkkhs/pocketrun/releases/tag/chapter-10) |
| 11 | Kotlin 真实模型循环与加密配置 | [#18](https://github.com/kkkhs/pocketrun/pull/18)、[#20](https://github.com/kkkhs/pocketrun/pull/20) | [chapter-11](https://github.com/kkkhs/pocketrun/releases/tag/chapter-11) |

交付与文档补充：

- [PR #10](https://github.com/kkkhs/pocketrun/pull/10)：第 0—4 章任务式讲解。
- [PR #16](https://github.com/kkkhs/pocketrun/pull/16)：instrumentation 观察器的框架线程收尾修复及回归。
- [PR #20](https://github.com/kkkhs/pocketrun/pull/20)：第 10—11 章本地验收、固定 Debug 网关策略与来源截图；chapter-11 固定于其合并 `8f95aa7`。
- [PR #21](https://github.com/kkkhs/pocketrun/pull/21)：已发布状态与源码快照链接。
- [PR #22](https://github.com/kkkhs/pocketrun/pull/22)：第 6—11 章任务主线、64 段固定源码摘录和六张图。
- [PR #23](https://github.com/kkkhs/pocketrun/pull/23)：整体阅读准备度、共享参考与运行前置。
- [PR #24](https://github.com/kkkhs/pocketrun/pull/24)：执行概念与完整 Ditto 目标/缺口。

### 第 7—11 章收口汇报摘要

| 范围 | 已记录结果 | 不能据此推出 |
| --- | --- | --- |
| 第 7 章独立包 | 实际 UI、正常演示、12 项生命周期、7 个死亡恢复场景 | 完整 Alpine、包管理或所有 OEM 兼容 |
| 第 8 章独立包 | 真实 PTY、6 项生命周期、App/tracer 死亡恢复、原 120 秒产品期限与自有资源检查 | 完整终端模拟器；新增 CSV 交互阅读练习已重新设备验收 |
| 第 9 章独立包 | 真实手机请求/批准/拒绝、固定 CSV、显示观察/输入、执行中取消和原 5 秒期限 | 任意 App 自动化或通用命令工具 |
| 第 10 章独立包 | 同一目标保留设备和手机批准 CSV 两条结果，核对后完成 | 跨进程死亡持久任务、长期记忆 |
| 第 11 章正常集成包 | 本机 Ferry/Seed 2.1 Turbo，3 回合、2 个真实工具、CSV 3 行，来源与清理确认；520 输出 token、3157 字节模型上下文 | 这是 Claude Opus；完整手机自动化或此前网络故障根因已修复 |

原失败继续保留：第 7 章历史 rootfs 重用失败；第 8 章总 FD 差额与后来精确资源检查分开；第 11 章审批过期和第三回合 `MODEL_TRANSPORT_ERROR` 不由后续成功改写。日期、完整 App/Test SHA、runner 和证据范围以各章附录及 Release 为准，不在多个文档重复复制易失真的全部数字。

## 5. 开发与交接约定

- 主会话负责需求、依赖、决策和总文档；默认直接推进，必要时只开有明确边界的 worker，再做阶段性独审。
- 同一文件只有一个 writer；写者与独审职责分开。已委派的搜索不在主会话重复执行。
- 自主中途判断不等于绕过权限、操作未授权设备、关闭产品批准或伪报验收。
- 开发与发布可以分开排队；发布阻塞只暂停依赖它的动作，不拖住已经具备代码基础的后续实现。
- 交接必须给出源码提交、实际状态、已验证范围、原失败、剩余任务、资源归属及下一步；不能只给一个终端或“都通过了”的结论。
- 本地文档可以随开发维护；共享文档/飞书仅按明确要求写入。公开仓库不放秘密、配对码、设备连接信息或未经授权的内部原文。

## 6. 剩余工作

这是后续开发的入口，不是完整原产品能力已经核实的声明：

1. 建立版本明确的 Ditto 原产品工具/操作/模型/恢复清单，补直接证据；未经核实标记 unknown。
2. 按[功能对照](website/docs/ditto-parity.md)补 Linux 分发和包管理、通用工具、一般 App 操作、模型观察、终端呈现、持久化与恢复。
3. 串联“处理文件 → 操作 App → 保存结果”的完整任务，逐阶段观察来源和副作用，验证取消/失败/恢复。
4. 继续第 12—13 章计划；功能清单需要时增加章节，不能把已有编号当作最终范围上限。
5. 扩展设备/目标 App/兼容分支覆盖，保持正 displayId、实际 UID 和资源所有权约束。
6. 将必要的原始研究与验收资料转存到适当的持久位置，并建立脱敏索引；见下一节。

## 7. 记录边界与尚未入仓资料

本次统一的是有效要求、主要决策和阶段汇报，不是把所有会话、日志和私有材料公开归档：

- Devbox 的原 goal、写作指南和停止交接文件曾保存在仓库外；本文件和 DEVELOPMENT.md 已吸收其可公开的目标与执行规范，不保证原外部文件仍可访问。
- Ditto 内部文章正文、原 APK 和完整重建材料没有在当前仓库形成可重新核查的完整资料集。现有静态对照保留明确版本与局限；不能把缺资料理解成没有该功能。
- 原始设备/模型 runner、截图和宿主资源观察部分在私有或临时目录中；已公开教程保存摘要与版本关联。临时目录不是长期档案，不声称原始日志都随源码 Release 发布。
- 配对码、token、provider secret、CLI/OAuth 认证和其他敏感值不进入本记录、源码、日志或公开附件。后续归档只保存允许公开且足以支持结论的资料。
- 本次整理不重新运行设备验收；既有记录仍按对应源码、APK 和批次解释。

## 8. 后续更新格式

有新的要求、开发进展、阻塞、交接或发布时，在本文件追加最小可核对记录：

```text
日期：YYYY-MM-DD（不确定则写阶段，不补猜日期）
事项：要求 / 决策 / 开发汇报 / 验收 / 交接 / 发布
结论与理由：
影响范围与源码版本：
证据：仓库文件、PR、CI、Release 或允许公开的记录
未完成 / 原失败 / 下一步：
```

更新有效目标时同步第 1 节；实现缺口更新 ditto-parity.md；开发方法更新 DEVELOPMENT.md；教学解释写回相应章节。不要把只有工具存在、代码可编译、一个局部测试通过写成整体功能完成。
