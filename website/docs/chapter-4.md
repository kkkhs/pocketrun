---
sidebar_position: 5
sidebar_label: 第 4 章：创建演示虚拟屏
title: 第 4 章：把 Demo App 放进虚拟屏
description: 复用已有 Binder 后端，创建无实体面板的 VirtualDisplay，把固定 Demo 路由到目标 displayId，核对归属并释放。
---

import beforeScreenshot from './assets/chapter-4-before.png';
import activeScreenshot from './assets/chapter-4-active.png';
import releasedScreenshot from './assets/chapter-4-released.png';
import VirtualDisplayFlow from './assets/virtual-display-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章，App 已经能通过 Binder 调用独立的 UID 2000 后端。现在复用它，不需要为每次操作重新连接 ADB、启动进程。

这一章只做一个任务：**创建一块 720 × 1280、没有实体面板的显示，把固定 Demo 放进去，核对它确实在那块屏上，再释放。**

```text
创建：取得显示 ID → 启动固定 Demo
核对：App 收到 ID，确认系统显示与 task
结束：释放显示，旧 ID 作废
```

本章可见结果是 PocketRun 面板中的有效 `displayId` 和释放后的状态，**不是虚拟屏画面**。截图、UI 树与输入留到[第 5 章](chapter-5.md)。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>创建前</strong></figcaption>
    <img src={beforeScreenshot} alt="第 4 章虚拟屏面板，创建按钮可用，检查和释放按钮禁用" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>创建并启动 Demo 后</strong></figcaption>
    <img src={activeScreenshot} alt="第 4 章虚拟屏面板显示 Demo 已启动到虚拟屏以及有效 displayId" width="360" />
  </figure>
</div>

截图中的 ID 由 Android 动态分配，不要求等于 `10`。通过条件是 **ID 大于 0，且系统确认 Demo Activity 的 task 属于同一个显示**，不是只信后端返回的数字。

## 先在界面跑一遍

1. 按[第 2 章](chapter-2.md)、[第 3 章](chapter-3.md)连接 ADB 并启动 shell 后端；后端就绪后可以断开 ADB。
2. 点“创建演示虚拟屏”，结果应显示一个大于 0 的 `displayId`。
3. 点“检查虚拟屏”，ID 应保持不变。
4. 点“释放虚拟屏”，结果应说明旧 ID 已失效。
5. 最后停止 shell 后端。

“检查虚拟屏”读取后端保存的显示状态，不等于系统独立核对 task，也不等于截图。真实设备测试会额外检查系统 display/task 列表。

## 先理解这块屏

**VirtualDisplay 不是虚拟机。** 它是 Android DisplayManager 创建的一块逻辑显示，有尺寸、密度和编号，但没有另一套 Android 内核或系统。可以把它想成一台没有实体面板的外接显示器。

- **VirtualDisplay**：承载这块逻辑显示。
- **Surface**：显示画面写入的位置。
- **ImageReader**：提供这个 Surface；本章只让它承接画面，第 5 章才取帧做预览。
- **displayId**：系统分配的显示编号，后续启动、截图、查树和输入都要指向同一个有效编号。

画面资源的关系是 `Demo 绘制 → 虚拟屏的 Surface → ImageReader`。它先为画面提供写入位置，下一章再读取其中的像素。

默认主屏编号是 `0`，本章只使用正数编号。把 Demo 放到另一块屏，只改变它的显示位置，不改变它的进程身份或权限。

## 分清谁持有资源

| 执行层 | 本章负责什么 |
| --- | --- |
| React / TypeScript | 按钮和 `{active, displayId}` 状态展示 |
| App Kotlin | 同一 App 进程内接收 TurboModule 调用，经 Binder 请求后端，把结果转成 Promise 数据 |
| shell 后端 Kotlin | 独立 UID 2000 进程，持有 VirtualDisplay、ImageReader 和 Surface |
| Android 系统 | 管理显示，按 `displayId` 把 Activity task 放到目标屏 |

**React state 保存编号，不拥有显示资源。** 页面重绘不会复制 VirtualDisplay；清空 state 也不能释放它。Demo 仍运行在普通 PocketRun App 进程里，shell 后端负责创建和管理环境。

## 按步骤创建并核对

这张图只看时间顺序，前一节才回答“谁执行”。

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="创建显示、启动 Demo、核对归属与释放的阶段顺序，可横向查看">
    <VirtualDisplayFlow className={`pr-vd-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-vd-title" aria-describedby="pr-vd-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>系统先分配 displayId，后端再用这个 ID 启动 Demo；核对归属与释放是后面的两个步骤。</figcaption>
</figure>

### 创建显示并取得 ID

[DisplayPanel.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/DisplayPanel.tsx) 调用 `createDemoDisplay()`，经 [AdbModule.createDemoDisplay()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/AdbModule.kt#L95) 和 [PrivilegedBackendController.createDemoDisplay()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt#L126)，通过 Binder 进入后端。

[VirtualDisplaySession.createDemoDisplay()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/VirtualDisplaySession.kt#L24) 先创建 ImageReader 的 Surface，再请求系统创建 VirtualDisplay。Android 返回显示对象，后端从它取得 `displayId`，确认大于 0，保存这次会话的资源和 ID。

固定尺寸是 720 × 1280，密度 320 dpi。它们定义目标显示的坐标系，不是 PocketRun 面板预览在主屏上的尺寸。

<details>
<summary>创建细节：像素格式、兼容 flags 与最小 Context</summary>

`VirtualDisplaySession.kt` 固定使用：

```text
width       720
height      1280
densityDpi  320
format      RGBA_8888
maxImages   4
```

后端尝试五组兼容 flags，所有组都保留 `OWN_CONTENT_ONLY`。没有采用允许默认屏镜像的最后降级方式，防止下一章读取帧时意外得到主屏内容。

后端是 `adbd` 直接启动的裸 `app_process`，不是由 ActivityManager 创建的普通 App 进程。公开 `DisplayManager` 对象通常来自完整 Android Context。

在已验收的 API 36 路径中，[createVirtualDisplay()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/VirtualDisplaySession.kt#L230) 直接取得 `DisplayManagerGlobal`，只传入报告为 `com.android.shell` 的最小 Context，调用 `createVirtualDisplay(Context, ..., VirtualDisplayConfig, ...)`。这个 Context 不会改变进程 UID；隐藏接口的签名仍要按目标系统版本核对。

API 36 的真实设备路径已经验证；其他 Android / OEM 版本仍要逐台验证，而不是仅凭编译通过宣称兼容。无法创建安全显示时返回失败，不回落到主屏镜像。

</details>

### 启动固定 Demo

拿到 ID 后，[VirtualDisplaySession.launchDemo()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/VirtualDisplaySession.kt#L249) 用固定参数数组执行：

```text
/system/bin/am start -W --display <displayId> \
  -n io.github.kkkhs.pocketrun/.DisplayDemoActivity
```

`--display` 告诉 Android 把 Activity 放到刚创建的逻辑显示。**同一次 `createDemoDisplay()` 调用内部先取 ID、再启动 Demo，启动成功后才把 ID 返回 App**，不是由 React 另发一条任意启动命令。App Kotlin 将它包装成 `{active, displayId}`，Promise 完成后页面显示结果。

[DisplayDemoActivity.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayDemoActivity.kt) 是没有 Launcher 图标、没有业务数据的原生页面。第 4 章快照只显示 `PocketRun Virtual Display Demo`、当前 `displayId` 和固定测试标识；输入框是第 5 章增加的。

TypeScript 不能提供 package、component 或命令字符串。先用确定目标验证显示路由，不把“创建了 VirtualDisplay”描述成“获准操作任意 App”。Demo 发现自己落到默认屏 0 时会立即结束，避免释放过程中短暂迁回用户主屏。

### 核对显示与 task

“检查虚拟屏”经 [PrivilegedBackendController.displayStatus()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt#L134) 读取后端 `getDisplayId()`。这能确认当前会话编号，没有独立确认 Demo 归属。

本章真实设备测试再从系统检查两件事：

1. DisplayManager 能查到返回的正数 ID。
2. `DisplayDemoActivity` 的 task 位于系统记录的 `Display #<id>`。

两者同时成立，才证明“显示存在，Demo 路由正确”。它们仍不能证明“已经看见画面”或“能够输入”。

### 释放并作废旧 ID

“释放虚拟屏”经 [PrivilegedBackendController.releaseDisplay()](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendController.kt#L141) 发出 Binder `releaseDisplay()`。后端释放 VirtualDisplay、关闭 ImageReader，让旧编号不再作为有效会话返回。

第 4 章快照的清理顺序是 `VirtualDisplay.release() → ImageReader.close() → 保存 ID 改为 -1`；当前 `main` 的 `VirtualDisplaySession.close()` 已扩展为先使会话失效，再清理第 5 章新增的查树与帧资源。两者不要混读成第 4 章已经实现查树。

后端 shutdown、App client Binder 死亡也会走清理路径；后端进程死亡时系统回收其显示资源。Android 移除该显示上的 task/window，Demo 不允许回落到默认屏。重新创建时要使用新返回的有效 ID，不能复用旧状态。

<figure style={{margin: '1.25rem 0', maxWidth: 360}}>
  <figcaption><strong>释放后的状态</strong></figcaption>
  <img src={releasedScreenshot} alt="虚拟屏释放后，创建按钮重新可用，旧 displayId 失效" width="360" />
</figure>

## 对照调用边界

第 4 章在 [IPrivilegedBackend.aidl](https://github.com/kkkhs/pocketrun/blob/chapter-4/app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl) 中增加三个方法：

```aidl
int createDemoDisplay();
int getDisplayId();
void releaseDisplay();
```

仍没有任意 shell 命令，也不把 Surface、ImageReader 或 Binder 句柄传给 TypeScript。

```text
DisplayPanel.tsx                         按钮和结果
  ↓ TurboModule（约定在 NativePocketRunAdb.ts）
AdbModule.kt                             App Kotlin 原生入口
  ↓ 普通 Kotlin 调用
PrivilegedBackendController.kt           App 侧 Binder proxy
  ↓ Binder（约定在 IPrivilegedBackend.aidl）
PrivilegedBackendMain.kt                 后端 Binder Stub
  ↓ 普通 Kotlin 调用
VirtualDisplaySession.kt                 显示资源所有者
```

Spec 和 AIDL 是调用约定，不是又增加了两个执行阶段。上面的代码链接用于定位当前文件；本章的能力和历史结果以 `chapter-4` 快照为准。

## 验证显示与清理

<details>
<summary>实践命令：源码检查、APK 构建与设备验收</summary>

在 `app/` 运行：

```bash
npm run typecheck
npm run lint
npm test -- --runInBand
npm run audit:ci

cd android
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

本章快照的专用模拟器测试命令与[第 3 章](chapter-3.md)相同；先核对属于本次演示的 AVD，再安装，不在未选中或未授权设备执行。

测试额外验证系统 DisplayManager 能查到返回 ID、Demo task 位于 `Display #<id>`、显式释放后显示和 task 消失、再次创建成功，以及 kill 后端后 Binder death、VirtualDisplay 与 task 清理完成，没有遗留 `pocketrun:backend` 进程。

</details>

2026-11-18 的第 4 章结果：

- 12 个 React UI 测试、ADB transport 测试与 App JVM 命令测试通过。
- AIDL / Codegen 与 ARM64 Debug、Release、AndroidTest APK 构建通过。
- 专用 Android 16 / API 36 模拟器真实设备测试为 `OK (2 tests)`。
- 产品 UI 完成“ADB 连接 → shell 后端 → Demo VirtualDisplay → 系统 task/display 交叉核对 → 释放 → shutdown”。
- 释放和后端死亡后都没有遗留显示、Demo task 或 shell 后端。
- `audit:ci` 阻止所有未豁免 high / critical 公告；picomatch 的精确临时例外及 2026-12-18 截止日期记录在第 0 章，已有 moderate 条目继续如实保留。

这些数字对应第 4 章快照，不随 `main` 的后续扩展更新。编译与 mock 通过不能替代系统显示和 task 的真实验证；这台模拟器上的结果也不代表所有品牌设备都已支持。

## 带着显示进入下一章

本章只证明 **显示存在、Demo 路由正确、资源能清理**。第 5 章继续从同一个 ImageReader 读取帧，围绕同一 `displayId` 加入 UI 树、定向输入和结果确认。

练习：解释为什么“创建了 VirtualDisplay”“Demo task 位于该 displayId”“已经看到虚拟屏画面”是三个不同的验收条件。

参考：[VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay)、[DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[ImageReader](https://developer.android.com/reference/android/media/ImageReader)、[多显示支持](https://source.android.com/docs/core/display/multi_display)。

## 本章源码

- [PR #7：VirtualDisplay、Demo 路由与第 4 章](https://github.com/kkkhs/pocketrun/pull/7)
- [PR #10：前序章节的任务主线与图解](https://github.com/kkkhs/pocketrun/pull/10)
- [Release `chapter-4`：本章结束时的源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-4)
