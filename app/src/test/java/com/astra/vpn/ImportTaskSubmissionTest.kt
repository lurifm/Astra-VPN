package com.astra.vpn

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTaskSubmissionTest {
    @Test fun acceptedTaskIsSubmittedAndReturnsTrue() {
        var executions = 0
        val executor = Executor { command -> command.run() }

        val accepted = ImportTaskSubmission.submit(executor, { executions++ })

        assertTrue(accepted)
        assertEquals(1, executions)
    }

    @Test fun rejectedTaskRunsCleanupAndReturnsFalse() {
        var executions = 0
        var cleanups = 0
        val rejectingExecutor = Executor { throw RejectedExecutionException("shutdown") }

        val accepted = ImportTaskSubmission.submit(
            rejectingExecutor,
            { executions++ },
            { cleanups++ }
        )

        assertFalse(accepted)
        assertEquals(0, executions)
        assertEquals(1, cleanups)
    }

    @Test fun unrelatedExecutorFailuresAreNotSilentlyHidden() {
        val brokenExecutor = Executor { throw IllegalStateException("executor failure") }

        try {
            ImportTaskSubmission.submit(brokenExecutor, {})
            throw AssertionError("Expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertEquals("executor failure", expected.message)
        }
    }
}
