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
        val baseDirectory = filesDir.apply { mkdirs() }
        val workingDirectory = (getExternalFilesDir(null) ?: filesDir).apply { mkdirs() }
        val temporaryDirectory = cacheDir.apply { mkdirs() }

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
