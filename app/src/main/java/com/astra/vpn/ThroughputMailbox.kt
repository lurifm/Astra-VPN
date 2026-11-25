package com.astra.vpn

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Terminal measurement event; contains metrics only, never a profile URI or credentials. */
internal data class ThroughputMailboxEvent(
    val sessionId: String,
    val action: String,
    val resultJson: String? = null,
    val errorMessage: String? = null
) {
    fun toIntent(): Intent = Intent(action).apply {
        putExtra(AstraThroughputProbeService.EXTRA_SESSION, sessionId)
        resultJson?.let { putExtra(AstraThroughputProbeService.EXTRA_RESULT, it) }
        errorMessage?.let { putExtra(AstraThroughputProbeService.EXTRA_ERROR, it) }
    }
}

internal object ThroughputMailboxCodec {
    fun encode(event: ThroughputMailboxEvent): String {
        require(event.sessionId.isNotBlank() && event.sessionId.length <= 128)
        require(event.action == AstraThroughputProbeService.ACTION_RESULT ||
            event.action == AstraThroughputProbeService.ACTION_ERROR)
        require((event.action == AstraThroughputProbeService.ACTION_RESULT) == (event.resultJson != null))
        require((event.action == AstraThroughputProbeService.ACTION_ERROR) == (event.errorMessage != null))
        return JSONObject()
            .put("session", event.sessionId)
            .put("action", event.action)
            .put("result", event.resultJson)
            .put("error", event.errorMessage?.take(240))
            .toString()
    }

    fun decode(raw: String): ThroughputMailboxEvent? = runCatching {
        require(raw.length <= MAX_PAYLOAD_CHARS)
        val json = JSONObject(raw)
        val session = json.getString("session")
        val action = json.getString("action")
        require(session.isNotBlank() && session.length <= 128)
        require(action == AstraThroughputProbeService.ACTION_RESULT ||
            action == AstraThroughputProbeService.ACTION_ERROR)
        val result = json.optString("result").takeIf { it.isNotBlank() && it != "null" }
        val error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
        require((action == AstraThroughputProbeService.ACTION_RESULT) == (result != null))
        require((action == AstraThroughputProbeService.ACTION_ERROR) == (error != null))
        ThroughputMailboxEvent(session, action, result, error)
    }.getOrNull()

    private const val MAX_PAYLOAD_CHARS = 64 * 1024
}

/** Atomic file mailbox shared by the main and isolated throughput processes. */
internal class ThroughputMailboxStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))

    @Synchronized
    fun write(event: ThroughputMailboxEvent) {
        val bytes = ThroughputMailboxCodec.encode(event).toByteArray(Charsets.UTF_8)
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            file.finishWrite(output)
            output = null
        } catch (error: Exception) {
            output?.let { runCatching { file.failWrite(it) } }
            throw error
        }
    }

    @Synchronized
    fun read(): ThroughputMailboxEvent? = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).use {
            ThroughputMailboxCodec.decode(it.readText())
        }
    }.getOrNull()

    @Synchronized
    fun acknowledge(sessionId: String) {
        if (sessionId.isBlank()) return
        if (read()?.sessionId == sessionId) file.delete()
    }

    private companion object {
        const val FILE_NAME = "throughput_terminal_event"
    }
}
