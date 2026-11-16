package com.astra.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var profileStore: ProfileStore
    private lateinit var profileList: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private var selectedProfile: String? = null
    private var pendingConfig: String? = null
    private var connected = false
    private val importExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = ProfileStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 28)
            setBackgroundColor(Color.rgb(13, 17, 28))
        }

        val title = TextView(this).apply {
            text = "ASTRA VPN"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Защищённое подключение"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Выбери сервер для подключения"
            textSize = 14f
            setTextColor(Color.rgb(255, 196, 92))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 20)
        }

        val importButton = Button(this).apply {
            text = "ДОБАВИТЬ СЕРВЕР"
            setOnClickListener { showImportDialog() }
        }

        connectButton = Button(this).apply {
            text = "ВЫБЕРИ СЕРВЕР"
            isEnabled = false
            setOnClickListener {
                if (connected) {
                    stopTunnel()
                } else {
                    connectSelectedProfile()
                }
            }
        }

        val listTitle = TextView(this).apply {
            text = "Мои серверы"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 28, 0, 10)
        }
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(importButton, matchWidth())
        root.addView(connectButton, matchWidth())
        root.addView(listTitle)
        root.addView(profileList)

        setContentView(root)
        renderProfiles()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Ссылка сервера или HTTPS-подписка"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(24, 18, 24, 18)
        }
        AlertDialog.Builder(this)
            .setTitle("Импорт сервера")
            .setMessage("Вставь ссылку сервера, несколько ссылок или HTTPS-ссылку подписки. Данные сохраняются зашифрованно на устройстве.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = input.text.toString().trim()
                        if (value.isEmpty()) {
                            input.error = "Вставь ссылку"
                            return@setOnClickListener
                        }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        input.isEnabled = false
                        status.text = "Импорт подписки…"
                        importExecutor.execute {
                            val result = runCatching { SubscriptionParser.importText(value) }
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                dialog.dismiss()
                                input.isEnabled = true
                                if (result.isSuccess) {
                                    val links = result.getOrThrow()
                                    profileStore.addAll(links)
                                    renderProfiles()
                                    status.text = "Импортировано серверов: ${links.size}"
                                    Toast.makeText(this, "Добавлено серверов: ${links.size}", Toast.LENGTH_LONG).show()
                                } else {
                                    status.text = "Импорт не выполнен"
                                    AlertDialog.Builder(this)
                                        .setTitle("Ошибка импорта")
                                        .setMessage(result.exceptionOrNull()?.message ?: "Неизвестная ошибка")
                                        .setPositiveButton("ОК", null)
                                        .show()
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun renderProfiles() {
        profileList.removeAllViews()
        val profiles = profileStore.all()
        if (selectedProfile == null || selectedProfile !in profiles) selectedProfile = profiles.firstOrNull()
        connectButton.isEnabled = selectedProfile != null || connected
        connectButton.text = if (connected) "ОТКЛЮЧИТЬСЯ" else if (selectedProfile != null) "ПОДКЛЮЧИТЬСЯ" else "ВЫБЕРИ СЕРВЕР"
        if (profiles.isEmpty()) {
            profileList.addView(TextView(this).apply {
                text = "Пока нет добавленных серверов"
                setTextColor(Color.LTGRAY)
                textSize = 14f
            })
            return
        }

        profiles.forEach { link ->
            val label = link.substringAfter('#', "").ifBlank {
                link.substringBefore("://").uppercase()
            }.take(48)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val name = TextView(this).apply {
                text = (if (link == selectedProfile) "✓ " else "") + label
                textSize = 15f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    selectedProfile = link
                    status.text = "Выбран сервер: $label"
                    renderProfiles()
                }
            }
            val delete = Button(this).apply {
                text = "Удалить"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Удалить этот сервер?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить") { _, _ ->
                            profileStore.remove(link)
                            renderProfiles()
                        }
                        .show()
                }
            }
            row.addView(name)
            row.addView(delete)
            profileList.addView(row)
        }
    }

    private fun connectSelectedProfile() {
        val link = selectedProfile ?: run {
            status.text = "Сначала добавь и выбери сервер"
            return
        }
        val config = runCatching { SingBoxConfigBuilder.build(link) }.getOrElse { error ->
            AlertDialog.Builder(this)
                .setTitle("Не удалось подготовить сервер")
                .setMessage(error.message ?: "Неподдерживаемая конфигурация")
                .setPositiveButton("ОК", null)
                .show()
            return
        }
        pendingConfig = config
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else {
            startTunnel(config)
        }
    }

    @Deprecated("Deprecated in Android API, retained for compatibility with minSdk")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                pendingConfig?.let { startTunnel(it) }
            } else {
                pendingConfig = null
                status.text = "Разрешение VPN не выдано"
            }
        }
    }

    private fun startTunnel(config: String) {
        val intent = Intent(this, AstraVpnService::class.java)
            .setAction(AstraVpnService.ACTION_START)
            .putExtra(AstraVpnService.EXTRA_CONFIG, config)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            connected = true
            pendingConfig = null
            status.text = "Запрос на подключение отправлен"
            renderProfiles()
        } catch (error: Exception) {
            connected = false
            status.text = "Не удалось запустить VPN"
            Toast.makeText(this, error.message ?: "Ошибка запуска VPN", Toast.LENGTH_LONG).show()
            renderProfiles()
        }
    }

    private fun stopTunnel() {
        val intent = Intent(this, AstraVpnService::class.java).setAction(AstraVpnService.ACTION_STOP)
        runCatching { startService(intent) }
        connected = false
        status.text = "VPN отключён"
        renderProfiles()
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val REQUEST_VPN_PERMISSION = 7001
    }
}
