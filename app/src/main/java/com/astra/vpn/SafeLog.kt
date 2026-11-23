package com.astra.vpn

import android.util.Log

/**
 * Logs safe operation context without forwarding exception messages or stack traces.
 * Exception messages may contain profile URLs, credentials, or remote endpoint details.
 */
internal object SafeLogMessage {
    /** Formats operation context without exposing exception messages or stack traces. */
    fun format(message: String, error: Throwable): String =
        "$message (${error.javaClass.simpleName})"
}

internal object SafeLog {
    fun w(tag: String, message: String, error: Throwable) {
        Log.w(tag, SafeLogMessage.format(message, error))
    }

    fun e(tag: String, message: String, error: Throwable) {
        Log.e(tag, SafeLogMessage.format(message, error))
    }
}
