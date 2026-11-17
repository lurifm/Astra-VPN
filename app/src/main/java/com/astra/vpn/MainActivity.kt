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
import android.net.TrafficStats
import android.telephony.TelephonyManager
import android.os.Handler
import android.os.Looper
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private var activeProfile: String? = null
    private var pendingConfig: String? = null
    private var connected = false
    private var connecting = false
    private var receiverRegistered = false
    private var refreshingSubscriptions = false
    private var sortMode = "original"
    private val settingsPrefs by lazy { getSharedPreferences("astra_settings", MODE_PRIVATE) }
    private val autoUpdateHandler = Handler(Looper.getMainLooper())
    private val autoUpdateTick = object : Runnable {
        override fun run() {
            maybeAutoUpdateSubscriptions(false)
            autoUpdateHandler.postDelayed(this, 60_000L)
        }
    }
    private lateinit var monitorCard: LinearLayout
    private lateinit var monitorProtection: TextView
    private lateinit var monitorVpnCountry: TextView
    private lateinit var monitorNetwork: TextView
    private lateinit var monitorOperator: TextView
    private lateinit var monitorDownload: TextView
    private lateinit var monitorUpload: TextView
    private lateinit var monitorLocation: TextView
    private lateinit var speedGraph: SpeedGraphView
    private val monitorExecutor = Executors.newSingleThreadExecutor()
    private val monitorHandler = Handler(Looper.getMainLooper())
    private var lastRxBytes = TrafficStats.UNSUPPORTED.toLong()
    private var lastTxBytes = TrafficStats.UNSUPPORTED.toLong()
    private var lastTrafficAt = 0L
    private var originalCountry = "Определяем…"
    private var vpnCountry = "—"
    private var lastGeoConnectedState: Boolean? = null
    private val monitorTick = object : Runnable {
        override fun run() {
            updateTrafficMonitor()
            updateNetworkLabel()
            monitorHandler.postDelayed(this, 1000L)
        }
    }

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
            updateGeoLocationForState()
            updateProtectionLabels()
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
        val settingsButton = TextView(this).apply {
            text = "⚙"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(LIGHT_ACCENT)
            background = rounded(CARD, 16)
            setOnClickListener { showAppSettingsDialog() }
        }
        val brand = TextView(this).apply {
            text = "ASTRA VPN"
            textSize = 25f
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
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(brand, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(12) })
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
                if (connected && selectedProfile == activeProfile) stopTunnel() else connectSelectedProfile()
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
        monitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD, 20)
        }
        monitorCard.addView(TextView(this).apply {
            text = "МОНИТОРИНГ СОЕДИНЕНИЯ"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            setTextColor(LIGHT_ACCENT)
        }, matchWidth())
        monitorNetwork = TextView(this).apply {
            text = "Сеть: определяем…"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(2))
        }
        monitorOperator = TextView(this).apply {
            text = "Оператор: —"
            textSize = 13f
            setTextColor(MUTED)
        }
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        monitorDownload = TextView(this).apply {
            text = "↓ — Мбит/с"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(GREEN)
        }
        monitorUpload = TextView(this).apply {
            text = "↑ — Мбит/с"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.RIGHT
        }
        speedRow.addView(monitorDownload, LinearLayout.LayoutParams(0, dp(30), 1f))
        speedRow.addView(monitorUpload, LinearLayout.LayoutParams(0, dp(30), 1f))
        speedGraph = SpeedGraphView(this)
        monitorProtection = TextView(this).apply {
            text = "🛡 Защита: отключена"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
            setPadding(0, dp(10), 0, dp(4))
        }
        monitorVpnCountry = TextView(this).apply {
            text = "Страна выхода VPN: —"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(6))
        }
        monitorCard.addView(monitorProtection)
        monitorCard.addView(monitorVpnCountry)
        monitorCard.addView(monitorNetwork)
        monitorCard.addView(monitorOperator)
        monitorCard.addView(TextView(this).apply {
            text = "Скорость всего устройства"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        })
        monitorCard.addView(speedRow)
        monitorCard.addView(speedGraph, LinearLayout.LayoutParams(matchWidth().width, dp(82)))
        monitorLocation = TextView(this).apply {
            text = "IP-геолокация: определяем…"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        }
        monitorCard.addView(monitorLocation)
        root.addView(listHeader)
        root.addView(scroll, LinearLayout.LayoutParams(matchWidth().width, 0, 1f))
        setContentView(root)
        requestNotificationPermissionIfNeeded()
        requestPhoneStatePermissionIfNeeded()
        renderProfiles()
        updateNetworkLabel()
        // GeoIP is queried after restoreVpnState() determines whether a VPN is already active.
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
            .setMessage("Можно вставить ссылку на сервер или HTTPS-ссылку подписки. Импортированные профили хранятся зашифрованно на устройстве.")
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
        // Refreshing subscription data does not alter the currently active tunnel.
        if (refreshingSubscriptions) {
            Toast.makeText(this, "Обновление уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        val sources = safeSubscriptionSources() ?: return
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

    private var storageErrorShown = false

    private fun showStorageError(error: ProfileStorageException) {
        status.text = "Хранилище недоступно"
        status.setTextColor(RED)
        if (!storageErrorShown && !isFinishing && !isDestroyed) {
            storageErrorShown = true
            AlertDialog.Builder(this)
                .setTitle("Проблема защищённого хранилища")
                .setMessage(error.message ?: "Не удалось прочитать сохранённые серверы.")
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    private fun safeProfiles(): List<String> = try {
        profileStore.all()
    } catch (error: ProfileStorageException) {
        showStorageError(error)
        emptyList()
    }

    private fun safeSubscriptionSources(): List<String>? = try {
        profileStore.subscriptionSources()
    } catch (error: ProfileStorageException) {
        showStorageError(error)
        null
    }

    private fun safeSubscriptionSummaries(): List<SubscriptionSummary> = try {
        profileStore.subscriptionSummaries()
    } catch (error: ProfileStorageException) {
        showStorageError(error)
        emptyList()
    }

    private fun renderProfiles() {
        profileList.removeAllViews()
        val summaries = safeSubscriptionSummaries()
        if (summaries.isNotEmpty()) {
            profileList.addView(TextView(this).apply {
                text = "ПОДПИСКИ"
                textSize = 11f
                letterSpacing = .10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LIGHT_ACCENT)
                setPadding(dp(2), dp(2), 0, dp(8))
            })
            summaries.forEach { subscription ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(13), dp(15), dp(13))
                    background = rounded(CARD, 16)
                    isClickable = true
                    setOnClickListener { refreshSubscriptions() }
                }
                card.addView(TextView(this).apply {
                    text = "☁  ${subscription.title}"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    maxLines = 1
                })
                card.addView(TextView(this).apply {
                    text = "${subscription.serverCount} серверов  ·  HTTPS-подписка"
                    textSize = 12f
                    setTextColor(LIGHT_ACCENT)
                    setPadding(0, dp(5), 0, dp(3))
                })
                card.addView(TextView(this).apply {
                    text = "Трафик и срок действия: нет данных от провайдера"
                    textSize = 11f
                    setTextColor(MUTED)
                })
                profileList.addView(card, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(9)
                })
            }
            profileList.addView(TextView(this).apply {
                text = "СЕРВЕРЫ"
                textSize = 11f
                letterSpacing = .10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LIGHT_ACCENT)
                setPadding(dp(2), dp(8), 0, dp(8))
            })
        }
        val profiles = safeProfiles()
        if (selectedProfile == null || selectedProfile !in profiles) selectedProfile = profiles.firstOrNull()
        val selected = selectedProfile
        connectButton.isEnabled = connected || (selected != null && !connecting)
        connectButton.text = when {
            connected && selected != activeProfile -> "ПЕРЕКЛЮЧИТЬ СЕРВЕР"
            connected -> "ОТКЛЮЧИТЬ VPN"
            connecting -> "ПОДКЛЮЧАЕМСЯ…"
            else -> "ПОДКЛЮЧИТЬСЯ"
        }
        selectedInfo.text = selected?.let {
            if (connected && it == activeProfile) "🛡 Защищено · ${profileSummary(it)}" else profileSummary(it)
        } ?: "Сначала добавь сервер"

        if (profiles.isEmpty()) {
            profileList.addView(TextView(this).apply {
                text = "Пока нет серверов. Нажми + и импортируй ссылку подписки."
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
            return
        }

        val displayProfiles = when (sortMode) {
            "ping" -> profiles.sortedWith(compareBy<String> { latencyMs[it] ?: Long.MAX_VALUE })
            "alphabet" -> profiles.sortedBy { profileName(it).lowercase(java.util.Locale.getDefault()) }
            else -> profiles
        }

        displayProfiles.forEach { link ->
            val isSelected = link == selected
            val name = profileName(link)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(10), dp(14))
                background = serverRowBackground(isSelected)
                elevation = dp(if (isSelected) 3 else 0).toFloat()
                setOnClickListener {
                    if (!connecting) {
                        selectedProfile = link
                        renderProfiles()
                    } else {
                        Toast.makeText(this@MainActivity, "Дождись завершения текущего переключения", Toast.LENGTH_SHORT).show()
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
                                try {
                                    profileStore.remove(link)
                                } catch (error: ProfileStorageException) {
                                    showStorageError(error)
                                    return@setPositiveButton
                                }
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
                text = CountryFlagResolver.flagFor(name)
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
        val profiles = safeProfiles()
        if (profiles.isEmpty()) return

        Toast.makeText(this, "Проверяем доступность серверов…", Toast.LENGTH_SHORT).show()

        // Update non-VLESS labels in memory without rebuilding the entire list
        // once per profile. Re-rendering 45 rows 45 times can freeze the UI.
        val vlessProfiles = profiles.filter { link ->
            val uri = Uri.parse(link)
            val scheme = uri.scheme.orEmpty().lowercase()
            if (scheme != "vless") {
                pingInfo[link] = if (scheme == "hysteria2" || scheme == "hy2" || scheme == "tuic") {
                    "UDP • TCP-тест не применяется"
                } else {
                    "—"
                }
                false
            } else {
                !uri.host.isNullOrBlank() && uri.port in 1..65535
            }
        }

        if (vlessProfiles.isEmpty()) {
            renderProfiles()
            return
        }

        // Probe on the bounded background pool, then redraw once after the
        // whole batch finishes instead of posting dozens of full list rebuilds.
        val remaining = java.util.concurrent.atomic.AtomicInteger(vlessProfiles.size)
        vlessProfiles.forEach { link ->
            val uri = Uri.parse(link)
            pingInfo[link] = "Проверяем…"
            pingExecutor.execute {
                val result = runCatching { TcpLatencyProbe.measure(uri.host.orEmpty(), uri.port) }
                result.onSuccess {
                    latencyMs[link] = it.tcpMedianMs
                    pingInfo[link] = "DNS ${it.dnsMs} мс · TCP ${it.tcpMedianMs} мс (${it.successfulSamples}/3)"
                }.onFailure {
                    latencyMs.remove(link)
                    pingInfo[link] = "TCP недоступен"
                }

                if (remaining.decrementAndGet() == 0) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) renderProfiles()
                    }
                }
            }
        }
        renderProfiles()
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
        val candidates = safeProfiles().filter {
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
                val result = runCatching { TcpLatencyProbe.measure(uri.host.orEmpty(), uri.port) }
                synchronized(latencyMs) {
                    result.onSuccess {
                        latencyMs[link] = it.tcpMedianMs
                        pingInfo[link] = "DNS ${it.dnsMs} мс · TCP ${it.tcpMedianMs} мс (3 замера)"
                    }.onFailure {
                        latencyMs.remove(link)
                        pingInfo[link] = "TCP недоступен"
                    }
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
            val waitSeconds = batches * 6L + 2L
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
        if (connecting) return
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
        monitorHandler.removeCallbacks(monitorTick)
        monitorHandler.post(monitorTick)
        if (!receiverRegistered) {
            val filter = IntentFilter(AstraVpnService.ACTION_STATE)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(vpnStateReceiver, filter)
            receiverRegistered = true
        }
        sortMode = settingsPrefs.getString("sort_mode", "original") ?: "original"
        restoreVpnState()
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        autoUpdateHandler.postDelayed(autoUpdateTick, 60_000L)
        maybeAutoUpdateSubscriptions(true)
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
        updateGeoLocationForState()
        updateProtectionLabels()
    }

    override fun onStop() {
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        monitorHandler.removeCallbacks(monitorTick)
        if (receiverRegistered) {
            unregisterReceiver(vpnStateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun startTunnel(config: String) {
        activeProfile = selectedProfile
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

    private fun underlyingNetworkCapabilities(): NetworkCapabilities? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks
            .asSequence()
            .mapNotNull { network -> connectivity.getNetworkCapabilities(network) }
            .firstOrNull { caps ->
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
    }

    private fun updateNetworkLabel() {
        val caps = underlyingNetworkCapabilities()
        val telephony = getSystemService(TelephonyManager::class.java)
        when {
            caps == null -> {
                monitorNetwork.text = "Сеть: не определена"
                monitorOperator.text = "Оператор: —"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                monitorNetwork.text = "Сеть: Wi-Fi"
                val simOperator = if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runCatching { telephony.simOperatorName }.getOrNull().orEmpty()
                } else ""
                monitorOperator.text = "Оператор SIM: " + simOperator.ifBlank { "не определён" }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val networkType = if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runCatching {
                        when (telephony.dataNetworkType) {
                            TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                            TelephonyManager.NETWORK_TYPE_NR -> "5G"
                            TelephonyManager.NETWORK_TYPE_HSPAP,
                            TelephonyManager.NETWORK_TYPE_HSPA,
                            TelephonyManager.NETWORK_TYPE_HSUPA,
                            TelephonyManager.NETWORK_TYPE_HSDPA -> "3G"
                            TelephonyManager.NETWORK_TYPE_EDGE,
                            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                            else -> "мобильная сеть"
                        }
                    }.getOrDefault("мобильная сеть")
                } else "мобильная сеть"
                val operator = if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runCatching { telephony.networkOperatorName }.getOrNull().orEmpty()
                } else ""
                monitorNetwork.text = "Сеть: мобильная · $networkType"
                monitorOperator.text = "Оператор: " + operator.ifBlank { "разрешение не предоставлено" }
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                monitorNetwork.text = "Сеть: Ethernet"
                monitorOperator.text = "Оператор: —"
            }
            else -> {
                monitorNetwork.text = "Сеть: другое подключение"
                monitorOperator.text = "Оператор: —"
            }
        }
    }

    private fun updateTrafficMonitor() {
        // Android exposes aggregate device counters without special usage-access
        // permission. These include background apps, so label them as device-wide.
        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            monitorDownload.text = "↓ недоступно"
            monitorUpload.text = "↑ недоступно"
            return
        }
        if (lastTrafficAt > 0L && lastRxBytes >= 0L && lastTxBytes >= 0L) {
            val elapsed = ((now - lastTrafficAt).coerceAtLeast(1L)) / 1000.0
            val down = ((rx - lastRxBytes).coerceAtLeast(0L) * 8.0 / elapsed / 1_000_000.0).toFloat()
            val up = ((tx - lastTxBytes).coerceAtLeast(0L) * 8.0 / elapsed / 1_000_000.0).toFloat()
            monitorDownload.text = "↓ " + String.format(java.util.Locale.US, "%.2f Мбит/с", down)
            monitorUpload.text = "↑ " + String.format(java.util.Locale.US, "%.2f Мбит/с", up)
            speedGraph.addSample(down, up)
        }
        lastRxBytes = rx
        lastTxBytes = tx
        lastTrafficAt = now
    }

    private fun updateGeoLocationForState() {
        if (lastGeoConnectedState == connected) return
        lastGeoConnectedState = connected
        requestGeoLocation(connected)
    }

    private fun requestGeoLocation(isVpn: Boolean) {
        monitorExecutor.execute {
            val countryResult = runCatching {
                val connection = (URL("https://ipwho.is/").openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    requestMethod = "GET"
                }
                try {
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    if (!json.optBoolean("success", false)) error("GeoIP service returned no result")
                    val country = json.optString("country", "").ifBlank { "Не определено" }
                    val countryCode = json.optString("country_code", "")
                    val flag = if (countryCode.length == 2) {
                        countryCode.uppercase().map { Character.toString(0x1F1E6 + it.code - 'A'.code) }.joinToString("")
                    } else "🌍"
                    "$flag $country"
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                countryResult.onSuccess { country ->
                    if (isVpn) vpnCountry = country else originalCountry = country
                    monitorLocation.text = "До VPN: $originalCountry  ·  Сейчас: " +
                        if (connected) vpnCountry else originalCountry
                    updateProtectionLabels()
                    monitorVpnCountry.text = "Страна выхода VPN: " + if (connected) vpnCountry else "VPN отключён"
                }.onFailure {
                    monitorLocation.text = "Геолокация по IP временно недоступна"
                }
            }
        }
    }

    private fun updateProtectionLabels() {
        monitorProtection.text = if (connected) "🛡 Трафик защищён · VPN активен" else if (connecting) "◌ Подключение VPN…" else "○ Защита выключена"
        monitorProtection.setTextColor(if (connected) GREEN else if (connecting) AMBER else MUTED)
        if (!connected) monitorVpnCountry.text = "Страна выхода VPN: —"
        else monitorVpnCountry.text = "Страна выхода VPN: $vpnCountry"
    }

    private fun showNetworkMonitorDialog() {
        val content = ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            (monitorCard.parent as? ViewGroup)?.removeView(monitorCard)
            addView(monitorCard)
        }
        AlertDialog.Builder(this)
            .setTitle("Мониторинг сети")
            .setView(content)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun requestPhoneStatePermissionIfNeeded() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), REQUEST_PHONE_STATE_PERMISSION)
        }
    }

    private fun showAppSettingsDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        panel.addView(TextView(this).apply {
            text = "Сортировка серверов"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(6))
        })
        val sortGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val currentMode = settingsPrefs.getString("sort_mode", "original") ?: "original"
        listOf(
            "original" to "Как в подписке",
            "ping" to "По пингу (от меньшего к большему)",
            "alphabet" to "По алфавиту"
        ).forEach { (value, label) ->
            sortGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = value
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = currentMode == value
            })
        }
        panel.addView(sortGroup)
        val monitorEntry = TextView(this).apply {
            text = "📊  Мониторинг сети  ›"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(0, dp(16), 0, dp(14))
            setOnClickListener { showNetworkMonitorDialog() }
        }
        panel.addView(monitorEntry)

        val appRoutingEntry = TextView(this).apply {
            text = "📱  Приложения через VPN  ›"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(0, dp(12), 0, dp(14))
            setOnClickListener { showAppRoutingDialog() }
        }
        panel.addView(appRoutingEntry)
        val autoUpdate = CheckBox(this).apply {
            text = "Автоматически обновлять подписки"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("auto_update", false)
        }
        panel.addView(autoUpdate)
        val updateOnOpen = CheckBox(this).apply {
            text = "Обновлять при открытии приложения"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("update_on_open", false)
        }
        panel.addView(updateOnOpen)
        val interval = TextView(this).apply {
            textSize = 14f
            setTextColor(LIGHT_ACCENT)
            setPadding(0, dp(8), 0, dp(8))
            fun refreshLabel() {
                val hours = settingsPrefs.getInt("update_interval_hours", 2)
                text = "Интервал обновления: $hours ч · Изменить"
            }
            refreshLabel()
            setOnClickListener {
                val values = arrayOf("1 час", "2 часа", "6 часов", "12 часов", "24 часа")
                val hours = intArrayOf(1, 2, 6, 12, 24)
                val selected = hours.indexOf(settingsPrefs.getInt("update_interval_hours", 2)).coerceAtLeast(0)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Интервал автообновления")
                    .setSingleChoiceItems(values, selected) { dialog, which ->
                        settingsPrefs.edit().putInt("update_interval_hours", hours[which]).apply()
                        refreshLabel()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
        panel.addView(interval)
        AlertDialog.Builder(this)
            .setTitle("Настройки Astra")
            .setView(panel)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val selectedId = sortGroup.checkedRadioButtonId
                val mode = sortGroup.findViewById<RadioButton>(selectedId)?.tag as? String ?: "original"
                sortMode = mode
                settingsPrefs.edit()
                    .putString("sort_mode", mode)
                    .putBoolean("auto_update", autoUpdate.isChecked)
                    .putBoolean("update_on_open", updateOnOpen.isChecked)
                    .apply()
                renderProfiles()
                maybeAutoUpdateSubscriptions(true)
            }
            .show()
    }


    private data class InstalledApp(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable
    )

    private fun showAppRoutingDialog() {
        val prefs = settingsPrefs
        val selected = prefs.getStringSet("vpn_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val mode = prefs.getString("vpn_app_mode", "exclude") ?: "exclude"
        val allowMode = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Только выбранные приложения через VPN"
            tag = "include"
            isChecked = mode == "include"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val excludeMode = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Все приложения через VPN, кроме выбранных"
            tag = "exclude"
            isChecked = mode != "include"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        modeGroup.addView(excludeMode)
        modeGroup.addView(allowMode)
        val apps = runCatching {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { info ->
                    val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                    val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName
                    InstalledApp(packageName, label, info.loadIcon(packageManager))
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase(java.util.Locale.getDefault()) }
        }.getOrElse { emptyList() }
        val checks = mutableMapOf<String, CheckBox>()
        val appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.forEach { app ->
            val check = CheckBox(this).apply {
                text = app.label
                tag = app.packageName
                isChecked = app.packageName in selected
                textSize = 14f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(LIGHT_ACCENT)
                setCompoundDrawablesWithIntrinsicBounds(app.icon, null, null, null)
                compoundDrawablePadding = dp(12)
                setPadding(0, dp(5), 0, dp(5))
            }
            checks[app.packageName] = check
            appList.addView(check)
        }
        val listScroll = ScrollView(this).apply {
            addView(appList)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)
            )
        }
        val appSearch = EditText(this).apply {
            hint = "Найти приложение…"
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(CARD, 12)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString()?.trim()?.lowercase(java.util.Locale.getDefault()).orEmpty()
                    apps.forEach { app ->
                        checks[app.packageName]?.visibility = if (query.isEmpty() ||
                            app.label.lowercase(java.util.Locale.getDefault()).contains(query)) View.VISIBLE else View.GONE
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
            addView(TextView(this@MainActivity).apply {
                text = "Выбери приложения и режим маршрутизации. Изменения применятся после следующего подключения VPN."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, 0, 0, dp(10))
            })
            addView(modeGroup)
            addView(appSearch, matchWidth().apply { bottomMargin = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = "Приложения"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(listScroll)
        }
        AlertDialog.Builder(this)
            .setTitle("Маршрутизация приложений")
            .setView(content)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val packages = checks.filterValues { it.isChecked }.keys
                val chosenMode = modeGroup.findViewById<RadioButton>(modeGroup.checkedRadioButtonId)
                    ?.tag as? String ?: "exclude"
                prefs.edit()
                    .putStringSet("vpn_app_packages", packages.toSet())
                    .putString("vpn_app_mode", chosenMode)
                    .apply()
                Toast.makeText(
                    this,
                    if (chosenMode == "include" && packages.isEmpty())
                        "Список пуст: через VPN не пойдут выбранные приложения"
                    else "Правила сохранены. Переподключи VPN для применения.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun maybeAutoUpdateSubscriptions(onOpen: Boolean) {
        if (refreshingSubscriptions) return
        val enabled = settingsPrefs.getBoolean("auto_update", false)
        val updateOnOpen = settingsPrefs.getBoolean("update_on_open", false)
        val now = System.currentTimeMillis()
        val last = settingsPrefs.getLong("last_subscription_update", 0L)
        val intervalMs = settingsPrefs.getInt("update_interval_hours", 2) * 60L * 60L * 1000L
        val shouldUpdate = (onOpen && updateOnOpen && now - last > 5 * 60_000L) ||
            (enabled && now - last >= intervalMs)
        if (!shouldUpdate) return
        val sources = safeSubscriptionSources() ?: return
        if (sources.isEmpty()) return
        settingsPrefs.edit().putLong("last_subscription_update", now).apply()
        refreshSubscriptions()
    }

    private fun profileName(link: String): String {
        val fragment = Uri.parse(link).fragment?.let { Uri.decode(it) }?.trim().orEmpty()
        if (fragment.isNotBlank()) return fragment
        val host = Uri.parse(link).host.orEmpty()
        return host.ifBlank { "Сервер" }
    }

    private fun profileSummary(link: String): String {
        val flag = CountryFlagResolver.flagFor(profileName(link))
        val latency = pingInfo[link] ?: "Задержка не проверена"
        return "$flag  ${profileName(link)}  ·  ${protocolName(link)}\n$latency"
    }

    private fun protocolName(link: String): String = when (Uri.parse(link).scheme?.lowercase()) {
        "vless" -> "VLESS"
        "hysteria2", "hy2" -> "Hysteria2"
        "tuic" -> "TUIC"
        else -> "VPN"
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

    /** Subtle outline makes the selected server unmistakable without visual clutter. */
    private fun serverRowBackground(selected: Boolean) = GradientDrawable().apply {
        setColor(if (selected) SELECTED_CARD else CARD)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(if (selected) 2 else 1), if (selected) ACCENT else 0xFF26324B.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        monitorHandler.removeCallbacks(monitorTick)
        monitorExecutor.shutdownNow()
        importExecutor.shutdownNow()
        pingExecutor.shutdownNow()
        autoSelectExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 7001
        private const val REQUEST_NOTIFICATION_PERMISSION = 7002
        private const val REQUEST_PHONE_STATE_PERMISSION = 7003
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
