# PocketRun Android

第 1 章通过 TypeScript → TurboModule → Kotlin 读取设备信息；第 2 章取得本机 ADB 启动入口；第 3 章启动 UID 2000 的 `app_process` Binder 后端；第 4 章由该后端创建 VirtualDisplay 并启动固定 Demo Activity；第 5 章从同一显示读取截图和 UI 树、定向输入并重新观察结果。

## 环境

- Node 24.18.0、npm、完整 JDK 17。
- Android SDK Platform 37.0、Platform Tools、Build Tools 37.0.0 / 36.0.0。
- NDK 27.1.12297006、CMake 3.22.1。
- 一个自己创建的 ARM64 模拟器：无线 ADB 基础练习要求 Android 11+；第 7—11 章的固定 Linux 环境要求 Android 14 / API 34+、64 位 App。沿 API 36 / ARM64 专用模拟器练习最容易对照已验证结果。

`JAVA_HOME` 指向完整 JDK，`ANDROID_HOME` 指向 SDK。安装见[第 1 章](../website/docs/chapter-1.md)，无线 ADB 见[第 2 章](../website/docs/chapter-2.md)，shell 后端与 Binder 见[第 3 章](../website/docs/chapter-3.md)，VirtualDisplay 见[第 4 章](../website/docs/chapter-4.md)，截图 / UI 树 / 定向输入见[第 5 章](../website/docs/chapter-5.md)。React Native 版本固定为 0.88.1，CLI 为 20.2.0。

## 检查

在 `app/` 运行：

```bash
npm ci --registry=https://registry.npmjs.org
npm run lint
npm run typecheck
npm test -- --runInBand
npm run audit:ci
cd android
./gradlew :adb:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

UI 测试使用原生模块 mock，覆盖设备信息、ADB、Binder 后端、VirtualDisplay 和观察 / 输入状态。JVM 测试覆盖 ADB transport、固定启动命令、截图 / UI 树 codec 与输入边界；专用模拟器中的真实配对、后端死亡、显示路由、截图、UI 树、输入与释放步骤见第 2—5 章。

## 运行

当前 `main` 与第 6—11 章快照的 Debug 包已内置 JavaScript bundle，不要求设备连接 Metro。按对应章节构建后，在 `app/` 显式选择自己的模拟器并安装、启动：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name
adb -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -n io.github.kkkhs.pocketrun/.MainActivity
```

先核对 AVD 名称、系统/ABI 和本次 APK，再执行安装；序列号不能直接套用到其他设备。只读教程无需先运行这些步骤。

<details>
<summary>需要 Metro 的开发配置</summary>

仅当所用源码配置为从开发服务器加载 JavaScript、而不是内置 bundle 时，使用下面的流程；早期章节仍以对应快照中的运行说明为准。

启动自己的模拟器，在 `app/` 启动 Metro：

```bash
npm start -- --host 127.0.0.1 --port 8088 --no-interactive
```

另开终端，在 `app/` 执行。`SERIAL` 必须换成自己模拟器的序列号；下例对应 `-port 5556`：

```bash
SERIAL=emulator-5556
adb -s "$SERIAL" emu avd name
adb -s "$SERIAL" reverse tcp:8081 tcp:8088
adb -s "$SERIAL" install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell am start -n io.github.kkkhs.pocketrun/.MainActivity
```

确认 AVD 名称和安装目标后再安装；不要直接套用序列号操作其他设备。这个开发配置从设备端口 8081 连接 Metro，上面的 reverse 映射到本机 8088；内置 bundle 的当前包不需要这条映射。

</details>

点击“检查设备”验证第 1 章；按第 2 章完成 ADB 配对和连接；按第 3 章启动 Binder 后端；按第 4 章创建演示虚拟屏；再按第 5 章读取截图与 UI 树，点击唯一输入框、输入固定文本并重新观察结果。普通 App Kotlin 仍是 App UID，独立 `app_process` 后端才是 shell UID 2000，显示资源也由后端持有。
