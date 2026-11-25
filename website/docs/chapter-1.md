---
sidebar_position: 1
sidebar_label: 第 1 章 · React 调用 Kotlin
title: 第 1 章：让界面调用 Kotlin
description: 写下第一个 React Native TurboModule，把 Android 系统信息和当前 App UID 送回 TypeScript 界面。
---

import deviceScreenshot from './assets/chapter-1-device.png';
import beforeScreenshot from './assets/chapter-1-before.png';
import NativeCallFlow from './assets/native-call-flow.svg';
import NativeModuleLookup from './assets/native-module-lookup.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

写 React 的时候，浏览器或 React Native 已经帮我们把渲染和状态管理包圆了。但要做一个真正能操作手机的 Android AI 助手，前端 JavaScript 迟早得跨过那道墙——去调 Android 原生 API、管套接字、拉子进程、读文件系统。

这一章我们先迈出最小、也最关键的一步：**在页面上放一个“检查设备”按钮，点击后调用 Kotlin 读取手机的 Android 版本、API Level、支持的 ABI 和当前进程的 App UID，并把结果渲染回 React 界面。**

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0'}}>
  <figure style={{margin: 0, flex: '0 1 280px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>点击前</strong></figcaption>
    <img src={beforeScreenshot} alt="点击前：按钮下方显示尚未读取设备信息" width="280" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 280px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>点击后</strong></figcaption>
    <img src={deviceScreenshot} alt="点击后：显示 Android 16、API 36、arm64-v8a 和 App UID" width="280" />
  </figure>
</div>

别小看这四个字段——尤其是最后那个 **App UID**（图里是 `10150`，每台设备安装时会动态分配）。你会在这里建立整套教程的第一个核心认知：**JavaScript 调进了 Kotlin，确实跨越了语言边界，但依然关在同一个普通 App 进程和同一个沙箱 UID 里，权限一点都没变高。**

## 一次点击背后发生了什么

对前端开发者来说，你可以把 React Native 新架构里的 **TurboModule** 类比成一个**带自动类型生成的本地 RPC**：你在 TypeScript 里声明接口签名，构建工具自动生成 Kotlin 抽象类，运行时 JS 通过名字拿到原生模块的代理，像调普通异步函数一样 `await` 结果。

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="一次按钮调用与结果返回的顺序">
    <NativeCallFlow className={`pr-native-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-native-title" aria-describedby="pr-native-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>按钮点击后的运行时调用链：TypeScript 发起异步调用，Kotlin 在同一 App 进程内读取系统信息，通过 Promise 返回给 React。</figcaption>
</figure>

整个过程都在**同一个 App 进程**内完成，没有跨进程通信（Binder），也没有额外后端服务。整个第 1 章涉及的核心代码只有 5 个文件：

```text
app/
├── App.tsx                         # React 页面：按钮、加载态与结果展示
├── specs/NativePocketRunDevice.ts  # TurboModule 接口约定（Codegen 输入）
└── android/app/src/main/java/io/github/kkkhs/pocketrun/
    ├── DeviceInfoModule.kt         # Kotlin 原生实现：读取 Build 与 Process.myUid()
    ├── DeviceInfoPackage.kt        # 模块目录：把模块名映射到 Kotlin 实例
    └── MainApplication.kt          # 应用入口：把 Package 注册进 ReactHost
```

我们按“写约定 → 写实现 → 做注册 → 前端调用”的顺序把这 5 个文件串一遍。

## 第一步：用 TypeScript 写好调用约定

先告诉 React Native“原生侧要暴露什么方法、返回什么结构”。打开 [NativePocketRunDevice.ts](https://github.com/kkkhs/pocketrun/blob/chapter-1/app/specs/NativePocketRunDevice.ts)：

```ts title="app/specs/NativePocketRunDevice.ts"
import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type DeviceInfo = {
  androidVersion: string;
  apiLevel: number;
  supportedAbis: Array<string>;
  appUid: number;
};

export interface Spec extends TurboModule {
  getDeviceInfo(): Promise<DeviceInfo>;
}

export default TurboModuleRegistry.get<Spec>('PocketRunDevice');
```

这段代码其实分管**两个不同阶段**的事：

1. **构建期（给 Codegen 看）**：Android 不会在运行时解析 `.ts` 文件。执行 Gradle 构建时，React Native Codegen 会根据 [app/package.json](https://github.com/kkkhs/pocketrun/blob/chapter-1/app/package.json) 里的 `codegenConfig`，扫描 `specs/` 目录，自动生成 C++ 胶水代码和 Kotlin/Java 抽象基类 `NativePocketRunDeviceSpec`（产物落在 `build/` 目录，不手改也不提交）。
2. **运行期（给 JS 用）**：最后一行的 `TurboModuleRegistry.get<Spec>('PocketRunDevice')` 会在运行时按名字向原生侧查找模块，返回一个可调用的代理对象。

:::tip 为什么用 `get()` 而不是 `getEnforcing()`？
`TurboModuleRegistry.getEnforcing()` 在找不到原生模块时会直接抛异常让整个模块加载崩溃；而 `get()` 会温柔地返回 `null`，方便我们在 `App.tsx` 里捕获并展示一句友好的中文提示（“原生模块未注册，请重新构建 Android 应用”），也方便 Jest 单元测试。
:::

## 第二步：用 Kotlin 实现原生逻辑

有了 Codegen 生成的基类 `NativePocketRunDeviceSpec`，我们在 [DeviceInfoModule.kt](https://github.com/kkkhs/pocketrun/blob/chapter-1/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DeviceInfoModule.kt) 里继承它并实现 `getDeviceInfo`：

```kotlin title="DeviceInfoModule.kt"
@ReactModule(name = DeviceInfoModule.NAME)
class DeviceInfoModule(reactContext: ReactApplicationContext) :
    NativePocketRunDeviceSpec(reactContext) {

  override fun getName() = NAME

  override fun getDeviceInfo(promise: Promise) {
    try {
      val abis = Arguments.createArray()
      Build.SUPPORTED_ABIS.forEach { abis.pushString(it) }

      val result = Arguments.createMap().apply {
        putString("androidVersion", Build.VERSION.RELEASE)
        putInt("apiLevel", Build.VERSION.SDK_INT)
        putArray("supportedAbis", abis)
        putInt("appUid", Process.myUid())
      }
      promise.resolve(result)
    } catch (error: Exception) {
      promise.reject("E_DEVICE_INFO", "读取设备信息失败。", error)
    }
  }

  companion object {
    const val NAME = "PocketRunDevice"
  }
}
```

这里有三个值得注意的细节：
- Kotlin 不能直接把原生 `Build` 对象或普通 `Map` 扔给 JavaScript，必须用 React Native 提供的 `Arguments.createMap()` 和 `Arguments.createArray()` 打包成可跨 JSI 边界传递的数据结构。
- TypeScript 里的 `Promise<DeviceInfo>`，在 Kotlin 签名里会自动变成一个末尾参数 `promise: Promise`——调用 `promise.resolve(result)` 或 `promise.reject(...)` 来结算前端的 `await`。
- `Process.myUid()` 返回的就是操作系统分配给当前 App 进程的 Linux 用户 ID（通常在 `10000` 以上）。后面整套教程都会反复盯住这个数字：一旦涉及系统级操作，我们会需要 **UID 2000（shell）**，而普通 App 里的任何 Kotlin 代码永远都是这个普通 App UID。

## 第三步：把模块登记进 React Native

写完 Kotlin 类还不算完——React Native 启动时并不知道 `PocketRunDevice` 这个名字对应哪个类。我们需要两步登记：

1. 在 [DeviceInfoPackage.kt](https://github.com/kkkhs/pocketrun/blob/chapter-1/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DeviceInfoPackage.kt) 里充当“模块通讯录”：通过 `getReactModuleInfoProvider()` 声明自己提供了名为 `PocketRunDevice` 的 TurboModule，再在 `getModule(name, reactContext)` 被调用时返回 `DeviceInfoModule` 实例（后续章节的新增模块也会统一注册在这里）。
2. 在 [MainApplication.kt](https://github.com/kkkhs/pocketrun/blob/chapter-1/app/android/app/src/main/java/io/github/kkkhs/pocketrun/MainApplication.kt) 创建 ReactHost 时，把 `DeviceInfoPackage()` 加进 `PackageList(this).packages`。

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="PocketRunDevice 查找名与原生实现的对应关系">
    <NativeModuleLookup className={`pr-lookup-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-lookup-title" aria-describedby="pr-lookup-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>按名称查找模块的完整闭环：TS 查找名、Package 登记名、Kotlin 模块名三处必须完全一致。</figcaption>
</figure>

初学者最容易搞混这几个名字，记住这张对照表就再也不会踩坑：

| 名称 | 角色 | 是否必须一致 |
| --- | --- | --- |
| `PocketRunDevice` | 运行时查找键（TS `Registry.get`、Package 登记、Module `NAME`） | **三处必须严格一致** |
| `DeviceInfoModule` / `DeviceInfoPackage` | Kotlin 实现类与注册类名称 | 自己维护即可 |
| `NativePocketRunDeviceSpec` | Codegen 根据 TS 文件名自动生成的抽象基类 | 由工具生成 |
| `PocketRunSpecs` | `package.json` 里的 Codegen 库名，用于组织生成产物 | 不是模块查找键 |

## 第四步：在 React 页面里调用它

回到最熟悉的 [App.tsx](https://github.com/kkkhs/pocketrun/blob/chapter-1/app/App.tsx)，调原生方法和调一个普通 `fetch` 几乎没有区别：

```tsx title="app/App.tsx（核心片段）"
import NativePocketRunDevice, { type DeviceInfo } from './specs/NativePocketRunDevice';

async function readDeviceInfo() {
  if (loading) return;
  setLoading(true);
  setError(null);
  try {
    if (!NativePocketRunDevice) {
      throw new Error('原生模块未注册，请重新构建 Android 应用。');
    }
    setInfo(await NativePocketRunDevice.getDeviceInfo());
  } catch (failure) {
    setInfo(null);
    setError(failure instanceof Error ? failure.message : '读取失败，请重试。');
  } finally {
    setLoading(false);
  }
}
```

:::warning 改完原生代码，刷新 Metro 没用！
如果你只改了 `App.tsx`，热重载立刻生效；但只要改了 `specs/*.ts` 或任何 Kotlin/C++ 代码，就必须重新跑 Gradle 编译并重新安装 APK，否则手机里跑的还是旧版二进制。
:::

## 动手跑一遍

如果你只是先通读原理，可以直接跳到下一章；如果想在自己的模拟器上亲手构建并验证，按下面的步骤操作即可。

<details>
<summary>首次准备：安装 JDK 17、Android SDK 与专用 ARM64 模拟器</summary>

PocketRun 的 `app/` 与 `website/` 是两个独立的 npm 工程，依赖互不干扰。核心工具链版本如下：

| 工具 | 推荐版本 |
| --- | --- |
| Node / React Native CLI | 24.18.0 / 20.2.0 |
| React Native / React | 0.88.1 / 19.3.0 |
| JDK | 完整 JDK 17（例如 Temurin 17，确认 `javac -version` 可用） |
| Android SDK | Platform 37.0、Build Tools 37.0.0 & 36.0.0、NDK 27.1.12297006、CMake 3.22.1 |
| 演示模拟器 | Android 16（API 36）ARM64 AVD |

1. 安装 Node 24.18.0 和[完整 JDK 17](https://adoptium.net/temurin/releases/?version=17)（注意：只有 JRE 能跑 `java`，但没有编译器 `javac`，Gradle 会直接报错）。
2. 从 [Android 官方页面](https://developer.android.com/studio#command-tools) 下载 Command-line Tools，解压到 `$ANDROID_HOME/cmdline-tools/23.0/`。
3. 配置环境变量并安装所需 SDK 组件（以 macOS 为例）：

```bash
nvm use
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/cmdline-tools/23.0/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

javac -version
sdkmanager --sdk_root="$ANDROID_HOME" \
  'platform-tools' 'platforms;android-37.0' \
  'build-tools;37.0.0' 'build-tools;36.0.0' \
  'ndk;27.1.12297006' 'cmake;3.22.1' \
  'emulator' 'system-images;android-36;default;arm64-v8a'
```

4. 创建一个专用的演示模拟器（操作前先用 `emulator -list-avds` 确认不重名）：

```bash
avdmanager create avd --name pocketrun-ch1-api36 \
  --package 'system-images;android-36;default;arm64-v8a' \
  --device pixel_7
```

5. 安装 `app/` 的前端依赖：

```bash
cd app
npm ci --registry=https://registry.npmjs.org
```

</details>

构建并安装 Debug APK（确保 `SERIAL` 替换成你自己的模拟器序列号）：

```bash
# 1. 代码检查与 ARM64 Debug 构建（在 app/ 目录）
npm run typecheck
npm run lint
npm test -- --runInBand
cd android
./gradlew :app:assembleDebug -PreactNativeArchitectures=arm64-v8a --max-workers=2

# 2. 启动模拟器、确认身份并安装（当前 main 的 Debug 包已内置 JS bundle，无需另起 Metro）
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -n io.github.kkkhs.pocketrun/.MainActivity
```

点下界面里的“检查设备”，你可以顺手用主机 `adb` 做个交叉核对，看看 Kotlin 读到的值是不是和系统完全一致：

```bash
adb -s "$SERIAL" shell getprop ro.build.version.release
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell run-as io.github.kkkhs.pocketrun id -u
```

:::info 踩坑提示：为什么 Spec 文件不能叫 `NativeDeviceInfo.ts`？
早期开发时我们把接口文件起名 `NativeDeviceInfo.ts`，结果 TypeScript 检查全过，C++ 编译却报符号冲突——因为 React Native 内部已经有同名的生成类了。改名成带项目前缀的 `NativePocketRunDevice.ts` 立刻解决。给 TurboModule 起名时，**统一加业务前缀**能省下很多莫名其妙的编译排查时间。
:::

## 本章小结与源码

这一章我们打通了最基础的一条路：**React TypeScript → TurboModule → Kotlin**，并且亲眼看到了 App 的普通沙箱身份（例如 UID `10150`）。

既然普通 App 调一万次 Kotlin 也变不成 shell，那在**不 Root 手机**的前提下，App 怎样才能合法地拿到操作其他 App 所需的 **shell UID 2000** 权限？下一章，我们让 PocketRun 自己去连同一台手机上的无线 ADB。

- **本章源码快照**：[Release `chapter-1`](https://github.com/kkkhs/pocketrun/releases/tag/chapter-1) · [PR #2](https://github.com/kkkhs/pocketrun/pull/2) / [PR #3](https://github.com/kkkhs/pocketrun/pull/3) / [PR #4](https://github.com/kkkhs/pocketrun/pull/4)
- **延伸参考**：[React Native TurboModules 官方文档](https://reactnative.dev/docs/turbo-native-modules-introduction) · [实机验收与工程档案](verification-records.md)
