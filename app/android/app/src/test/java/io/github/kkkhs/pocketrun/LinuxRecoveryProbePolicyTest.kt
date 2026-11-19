package io.github.kkkhs.pocketrun

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Host parser regression; actual live-process association still needs Android evidence. */
class LinuxRecoveryProbePolicyTest {
    @Test fun identifiesTheSingleFixedTemporaryBindingInsteadOfAnyLiveGuestMapping() {
        val command = listOf("/data/app/pkg/lib/arm64/libproot.so", "-b", "/private/run-a/tmp:/tmp",
            "-b", "/private/workspace:/workspace", "/bin/busybox", "sh").joinToString("\u0000")
        assertEquals(File("/private/run-a/tmp"), LinuxRecoveryProbe.temporaryBinding(command))
    }

    @Test fun missingAmbiguousRelativeOrUnrelatedCommandsRemainUnknown() {
        assertNull(LinuxRecoveryProbe.temporaryBinding("/bin/busybox\u0000sleep\u000030"))
        assertNull(LinuxRecoveryProbe.temporaryBinding("/private/libproot.so\u0000-b\u0000relative:/tmp"))
        assertNull(LinuxRecoveryProbe.temporaryBinding("/private/libproot.so\u0000-b"))
        assertNull(LinuxRecoveryProbe.temporaryBinding("/private/libproot.so\u0000-b\u0000/a:/tmp\u0000-b\u0000/b:/tmp"))
    }
}
