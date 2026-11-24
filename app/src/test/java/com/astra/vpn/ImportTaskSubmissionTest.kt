package com.astra.vpn

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTaskSubmissionTest {
    @Test fun acceptedTaskIsSubmittedAndReturnsTrue() {
        var executions = 0
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        try {
            val accepted = ImportTaskSubmission.submit(executor, { executions++ })
            executor.shutdown()
            assertTrue(accepted)
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(1, executions)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun rejectedTaskRunsCleanupAndReturnsFalse() {
        var executions = 0
        var cleanups = 0
        val rejectingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor().apply {
            shutdown()
        }

        val accepted = ImportTaskSubmission.submit(
            rejectingExecutor,
            { executions++ },
            { cleanups++ }
        )

        assertFalse(accepted)
        assertEquals(0, executions)
        assertEquals(1, cleanups)
    }

    @Test fun shutdownCleansTasksRemovedFromQueue() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
        val executions = AtomicInteger()
        val cleanups = AtomicInteger()

        try {
            executor.execute {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))

            assertTrue(
                ImportTaskSubmission.submit(
                    executor,
                    { executions.incrementAndGet() },
                    onCancelled = { cleanups.incrementAndGet() }
                )
            )

            val discarded = ImportTaskSubmission.shutdownNow(executor)
            assertEquals(1, discarded.size)
            assertEquals(0, executions.get())
            assertEquals(1, cleanups.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun unrelatedExecutorFailuresAreNotSilentlyHidden() {
        val brokenExecutor = object : java.util.concurrent.AbstractExecutorService() {
            override fun execute(command: Runnable) {
                throw IllegalStateException("executor failure")
            }
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
        }

        try {
            ImportTaskSubmission.submit(brokenExecutor, {})
            throw AssertionError("Expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals("executor failure", expected.message)
        }
    }
}
