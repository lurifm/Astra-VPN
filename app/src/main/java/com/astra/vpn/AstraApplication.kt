package com.astra.vpn

import android.app.Application
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import org.json.JSONObject
import java.util.Locale

class AstraApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            setupLibbox()
        }.onFailure { error ->
            Log.e(TAG, "Не удалось инициализировать sing-box", error)
        }
    }

    private fun setupLibbox() {
        // The URL-test engine runs in :latency so it has an independent libbox
        // singleton and command.sock. Keep every writable path process-isolated.
        val processSuffix = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName().substringAfterLast(":", "main")
        } else {
            "main"
        }
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
