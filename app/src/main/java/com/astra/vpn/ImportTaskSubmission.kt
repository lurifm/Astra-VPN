package com.astra.vpn

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks queued tasks so shutdownNow can release resources owned by work
 * that is removed from the executor queue before it ever starts.
 */
internal object ImportTaskSubmission {
    private class SubmittedTask(
        private val task: () -> Unit,
        private val onRejected: () -> Unit,
        private val onCancelled: () -> Unit
    ) : Runnable {
        private val claimed = AtomicBoolean(false)

        override fun run() {
            if (claimed.compareAndSet(false, true)) task()
        }

        fun reject() {
            if (claimed.compareAndSet(false, true)) onRejected()
        }

        fun cancelBeforeStart() {
            if (claimed.compareAndSet(false, true)) onCancelled()
        }
    }

    fun submit(
        executor: ExecutorService,
        task: () -> Unit,
        onRejected: () -> Unit = {},
        onCancelled: () -> Unit = {}
    ): Boolean {
        val submitted = SubmittedTask(task, onRejected, onCancelled)
        return try {
            executor.execute(submitted)
            true
        } catch (_: RejectedExecutionException) {
            submitted.reject()
            false
        }
    }

    /** Stops the executor and invokes cleanup for every queued task it discards. */
    fun shutdownNow(executor: ExecutorService): List<Runnable> {
        val pending = executor.shutdownNow()
        pending.forEach { runnable ->
            (runnable as? SubmittedTask)?.cancelBeforeStart()
        }
        return pending
    }
}
