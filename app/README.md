# PocketRun Android

第 1 章通过 TypeScript → TurboModule → Kotlin 读取设备信息；第 2 章让 App 在本机无线调试中配对、连接，并用固定的 `id -u` 检查 shell UID。

## 环境

- Node 24.18.0、npm、完整 JDK 17。
- Android SDK Platform 37.0、Platform Tools、Build Tools 37.0.0 / 36.0.0。
- NDK 27.1.12297006、CMake 3.22.1。
- 一个自己创建的 ARM64 Android 11+ 模拟器；无线 ADB 练习要开启开发者选项。

`JAVA_HOME` 指向完整 JDK，`ANDROID_HOME` 指向 SDK。安装见[第 1 章](../website/docs/chapter-1.md)，无线 ADB 原理与操作见[第 2 章](../website/docs/chapter-2.md)。React Native 版本固定为 0.88.1，CLI 为 20.2.0。

## 检查

在 `app/` 运行：

```bash
npm ci --registry=https://registry.npmjs.org
npm run lint
npm run typecheck
npm test -- --runInBand
cd android
./gradlew :adb:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

UI 测试使用原生模块 mock，覆盖设备信息状态以及 ADB 端口分离、配对码清理、取消和原生错误。JVM 测试覆盖 ADB transport 生命周期；真实系统配对步骤见第 2 章。

## 运行

先启动自己的模拟器，在 `app/` 启动 Metro：

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

确认 AVD 名称和安装目标后再安装；不要直接套用序列号操作其他设备。默认 Debug 包从设备端口 8081 连接 Metro，上面的 reverse 映射到本机 8088。

点击“检查设备”验证第 1 章；按第 2 章在系统无线调试中完成配对、连接、再次检查和断开。普通 Kotlin 模块仍是 App UID；只有 ADB 命令流由系统以 shell 身份执行。
