package io.github.kkkhs.pocketrun

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class DeviceInfoPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
      if (name == DeviceInfoModule.NAME) DeviceInfoModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
        DeviceInfoModule.NAME to ReactModuleInfo(
            DeviceInfoModule.NAME,
            DeviceInfoModule.NAME,
            false,
            false,
            false,
            true,
        )
    )
  }
}
