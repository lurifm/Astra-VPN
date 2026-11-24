package com.astra.vpn

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Converts executor shutdown races into a handled submission failure.
 * The rejection callback runs on the submitting thread so UI callers can
 * immediately restore their controls and release owned buffers.
 */
internal object ImportTaskSubmission {
    fun submit(
        executor: Executor,
        task: () -> Unit,
        onRejected: () -> Unit = {}
    ): Boolean {
        return try {
            executor.execute { task() }
            true
        } catch (_: RejectedExecutionException) {
            onRejected()
            false
        }
    }
}
