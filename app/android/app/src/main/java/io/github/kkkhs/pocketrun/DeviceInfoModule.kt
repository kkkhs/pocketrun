package io.github.kkkhs.pocketrun

import android.os.Build
import android.os.Process
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule

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
      if (BuildConfig.DEBUG) {
        Log.i(NAME, "API=${Build.VERSION.SDK_INT} UID=${Process.myUid()} ABIs=${Build.SUPPORTED_ABIS.joinToString()}")
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
