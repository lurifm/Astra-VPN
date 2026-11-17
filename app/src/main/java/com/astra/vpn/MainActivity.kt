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
    private val pingInfo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val latencyMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newFixedThreadPool(3)
    // Keep auto-selection probes isolated from background list refresh probes.
    private val autoSelectExecutor = Executors.newFixedThreadPool(3)
    private var selectingFastest = false
    private var selectedProfile: String? = null
    private var pendingConfig: String? = null
    private var connected = false
    private var connecting = false
    private var receiverRegistered = false
    private var refreshingSubscriptions = false

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AstraVpnService.ACTION_STATE) return
            when (intent.getStringExtra(AstraVpnService.EXTRA_STATE)) {
                AstraVpnService.STATE_CONNECTING -> {
                    connected = false
                    connecting = true
                    status.text = "Создаём VPN-туннель…"
                    status.setTextColor(AMBER)
                }
                AstraVpnService.STATE_CONNECTED -> {
                    connected = true
                    connecting = false
                    status.text = "Подключено"
                    status.setTextColor(GREEN)
                }
                AstraVpnService.STATE_DISCONNECTED -> {
                    connected = false
                    connecting = false
                    status.text = "Отключено"
                    status.setTextColor(MUTED)
                }
                AstraVpnService.STATE_ERROR -> {
                    connected = false
                    connecting = false
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
        val updateSubscriptions = TextView(this).apply {
            text = "Обновить"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { refreshSubscriptions() }
        }
        val refresh = TextView(this).apply {
            text = "Автовыбор"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setOnClickListener { selectFastestServer() }
        }
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, dp(32), 1f))
        listHeader.addView(updateSubscriptions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
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
        // Populate visible latency information as soon as saved profiles are loaded.
        checkAllLatencies()
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
                            // Keep network, parsing and encrypted persistence off the UI thread.
                            // A storage failure is reported in the dialog instead of crashing Activity.
                            val result = runCatching {
                                val links = SubscriptionParser.importText(value)
                                if (value.startsWith("https://", ignoreCase = true)) {
                                    profileStore.replaceSubscription(value, links)
                                } else {
                                    profileStore.addAll(links)
                                }
                                links
                            }
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                dialog.dismiss()
                                result.onSuccess { links ->
                                    renderProfiles()
                                    checkAllLatencies()
                                    status.text = "Импортировано: ${links.size}"
                                    status.setTextColor(GREEN)
                                    Toast.makeText(this, "Добавлено серверов: ${links.size}", Toast.LENGTH_LONG).show()
                                }.onFailure { error ->
                                    status.text = "Ошибка импорта"
                                    status.setTextColor(RED)
                                    AlertDialog.Builder(this)
                                        .setTitle("Не удалось импортировать")
                                        .setMessage(error.message ?: "Неизвестная ошибка")
                                        .setPositiveButton("ОК", null).show()
                                }
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    /**
     * Refresh each previously imported HTTPS subscription independently.
     * The previous server set is kept whenever download, parsing or storage fails.
     */
    private fun refreshSubscriptions() {
        if (connected || connecting) {
            Toast.makeText(this, "Отключи VPN перед обновлением подписок", Toast.LENGTH_SHORT).show()
            return
        }
        if (refreshingSubscriptions) {
            Toast.makeText(this, "Обновление уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        val sources = profileStore.subscriptionSources()
        if (sources.isEmpty()) {
            Toast.makeText(this, "Сначала импортируй HTTPS-ссылку подписки", Toast.LENGTH_LONG).show()
            return
        }
        refreshingSubscriptions = true
        status.text = "Обновляем подписки…"
        status.setTextColor(AMBER)
        importExecutor.execute {
            var updated = 0
            var totalProfiles = 0
            val errors = mutableListOf<String>()
            sources.forEachIndexed { index, source ->
                val result = runCatching {
                    val links = SubscriptionParser.importText(source)
                    profileStore.replaceSubscription(source, links)
                    links.size
                }
                result.onSuccess {
                    updated++
                    totalProfiles += it
                }.onFailure {
                    errors += "Подписка ${index + 1}: ${it.message ?: "ошибка обновления"}"
                }
            }
            runOnUiThread {
                refreshingSubscriptions = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderProfiles()
                checkAllLatencies()
                status.text = if (errors.isEmpty()) "Подписки обновлены" else "Обновление завершено с ошибками"
                status.setTextColor(if (errors.isEmpty()) GREEN else AMBER)
                val summary = buildString {
                    append("Успешно обновлено: $updated из ${sources.size}.\nСерверов получено: $totalProfiles.")
                    if (errors.isNotEmpty()) append("\n\n").append(errors.joinToString("\n"))
                    append("\n\nПри ошибке предыдущие серверы этой подписки сохранены.")
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Обновление подписок")
                    .setMessage(summary)
                    .setPositiveButton("ОК", null)
                    .show()
            }
        }
    }

    private fun renderProfiles() {
        profileList.removeAllViews()
        val profiles = profileStore.all()
        if (selectedProfile == null || selectedProfile !in profiles) selectedProfile = profiles.firstOrNull()
        val selected = selectedProfile
        connectButton.isEnabled = connected || (selected != null && !connecting)
        connectButton.text = when {
            connected -> "ОТКЛЮЧИТЬ VPN"
            connecting -> "ПОДКЛЮЧАЕМСЯ…"
            else -> "ПОДКЛЮЧИТЬСЯ"
        }
        selectedInfo.text = selected?.let { profileSummary(it) } ?: "Сначала добавь сервер"

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
                    if (!connected && !connecting) {
                        selectedProfile = link
                        renderProfiles()
                    } else {
                        Toast.makeText(this@MainActivity, "Дождись завершения подключения или отключи VPN", Toast.LENGTH_SHORT).show()
                    }
                }
                setOnLongClickListener {
                    if (connected || connecting) {
                        Toast.makeText(this@MainActivity, "Сначала отключи VPN", Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Удалить сервер?")
                            .setMessage("Профиль «$name» будет удалён с этого устройства.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить") { _, _ ->
                                profileStore.remove(link)
                                pingInfo.remove(link)
                                latencyMs.remove(link)
                                if (selectedProfile == link) selectedProfile = null
                                renderProfiles()
                                Toast.makeText(this@MainActivity, "Сервер удалён", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    true
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
                    "UDP • TCP-тест не применяется" else "—"
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
                result.onSuccess { latencyMs[link] = it; pingInfo[link] = "${it} мс (TCP)" }
                    .onFailure { latencyMs.remove(link); pingInfo[link] = "TCP недоступен" }
                runOnUiThread { if (!isFinishing) renderProfiles() }
            }
        }
    }

    /**
     * Chooses the fastest VLESS endpoint using a short TCP reachability test.
     * UDP profiles are deliberately excluded: a TCP result says nothing about
     * Hysteria2/TUIC availability or actual VPN tunnel latency.
     */
    private fun selectFastestServer() {
        if (selectingFastest) {
            Toast.makeText(this, "Проверка серверов уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        if (connected || connecting) {
            Toast.makeText(this, "Дождись завершения подключения или отключи VPN перед сменой сервера", Toast.LENGTH_SHORT).show()
            return
        }
        val candidates = profileStore.all().filter {
            Uri.parse(it).scheme.equals("vless", ignoreCase = true) &&
                !Uri.parse(it).host.isNullOrBlank() && Uri.parse(it).port > 0
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Для автовыбора пока нужны VLESS-серверы. UDP-серверы не оцениваем TCP-тестом.", Toast.LENGTH_LONG).show()
            return
        }
        selectingFastest = true
        Toast.makeText(this, "Ищем самый быстрый VLESS-сервер…", Toast.LENGTH_SHORT).show()
        candidates.forEach { latencyMs.remove(it); pingInfo[it] = "Проверяем…" }
        renderProfiles()
        val latch = java.util.concurrent.CountDownLatch(candidates.size)
        candidates.forEach { link ->
            autoSelectExecutor.execute {
                val uri = Uri.parse(link)
                val started = System.nanoTime()
                val result = runCatching {
                    Socket().use { socket -> socket.connect(InetSocketAddress(uri.host, uri.port), 3000) }
                    ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                }
                synchronized(latencyMs) {
                    result.onSuccess { latencyMs[link] = it; pingInfo[link] = "${it} мс (TCP)" }
                        .onFailure { latencyMs.remove(link); pingInfo[link] = "TCP недоступен" }
                }
                latch.countDown()
                runOnUiThread { if (!isFinishing && !isDestroyed) renderProfiles() }
            }
        }
        importExecutor.execute {
            // Three probes run concurrently; allow one full 3-second timeout
            // per batch so large subscriptions are not ranked from only the
            // first few servers.
            val batches = (candidates.size + 2) / 3
            val waitSeconds = batches * 3L + 1L
            val completed = runCatching {
                latch.await(waitSeconds, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrDefault(false)
            val fastest = synchronized(latencyMs) {
                candidates.mapNotNull { link -> latencyMs[link]?.let { link to it } }
                    .minByOrNull { it.second }?.first
            }
            runOnUiThread {
                selectingFastest = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (fastest != null) {
                    selectedProfile = fastest
                    status.text = if (completed) "Выбран быстрый сервер" else "Выбран лучший из проверенных"
                    status.setTextColor(GREEN)
                    selectedInfo.text = profileSummary(fastest)
                    Toast.makeText(this, "Выбран сервер: ${profileName(fastest)}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Не удалось проверить доступность VLESS-серверов", Toast.LENGTH_LONG).show()
                }
                renderProfiles()
            }
        }
    }

    private fun connectSelectedProfile() {
        if (connected || connecting) return
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
        restoreVpnState()
    }

    private fun restoreVpnState() {
        val saved = getSharedPreferences(AstraVpnService.STATE_PREFS, MODE_PRIVATE)
            .getString(AstraVpnService.PREF_STATE, AstraVpnService.STATE_DISCONNECTED)
        val statePrefs = getSharedPreferences(AstraVpnService.STATE_PREFS, MODE_PRIVATE)
        val stateAge = System.currentTimeMillis() - statePrefs.getLong(AstraVpnService.PREF_UPDATED_AT, 0L)
        val connectingIsRecent = saved == AstraVpnService.STATE_CONNECTING && stateAge in 0..30_000L
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val androidVpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        // A saved flag alone is insufficient: Android may have revoked the
        // tunnel while the Activity was in the background.
        connected = saved == AstraVpnService.STATE_CONNECTED && androidVpnActive
        when {
            connected -> {
                status.text = "Подключено"
                status.setTextColor(GREEN)
            }
            connectingIsRecent -> {
                connected = false
                connecting = true
                status.text = "Подключаемся…"
                status.setTextColor(AMBER)
            }
            else -> {
                connected = false
                connecting = false
                status.text = "Отключено"
                status.setTextColor(MUTED)
            }
        }
        renderProfiles()
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
            connecting = true
            status.text = "Подключаемся…"
            status.setTextColor(AMBER)
        } catch (error: Exception) {
            // Do not leave a stale configuration behind after a failed service start.
            pendingConfig = null
            connecting = false
            connected = false
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
        connecting = false
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

    private fun profileSummary(link: String): String {
        val flag = countryFlag(profileName(link))
        val latency = pingInfo[link] ?: "Задержка не проверена"
        return "$flag  ${profileName(link)}  ·  ${protocolName(link)}\n$latency"
    }

    private fun protocolName(link: String): String = when (Uri.parse(link).scheme?.lowercase()) {
        "vless" -> "VLESS"
        "hysteria2", "hy2" -> "Hysteria2"
        "tuic" -> "TUIC"
        else -> "VPN"
    }

    private fun countryFlag(label: String): String {
        val value = Uri.decode(label).lowercase()
        val flags = listOf(
            listOf("🇸🇪", "швец", "sweden", "swe"),
            listOf("🇳🇱", "нидер", "голланд", "netherlands", "holland", "nld"),
            listOf("🇬🇧", "британ", "великобрит", "united kingdom", "england", "uk", "gb", "gbr"),
            listOf("🇩🇪", "герман", "germany", "deu"),
            listOf("🇫🇷", "франц", "france", "fra"),
            listOf("🇷🇺", "росс", "russia", "rus"),
            listOf("🇫🇮", "финлянд", "finland", "fin"),
            listOf("🇵🇱", "польш", "poland", "pol"),
            listOf("🇹🇷", "турц", "turkey", "tur"),
            listOf("🇺🇸", "сша", "united states", "america", "us", "usa"),
            listOf("🇨🇦", "канад", "canada", "can"),
            listOf("🇨🇭", "швейцар", "switzerland", "che"),
            listOf("🇦🇹", "австри", "austria", "aut"),
            listOf("🇮🇹", "итал", "italy", "ita"),
            listOf("🇪🇸", "испани", "spain", "esp"),
            listOf("🇳🇴", "норвег", "norway", "nor"),
            listOf("🇩🇰", "дани", "denmark", "dnk"),
            listOf("🇧🇪", "бельги", "belgium", "bel"),
            listOf("🇨🇿", "чех", "czech", "cze"),
            listOf("🇸🇰", "словаки", "slovakia", "svk"),
            listOf("🇭🇺", "венгр", "hungary", "hun"),
            listOf("🇷🇴", "румын", "romania", "rou"),
            listOf("🇧🇬", "болгар", "bulgaria", "bgr"),
            listOf("🇪🇪", "эстон", "estonia", "est"),
            listOf("🇱🇻", "латви", "latvia", "lva"),
            listOf("🇱🇹", "литв", "lithuania", "ltu"),
            listOf("🇺🇦", "украин", "ukraine", "ukr"),
            listOf("🇰🇿", "казах", "kazakhstan", "kaz"),
            listOf("🇯🇵", "япони", "japan", "jpn"),
            listOf("🇸🇬", "сингапур", "singapore", "sgp"),
            listOf("🇰🇷", "коре", "south korea", "kor"),
            listOf("🇭🇰", "гонконг", "hong kong", "hkg"),
            listOf("🇦🇪", "оаэ", "uae", "emirates"),
            listOf("🇮🇳", "инд", "india", "ind"),
            listOf("🇧🇷", "браз", "brazil", "bra")
        )
        // ISO alpha-3 aliases must match as standalone tokens. A plain substring
        // check mislabels unrelated names (for example, "can" inside "scan").
        val normalized = value.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return flags.firstOrNull { entry ->
            entry.drop(1).any { token ->
                when {
                    token.length <= 3 && token.all { it.isLetter() } ->
                        Regex("(^|\\s)${Regex.escape(token)}($|\\s)").containsMatchIn(normalized)
                    else -> normalized.contains(token)
                }
            }
        }?.first() ?: "🌐"
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
        autoSelectExecutor.shutdownNow()
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
