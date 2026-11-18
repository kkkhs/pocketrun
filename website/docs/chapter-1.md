---
sidebar_position: 2
sidebar_label: 第 1 章：React 调用 Kotlin
title: 第 1 章：让界面调用 Kotlin
description: 做一个按钮，把 Android 原生信息送回 React 界面。
---

import deviceScreenshot from './assets/chapter-1-device.png';
import beforeScreenshot from './assets/chapter-1-before.png';
import NativeCallFlow from './assets/native-call-flow.svg';
import NativeModuleLookup from './assets/native-module-lookup.svg';
import figureStyles from '@site/src/css/tutorial-figures.module.css';

从一个 React 按钮开始。这一章只做一个任务：**点击“检查设备”，让 Kotlin 读取 Android 版本、API Level、ABI 和当前 App UID，再把结果显示在 React 页面上。**

```text
发起：用户点按钮，React 调原生方法
执行：Kotlin 读取设备信息
返回：Promise 完成，React 显示结果
```

现在不处理 shell 权限。先确认“界面能调用 Android，但调用后仍是普通 App 身份”；下一章才引入需要系统授权的 ADB 入口。

<div style={{display: 'flex', gap: '1.5rem', flexWrap: 'wrap', margin: '1.25rem 0'}}>
  <figure style={{margin: 0, flex: '0 1 280px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>点击前</strong></figcaption>
    <img src={beforeScreenshot} alt="点击前：按钮下方显示尚未读取设备信息" width="280" />
  </figure>
  <figure style={{margin: 0, flex: '0 1 280px', minWidth: 0, maxWidth: '100%'}}>
    <figcaption><strong>调用成功后</strong></figcaption>
    <img src={deviceScreenshot} alt="点击后：显示 Android 16、API 36、arm64-v8a 和 App UID" width="280" />
  </figure>
</div>

两张图来自同一次模拟器验证，均截取内容区域。UID 由 Android 分配，你的设备上不必是 `10150`。

## 看清一次点击

如果已经安装本章 App，先点“检查设备”，确认从“尚未读取”变成四个字段。还没有环境时，可先读下面的原理，再展开安装说明。

**这里有两个执行层，但只有一个 App 进程：** React / TypeScript 管按钮和显示，App Kotlin 读 Android 信息。TurboModule 接通两层，不是跨进程的 Binder，也不会启动 shell 后端。

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="一次按钮调用与结果返回的顺序，可横向查看">
    <NativeCallFlow className={`pr-native-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-native-title" aria-describedby="pr-native-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>这张图只回答点击后的先后顺序。所有步骤仍在同一个 App 进程内；Codegen 不在这条运行链里。</figcaption>
</figure>

React 负责显示和交互，Kotlin 读取系统信息。**调用原生方法不会改变 App 的身份**，也不会授予 shell 或 root 权限。

本章没有 Agent、虚拟屏或额外设备授权。先把最短调用链跑通，再接后续能力。

<details>
<summary>实践准备：安装 App 依赖、JDK、SDK 与模拟器</summary>

代码位于 `app/`，和 `website/` 各用一份 npm 依赖。没有把手机工程混进文档站。

| 工具 | 本章版本 |
| --- | --- |
| Node / React Native CLI | 24.18.0 / 20.2.0 |
| React Native / React | 0.88.1 / 19.3.0 |
| JDK | Temurin 17.0.21，完整 JDK |
| Gradle / Android Gradle Plugin | 9.4.1 / 9.2.1 |
| SDK Platform / targetSdk | 37.0 / 36 |
| Build Tools | 37.0.0；依赖还使用 36.0.0 |
| NDK / CMake | 27.1.12297006 / 3.22.1 |
| 验证设备 | Android 16、API 36、ARM64 模拟器 |

本章已验证 macOS Apple Silicon。SDK Platform 用于编译，模拟器 API 是运行系统，二者不必相同。模板的 `minSdk` 是 24；后续无线 ADB 章节仍以 Android 11 及以上设备为前提。

<details>
<summary>第一次安装 JDK、SDK 和模拟器</summary>

1. 安装 Node 和[完整 JDK 17](https://adoptium.net/temurin/releases/?version=17)。`java` 能运行不代表有编译器，还要确认 `javac -version`。
2. 从 [Android 官方下载页](https://developer.android.com/studio#command-tools)下载匹配系统的 Command-line Tools。本次使用 23.0 的 macOS ARM64 包，校验后放入 SDK 的 `cmdline-tools/23.0/`，保留原有工具。
3. 阅读并接受所需 SDK 许可，再安装本章组件。不要用下载脚本绕过许可确认。

macOS 的终端配置示例：

```bash
# 在 pocketrun 仓库根目录
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

23.0 中的 `sdkmanager` 是兼容入口，会提示转用 Android CLI；本次安装确实通过这个入口完成。

创建自己的模拟器。名称已存在时先查看，不用 `--force` 覆盖：

```bash
emulator -list-avds
avdmanager create avd --name pocketrun-ch1-api36 \
  --package 'system-images;android-36;default;arm64-v8a' \
  --device pixel_7
```

开发环境需要数 GB 下载与解压空间，Gradle 还会产生缓存。先检查剩余空间；本次只清理了自己创建并已校验的临时安装包，没有清理个人文件。

</details>

在已有学习仓库中安装 App 依赖：

```bash
cd app
npm ci --registry=https://registry.npmjs.org
```

<details>
<summary>想自己生成一个空工程？</summary>

在另一个空目录运行固定版本的官方生成器，不覆盖当前仓库：

```bash
npm exec --yes --registry=https://registry.npmjs.org \
  --package=@react-native-community/cli@20.2.0 -- \
  rnc-cli init PocketRun --version 0.88.1 \
  --directory app --package-name io.github.kkkhs.pocketrun \
  --pm npm --skip-install --install-pods false --skip-git-init
```

本章保留 Android 模板，移除了生成的 iOS/Ruby 目录和欢迎页依赖，没有安装 CocoaPods。生成器只给起点，下面的原生模块需要自己加入。

</details>

</details>

## 接好原生方法

关键文件只有这些：

```text
app/
├── App.tsx                         # 按钮与三种界面状态
├── specs/NativePocketRunDevice.ts   # TypeScript 调用约定
└── android/app/src/main/java/io/github/kkkhs/pocketrun/
    ├── DeviceInfoModule.kt          # 读取系统信息
    ├── DeviceInfoPackage.kt         # 提供原生模块
    └── MainApplication.kt           # 注册 Package
```

### 先写调用约定

先约定“按钮能调用什么、得到什么”。[NativePocketRunDevice.ts](https://github.com/kkkhs/pocketrun/blob/main/app/specs/NativePocketRunDevice.ts) 定义返回值和一个异步方法：

```ts
import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

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

这个文件做两件事：`Spec` 描述方法和返回值；最后一行按名字取得原生模块。前端 import 的默认值，就是最后一行的结果。

**Android 不会在点击时读取这个 `.ts` 文件。** 构建时，Codegen 按 [app/package.json](https://github.com/kkkhs/pocketrun/blob/main/app/package.json) 中的 `codegenConfig` 生成 `NativePocketRunDeviceSpec` 等接口；运行时，手机执行编译后的 JavaScript 和原生代码。

生成文件留在 `build/`，不手改、不提交。这里用 `get`：找不到模块时返回 `null`，由页面显示错误；顶层 `getEnforcing` 则会直接抛错。

### 用 Kotlin 读取

[DeviceInfoModule.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DeviceInfoModule.kt) 继承生成的 `NativePocketRunDeviceSpec`，实现 `getDeviceInfo()`。它从 `Build` 取系统版本和 ABI，从 `Process.myUid()` 取**当前 App 进程**的 UID：

```kotlin
val abis = Arguments.createArray()
Build.SUPPORTED_ABIS.forEach { abis.pushString(it) }
val result = Arguments.createMap().apply {
  putString("androidVersion", Build.VERSION.RELEASE)
  putInt("apiLevel", Build.VERSION.SDK_INT)
  putArray("supportedAbis", abis)
  putInt("appUid", Process.myUid())
}
promise.resolve(result)
```

返回值用 RN 的数组和 Map 构造，`promise.resolve(result)` 再交给 JavaScript。失败则由 `promise.reject(...)` 拒绝 Promise；React 不需要拿到 Android `Build` 对象本身。

### 登记模块名字

Kotlin 类写好后，RN 还不知道去哪里找它。[MainApplication.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/MainApplication.kt) 在创建 ReactHost 时加入我们的 Package：

```kotlin
PackageList(this).packages.apply {
  add(DeviceInfoPackage())
}
```

[DeviceInfoPackage.kt](https://github.com/kkkhs/pocketrun/blob/main/app/android/app/src/main/java/io/github/kkkhs/pocketrun/DeviceInfoPackage.kt) 像一份模块目录，向 RN 提供两样东西：

| 方法 | 告诉 RN 什么 |
| --- | --- |
| `getReactModuleInfoProvider()` | 我提供名为 `PocketRunDevice` 的 TurboModule |
| `getModule(name, context)` | 请求这个名字时，返回 `DeviceInfoModule` 实例 |

第 1 章快照中的创建方法很短；后续章节会在这个 Package 中再加入 ADB 模块：

```kotlin
override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == DeviceInfoModule.NAME) DeviceInfoModule(reactContext) else null
```

其中 `DeviceInfoModule.NAME` 的值是 `"PocketRunDevice"`。RN 管理模块的创建和复用，不需要页面每点一次就创建一个 Kotlin 对象。

### 按名字取代理

<figure style={{margin: '1.25rem 0'}}>
  <div style={{overflowX: 'auto'}} tabIndex={0} role="region" aria-label="PocketRunDevice 查找名与原生实现的对应关系，可横向查看">
    <NativeModuleLookup className={`pr-lookup-diagram ${figureStyles.diagram}`} role="img" aria-labelledby="pr-lookup-title" aria-describedby="pr-lookup-desc" style={{display: 'block', width: '100%', minWidth: 820, height: 'auto'}} />
  </div>
  <figcaption>这张图只回答“这个名字怎样对应到实现”。目录和实例复用由 RN 管理，不是每次点击重做的任务阶段。</figcaption>
</figure>

`App.tsx` 的 import 会执行接口文件中的默认导出：

```ts
import NativePocketRunDevice from './specs/NativePocketRunDevice';
// 默认导出执行：TurboModuleRegistry.get<Spec>('PocketRunDevice')
```

RN 根据 `PocketRunDevice` 找到 Package，再取得原生实例。JS 拿到的是**可调用代理**，不是 Kotlin 对象本身，也不是一份 `Spec` 类型。

需要对应的是运行时注册名，不是所有类名、文件名：

| 名称 | 用途 |
| --- | --- |
| `PocketRunDevice` | JS 查找名、Package 登记名、原生模块名，三者必须对应 |
| `DeviceInfoModule` / `DeviceInfoPackage` | Kotlin 类名，分别负责实现和提供模块 |
| `NativePocketRunDeviceSpec` | Codegen 生成的基类，供 Kotlin 继承 |
| `PocketRunSpecs` | Codegen 库名，用于组织生成文件，不是查找键 |

## 点按钮并显示

准备和注册已经完成，点击才进入运行阶段。[App.tsx](https://github.com/kkkhs/pocketrun/blob/main/app/App.tsx) 的按钮调用代理上的 `getDeviceInfo()`，最终进入 Kotlin 的同名方法。`promise.resolve(result)` 会使 JS 的 Promise 完成，然后 React 更新界面：

```ts
if (!NativePocketRunDevice) {
  throw new Error('原生模块未注册，请重新构建 Android 应用。');
}
const info = await NativePocketRunDevice.getDeviceInfo();
setInfo(info);
```

加载时禁用按钮，失败时显示错误，重试成功后清掉旧错误。

若提示模块未注册，先检查 Package 是否加入、注册名是否对应。修改 Spec 或 Kotlin 后要重新构建并安装 APK；只刷新 Metro 不会更新原生代码。

## 核对真实结果

<details>
<summary>实践命令：构建、安装与系统信息对照</summary>

先按前面的环境说明准备自己的专用模拟器。检查源码，再构建 Android：

```bash
# 在 app/
npm run lint
npm run typecheck
npm test -- --runInBand
cd android
./gradlew :app:assembleDebug \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

成功后得到 `app/android/app/build/outputs/apk/debug/app-debug.apk`。这是调试包；模板中的公开调试签名不能用于正式发布。

启动刚才创建的模拟器。下面使用无窗口模式做自动验证，手动体验可以移除 `-no-window`：

```bash
emulator -avd pocketrun-ch1-api36 -port 5556 \
  -no-window -no-snapshot -no-boot-anim -no-audio \
  -camera-back none -camera-front none -gpu swiftshader -cores 2
```

另一个终端在 `app/` 启动 Metro，它负责给调试 App 提供 JavaScript：

```bash
npm start -- --host 127.0.0.1 --port 8088 --no-interactive
```

再开终端，在 `app/` 安装。**先核对 AVD 名称，再执行安装**；序列号必须是你自己的模拟器：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name
adb -s "$SERIAL" reverse tcp:8081 tcp:8088
adb -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -n io.github.kkkhs.pocketrun/.MainActivity
```

调试包默认连接设备端口 8081，`reverse` 将它映射到本机 Metro 的 8088。端口被占用时换端口并同步映射，不停止别人的服务。

点击“检查设备”，把页面结果与下面的输出对照：

```bash
adb -s "$SERIAL" shell getprop ro.build.version.release
adb -s "$SERIAL" shell getprop ro.build.version.sdk
adb -s "$SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$SERIAL" shell run-as io.github.kkkhs.pocketrun id -u
adb -s "$SERIAL" logcat -d -s PocketRunDevice:I '*:S'
```

`run-as` 用于本章可调试的自有 App，不是获取其他应用权限的方法。结束后停止 Metro；用模拟器窗口退出，或对确认属于本次任务的序列号执行 `adb -s "$SERIAL" emu kill`。

</details>

本章已经验证：

- TypeScript、ESLint、三个 UI 测试通过。
- Codegen、Kotlin/Java、C++ 和 ARM64 Debug APK 构建通过。
- Android 16 模拟器中真实点击按钮，四个字段与系统信息、App UID 和原生日志一致。

加载、失败重试和模块缺失由测试中的 mock 验证；真实模拟器验证的是成功调用。两者不互相替代。

<details>
<summary>这次遇到的三个问题</summary>

1. **有 `java`，却没有 `javac`。** 原来只有 JRE，不能用于完整编译；补装 JDK 17。
2. **Gradle 下载超时。** 模板读取超时是 10 秒。本章改为 60 秒，同时固定官方分发包 SHA-256；没有关闭 HTTPS 校验。
3. **TypeScript 通过，C++ 构建仍失败。** 最初的 `NativeDeviceInfo` 与 React Native 内置生成类重名。改成 `NativePocketRunDevice` 后通过，说明 Java 包名不同也不能避免所有生成符号冲突。

模板及部分依赖有 AGP/旧接口弃用提示。本章保留经过验证的版本组合，不据此宣称已兼容未来 Gradle/AGP 版本。

2026-11-16 的 App 依赖审计仍有 9 个 low、6 个 moderate 传播条目，高危门禁通过，但不是零条目：

- deepmerge 对应的[官方 CVE](https://www.cve.org/CVERecord?id=CVE-2026-93753)已被标为 `REJECTED`。
- [minimatch 公告](https://github.com/advisories/GHSA-g3hv-75wg-m249)要求受控 glob 和 `optimizationLevel >= 2`。当前唯一受影响版本的调用来自 TypeScript lint 工具，传入 `{dot: true}`，使用默认级别 1。调用配置变化后需重新评估。

</details>

练习：沿着这五个文件找出按钮、接口、Kotlin 实现和注册位置。下一章将引入 ADB；届时要区分普通 App 身份和 shell 命令的执行身份。

参考：[原生模块](https://reactnative.dev/docs/turbo-native-modules-introduction)、[Codegen](https://reactnative.dev/docs/the-new-architecture/using-codegen)、[Android 开发环境](https://reactnative.dev/docs/set-up-your-environment)。

## 本章代码

- [PR #2：设备信息原生模块与第 1 章](https://github.com/kkkhs/pocketrun/pull/2)
- [PR #3：调用流程图与前后截图](https://github.com/kkkhs/pocketrun/pull/3)
- [PR #4：模块注册、查找与前端联动说明](https://github.com/kkkhs/pocketrun/pull/4)
- [Release `chapter-1`：本章结束时的源码快照](https://github.com/kkkhs/pocketrun/releases/tag/chapter-1)
