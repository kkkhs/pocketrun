package io.github.kkkhs.pocketrun

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class DeviceInfoPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      when (name) {
        DeviceInfoModule.NAME -> DeviceInfoModule(reactContext)
        AdbModule.NAME -> AdbModule(reactContext)
        WorkspaceModule.NAME -> WorkspaceModule(reactContext)
        LinuxModule.NAME -> LinuxModule(reactContext)
        PtyModule.NAME -> PtyModule(reactContext)
        else -> null
      }

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
        DeviceInfoModule.NAME to ReactModuleInfo(
            DeviceInfoModule.NAME,
            DeviceInfoModule.NAME,
            false,
            false,
            false,
            true,
        ),
        AdbModule.NAME to ReactModuleInfo(
            AdbModule.NAME,
            AdbModule.NAME,
            false,
            false,
            false,
            true,
        ),
        WorkspaceModule.NAME to ReactModuleInfo(
            WorkspaceModule.NAME,
            WorkspaceModule.NAME,
            false,
            false,
            false,
            true,
        ),
        LinuxModule.NAME to ReactModuleInfo(
            LinuxModule.NAME,
            LinuxModule.NAME,
            false,
            false,
            false,
            true,
        ),
        PtyModule.NAME to ReactModuleInfo(
            PtyModule.NAME,
            PtyModule.NAME,
            false,
            false,
            false,
            true,
        ),

    )
  }
}
