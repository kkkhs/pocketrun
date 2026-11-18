package io.github.kkkhs.pocketrun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VirtualDisplayLaunchCommandTest {
    @Test fun buildsOnlyTheFixedDemoActivityCommand() {
        assertEquals(
            listOf(
                "/system/bin/am",
                "start",
                "-W",
                "--display",
                "7",
                "-n",
                "io.github.kkkhs.pocketrun/.DisplayDemoActivity",
            ),
            VirtualDisplaySession.buildDemoLaunchArguments("io.github.kkkhs.pocketrun", 7),
        )
    }

    @Test fun rejectsInvalidPackageAndDefaultDisplay() {
        assertThrows(IllegalArgumentException::class.java) {
            VirtualDisplaySession.buildDemoLaunchArguments("bad;package", 7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VirtualDisplaySession.buildDemoLaunchArguments("io.github.kkkhs.pocketrun", 0)
        }
    }
}
