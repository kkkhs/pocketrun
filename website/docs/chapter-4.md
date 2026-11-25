---
sidebar_position: 4
sidebar_label: 第 4 章 · 隔离的虚拟副屏
title: 第 4 章：给 Agent 造一块看不见的虚拟屏
description: 让 UID 2000 后端创建一块无实体面板的 VirtualDisplay，把 Demo 页面定向启动到目标 displayId，并守住绝不降级到主屏的底线。
---

import beforeScreenshot from './assets/chapter-4-before.png';
import activeScreenshot from './assets/chapter-4-active.png';
import releasedScreenshot from './assets/chapter-4-released.png';
import VirtualDisplayFlow from './assets/virtual-display-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

想象一下，你正在手机上回消息，这时让 AI 助手帮你打开另一个 App 查点东西、填个表单。如果 Agent 直接抢过你的主屏幕，页面疯狂跳转、自动点击，你既没法继续用手机，万一碰一下屏幕还会把 Agent 的操作全搅乱。

怎么让 Agent 拥有一块**专属的、不打扰主屏的画布**？Android 系统原生提供了一个极佳的积木：**`VirtualDisplay`（虚拟显示）**。

这一章我们复用第 3 章的 Binder 后端，完成一件干脆利落的事：**创建一块 720 × 1280、没有实体屏幕的逻辑显示器，把固定 Demo 页面定向启动进去，确认它确实跑在那块屏上，最后干净释放。**

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>创建前</strong></figcaption>
    <img src={beforeScreenshot} alt="第 4 章虚拟屏面板，创建按钮可用，检查和释放按钮禁用" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>创建并启动 Demo 后，拿到正数 displayId</strong></figcaption>
    <img src={activeScreenshot} alt="第 4 章虚拟屏面板显示 Demo 已启动到虚拟屏以及有效 displayId" width="360" />
  </figure>
</div>

这一章界面上只会看到一个正数 `displayId`（由系统动态分配，例如 `10`），还看不到虚拟屏里的画面——别急，下一章我们就会把它的画面和控件树都拉出来。

## 先破除一个误解：VirtualDisplay 不是虚拟机

很多开发者第一次听到“虚拟屏”，会下意识以为在手机里又起了一个 Android 虚拟机。完全不是！

把 `VirtualDisplay` 想象成**给手机插了一台没有实体面板的外接显示器**，一下就明白了：它和主屏共用同一个 Android 系统、同一个内核、同一套进程，只是在 `DisplayManager` 里多了一块有独立编号和分辨率的显示区域。

四个核心对象的关系非常简单：

```text
Demo Activity 渲染界面 ──▶ Surface（画布写入端） ◀── VirtualDisplay（逻辑显示器，拥有 displayId）
                                │
                                ▼
                           ImageReader（帧接收端，下一章从中取截图）
```

- **`displayId`**：系统分配的显示器编号。**手机主屏永远是 `0`**，我们创建的虚拟屏则是一个正整数（`> 0`）。
- **`ImageReader`**：负责提供一个 `Surface` 接住这块屏幕画出来的每一帧像素。本章先把它搭起来承接画面，第 5 章再从中取帧编码成 JPEG。
- **资源归属**：`VirtualDisplay` 和 `ImageReader` 全部由 **UID 2000 的 shell 后端进程** 持有。React 界面里保存的只是 `{ active, displayId }` 这个状态快照——在前端清空 state 绝对不会释放底层屏幕。

## 从创建到释放的四步流程

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="创建显示、启动 Demo、核对归属与释放的阶段顺序">
    <VirtualDisplayFlow className={`pr-vd-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-vd-title" aria-describedby="pr-vd-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>所有操作在后端的同一次会话里串联：先拿正数 displayId，再定向拉起 Demo；释放后旧编号立即作废。</figcaption>
</figure>

### 1. 创建一块“绝不镜像主屏”的虚拟屏

在 [IPrivilegedBackend.aidl](https://github.com/kkkhs/pocketrun/blob/chapter-4/app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl) 里，我们只新增三个克制的跨进程方法：

```java title="IPrivilegedBackend.aidl（第 4 章新增）"
int createDemoDisplay();
int getDisplayId();
void releaseDisplay();
```

后端实现位于 [VirtualDisplaySession.kt](https://github.com/kkkhs/pocketrun/blob/chapter-4/app/android/app/src/main/java/io/github/kkkhs/pocketrun/VirtualDisplaySession.kt)。创建虚拟屏时，固定分辨率为 **720 × 1280、320 dpi、RGBA_8888**（这就是后面所有截图和点击坐标的统一坐标系）。

最有意思的一段代码在兼容标志位 `FLAG_MASKS` 的选择上：

```kotlin title="VirtualDisplaySession.kt（只保留 OWN_CONTENT_ONLY 的兼容梯队）"
// 按兼容性从高到低依次尝试 5 组 flags，但全部保留 OWN_CONTENT_ONLY；
// 刻意剔除了“镜像主屏”的兜底组合，避免主屏隐私画面漏进 ImageReader。
private val FLAG_MASKS = intArrayOf(0x1fcc9, 0x1ecc9, 0x1f8c9, 0x1e8c9, 0x160c9)

for (flags in FLAG_MASKS) {
    val created = createVirtualDisplay(reader, flags) ?: continue
    if (created.display.displayId <= 0) {
        created.release()
        continue
    }
    selected = created
    break
}
```

为什么要这么较真？因为如果某组 flags 不带 `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`，Android 可能会把虚拟屏退化成**主屏的镜像**——那样下一章截图时抓到的就不是目标任务，而是用户主屏上的隐私内容。宁可明确报错失败，也绝不退化成主屏镜像。

<details>
<summary>实现细节：裸 app_process 怎样调用 DisplayManagerGlobal？</summary>

和第 3 章调 `ContentProvider` 同理，`app_process` 没有普通 App 的完整 `Context`。因此 `createVirtualDisplay()` 构造了一个只汇报包名为 `com.android.shell` 的最小 `ContextWrapper`，直接反射调用 `DisplayManagerGlobal.getInstance().createVirtualDisplay(context, null, config, null, null)`。外层的 `asShell { Binder.clearCallingIdentity() ... }` 确保系统校验的是后端的 UID 2000，而不是调用方 App UID。

</details>

### 2. 把 Demo Activity 定向投进这块屏

拿到大于 `0` 的 `displayId` 后，同一个 `createDemoDisplay()` 调用紧接着在后端内部执行：

```kotlin title="VirtualDisplaySession.kt（定向启动 Demo）"
listOf(
    "/system/bin/am", "start", "-W",
    "--display", displayId.toString(),
    "-n", "$packageName/.DisplayDemoActivity",
)
```

- **`--display <displayId>`** 告诉 Android 窗口管理器把新启动的 Activity 放到刚建好的逻辑屏上；**`-W`** 等待启动完成。
- **把“建屏 + 启动”合在后端一步完成**，而且目标组件写死为 PocketRun 自己的 `DisplayDemoActivity`（没有桌面图标、不含任何隐私数据），前端不能传任意包名或命令行字符串。
- **防逃逸自检**：`DisplayDemoActivity` 自己也会检查当前所在显示编号——一旦发现系统在释放虚拟屏的过程中试图把它迁回默认主屏 `0`，它会立刻调用 `finish()` 自我结束，绝不突然弹窗吓到用户！

### 3. 释放资源，让旧 ID 彻底作废

点下 **释放虚拟屏** 后，后端依次关闭 `VirtualDisplay` 与 `ImageReader`，并把保存的 `owned` 置空（`getDisplayId()` 重新返回 `-1`）；同时如果后端收到 `shutdown()` 或检测到 App 进程死亡，也会自动走同一套清理流程。

<figure style={{margin: '1.25rem 0', maxWidth: 360}}>
  <figcaption><strong>释放后，旧 displayId 失效</strong></figcaption>
  <img src={releasedScreenshot} alt="虚拟屏释放后，创建按钮重新可用，旧 displayId 失效" width="360" />
</figure>

## 动手跑一遍

1. 确保第 3 章的 **shell 后端** 处于运行状态（ADB 可以断开）。
2. 在第 4 章的虚拟屏面板点击 **创建演示虚拟屏**，看到返回正数 `displayId`。
3. 点击 **检查虚拟屏**，确认返回的编号保持一致。
4. 想眼见为实？用主机 `adb` 查一下系统真实的显示与任务栈（替换 `$SERIAL` 和 `$ID`）：
   ```bash
   adb -s "$SERIAL" shell dumpsys display | grep "DisplayDeviceInfo"
   adb -s "$SERIAL" shell dumpsys activity activities | grep -E "Display #|DisplayDemoActivity"
   ```
   你会看到系统里确实多了一块名为 `PocketRun-Demo` 的逻辑屏，而且 `DisplayDemoActivity` 稳稳地待在 `Display #<id>` 下面，而手机主屏纹丝不动！
5. 最后点击 **释放虚拟屏**，再重新跑一次 `dumpsys`，确认屏幕和任务都已经干净消失。

## 本章小结与源码

这一章我们在后台安静地撑起了一块属于 Agent 的 720×1280 隔离画布，把目标页面放了进去，也立下了**“只认正数 displayId、绝不降级主屏”**的规矩。

现在画面就在这块看不见的屏上渲染着——下一章，我们就给 Agent 装上眼睛和手：**从 `ImageReader` 里把截图拿出来、用 `UiAutomation` 把控件树扒出来，算出输入框坐标，把一串文字真正敲进去！**

- **本章源码快照**：[Release `chapter-4`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-4) · [PR #7](https://github.com/kkkhs/pocketrun/pull/7)
- **延伸参考**：[Android VirtualDisplay 文档](https://developer.android.com/reference/android/hardware/display/VirtualDisplay) · [Android 多屏支持架构](https://source.android.com/docs/core/display/multi_display) · [实机验收与工程档案](verification-records.md)
