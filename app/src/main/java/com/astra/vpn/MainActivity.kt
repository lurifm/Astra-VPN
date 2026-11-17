package com.astra.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var profileStore: ProfileStore
    private lateinit var profileList: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectButton: Button
    private lateinit var selectedInfo: TextView
    private val pingInfo = mutableMapOf<String, String>()
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newFixedThreadPool(3)
    private var selectedProfile: String? = null
    private var pendingConfig: String? = null
    private var connected = false
    private var receiverRegistered = false

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AstraVpnService.ACTION_STATE) return
            when (intent.getStringExtra(AstraVpnService.EXTRA_STATE)) {
                AstraVpnService.STATE_CONNECTING -> {
                    connected = false
                    status.text = "Создаём VPN-туннель…"
                    status.setTextColor(AMBER)
                }
                AstraVpnService.STATE_CONNECTED -> {
                    connected = true
                    status.text = "Подключено"
                    status.setTextColor(GREEN)
                }
                AstraVpnService.STATE_DISCONNECTED -> {
                    connected = false
                    status.text = "Отключено"
                    status.setTextColor(MUTED)
                }
                AstraVpnService.STATE_ERROR -> {
                    connected = false
                    val message = intent.getStringExtra(AstraVpnService.EXTRA_ERROR)
                    status.text = "Ошибка подключения"
                    status.setTextColor(RED)
                    if (!message.isNullOrBlank()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Не удалось подключиться")
                            .setMessage(message)
                            .setPositiveButton("Понятно", null)
                            .show()
                    }
                }
            }
            renderProfiles()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = ProfileStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            setBackgroundColor(BG)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "ASTRA"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            letterSpacing = .12f
        }
        val addButton = TextView(this).apply {
            text = "+"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(CARD, 18)
            setOnClickListener { showImportDialog() }
        }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(addButton, LinearLayout.LayoutParams(dp(52), dp(48)))

        val subtitle = TextView(this).apply {
            text = "Быстрый и защищённый интернет"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, dp(20))
        }

        val connectCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(20))
            background = rounded(CARD, 26)
        }
        status = TextView(this).apply {
            text = "Выбери сервер"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(AMBER)
        }
        selectedInfo = TextView(this).apply {
            text = "Сервер не выбран"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(0, dp(7), 0, dp(17))
        }
        connectButton = Button(this).apply {
            text = "ПОДКЛЮЧИТЬСЯ"
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, 18)
            isEnabled = false
            setOnClickListener {
                if (connected) stopTunnel() else connectSelectedProfile()
            }
        }
        connectCard.addView(status, matchWidth())
        connectCard.addView(selectedInfo, matchWidth())
        connectCard.addView(connectButton, LinearLayout.LayoutParams(matchWidth().width, dp(54)))

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(12))
        }
        val listTitle = TextView(this).apply {
            text = "Мои серверы"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val refresh = TextView(this).apply {
            text = "Проверить задержку"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setOnClickListener { checkAllLatencies() }
        }
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, dp(32), 1f))
        listHeader.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))

        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(profileList)
        }

        root.addView(header)
        root.addView(subtitle)
        root.addView(connectCard, matchWidth())
        root.addView(listHeader)
        root.addView(scroll, LinearLayout.LayoutParams(matchWidth().width, 0, 1f))
        setContentView(root)
        requestNotificationPermissionIfNeeded()
        renderProfiles()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Вставь ссылку или HTTPS-подписку"
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Добавить серверы")
            .setMessage("Можно вставить одну ссылку или ссылку подписки Velvet VPN. Импортированные профили хранятся зашифрованно на устройстве.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Импортировать", null)
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
                        status.text = "Импортируем серверы…"
                        importExecutor.execute {
                            val result = runCatching { SubscriptionParser.importText(value) }
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                dialog.dismiss()
                                if (result.isSuccess) {
                                    val links = result.getOrThrow()
                                    profileStore.addAll(links)
                                    renderProfiles()
                                    status.text = "Импортировано: ${links.size}"
                                    status.setTextColor(GREEN)
                                    Toast.makeText(this, "Добавлено серверов: ${links.size}", Toast.LENGTH_LONG).show()
                                } else {
                                    status.text = "Ошибка импорта"
                                    status.setTextColor(RED)
                                    AlertDialog.Builder(this)
                                        .setTitle("Не удалось импортировать")
                                        .setMessage(result.exceptionOrNull()?.message ?: "Неизвестная ошибка")
                                        .setPositiveButton("ОК", null).show()
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
        val selected = selectedProfile
        connectButton.isEnabled = selected != null || connected
        connectButton.text = if (connected) "ОТКЛЮЧИТЬ VPN" else "ПОДКЛЮЧИТЬСЯ"
        selectedInfo.text = selected?.let { profileName(it) + "  ·  " + protocolName(it) } ?: "Сначала добавь сервер"

        if (profiles.isEmpty()) {
            profileList.addView(TextView(this).apply {
                text = "Пока нет серверов. Нажми + и импортируй ссылку подписки."
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
            return
        }

        profiles.forEach { link ->
            val isSelected = link == selected
            val name = profileName(link)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(10), dp(12))
                background = rounded(if (isSelected) SELECTED_CARD else CARD, 18)
                setOnClickListener {
                    if (!connected) {
                        selectedProfile = link
                        renderProfiles()
                    } else {
                        Toast.makeText(this@MainActivity, "Отключи VPN перед сменой сервера", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            val flag = TextView(this).apply {
                text = countryFlag(name)
                textSize = 25f
                gravity = Gravity.CENTER
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this).apply {
                text = name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
            }
            val meta = TextView(this).apply {
                val latency = pingInfo[link] ?: "Задержка не проверена"
                text = "${protocolName(link)}  ·  $latency"
                textSize = 12f
                setTextColor(if (latency.contains("мс")) GREEN else MUTED)
                setPadding(0, dp(4), 0, 0)
            }
            details.addView(title)
            details.addView(meta)
            val selectMark = TextView(this).apply {
                text = if (isSelected) "✓" else "›"
                textSize = 23f
                setTextColor(if (isSelected) LIGHT_ACCENT else MUTED)
                gravity = Gravity.CENTER
            }
            row.addView(flag, LinearLayout.LayoutParams(dp(42), dp(46)))
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            })
            row.addView(selectMark, LinearLayout.LayoutParams(dp(28), dp(42)))
            profileList.addView(row, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            })
        }
    }

    private fun checkAllLatencies() {
        val profiles = profileStore.all()
        if (profiles.isEmpty()) return
        Toast.makeText(this, "Проверяем доступность серверов…", Toast.LENGTH_SHORT).show()
        profiles.forEach { link ->
            val uri = Uri.parse(link)
            val scheme = uri.scheme.orEmpty().lowercase()
            if (scheme != "vless") {
                pingInfo[link] = if (scheme == "hysteria2" || scheme == "hy2" || scheme == "tuic")
                    "UDP • тест не поддерживается" else "—"
                runOnUiThread { renderProfiles() }
                return@forEach
            }
            val host = uri.host ?: return@forEach
            val port = uri.port
            pingExecutor.execute {
                val started = System.nanoTime()
                val result = runCatching {
                    Socket().use { socket -> socket.connect(InetSocketAddress(host, port), 3000) }
                    ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                }
                pingInfo[link] = result.fold({ "${it} мс (TCP)" }, { "Недоступен" })
                runOnUiThread { if (!isFinishing) renderProfiles() }
            }
        }
    }

    private fun connectSelectedProfile() {
        val link = selectedProfile ?: return
        val config = runCatching { SingBoxConfigBuilder.build(link) }.getOrElse { error ->
            AlertDialog.Builder(this).setTitle("Ошибка конфигурации")
                .setMessage(error.message ?: "Не удалось подготовить сервер")
                .setPositiveButton("ОК", null).show()
            return
        }
        pendingConfig = config

        // Android permits only one active VpnService at a time. Make this
        // explicit so users understand why their previous VPN will disconnect.
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val activeCapabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val anotherVpnIsActive = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (anotherVpnIsActive && !connected) {
            AlertDialog.Builder(this)
                .setTitle("Заменить текущее VPN?")
                .setMessage("Android может отключить сейчас работающий VPN при запуске Astra. Продолжить подключение?")
                .setNegativeButton("Отмена") { _, _ -> pendingConfig = null }
                .setPositiveButton("Продолжить") { _, _ -> requestVpnPermissionAndStart(config) }
                .show()
            return
        }
        requestVpnPermissionAndStart(config)
    }

    private fun requestVpnPermissionAndStart(config: String) {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
        } else startTunnel(config)
    }

    @Deprecated("Deprecated in Android API, retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) pendingConfig?.let { startTunnel(it) }
            else {
                pendingConfig = null
                status.text = "Разрешение VPN не выдано"
                status.setTextColor(RED)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter(AstraVpnService.ACTION_STATE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(vpnStateReceiver, filter)
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(vpnStateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun startTunnel(config: String) {
        val intent = Intent(this, AstraVpnService::class.java)
            .setAction(AstraVpnService.ACTION_START)
            .putExtra(AstraVpnService.EXTRA_CONFIG, config)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            pendingConfig = null
            status.text = "Подключаемся…"
            status.setTextColor(AMBER)
        } catch (error: Exception) {
            status.text = "Не удалось запустить VPN"
            status.setTextColor(RED)
            Toast.makeText(this, error.message ?: "Ошибка запуска VPN", Toast.LENGTH_LONG).show()
        }
        renderProfiles()
    }

    private fun stopTunnel() {
        val intent = Intent(this, AstraVpnService::class.java).setAction(AstraVpnService.ACTION_STOP)
        runCatching { startService(intent) }
        connected = false
        status.text = "Отключаемся…"
        status.setTextColor(MUTED)
        renderProfiles()
    }

    private fun profileName(link: String): String {
        val fragment = Uri.parse(link).fragment?.let { Uri.decode(it) }?.trim().orEmpty()
        if (fragment.isNotBlank()) return fragment
        val host = Uri.parse(link).host.orEmpty()
        return host.ifBlank { "Сервер" }
    }

    private fun protocolName(link: String): String = when (Uri.parse(link).scheme?.lowercase()) {
        "vless" -> "VLESS"
        "hysteria2", "hy2" -> "Hysteria2"
        "tuic" -> "TUIC"
        else -> "VPN"
    }

    private fun countryFlag(label: String): String {
        val value = Uri.decode(label).lowercase()
        return when {
            "🇸🇪" in label -> "🇸🇪"
            "🇳🇱" in label -> "🇳🇱"
            "🇬🇧" in label -> "🇬🇧"
            "🇩🇪" in label -> "🇩🇪"
            "🇫🇷" in label -> "🇫🇷"
            "🇷🇺" in label -> "🇷🇺"
            "🇫🇮" in label -> "🇫🇮"
            "🇵🇱" in label -> "🇵🇱"
            "🇹🇷" in label -> "🇹🇷"
            "🇺🇸" in label -> "🇺🇸"
            "швец" in value || "sweden" in value -> "🇸🇪"
            "нидер" in value || "голланд" in value || "nether" in value -> "🇳🇱"
            "англ" in value || "британ" in value || "united kingdom" in value || "england" in value -> "🇬🇧"
            "герман" in value || "german" in value -> "🇩🇪"
            "франц" in value || "france" in value -> "🇫🇷"
            "росс" in value || "russia" in value -> "🇷🇺"
            "финлянд" in value || "finland" in value -> "🇫🇮"
            "польш" in value || "poland" in value -> "🇵🇱"
            "турц" in value || "turkey" in value -> "🇹🇷"
            "сша" in value || "united states" in value || "usa" in value -> "🇺🇸"
            else -> "🌐"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        importExecutor.shutdownNow()
        pingExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 7001
        private const val REQUEST_NOTIFICATION_PERMISSION = 7002
        private const val BG = 0xFF0B1020.toInt()
        private const val CARD = 0xFF171F35.toInt()
        private const val SELECTED_CARD = 0xFF202D50.toInt()
        private const val ACCENT = 0xFF526FE8.toInt()
        private const val LIGHT_ACCENT = 0xFF9EAEFF.toInt()
        private const val GREEN = 0xFF55D69A.toInt()
        private const val AMBER = 0xFFFFC66D.toInt()
        private const val RED = 0xFFFF7777.toInt()
        private const val MUTED = 0xFF9AA6BF.toInt()
    }
}
