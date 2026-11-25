---
sidebar_position: 5
sidebar_label: 第 5 章 · 看见并操作虚拟屏
title: 第 5 章：看见界面，再精准操作它
description: 打通 AI 操作手机的完整闭环：共享内存传截图、UiAutomation 抓控件树、计算点击坐标、注入真实触摸与按键事件，最后重新观察确认结果。
---

import observeScreenshot from './assets/chapter-5-observe.png';
import afterInputScreenshot from './assets/chapter-5-after-input.png';
import TaskExecutionLayers from './assets/task-execution-layers.svg';
import ObserveActFlow from './assets/observe-act-flow.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

上一章我们把 `DisplayDemoActivity` 扔进了看不见的虚拟屏里，但现在的我们还是“睁眼瞎”——既看不到里面画了什么，也不知道输入框在哪，更点不到它。

这一章，我们要把 Agent 操作手机的完整动作闭环打通：**看见虚拟屏里的界面 → 找到那个输入框并算出坐标 → 点击获焦、敲入 `PocketRun_Ditto_5` → 再次观察，确认文字真的填进去了。**

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0', alignItems: 'flex-start'}}>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>操作前：拿到虚拟屏截图与控件树</strong></figcaption>
    <img src={observeScreenshot} alt="PocketRun 面板显示虚拟屏截图和 available UI 树，输入框尚未填字" width="360" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 360px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>操作后：再次观察，输入框与结果行同步更新</strong></figcaption>
    <img src={afterInputScreenshot} alt="虚拟屏的输入框和结果 TextView 都显示 PocketRun_Ditto_5，UI 树状态为 available" width="360" />
  </figure>
</div>

注意看左右两张图的区别：左图输入框里的浅色文字只是 `hint`（占位提示），下面的 `input:` 还是空的；右图里不仅输入框出现了真实文字，下方同步展示的 `input: PocketRun_Ditto_5` 也亮了出来。

## 把两张图分开看：分层架构 vs 时间顺序

理解这一章最快的方法，是把“**谁在执行**”和“**先做什么、再做什么**”拆开看：

<figure style={{margin: '1.25rem 0', maxWidth: 800}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="四层执行职责与两个 PocketRun 进程">
    <TaskExecutionLayers className={`pr-layers-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-layers-title" aria-describedby="pr-layers-desc" style={{display: 'block', width: '100%', minWidth: 640, height: 'auto'}} />
  </div>
  <figcaption>分层视角：React 与 App Kotlin 仍在普通 App 进程，只有 shell 后端是独立 UID 2000 进程；所有虚拟屏操作统一通过 AIDL 穿越进程边界。</figcaption>
</figure>

<figure style={{margin: '1.25rem 0', maxWidth: 800}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="填写输入框任务的阶段顺序">
    <ObserveActFlow className={`pr-oa-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-oa-title" aria-describedby="pr-oa-desc" style={{display: 'block', width: '100%', minWidth: 640, height: 'auto'}} />
  </div>
  <figcaption>时序视角：任何一次可靠的 GUI 操作都遵循“观察 → 定位 → 操作 → 再次观察核对”的四步循环。</figcaption>
</figure>

为了承接这四个动作，[IPrivilegedBackend.aidl](https://github.com/kkkhs/pocketrun/blob/chapter-5/app/android/app/src/main/aidl/io/github/kkkhs/pocketrun/IPrivilegedBackend.aidl) 顺理成章地多了四个方法——注意**每一个方法的第一个参数都是 `expectedDisplayId`**：

```java title="IPrivilegedBackend.aidl（第 5 章新增）"
SharedMemory captureScreenshot(int expectedDisplayId);
byte[]       dumpUiTree(int expectedDisplayId);
boolean      injectTap(int expectedDisplayId, int x, int y);
boolean      injectText(int expectedDisplayId, String text);
```

接下来，我们顺着 **看 → 找 → 点/打字 → 核对** 四步，看看每一环是怎么在底层落地的。

## 第一步：两双眼睛——截图与 UI 树

为什么既要截图、又要 UI 树？**截图让你（以及未来的视觉模型）看见页面长什么样，UI 树则直接给出每个控件的精确类型、文本、状态和矩形坐标**——查树走的是 Android 无障碍系统接口，完全不需要拿截图去做 OCR。

两条数据路径各自独立，但都牢牢绑定在同一个 `displayId` 上：

### 路径 A：把虚拟屏的像素变成 React 里的图片

1. **取帧与去填充（`VirtualDisplaySession.kt`）**：第 4 章挂在虚拟屏上的 `ImageReader` 一直在后台通过 `HandlerThread` 接收最新渲染帧（旧帧随手 `close()` 避免耗尽缓冲区）。截图时，代码读取像素平面时**不会直接把整块内存当成紧凑位图**——GPU 渲染出来的行尾往往带有内存对齐填充（`rowStride > width * pixelStride`），所以代码严格按 `cropRect`、`rowStride`、`pixelStride` 逐像素提取出真正的 720×1280 图像，再压缩成 90 质量、上限 2 MiB 的 JPEG。
2. **用 `SharedMemory` 跨进程传图**：Android 的 Binder 事务缓冲区总额只有 1 MiB 左右，直接把几百 KB 的图片塞进 `byte[]` 返回非常容易触发 `TransactionTooLargeException`。PocketRun 采用 `ScreenshotCodec` 封一个 32 字节大端头（含魔数 `0x50525353`、`displayId`、宽高、帧时间戳、JPEG 长度）+ JPEG 正文，装进**只读 `SharedMemory`** 通过 Binder 传递文件描述符。
3. **写进私有缓存交给 React**：App 侧收到 `SharedMemory` 后校验头部和 `displayId`，映射拷贝成 App 私有缓存文件（权限 `0600`），返回一个 `file://` URI 给 React 的 `<Image>` 渲染。

:::tip 一个容易漏掉的 FD 泄漏坑
通过 Binder 返回 `SharedMemory` 时，内核会自动把文件描述符复制一份写进回复 Parcel——如果后端不手动关闭自己手里那份原件，每截一张图后端就会泄漏一个 FD！在 [PrivilegedBackendMain.kt](https://github.com/kkkhs/pocketrun/blob/chapter-5/app/android/app/src/main/java/io/github/kkkhs/pocketrun/PrivilegedBackendMain.kt) 的 `onTransact()` 里，我们用 `ThreadLocal` 暂存这次返回的 `SharedMemory`，等 `super.onTransact()` 把 FD 写进回复后，立刻在 `finally` 里把它关闭。
:::

### 路径 B：用 UiAutomation 抓取目标屏的控件树

打开 [DisplayAutomation.kt](https://github.com/kkkhs/pocketrun/blob/chapter-5/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayAutomation.kt)，它的核心思路非常干净：

1. 在独立工作线程里建立 `UiAutomation` 连接，并显式传入 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，确保不会把用户手机上原本开着的无障碍服务干掉。
2. 调用 `getWindowsOnAllDisplays()`，**只遍历 `SparseArray[displayId]` 下的窗口**——其他屏幕（尤其是主屏 `0`）的窗口只做 `recycle()`，碰都不碰。
3. 递归提取每个节点的 `className`、`viewId`、`text`、`contentDescription`、`bounds` 以及 `clickable / editable / enabled / focused` 标志，但**全部加了硬上限**：最多 **512 个节点、深度 32、单字段 256 字节、总计 256 KiB**，超出就标记 `truncated = true` 优雅截断，防止一个巨型列表把 Binder 撑爆。
4. 整个查询有 **1800 ms 截止时间**，而且**不在持有会话锁的情况下等待查询**——就算某次系统无障碍卡慢，用户立刻点“释放虚拟屏”也不会被死锁卡住。

## 第二步：在 TypeScript 里算出点击坐标

App Kotlin 把二进制控件树解码成 JSON 返回给 [DisplayPanel.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-5/app/DisplayPanel.tsx)。定位输入框完全可以在前端纯计算完成，不需要跨进程：

```ts title="定位唯一可编辑输入框并计算中心点"
// 筛选出 contentDescription 为“演示输入框”、可编辑且启用的节点
const target = {
  contentDescription: '演示输入框',
  flags: { editable: true, enabled: true },
  bounds: { left: 64, top: 700, right: 656, bottom: 780 },
};

const x = Math.floor((target.bounds.left + target.bounds.right) / 2); // 360
const y = Math.floor((target.bounds.top + target.bounds.bottom) / 2); // 740
```

只有当树状态是 `available`、未截断、恰好命中唯一目标且坐标落在 `720 × 1280` 范围内时，页面才点亮点击和输入按钮。记住：**`(360, 740)` 是虚拟屏里的像素坐标**，跟手机主屏上预览图被缩放到多大毫无关系。

## 第三步：注入真实的触摸与按键事件

为什么不直接去调 `EditText.setText()`？因为对于未来的真实第三方 App，你根本拿不到它的 View 对象！我们必须走**真正的系统输入分发链路**，让目标 App 像收到真人手指点击和键盘敲击一样做出反应。

来看看 [DisplayInput.kt](https://github.com/kkkhs/pocketrun/blob/chapter-5/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DisplayInput.kt) 是怎么把点击和文字变成系统事件的：

```kotlin title="DisplayInput.kt（定向绑定 displayId 与注入核心）"
// 1. 构造触摸按下与抬起事件
val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
val up   = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
down.source = InputDevice.SOURCE_TOUCHSCREEN
up.source   = InputDevice.SOURCE_TOUCHSCREEN

// 2. 先给每个事件绑定 displayId 并回读校验，没绑定成功绝不发送
if (!injector.bind(down, displayId) || !injector.bind(up, displayId)) return false

// 3. 发送 DOWN 后无论成功与否都尝试发 UP，避免在目标屏留下“手指一直按着不放”的卡死状态
val downAccepted = runCatching { injector.inject(down) }.getOrDefault(false)
val upAccepted   = runCatching { injector.inject(up) }.getOrDefault(false)
```

文字输入则是用 `KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())` 把字符串（当前限定 1—64 个可打印 ASCII 字符）拆成一串真实的 `KeyEvent`（包括 Shift 按下/抬起等修饰键），逐个绑定 `displayId` 后发送。

真正调用 `IInputManager` 时，藏着一个极容易踩的 Android 隐藏 API 大坑：

```kotlin title="DisplayInput.kt（注意第三个参数是 appUid！）"
// 关键：第三个参数是目标 App UID，绝对不是 displayId！
// displayId 必须提前通过 event.setDisplayId(displayId) 写在事件对象本身上。
val result = if (targeted) {
    inject.invoke(service, event, WAIT_FOR_RESULT, appUid)
} else {
    inject.invoke(service, event, WAIT_FOR_RESULT)
}
```

如果系统支持 `injectInputEventToTarget`，这还给了我们双重保险：事件既锁死了只去目标虚拟屏（`event.setDisplayId`），又锁死了只投递给目标包 UID（`appUid`）。

## 第四步：不要轻信返回值，再看一眼！

调用 `injectText()` 返回 `true`，只代表 Android 输入子系统接受了这一串按键事件——**它并不保证输入框当时真的有焦点、没被弹窗挡住、文字真的填进去了**。

所以在真实操作和未来的 Agent 循环里，**操作之后必须再次读取截图和 UI 树**，核对控件的真实 `text` 和界面上的 `input:` 结果行确实变成了 `PocketRun_Ditto_5`。这也是为什么 `DisplayDemoActivity` 专门用 `TextWatcher` 把输入值实时同步到下方结果行——帮我们一眼区分“占位提示 hint”和“真正敲进去的文字”。

## 动手跑一遍

1. 按第 2—4 章完成无线 ADB 连接、启动 shell 后端、创建演示虚拟屏。
2. 在第 5 章面板依次点击：
   - **读取虚拟屏截图** → 看到 Demo 页面；
   - **读取 UI 树** → 状态为 `available`，面板自动定位到输入框中心坐标；
   - **点击演示输入框** → 让输入框获焦；
   - **输入固定 ASCII 文本** → 向虚拟屏敲入 `PocketRun_Ditto_5`；
   - **再次读取截图和 UI 树** → 亲眼确认输入框和下方 `input:` 行都已变成 `PocketRun_Ditto_5`。
3. 实验结束后，依次点 **释放虚拟屏** 和 **停止 shell 后端**，确保显示、线程和截图缓存全部回收。

## 第一部分小结与源码

恭喜！到第 5 章为止，你已经亲手打通了整套教程的**第一条核心通道——Android 屏幕控制通道**：

> **React UI → TurboModule → App Kotlin → 无线 ADB 点火 → UID 2000 独立后端 → Typed Binder → 隔离 VirtualDisplay → 截图 / 控件树 / 定向输入**

接下来进入**第二部分**：如果任务需要读写文件、整理 CSV、跑 Linux 脚本甚至开一个交互终端，我们根本不需要动用 UID 2000 后端。下一章，我们回到普通 App 进程，搭起私有工作目录和第一条命令管道。

- **本章源码快照**：[Release `chapter-5`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-5) · [PR #8](https://github.com/kkkhs/pocketrun/pull/8) / [PR #9](https://github.com/kkkhs/pocketrun/pull/9)
- **延伸参考**：[SharedMemory 文档](https://developer.android.com/reference/android/os/SharedMemory) · [UiAutomation 文档](https://developer.android.com/reference/android/app/UiAutomation) · [实机验收与工程档案](verification-records.md)
