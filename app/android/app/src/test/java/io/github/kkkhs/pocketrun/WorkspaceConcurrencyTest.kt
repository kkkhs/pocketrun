package io.github.kkkhs.pocketrun

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceConcurrencyTest {
    @Test fun independentConsumersCannotDeleteLiveSampleStaging() {
        val root = Files.createTempDirectory("workspace-consumers-").toFile()
        val staged = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            WorkspaceSession(root, beforeSamplePublish = {
                staged.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }).use { workspace ->
                WorkspaceSession(root).use { linuxConsumer ->
                    val first = workers.submit<WorkspaceSession.Info> { workspace.prepare() }
                    assertTrue(staged.await(2, TimeUnit.SECONDS))
                    val temporary = root.resolve("workspaces/demo").listFiles()!!.single { it.name.startsWith(".pocketrun-sample-") }
                    val second = workers.submit<WorkspaceSession.Info> {
                        secondStarted.countDown()
                        linuxConsumer.prepare()
                    }
                    assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
                    assertFalse(second.isDone)
                    assertTrue(temporary.isFile)
                    release.countDown()
                    assertTrue(first.get(2, TimeUnit.SECONDS).sampleCreated)
                    assertFalse(second.get(2, TimeUnit.SECONDS).sampleCreated)
                    assertEquals(WorkspaceSession.DEMO_CSV, root.resolve("workspaces/demo/items.csv").readText())
                }
            }
        } finally {
            release.countDown()
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }
}
