package io.github.kkkhs.pocketrun

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

internal fun isBackendCallerAllowed(appUid: Int, callingUid: Int) = callingUid == appUid

object PrivilegedBackendMain {
    private const val TAG = "PocketRunBackend"
    private const val SHELL_PACKAGE = "com.android.shell"
    @JvmStatic
    fun main(args: Array<String>) {
        val options = try {
            Options.parse(args)
        } catch (error: Exception) {
            Log.e(TAG, "Backend arguments were rejected", error)
            return
        }
        if (Process.myUid() != Process.SHELL_UID) return

        Log.i(TAG, "Starting shell backend Binder exchange")
        Looper.prepareMainLooper()
        val handler = Handler(Looper.getMainLooper())
        val backend = BackendBinder(options.appUid, options.packageName, handler)
        val client = try {
            exchange(options, backend)
        } catch (error: Exception) {
            backend.close()
            Log.e(TAG, "Binder exchange failed", error)
            return
        }
        val clientDeath = IBinder.DeathRecipient { handler.post { Looper.getMainLooper().quitSafely() } }
        runCatching { client.linkToDeath(clientDeath, 0) }.getOrElse {
            backend.close()
            return
        }

        // ponytail: exit with the App for now; add persistent re-attach in the recovery chapter.
        try {
            Looper.loop()
        } finally {
            backend.close()
            runCatching { client.unlinkToDeath(clientDeath, 0) }
        }
    }

    private fun exchange(options: Options, backend: IPrivilegedBackend): IBinder {
        val extras = Bundle().apply {
            putString(BinderExchangeProvider.KEY_TOKEN, options.token)
            putBinder(BinderExchangeProvider.KEY_BACKEND, backend.asBinder())
        }
        Log.i(TAG, "Calling Binder exchange provider")
        val result = callProvider(options.authority, options.userId, extras)
            ?: error("Binder exchange 没有返回结果。")
        Log.i(TAG, "Binder exchange completed")
        return checkNotNull(result.getBinder(BinderExchangeProvider.KEY_CLIENT)) {
            "Binder exchange 没有返回客户端 token。"
        }
    }

    private fun callProvider(authority: String, userId: Int, extras: Bundle): Bundle? {
        val activityManager = Class.forName("android.app.ActivityManager")
            .getDeclaredMethod("getService")
            .invoke(null)
        val externalToken = Binder()
        var acquired = false
        try {
            val getProvider = activityManager.javaClass.methods.first {
                it.name == "getContentProviderExternal" && it.parameterCount == 4
            }.apply { isAccessible = true }
            val holder = checkNotNull(
                getProvider.invoke(activityManager, authority, userId, externalToken, TAG)
            ) { "Binder exchange provider 不可用。" }
            acquired = true
            val provider = holder.javaClass.getDeclaredField("provider").apply {
                isAccessible = true
            }.get(holder)
            val callParameterCount = if (Build.VERSION.SDK_INT >= 31) 5 else 6
            val call = provider.javaClass.methods.first {
                it.name == "call" && it.parameterCount == callParameterCount
            }.apply { isAccessible = true }
            return if (Build.VERSION.SDK_INT >= 31) {
                val builderClass = Class.forName("android.content.AttributionSource\$Builder")
                val builder = builderClass
                    .getConstructor(Int::class.javaPrimitiveType)
                    .newInstance(Process.SHELL_UID)
                builderClass.getMethod("setPackageName", String::class.java)
                    .invoke(builder, SHELL_PACKAGE)
                val source = builderClass.getMethod("build").invoke(builder)
                call.invoke(
                    provider,
                    source,
                    authority,
                    BinderExchangeProvider.METHOD_EXCHANGE,
                    null,
                    extras,
                ) as Bundle?
            } else {
                call.invoke(
                    provider,
                    SHELL_PACKAGE,
                    null,
                    authority,
                    BinderExchangeProvider.METHOD_EXCHANGE,
                    null,
                    extras,
                ) as Bundle?
            }
        } finally {
            if (acquired) {
                val remove = activityManager.javaClass.methods.firstOrNull {
                    it.name == "removeContentProviderExternalAsUser" && it.parameterCount == 3
                } ?: activityManager.javaClass.methods.first {
                    it.name == "removeContentProviderExternal" && it.parameterCount == 2
                }
                remove.isAccessible = true
                if (remove.parameterCount == 3) {
                    remove.invoke(activityManager, authority, externalToken, userId)
                } else {
                    remove.invoke(activityManager, authority, externalToken)
                }
            }
        }
    }

    private class BackendBinder(
        private val appUid: Int,
        packageName: String,
        private val handler: Handler,
    ) : IPrivilegedBackend.Stub() {
        private val stopping = AtomicBoolean(false)
        private val displaySession = VirtualDisplaySession(packageName)
        private val inTransaction = ThreadLocal<Boolean>()
        private val captureReturnMemory = ThreadLocal<SharedMemory>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val previousTransaction = inTransaction.get()
            val previousMemory = captureReturnMemory.get()
            inTransaction.set(true)
            captureReturnMemory.remove()
            try {
                return super.onTransact(code, data, reply, flags)
            } finally {
                // The generated Stub has already duplicated the FD into the reply Parcel.
                // Close the shell-side return FD even when parcel writing throws.
                captureReturnMemory.get()?.close()
                if (previousMemory == null) captureReturnMemory.remove() else captureReturnMemory.set(previousMemory)
                if (previousTransaction == null) inTransaction.remove() else inTransaction.set(previousTransaction)
            }
        }

        override fun getUid(): Int {
            enforceClient()
            return Process.myUid()
        }

        override fun getPid(): Int {
            enforceClient()
            return Process.myPid()
        }

        override fun ping(): String {
            enforceClient()
            return "pong"
        }

        override fun createDemoDisplay(): Int {
            enforceClient()
            return asShell { displaySession.createDemoDisplay() }
        }

        override fun getDisplayId(): Int {
            enforceClient()
            return displaySession.getDisplayId()
        }

        override fun captureScreenshot(expectedDisplayId: Int): SharedMemory {
            enforceClient()
            val memory = asShell { displaySession.captureScreenshot(expectedDisplayId) }
            if (inTransaction.get() == true) captureReturnMemory.set(memory)
            return memory
        }

        override fun dumpUiTree(expectedDisplayId: Int): ByteArray {
            enforceClient()
            return asShell { displaySession.dumpUiTree(expectedDisplayId) }
        }

        override fun injectTap(expectedDisplayId: Int, x: Int, y: Int): Boolean {
            enforceClient()
            return asShell { displaySession.injectTap(expectedDisplayId, x, y, appUid) }
        }

        override fun injectText(expectedDisplayId: Int, text: String): Boolean {
            enforceClient()
            return asShell { displaySession.injectText(expectedDisplayId, text, appUid) }
        }

        override fun launchAppOnDisplay(expectedDisplayId: Int, packageName: String): Boolean {
            enforceClient()
            return asShell { displaySession.launchApp(expectedDisplayId, packageName) }
        }

        override fun injectAppTap(expectedDisplayId: Int, x: Int, y: Int): Boolean {
            enforceClient()
            return asShell { displaySession.injectAppTap(expectedDisplayId, x, y, appUid) }
        }

        override fun injectAppSwipe(expectedDisplayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
            enforceClient()
            return asShell { displaySession.injectAppSwipe(expectedDisplayId, x1, y1, x2, y2, durationMs, appUid) }
        }

        override fun injectAppKey(expectedDisplayId: Int, keyCode: Int): Boolean {
            enforceClient()
            return asShell { displaySession.injectAppKey(expectedDisplayId, keyCode, appUid) }
        }

        override fun injectAppText(expectedDisplayId: Int, text: String): Boolean {
            enforceClient()
            return asShell { displaySession.injectAppText(expectedDisplayId, text, appUid) }
        }

        override fun releaseDisplay() {
            enforceClient()
            asShell { displaySession.close() }
        }

        override fun shutdown() {
            enforceClient()
            if (stopping.compareAndSet(false, true)) {
                asShell { displaySession.terminate() }
                handler.post { Looper.getMainLooper().quitSafely() }
            }
        }

        fun close() {
            stopping.set(true)
            displaySession.terminate()
        }

        private inline fun <T> asShell(action: () -> T): T {
            val identity = Binder.clearCallingIdentity()
            try {
                check(Process.myUid() == Process.SHELL_UID) { "后端不是 shell UID 2000。" }
                return action()
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        private fun enforceClient() {
            val caller = Binder.getCallingUid()
            if (!isBackendCallerAllowed(appUid, caller)) {
                throw SecurityException("后端调用者 UID 不匹配。")
            }
        }
    }

    private data class Options(
        val token: String,
        val authority: String,
        val appUid: Int,
        val userId: Int,
    ) {
        val packageName: String get() = authority.removeSuffix(AUTHORITY_SUFFIX)

        companion object {
            private const val AUTHORITY_SUFFIX = ".binder_exchange"
            fun parse(args: Array<String>): Options {
                require(args.size == 8) { "后端启动参数数量无效。" }
                val values = args.toList().chunked(2).associate { (key, value) -> key to value }
                val token = checkNotNull(values["--token"])
                val authority = checkNotNull(values["--authority"])
                val appUid = checkNotNull(values["--app-uid"]).toInt()
                val userId = checkNotNull(values["--user"]).toInt()
                require(Regex("[A-Za-z0-9_-]{43}").matches(token)) { "后端 token 无效。" }
                require(Regex("[A-Za-z0-9_.]+").matches(authority) && authority.endsWith(AUTHORITY_SUFFIX)) {
                    "后端 authority 无效。"
                }
                require(Regex("[A-Za-z0-9_.]+").matches(authority.removeSuffix(AUTHORITY_SUFFIX))) {
                    "后端 package 无效。"
                }
                require(appUid > 0 && userId >= 0) { "后端身份无效。" }
                return Options(token, authority, appUid, userId)
            }
        }
    }
}
