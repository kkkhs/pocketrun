---
sidebar_position: 4
sidebar_label: 第 1 章：React 调用 Kotlin
title: 第 1 章：让界面调用 Kotlin
description: 做一个按钮，把 Android 原生信息送回 React 界面。
---

import deviceScreenshot from './assets/chapter-1-device.png';

这一章只做一件事：点击“检查设备”，显示 Android 版本、API Level、ABI 和当前 App UID。

<img src={deviceScreenshot} alt="PocketRun 在 Android 模拟器中显示设备信息" width="280" />

这是本章实际运行画面的内容区域。UID 由 Android 分配，你的设备上不必是 `10150`。

## 一次点击怎么走

```text
React 按钮 → TurboModule → Kotlin → Android 系统
    ↑                                    │
    └─────── Promise 返回设备信息 ─────────┘
```

Codegen 在**构建期**根据 TypeScript 接口生成连接代码，不在每次点击时运行。

React 负责显示和交互，Kotlin 读取系统信息。**调用原生方法不会改变 App 的身份**，也不会授予 shell 或 root 权限。

本章没有 Agent、虚拟屏或额外设备授权。先把最短调用链跑通，再接后续能力。

## 先把环境准备好

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

## 加入一个原生方法

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

`NativePocketRunDevice.ts` 定义返回值和一个异步方法：

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

`package.json` 的 `codegenConfig` 指向 `specs/`。Gradle 构建时生成 `NativePocketRunDeviceSpec`，Kotlin 实现继承它。生成文件留在 `build/`，不手改、不提交。

这里用 `get` 而不是 `getEnforcing`：模块缺失时拿到 `null`，按钮可以显示错误；不会在页面加载前直接抛错。

### Kotlin 读取系统信息

`DeviceInfoModule.kt` 的读取部分：

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

`DeviceInfoPackage` 把名字 `PocketRunDevice` 对应到这个实现，`MainApplication` 把 Package 加入列表。三个位置的名称必须一致。

React 只需等待结果：

```ts
if (!NativePocketRunDevice) {
  throw new Error('原生模块未注册，请重新构建 Android 应用。');
}
const info = await NativePocketRunDevice.getDeviceInfo();
setInfo(info);
```

完整实现还处理加载、失败和重试：加载时禁用按钮，失败时显示错误，重试成功后清掉旧错误。没有为了演示加载效果加入人工延时。

## 构建并实际点一次

先检查源码，再构建 Android：

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

## 哪些已经验证

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

练习：沿着这五个文件找出按钮、接口、Kotlin 实现和注册位置。下一章将引入 ADB；届时要区分普通 App 身份和 shell 后端身份。

参考：[原生模块](https://reactnative.dev/docs/turbo-native-modules-introduction)、[Codegen](https://reactnative.dev/docs/the-new-architecture/using-codegen)、[Android 开发环境](https://reactnative.dev/docs/set-up-your-environment)。
