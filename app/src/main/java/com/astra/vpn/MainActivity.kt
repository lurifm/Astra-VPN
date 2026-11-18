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
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var profileStore: ProfileStore
    private lateinit var profileList: LinearLayout
    private lateinit var profileScroll: ScrollView
    private lateinit var serverSearch: EditText
    private lateinit var countryFilter: Spinner
    private lateinit var status: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var selectedInfo: TextView
    private val pingInfo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val latencyMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val latencyCheckGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var urlTestProfileMap: Map<String, String> = emptyMap()
    private var latencyTestInFlight = false
    private var pendingFastestSelection = false
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
    private val connectionHistory by lazy { ConnectionHistoryStore(this) }
    private var refreshingSubscriptions = false
    private var sortMode = "original"
    private val settingsPrefs by lazy { getSharedPreferences("astra_settings", MODE_PRIVATE) }
    private val autoUpdateHandler = Handler(Looper.getMainLooper())
    private val searchRenderHandler = Handler(Looper.getMainLooper())
    private val searchRenderTask = Runnable { if (!isFinishing && !isDestroyed) renderProfiles() }
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
    private val healthCheckExecutor = Executors.newSingleThreadExecutor()
    private val healthCheckInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastHealthCheckAt = 0L
    @Volatile private var tunnelInternetOk: Boolean? = null
    private var originalCountry = "Определяем…"
    private var vpnCountry = "—"
    private var lastGeoConnectedState: Boolean? = null
    private val monitorTick = object : Runnable {
        override fun run() {
            updateTrafficMonitor()
            updateNetworkLabel()
            scheduleTunnelHealthCheck()
            monitorHandler.postDelayed(this, 1000L)
        }
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AstraLatencyTestService.ACTION_RESULTS -> {
                    val payload = intent.getStringExtra(AstraLatencyTestService.EXTRA_RESULTS) ?: return
                    applyLatencyResults(payload)
                    return
                }
                AstraLatencyTestService.ACTION_ERROR -> {
                    latencyTestInFlight = false
                    pendingFastestSelection = false
                    selectingFastest = false
                    urlTestProfileMap.values.forEach { profile ->
                        latencyMs.remove(profile)
                        pingInfo[profile] = "н/д"
                    }
                    val message = intent.getStringExtra(AstraLatencyTestService.EXTRA_ERROR) ?: "Не удалось проверить серверы"
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                            renderProfiles()
                        }
                    }
                    return
                }
                AstraVpnService.ACTION_STATE -> Unit
                else -> return
            }
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
                    tunnelInternetOk = null
                    lastHealthCheckAt = 0L
                    status.text = "Подключено"
                    status.setTextColor(GREEN)
                }
                AstraVpnService.STATE_DISCONNECTED -> {
                    connected = false
                    connecting = false
                    tunnelInternetOk = null
                    status.text = "Отключено"
                    status.setTextColor(MUTED)
                }
                AstraVpnService.STATE_ERROR -> {
                    connected = false
                    connecting = false
                    tunnelInternetOk = false
                    status.text = "Ошибка подключения"
                    status.setTextColor(RED)
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Не удалось подключиться")
                        .setMessage("VPN не удалось запустить. Проверь выбранный сервер и подключение к интернету.")
                        .setPositiveButton("Понятно", null)
                        .show()
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
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF070B13.toInt(), 0xFF111B2D.toInt(), 0xFF090F1B.toInt()))
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
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(brand, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(12) })
        header.addView(addButton, LinearLayout.LayoutParams(dp(52), dp(48)))

        val subtitle = TextView(this).apply {
            text = "PRIVATE CONNECTION  ·  ANDROID"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(3), 0, dp(20))
        }

        val connectCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF263B63.toInt(), 0xFF151F34.toInt())).apply { cornerRadius = dp(28).toFloat(); setStroke(dp(1), 0xFF40577F.toInt()) }
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
        connectButton = MaterialButton(this).apply {
            text = "ПОДКЛЮЧИТЬСЯ"
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            cornerRadius = dp(18)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            rippleColor = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
            insetTop = 0
            insetBottom = 0
            elevation = dp(2).toFloat()
            isEnabled = false
            setOnClickListener {
                // Give immediate visible feedback before asking Android to start/stop VpnService.
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                if (connected && selectedProfile == activeProfile) {
                    status.text = "Отключаем VPN…"
                    status.setTextColor(AMBER)
                    text = "ОТКЛЮЧАЕМ…"
                    stopTunnel()
                } else {
                    status.text = "Подготавливаем подключение…"
                    status.setTextColor(AMBER)
                    text = "ЗАПУСКАЕМ…"
                    connectSelectedProfile()
                }
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
            text = "Серверы"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val updateSubscriptions = TextView(this).apply {
            text = "↻ Обновить"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { refreshSubscriptions() }
        }
        val refresh = TextView(this).apply {
            text = "⚡ Быстрый"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setOnClickListener { selectFastestServer() }
        }
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, dp(32), 1f))
        listHeader.addView(updateSubscriptions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        listHeader.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(8))
        }
        serverSearch = EditText(this).apply {
            hint = "Поиск сервера…"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(12), 0, dp(8), 0)
            background = rounded(CARD, 15)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Debounce fast typing: rebuilding hundreds of server rows on every
                    // keystroke causes avoidable UI-thread work on mid-range phones.
                    searchRenderHandler.removeCallbacks(searchRenderTask)
                    searchRenderHandler.postDelayed(searchRenderTask, 140L)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        countryFilter = Spinner(this).apply {
            setPadding(dp(4), 0, dp(4), 0)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { renderProfiles() }
            }
        }
        filterRow.addView(serverSearch, LinearLayout.LayoutParams(0, dp(48), 1f))
        filterRow.addView(countryFilter, LinearLayout.LayoutParams(dp(112), dp(48)).apply { leftMargin = dp(8) })
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(profileList)
        }

        root.addView(header)
        root.addView(subtitle)
        root.addView(connectCard, matchWidth())
        monitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD, 20).apply { setStroke(dp(1), 0xFF26344C.toInt()) }
        }
        monitorCard.addView(TextView(this).apply {
            text = "СОСТОЯНИЕ ПОДКЛЮЧЕНИЯ"
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
        root.addView(filterRow, matchWidth())
        root.addView(profileScroll, LinearLayout.LayoutParams(matchWidth().width, 0, 1f))
        setContentView(root)
        requestNotificationPermissionIfNeeded()
        requestPhoneStatePermissionIfNeeded()
        renderProfiles()
        updateNetworkLabel()
        // Check the open-app option immediately and keep a lightweight scheduler alive
        // while this screen is active; the worker checks settings every minute.
        SubscriptionRefreshScheduler.sync(this)
        maybeAutoUpdateSubscriptions(true)
        autoUpdateHandler.postDelayed(autoUpdateTick, 60_000L)
        // GeoIP is queried after restoreVpnState() determines whether a VPN is already active.
        // Let the first frame become interactive before starting the isolated
        // sing-box measurement engine. This avoids competing with cold app startup.
        autoUpdateHandler.postDelayed({
            if (!isFinishing && !isDestroyed && !latencyTestInFlight) checkAllLatencies()
        }, 1_200L)
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Вставь ссылку или HTTPS-подписку"
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        MaterialAlertDialogBuilder(this)
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
                                val parsed = SubscriptionParser.importDetailed(value)
                                val links = parsed.links
                                if (value.startsWith("https://", ignoreCase = true)) {
                                    profileStore.replaceSubscription(value, links, parsed.metadata)
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
                                    // Network/parser errors can echo the submitted URL, which may
                                    // contain access credentials. Only storage errors have safe,
                                    // user-facing recovery text.
                                    val message = if (error is ProfileStorageException) {
                                        error.message ?: "Не удалось открыть защищённое хранилище."
                                    } else {
                                        "Не удалось прочитать ссылку или получить серверы. Проверь адрес и подключение к интернету."
                                    }
                                    MaterialAlertDialogBuilder(this)
                                        .setTitle("Не удалось импортировать")
                                        .setMessage(message)
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
    private fun refreshSubscriptions(showResult: Boolean = true) {
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
        // Track attempts separately from successful updates so a broken provider
        // is not retried every minute, while the UI still shows the last success.
        settingsPrefs.edit().putLong("last_subscription_attempt", System.currentTimeMillis()).apply()
        status.text = "Обновляем подписки…"
        status.setTextColor(AMBER)
        importExecutor.execute {
            var updated = 0
            var totalProfiles = 0
            val errors = mutableListOf<String>()
            val failedSources = linkedSetOf<String>()
            sources.forEachIndexed { index, source ->
                val result = runCatching {
                    SubscriptionRefreshCoordinator.refresh(profileStore, source)
                }
                result.onSuccess {
                    updated++
                    totalProfiles += it
                }.onFailure {
                    // Parser/network exceptions may contain the full subscription URL
                    // (including credentials). Keep provider secrets out of dialogs.
                    errors += "Подписка ${index + 1}: не удалось обновить"
                    failedSources += SubscriptionSourceFingerprint.of(source)
                }
            }
            // Keep manual/foreground refresh failures in the same fingerprint-only
            // retry queue used by WorkManager, so a later background run retries
            // only failed providers rather than downloading every successful one.
            SubscriptionRefreshRetryState.persist(settingsPrefs, failedSources, sources)
            runOnUiThread {
                refreshingSubscriptions = false
                if (updated > 0) {
                    settingsPrefs.edit().putLong("last_subscription_update", System.currentTimeMillis()).apply()
                }
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
                if (showResult) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Обновление подписок")
                        .setMessage(summary)
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }
    }

    private var storageErrorShown = false

    private fun showStorageError(error: ProfileStorageException) {
        status.text = "Хранилище недоступно"
        status.setTextColor(RED)
        if (!storageErrorShown && !isFinishing && !isDestroyed) {
            storageErrorShown = true
            MaterialAlertDialogBuilder(this)
                .setTitle("Проблема защищённого хранилища")
                .setMessage(error.message ?: "Не удалось прочитать сохранённые серверы.")
                .setPositiveButton("Понятно", null)
                .show()
        }
    }

    private fun safeProfiles(): List<String>? = try {
        profileStore.all()
    } catch (error: ProfileStorageException) {
        showStorageError(error)
        null
    }

    private fun safeSubscriptionSources(): List<String>? = try {
        profileStore.subscriptionSources()
    } catch (error: ProfileStorageException) {
        showStorageError(error)
        null
    }

    private fun safeSubscriptionSummaries(): List<SubscriptionSummary>? = try {
        profileStore.subscriptionSummaries()
    } catch (error: ProfileStorageException) {
        showStorageError(error)
        null
    }

    private fun subscriptionUsedBytes(item: SubscriptionSummary): Long =
        SubscriptionUsageCalculator.usedBytes(item.uploadBytes, item.downloadBytes)

    private fun subscriptionUsageText(item: SubscriptionSummary): String {
        val usedBytes = subscriptionUsedBytes(item)
        val used = if (item.uploadBytes != null || item.downloadBytes != null) {
            formatTraffic(usedBytes)
        } else null
        val total = item.totalBytes?.takeIf { it >= 0L }?.let(::formatTraffic)
        val traffic = when {
            used != null && total != null -> {
                val remaining = SubscriptionUsageCalculator.remainingBytes(usedBytes, item.totalBytes!!) ?: 0L
                "Использовано: $used из $total · осталось ${formatTraffic(remaining)}"
            }
            used != null -> "Использовано: $used · лимит не указан"
            else -> "Трафик: провайдер не предоставил данные"
        }
        val expiry = item.expiresAtEpochSeconds?.let { seconds ->
            if (seconds <= 0L) "Срок действия: не ограничен"
            else java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault()).format(java.util.Date(seconds * 1000L))
        } ?: "срок действия не указан"
        return "$traffic · $expiry"
    }

    private fun showSubscriptionDetails(item: SubscriptionSummary) {
        val host = Uri.parse(item.sourceUrl).host ?: "HTTPS-подписка"
        val usedBytes = subscriptionUsedBytes(item)
        val trafficDetails = when {
            item.totalBytes != null && item.totalBytes >= 0L -> {
                val remaining = SubscriptionUsageCalculator.remainingBytes(usedBytes, item.totalBytes) ?: 0L
                "Использовано: ${formatTraffic(usedBytes)}\nОсталось: ${formatTraffic(remaining)}\nВсего: ${formatTraffic(item.totalBytes)}"
            }
            item.uploadBytes != null || item.downloadBytes != null -> "Использовано: ${formatTraffic(usedBytes)}\nОбщий лимит не указан"
            else -> "Провайдер не передал данные о трафике"
        }
        val expiry = item.expiresAtEpochSeconds?.let { seconds ->
            if (seconds <= 0L) "Без ограничения срока"
            else java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(seconds * 1000L))
        } ?: "Не указан провайдером"
        val details = "Источник: $host\nСерверов: ${item.serverCount}\n\n$trafficDetails\n\nСрок действия: $expiry\n\nНажми «Обновить», чтобы получить актуальные данные от провайдера."
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setMessage(details)
            .setPositiveButton("Обновить") { _, _ -> refreshSingleSubscription(item.sourceUrl) }
            .setNeutralButton("Переименовать") { _, _ -> renameSubscription(item) }
            .setNegativeButton("Удалить") { _, _ -> confirmRemoveSubscription(item) }
            .show()
    }

    private fun renameSubscription(item: SubscriptionSummary) {
        val input = EditText(this).apply {
            setText(item.title)
            setSelection(text.length)
            hint = "Название подписки"
            setSingleLine(true)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Переименовать подписку")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                runCatching { profileStore.renameSubscription(item.sourceUrl, input.text.toString()) }
                    .onSuccess { renderProfiles(); Toast.makeText(this, "Название сохранено", Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        MaterialAlertDialogBuilder(this)
                            .setMessage("Не удалось сохранить название подписки.")
                            .setPositiveButton("ОК", null)
                            .show()
                    }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmRemoveSubscription(item: SubscriptionSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить подписку?")
            .setMessage("Будут удалены сама подписка и все ${item.serverCount} серверов, полученных из неё. Это действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                runCatching { profileStore.removeSubscription(item.sourceUrl) }
                    .onSuccess { removed ->
                        if (activeProfile?.let { it in removed } == true || selectedProfile?.let { it in removed } == true) stopTunnel()
                        if (activeProfile?.let { it in removed } == true) activeProfile = null
                        if (selectedProfile?.let { it in removed } == true) selectedProfile = null
                        renderProfiles()
                        checkAllLatencies()
                        Toast.makeText(this, "Подписка удалена", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        MaterialAlertDialogBuilder(this)
                            .setMessage("Не удалось удалить подписку. Проверь доступность защищённого хранилища.")
                            .setPositiveButton("ОК", null)
                            .show()
                    }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refreshSingleSubscription(source: String) {
        if (refreshingSubscriptions) {
            Toast.makeText(this, "Обновление уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        refreshingSubscriptions = true
        status.text = "Обновляем подписку…"
        status.setTextColor(AMBER)
        importExecutor.execute {
            val result = runCatching {
                val parsed = SubscriptionParser.importDetailed(source)
                profileStore.replaceSubscription(source, parsed.links, parsed.metadata)
                parsed.links.size
            }
            runOnUiThread {
                refreshingSubscriptions = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderProfiles()
                checkAllLatencies()
                result.onSuccess { count ->
                    status.text = "Подписка обновлена"
                    status.setTextColor(GREEN)
                    Toast.makeText(this, "Получено серверов: $count", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    status.text = "Ошибка обновления"
                    status.setTextColor(RED)
                    // Provider/network exceptions can contain the subscription URL.
                    // Expose only the safe storage recovery message when applicable.
                    val message = if (error is ProfileStorageException) {
                        error.message ?: "Не удалось открыть защищённое хранилище."
                    } else {
                        "Не удалось получить серверы подписки. Проверь подключение к интернету."
                    }
                    MaterialAlertDialogBuilder(this).setTitle("Не удалось обновить подписку")
                        .setMessage(message)
                        .setPositiveButton("ОК", null).show()
                }
            }
        }
    }

    private fun formatTraffic(bytes: Long): String {
        if (bytes < 0L) return "—"
        val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
        return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
    }
    private fun renderProfiles() {
        val profiles = safeProfiles() ?: return
        val summaries = safeSubscriptionSummaries() ?: return
        val previousScrollY = if (::profileScroll.isInitialized) profileScroll.scrollY else 0
        profileList.removeAllViews()
        // Latency updates and favorite changes rebuild the current rows. Keep the user's
        // place in a long server list instead of jumping back to the top each time.
        if (::profileScroll.isInitialized) {
            profileScroll.post { profileScroll.scrollTo(0, previousScrollY) }
        }
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
                    setOnClickListener { showSubscriptionDetails(subscription) }
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
                    text = subscriptionUsageText(subscription)
                    textSize = 11f
                    setTextColor(MUTED)
                })
                val totalBytes = subscription.totalBytes ?: 0L
                if (totalBytes > 0L && (subscription.uploadBytes != null || subscription.downloadBytes != null)) {
                    val usedBytes = subscriptionUsedBytes(subscription)
                    val percent = SubscriptionUsageCalculator.percentage(usedBytes, totalBytes) ?: 0
                    val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        this.progress = percent
                        isIndeterminate = false
                        progressTintList = android.content.res.ColorStateList.valueOf(if (percent >= 90) AMBER else GREEN)
                        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF303B54.toInt())
                    }
                    card.addView(progress, LinearLayout.LayoutParams(matchWidth().width, dp(6)).apply {
                        topMargin = dp(9)
                    })
                    card.addView(TextView(this).apply {
                        text = "$percent% использовано"
                        textSize = 10f
                        setTextColor(MUTED)
                        setPadding(0, dp(4), 0, 0)
                    })
                }
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
        val allCountries = profiles.map { CountryFlagResolver.flagFor(profileName(it)) }
            .filter { it.isNotBlank() && it != "🌐" }.distinct().sorted()
        val countryOptions = allCountries.map { "${it} ${CountryFlagResolver.countryNameForFlag(it)}" }
        val filterOptions = listOf("🌍 Все страны") + countryOptions
        val currentOptions = (0 until countryFilter.count).mapNotNull { countryFilter.getItemAtPosition(it)?.toString() }
        if (currentOptions != filterOptions) {
            val previousSelection = countryFilter.selectedItem?.toString()
            countryFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, filterOptions)
            val restoreIndex = filterOptions.indexOf(previousSelection).takeIf { it >= 0 } ?: 0
            countryFilter.setSelection(restoreIndex, false)
        }
        val selectedCountry = countryFilter.selectedItem?.toString()
            ?.takeIf { !it.startsWith("🌍") }?.substringBefore(" ")
        val query = serverSearch.text?.toString()?.trim()?.lowercase(java.util.Locale.getDefault()).orEmpty()
        val filteredProfiles = profiles.filter { link ->
            val name = profileName(link)
            val matchesText = query.isBlank() || name.lowercase(java.util.Locale.getDefault()).contains(query) ||
                link.lowercase(java.util.Locale.ROOT).contains(query) || protocolName(link).lowercase().contains(query)
            val matchesCountry = selectedCountry == null || CountryFlagResolver.flagFor(name) == selectedCountry
            matchesText && matchesCountry
        }
        if (selectedProfile == null || selectedProfile !in profiles) selectedProfile = profiles.firstOrNull()
        val selected = selectedProfile
        connectButton.isEnabled = connected || (selected != null && !connecting)
        connectButton.text = when {
            connected && selected != activeProfile -> "ПЕРЕКЛЮЧИТЬ СЕРВЕР"
            connected -> "ОТКЛЮЧИТЬ VPN"
            connecting -> "ПОДКЛЮЧАЕМСЯ…"
            else -> "ПОДКЛЮЧИТЬСЯ"
        }
        if (selected != null && connected && selected == activeProfile) {
            selectedInfo.text = when (tunnelInternetOk) {
                true -> "🛡 Защита проверена · интернет через VPN работает\n${profileSummary(selected)}"
                false -> "⚠ СОЕДИНЕНИЕ НЕ ЗАЩИЩЕНО · интернет через сервер недоступен\n${profileSummary(selected)}"
                null -> "◌ Проверяем защиту соединения…\n${profileSummary(selected)}"
            }
            selectedInfo.setTextColor(when (tunnelInternetOk) {
                true -> GREEN
                false -> RED
                null -> AMBER
            })
        } else {
            selectedInfo.text = selected?.let(::profileSummary) ?: "Сначала добавь сервер"
            selectedInfo.setTextColor(MUTED)
        }

        if (profiles.isEmpty() || filteredProfiles.isEmpty()) {
            profileList.addView(TextView(this).apply {
                text = if (profiles.isEmpty()) "Пока нет серверов. Нажми + и импортируй ссылку подписки." else "По заданным фильтрам серверы не найдены."
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
            return
        }

        val favorites = settingsPrefs.getStringSet("favorite_servers", emptySet()) ?: emptySet()
        val displayProfiles = ServerLatencySort.sort(
            profiles = filteredProfiles,
            mode = sortMode,
            latencyMs = latencyMs,
            favorites = favorites,
            locale = java.util.Locale.getDefault()
        )

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
                    if (connecting) {
                        Toast.makeText(this@MainActivity, "Дождись завершения текущего переключения", Toast.LENGTH_SHORT).show()
                    } else if (connected && link != activeProfile) {
                        // One tap switches the active tunnel: no extra confirmation dialog.
                        selectedProfile = link
                        val nextConfig = runCatching { SingBoxConfigBuilder.build(link) }.getOrElse {
                            Toast.makeText(this@MainActivity, "Не удалось подготовить конфигурацию сервера", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        startTunnel(nextConfig)
                    } else {
                        selectedProfile = link
                        renderProfiles()
                    }
                }
                setOnLongClickListener {
                    if (connected || connecting) {
                        Toast.makeText(this@MainActivity, "Сначала отключи VPN", Toast.LENGTH_SHORT).show()
                    } else {
                        MaterialAlertDialogBuilder(this@MainActivity)
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
                val latency = pingInfo[link] ?: "не проверен"
                text = "${protocolName(link)}  ·  $latency"
                textSize = 12f
                val tone = latencyMs[link]?.let { latencyColor(it) }
                    ?: when (pingInfo[link]) {
                        "н/д" -> RED
                        "Проверяем…" -> AMBER
                        else -> MUTED
                    }
                setTextColor(tone)
                background = rounded(if (latencyMs[link] != null) 0x1A55D69A else 0x1A9AA6BF, 9)
                contentDescription = "Задержка измеряется HTTP URL-test через соответствующий сервер sing-box. Недоступные серверы отображаются как н/д."
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            details.addView(title)
            details.addView(meta)
            val favoriteButton = TextView(this).apply {
                text = if (link in favorites) "★" else "☆"
                textSize = 22f
                setTextColor(if (link in favorites) AMBER else MUTED)
                gravity = Gravity.CENTER
                contentDescription = if (link in favorites) "Убрать из избранного" else "Добавить в избранное"
                setOnClickListener { toggleFavoriteServer(link) }
            }
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
            row.addView(favoriteButton, LinearLayout.LayoutParams(dp(34), dp(42)))
            row.addView(selectMark, LinearLayout.LayoutParams(dp(28), dp(42)))
            profileList.addView(row, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            })
        }
    }

    private fun toggleFavoriteServer(link: String) {
        val favorites = (settingsPrefs.getStringSet("favorite_servers", emptySet()) ?: emptySet()).toMutableSet()
        val added = if (favorites.add(link)) true else {
            favorites.remove(link)
            false
        }
        settingsPrefs.edit().putStringSet("favorite_servers", favorites).apply()
        renderProfiles()
        Toast.makeText(this, if (added) "Добавлено в избранное" else "Убрано из избранного", Toast.LENGTH_SHORT).show()
    }

    /**
     * Bind endpoint probes to the physical network. When Astra is connected,
     * ordinary app sockets follow the VPN and can loop back into the tunnel;
     * an explicit Android Network socket factory avoids measuring that route.
     */
    private fun networkForLatencyProbe(): android.net.Network? {
        val manager = getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork
        val activeCapabilities = active?.let(manager::getNetworkCapabilities)
        if (active != null && activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
            return active
        }
        return manager.allNetworks.asSequence()
            .mapNotNull { network ->
                manager.getNetworkCapabilities(network)?.let { capabilities -> network to capabilities }
            }
            .filter { (_, capabilities) ->
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedWith(
                compareByDescending<Pair<android.net.Network, NetworkCapabilities>> {
                    it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }.thenByDescending {
                    it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }
            )
            .firstOrNull()?.first
    }

    private fun latencyQuality(ms: Long): String = when {
        ms < 50L -> "отлично"
        ms < 100L -> "хорошо"
        ms < 180L -> "средне"
        ms < 250L -> "высоко"
        else -> "очень высоко"
    }

    private fun latencyColor(ms: Long): Int = when {
        ms < 100L -> GREEN
        ms < 180L -> AMBER
        else -> RED
    }

    private fun checkAllLatencies() {
        val profiles = safeProfiles()
        if (profiles == null) {
            pendingFastestSelection = false
            selectingFastest = false
            return
        }
        if (latencyTestInFlight) {
            Toast.makeText(this, "Проверка серверов уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        latencyCheckGeneration.incrementAndGet()
        val currentProfiles = profiles.toHashSet()
        latencyMs.keys.toList().filterNot { it in currentProfiles }.forEach { latencyMs.remove(it) }
        pingInfo.keys.toList().filterNot { it in currentProfiles }.forEach { pingInfo.remove(it) }
        if (profiles.isEmpty()) {
            pendingFastestSelection = false
            selectingFastest = false
            renderProfiles()
            return
        }

        profiles.forEach { profile ->
            latencyMs.remove(profile)
            pingInfo[profile] = "Проверяем…"
        }
        renderProfiles()
        Toast.makeText(this, "Измеряем реальную задержку через серверы…", Toast.LENGTH_SHORT).show()

        // Build/validate the sing-box config off the main thread. Libbox.checkConfig
        // can be expensive with large subscriptions and must not freeze touch feedback.
        latencyTestInFlight = true
        val generation = latencyCheckGeneration.get()
        pingExecutor.execute {
            val prepared = runCatching { SingBoxUrlTestConfig.build(profiles) }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != latencyCheckGeneration.get()) return@runOnUiThread
                prepared.onSuccess { testConfig ->
                    urlTestProfileMap = testConfig.profileByOutboundTag
                    // Keep the tag-to-profile map across Activity recreation. The
                    // foreground test service may finish while Android recreates UI.
                    getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LATENCY_PROFILE_MAP, JSONObject(urlTestProfileMap).toString())
                        .remove(KEY_LATENCY_PENDING_RESULTS)
                        .apply()
                    runCatching {
                        AstraLatencyTestService.start(this, testConfig.json, testConfig.profileByOutboundTag.keys)
                    }.onFailure {
                        latencyTestInFlight = false
                        pendingFastestSelection = false
                        selectingFastest = false
                        profiles.forEach { profile ->
                            latencyMs.remove(profile)
                            pingInfo[profile] = "н/д"
                        }
                        Toast.makeText(this, "Не удалось запустить проверку серверов", Toast.LENGTH_SHORT).show()
                        renderProfiles()
                    }
                }.onFailure {
                    latencyTestInFlight = false
                    pendingFastestSelection = false
                    selectingFastest = false
                    profiles.forEach { profile ->
                        latencyMs.remove(profile)
                        pingInfo[profile] = "н/д"
                    }
                    Toast.makeText(this, "Не удалось подготовить проверку серверов", Toast.LENGTH_SHORT).show()
                    renderProfiles()
                }
            }
        }
    }

    /**
     * Chooses the fastest VLESS endpoint using a short TCP reachability test.
     * UDP profiles are deliberately excluded: a TCP result says nothing about
     * Hysteria2/TUIC availability or actual VPN tunnel latency.
     */
    private fun selectFastestServer() {
        if (selectingFastest || latencyTestInFlight) {
            Toast.makeText(this, "Проверка серверов уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        if (connected || connecting) {
            Toast.makeText(this, "Отключи VPN перед сменой сервера", Toast.LENGTH_SHORT).show()
            return
        }
        selectingFastest = true
        pendingFastestSelection = true
        Toast.makeText(this, "Ищем самый быстрый сервер…", Toast.LENGTH_SHORT).show()
        checkAllLatencies()
    }

    private fun connectSelectedProfile() {
        if (connecting) return
        val link = selectedProfile ?: return
        val config = runCatching { SingBoxConfigBuilder.build(link) }.getOrElse {
            // Config/parser diagnostics may include the original proxy URI and credentials.
            // Keep the user-facing error actionable without exposing the imported profile.
            MaterialAlertDialogBuilder(this).setTitle("Ошибка конфигурации")
                .setMessage("Не удалось подготовить конфигурацию сервера. Проверь ссылку и параметры подключения.")
                .setPositiveButton("ОК", null).show()
            return
        }
        pendingConfig = config

        // Do not show an extra in-app confirmation when switching profiles.
        // If Astra already owns the VPN, the service reloads sing-box in place
        // and reuses the existing TUN. Android's own VPN permission prompt is
        // still required when permission has not yet been granted.
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
            val filter = IntentFilter().apply {
                addAction(AstraVpnService.ACTION_STATE)
                addAction(AstraLatencyTestService.ACTION_RESULTS)
                addAction(AstraLatencyTestService.ACTION_ERROR)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(vpnStateReceiver, filter)
            receiverRegistered = true
        }
        restorePendingLatencyResult()
        sortMode = settingsPrefs.getString("sort_mode", "original") ?: "original"
        restoreVpnState()
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        autoUpdateHandler.postDelayed(autoUpdateTick, 60_000L)
        maybeAutoUpdateSubscriptions(true)
    }

    private fun restorePendingLatencyResult() {
        val prefs = getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE)
        val mapJson = prefs.getString(KEY_LATENCY_PROFILE_MAP, null)
        if (mapJson != null) {
            runCatching {
                val json = JSONObject(mapJson)
                urlTestProfileMap = buildMap {
                    json.keys().forEach { tag -> put(tag, json.getString(tag)) }
                }
            }
        }
        val pending = prefs.getString(KEY_LATENCY_PENDING_RESULTS, null) ?: return
        prefs.edit().remove(KEY_LATENCY_PENDING_RESULTS).apply()
        applyLatencyResults(pending)
    }

    private fun applyLatencyResults(payload: String) {
        getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
            .remove(KEY_LATENCY_PENDING_RESULTS)
            .apply()
        latencyTestInFlight = false
        val values = runCatching { JSONObject(payload) }.getOrNull() ?: return
        urlTestProfileMap.forEach { (tag, profile) ->
            val ms = if (values.has(tag) && !values.isNull(tag)) values.optLong(tag, -1L) else -1L
            if (ms > 0L) {
                latencyMs[profile] = ms
                pingInfo[profile] = "URL-test $ms мс · ${latencyQuality(ms)}"
            } else {
                latencyMs.remove(profile)
                pingInfo[profile] = "н/д"
            }
        }
        if (pendingFastestSelection) {
            pendingFastestSelection = false
            selectingFastest = false
            val fastest = urlTestProfileMap.values.mapNotNull { link ->
                latencyMs[link]?.let { link to it }
            }.minByOrNull { it.second }?.first
            if (fastest != null) {
                selectedProfile = fastest
                status.text = "Выбран быстрый сервер"
                status.setTextColor(GREEN)
                selectedInfo.text = profileSummary(fastest)
                Toast.makeText(this, "Выбран сервер: " + profileName(fastest), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Работающих серверов не найдено", Toast.LENGTH_LONG).show()
            }
        }
        if (!isFinishing && !isDestroyed) renderProfiles()
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
        searchRenderHandler.removeCallbacks(searchRenderTask)
        monitorHandler.removeCallbacks(monitorTick)
        // Keep the receiver registered while this Activity instance is alive.
        // A latency test can finish while the app is backgrounded; unregistering
        // here would drop its one-shot result and leave rows stuck on "Проверяем…".
        super.onStop()
    }

    private fun startTunnel(config: String) {
        activeProfile = selectedProfile
        val intent = Intent(this, AstraVpnService::class.java)
            .setAction(AstraVpnService.ACTION_START)
            .putExtra(AstraVpnService.EXTRA_CONFIG, config)
            .putExtra(AstraVpnService.EXTRA_PROFILE_NAME, activeProfile?.let { profileName(it) } ?: "Сервер не выбран")
            .putExtra(AstraVpnService.EXTRA_PROTOCOL, activeProfile?.let { protocolName(it) } ?: "—")
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
            Toast.makeText(this, "Не удалось запустить VPN. Проверь выбранный сервер и разрешение на VPN.", Toast.LENGTH_LONG).show()
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
        val activeNetwork = connectivity.activeNetwork
        val activeCapabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)

        // Outside a VPN, Android's active network is the authoritative default.
        // While VPN is active, activeNetwork describes the tunnel, so select the
        // best validated physical underlay from the remaining connected networks.
        if (activeCapabilities != null &&
            !activeCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            return activeCapabilities
        }

        return connectivity.allNetworks
            .asSequence()
            .mapNotNull { network -> connectivity.getNetworkCapabilities(network) }
            .filter { caps ->
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            .sortedByDescending { caps ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 1 else 0)
            }
            .firstOrNull()
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

    /**
     * Checks real HTTPS reachability through Android's active VPN route.
     * A created VpnService/TUN alone does not prove that the selected proxy can
     * carry traffic. The check runs off the UI thread and is rate-limited.
     */
    private fun scheduleTunnelHealthCheck() {
        if (!connected || isFinishing || isDestroyed) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val vpnNetwork = connectivity.activeNetwork
        val vpnCapabilities = vpnNetwork?.let(connectivity::getNetworkCapabilities)
        if (vpnNetwork == null || vpnCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
            tunnelInternetOk = false
            updateProtectionLabels()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckAt < 12_000L || !healthCheckInFlight.compareAndSet(false, true)) return
        lastHealthCheckAt = now
        healthCheckExecutor.execute {
            val ok = TunnelHealthChecker.check(vpnNetwork)
            tunnelInternetOk = ok
            healthCheckInFlight.set(false)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && connected) {
                    updateProtectionLabels()
                    renderProfiles()
                }
            }
        }
    }

    private fun updateProtectionLabels() {
        val label = when {
            connecting -> "◌ Проверяем VPN-подключение…" to AMBER
            !connected -> "○ Защита выключена" to MUTED
            tunnelInternetOk == true -> "🛡 Защита активна · интернет через VPN работает" to GREEN
            tunnelInternetOk == false -> "⚠ VPN включён, но интернет через него не работает. Защита не подтверждена" to RED
            else -> "◌ VPN-туннель создан · проверяем доступ в интернет…" to AMBER
        }
        monitorProtection.text = label.first
        monitorProtection.setTextColor(label.second)
        if (!connected) monitorVpnCountry.text = "Страна выхода VPN: —"
        else monitorVpnCountry.text = "Страна выхода VPN: $vpnCountry"
    }

    private fun showNetworkMonitorDialog() {
        val content = ScrollView(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            (monitorCard.parent as? ViewGroup)?.removeView(monitorCard)
            addView(monitorCard)
        }
        MaterialAlertDialogBuilder(this)
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

    private fun showConnectionDiagnostics() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val activeCapabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        val vpnTransport = activeCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        // While VPN is connected, activeNetwork can describe the VPN interface itself.
        // Look for the physical underlay so diagnostics reports Wi-Fi/mobile correctly.
        val underlyingCapabilities = if (vpnTransport) {
            connectivity.allNetworks
                .mapNotNull { candidate -> connectivity.getNetworkCapabilities(candidate) }
                .filter { candidate ->
                    !candidate.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                .sortedByDescending {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                .firstOrNull()
        } else null
        val capabilities = underlyingCapabilities ?: activeCapabilities
        val physicalNetworkType = when {
            capabilities == null -> "Нет активной сети"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Другая сеть"
        }
        val networkType = if (vpnTransport && underlyingCapabilities != null) {
            "$physicalNetworkType (через VPN)"
        } else if (vpnTransport) {
            "VPN (физическая сеть не определена)"
        } else physicalNetworkType
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val selected = selectedProfile
        val active = activeProfile
        val latency = selected?.let { pingInfo[it] } ?: "Нет данных"
        @Suppress("DEPRECATION")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "неизвестно"
        val report = buildString {
            appendLine("ASTRA VPN — диагностика")
            appendLine("Версия: $version")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Состояние VPN: ${if (connected) "Подключено" else if (connecting) "Подключение" else "Отключено"}")
            appendLine("Активный VPN-профиль: ${active?.let { profileName(it) } ?: "—"}")
            appendLine("Выбранный сервер: ${selected?.let { profileName(it) } ?: "—"}")
            appendLine("Протокол: ${selected?.let { protocolName(it) } ?: "—"}")
            appendLine("Проверка сервера: $latency")
            appendLine("Сеть устройства: $networkType")
            appendLine("Интернет доступен: ${if (internet) "да" else "нет"}")
            appendLine("Сеть подтверждена Android: ${if (validated) "да" else "нет"}")
            appendLine("VPN-транспорт активной сети: ${if (vpnTransport) "да" else "нет"}")
            appendLine("Количество серверов: ${safeProfiles()?.size?.toString() ?: "недоступно"}")
            appendLine("Время отчёта: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Диагностика соединения")
            .setMessage(report)
            .setPositiveButton("Поделиться") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Astra VPN — диагностика")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(share, "Отправить отчёт"))
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showConnectionHistoryDialog() {
        val entries = connectionHistory.getEntries()
        val body = if (entries.isEmpty()) {
            "История пока пуста. Здесь будут отображаться подключения, отключения и ошибки."
        } else {
            entries.joinToString("\n\n") { item ->
                val date = java.text.SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(item.timestamp))
                val stateLabel = when (item.state) {
                    "connecting" -> "⏳ Подключение"
                    "connected" -> "🟢 Подключено"
                    "disconnected" -> "⚪ Отключено"
                    "error" -> "🔴 Ошибка подключения"
                    else -> item.state
                }
                "$stateLabel\n$date\n${item.serverName} · ${item.protocol}"
            }
        }
        val historyText = TextView(this).apply {
            text = body
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply {
            addView(historyText)
            isFillViewport = true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("История подключений · ${entries.size}")
            .setView(scroll)
            .setPositiveButton("Поделиться") { _, _ ->
                val shareText = entries.joinToString("\n\n") { item ->
                    val date = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(item.timestamp))
                    val label = when (item.state) {
                        "connecting" -> "Подключение"
                        "connected" -> "Подключено"
                        "disconnected" -> "Отключено"
                        "error" -> "Ошибка подключения"
                        else -> item.state
                    }
                    "$date — $label — ${item.serverName} (${item.protocol})"
                }.ifBlank { "История подключений Astra VPN пуста." }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "ASTRA VPN — история подключений\n\n$shareText")
                }
                startActivity(Intent.createChooser(share, "Поделиться историей"))
            }
            .setNeutralButton("Очистить") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Очистить историю?")
                    .setMessage("Все сохранённые записи подключений будут удалены.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Очистить") { _, _ ->
                        connectionHistory.clear()
                        Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
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

        val diagnosticsEntry = TextView(this).apply {
            text = "🧰  Диагностика соединения  ›"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(0, dp(12), 0, dp(14))
            setOnClickListener { showConnectionDiagnostics() }
        }
        panel.addView(diagnosticsEntry)

        val historyEntry = TextView(this).apply {
            text = "🕘  История подключений  ›"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(0, dp(12), 0, dp(14))
            setOnClickListener { showConnectionHistoryDialog() }
        }
        panel.addView(historyEntry)

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
        val subscriptionNotifications = CheckBox(this).apply {
            text = "Уведомлять об обновлении подписок"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("subscription_notifications", true)
        }
        panel.addView(subscriptionNotifications)
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
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Интервал автообновления")
                    .setSingleChoiceItems(values, selected) { dialog, which ->
                        settingsPrefs.edit().putInt("update_interval_hours", hours[which]).apply()
                        SubscriptionRefreshScheduler.sync(this@MainActivity)
                        refreshLabel()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
        panel.addView(interval)
        panel.addView(TextView(this).apply {
            val lastUpdate = settingsPrefs.getLong("last_subscription_update", 0L)
            text = if (lastUpdate > 0L) {
                "Последнее успешное обновление: " + java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastUpdate))
            } else "Подписки ещё не обновлялись"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, dp(2))
        })
        panel.addView(TextView(this).apply {
            val lastAttempt = settingsPrefs.getLong("last_subscription_attempt", 0L)
            val lastUpdate = settingsPrefs.getLong("last_subscription_update", 0L)
            text = when {
                lastAttempt <= 0L -> "Попыток обновления пока не было"
                lastAttempt > lastUpdate -> "Последняя попытка: " + java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastAttempt)) + " · результат мог быть с ошибкой"
                else -> "Последняя попытка совпадает с успешным обновлением"
            }
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(8))
        })
        MaterialAlertDialogBuilder(this)
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
                    .putBoolean("subscription_notifications", subscriptionNotifications.isChecked)
                    .apply()
                if (subscriptionNotifications.isChecked) requestNotificationPermissionIfNeeded()
                SubscriptionRefreshScheduler.sync(this@MainActivity)
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
            background = rounded(CARD, 15)
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
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Маршрутизация приложений")
            .setView(content)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val packages = checks.filterValues { it.isChecked }.keys.toSet()
                val chosenMode = modeGroup.findViewById<RadioButton>(modeGroup.checkedRadioButtonId)
                    ?.tag as? String ?: "exclude"

                // Android interprets an empty allowed-app list as the default
                // full-device VPN. Do not silently save a setting that means
                // the opposite of what the user selected.
                if (chosenMode == "include" && packages.isEmpty()) {
                    Toast.makeText(
                        this,
                        "Выбери хотя бы одно приложение для режима «Только выбранные».",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                prefs.edit()
                    .putStringSet("vpn_app_packages", packages)
                    .putString("vpn_app_mode", chosenMode)
                    .apply()
                Toast.makeText(
                    this,
                    "Правила сохранены. Переподключи VPN для применения.",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun maybeAutoUpdateSubscriptions(onOpen: Boolean) {
        if (refreshingSubscriptions) return
        val enabled = settingsPrefs.getBoolean("auto_update", false)
        val updateOnOpen = settingsPrefs.getBoolean("update_on_open", false)
        val now = System.currentTimeMillis()
        val last = settingsPrefs.getLong("last_subscription_update", 0L)
        val lastAttempt = settingsPrefs.getLong("last_subscription_attempt", last)
        val intervalMs = SubscriptionRefreshInterval.intervalMillis(settingsPrefs.getInt("update_interval_hours", 2))
        val shouldUpdate = (onOpen && updateOnOpen && now - lastAttempt > 5 * 60_000L) ||
            (enabled && now - lastAttempt >= intervalMs)
        if (!shouldUpdate) return
        val sources = safeSubscriptionSources() ?: return
        if (sources.isEmpty()) return
        refreshSubscriptions(showResult = false)
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
    private fun serverRowBackground(selected: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (selected) intArrayOf(0xFF202F50.toInt(), 0xFF17243C.toInt())
        else intArrayOf(0xFF171F30.toInt(), 0xFF121A29.toInt())
    ).apply {
        cornerRadius = dp(20).toFloat()
        setStroke(dp(if (selected) 1 else 1), if (selected) 0xFF718AFF.toInt() else 0xFF26344D.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        searchRenderHandler.removeCallbacks(searchRenderTask)
        monitorHandler.removeCallbacks(monitorTick)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(vpnStateReceiver) }
            receiverRegistered = false
        }
        monitorExecutor.shutdownNow()
        importExecutor.shutdownNow()
        pingExecutor.shutdownNow()
        autoSelectExecutor.shutdownNow()
        healthCheckExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 7001
        private const val REQUEST_NOTIFICATION_PERMISSION = 7002
        private const val REQUEST_PHONE_STATE_PERMISSION = 7003
        private const val LATENCY_PREFS = "astra_latency_results"
        private const val KEY_LATENCY_PROFILE_MAP = "profile_map"
        private const val KEY_LATENCY_PENDING_RESULTS = "pending_results"
        private const val BG = 0xFF080B14.toInt()
        private const val CARD = 0xFF111827.toInt()
        private const val SELECTED_CARD = 0xFF182746.toInt()
        private const val ACCENT = 0xFF647CFF.toInt()
        private const val LIGHT_ACCENT = 0xFFA9B8FF.toInt()
        private const val GREEN = 0xFF55D69A.toInt()
        private const val AMBER = 0xFFFFC66D.toInt()
        private const val RED = 0xFFFF7777.toInt()
        private const val MUTED = 0xFF9AA6BF.toInt()
    }
}
