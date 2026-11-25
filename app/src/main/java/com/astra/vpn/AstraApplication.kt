package com.astra.vpn

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject
import java.util.Locale

class AstraApplication : Application() {
    private val throughputEventLock = Any()
    private var pendingThroughputEvent: Intent? = null
    private val throughputEventReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != AstraThroughputProbeService.ACTION_RESULT &&
                action != AstraThroughputProbeService.ACTION_ERROR
            ) return
            synchronized(throughputEventLock) { pendingThroughputEvent = Intent(intent) }
        }
    }

    fun peekPendingThroughputEvent(): Intent? = synchronized(throughputEventLock) {
        pendingThroughputEvent?.let(::Intent)
    }

    fun acknowledgeThroughputEvent(sessionId: String) {
        synchronized(throughputEventLock) {
            if (pendingThroughputEvent?.getStringExtra(AstraThroughputProbeService.EXTRA_SESSION) == sessionId) {
                pendingThroughputEvent = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (isMainProcess()) {
            val filter = IntentFilter().apply {
                addAction(AstraThroughputProbeService.ACTION_RESULT)
                addAction(AstraThroughputProbeService.ACTION_ERROR)
            }
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(
                    this, throughputEventReceiver, filter,
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }.onFailure { SafeLog.w(TAG, "Не удалось зарегистрировать mailbox измерения скорости", it) }
        }
        runCatching {
            setupLibbox()
        }.onFailure { error ->
            SafeLog.e(TAG, "Не удалось инициализировать sing-box", error)
        }
    }

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName ?: packageName
        }
        return processName == packageName
    }

    private fun setupLibbox() {
        // The URL-test engine runs in :latency so it has an independent libbox
        // singleton and command.sock. Keep every writable path process-isolated.
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val activityManager = getSystemService(android.app.ActivityManager::class.java)
            activityManager.runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }
                ?.processName ?: packageName
        }
        val processSuffix = processName.substringAfterLast(":", "main")
        val processRoot = if (processSuffix == "main") filesDir else java.io.File(filesDir, processSuffix)
        val baseDirectory = processRoot.apply { mkdirs() }
        val workingRoot = getExternalFilesDir(null) ?: filesDir
        val workingDirectory = (if (processSuffix == "main") workingRoot else java.io.File(workingRoot, processSuffix)).apply { mkdirs() }
        val temporaryDirectory = (if (processSuffix == "main") cacheDir else java.io.File(cacheDir, processSuffix)).apply { mkdirs() }

        Libbox.setLocale(Locale.getDefault().toLanguageTag())

        val options = SetupOptions().apply {
            basePath = baseDirectory.absolutePath
            workingPath = workingDirectory.absolutePath
            tempPath = temporaryDirectory.absolutePath
            fixAndroidStack = true
            logMaxLines = 1000
            debug = false
            platformMetadata = JSONObject()
                .put("os", "Android " + Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .toString()
        }

        Libbox.setup(options)
        Log.i(TAG, "sing-box успешно инициализирован")
    }

    companion object {
        private const val TAG = "AstraApplication"
    }
}


/** In-memory only: profile links are never written to saved-instance disk state. */
internal data class ThroughputRetainedState(
    val sessionId: String?,
    val profile: String?,
    val preparationPending: Boolean,
    val preparationFastMode: Boolean,
    val inFlight: Boolean,
    val batchActive: Boolean,
    val batchQueue: List<String>,
    val batchGeneration: Long,
    val batchTotal: Int,
    val batchCompleted: Int,
    val fastestCandidates: List<String>,
    val selectingFastest: Boolean,
    val tilePendingStart: Boolean
)
