package com.astra.vpn

import android.util.Log

/**
 * Logs safe operation context without forwarding exception messages or stack traces.
 * Exception messages may contain profile URLs, credentials, or remote endpoint details.
 */
internal object SafeLog {
    fun w(tag: String, message: String, error: Throwable) {
        Log.w(tag, "$message (${error.javaClass.simpleName})")
    }

    fun e(tag: String, message: String, error: Throwable) {
        Log.e(tag, "$message (${error.javaClass.simpleName})")
    }
}
