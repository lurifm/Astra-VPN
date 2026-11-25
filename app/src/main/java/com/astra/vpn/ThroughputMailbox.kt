package com.astra.vpn

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

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

/**
 * Atomic mailbox shared by the main and isolated throughput processes.
 * AtomicFile protects against torn writes; the separate OS file lock also
 * serializes readers, writers and acknowledgements across Android processes.
 */
internal class ThroughputMailboxStore(context: Context) {
    private val baseFile = File(context.filesDir, FILE_NAME)
    private val lockFile = File(context.filesDir, LOCK_FILE_NAME)
    private val file = AtomicFile(baseFile)

    fun write(event: ThroughputMailboxEvent) = withMailboxLock {
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

    fun read(): ThroughputMailboxEvent? = withMailboxLock { readLocked() }

    fun acknowledge(sessionId: String) = withMailboxLock {
        if (sessionId.isNotBlank() && readLocked()?.sessionId == sessionId) {
            file.delete()
        }
    }

    private fun readLocked(): ThroughputMailboxEvent? {
        val raw = try {
            file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            return null
        } catch (_: Exception) {
            return null
        }
        val decoded = ThroughputMailboxCodec.decode(raw)
        if (decoded == null) {
            // A malformed or truncated terminal event must not poison every
            // future app launch. Delete only after a successful file read.
            file.delete()
        }
        return decoded
    }

    private fun <T> withMailboxLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { lockHandle ->
            lockHandle.channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private companion object {
        const val FILE_NAME = "throughput_terminal_event"
        const val LOCK_FILE_NAME = "throughput_terminal_event.lock"
        val PROCESS_LOCK = Any()
    }
}
