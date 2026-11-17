package io.github.kkkhs.pocketrun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendLaunchCommandTest {
    private val token = "A".repeat(43)

    @Test fun buildsOnlyTheFixedDetachedAppProcessCommand() {
        val command = BackendLaunchCommand.build(
            "/data/app/it's/base.apk",
            token,
            "io.github.kkkhs.pocketrun.binder_exchange",
            10150,
            0,
        )

        assertTrue(command.contains("/system/bin/setsid /system/bin/app_process"))
        assertTrue(command.contains(BackendLaunchCommand.MAIN_CLASS))
        assertTrue(command.contains("--app-uid' '10150' '--user' '0'"))
        assertTrue(command.contains("'/data/app/it'\"'\"'s/base.apk'"))
        assertTrue(command.contains("kill -0 \"\$backend_pid\""))
        assertTrue(command.contains("printf '${BackendLaunchCommand.STARTED_MARKER}\\n'"))
        assertFalse(command.contains("sh -c"))
    }

    @Test fun backendAcceptsOnlyTheExpectedAppUid() {
        assertTrue(isBackendCallerAllowed(10150, 10150))
        assertFalse(isBackendCallerAllowed(10150, 2000))
        assertFalse(isBackendCallerAllowed(10150, 10151))
    }

    @Test fun rejectsUntrustedBootstrapValues() {
        assertThrows(IllegalArgumentException::class.java) {
            BackendLaunchCommand.build("relative.apk", token, "valid.authority", 10150, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendLaunchCommand.build("/base.apk", "bad token", "valid.authority", 10150, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackendLaunchCommand.build("/base.apk", token, "bad;authority", 10150, 0)
        }
    }
}
