# PocketRun Android

第 1 章示例：点击“检查设备”，通过 TypeScript → TurboModule → Kotlin 读取 Android 版本、API Level、ABI 和当前 App UID。

## 环境

- Node 24.18.0、npm、完整 JDK 17。
- Android SDK Platform 37.0、Platform Tools、Build Tools 37.0.0 / 36.0.0。
- NDK 27.1.12297006、CMake 3.22.1。
- 一个自己创建的 ARM64 Android 模拟器。

`JAVA_HOME` 指向完整 JDK，`ANDROID_HOME` 指向 SDK。安装和原理见[第 1 章](../website/docs/chapter-1.md)。React Native 版本固定为 0.88.1，CLI 为 20.2.0。

## 检查

在 `app/` 运行：

```bash
npm ci --registry=https://registry.npmjs.org
npm run lint
npm run typecheck
npm test -- --runInBand
cd android
./gradlew :app:assembleDebug -PreactNativeArchitectures=arm64-v8a --max-workers=2
```

UI 测试使用原生模块 mock，覆盖加载、成功、失败重试和模块缺失。它不能代替实际 Android 调用验证。

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

点击按钮，核对显示结果与 `adb -s "$SERIAL" logcat -d -s PocketRunDevice:I '*:S'`。Kotlin 使用 `Process.myUid()`；原生桥接不会授予 shell 或 root 权限。
