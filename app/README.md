# PocketRun Android

第 1 章通过 TypeScript → TurboModule → Kotlin 读取设备信息；第 2 章取得本机 ADB 启动入口；第 3 章启动 UID 2000 的 `app_process` Binder 后端；第 4 章由该后端创建 VirtualDisplay 并启动固定 Demo Activity。

## 环境

- Node 24.18.0、npm、完整 JDK 17。
- Android SDK Platform 37.0、Platform Tools、Build Tools 37.0.0 / 36.0.0。
- NDK 27.1.12297006、CMake 3.22.1。
- 一个自己创建的 ARM64 Android 11+ 模拟器；无线 ADB 练习要开启开发者选项。

`JAVA_HOME` 指向完整 JDK，`ANDROID_HOME` 指向 SDK。安装见[第 1 章](../website/docs/chapter-1.md)，无线 ADB 见[第 2 章](../website/docs/chapter-2.md)，shell 后端与 Binder 见[第 3 章](../website/docs/chapter-3.md)，VirtualDisplay 见[第 4 章](../website/docs/chapter-4.md)。React Native 版本固定为 0.88.1，CLI 为 20.2.0。

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

UI 测试使用原生模块 mock，覆盖设备信息、ADB、Binder 后端与 VirtualDisplay 状态。JVM 测试覆盖 ADB transport 和固定启动命令；专用模拟器中的真实配对、后端死亡、显示路由与释放步骤见第 2—4 章。

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

点击“检查设备”验证第 1 章；按第 2 章完成 ADB 配对和连接；按第 3 章启动 Binder 后端；再按第 4 章创建、检查并释放演示虚拟屏。普通 App Kotlin 仍是 App UID，独立 `app_process` 后端才是 shell UID 2000，显示资源也由后端持有。
