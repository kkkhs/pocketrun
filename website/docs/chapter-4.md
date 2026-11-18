---
sidebar_position: 5
sidebar_label: 第 4 章：创建演示虚拟屏
title: 第 4 章：把 Demo App 放进虚拟屏
description: 让 shell 后端创建 ImageReader-backed VirtualDisplay，并把固定 Demo Activity 启动到目标 displayId。
---

import beforeScreenshot from './assets/chapter-4-before.png';
import activeScreenshot from './assets/chapter-4-active.png';
import releasedScreenshot from './assets/chapter-4-released.png';
import VirtualDisplayFlow from './assets/virtual-display-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

这一章第一次真正使用 shell 后端：创建一块 720 × 1280 的 VirtualDisplay，并把固定 Demo Activity 启动到它的 `displayId`。

**主线位置：** Binder shell 后端 → **本章：VirtualDisplay + 目标 displayId** → 下一章：读取这块屏的画面和 UI 树，再向它输入。

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

截图中的 ID 由 Android 动态分配，不要求等于 `10`。真正的通过条件是：ID 大于 0，并且系统确认 Demo Activity 的 task 属于同一个显示。

## 先把四个名词分开

### VirtualDisplay 不是虚拟机

它只是 Android DisplayManager 创建的一块**逻辑显示**：有尺寸、密度和 `displayId`，但没有另一套 Android 内核或系统。

可以把它想成一台没有实体面板的外接显示器。

### Surface 是画面写入位置

Activity 在 VirtualDisplay 上绘制后，像素要写到一个 Surface。本章由 ImageReader 提供 Surface：

```text
Demo Activity → VirtualDisplay → Surface → ImageReader
```

本章只让 ImageReader 承接画面，还不读取像素。第 5 章才会取得帧并显示预览。

### displayId 是后续能力的路由键

创建成功后 Android 返回一个正整数 ID。启动 App、截图、读取 UI 树和输入事件都必须指向同一个 ID。

如果截图用 ID 7、输入却落到默认屏 0，就可能操作错界面。因此后续章节会一直围绕同一份显示状态工作。

### 谁持有 VirtualDisplay

```text
React / TypeScript UI
        ↓ TurboModule + Promise
App Kotlin 原生层（App UID）
        ↓ Binder
shell 后端 Kotlin（UID 2000）
        ↓
VirtualDisplay / ImageReader / Surface
```

React state 只保存 `{active, displayId}` 用于展示。真正的 Android 资源都留在 shell 后端；页面重绘不会复制或拥有它们。

## 一次创建怎么走

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="从 TypeScript 到 shell 后端创建虚拟屏并启动 Demo 的流程图，可横向查看">
    <VirtualDisplayFlow className={`pr-vd-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-vd-title" aria-describedby="pr-vd-desc" style={{display: 'block', width: '100%', minWidth: 720, height: 'auto'}} />
  </div>
  <figcaption>语言层、进程边界和显示资源分别标出；Binder 只是 App Kotlin 与 shell 后端之间的通道。</figcaption>
</figure>

## 先在界面跑一遍

1. 按第 2、3 章连接 ADB 并启动 shell 后端。
2. 点“创建演示虚拟屏”。
3. 结果应显示一个大于 0 的 `displayId`。
4. 点“检查虚拟屏”，ID 应保持不变。
5. 点“释放虚拟屏”，结果应说明旧 ID 已失效。
6. 最后停止 shell 后端。

本章没有显示虚拟屏里的实际画面。界面上的 ID 是状态证据，设备测试还会从系统的 display/task 列表独立核对，不能只信后端自己返回的数字。

## 固定 Demo，而不是先操作任意 App

`DisplayDemoActivity.kt` 是一个没有 Launcher 图标、没有业务数据的原生页面。它只显示：

- `PocketRun Virtual Display Demo`；
- 当前 `displayId`；
- 固定测试标识。

shell 后端只会执行参数数组固定的命令：

```text
/system/bin/am start -W --display <displayId> \
  -n io.github.kkkhs.pocketrun/.DisplayDemoActivity
```

TypeScript 不能提供 package、component 或命令字符串。先用确定的目标验证显示路由，后续再讨论不同 App 的兼容性和授权。

Demo Activity 发现自己落到默认屏 0 时会立即结束。这避免虚拟屏释放过程中，系统把测试页面短暂迁回用户主屏。

## 后端怎样创建显示

`VirtualDisplaySession.kt` 固定使用：

```text
width       720
height      1280
densityDpi  320
format      RGBA_8888
maxImages   4
```

创建顺序：

1. ImageReader 创建 Surface。
2. DisplayManagerGlobal 用 `com.android.shell` 身份创建 VirtualDisplay。
3. 检查返回的 `displayId > 0`。
4. 保存 ImageReader、VirtualDisplay 和 ID。
5. 用固定 `am start --display` 启动 Demo。

后端会尝试五组兼容 flags，所有组都保留 `OWN_CONTENT_ONLY`。没有采用允许默认屏镜像的最后降级方式，防止下一章读取帧时意外得到主屏内容。

<details>
<summary>为什么 DisplayManager 仍需要少量反射？</summary>

后端是 `adbd` 直接启动的裸 `app_process`，不是由 ActivityManager 创建的普通 App 进程。公开 `DisplayManager` 对象通常来自一个完整 Android Context。

这里直接取得 `DisplayManagerGlobal`，只传入报告为 `com.android.shell` 的最小 Context，再调用 Android 11—16 都存在的 `createVirtualDisplay(Context, ..., VirtualDisplayConfig, ...)` 形态。API 36 的真实设备路径已经验证；其他 Android / OEM 版本仍要逐台验证，而不是仅凭编译通过宣称兼容。

</details>

## Typed Binder 增加三种能力

第 4 章在 AIDL 中加入：

```aidl
int createDemoDisplay();
int getDisplayId();
void releaseDisplay();
```

仍然没有任意 shell 命令，也没有把 Surface、ImageReader 或 Binder 句柄传给 TypeScript。

调用链对应文件：

```text
DisplayPanel.tsx                         React / TypeScript UI
    ↓ TurboModule
NativePocketRunAdb.ts                    TS 调用约定
    ↓
AdbModule.kt                             App Kotlin 原生层
    ↓ Binder
PrivilegedBackendController.kt           App 侧 Binder proxy
    ↓ Binder driver
PrivilegedBackendMain.kt                 shell 后端 Binder Stub
    ↓
VirtualDisplaySession.kt                 显示资源所有者
```

## 释放为什么和创建同样重要

`releaseDisplay()` 按顺序执行：

1. `VirtualDisplay.release()`；
2. `ImageReader.close()`；
3. 保存的 ID 改为 `-1`。

后端 shutdown、App client Binder 死亡和后端进程死亡也会走清理路径。Android 系统会移除该显示上的 task/window；Demo Activity 不允许回落到默认屏。

<figure style={{margin: '1.25rem 0', maxWidth: 360}}>
  <figcaption><strong>释放后的状态</strong></figcaption>
  <img src={releasedScreenshot} alt="虚拟屏释放后，创建按钮重新可用，旧 displayId 失效" width="360" />
</figure>

## 构建与真实验证

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

专用模拟器测试命令保持与第 3 章相同。测试额外验证：

- 系统 DisplayManager 能查到返回 ID；
- `DisplayDemoActivity` 的 task 位于 `Display #<id>`；
- 显式释放后系统显示和 Demo task 都消失；
- 再次创建成功；
- kill shell 后端后，Binder death、VirtualDisplay 和 task 都被清理；
- 没有遗留 `pocketrun:backend` 进程。

## 本章验证结果

2026-11-18 的结果：

- 12 个 React UI 测试、ADB transport 测试与 App JVM 命令测试通过。
- AIDL / Codegen 与 ARM64 Debug、Release、AndroidTest APK 构建通过。
- 专用 Android 16 / API 36 模拟器真实设备测试为 `OK (2 tests)`。
- 产品 UI 完成“ADB 连接 → shell 后端 → Demo VirtualDisplay → 系统 task/display 交叉核对 → 释放 → shutdown”。
- 释放和后端死亡后都没有遗留显示、Demo task 或 shell 后端。
- `audit:ci` 阻止所有未豁免 high / critical 公告；picomatch 的精确临时例外及 2026-12-18 截止日期记录在第 0 章，已有 moderate 条目继续如实保留。

本章证明目标显示存在且路由正确。[第 5 章](chapter-5.md)继续从同一个 ImageReader 读取帧，并围绕同一 `displayId` 加入 UI 树、定向输入和结果确认。

练习：解释为什么“创建了 VirtualDisplay”“Demo task 位于该 displayId”“已经看到虚拟屏画面”是三个不同的验收条件。

参考：[VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay)、[DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[ImageReader](https://developer.android.com/reference/android/media/ImageReader)、[多显示支持](https://source.android.com/docs/core/display/multi_display)。

## 本章代码

- [PR #7：VirtualDisplay、Demo 路由与第 4 章](https://github.com/kkkhs/pocketrun/pull/7)
- [Release `chapter-4`：本章结束时的源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-4)
