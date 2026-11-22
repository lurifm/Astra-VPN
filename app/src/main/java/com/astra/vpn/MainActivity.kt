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
import android.net.NetworkRequest
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
import android.view.WindowInsets
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

/** Per-source refresh state. Only SHA-256 fingerprints are used as preference keys. */
internal object SubscriptionRefreshHistory {
    data class Entry(val state: String, val timestamp: Long)
    private fun prefix(source: String) = "subscription_history_${SubscriptionSourceFingerprint.of(source)}"
    fun recordAttempt(settings: android.content.SharedPreferences, source: String, at: Long = System.currentTimeMillis()) {
        settings.edit().putString("${prefix(source)}_state", "updating").putLong("${prefix(source)}_time", at).apply()
    }
    fun recordSuccess(settings: android.content.SharedPreferences, source: String, at: Long = System.currentTimeMillis()) {
        settings.edit().putString("${prefix(source)}_state", "success").putLong("${prefix(source)}_time", at).apply()
    }
    fun recordFailure(settings: android.content.SharedPreferences, source: String, at: Long = System.currentTimeMillis()) {
        settings.edit().putString("${prefix(source)}_state", "failure").putLong("${prefix(source)}_time", at).apply()
    }
    fun get(
        settings: android.content.SharedPreferences,
        source: String,
        now: Long = System.currentTimeMillis()
    ): Entry? {
        val key = prefix(source)
        val state = settings.getString("${key}_state", null) ?: return null
        val timestamp = settings.getLong("${key}_time", 0L)
        if (timestamp <= 0L) return null

        // A process may be killed while a refresh is running. Do not leave
        // the UI showing an endless "updating" state after that happens.
        if (state == "updating" && now >= timestamp && now - timestamp >= STALE_AFTER_MS) {
            settings.edit().putString("${key}_state", "failure").apply()
            return Entry("failure", timestamp)
        }
        return Entry(state, timestamp)
    }

    private const val STALE_AFTER_MS = 15 * 60_000L
}

class MainActivity : Activity() {
    private var settingsDialog: android.app.Dialog? = null
    private lateinit var profileStore: ProfileStore
    private lateinit var profileList: LinearLayout
    private lateinit var profileScroll: ScrollView
    private lateinit var serverSearch: EditText
    private lateinit var countryFilter: Spinner
    private lateinit var serverCountLabel: TextView
    private lateinit var pingCheckButton: TextView
    private lateinit var throughputCheckButton: TextView
    private lateinit var status: TextView
    private lateinit var connectButton: MaterialButton
    private var connectTintAnimator: android.animation.ValueAnimator? = null
    private lateinit var connectActionLabel: TextView
    private lateinit var connectionCard: LinearLayout
    private lateinit var constellationView: ConstellationView
    private lateinit var starBurstView: StarBurstView
    private lateinit var starButtonFrame: android.widget.FrameLayout
    private lateinit var connectionIndicator: View
    private var connectionVisualState: String? = null
    private var starPulseAnimator: android.animation.ValueAnimator? = null
    private lateinit var selectedInfo: TextView
    private lateinit var quickSwitchContainer: LinearLayout
    private lateinit var orbitMetricsLabel: TextView
    private lateinit var orbitSpeedLabel: TextView
    private lateinit var orbitNameLabel: TextView
    private lateinit var orbitMetaLabel: TextView
    private val pingInfo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val latencyMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val throughputHistory by lazy { ThroughputHistoryStore(this) }
    private val throughputSamples = java.util.concurrent.ConcurrentHashMap<String, ThroughputSample>()
    @Volatile private var throughputProbeInFlight = false
    @Volatile private var activeThroughputSessionId: String? = null
    @Volatile private var activeThroughputProfile: String? = null
    private var throughputReceiverRegistered = false
    private val throughputBatchQueue = java.util.ArrayDeque<String>()
    private val throughputBatchHandler = Handler(Looper.getMainLooper())
    private var throughputBatchTask: Runnable? = null
    @Volatile private var throughputBatchActive = false
    private var throughputBatchGeneration = 0L
    private var throughputBatchTotal = 0
    private var throughputBatchCompleted = 0
    private var fastestSelectionCandidates: List<String> = emptyList()
    private val throughputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AstraThroughputProbeService.ACTION_RESULT &&
                intent?.action != AstraThroughputProbeService.ACTION_ERROR) return
            val session = intent.getStringExtra(AstraThroughputProbeService.EXTRA_SESSION)
            if (session.isNullOrBlank() || session != activeThroughputSessionId) return
            val profile = activeThroughputProfile
            activeThroughputSessionId = null
            activeThroughputProfile = null
            var sampleSaved = false
            if (intent.action == AstraThroughputProbeService.ACTION_RESULT && profile != null) {
                val parsed = runCatching {
                    val json = JSONObject(intent.getStringExtra(AstraThroughputProbeService.EXTRA_RESULT) ?: error("empty"))
                    ThroughputSample(
                        json.getDouble("downloadMbps"),
                        json.getDouble("uploadMbps"),
                        json.getLong("measuredAt")
                    )
                }.getOrNull()
                if (parsed != null && runCatching { throughputHistory.put(profile, parsed) }.isSuccess) {
                    throughputSamples[profile] = parsed
                    sampleSaved = true
                }
            }
            if (throughputBatchActive) {
                throughputBatchCompleted++
                throughputProbeInFlight = true
                scheduleNextThroughputBatchProbe()
            } else {
                throughputProbeInFlight = false
                if (sampleSaved) {
                    val sample = profile?.let { throughputSamples[it] }
                    if (sample != null) Toast.makeText(this@MainActivity,
                        "Скорость: ↓ ${String.format(java.util.Locale.US, "%.1f", sample.downloadMbps)} / ↑ ${String.format(java.util.Locale.US, "%.1f", sample.uploadMbps)} Мбит/с",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Не удалось измерить скорость сервера", Toast.LENGTH_SHORT).show()
                }
            }
            renderProfiles()
        }
    }
    // Monotonic timestamps prevent auto-failover from trusting an old ping after network conditions change.
    private val latencyMeasuredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val latencyCheckGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var urlTestProfileMap: Map<String, String> = emptyMap()
    private var latencyTestInFlight = false
    // Reject late broadcasts from a cancelled or superseded latency-test session.
    private var activeLatencySessionId: String? = null
// A cancelled config build must not occupy a worker while a retry is queued.
    private var latencyPreparationTask: java.util.concurrent.Future<*>? = null
    private var pendingFastestSelection = false
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newFixedThreadPool(3)
    // Keep auto-selection probes isolated from background list refresh probes.
    private val autoSelectExecutor = Executors.newFixedThreadPool(3)
    private var selectingFastest = false
    private var tilePendingStart = false
    private var selectedProfile: String? = null
    private var pendingFailoverSelection: FailoverSelectionTransaction? = null
    private var persistedSelectionSnapshot: String? = null
    private var selectionPersistenceReady = false
    private var activeProfile: String? = null
    private var pendingConfig: String? = null
    // Profile captured together with the config while Android VPN consent is pending.
    private var pendingProfile: String? = null
    private var connected = false
    private var connecting = false
    private var disconnecting = false
    private val connectRetryHandler = Handler(Looper.getMainLooper())
    private var connectRetryTask: Runnable? = null
    private var activityStarted = false
    private var connectRetryGeneration = 0L
    private var connectRetryAttempt = 0
    private var receiverRegistered = false
    private var outboundTrafficRefresh: (() -> Unit)? = null
    private val connectionHistory by lazy { ConnectionHistoryStore(this) }
    private val latencyHistory by lazy { LatencyHistoryStore(this) }
    private var refreshingSubscriptions = false
    private var pendingBackupBytes: ByteArray? = null
    private var sortMode = "original"
    // Tracks the last filter value actually used to render rows, preventing Spinner
    // adapter/selection callbacks from scheduling duplicate full list rebuilds.
    private var renderedCountryFilterSelection: String? = null
    private var subscriptionsExpanded = false
    // Reuse parsed node metadata between ping/throughput UI refreshes. These values
    // change only when the stored profile list changes, not on every measurement.
    private var cachedProfileOrder: List<String> = emptyList()
    private var cachedProfileDetails: Map<String, Pair<String, String>> = emptyMap()
    private var cachedProfileFlags: Map<String, String> = emptyMap()
    private var cachedNormalizedProfileNames: Map<String, String> = emptyMap()
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
    private lateinit var monitorVpnSpeed: TextView
    private lateinit var monitorLocation: TextView
    private lateinit var speedGraph: SpeedGraphView
    private lateinit var monitorSpeedStats: TextView
    private lateinit var monitorSpeedDiagnostics: TextView
    private lateinit var monitorDailyTraffic: TextView
    private lateinit var monitorWeeklyTraffic: TextView
    private val dailyTrafficHistory: DailyTrafficHistory by lazy {
        DailyTrafficHistory.decode(getSharedPreferences("astra_daily_traffic", MODE_PRIVATE).getString("week", null))
    }
    private val dailyTrafficAccumulator: DailyTrafficAccumulator by lazy {
        val prefs = getSharedPreferences("astra_daily_traffic", MODE_PRIVATE)
        DailyTrafficAccumulator(
            dayKey = prefs.getInt("day", 0),
            lastRx = prefs.getLong("rx", -1L),
            lastTx = prefs.getLong("tx", -1L),
            downloaded = prefs.getLong("downloaded", 0L),
            uploaded = prefs.getLong("uploaded", 0L)
        )
    }
    private val monitorExecutor = Executors.newSingleThreadExecutor()
    private val monitorHandler = Handler(Looper.getMainLooper())
    private var lastRxBytes = TrafficStats.UNSUPPORTED.toLong()
    private var lastTxBytes = TrafficStats.UNSUPPORTED.toLong()
    private var lastTrafficAt = 0L
    // Network type and carrier metadata change infrequently; avoid querying telephony every frame.
    private var lastNetworkLabelAt = 0L
    private val healthCheckExecutor = Executors.newSingleThreadExecutor()
    private val diagnosticsExecutor = Executors.newSingleThreadExecutor()
    private val healthCheckInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    // A probe belongs to the exact tunnel configuration that started it. Reloading a profile
    // can reuse the same Android VPN Network, so Network identity alone cannot reject stale results.
    private val tunnelHealthCheckGeneration = TunnelHealthCheckGeneration()
    @Volatile private var lastHealthCheckAt = 0L
    @Volatile private var tunnelInternetOk: Boolean? = null
    @Volatile private var waitingForUnderlyingNetwork = false
    @Volatile private var waitingForVpnRoute = false
    private var consecutiveHealthFailures = 0
    private var lastAutoFailoverAt = 0L
    private val autoFailoverBudget = AutoFailoverBudget(maxAttempts = 3, windowMs = 10 * 60_000L)
    private val speedDropPolicy = SpeedDropFailoverPolicy(maxSampleGapMs = 5_000L)
    private val vpnTrafficRateTracker = VpnTrafficRateTracker()
    private var lastVpnTrafficSnapshotAt = 0L
    private var originalCountry = "Определяем…"
    private var vpnCountry = "—"
    private var lastGeoConnectedState: Boolean? = null
    private var brandTapCount = 0
    private var brandTapWindowStartedAt = 0L
    // Observe physical Wi-Fi/mobile/Ethernet changes directly instead of waiting
    // for the periodic monitor tick. This callback never treats the VPN transport
    // itself as an underlay and is registered only while the Activity is visible.
    private var physicalNetworkCallbackRegistered = false
    // React immediately when Battery Saver changes while the Activity is visible.
    private var batterySaverReceiverRegistered = false
    private val batterySaverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
            setHeroAnimationsAllowed(!isBatterySaverActive())
            // Apply the lower polling cadence immediately when system Battery Saver changes.
            monitorHandler.removeCallbacks(monitorTick)
            monitorHandler.post(monitorTick)
        }
    }
    private val physicalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) = onPhysicalNetworkChanged()
        override fun onLost(network: android.net.Network) = onPhysicalNetworkChanged()
        override fun onCapabilitiesChanged(
            network: android.net.Network,
            networkCapabilities: NetworkCapabilities
        ) = onPhysicalNetworkChanged()

        override fun onLinkPropertiesChanged(
            network: android.net.Network,
            linkProperties: android.net.LinkProperties
        ) = onPhysicalNetworkChanged()
    }

    private val physicalNetworkChangeDebounce = Runnable {
        // Several callbacks commonly arrive for one Wi-Fi/cellular handover
        // (availability, capabilities and link properties). Collapse them into
        // one transition so a probe is not repeatedly invalidated mid-handover.
        lastNetworkLabelAt = 0L
        lastHealthCheckAt = 0L
        tunnelHealthCheckGeneration.advance()
        consecutiveHealthFailures = 0
        tunnelInternetOk = null
        if (connected) {
            status.text = "Проверяем интернет через VPN…"
            status.setTextColor(AMBER)
        }
        updateProtectionLabels()
        updateNetworkLabel()
        if (connected) scheduleTunnelHealthCheck()
    }

    private fun onPhysicalNetworkChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            monitorHandler.post { onPhysicalNetworkChanged() }
            return
        }
        monitorHandler.removeCallbacks(physicalNetworkChangeDebounce)
        monitorHandler.postDelayed(
            physicalNetworkChangeDebounce,
            NetworkRecoveryPolicy.NETWORK_CHANGE_DEBOUNCE_MS
        )
    }

    private fun registerPhysicalNetworkCallback() {
        if (physicalNetworkCallbackRegistered) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .registerNetworkCallback(request, physicalNetworkCallback)
            physicalNetworkCallbackRegistered = true
        }.onFailure {
            android.util.Log.w("AstraVPN", "Не удалось зарегистрировать мониторинг физической сети", it)
        }
    }

    private fun unregisterPhysicalNetworkCallback() {
        if (!physicalNetworkCallbackRegistered) return
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(physicalNetworkCallback)
        }
        physicalNetworkCallbackRegistered = false
    }

    private val monitorTick = object : Runnable {
        override fun run() {
            updateTrafficMonitor()
            updateNetworkLabel()
            scheduleTunnelHealthCheck()
            val interval = NetworkRecoveryPolicy.monitorIntervalMs(
                appBatterySaverEnabled = settingsPrefs.getBoolean("battery_saver_mode", false),
                systemBatterySaverEnabled = getSystemService(android.os.PowerManager::class.java).isPowerSaveMode
            )
            monitorHandler.postDelayed(this, interval)
        }
    }

    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AstraLatencyTestService.ACTION_RESULTS -> {
                    if (intent.getStringExtra(AstraLatencyTestService.EXTRA_SESSION) != activeLatencySessionId) return
                    val payload = intent.getStringExtra(AstraLatencyTestService.EXTRA_RESULTS) ?: return
                    val finished = intent.getBooleanExtra(AstraLatencyTestService.EXTRA_FINAL, false)
                    applyLatencyResults(payload, finished)
                    if (finished) activeLatencySessionId = null
                    return
                }
                AstraLatencyTestService.ACTION_ERROR -> {
                    if (intent.getStringExtra(AstraLatencyTestService.EXTRA_SESSION) != activeLatencySessionId) return
                    activeLatencySessionId = null
                    getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
                        .remove(KEY_LATENCY_SESSION_ID).apply()
                    latencyTestInFlight = false
                    pendingFastestSelection = false
                    selectingFastest = false
                    tilePendingStart = false
                    // Recover measurements that the service persisted before this error;
                    // the Activity may have been recreated and missed earlier partial broadcasts.
                    restorePendingLatencyResult()
                    urlTestProfileMap.values.forEach { profile ->
                        // A broken test engine/channel is not evidence that the proxy is offline.
                        // Keep unknown results neutral; the toast below explains the test failure.
                        if (freshLatencyMs(profile) == null) pingInfo[profile] = "н/д"
                    }
                    val message = intent.getStringExtra(AstraLatencyTestService.EXTRA_ERROR)
                        ?: "Проверка прервана. Сохранены уже полученные результаты."
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                            renderProfiles()
                        }
                    }
                    return
                }
                AstraVpnService.ACTION_TRAFFIC -> {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) outboundTrafficRefresh?.invoke()
                    }
                    return
                }
                AstraVpnService.ACTION_STATE -> Unit
                else -> return
            }
            val serviceState = intent.getStringExtra(AstraVpnService.EXTRA_STATE)
            // CONNECTING/CONNECTED may be delayed broadcasts from a request that
            // was already in flight when the user pressed Disconnect. Only the
            // terminal DISCONNECTED acknowledgement may clear that stop intent.
            val disconnectPending = settingsPrefs.getBoolean("vpn_disconnect_requested", false)
            if (DisconnectRequestPolicy.shouldClearAfterServiceState(serviceState)) {
                settingsPrefs.edit().remove("vpn_disconnect_requested").apply()
            } else if (disconnectPending &&
                DisconnectRequestPolicy.shouldIgnoreWhileDisconnectPending(serviceState)
            ) {
                // Keep the last confirmed tunnel state until STOP is acknowledged.
                // Delayed CONNECTING/CONNECTED/ERROR broadcasts belong to an older
                // request and must not revive the UI or trigger a retry/dialog.
                return
            }
            when (serviceState) {
                AstraVpnService.STATE_CONNECTING -> {
                    cancelConnectRetry(resetAttempts = false)
                    tunnelHealthCheckGeneration.advance()
                    waitingForVpnRoute = false
                    disconnecting = false
                    connected = false
                    connecting = true
                    consecutiveHealthFailures = 0
                    lastHealthCheckAt = 0L
                    status.text = "Создаём VPN-туннель…"
                    status.setTextColor(AMBER)
                }
                AstraVpnService.STATE_CONNECTED -> {
                    cancelConnectRetry(resetAttempts = true)
                    waitingForUnderlyingNetwork = false
                    waitingForVpnRoute = false
                    tunnelHealthCheckGeneration.advance()
                    disconnecting = false
                    connected = true
                    if (settingsPrefs.getLong(KEY_VPN_CONNECTED_SINCE, 0L) <= 0L) {
                        settingsPrefs.edit().putLong(KEY_VPN_CONNECTED_SINCE, System.currentTimeMillis()).apply()
                    }
                    connecting = false
                    tunnelInternetOk = null
                    lastHealthCheckAt = 0L
                    // Android CONNECTED confirms that a TUN exists, not that
                    // internet traffic can pass through the selected proxy.
                    status.text = "Проверяем интернет через VPN…"
                    status.setTextColor(AMBER)
                    updateProtectionLabels()
                    scheduleTunnelHealthCheck()
                }
                AstraVpnService.STATE_DISCONNECTED -> {
                    // A user stop or a terminal failover failure can end in
                    // DISCONNECTED rather than ERROR. Do not leave a provisional
                    // automatic selection pinned after its replacement tunnel is gone.
                    rollbackPendingFailoverSelection()
                    waitingForUnderlyingNetwork = false
                    waitingForVpnRoute = false
                    tunnelHealthCheckGeneration.advance()
                    disconnecting = false
                    connected = false
                    settingsPrefs.edit().remove(KEY_VPN_CONNECTED_SINCE).apply()
                    connecting = false
                    consecutiveHealthFailures = 0
                    tunnelInternetOk = null
                    status.text = "Отключено"
                    status.setTextColor(MUTED)
                }
                AstraVpnService.STATE_ERROR -> {
                    val wasAutomaticFailover = pendingFailoverSelection != null
                    rollbackPendingFailoverSelection()
                    tunnelHealthCheckGeneration.advance()
                    disconnecting = false
                    connected = false
                    settingsPrefs.edit().remove(KEY_VPN_CONNECTED_SINCE).apply()
                    connecting = false
                    consecutiveHealthFailures = 0
                    tunnelInternetOk = false
                    status.text = "Ошибка подключения"
                    status.setTextColor(RED)
                    // AstraVpnService sends a user-safe explanation in EXTRA_ERROR.
                    // Prefer it over a generic dialog so the user knows what to fix.
                    val reportedError = intent.getStringExtra(AstraVpnService.EXTRA_ERROR)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    val message = reportedError ?: "VPN не удалось запустить. Проверь выбранный сервер и подключение к интернету."
                    val retryable = intent.getBooleanExtra(AstraVpnService.EXTRA_RETRYABLE, false)
                    if (!scheduleTransientConnectRetry(retryable, wasAutomaticFailover)) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Не удалось подключиться")
                            .setMessage(message)
                            .setPositiveButton("Понятно", null)
                            .show()
                    }
                }
            }
            updateGeoLocationForState()
            updateProtectionLabels()
            AstraHomeWidgetProvider.refreshAll(this@MainActivity)
            renderProfiles()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = ProfileStore(this)
        runCatching {
            val filter = IntentFilter().apply {
                addAction(AstraThroughputProbeService.ACTION_RESULT)
                addAction(AstraThroughputProbeService.ACTION_ERROR)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(throughputReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(throughputReceiver, filter)
            throughputReceiverRegistered = true
        }
        // Restore only the user's selected node. Actual VPN connectivity is
        // independently verified against Android's live VPN transport in onStart().
        selectedProfile = runCatching { profileStore.selectedProfile() }.getOrNull()
        persistedSelectionSnapshot = selectedProfile
        selectionPersistenceReady = true
        AstraNoticeChannels.ensureCreated(this)
        AstraQuickShortcut.publish(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF05070D.toInt(), 0xFF10131F.toInt(), 0xFF070A12.toInt()))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val settingsButton = android.widget.ImageView(this).apply {
            contentDescription = "Настройки Astra VPN"
            setImageResource(R.drawable.ic_settings_sliders)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = rounded(CARD, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { showAppSettingsDialog() }
        }
        val brand = TextView(this).apply {
            text = "ASTRA"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            includeFontPadding = true
            letterSpacing = .045f
            contentDescription = "ASTRA"
            setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - brandTapWindowStartedAt > 2_500L) {
                    brandTapWindowStartedAt = now
                    brandTapCount = 0
                }
                brandTapCount++
                if (brandTapCount >= 7) {
                    brandTapCount = 0
                    showSecretOrbit()
                }
            }
        }
        val addButton = TextView(this).apply {
            text = "＋ Добавить орбиту"
            contentDescription = "Добавить сервер или подписку"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(Color.WHITE)
            background = rounded(CARD, 18).apply { setStroke(dp(1), 0xFF465B85.toInt()) }
            setOnClickListener {
                if (!importClipboardInOneTap()) showImportDialog()
            }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(brand, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(12) })
        header.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))

        val connectCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Let the orbit scene float on the night-sky background.
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 0f
        }
        val connectionCaption = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(9))
        }
        connectionIndicator = View(this).apply {
            background = rounded(0xFF8290E8.toInt(), 99)
            contentDescription = "Индикатор состояния VPN"
        }
        connectionCaption.addView(connectionIndicator, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
            rightMargin = dp(8)
        })
        connectionCaption.addView(TextView(this).apply {
            text = "СТАТУС СОЕДИНЕНИЯ"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .16f
            gravity = Gravity.CENTER
            setTextColor(LIGHT_ACCENT)
        })
        status = TextView(this).apply {
            text = "Выбери сервер"
            textSize = 20f
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
        constellationView = ConstellationView(this).apply {
            contentDescription = "Созвездия Astra. Звёзды оживают при подключении"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        connectButton = MaterialButton(this).apply {
            text = "✦"
            contentDescription = "Подключить или отключить VPN"
            isAllCaps = false
            textSize = 48f
            letterSpacing = 0f
            typeface = Typeface.DEFAULT
            setTextColor(Color.WHITE)
            cornerRadius = dp(56)
            minWidth = 0
            minHeight = dp(108)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFC7CED8.toInt())
            rippleColor = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
            insetTop = 0
            insetBottom = 0
            elevation = dp(4).toFloat()
            isEnabled = false
            setOnClickListener {
                // Give immediate visible feedback before asking Android to start/stop VpnService.
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                starBurstView.play()
                animate().cancel()
                animate().scaleX(0.88f).scaleY(0.88f).alpha(0.72f).setDuration(90L)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(190L).start()
                    }.start()
                if (!connected && !connecting) launchNightShootingStarIfNeeded()
                if (disconnecting) {
                    return@setOnClickListener
                }
                if (pendingConfig != null) {
                    cancelPendingConnect()
                } else if (connected && selectedProfile == activeProfile) {
                    status.text = "Отключаем VPN…"
                    status.setTextColor(AMBER)
                    connectActionLabel.text = "Отключаем…"
                    applyManualBankingOverride(connecting = false)
                    stopTunnel()
                } else {
                    status.text = "Подготавливаем подключение…"
                    status.setTextColor(AMBER)
                    connectActionLabel.text = "Подключаемся…"
                    connectSelectedProfile()
                }
            }
        }
        connectActionLabel = TextView(this).apply {
            text = "Подключиться"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(9), 0, 0)
        }
        connectionCard = connectCard
        // The single status label below replaces this redundant caption row.
        connectCard.addView(status, matchWidth())
        orbitNameLabel = TextView(this).apply {
            text = "Выбери сервер"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD98A.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(1), dp(4), 0)
        }
        orbitMetaLabel = TextView(this).apply {
            text = "ASTRA  ·  ОЖИДАНИЕ СЕРВЕРА"
            textSize = 10f
            letterSpacing = .13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF9CA7C1.toInt())
            setPadding(0, dp(2), 0, dp(7))
            visibility = View.GONE
        }
        connectCard.addView(orbitNameLabel, matchWidth())
        connectCard.addView(orbitMetaLabel, matchWidth())
        connectCard.addView(constellationView, LinearLayout.LayoutParams(matchWidth().width, dp(30)).apply {
            topMargin = dp(1)
            bottomMargin = dp(2)
        })
        selectedInfo.visibility = View.GONE
        connectCard.addView(selectedInfo, matchWidth())
        starBurstView = StarBurstView(this).apply {
            contentDescription = "Анимация запуска: расширяющееся световое кольцо"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        starButtonFrame = android.widget.FrameLayout(this).apply {
            addView(OrbitHeroView(this@MainActivity), android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(connectButton, android.widget.FrameLayout.LayoutParams(dp(108), dp(108), Gravity.CENTER))
            addView(starBurstView, android.widget.FrameLayout.LayoutParams(dp(108), dp(108), Gravity.CENTER))
        }
        connectCard.addView(starButtonFrame, LinearLayout.LayoutParams(matchWidth().width, dp(184)).apply {
            topMargin = dp(0)
            bottomMargin = dp(0)
        })
        orbitMetricsLabel = TextView(this).apply {
            text = "00:00:00"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD98A.toInt())
            setPadding(dp(4), dp(5), dp(4), 0)
            contentDescription = "Длительность VPN-подключения"
        }
        orbitSpeedLabel = TextView(this).apply {
            text = "↓ — Мбит/с     ↑ — Мбит/с"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF9CA7C1.toInt())
            setPadding(dp(4), 0, dp(4), dp(7))
            contentDescription = "Скорость загрузки и отдачи через VPN"
        }
        connectCard.addView(orbitMetricsLabel, matchWidth())
        connectCard.addView(orbitSpeedLabel, matchWidth())
        connectActionLabel.visibility = View.GONE

        quickSwitchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF11172A.toInt(), 22).apply { setStroke(dp(1), 0xFF283653.toInt()) }
        }
        val quickSwitchHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        quickSwitchHeader.addView(TextView(this).apply {
            text = "БЫСТРЫЙ ПЕРЕХОД"; textSize = 10f; letterSpacing = .14f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF9CAAD0.toInt())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        quickSwitchHeader.addView(TextView(this).apply {
            text = "Все серверы  ›"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFC5D0FF.toInt()); gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(2), dp(5)); isClickable = true; isFocusable = true
            // Handler is attached after listHeader is created.
        })
        quickSwitchContainer.addView(quickSwitchHeader, matchWidth())
        connectCard.addView(quickSwitchContainer, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(12))
        }
        quickSwitchHeader.getChildAt(1).apply {
            contentDescription = "Показать список всех серверов"
            setOnClickListener {
                // listHeader is outside profileScroll. Its parent-relative top
                // coordinate is not a valid ScrollView offset.
                profileScroll.post {
                    profileScroll.smoothScrollTo(0, 0)
                    profileScroll.requestFocus()
                }
            }
        }
        val listTitle = TextView(this).apply {
            text = "ОРБИТЫ"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val updateSubscriptions = TextView(this).apply {
            text = "↻ Обновить"
            contentDescription = "Обновить подписки"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            background = rounded(0x1A9AA6BF, 12)
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40A9B8FF),
                null,
                null
            )
            setOnClickListener { refreshSubscriptions() }
        }
        throughputCheckButton = TextView(this).apply {
            text = "⇅ Скорость"
            contentDescription = "Измерить скорость выбранного сервера"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(9), 0, dp(9), 0)
            isClickable = true
            isFocusable = true
            background = rounded(0x1A9AA6BF, 12)
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40A9B8FF), null, null
            )
            setOnClickListener { if (throughputProbeInFlight) cancelThroughputProbe() else startThroughputProbe() }
        }
        pingCheckButton = TextView(this).apply {
            text = "◷ Пинг"
            contentDescription = "Вручную проверить задержку всех серверов"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(9), 0, dp(9), 0)
            isClickable = true
            isFocusable = true
            background = rounded(0x1A9AA6BF, 12)
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40A9B8FF),
                null,
                null
            )
            setOnClickListener {
                if (latencyTestInFlight) cancelLatencyCheck() else checkAllLatencies()
            }
        }
        val refresh = TextView(this).apply {
            text = "✦ Прыжок"
            contentDescription = "Найти сервер с наименьшей измеренной задержкой"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            isFocusable = true
            background = rounded(0x1A9AA6BF, 12)
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40A9B8FF),
                null,
                null
            )
            setOnClickListener { selectFastestServer() }
        }
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, dp(44), 1f))
        listHeader.addView(updateSubscriptions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        listHeader.addView(throughputCheckButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(5) })
        listHeader.addView(pingCheckButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            leftMargin = dp(5)
        })
        listHeader.addView(refresh, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            leftMargin = dp(5)
        })

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(8))
        }
        val clearSearch = TextView(this).apply {
            text = "×"
            textSize = 23f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            contentDescription = "Очистить поиск"
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            background = rounded(0x1A9AA6BF, 12)
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x40A9B8FF),
                null,
                null
            )
            setOnClickListener { serverSearch.text?.clear() }
        }
        serverSearch = EditText(this).apply {
            hint = "Поиск сервера…"
            contentDescription = "Поиск серверов по названию, протоколу или ссылке"
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(12), 0, dp(8), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF172238.toInt(), 0xFF111A2A.toInt())).apply {
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), 0xFF2D3B56.toInt())
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    // Debounce fast typing: rebuilding hundreds of server rows on every
                    // keystroke causes avoidable UI-thread work on mid-range phones.
                    searchRenderHandler.removeCallbacks(searchRenderTask)
                    searchRenderHandler.postDelayed(searchRenderTask, 140L)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        countryFilter = Spinner(this).apply {
            contentDescription = "Фильтр серверов по стране"
            setPadding(dp(8), 0, dp(6), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF172238.toInt(), 0xFF111A2A.toInt())).apply {
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), 0xFF2D3B56.toInt())
            }
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selected = parent?.getItemAtPosition(position)?.toString()
                    if (selected != renderedCountryFilterSelection) {
                        renderedCountryFilterSelection = selected
                        renderProfiles()
                    }
                }
            }
        }
        filterRow.addView(serverSearch, LinearLayout.LayoutParams(0, dp(48), 1f))
        filterRow.addView(clearSearch, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(4) })
        filterRow.addView(countryFilter, LinearLayout.LayoutParams(dp(132), dp(48)).apply { leftMargin = dp(4) })
        serverCountLabel = TextView(this).apply {
            text = "СЕРВЕРЫ  ·  НАСТРОЙКА СОРТИРОВКИ"
            textSize = 10f
            letterSpacing = .06f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(40)
            background = rounded(0xFF141E31.toInt(), 12).apply { setStroke(dp(1), 0xFF26344D.toInt()) }
            // This is an informational count, not a hidden settings shortcut.
            // Settings have their own explicit, consistently placed header button.
            isClickable = false
            isFocusable = false
            contentDescription = "Количество серверов и текущий режим сортировки"
        }
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(profileList)
        }

        root.addView(header)
        root.addView(connectCard, matchWidth().apply { bottomMargin = dp(8) })
        monitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD, 20).apply { setStroke(dp(1), 0xFF26344C.toInt()) }
        }
        val monitorHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, 0, 0, dp(2))
        }
        monitorHeader.addView(TextView(this).apply {
            text = "МОНИТОРИНГ СЕТИ"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            setTextColor(LIGHT_ACCENT)
        }, LinearLayout.LayoutParams(0, dp(28), 1f))
        val monitorExpand = TextView(this).apply {
            text = "Подробнее  ⌄"
            textSize = 12f
            setTextColor(LIGHT_ACCENT)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Развернуть мониторинг сети"
        }
        monitorHeader.addView(monitorExpand)
        monitorCard.addView(monitorHeader, matchWidth())
        monitorProtection = TextView(this).apply {
            text = "🛡 Защита: отключена"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
            setPadding(0, dp(5), 0, 0)
        }
        monitorCard.addView(monitorProtection)
        val monitorDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(7), 0, 0)
        }
        monitorHeader.setOnClickListener {
            val expanded = monitorDetails.visibility != View.VISIBLE
            monitorDetails.visibility = if (expanded) View.VISIBLE else View.GONE
            monitorExpand.text = if (expanded) "Свернуть  ⌃" else "Подробнее  ⌄"
            monitorExpand.contentDescription = if (expanded) "Свернуть мониторинг сети" else "Развернуть мониторинг сети"
            // Phone-state permission is only needed for optional carrier details.
            // Ask when the user opens the network monitor, not during cold startup.
            if (expanded) requestPhoneStatePermissionIfNeeded()
        }
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
        monitorSpeedStats = TextView(this).apply {
            text = "Средняя: ↓ — / ↑ — Мбит/с   ·   Пик: ↓ — / ↑ —"
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(5))
        }
        monitorVpnCountry = TextView(this).apply {
            text = "Страна выхода VPN: —"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(6))
        }
        monitorDetails.addView(monitorVpnCountry)
        monitorDetails.addView(monitorNetwork)
        monitorDetails.addView(monitorOperator)
        monitorDetails.addView(TextView(this).apply {
            text = "Скорость всего устройства · последние 60 секунд\n*Трафик за день с момента первого замера; возможна неполная статистика"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        })
        monitorDetails.addView(speedRow)
        monitorDetails.addView(speedGraph, LinearLayout.LayoutParams(matchWidth().width, dp(82)))
        monitorDetails.addView(monitorSpeedStats)
        monitorSpeedDiagnostics = TextView(this).apply {
            text = "Диагностика скорости: ожидаем первый замер"
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(6))
        }
        monitorDetails.addView(monitorSpeedDiagnostics)
        monitorDetails.addView(TextView(this).apply {
            text = "Скорость через VPN · sing-box proxy outbound"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(7), 0, dp(2))
        })
        monitorVpnSpeed = TextView(this).apply {
            text = "VPN ↓ — / ↑ — Мбит/с · ожидаем данные"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(0, 0, 0, dp(6))
        }
        monitorDetails.addView(monitorVpnSpeed)
        monitorDailyTraffic = TextView(this).apply {
            text = "Сегодня: ↓ — / ↑ —"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, dp(6))
        }
        monitorDetails.addView(monitorDailyTraffic)
        monitorWeeklyTraffic = TextView(this).apply {
            text = "За 7 дней*: ↓ — / ↑ —"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(8))
        }
        monitorDetails.addView(monitorWeeklyTraffic)
        monitorLocation = TextView(this).apply {
            text = "IP-геолокация: определяем…"
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        }
        monitorDetails.addView(monitorLocation)
        monitorCard.addView(monitorDetails, matchWidth())
        // Network monitoring is intentionally hidden from the home screen.
        // It remains available from Settings → Tools.
        root.addView(listHeader)
        root.addView(filterRow, matchWidth())
        root.addView(serverCountLabel, matchWidth().apply { bottomMargin = dp(8) })
        root.addView(profileScroll, LinearLayout.LayoutParams(matchWidth().width, 0, 1f))
        // targetSdk 35 enforces edge-to-edge on Android 15+. Keep the
        // programmatic home screen clear of status/navigation bars and cutouts
        // without changing its existing visual spacing on older Android versions.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                topInset = safe.top
                bottomInset = safe.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    topInset = insets.systemWindowInsetTop
                    bottomInset = insets.systemWindowInsetBottom
                }
            }
            view.setPadding(dp(18), dp(14) + topInset, dp(18), dp(14) + bottomInset)
            insets
        }
        setContentView(root)
        root.requestApplyInsets()
        if (!settingsPrefs.getBoolean("onboarding_completed", false)) {
            root.post { if (!isFinishing && !isDestroyed) showFirstRunOnboarding() }
        }
        // Accept server links shared from browsers, messengers and file viewers.
        // The user still confirms the import explicitly in Astra's own sheet.
        intent?.takeIf { it.action == Intent.ACTION_SEND }?.let { sharedIntent ->
            val sharedText = sharedIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: sharedIntent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                    runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull()
                }
            sharedText?.takeIf { it.isNotBlank() }?.let { text ->
                root.post { if (!isFinishing && !isDestroyed) showImportDialog(text) }
            }
        }
        requestNotificationPermissionIfNeeded()
        // Phone-state permission is requested lazily when the optional network
        // monitor is expanded, keeping first launch focused on connecting.
        // onStart() restores tunnel state and renders the profile list once.
        // Avoid building every server row twice during a cold launch.
        updateNetworkLabel()
        // Check the open-app option immediately and keep a lightweight scheduler alive
        // while this screen is active; the worker checks settings every minute.
        SubscriptionRefreshScheduler.sync(this)
        // onStart() performs the open-time subscription check. Avoid doing the
        // same preference/source scan here during cold-start initialization.
        autoUpdateHandler.postDelayed(autoUpdateTick, 60_000L)
        // GeoIP is queried after restoreVpnState() determines whether a VPN is already active.
        // Let the first frame become interactive before starting the isolated
        // sing-box measurement engine. This avoids competing with cold app startup.
        autoUpdateHandler.postDelayed({
            if (!isFinishing && !isDestroyed && !latencyTestInFlight) checkAllLatencies()
        }, 1_200L)
    }

    /**
     * Imports a recognized VPN link or HTTPS subscription directly from the clipboard.
     * If the clipboard does not look like supported input, fall back to the import sheet.
     */
    private fun importClipboardInOneTap(): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val value = runCatching {
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip!!.itemCount > 0) {
                clipboard.primaryClip!!.getItemAt(0).coerceToText(this)?.toString()?.trim()
            } else null
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false

        val looksLikeVpnInput = value.lineSequence().any { line ->
            val item = line.trim()
            item.startsWith("vless://", true) ||
                item.startsWith("hysteria2://", true) ||
                item.startsWith("hy2://", true) ||
                item.startsWith("tuic://", true) ||
                item.startsWith("https://", true)
        }
        if (!looksLikeVpnInput) return false

        Toast.makeText(this, "Импортируем данные из буфера…", Toast.LENGTH_SHORT).show()
        importExecutor.execute {
            val result = runCatching {
                val parsed = SubscriptionParser.importDetailed(value)
                val links = parsed.links
                require(links.isNotEmpty()) { "В буфере не найдено ни одного поддерживаемого сервера." }
                if (value.trimStart().startsWith("https://", true)) {
                    profileStore.replaceSubscription(value.trim(), links, parsed.metadata)
                } else {
                    profileStore.addAll(links)
                }
                links
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { links ->
                    renderProfiles()
                    checkAllLatencies()
                    Toast.makeText(this, "Добавлено серверов: ${links.size}", Toast.LENGTH_LONG).show()
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Не удалось импортировать")
                        .setMessage(error.message?.takeIf { it.isNotBlank() }
                            ?: "Проверь ссылку в буфере и подключение к интернету.")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }
        return true
    }

    private fun startQrScan() {
        runCatching {
            val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning
                .getClient(this, options)
                .startScan()
                .addOnSuccessListener { barcode ->
                    if (isFinishing || isDestroyed) return@addOnSuccessListener
                    val value = barcode.rawValue?.trim().orEmpty()
                    if (value.isEmpty()) {
                        Toast.makeText(this, "В QR-коде не найден текст", Toast.LENGTH_SHORT).show()
                    } else {
                        showImportDialog(value)
                    }
                }
                .addOnCanceledListener {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Сканирование отменено", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            "Не удалось запустить сканер. Проверь наличие и обновление Google Play services.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }.onFailure {
            if (!isFinishing && !isDestroyed) {
                Toast.makeText(
                    this,
                    "Сканирование QR-кодов сейчас недоступно на этом устройстве.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showImportDialog(initialText: String? = null) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF111A2B.toInt(), 0xFF080D18.toInt())
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        // A visible grab handle makes the import panel feel like a native
        // draggable sheet instead of a dialog floating over the home screen.
        panel.addView(View(this).apply {
            background = rounded(0xFF53617A.toInt(), 99)
        }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(View(this), LinearLayout.LayoutParams(0, dp(38), 1f))
        topRow.addView(TextView(this).apply {
            text = "ДОБАВЛЕНИЕ СЕРВЕРОВ"
            textSize = 10f
            letterSpacing = .14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(LIGHT_ACCENT)
        }, LinearLayout.LayoutParams(0, dp(38), 3f))
        topRow.addView(TextView(this).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            background = rounded(CARD, 14)
            contentDescription = "Закрыть"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(topRow)

        panel.addView(TextView(this).apply {
            text = "Добавить серверы"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(15), 0, dp(3))
        })
        panel.addView(TextView(this).apply {
            text = "Вставь ссылку на сервер или HTTPS-подписку. Импортированные профили хранятся в защищённом хранилище устройства."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(18))
        })
        panel.addView(TextView(this).apply {
            text = "ССЫЛКА ИЛИ АДРЕС ПОДПИСКИ"
            textSize = 10f
            letterSpacing = .09f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(dp(2), 0, 0, dp(8))
        })

        val input = EditText(this).apply {
            hint = "https://… или vless://…"
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1A2942.toInt(), 0xFF121C2E.toInt())
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF465B85.toInt())
            }
        }
        initialText?.trim()?.takeIf { it.isNotEmpty() }?.let { sharedText ->
            input.setText(sharedText)
            input.setSelection(input.text.length)
        }
        panel.addView(input, matchWidth().apply { bottomMargin = dp(6) })

        // Lightweight local preview: never performs network access or claims
        // that a profile is valid. Full validation remains in the import worker.
        val importPreview = TextView(this).apply {
            textSize = 11f
            setTextColor(MUTED)
            setPadding(dp(2), 0, dp(2), dp(10))
            contentDescription = "Предварительное количество ссылок серверов"
        }
        fun refreshImportPreview(value: String) {
            val trimmed = value.trim()
            val isSubscription = trimmed.startsWith("https://", true)
            val count = trimmed.lineSequence()
                .map { it.trim() }
                .filter { line ->
                    listOf("vless://", "hysteria2://", "hy2://", "tuic://")
                        .any { line.startsWith(it, true) }
                }
                .distinct()
                .count()
            importPreview.text = when {
                trimmed.isEmpty() -> "Поддерживаются VLESS, Hysteria2, TUIC и HTTPS-подписки"
                isSubscription -> "HTTPS-подписка · серверы будут определены после загрузки"
                count > 0 -> "Найдено ссылок серверов: $count · проверка при импорте"
                else -> "Ссылки серверов пока не распознаны"
            }
        }
        refreshImportPreview(input.text.toString())
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshImportPreview(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        panel.addView(importPreview, matchWidth())

        val pasteButton = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Из буфера"
            isAllCaps = false
            textSize = 13f
            setTextColor(LIGHT_ACCENT)
            setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clipText = runCatching {
                    if (clipboard.hasPrimaryClip() && clipboard.primaryClip!!.itemCount > 0) {
                        clipboard.primaryClip!!.getItemAt(0).coerceToText(this@MainActivity)?.toString()
                    } else null
                }.getOrNull()?.trim().orEmpty()
                if (clipText.isNotEmpty()) {
                    input.setText(clipText)
                    input.setSelection(input.text.length)
                } else {
                    Toast.makeText(this@MainActivity, "В буфере обмена нет текста", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val importMethodsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        importMethodsRow.addView(pasteButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        val qrButton = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "▦ QR"
            isAllCaps = false
            textSize = 13f
            setTextColor(LIGHT_ACCENT)
            setOnClickListener {
                dialog.dismiss()
                startQrScan()
            }
        }
        importMethodsRow.addView(qrButton, LinearLayout.LayoutParams(0, dp(44), 0.78f).apply { rightMargin = dp(5) })

        val fileButton = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "Файл"
            isAllCaps = false
            textSize = 13f
            setTextColor(LIGHT_ACCENT)
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                        "text/plain", "application/octet-stream", "application/x-yaml"
                    ))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                runCatching { startActivityForResult(intent, REQUEST_IMPORT_FILE) }
                    .onFailure {
                        Toast.makeText(this@MainActivity, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show()
                    }
            }
        }
        importMethodsRow.addView(fileButton, LinearLayout.LayoutParams(0, dp(44), 0.9f))
        panel.addView(importMethodsRow, matchWidth())
        panel.addView(TextView(this).apply {
            text = "VLESS  ·  Hysteria2  ·  TUIC  ·  HTTPS-подписки"
            textSize = 11f
            setTextColor(MUTED)
            setPadding(dp(2), dp(12), dp(2), dp(16))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cancelButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Отмена"
            isAllCaps = false
            setTextColor(LIGHT_ACCENT)
            setOnClickListener { dialog.dismiss() }
        }
        val importButton = MaterialButton(this).apply {
            text = "Импортировать"
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF17120A.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFD6A84F.toInt())
            elevation = dp(2).toFloat()
        }
        actions.addView(cancelButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(8) })
        actions.addView(importButton, LinearLayout.LayoutParams(0, dp(52), 1.35f))
        panel.addView(actions)
        dialog.setContentView(panel)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.setBackgroundColor(Color.TRANSPARENT)
            sheet?.layoutParams?.height = ViewGroup.LayoutParams.WRAP_CONTENT
            sheet?.let {
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).apply {
                    state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
        }
        importButton.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isEmpty()) {
                input.error = "Вставь ссылку"
                return@setOnClickListener
            }
            importButton.isEnabled = false
            cancelButton.isEnabled = false
            input.isEnabled = false
            pasteButton.isEnabled = false
            importButton.text = "Импортируем…"
            status.text = "Импортируем серверы…"
            importExecutor.execute {
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
                        Toast.makeText(this@MainActivity, "Добавлено серверов: ${links.size}", Toast.LENGTH_LONG).show()
                    }.onFailure { error ->
                        status.text = "Ошибка импорта"
                        status.setTextColor(RED)
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
        dialog.show()
    }

    /**
     * Refresh each previously imported HTTPS subscription independently.
     * The previous server set is kept whenever download, parsing or storage fails.
     */
    private fun refreshSubscriptions(showResult: Boolean = true, onlySources: List<String>? = null) {
        // Refreshing subscription data does not alter the currently active tunnel.
        if (refreshingSubscriptions) {
            Toast.makeText(this, "Обновление уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        val currentSources = safeSubscriptionSources() ?: return
        val sources = onlySources?.filter { it in currentSources } ?: currentSources
        if (sources.isEmpty()) {
            if (onlySources == null) {
                Toast.makeText(this, "Сначала импортируй HTTPS-ссылку подписки", Toast.LENGTH_LONG).show()
            }
            return
        }
        val selectionSnapshot = selectedProfile
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
                SubscriptionRefreshHistory.recordAttempt(settingsPrefs, source)
                val result = runCatching {
                    SubscriptionRefreshCoordinator.refresh(profileStore, source)
                }
                result.onSuccess {
                    SubscriptionRefreshHistory.recordSuccess(settingsPrefs, source)
                    updated++
                    totalProfiles += it
                }.onFailure {
                    SubscriptionRefreshHistory.recordFailure(settingsPrefs, source)
                    // Parser/network exceptions may contain the full subscription URL
                    // (including credentials). Keep provider secrets out of dialogs.
                    errors += "Подписка ${index + 1}: не удалось обновить"
                    failedSources += SubscriptionSourceFingerprint.of(source)
                }
            }
            // Keep manual/foreground refresh failures in the same fingerprint-only
            // retry queue used by WorkManager, so a later background run retries
            // only failed providers rather than downloading every successful one.
            SubscriptionRefreshRetryState.persist(settingsPrefs, failedSources, currentSources)
            runOnUiThread {
                refreshingSubscriptions = false
                if (updated > 0) {
                    settingsPrefs.edit().putLong("last_subscription_update", System.currentTimeMillis()).apply()
                }
                if (isFinishing || isDestroyed) return@runOnUiThread
                remapSelectionAfterRefresh(selectionSnapshot)
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

    /**
     * Notify only when the provider actually supplied expiry/traffic metadata.
     * Each warning class is deduplicated per subscription to avoid noisy repeats.
     */
    private fun checkSubscriptionWarnings() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val summaries = safeSubscriptionSummaries() ?: return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        AstraNoticeChannels.ensureCreated(this)
        val prefs = getSharedPreferences("astra_subscription_notices", MODE_PRIVATE)
        summaries.forEach { item ->
            val key = item.sourceUrl.hashCode().toUInt().toString(16)
            val nowSeconds = System.currentTimeMillis() / 1000L
            val expiry = item.expiresAtEpochSeconds
            if (expiry != null && expiry > 0L) {
                val remaining = expiry - nowSeconds
                val warning = when {
                    remaining <= 0L -> "expired" to "Срок действия подписки истёк"
                    remaining <= 24L * 60L * 60L -> "1d" to "Подписка истекает в течение суток"
                    remaining <= 7L * 24L * 60L * 60L -> "7d" to "Подписка истекает в течение 7 дней"
                    else -> null
                }
                if (warning != null) {
                    val noticeKey = "${key}_${warning.first}"
                    if (!prefs.getBoolean(noticeKey, false)) {
                        postSubscriptionNotice(manager, key.hashCode(), item.title, warning.second)
                        prefs.edit().putBoolean(noticeKey, true).apply()
                    }
                }
            }
            val total = item.totalBytes?.takeIf { it > 0L }
            if (total != null && (item.uploadBytes != null || item.downloadBytes != null)) {
                val remaining = (total - subscriptionUsedBytes(item)).coerceAtLeast(0L)
                if (remaining <= total / 10L) {
                    val noticeKey = "${key}_traffic_low"
                    if (!prefs.getBoolean(noticeKey, false)) {
                        postSubscriptionNotice(
                            manager,
                            key.hashCode() xor 0x51A7,
                            item.title,
                            "Осталось около ${(remaining.toDouble() / total.toDouble() * 100.0).toInt().coerceIn(0, 100)}% трафика"
                        )
                        prefs.edit().putBoolean(noticeKey, true).apply()
                    }
                }
            }
        }
    }

    private fun postSubscriptionNotice(
        manager: android.app.NotificationManager,
        id: Int,
        title: String,
        message: String
    ) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            android.app.PendingIntent.getActivity(
                this,
                id,
                it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(this, AstraNoticeChannels.SUBSCRIPTIONS)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        builder.setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(title.ifBlank { "Astra VPN" })
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
        if (pendingIntent != null) builder.setContentIntent(pendingIntent)
        manager.notify(20_000 + (id and 0x0FFF), builder.build())
    }

    private fun subscriptionExpiryState(item: SubscriptionSummary): Triple<String, Int, Int> {
        val expiry = item.expiresAtEpochSeconds ?: return Triple("HTTPS", LIGHT_ACCENT, 0xFF252B48.toInt())
        if (expiry <= 0L) return Triple("БЕЗ СРОКА", GREEN, 0xFF193B35.toInt())
        val remainingSeconds = expiry - System.currentTimeMillis() / 1000L
        return when {
            remainingSeconds <= 0L -> Triple("СРОК ИСТЁК", RED, 0xFF462832.toInt())
            remainingSeconds <= 7L * 24L * 60L * 60L -> Triple("СКОРО ИСТЕКАЕТ", AMBER, 0xFF40351F.toInt())
            else -> Triple("АКТИВНА", GREEN, 0xFF193B35.toInt())
        }
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
                "Использовано: ${formatTraffic(usedBytes)} из ${formatTraffic(item.totalBytes)}\nОсталось: ${formatTraffic(remaining)}"
            }
            item.uploadBytes != null || item.downloadBytes != null ->
                "Использовано: ${formatTraffic(usedBytes)}\nОбщий лимит не указан"
            else -> "Провайдер не передал данные о трафике"
        }
        val expiry = item.expiresAtEpochSeconds?.let { seconds ->
            if (seconds <= 0L) "Без ограничения срока"
            else java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(seconds * 1000L))
        } ?: "Не указан провайдером"
        val history = SubscriptionRefreshHistory.get(settingsPrefs, item.sourceUrl)
        val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy · HH:mm", java.util.Locale.getDefault())
        val historyText = when (history?.state) {
            "updating" -> "Синхронизация выполняется"
            "success" -> "Успешно · ${dateFormat.format(java.util.Date(history.timestamp))}"
            "failure" -> "Последняя попытка завершилась ошибкой · ${dateFormat.format(java.util.Date(history.timestamp))}"
            else -> "История обновлений пока отсутствует"
        }
        val historyColor = when (history?.state) {
            "updating" -> AMBER
            "success" -> GREEN
            "failure" -> RED
            else -> MUTED
        }
        val detailsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD, 16).apply { setStroke(dp(1), 0xFF2B3A55.toInt()) }
        }
        fun addDetail(label: String, value: String, valueColor: Int = Color.WHITE) {
            detailsPanel.addView(TextView(this).apply {
                text = label.uppercase(java.util.Locale.getDefault())
                textSize = 9f
                letterSpacing = .08f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MUTED)
                setPadding(0, dp(8), 0, dp(3))
            })
            detailsPanel.addView(TextView(this).apply {
                text = value
                textSize = 13f
                setTextColor(valueColor)
                setPadding(0, 0, 0, dp(2))
            })
        }
        addDetail("СОСТОЯНИЕ СИНХРОНИЗАЦИИ", historyText, historyColor)
        addDetail("ИСТОЧНИК", host)
        addDetail("СЕРВЕРЫ", item.serverCount.toString())
        addDetail("ТРАФИК", trafficDetails)
        addDetail("СРОК ДЕЙСТВИЯ", expiry)
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setView(detailsPanel)
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
                        val removesActiveProfile = activeProfile?.let { it in removed } == true
                        val removesPendingProfile = pendingProfile?.let { it in removed } == true ||
                            selectedProfile?.let { it in removed } == true
                        // Removing a subscription while Android's VPN consent is open must
                        // invalidate the captured config; otherwise a late RESULT_OK could
                        // start a profile that has already been deleted.
                        if (pendingConfig != null && removesPendingProfile) cancelPendingConnect()
                        if (removesActiveProfile) {
                            cancelBankingResumeForRemovedProfile()
                            stopTunnel()
                        }
                        if (removesActiveProfile) activeProfile = null
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
        val selectionSnapshot = selectedProfile
        refreshingSubscriptions = true
        status.text = "Обновляем подписку…"
        status.setTextColor(AMBER)
        importExecutor.execute {
            SubscriptionRefreshHistory.recordAttempt(settingsPrefs, source)
            val result = runCatching {
                // Use the same transaction lock as foreground bulk refresh and
                // WorkManager so an older response cannot overwrite newer data.
                SubscriptionRefreshCoordinator.refresh(profileStore, source)
            }
            result.onSuccess { SubscriptionRefreshHistory.recordSuccess(settingsPrefs, source) }
                .onFailure { SubscriptionRefreshHistory.recordFailure(settingsPrefs, source) }
            runOnUiThread {
                refreshingSubscriptions = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isSuccess) remapSelectionAfterRefresh(selectionSnapshot)
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

    /**
     * Rebind the user's selection to a rotated subscription URI without touching the
     * active tunnel. Ignore the snapshot if the user selected another row meanwhile.
     */
    private fun remapSelectionAfterRefresh(selectionSnapshot: String?) {
        if (selectedProfile != selectionSnapshot || selectionSnapshot == null) return
        val latestProfiles = safeProfiles() ?: return
        val remapped = ProfileSelectionMatcher.remap(selectionSnapshot, latestProfiles)
        val resolvedSelection = ProfileSelectionRefreshPolicy.resolve(
            previousSelection = selectionSnapshot,
            remappedSelection = remapped,
            connectedToPreviousSelection = connected && activeProfile == selectionSnapshot
        )
        // Subscription refresh can rotate the selected URI while Android's
        // consent dialog is still open. Never let its captured old config start.
        if (pendingConfig != null && pendingProfile != resolvedSelection) cancelPendingConnect()
        selectedProfile = resolvedSelection
    }

    /**
     * Save selection only when it changes. ProfileStore encrypts the URI using
     * Android Keystore, and its restore path remaps rotated subscription links.
     */
    private fun persistSelectedProfileIfChanged() {
        if (!selectionPersistenceReady || persistedSelectionSnapshot == selectedProfile) return
        runCatching { profileStore.setSelectedProfile(selectedProfile) }
            .onSuccess { persistedSelectionSnapshot = selectedProfile }
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
        if (::throughputCheckButton.isInitialized) {
            throughputCheckButton.text = when {
                throughputBatchActive -> "× Стоп ${(throughputBatchCompleted + 1).coerceAtMost(throughputBatchTotal)}/$throughputBatchTotal"
                throughputProbeInFlight -> "× Стоп"
                else -> "⇅ Скорость"
            }
            throughputCheckButton.contentDescription = when {
                throughputBatchActive -> "Отменить автоматическое сравнение скорости серверов, проверено $throughputBatchCompleted из $throughputBatchTotal"
                throughputProbeInFlight -> "Отменить измерение скорости"
                else -> "Измерить скорость выбранного сервера"
            }
        }
        if (::pingCheckButton.isInitialized) {
            pingCheckButton.text = if (latencyTestInFlight) "× Отмена" else "◷ Пинг"
            pingCheckButton.contentDescription = if (latencyTestInFlight) "Отменить проверку задержки серверов" else "Вручную проверить задержку всех серверов"
        }
        val profiles = safeProfiles() ?: return
        val summaries = safeSubscriptionSummaries() ?: return
        // Persisted/previously measured Ping values are useful only for a bounded
        // period in this process. Build one fresh snapshot for every visible
        // decision so stale numbers cannot affect sorting, badges or "fastest".
        val freshLatencySnapshot = LatencyFreshnessPolicy.freshLatencies(
            latencies = latencyMs,
            measuredAtElapsedMs = latencyMeasuredAt,
            nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
            candidates = profiles
        )
        val previousScrollY = if (::profileScroll.isInitialized) profileScroll.scrollY else 0
        profileList.removeAllViews()
        // Latency updates and favorite changes rebuild the current rows. Keep the user's
        // place in a long server list instead of jumping back to the top each time.
        if (::profileScroll.isInitialized) {
            profileScroll.post { profileScroll.scrollTo(0, previousScrollY) }
        }
        if (summaries.isNotEmpty()) {
            profileList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(2), 0, dp(10))
                val subscriptionsToggle = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    background = rounded(0xFF141E31.toInt(), 13)
                }
                subscriptionsToggle.addView(TextView(this@MainActivity).apply {
                    text = "Подписки · ${summaries.size}"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                subscriptionsToggle.addView(TextView(this@MainActivity).apply {
                    text = if (subscriptionsExpanded) "Свернуть  ⌃" else "Показать  ⌄"
                    textSize = 12f
                    setTextColor(LIGHT_ACCENT)
                })
                subscriptionsToggle.setOnClickListener {
                    subscriptionsExpanded = !subscriptionsExpanded
                    renderProfiles()
                }
                addView(subscriptionsToggle, matchWidth())
                val lastSuccess = settingsPrefs.getLong("last_subscription_update", 0L)
                val lastAttempt = settingsPrefs.getLong("last_subscription_attempt", 0L)
                val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy · HH:mm", java.util.Locale.getDefault())
                val historyText = when {
                    refreshingSubscriptions -> "Идёт синхронизация источников…"
                    lastSuccess > 0L -> "Последняя успешная синхронизация: ${dateFormat.format(java.util.Date(lastSuccess))}"
                    lastAttempt > 0L -> "Последняя попытка: ${dateFormat.format(java.util.Date(lastAttempt))} · успешных обновлений пока нет"
                    else -> "Подписки ещё не обновлялись"
                }
                addView(TextView(this@MainActivity).apply {
                    text = historyText
                    textSize = 10f
                    setTextColor(if (refreshingSubscriptions) AMBER else MUTED)
                    setPadding(dp(2), dp(5), 0, 0)
                    visibility = if (subscriptionsExpanded) View.VISIBLE else View.GONE
                })
            })
            summaries.forEach { subscription ->
                val refreshEntry = SubscriptionRefreshHistory.get(settingsPrefs, subscription.sourceUrl)
                val isSubscriptionRefreshing = refreshEntry?.state == "updating"
                val card = LinearLayout(this).apply {
                    visibility = if (subscriptionsExpanded) View.VISIBLE else View.GONE
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(13), dp(15), dp(13))
                    background = rounded(CARD, 16).apply {
                        setStroke(dp(if (isSubscriptionRefreshing) 2 else 1), if (isSubscriptionRefreshing) 0xFFD6B36A.toInt() else 0xFF2B3A55.toInt())
                    }
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Подписка ${subscription.title}, ${subscription.serverCount} серверов. Нажмите для управления."
                    setOnClickListener { showSubscriptionDetails(subscription) }
                }
                val subscriptionHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                subscriptionHeader.addView(TextView(this).apply {
                    text = "◈  ${subscription.title.takeUnless { title -> title.startsWith("base64:", true) || (title.length > 48 && title.matches(Regex("[A-Za-z0-9+/=_-]+"))) } ?: (Uri.parse(subscription.sourceUrl).host ?: "Подписка")}"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val subscriptionState = subscriptionExpiryState(subscription)
                subscriptionHeader.addView(TextView(this).apply {
                    text = if (isSubscriptionRefreshing) "ОБНОВЛЕНИЕ" else subscriptionState.first
                    textSize = 9f
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = .05f
                    setTextColor(if (isSubscriptionRefreshing) AMBER else subscriptionState.second)
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = rounded(if (isSubscriptionRefreshing) 0xFF40351F.toInt() else subscriptionState.third, 10)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                })
                card.addView(subscriptionHeader, matchWidth())
                card.addView(TextView(this).apply {
                    text = "${subscription.serverCount} серверов  ·  Нажми для управления"
                    textSize = 12f
                    setTextColor(LIGHT_ACCENT)
                    setPadding(0, dp(6), 0, dp(5))
                })
                val refreshLabel = when (refreshEntry?.state) {
                    "updating" -> "Синхронизация сейчас"
                    "success" -> "Последнее обновление: "
                    "failure" -> "Ошибка обновления: "
                    else -> null
                }
                if (refreshLabel != null) {
                    val stamp = java.text.SimpleDateFormat("dd.MM.yyyy · HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(refreshEntry!!.timestamp))
                    card.addView(TextView(this).apply {
                        text = refreshLabel + stamp
                        textSize = 10f
                        setTextColor(when (refreshEntry.state) {
                            "updating" -> AMBER
                            "success" -> GREEN
                            else -> RED
                        })
                        setPadding(0, 0, 0, dp(5))
                    })
                }
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
                text = "ОРБИТЫ"
                textSize = 11f
                letterSpacing = .10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LIGHT_ACCENT)
                setPadding(dp(2), dp(8), 0, dp(8))
            })
        }
        // Ping and throughput updates can trigger frequent row refreshes. Profile
        // names, protocols and country resolution are invariant until the profile
        // list itself changes, so parse them once and reuse the immutable snapshot.
        if (cachedProfileOrder != profiles) {
            val details = profiles.associateWith { link ->
                val uri = Uri.parse(link)
                val fragment = uri.fragment?.let { Uri.decode(it) }?.trim().orEmpty()
                val cleanFragment = fragment.removePrefix("#").replace(Regex("^\\d+[ _-]+"), "").trim()
                val looksEncoded = cleanFragment.startsWith("base64:", true) || (cleanFragment.length > 48 && cleanFragment.matches(Regex("[A-Za-z0-9+/=_-]+")))
                val name = cleanFragment.takeUnless { it.isBlank() || looksEncoded } ?: uri.host.orEmpty().ifBlank { "Сервер" }
                val protocol = when (uri.scheme?.lowercase()) {
                    "vless" -> "VLESS"
                    "hysteria2", "hy2" -> "Hysteria2"
                    "tuic" -> "TUIC"
                    else -> "VPN"
                }
                name to protocol
            }
            cachedProfileOrder = profiles.toList()
            cachedProfileDetails = details
            cachedProfileFlags = details.mapValues { (_, value) -> CountryFlagResolver.flagFor(value.first) }
            cachedNormalizedProfileNames = details.mapValues { (_, value) ->
                value.first.lowercase(java.util.Locale.getDefault())
            }
        }
        val profileDetails = cachedProfileDetails
        val profileFlags = cachedProfileFlags
        val normalizedNames = cachedNormalizedProfileNames
        val allCountries = profileFlags.values
            .filter { it.isNotBlank() && it != "🌐" }.distinct().sorted()
        // Use the same monochrome ISO language as the server badges; emoji flags
        // remain internal resolver keys and are never shown in the filter.
        val countryOptions = allCountries.map { flag ->
            val code = CountryFlagResolver.countryCodeForFlag(flag) ?: "—"
            "$code  ${CountryFlagResolver.countryNameForFlag(flag)}"
        }
        val filterOptions = listOf("Все страны") + countryOptions
        val currentOptions = (0 until countryFilter.count).mapNotNull { countryFilter.getItemAtPosition(it)?.toString() }
        if (currentOptions != filterOptions) {
            val previousSelection = countryFilter.selectedItem?.toString()
            countryFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, filterOptions)
            val restoreIndex = filterOptions.indexOf(previousSelection).takeIf { it >= 0 } ?: 0
            countryFilter.setSelection(restoreIndex, false)
        }
        renderedCountryFilterSelection = countryFilter.selectedItem?.toString()
        val selectedCountry = countryFilter.selectedItem?.toString()
            ?.takeIf { it != "Все страны" }
            ?.substringBefore("  ")?.let { code ->
                allCountries.firstOrNull { CountryFlagResolver.countryCodeForFlag(it) == code }
            }
        val query = serverSearch.text?.toString()?.trim()?.lowercase(java.util.Locale.getDefault()).orEmpty()
        val filteredProfiles = profiles.filter { link ->
            val matchesText = query.isBlank() || normalizedNames[link].orEmpty().contains(query) ||
                link.lowercase(java.util.Locale.ROOT).contains(query) ||
                profileDetails[link]?.second.orEmpty().lowercase(java.util.Locale.ROOT).contains(query)
            val matchesCountry = selectedCountry == null || profileFlags[link] == selectedCountry
            matchesText && matchesCountry
        }
        val sortLabel = when (sortMode) {
            "ping" -> "по пингу"
            "alphabet" -> "по алфавиту"
            else -> "как в подписке"
        }
        val measuredPingCount = freshLatencySnapshot.size
        serverCountLabel.text = if (latencyTestInFlight) {
            "${filteredProfiles.size} орбит  ·  пинг $measuredPingCount/${profiles.size}"
        } else {
            "${filteredProfiles.size} орбит  ·  $sortLabel"
        }
        serverCountLabel.contentDescription = if (latencyTestInFlight) {
            "Проверка задержки: получены результаты для $measuredPingCount из ${profiles.size} серверов."
        } else {
            "Показано ${filteredProfiles.size} из ${profiles.size} серверов. Сортировка: $sortLabel. Измерено серверов: $measuredPingCount из ${profiles.size}."
        }
        if (selectedProfile == null || selectedProfile !in profiles) {
            // The live tunnel may still be using a profile removed/rotated by refresh.
            // Keep that active identity until the user explicitly chooses another node.
            selectedProfile = if (connected && activeProfile != null &&
                (selectedProfile == null || selectedProfile == activeProfile)
            ) activeProfile else profiles.firstOrNull()
        }
        persistSelectedProfileIfChanged()
        val selected = selectedProfile
        connectButton.isEnabled = !disconnecting && (connected || (selected != null && !connecting))
        connectActionLabel.text = when {
            disconnecting -> "Отключаем…"
            connecting -> "Подключаемся…"
            connected && selected != activeProfile -> "Переключить"
            connected -> "Отключить"
            else -> "Подключиться"
        }
        updateConnectionCardAppearance(when {
            disconnecting -> "disconnecting"
            connecting -> "connecting"
            connected && selected == activeProfile -> "connected"
            connected -> "switching"
            else -> "idle"
        })
        // Re-apply health tint after rendering; ordinary renders must not overwrite
        // the latency color with a generic action-state color.
        updateConnectButtonServerHealth()
        connectButton.contentDescription = when {
            disconnecting -> "Отключение VPN выполняется"
            connecting -> "Подключение VPN выполняется"
            connected && selected == activeProfile -> "Отключить VPN"
            connected -> "Переключить VPN на выбранный сервер"
            else -> "Подключить VPN к выбранному серверу"
        }
        if (::quickSwitchContainer.isInitialized) renderQuickSwitch(profiles)
        updateOrbitIdentity()
        if (selected != null && connected && selected == activeProfile) {
            // Keep the home screen concise: this line identifies the selected server.
            selectedInfo.text = profileSummary(selected)
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
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(24), dp(20), dp(22))
                background = rounded(CARD, 20)
            }
            val emptyTitle = TextView(this).apply {
                text = if (profiles.isEmpty()) "Пока нет серверов" else "Ничего не найдено"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }
            val emptyMessage = TextView(this).apply {
                text = if (profiles.isEmpty()) {
                    "Добавь сервер по ссылке или импортируй подписку — они появятся здесь."
                } else {
                    "Попробуй изменить запрос или выбрать другую страну."
                }
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(0, dp(7), 0, dp(15))
            }
            emptyCard.addView(emptyTitle)
            emptyCard.addView(emptyMessage)
            if (profiles.isEmpty()) {
                val importAction = MaterialButton(this).apply {
                    text = "Добавить сервер"
                    isAllCaps = false
                    setOnClickListener { showImportDialog() }
                }
                emptyCard.addView(importAction, LinearLayout.LayoutParams(matchWidth().width, dp(48)))
            } else {
                val resetAction = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Сбросить поиск и фильтры"
                    isAllCaps = false
                    setOnClickListener {
                        serverSearch.setText("")
                        countryFilter.setSelection(0)
                        renderProfiles()
                    }
                }
                emptyCard.addView(resetAction, LinearLayout.LayoutParams(matchWidth().width, dp(48)))
            }
            profileList.addView(emptyCard, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            })
            return
        }

        val favorites = settingsPrefs.getStringSet("favorite_servers", emptySet()) ?: emptySet()
        val displayProfiles = ServerLatencySort.sort(
            profiles = filteredProfiles,
            mode = sortMode,
            latencyMs = freshLatencySnapshot,
            favorites = favorites,
            locale = java.util.Locale.getDefault()
        )
        // In ping-sorted mode, make the best measured node explicit in the list.
        // Unknown/unmeasured nodes never receive this marker.
        val fastestProfile = displayProfiles.firstOrNull { freshLatencySnapshot[it] != null }
            ?.takeIf { sortMode == "ping" }

        // Read persisted throughput samples in one batch before constructing rows.
        // The row builder then consumes this immutable snapshot instead of performing
        // synchronized SharedPreferences/JSON work while each individual row is built.
        val visibleThroughput = throughputHistory.getFresh(displayProfiles)
        visibleThroughput.forEach { (profile, sample) -> throughputSamples[profile] = sample }

        if (displayProfiles.isEmpty()) {
            val hasProfiles = profiles.isNotEmpty()
            val emptyCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(26), dp(22), dp(24))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF1A2540.toInt(), 0xFF111A2C.toInt())
                ).apply {
                    cornerRadius = dp(22).toFloat()
                    setStroke(dp(1), 0xFF34466B.toInt())
                }
            }
            emptyCard.addView(TextView(this).apply {
                text = if (hasProfiles) "⌕" else "✦"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(LIGHT_ACCENT)
            }, matchWidth())
            emptyCard.addView(TextView(this).apply {
                text = if (hasProfiles) "Ничего не найдено" else "Серверы ещё не добавлены"
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(7), 0, dp(4))
            }, matchWidth())
            emptyCard.addView(TextView(this).apply {
                text = if (hasProfiles) {
                    "Попробуй изменить запрос или выбрать другую страну."
                } else {
                    "Добавь ссылку на сервер или HTTPS-подписку, чтобы начать пользоваться Astra VPN."
                }
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(dp(4), 0, dp(4), dp(15))
            }, matchWidth())
            val emptyAction = MaterialButton(this).apply {
                text = if (hasProfiles) "Сбросить фильтры" else "Добавить серверы"
                isAllCaps = false
                textSize = 13f
                cornerRadius = dp(14)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (hasProfiles) {
                        serverSearch.setText("")
                        if (countryFilter.selectedItemPosition != 0) countryFilter.setSelection(0)
                        else renderProfiles()
                    } else {
                        showImportDialog()
                    }
                }
            }
            emptyCard.addView(emptyAction, LinearLayout.LayoutParams(matchWidth().width, dp(48)))
            profileList.addView(emptyCard, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            })
        }

        displayProfiles.forEach { link ->
            val isSelected = link == selected
            val isFastestProfile = link == fastestProfile
            // displayProfiles is derived from profiles, so every row already has
            // prepared details; keep a defensive label without reparsing the URI.
            val detailsForProfile = profileDetails[link]
            val name = detailsForProfile?.first ?: "Сервер"
            val protocolLabel = detailsForProfile?.second ?: "VPN"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(9), dp(12))
                // The active tunnel stays visually identifiable even when another
                // server is selected as the next destination.
                background = serverRowBackground(
                    selected = isSelected,
                    active = connected && link == activeProfile
                )
                // A restrained Material-style ripple gives immediate touch feedback
                // without changing the selected/unselected server card colors.
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x26D6A84F),
                    null,
                    null
                )
                minimumHeight = dp(68)
                elevation = dp(if (isSelected) 3 else 0).toFloat()
                // Expose selection and active-tunnel state as actual Android view
                // state, not only as text in the row's accessibility description.
                this.isSelected = isSelected
                this.isActivated = connected && link == activeProfile
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    stateDescription = when {
                        isSelected && isActivated -> "Выбран и используется"
                        isActivated -> "Используется для VPN"
                        isSelected -> "Выбран для подключения"
                        else -> null
                    }
                }
                contentDescription = buildString {
                    append(name)
                    append(", ")
                    append(protocolLabel)
                    append(", ")
                    append(freshLatencySnapshot[link]?.let { "Пинг $it мс" } ?: when (pingInfo[link]) {
                        "Проверяем…" -> "пинг проверяется"
                        "н/д" -> "пинг не измерен"
                        else -> "задержка не проверена"
                    })
                    if (isSelected) append(", выбран")
                    if (connected && link == activeProfile) append(", активное VPN-соединение")
                }
                setOnClickListener {
                    if (connecting) {
                        Toast.makeText(this@MainActivity, "Дождись завершения текущего переключения", Toast.LENGTH_SHORT).show()
                    } else if (connected && link != activeProfile) {
                        // A new explicit user choice supersedes any delayed retry for the old node.
                        cancelConnectRetry(resetAttempts = true)
                        // One tap switches the active tunnel: no extra confirmation dialog.
                        selectedProfile = link
                        val nextConfig = runCatching { SingBoxConfigBuilder.build(link) }.getOrElse {
                            Toast.makeText(this@MainActivity, "Не удалось подготовить конфигурацию сервера", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        startTunnel(nextConfig)
                    } else {
                        if (selectedProfile != link) cancelConnectRetry(resetAttempts = true)
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
                                throughputSamples.remove(link)
                                runCatching { throughputHistory.remove(link) }
                                if (selectedProfile == link) selectedProfile = null
                                renderProfiles()
                                Toast.makeText(this@MainActivity, "Сервер удалён", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    true
                }
            }
            val countryFlag = profileFlags[link] ?: "🌐"
            val countryCode = CountryFlagResolver.countryCodeForFlag(countryFlag)
            val flag = TextView(this).apply {
                // Use a compact monochrome ISO badge instead of platform-dependent
                // emoji flags. Unknown locations get a short label; the full node
                // name remains visible beside it.
                text = countryCode ?: name.take(3).uppercase(java.util.Locale.ROOT)
                textSize = 11f
                letterSpacing = .08f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFD98A.toInt())
                gravity = Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF29251D.toInt(), 0xFF171C29.toInt())
                ).apply {
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), 0xFF806B3A.toInt())
                }
                contentDescription = countryCode?.let { "Страна сервера: $it" } ?: "Сервер без определённой страны"
            }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(this).apply {
                text = name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val protocolText = TextView(this).apply {
                val countryName = CountryFlagResolver.countryNameForFlag(countryFlag)
                text = if (countryFlag.isNotBlank() && countryFlag != "🌐") {
                    "${countryName.uppercase(java.util.Locale.getDefault())}  ·  ${protocolLabel}"
                } else protocolLabel
                textSize = 9.5f
                letterSpacing = .07f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(LIGHT_ACCENT)
                // Keep protocol/country metadata visually separate from the server
                // name, like a compact badge rather than another plain text line.
                background = rounded(0x1CD6A84F, 7)
                setPadding(dp(7), dp(4), dp(7), dp(4))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val meta = TextView(this).apply {
                val latency = pingInfo[link] ?: "Не проверен"
                val measuredLatency = freshLatencySnapshot[link]
                text = when {
                    measuredLatency != null && isFastestProfile -> "МИНИМАЛЬНЫЙ ПИНГ  ·  ${measuredLatency} мс"
                    measuredLatency != null -> "ПИНГ  ·  ${measuredLatency} мс"
                    latency == "н/д" -> "ПИНГ  ·  нет данных"
                    latency == "Проверяем…" -> "ПИНГ  ·  проверяем…"
                    else -> "ПИНГ  ·  не проверен"
                }
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                // Keep the server list in Astra's gold palette. The exact millisecond
                // value carries the information; color is not used as a fake estimate.
                val tone = if (measuredLatency != null || latency == "Проверяем…") LIGHT_ACCENT else MUTED
                setTextColor(tone)
                val pillColor = if (measuredLatency != null || latency == "Проверяем…") {
                    0x1AD6A84F
                } else {
                    0x1A9AA6BF
                }
                background = rounded(pillColor, 8)
                contentDescription = when {
                    measuredLatency != null && isFastestProfile -> "Самый быстрый измеренный сервер: $measuredLatency миллисекунд"
                    measuredLatency != null -> "Пинг сервера: $measuredLatency миллисекунд, ${latencyQuality(measuredLatency)}"
                    latency == "н/д" -> "Нет достоверных данных о задержке сервера; это не означает, что сервер недоступен"
                    latency == "Проверяем…" -> "Проверяем пинг сервера"
                    else -> "Пинг ещё не проверялся"
                }
                setPadding(dp(9), dp(5), dp(9), dp(5))
            }
            details.addView(title)
            val isActiveServer = connected && link == activeProfile
            if (isSelected || isActiveServer) {
                details.addView(TextView(this).apply {
                    text = when {
                        isSelected && isActiveServer -> "●  АКТИВЕН · ВЫБРАН"
                        isActiveServer -> "●  АКТИВНОЕ СОЕДИНЕНИЕ"
                        else -> "◆  ВЫБРАН ДЛЯ ПОДКЛЮЧЕНИЯ"
                    }
                    textSize = 9f
                    letterSpacing = .055f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isActiveServer) GREEN else LIGHT_ACCENT)
                    setPadding(0, dp(3), 0, dp(4))
                    contentDescription = when {
                        isSelected && isActiveServer -> "Этот сервер выбран и сейчас используется для VPN-соединения"
                        isActiveServer -> "Этот сервер сейчас используется для VPN-соединения"
                        else -> "Этот сервер выбран для следующего подключения"
                    }
                })
            }
            details.addView(protocolText)
            details.addView(meta)
            visibleThroughput[link]?.let { sample ->
                details.addView(TextView(this).apply {
                    text = "↓ ${String.format(java.util.Locale.US, "%.1f", sample.downloadMbps)}  ·  ↑ ${String.format(java.util.Locale.US, "%.1f", sample.uploadMbps)} Мбит/с"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(LIGHT_ACCENT)
                    contentDescription = "Измеренная скорость: загрузка ${sample.downloadMbps} мегабит в секунду, отдача ${sample.uploadMbps} мегабит в секунду"
                    setPadding(dp(2), dp(4), 0, 0)
                })
            }
            val favoriteButton = TextView(this).apply {
                text = if (link in favorites) "★" else "☆"
                textSize = 22f
                setTextColor(if (link in favorites) AMBER else MUTED)
                gravity = Gravity.CENTER
                // A 48dp hit target is easier to use than the visual star's bounds.
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                background = rounded(0x1AD6A84F, 14)
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40D6A84F),
                    null,
                    null
                )
                contentDescription = if (link in favorites) "Убрать сервер $name из избранного" else "Добавить сервер $name в избранное"
                setOnClickListener { toggleFavoriteServer(link) }
            }
            row.addView(flag, LinearLayout.LayoutParams(dp(42), dp(46)))
            val measuredOrbitLatency = freshLatencySnapshot[link]
            val orbitQuality = when {
                measuredOrbitLatency == null || measuredOrbitLatency <= 0L -> 0
                measuredOrbitLatency <= 100L -> 3
                measuredOrbitLatency <= 250L -> 2
                else -> 1
            }
            row.addView(OrbitMarkerView(this).apply {
                quality = orbitQuality
                this.isSelected = isSelected || isActiveServer
                contentDescription = when (orbitQuality) {
                    3 -> "Орбита со звёздным качеством связи"
                    2 -> "Орбита с хорошим качеством связи"
                    1 -> "Орбита с повышенной задержкой"
                    else -> "Орбита ещё не проверена"
                }
            }, LinearLayout.LayoutParams(dp(30), dp(46)).apply { leftMargin = dp(4) })
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            })
            val shareButton = TextView(this).apply {
                text = "↗"
                textSize = 21f
                setTextColor(LIGHT_ACCENT)
                gravity = Gravity.CENTER
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                background = rounded(0x1A9AA6BF, 14)
                foreground = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40A9B8FF), null, null
                )
                contentDescription = "Поделиться сервером $name"
                setOnClickListener {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Astra VPN — $name")
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    startActivity(Intent.createChooser(share, "Поделиться сервером"))
                }
            }
            row.addView(shareButton, LinearLayout.LayoutParams(dp(44), dp(48)).apply { rightMargin = dp(4) })
            row.addView(favoriteButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            profileList.addView(row, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            })
        }
    }

    /** UI health indicators only trust monotonic ping samples no older than five minutes. */
    private fun freshLatencyMs(profile: String): Long? {
        val measured = latencyMs[profile] ?: return null
        val measuredAt = latencyMeasuredAt[profile] ?: return null
        val age = android.os.SystemClock.elapsedRealtime() - measuredAt
        return measured.takeIf {
            it > 0L && age in 0L..LatencyFreshnessPolicy.DEFAULT_MAX_AGE_MS
        }
    }

    private fun updateOrbitIdentity() {
        if (!::orbitNameLabel.isInitialized || !::orbitMetaLabel.isInitialized) return
        val link = selectedProfile
        if (link.isNullOrBlank()) {
            orbitNameLabel.text = "Выбери сервер"
            orbitMetaLabel.text = ""
            orbitMetaLabel.visibility = View.GONE
            return
        }
        val uri = Uri.parse(link)
        val fragment = uri.fragment?.let { Uri.decode(it) }?.trim().orEmpty()
        val clean = fragment.removePrefix("#").replace(Regex("^\\d+[ _-]+"), "").trim()
        val serverName = clean.takeUnless {
            it.isBlank() || it.length > 48 || it.startsWith("base64:", true) ||
                (it.length > 48 && it.matches(Regex("[A-Za-z0-9+/=_-]+")))
        } ?: uri.host.orEmpty().ifBlank { "Сервер" }
        val flag = CountryFlagResolver.flagFor(serverName)
        // Unknown labels must retain the actual server name. The resolver's
        // fallback text ("Другая страна") is not a usable location and makes
        // unrelated nodes look identical in the hero status line.
        val country = CountryFlagResolver.countryNameForFlag(flag)
            .takeUnless { it.isBlank() || it == "Другая страна" }
            ?: serverName
        val latency = freshLatencyMs(link)?.let { "$it мс" } ?: "Пинг не проверен"
        orbitNameLabel.text = "$country  ·  $latency"
        orbitMetaLabel.text = ""
        orbitMetaLabel.visibility = View.GONE
        orbitNameLabel.contentDescription = "Выбранный сервер: $country, задержка $latency"
        orbitMetaLabel.contentDescription = "Протокол " + protocolName(link) + ", задержка " + latency
        updateConnectButtonServerHealth()
    }

    private fun renderQuickSwitch(profiles: List<String>) {
        if (!::quickSwitchContainer.isInitialized) return
        while (quickSwitchContainer.childCount > 1) quickSwitchContainer.removeViewAt(1)
        if (profiles.isEmpty()) { quickSwitchContainer.visibility = View.GONE; return }
        quickSwitchContainer.visibility = View.VISIBLE
        val candidates = profiles.distinct().sortedWith(compareBy<String> { freshLatencyMs(it) ?: Long.MAX_VALUE }.thenBy { it }).take(3)
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        candidates.forEachIndexed { index, link ->
            // renderProfiles() prepares this metadata snapshot before rendering quick
            // switch tiles. Reuse it instead of reparsing the same URI a second time.
            val name = cachedProfileDetails[link]?.first ?: "Сервер"
            val flag = cachedProfileFlags[link] ?: "🌐"
            val country = CountryFlagResolver.countryNameForFlag(flag).takeUnless { it == "Другая страна" } ?: name
            val countryCode = CountryFlagResolver.countryCodeForFlag(flag)
            val measuredLatency = freshLatencyMs(link)
            val ping = measuredLatency?.let { "$it мс" } ?: "— мс"
            val selected = link == selectedProfile
            val activeAndFailed = connected && link == activeProfile && tunnelInternetOk == false
            val healthColor = when {
                activeAndFailed -> RED
                measuredLatency != null && measuredLatency < 200L -> LIGHT_ACCENT
                measuredLatency != null -> AMBER
                else -> MUTED
            }
            val healthLabel = when {
                activeAndFailed -> "НЕ ОТВЕЧАЕТ"
                measuredLatency != null && measuredLatency < 200L -> "СТАБИЛЬНЫЙ"
                measuredLatency != null -> "ВЫСОКАЯ ЗАДЕРЖКА"
                else -> "НЕ ПРОВЕРЕН"
            }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(7), dp(8))
                background = rounded(if (selected) 0xFF282B3A.toInt() else 0xFF151B2D.toInt(), 15).apply {
                    setStroke(dp(1), if (selected) 0xFFD6B66A.toInt() else 0xFF29334D.toInt())
                }
                isClickable = true; isFocusable = true
                foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), null, null)
                contentDescription = "Выбрать сервер $country. Состояние: $healthLabel. Задержка $ping"
                setOnClickListener {
                    if (selectedProfile != link) {
                        // The pending consent belongs to the previously selected profile.
                        // Cancel it before changing selection so RESULT_OK cannot start
                        // a server that no longer matches the UI selection.
                        if (pendingConfig != null) cancelPendingConnect()
                        selectedProfile = link; persistSelectedProfileIfChanged(); renderProfiles()
                        if (connected) connectSelectedProfile()
                    }
                }
            }
            tile.addView(TextView(this@MainActivity).apply {
                // Use a stable country code when known; otherwise show the actual
                // server label instead of the misleading generic "Other country".
                text = countryCode?.let { "$it · ${protocolName(link)}" } ?: name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            tile.addView(TextView(this@MainActivity).apply {
                text = "●  $healthLabel"
                textSize = 8f
                letterSpacing = .035f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(healthColor)
                maxLines = 1
            })
            tile.addView(TextView(this@MainActivity).apply {
                text = ping
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(healthColor)
            })
            tiles.addView(tile, LinearLayout.LayoutParams(0, dp(82), 1f).apply { if (index > 0) leftMargin = dp(6) })
        }
        quickSwitchContainer.addView(tiles, LinearLayout.LayoutParams(matchWidth().width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
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

        fun capabilities(network: android.net.Network?): NetworkCapabilities? =
            network?.let { runCatching { manager.getNetworkCapabilities(it) }.getOrNull() }

        fun isPhysicalInternet(network: android.net.Network?): Boolean {
            val caps = capabilities(network) ?: return false
            return !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        fun isReadyForProbe(network: android.net.Network?): Boolean {
            val caps = capabilities(network) ?: return false
            val notSuspended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            } else {
                true
            }
            return isPhysicalInternet(network) &&
                caps!!.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                notSuspended
        }

        fun transportPriority(network: android.net.Network): Int {
            val caps = capabilities(network) ?: return -1
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 0
                else -> -1
            }
        }

        // While Astra is connected, activeNetwork is the VPN itself. Prefer the
        // exact underlay selected by AstraVpnService, but only while Android still
        // reports it as validated and not suspended.
        AstraPlatform.currentPhysicalNetwork
            ?.takeIf(::isReadyForProbe)
            ?.let { return it }

        // A default network can advertise INTERNET before validation completes
        // (or while a captive portal is present). Do not let that unready network
        // hide another physical network that Android has already validated.
        if (isReadyForProbe(active)) return active

        val validated = manager.allNetworks.asSequence()
            .filter(::isReadyForProbe)
            .sortedWith(
                compareByDescending<android.net.Network> { it == active }
                    .thenByDescending(::transportPriority)
            )
            .firstOrNull()
        if (validated != null) return validated

        // If Android has not validated any network yet, retain the default
        // physical route as a last resort instead of refusing to test entirely.
        if (isPhysicalInternet(active)) return active

        return manager.allNetworks.asSequence()
            .filter(::isPhysicalInternet)
            .sortedWith(
                compareByDescending<android.net.Network> { it == active }
                    .thenByDescending(::transportPriority)
            )
            .firstOrNull()
    }

    private fun latencyQuality(ms: Long): String = LatencyQualityPolicy.label(ms)

    private fun latencyColor(ms: Long): Int = when {
        ms < 200L -> GREEN
        // High latency is degraded quality, not proof of server failure.
        else -> AMBER
    }


    /** Manual check for the selected profile. */
    private fun startThroughputProbe() {
        if (throughputProbeInFlight) return
        if (latencyTestInFlight || selectingFastest) {
            Toast.makeText(this, "Дождись завершения проверки пинга", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = selectedProfile ?: run {
            Toast.makeText(this, "Сначала выбери сервер", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Измеряем скорость выбранного сервера…", Toast.LENGTH_SHORT).show()
        startThroughputProbeForProfile(profile, fastMode = false)
    }

    private fun startThroughputProbeForProfile(profile: String, fastMode: Boolean) {
        val session = java.util.UUID.randomUUID().toString()
        activeThroughputSessionId = session
        activeThroughputProfile = profile
        throughputProbeInFlight = true
        renderProfiles()
        pingExecutor.execute {
            val prepared = runCatching {
                val network = networkForLatencyProbe()
                    ?: error("Физическая сеть недоступна")
                val port = java.net.ServerSocket(0).use { it.localPort }
                val config = SingBoxThroughputTestConfig.build(profile, port).json
                Triple(network, port, config)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || activeThroughputSessionId != session) return@runOnUiThread
                prepared.onSuccess { (network, port, config) ->
                    runCatching {
                        AstraThroughputProbeService.start(
                            this, config, network, profile, port, session, fastMode = fastMode
                        )
                    }.onFailure {
                        handleThroughputProbeStartFailure(session)
                    }
                }.onFailure {
                    handleThroughputProbeStartFailure(session)
                }
            }
        }
    }

    private fun handleThroughputProbeStartFailure(session: String) {
        if (activeThroughputSessionId != session) return
        activeThroughputSessionId = null
        activeThroughputProfile = null
        if (throughputBatchActive) {
            throughputBatchCompleted++
            throughputProbeInFlight = true
            scheduleNextThroughputBatchProbe()
        } else {
            throughputProbeInFlight = false
            Toast.makeText(this, "Не удалось подготовить измерение скорости", Toast.LENGTH_SHORT).show()
        }
        if (!isFinishing && !isDestroyed) renderProfiles()
    }

    private fun startThroughputBatchForFastest(profiles: List<String>) {
        fastestSelectionCandidates = profiles
        val fresh = throughputHistory.getFresh(profiles)
        throughputSamples.putAll(fresh)
        throughputBatchQueue.clear()
        throughputBatchQueue.addAll(
            ThroughputBatchPlanner.plan(profiles, latencyMs, fresh, ThroughputBatchPlanner.DEFAULT_LIMIT)
        )
        if (throughputBatchQueue.isEmpty()) {
            finishFastestSelection()
            return
        }
        throughputBatchGeneration++
        throughputBatchActive = true
        throughputBatchTotal = throughputBatchQueue.size
        throughputBatchCompleted = 0
        throughputProbeInFlight = true
        Toast.makeText(
            this,
            "Сравниваем скорость до $throughputBatchTotal лучших серверов по пингу…",
            Toast.LENGTH_LONG
        ).show()
        startNextThroughputBatchProbe()
    }

    private fun startNextThroughputBatchProbe() {
        if (!throughputBatchActive) return
        val profile = throughputBatchQueue.pollFirst()
        if (profile == null) {
            throughputBatchActive = false
            throughputProbeInFlight = false
            finishFastestSelection()
            return
        }
        throughputProbeInFlight = true
        throughputCheckButton.contentDescription =
            "Проверка скорости: ${throughputBatchCompleted + 1} из $throughputBatchTotal"
        startThroughputProbeForProfile(profile, fastMode = true)
    }

    private fun scheduleNextThroughputBatchProbe() {
        val generation = throughputBatchGeneration
        throughputBatchTask?.let(throughputBatchHandler::removeCallbacks)
        val task = Runnable {
            throughputBatchTask = null
            if (throughputBatchActive && generation == throughputBatchGeneration) {
                startNextThroughputBatchProbe()
            }
        }
        throughputBatchTask = task
        throughputBatchHandler.postDelayed(task, 500L)
    }

    private fun finishFastestSelection() {
        selectingFastest = false
        val candidates = fastestSelectionCandidates
        val timeAdjustedLatencies = TimeOfDayServerOptimizer.adjustedLatencies(
            candidates, latencyMs, { profile -> latencyHistory.samples(profile) }
        )
        val fastest = BestServerSelector.select(
            profiles = candidates,
            latencyMs = timeAdjustedLatencies,
            recentFailures = recentServerFailureCounts(candidates),
            latencyStabilityPenaltyMs = latencyStabilityPenalties(candidates),
            throughputSamples = throughputHistory.getFresh(candidates)
        )
        if (fastest != null) {
            selectedProfile = fastest
            // A successful latency selection is not a VPN connection.
            // Keep the connection status neutral until the tunnel is validated.
            if (!connected && !connecting) {
                status.text = "Быстрый сервер выбран"
                status.setTextColor(MUTED)
                updateConnectionCardAppearance("idle")
            }
            selectedInfo.text = profileSummary(fastest)
            Toast.makeText(this, "Выбрана орбита: " + profileName(fastest), Toast.LENGTH_LONG).show()
            if (tilePendingStart) {
                tilePendingStart = false
                connectSelectedProfile()
            }
        } else {
            tilePendingStart = false
            Toast.makeText(this, "Не удалось получить результаты проверки. Это не означает, что серверы не работают.", Toast.LENGTH_LONG).show()
        }
        if (!isFinishing && !isDestroyed) renderProfiles()
    }

    private fun cancelThroughputProbe(showToast: Boolean = true, refreshUi: Boolean = true) {
        val session = activeThroughputSessionId
        val wasBatch = throughputBatchActive
        if (session == null && !wasBatch) return
        activeThroughputSessionId = null
        activeThroughputProfile = null
        throughputBatchGeneration++
        throughputBatchTask?.let(throughputBatchHandler::removeCallbacks)
        throughputBatchTask = null
        throughputBatchQueue.clear()
        throughputBatchActive = false
        throughputProbeInFlight = false
        if (wasBatch) {
            selectingFastest = false
            pendingFastestSelection = false
            tilePendingStart = false
        }
        if (session != null) runCatching {
            startService(Intent(this, AstraThroughputProbeService::class.java)
                .setAction(AstraThroughputProbeService.ACTION_STOP)
                .putExtra(AstraThroughputProbeService.EXTRA_SESSION, session))
        }
        if (refreshUi && !isFinishing && !isDestroyed) renderProfiles()
        if (showToast) Toast.makeText(this, "Измерение скорости отменено", Toast.LENGTH_SHORT).show()
    }

    private fun cancelLatencyCheck() {
        if (!latencyTestInFlight && !selectingFastest) return
        latencyPreparationTask?.cancel(true)
        latencyPreparationTask = null
        // Invalidate config preparation callbacks so a cancelled test cannot start later.
        latencyCheckGeneration.incrementAndGet()
        activeLatencySessionId = null
        getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
            .remove(KEY_LATENCY_SESSION_ID).apply()
        latencyTestInFlight = false
        pendingFastestSelection = false
        selectingFastest = false
        // A cancelled auto-selection must never trigger a delayed Quick Settings connection.
        tilePendingStart = false
        runCatching {
            startService(Intent(this, AstraLatencyTestService::class.java)
                .setAction(AstraLatencyTestService.ACTION_STOP))
        }
        safeProfiles().orEmpty().forEach { profile ->
            if (freshLatencyMs(profile) == null) pingInfo[profile] = "н/д"
        }
        renderProfiles()
        Toast.makeText(this, "Проверка серверов отменена", Toast.LENGTH_SHORT).show()
    }

    private fun checkAllLatencies() {
        val profiles = safeProfiles()
        if (profiles == null) {
            pendingFastestSelection = false
            selectingFastest = false
            tilePendingStart = false
            return
        }
        if (latencyTestInFlight) {
            Toast.makeText(this, "Проверка серверов уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        if (throughputProbeInFlight) {
            Toast.makeText(this, "Дождись завершения измерения скорости", Toast.LENGTH_SHORT).show()
            return
        }
        latencyCheckGeneration.incrementAndGet()
        val currentProfiles = profiles.toHashSet()
        latencyMs.keys.toList().filterNot { it in currentProfiles }.forEach { latencyMs.remove(it) }
        pingInfo.keys.toList().filterNot { it in currentProfiles }.forEach { pingInfo.remove(it) }
        if (profiles.isEmpty()) {
            pendingFastestSelection = false
            selectingFastest = false
            tilePendingStart = false
            renderProfiles()
            return
        }

        profiles.forEach { profile ->
            latencyMs.remove(profile)
            pingInfo[profile] = "Проверяем…"
        }
        // Flip the state before rendering so the progress counter immediately
        // shows 0/N instead of briefly displaying the idle sorting label.
        latencyTestInFlight = true
        renderProfiles()
        Toast.makeText(this, "Измеряем реальную задержку через серверы…", Toast.LENGTH_SHORT).show()

        // Build/validate the sing-box config off the main thread. Libbox.checkConfig
        // can be expensive with large subscriptions and must not freeze touch feedback.
        val generation = latencyCheckGeneration.get()
        val preparationTask = java.util.concurrent.FutureTask<Unit> {
            val prepared = runCatching { SingBoxUrlTestConfig.build(profiles, SingBoxUrlTestConfig.TEST_URL) }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != latencyCheckGeneration.get()) return@runOnUiThread
                latencyPreparationTask = null
                prepared.onSuccess { testConfig ->
                    urlTestProfileMap = testConfig.profileByOutboundTag
                    // Keep the tag-to-profile map across Activity recreation. The
                    // foreground test service may finish while Android recreates UI.
                    getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LATENCY_PROFILE_MAP, JSONObject(urlTestProfileMap).toString())
                        .remove(KEY_LATENCY_PENDING_RESULTS)
                        .remove(KEY_LATENCY_LAST_RESULTS)
                        .remove(KEY_LATENCY_LAST_RESULTS_AT)
                        .apply()
                    runCatching {
                        val physicalNetwork = networkForLatencyProbe()
                            ?: error("Не найдена физическая сеть для проверки серверов")
                        val sessionId = generation.toString()
                        activeLatencySessionId = sessionId
                        getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
                            .putString(KEY_LATENCY_SESSION_ID, sessionId).apply()
                        AstraLatencyTestService.start(
                            this,
                            testConfig.json,
                            testConfig.profileByOutboundTag.keys,
                            physicalNetwork,
                            sessionId
                        )
                    }.onFailure {
                        activeLatencySessionId = null
                        getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
                            .remove(KEY_LATENCY_SESSION_ID).apply()
                        latencyTestInFlight = false
                        pendingFastestSelection = false
                        selectingFastest = false
                        tilePendingStart = false
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
                    tilePendingStart = false
                    profiles.forEach { profile ->
                        latencyMs.remove(profile)
                        pingInfo[profile] = "н/д"
                    }
                    Toast.makeText(this, "Не удалось подготовить проверку серверов", Toast.LENGTH_SHORT).show()
                    renderProfiles()
                }
            }
        }
        latencyPreparationTask = preparationTask
        pingExecutor.execute(preparationTask)
    }

    /**
     * Chooses the fastest VLESS endpoint using a short TCP reachability test.
     * UDP profiles are deliberately excluded: a TCP result says nothing about
     * Hysteria2/TUIC availability or actual VPN tunnel latency.
     */
    private fun selectFastestServer() {
        if (selectingFastest || latencyTestInFlight || throughputProbeInFlight) {
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
        // Consent UI is asynchronous: block duplicate starts before the service
        // has had a chance to publish STATE_CONNECTING.
        if (connecting || pendingConfig != null) return
        val link = selectedProfile ?: return
        // A deliberate new connection/profile selection supersedes any pending
        // banking auto-resume, including a stale snapshot from a prior process.
        applyManualBankingOverride(connecting = true)
        val config = runCatching { SingBoxConfigBuilder.build(link) }.getOrElse {
            rollbackPendingFailoverSelection()
            // Config/parser diagnostics may include the original proxy URI and credentials.
            // Keep the user-facing error actionable without exposing the imported profile.
            MaterialAlertDialogBuilder(this).setTitle("Ошибка конфигурации")
                .setMessage("Не удалось подготовить конфигурацию сервера. Проверь ссылку и параметры подключения.")
                .setPositiveButton("ОК", null).show()
            renderProfiles()
            return
        }
        pendingConfig = config
        pendingProfile = link
        connecting = true
        status.text = "Подключаемся…"
        status.setTextColor(AMBER)
        renderProfiles()

        // Do not show an extra in-app confirmation when switching profiles.
        // If Astra already owns the VPN, the service reloads sing-box in place
        // and reuses the existing TUN. Android's own VPN permission prompt is
        // still required when permission has not yet been granted.
        requestVpnPermissionAndStart(config)
    }

    private fun requestVpnPermissionAndStart(config: String) {
        try {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) {
                startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
            } else {
                startTunnel(config)
            }
        } catch (error: Exception) {
            rollbackPendingFailoverSelection()
            // If Android cannot launch its consent screen, restore the UI instead
            // of leaving the connect button in a permanent starting state.
            pendingConfig = null
            pendingProfile = null
            connecting = false
            connected = false
            status.text = "Не удалось запросить разрешение VPN"
            status.setTextColor(RED)
            updateProtectionLabels()
            renderProfiles()
            Toast.makeText(
                this,
                "Не удалось открыть системное разрешение VPN. Перезапусти приложение и попробуй снова.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun handleBackupExportResult(uri: Uri?) {
        val bytes = pendingBackupBytes
        pendingBackupBytes = null
        if (uri == null || bytes == null) {
            bytes?.fill(0)
            return
        }
        importExecutor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: error("Не удалось открыть файл для записи")
            }
            bytes.fill(0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    Toast.makeText(this, "Зашифрованная резервная копия сохранена", Toast.LENGTH_LONG).show()
                }.onFailure {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Не удалось сохранить копию")
                        .setMessage("Проверь доступ к выбранному месту и свободное пространство.")
                        .setPositiveButton("ОК", null).show()
                }
            }
        }
    }

    private fun handleBackupImportFile(uri: Uri?) {
        if (uri == null) return
        importExecutor.execute {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= EncryptedBackupCodec.MAX_PLAINTEXT_BYTES + 128) {
                            "Файл резервной копии слишком большой"
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("Не удалось открыть файл резервной копии")
                require(bytes.isNotEmpty()) { "Файл резервной копии пуст" }
                bytes
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    result.getOrNull()?.fill(0)
                    return@runOnUiThread
                }
                result.onSuccess { bytes -> showBackupPasswordDialog(forExport = false, backupBytes = bytes) }
                    .onFailure { error ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Не удалось прочитать копию")
                            .setMessage(error.message ?: "Проверь выбранный файл.")
                            .setPositiveButton("ОК", null).show()
                    }
            }
        }
    }

    private fun showBackupPasswordDialog(forExport: Boolean, backupBytes: ByteArray? = null) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        val passwordInput = EditText(this).apply {
            hint = "Пароль (не менее 12 символов)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        }
        panel.addView(passwordInput, matchWidth())
        val confirmationInput = if (forExport) EditText(this).apply {
            hint = "Повтори пароль"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
        } else null
        confirmationInput?.let { panel.addView(it, matchWidth()) }

        var backupBytesTransferred = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (forExport) "Защитить резервную копию" else "Открыть резервную копию")
            .setMessage(
                if (forExport) "Придумай пароль от 12 символов. Он понадобится при восстановлении на любом устройстве. Astra не сохраняет и не отправляет пароль."
                else "Введи пароль, которым была защищена копия. Без него восстановить профили невозможно."
            )
            .setView(panel)
            .setPositiveButton(if (forExport) "Продолжить" else "Расшифровать", null)
            .setNegativeButton("Отмена") { _, _ -> backupBytes?.fill(0) }
            .show()
        dialog.setOnCancelListener { backupBytes?.fill(0) }
        dialog.setOnDismissListener {
            if (!backupBytesTransferred) backupBytes?.fill(0)
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val password = passwordInput.text.toString()
                    if (password.length !in 12..256) {
                        passwordInput.error = "Нужно от 12 до 256 символов"
                        return@setOnClickListener
                    }
                    if (forExport && confirmationInput?.text?.toString() != password) {
                        confirmationInput?.error = "Пароли не совпадают"
                        return@setOnClickListener
                    }
                    val passwordChars = password.toCharArray()
                    passwordInput.text?.clear()
                    confirmationInput?.text?.clear()
                    dialog.dismiss()
                    if (forExport) {
                        importExecutor.execute {
                            val result = runCatching {
                                val payload = profileStore.exportBackupPayload()
                                try {
                                    EncryptedBackupCodec.encrypt(payload, passwordChars)
                                } finally {
                                    payload.fill(0)
                                }
                            }
                            passwordChars.fill('\u0000')
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                result.onSuccess { encrypted ->
                                    pendingBackupBytes?.fill(0)
                                    pendingBackupBytes = encrypted
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "application/octet-stream"
                                        putExtra(Intent.EXTRA_TITLE, "Astra-VPN-backup.astrabak")
                                    }
                                    runCatching { startActivityForResult(intent, REQUEST_EXPORT_BACKUP) }
                                        .onFailure {
                                            pendingBackupBytes?.fill(0)
                                            pendingBackupBytes = null
                                            Toast.makeText(this, "Не удалось открыть сохранение файла", Toast.LENGTH_LONG).show()
                                        }
                                }.onFailure { error ->
                                    MaterialAlertDialogBuilder(this)
                                        .setTitle("Не удалось создать копию")
                                        .setMessage(error.message ?: "Проверь сохранённые профили.")
                                        .setPositiveButton("ОК", null).show()
                                }
                            }
                        }
                    } else {
                        val encrypted = backupBytes ?: return@setOnClickListener
                        backupBytesTransferred = true
                        importExecutor.execute {
                            val result = runCatching {
                                val payload = EncryptedBackupCodec.decrypt(encrypted, passwordChars)
                                try {
                                    profileStore.restoreBackupPayload(payload)
                                } finally {
                                    payload.fill(0)
                                }
                            }
                            passwordChars.fill('\u0000')
                            encrypted.fill(0)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                result.onSuccess { count ->
                                    renderProfiles()
                                    checkAllLatencies()
                                    Toast.makeText(this, "Восстановлено серверов: $count", Toast.LENGTH_LONG).show()
                                }.onFailure { error ->
                                    MaterialAlertDialogBuilder(this)
                                        .setTitle("Не удалось восстановить копию")
                                        .setMessage(error.message ?: "Неверный пароль или повреждённый файл.")
                                        .setPositiveButton("ОК", null).show()
                                }
                            }
                        }
                    }
                }
    }

    private fun startBackupImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "Выбери резервную копию Astra")
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT_BACKUP) }
            .onFailure { Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show() }
    }

    private fun startBackupExport() {
        showBackupPasswordDialog(forExport = true)
    }

    @Deprecated("Deprecated in Android API, retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                pendingConfig?.let { startTunnel(it) } ?: run {
                    pendingProfile = null
                    connecting = false
                }
            } else {
                // A denied Android consent prompt aborts the provisional failover
                // just like a service-side connection failure. Restore the last
                // known-good profile before clearing the captured request.
                rollbackPendingFailoverSelection()
                pendingConfig = null
                pendingProfile = null
                connecting = false
                status.text = "Разрешение VPN не выдано"
                status.setTextColor(RED)
                renderProfiles()
                updateProtectionLabels()
            }
        } else if (requestCode == REQUEST_EXPORT_BACKUP) {
            handleBackupExportResult(if (resultCode == RESULT_OK) data?.data else null)
        } else if (requestCode == REQUEST_IMPORT_BACKUP && resultCode == RESULT_OK) {
            handleBackupImportFile(data?.data)
        } else if (requestCode == REQUEST_IMPORT_FILE && resultCode == RESULT_OK) {
            val uris = buildList {
                data?.clipData?.let { clip ->
                    for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                }
                data?.data?.let { if (none { existing -> existing == it }) add(it) }
            }
            if (uris.isEmpty()) return
            importExecutor.execute {
                val imported = mutableListOf<String>()
                val failures = mutableListOf<String>()
                uris.forEachIndexed { index, uri ->
                    runCatching {
                        val bytes = contentResolver.openInputStream(uri)?.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 2 * 1024 * 1024) { "Файл больше 2 МБ" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("Не удалось открыть файл")
                        SubscriptionParser.importDetailed(String(bytes, Charsets.UTF_8)).links
                    }.onSuccess { imported.addAll(it) }
                     .onFailure { failures.add("Файл ${index + 1}: ${it.message ?: "не удалось прочитать"}") }
                }
                val result = runCatching {
                    require(imported.isNotEmpty()) {
                        failures.firstOrNull() ?: "В выбранных файлах не найдены поддерживаемые серверы"
                    }
                    profileStore.addAll(imported.distinct())
                    imported.distinct()
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.onSuccess { links ->
                        renderProfiles()
                        checkAllLatencies()
                        status.text = "Импортировано: ${links.size}"
                        status.setTextColor(GREEN)
                        val summary = buildString {
                            append("Добавлено серверов: ${links.size}")
                            if (failures.isNotEmpty()) append("\nНе удалось обработать файлов: ${failures.size}")
                        }
                        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
                        if (failures.isNotEmpty()) {
                            MaterialAlertDialogBuilder(this)
                                .setTitle("Частичный импорт")
                                .setMessage(failures.take(5).joinToString("\n"))
                                .setPositiveButton("ОК", null).show()
                        }
                    }.onFailure { error ->
                        status.text = "Ошибка импорта"
                        status.setTextColor(RED)
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Не удалось импортировать файлы")
                            .setMessage(error.message ?: "Проверь формат выбранных файлов.")
                            .setPositiveButton("ОК", null).show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        setHeroAnimationsAllowed(!isBatterySaverActive())
        registerBatterySaverReceiver()
        registerPhysicalNetworkCallback()
        monitorHandler.removeCallbacks(monitorTick)
        monitorHandler.post(monitorTick)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AstraVpnService.ACTION_STATE)
                addAction(AstraVpnService.ACTION_TRAFFIC)
                addAction(AstraLatencyTestService.ACTION_RESULTS)
                addAction(AstraLatencyTestService.ACTION_ERROR)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(vpnStateReceiver, filter)
            receiverRegistered = true
        }
        restorePendingLatencyResult()
        // The foreground Activity may legally start the user-enabled monitor.
        // Do not start it in the background or without the special Usage Access grant.
        if (settingsPrefs.getBoolean("banking_auto_pause_enabled", false) &&
            UsageAccessPermission.hasAccess(this)
        ) {
            runCatching { BankingForegroundMonitorService.start(this) }
                .onFailure { android.util.Log.w("AstraVPN", "Не удалось запустить банковский монитор", it) }
        }
        sortMode = settingsPrefs.getString("sort_mode", "original") ?: "original"
        restoreVpnState()
        AstraHomeWidgetProvider.refreshAll(this)
        if (intent?.action == ACTION_TILE_TOGGLE) {
            intent.action = null
            handleQuickTileToggle()
        }
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        autoUpdateHandler.postDelayed(autoUpdateTick, 60_000L)
        maybeAutoUpdateSubscriptions(true)
        checkSubscriptionWarnings()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PHONE_STATE_PERMISSION) return

        // Permission state changes should be reflected immediately instead of
        // waiting for the next throttled network-monitor tick.
        lastNetworkLabelAt = 0L
        updateNetworkLabel()
        if (grantResults.firstOrNull() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            android.util.Log.i("AstraVPN", "Дополнительные сведения об операторе недоступны без разрешения READ_PHONE_STATE")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (intent.action == ACTION_TILE_TOGGLE) {
            intent.action = null
            handleQuickTileToggle()
        }
    }

    private fun handleQuickTileToggle() {
        if (connected) {
            applyManualBankingOverride(connecting = false)
            stopTunnel()
            return
        }
        if (pendingConfig != null) {
            cancelPendingConnect()
            return
        }
        if (connecting) {
            Toast.makeText(this, "Подключение уже выполняется", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedProfile != null) {
            connectSelectedProfile()
        } else if (latencyTestInFlight) {
            tilePendingStart = true
            pendingFastestSelection = true
            selectingFastest = true
            Toast.makeText(this, "Выберем лучший сервер после проверки…", Toast.LENGTH_SHORT).show()
        } else {
            tilePendingStart = true
            selectFastestServer()
        }
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
        // The foreground latency service can outlive this Activity. Restore its
        // session token so broadcasts from the still-running test are not discarded.
        activeLatencySessionId = prefs.getString(KEY_LATENCY_SESSION_ID, null)
        latencyTestInFlight = activeLatencySessionId != null
        val pending = prefs.getString(KEY_LATENCY_PENDING_RESULTS, null)
        // A reusable cache is not a completion signal for a currently running test.
        // Only the one-shot pending snapshot may finish a restored session.
        if (latencyTestInFlight && pending == null) {
            renderProfiles()
            return
        }
        val cached = pending ?: prefs.getString(KEY_LATENCY_LAST_RESULTS, null)
        if (cached == null) {
            if (latencyTestInFlight) renderProfiles()
            return
        }
        val nowWallClockMs = System.currentTimeMillis()
        val cacheSavedAt = prefs.getLong(KEY_LATENCY_LAST_RESULTS_AT, 0L)
        val cacheAgeMs = (nowWallClockMs - cacheSavedAt).coerceAtLeast(0L)
        val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
        if (!LatencyCacheFreshnessPolicy.isFresh(cacheSavedAt, nowWallClockMs) ||
            cacheAgeMs >= nowElapsedMs
        ) {
            prefs.edit()
                .remove(KEY_LATENCY_PENDING_RESULTS)
                .remove(KEY_LATENCY_LAST_RESULTS)
                .remove(KEY_LATENCY_LAST_RESULTS_AT)
                .apply()
            safeProfiles().orEmpty().forEach { profile ->
                latencyMs.remove(profile)
                latencyMeasuredAt.remove(profile)
                pingInfo[profile] = "н/д"
            }
            if (latencyTestInFlight) renderProfiles()
            return
        }
        if (pending != null) {
            prefs.edit().remove(KEY_LATENCY_PENDING_RESULTS).apply()
        }
        // Preserve the original sample age after process recreation.
        applyLatencyResults(
            cached,
            finished = true,
            measuredAtElapsedMs = nowElapsedMs - cacheAgeMs
        )
    }

    private fun applyLatencyResults(
        payload: String,
        finished: Boolean,
        measuredAtElapsedMs: Long = android.os.SystemClock.elapsedRealtime()
    ) {
        // Validate the snapshot before changing session state. A malformed final
        // payload must not leave auto-selection or the quick tile waiting forever.
        val values = runCatching { JSONObject(payload) }.getOrNull()
        if (values == null) {
            if (finished) {
                getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_LATENCY_PENDING_RESULTS)
                    .apply()
                latencyTestInFlight = false
                pendingFastestSelection = false
                selectingFastest = false
                tilePendingStart = false
                urlTestProfileMap.values.forEach { profile ->
                    if (freshLatencyMs(profile) == null) pingInfo[profile] = "н/д"
                }
                Toast.makeText(this, "Получен некорректный результат проверки. Повтори Ping.", Toast.LENGTH_LONG).show()
                if (!isFinishing && !isDestroyed) renderProfiles()
            }
            return
        }
        if (finished) {
            getSharedPreferences(LATENCY_PREFS, MODE_PRIVATE).edit()
                .remove(KEY_LATENCY_PENDING_RESULTS)
                .remove(KEY_LATENCY_SESSION_ID)
                .apply()
            activeLatencySessionId = null
            latencyTestInFlight = false
        }
        // Render each successful measurement immediately instead of waiting for
        // the slowest server in the subscription to finish its URL-test. A final
        // cumulative broadcast can repeat values already shown by partial updates;
        // avoid rebuilding every server row when its visible state did not change.
        var latencyPresentationChanged = false
        urlTestProfileMap.forEach { (tag, profile) ->
            if (!values.has(tag)) return@forEach
            val ms = if (!values.isNull(tag)) values.optLong(tag, -1L) else -1L
            if (ms > 0L) {
                val previousMs = latencyMs[profile]
                val wasFresh = freshLatencyMs(profile) != null
                val nextInfo = "$ms мс · ${latencyQuality(ms)}"
                if (previousMs != ms || !wasFresh || pingInfo[profile] != nextInfo) {
                    latencyPresentationChanged = true
                }
                latencyMs[profile] = ms
                latencyMeasuredAt[profile] = measuredAtElapsedMs
                if (finished) latencyHistory.record(profile, ms)
                pingInfo[profile] = nextInfo
            } else if (finished) {
                // A failed URL-test means "no measurement", not proof that the server is dead.
                if (latencyMs.containsKey(profile) || latencyMeasuredAt.containsKey(profile) ||
                    pingInfo[profile] != "н/д"
                ) latencyPresentationChanged = true
                latencyMs.remove(profile)
                latencyMeasuredAt.remove(profile)
                pingInfo[profile] = "н/д"
            }
        }
        // URL-test broadcasts partial snapshots as individual nodes finish.
        // Do not pick the "fastest" server from an incomplete subset: a slower
        // node may still return a valid result before the final snapshot.
        if (pendingFastestSelection && finished) {
            pendingFastestSelection = false
            startThroughputBatchForFastest(urlTestProfileMap.values.toList())
        }
        // Coalesce bursts of partial latency results into one list rebuild.
        // This keeps ping badges responsive without repeatedly recreating every
        // server card while several probes finish close together.
        if (!isFinishing && !isDestroyed && (finished || latencyPresentationChanged)) {
            searchRenderHandler.removeCallbacks(searchRenderTask)
            searchRenderHandler.postDelayed(searchRenderTask, 90L)
        }
    }

    private fun restoreVpnState() {
        val saved = getSharedPreferences(AstraVpnService.STATE_PREFS, MODE_PRIVATE)
            .getString(AstraVpnService.PREF_STATE, AstraVpnService.STATE_DISCONNECTED)
        val statePrefs = getSharedPreferences(AstraVpnService.STATE_PREFS, MODE_PRIVATE)
        val stateAge = System.currentTimeMillis() - statePrefs.getLong(AstraVpnService.PREF_UPDATED_AT, 0L)
        val connectingIsRecent = saved == AstraVpnService.STATE_CONNECTING && stateAge in 0..30_000L
        val savedError = statePrefs.getString(AstraVpnService.PREF_ERROR, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val errorTimestamp = statePrefs.getLong(AstraVpnService.PREF_UPDATED_AT, 0L)
        val errorIsRecent = saved == AstraVpnService.STATE_ERROR &&
            savedError != null && stateAge in 0..120_000L
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val androidVpnActive = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        // Require both Android's VPN transport and Astra's own encrypted
        // active-profile marker; another VPN app may own the default route.
        val hasAstraActiveProfile = runCatching {
            !ProfileStore(this).activeTunnelProfile().isNullOrBlank()
        }.getOrDefault(false)
        connected = VpnUiRestorePolicy.isAstraTunnelConnected(
            savedState = saved,
            connectedState = AstraVpnService.STATE_CONNECTED,
            androidVpnTransportActive = androidVpnActive,
            activeProfileAvailable = hasAstraActiveProfile
        )
        if (connected && settingsPrefs.getLong(KEY_VPN_CONNECTED_SINCE, 0L) <= 0L) {
            settingsPrefs.edit().putLong(KEY_VPN_CONNECTED_SINCE, System.currentTimeMillis()).apply()
        } else if (!connected) {
            settingsPrefs.edit().remove(KEY_VPN_CONNECTED_SINCE).apply()
        }
        disconnecting = connected && settingsPrefs.getBoolean("vpn_disconnect_requested", false)
        // A persisted DISCONNECTED value may predate the user's latest stop
        // request. Only the live terminal service broadcast can acknowledge and
        // clear the marker; otherwise a stale snapshot could permit sticky recovery.
        when {
            connected && disconnecting -> {
                status.text = "Отключаемся…"
                status.setTextColor(AMBER)
            }
            connected -> {
                // Restored Android tunnel state is provisional until an HTTPS
                // health check confirms end-to-end internet through the VPN.
                tunnelInternetOk = null
                status.text = "Проверяем интернет через VPN…"
                status.setTextColor(AMBER)
            }
            connectingIsRecent -> {
                connected = false
                connecting = true
                status.text = "Подключаемся…"
                status.setTextColor(AMBER)
            }
            errorIsRecent -> {
                connected = false
                connecting = false
                tunnelInternetOk = false
                status.text = "Ошибка подключения"
                status.setTextColor(RED)
                // Preserve the last actionable service error if Android recreated
                // the Activity before the user had a chance to read it.
                val shownAt = settingsPrefs.getLong("shown_vpn_error_at", 0L)
                if (shownAt != errorTimestamp) {
                    settingsPrefs.edit().putLong("shown_vpn_error_at", errorTimestamp).apply()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Не удалось подключиться")
                        .setMessage(savedError)
                        .setPositiveButton("Понятно", null)
                        .show()
                }
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
        if (connected && !disconnecting) scheduleTunnelHealthCheck()
    }

    override fun onStop() {
        activityStarted = false
        // A health probe may finish after the Activity moves to the background.
        // It must not trigger UI failover or act on a route no longer monitored here.
        tunnelHealthCheckGeneration.advance()
        lastHealthCheckAt = 0L
        setHeroAnimationsAllowed(false)
        unregisterBatterySaverReceiver()
        unregisterPhysicalNetworkCallback()
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        searchRenderHandler.removeCallbacks(searchRenderTask)
        monitorHandler.removeCallbacks(monitorTick)
        monitorHandler.removeCallbacks(physicalNetworkChangeDebounce)
        // Keep the receiver registered while this Activity instance is alive.
        // A latency test can finish while the app is backgrounded; unregistering
        // here would drop its one-shot result and leave rows stuck on "Проверяем…".
        super.onStop()
    }

    private fun isBatterySaverActive(): Boolean =
        settingsPrefs.getBoolean("battery_saver_mode", false) ||
            getSystemService(android.os.PowerManager::class.java).isPowerSaveMode

    private fun registerBatterySaverReceiver() {
        if (batterySaverReceiverRegistered) return
        val filter = IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(batterySaverReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(batterySaverReceiver, filter)
            }
            batterySaverReceiverRegistered = true
        }.onFailure {
            android.util.Log.w("AstraVPN", "Не удалось подписаться на смену Battery Saver", it)
        }
    }

    private fun unregisterBatterySaverReceiver() {
        if (!batterySaverReceiverRegistered) return
        runCatching { unregisterReceiver(batterySaverReceiver) }
        batterySaverReceiverRegistered = false
    }

    /** Keep decorative motion out of the background and respect Android Battery Saver. */
    private fun setHeroAnimationsAllowed(allowed: Boolean) {
        if (::starButtonFrame.isInitialized) {
            (0 until starButtonFrame.childCount).asSequence()
                .mapNotNull { starButtonFrame.getChildAt(it) as? OrbitHeroView }
                .firstOrNull()?.setAnimationAllowed(allowed)
        }
        if (::constellationView.isInitialized) {
            constellationView.setActive(allowed && connectionVisualState == "connected")
        }
        if (!allowed) stopStarPulse()
        if (!allowed) {
            val decor = window.decorView as? ViewGroup
            if (decor != null) {
                for (index in decor.childCount - 1 downTo 0) {
                    if (decor.getChildAt(index) is NightShootingStarView) {
                        decor.removeViewAt(index)
                    }
                }
            }
        }
    }

    /** Restore the last known-good selection if an automatic failover attempt fails. */
    private fun rollbackPendingFailoverSelection() {
        val pending = pendingFailoverSelection ?: return
        // The failed target is explicit; activeProfile may still refer to the previous tunnel during config preparation.
        val failedProfile = pending.targetProfile
        val restored = FailoverSelectionPolicy.rollback(
            pending = pending,
            currentSelection = selectedProfile,
            failedProfile = failedProfile
        )
        selectedProfile = restored
        pendingFailoverSelection = null
        if (activeProfile == pending.targetProfile) activeProfile = null
        runCatching {
            profileStore.setRecoveryProfile(
                if (settingsPrefs.getBoolean("vpn_recovery_enabled", false)) restored else null
            )
        }
        persistSelectedProfileIfChanged()
        if (::selectedInfo.isInitialized) selectedInfo.text = restored?.let(::profileSummary) ?: "Сервер не выбран"
    }

    /** Schedule only explicitly retryable transient failures; never retry arbitrary config errors. */
    private fun scheduleTransientConnectRetry(
        retryable: Boolean,
        isAutomaticFailover: Boolean
    ): Boolean {
        val retryProfile = selectedProfile
        val userRequestedDisconnect = settingsPrefs.getBoolean("vpn_disconnect_requested", false)
        if (isFinishing || isDestroyed || disconnecting || connected ||
            !TransientConnectRetryPolicy.shouldSchedule(
                retryable = retryable,
                isAutomaticFailover = isAutomaticFailover,
                userRequestedDisconnect = userRequestedDisconnect,
                hasSelectedProfile = !retryProfile.isNullOrBlank(),
                attempt = connectRetryAttempt
            )
        ) return false
        val delay = TransientConnectRetryPolicy.delayForAttempt(connectRetryAttempt) ?: return false
        connectRetryAttempt++
        cancelConnectRetry(resetAttempts = false)
        val generation = connectRetryGeneration
        status.text = "Повторная попытка подключения через ${delay / 1_000} сек…"
        status.setTextColor(AMBER)
        connectRetryTask = Runnable {
            // Validate cancellation and connection state before deferring. Otherwise a
            // stale retry could keep polling forever while the Activity is backgrounded.
            val stillPending = TransientConnectRetryPolicy.shouldRemainPending(
                generationCurrent = generation == connectRetryGeneration,
                selectedProfileUnchanged = selectedProfile == retryProfile,
                activityActive = !isFinishing && !isDestroyed,
                userRequestedDisconnect = settingsPrefs.getBoolean("vpn_disconnect_requested", false),
                connecting = connecting,
                connected = connected,
                disconnecting = disconnecting
            )
            if (!stillPending) {
                connectRetryTask = null
                return@Runnable
            }
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val hasValidatedInternet = hasValidatedPhysicalNetwork(connectivity)
            if (TransientConnectRetryPolicy.shouldDeferUntilConditions(
                    activityStarted = activityStarted,
                    activityFinishing = isFinishing,
                    activityDestroyed = isDestroyed,
                    hasValidatedPhysicalInternet = hasValidatedInternet
                )
            ) {
                // Keep the same bounded retry pending while backgrounded or offline.
                connectRetryTask?.let { connectRetryHandler.postDelayed(it, 1_000L) }
                return@Runnable
            }
            val canExecute = TransientConnectRetryPolicy.shouldExecute(
                generationCurrent = generation == connectRetryGeneration,
                selectedProfileUnchanged = selectedProfile == retryProfile,
                activityActive = activityStarted && !isFinishing && !isDestroyed,
                userRequestedDisconnect = settingsPrefs.getBoolean("vpn_disconnect_requested", false),
                connecting = connecting,
                connected = connected,
                disconnecting = disconnecting,
                hasValidatedPhysicalInternet = hasValidatedInternet
            )
            if (!canExecute) {
                connectRetryTask = null
                return@Runnable
            }
            connectRetryTask = null
            connectSelectedProfile()
        }.also { connectRetryHandler.postDelayed(it, delay) }
        updateProtectionLabels()
        return true
    }

    private fun cancelConnectRetry(resetAttempts: Boolean) {
        connectRetryGeneration = if (connectRetryGeneration == Long.MAX_VALUE) 0L else connectRetryGeneration + 1L
        connectRetryTask?.let(connectRetryHandler::removeCallbacks)
        connectRetryTask = null
        if (resetAttempts) connectRetryAttempt = 0
    }
    private fun startTunnel(config: String) {
        // A new explicit connect supersedes the durable stop marker. Flush the
        // removal before dispatching START, otherwise a process death immediately
        // after the tap could make the next sticky restart incorrectly stay offline.
        val stopMarkerCleared = settingsPrefs.edit()
            .remove("vpn_disconnect_requested")
            .commit()
        if (!stopMarkerCleared) {
            // The start was rejected before dispatch. Roll back any provisional
            // failover choice and discard the captured config/profile so a later
            // consent result or retry cannot accidentally reuse this request.
            rollbackPendingFailoverSelection()
            pendingConfig = null
            pendingProfile = null
            connecting = false
            status.text = "Не удалось сохранить подключение"
            status.setTextColor(RED)
            Toast.makeText(this, "Не удалось сохранить новое подключение. Попробуй ещё раз.", Toast.LENGTH_LONG).show()
            updateProtectionLabels()
            renderProfiles()
            return
        }
        cancelConnectRetry(resetAttempts = false)
        waitingForUnderlyingNetwork = false
        // Invalidate an in-flight health probe before a profile reload, even if Android reuses
        // the same VPN Network object for the replacement sing-box configuration.
        tunnelHealthCheckGeneration.advance()
        activeProfile = pendingProfile ?: selectedProfile
        val intent = Intent(this, AstraVpnService::class.java)
            .setAction(AstraVpnService.ACTION_START)
            .putExtra(AstraVpnService.EXTRA_CONFIG, config)
            .putExtra(AstraVpnService.EXTRA_PROFILE_LINK, activeProfile)
            .putExtra(AstraVpnService.EXTRA_PROFILE_NAME, activeProfile?.let { profileName(it) } ?: "Сервер не выбран")
            .putExtra(AstraVpnService.EXTRA_PROTOCOL, activeProfile?.let { protocolName(it) } ?: "—")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            pendingConfig = null
            pendingProfile = null
            connecting = true
            status.text = "Подключаемся…"
            status.setTextColor(AMBER)
        } catch (error: Exception) {
            rollbackPendingFailoverSelection()
            // Do not leave a stale configuration behind after a failed service start.
            pendingConfig = null
            pendingProfile = null
            connecting = false
            connected = false
            tunnelInternetOk = false
            status.text = "Не удалось запустить VPN"
            status.setTextColor(RED)
            updateProtectionLabels()
            Toast.makeText(this, "Не удалось запустить VPN. Проверь выбранный сервер и разрешение на VPN.", Toast.LENGTH_LONG).show()
        }
        updateProtectionLabels()
        renderProfiles()
    }

    /**
     * Cancels a start that is still waiting for Android's VPN consent result.
     * Clearing the captured config makes a late RESULT_OK harmless.
     */
    private fun cancelPendingConnect() {
        if (pendingConfig == null) return
        // This path is also used when the user cancels the Android consent
        // flow or changes/removes the provisional target before it can start.
        rollbackPendingFailoverSelection()
        pendingConfig = null
        pendingProfile = null
        cancelConnectRetry(resetAttempts = true)
        connecting = false
        disconnecting = false
        // Cancelling a provisional start may leave the previous tunnel alive,
        // but it does not make that tunnel healthy by itself. Preserve only a
        // previously successful end-to-end check; otherwise keep the UI provisional.
        when {
            !connected -> {
                status.text = "Отключено"
                status.setTextColor(MUTED)
            }
            tunnelInternetOk == true -> {
                status.text = "Подключено"
                status.setTextColor(GREEN)
            }
            tunnelInternetOk == false -> {
                status.text = "VPN без интернета"
                status.setTextColor(RED)
            }
            else -> {
                status.text = "Проверяем интернет через VPN…"
                status.setTextColor(AMBER)
                scheduleTunnelHealthCheck()
            }
        }
        updateProtectionLabels()
        renderProfiles()
        Toast.makeText(this, "Подключение отменено", Toast.LENGTH_SHORT).show()
    }

    /**
     * A direct user action always wins over a pending automatic banking resume.
     * State is committed before the encrypted profile snapshot is cleared, so
     * a crash between the writes still leaves the fail-closed manual flag.
     */
    private fun applyManualBankingOverride(connecting: Boolean) {
        runCatching {
            val stateStore = BankingAutomationStateStore(this)
            val state = stateStore.read()
            stateStore.write(
                if (connecting) state.onManualConnect() else state.onManualDisconnect()
            )
            ProfileStore(this).clearBankPauseSnapshot()
        }.onFailure {
            android.util.Log.e("AstraVPN", "Не удалось сохранить ручное переопределение банковской автопаузы", it)
        }
    }

    /** A deleted active profile must never be resurrected by an old bank snapshot. */
    private fun cancelBankingResumeForRemovedProfile() {
        runCatching {
            val stateStore = BankingAutomationStateStore(this)
            stateStore.write(stateStore.read().cancelAutomaticResume())
            ProfileStore(this).clearBankPauseSnapshot()
        }.onFailure {
            android.util.Log.e("AstraVPN", "Не удалось отменить восстановление удалённого профиля", it)
        }
    }

    private fun stopTunnel() {
        cancelConnectRetry(resetAttempts = true)
        // Ignore repeated taps from the main button or Quick Settings tile while
        // the first stop request is still waiting for the service acknowledgement.
        if (disconnecting) return
        val intent = Intent(this, AstraVpnService::class.java).setAction(AstraVpnService.ACTION_STOP)
        try {
            disconnecting = true
            // This marker gates START_STICKY recovery. Flush it to disk before
            // dispatching STOP so a process death cannot resurrect the tunnel
            // from an older preference snapshot.
            val stopIntentPersisted = settingsPrefs.edit()
                .putBoolean("vpn_disconnect_requested", true)
                .commit()
            if (!stopIntentPersisted) {
                disconnecting = false
                status.text = "Не удалось сохранить отключение VPN"
                status.setTextColor(RED)
                Toast.makeText(this, "Не удалось надёжно сохранить команду отключения. Повтори попытку.", Toast.LENGTH_LONG).show()
                renderProfiles()
                return
            }
            startService(intent)
            // Keep the last confirmed tunnel state until the service reports that
            // it has actually stopped. Do not briefly show "Подключиться" while
            // Android is still tearing down the active VPN.
            connectButton.isEnabled = false
            connectActionLabel.text = "Отключаем…"
            status.text = "Отключаемся…"
            status.setTextColor(AMBER)
            // Apply the complete card/button/indicator state immediately; waiting
            // for the service broadcast would leave the hero card looking connected.
            updateConnectionCardAppearance("disconnecting")
        } catch (error: Exception) {
            disconnecting = false
            settingsPrefs.edit().remove("vpn_disconnect_requested").apply()
            // Keep the UI honest if Android refuses the stop command.
            status.text = "Не удалось отключить VPN"
            status.setTextColor(RED)
            Toast.makeText(this, "Android не позволил остановить VPN. Попробуй ещё раз.", Toast.LENGTH_LONG).show()
            renderProfiles()
        }
    }

    /** True only when Android has a validated, unsuspended physical Internet path. */
    private fun hasValidatedPhysicalNetwork(connectivity: ConnectivityManager): Boolean {
        fun isUsableUnderlay(capabilities: NetworkCapabilities?): Boolean {
            if (capabilities == null) return false
            val notSuspended = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            return NetworkRecoveryPolicy.isValidatedPhysicalUnderlay(
                isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isNotSuspended = notSuspended
            )
        }

        // Prefer the exact underlay selected by AstraPlatform for sing-box.
        // A secondary connected network must not make a broken default route
        // look healthy and permit false tunnel-failure accounting.
        val selectedUnderlay = AstraPlatform.currentPhysicalNetwork
        if (selectedUnderlay != null) {
            return isUsableUnderlay(
                runCatching { connectivity.getNetworkCapabilities(selectedUnderlay) }.getOrNull()
            )
        }

        // During initial startup the platform callback may not have selected
        // an underlay yet; allow discovery until that authoritative handle exists.
        return connectivity.allNetworks.any { network ->
            isUsableUnderlay(runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull())
        }
    }

    private fun underlyingNetworkCapabilities(): NetworkCapabilities? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val selectedUnderlay = AstraPlatform.currentPhysicalNetwork
        val selectedCapabilities = selectedUnderlay?.let {
            runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull()
        }
        if (selectedCapabilities != null &&
            !selectedCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) return selectedCapabilities

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
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastNetworkLabelAt != 0L && now - lastNetworkLabelAt < 5_000L) return
        lastNetworkLabelAt = now
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

    private fun updateDailyTraffic(rx: Long, tx: Long) {
        val calendar = java.util.Calendar.getInstance()
        val dayKey = calendar.get(java.util.Calendar.YEAR) * 10000 +
            (calendar.get(java.util.Calendar.MONTH) + 1) * 100 +
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val totals = dailyTrafficAccumulator.update(dayKey, rx, tx)
        dailyTrafficHistory.record(dayKey, totals.downloadedBytes, totals.uploadedBytes)
        val prefs = getSharedPreferences("astra_daily_traffic", MODE_PRIVATE)
        prefs.edit()
            .putInt("day", dailyTrafficAccumulator.currentDayKey())
            .putLong("rx", dailyTrafficAccumulator.lastRxBytes())
            .putLong("tx", dailyTrafficAccumulator.lastTxBytes())
            .putLong("downloaded", totals.downloadedBytes)
            .putLong("uploaded", totals.uploadedBytes)
            .putString("week", dailyTrafficHistory.encode())
            .apply()
        monitorDailyTraffic.text = "Сегодня*  ↓ " +
            DailyTrafficAccumulator.formatBytes(totals.downloadedBytes) +
            "  /  ↑ " + DailyTrafficAccumulator.formatBytes(totals.uploadedBytes)
        val weekly = dailyTrafficHistory.total()
        monitorWeeklyTraffic.text = "За 7 дней*  ↓ " +
            DailyTrafficAccumulator.formatBytes(weekly.downloadedBytes) +
            "  /  ↑ " + DailyTrafficAccumulator.formatBytes(weekly.uploadedBytes) +
            "\n" + dailyTrafficHistory.entries().joinToString("  ·  ") { entry ->
                val date = String.format(java.util.Locale.US, "%02d.%02d", entry.dayKey % 100, (entry.dayKey / 100) % 100)
                "$date ↓ ${DailyTrafficAccumulator.formatBytes(entry.totals.downloadedBytes)} ↑ ${DailyTrafficAccumulator.formatBytes(entry.totals.uploadedBytes)}"
            }
    }

    private fun maybeAutoSpeedFailover() {
        // Speed-based switching shares the same global retry budget as tunnel-health
        // failover. This prevents two independent detectors from creating a switch loop.
        if (!AutoSpeedFailoverEligibilityPolicy.shouldEvaluate(
                connected = connected,
                connecting = connecting,
                tunnelInternetOk = tunnelInternetOk,
                selectingFastest = selectingFastest,
                hasPendingFailover = pendingFailoverSelection != null,
                enabled = settingsPrefs.getBoolean("auto_speed_failover", false)
            )
        ) return

        val now = android.os.SystemClock.elapsedRealtime()
        if (!autoFailoverBudget.canAttempt(now)) {
            status.text = "Автопереключения временно приостановлены"
            status.setTextColor(AMBER)
            updateProtectionLabels()
            return
        }

        val current = activeProfile ?: selectedProfile
        val profiles = safeProfiles() ?: return
        val candidates = profiles.filterNot { it == current }
        val freshLatencies = LatencyFreshnessPolicy.freshLatencies(
            latencyMs, latencyMeasuredAt, now, candidates
        )
        val measuredCandidates = candidates.filter { freshLatencies.containsKey(it) }
        val adjusted = TimeOfDayServerOptimizer.adjustedLatencies(
            measuredCandidates, freshLatencies, { profile -> latencyHistory.samples(profile) }
        )
        val alternate = BestServerSelector.select(
            profiles = measuredCandidates,
            latencyMs = adjusted,
            recentFailures = recentServerFailureCounts(measuredCandidates),
            latencyStabilityPenaltyMs = latencyStabilityPenalties(measuredCandidates),
            throughputSamples = throughputHistory.getFresh(measuredCandidates)
        )
        if (alternate == null) {
            Toast.makeText(
                this,
                "Скорость заметно снизилась, но нет свежих измерений резервных серверов. Проверь орбиты.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Reserve the shared budget before mutating selection or starting a connection.
        if (!autoFailoverBudget.recordAttempt(now)) return
        lastAutoFailoverAt = now
        pendingFailoverSelection = FailoverSelectionTransaction(
            previousProfile = current,
            targetProfile = alternate
        )
        selectedProfile = alternate
        selectedInfo.text = profileSummary(alternate)
        status.text = "Скорость снизилась · меняем орбиту…"
        status.setTextColor(AMBER)
        updateConnectionCardAppearance("switching")
        Toast.makeText(
            this,
            "Скорость упала более чем на 50%. Переключаемся на " + profileName(alternate),
            Toast.LENGTH_LONG
        ).show()
        connectSelectedProfile()
    }

    private fun updateTrafficMonitor() {
        // The device-wide graph remains explicitly separate from VPN-only measurements.
        updateVpnTrafficMonitor()

        val now = System.currentTimeMillis()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            monitorDownload.text = "↓ недоступно"
            monitorUpload.text = "↑ недоступно"
            return
        }
        updateDailyTraffic(rx, tx)
        if (lastTrafficAt > 0L && lastRxBytes >= 0L && lastTxBytes >= 0L) {
            val elapsed = ((now - lastTrafficAt).coerceAtLeast(1L)) / 1000.0
            val down = ((rx - lastRxBytes).coerceAtLeast(0L) * 8.0 / elapsed / 1_000_000.0).toFloat()
            val up = ((tx - lastTxBytes).coerceAtLeast(0L) * 8.0 / elapsed / 1_000_000.0).toFloat()
            monitorDownload.text = "↓ " + String.format(java.util.Locale.US, "%.2f Мбит/с", down)
            monitorUpload.text = "↑ " + String.format(java.util.Locale.US, "%.2f Мбит/с", up)
            speedGraph.addSample(down, up)
            monitorSpeedDiagnostics.text = LiveSpeedDiagnostics.summary(
                down.toDouble(), up.toDouble(), connected, 0L
            )
            val stats = speedGraph.currentStats()
            monitorSpeedStats.text = String.format(
                java.util.Locale.US,
                "Средняя: ↓ %.2f / ↑ %.2f Мбит/с   ·   Пик: ↓ %.2f / ↑ %.2f",
                stats.averageDownloadMbps, stats.averageUploadMbps,
                stats.peakDownloadMbps, stats.peakUploadMbps
            )
        }
        lastRxBytes = rx
        lastTxBytes = tx
        lastTrafficAt = now
    }

    /**
     * Reads the proxy outbound counters emitted by libbox. Unlike TrafficStats,
     * this sample excludes unrelated apps and direct outbound traffic. The core
     * currently exposes connection-event deltas, so coverage can be incomplete.
     */
    private fun updateOrbitMetrics() {
        if (!::orbitMetricsLabel.isInitialized) return
        updateOrbitIdentity()
        if (!connected) {
            orbitMetricsLabel.text = "00:00:00"
            orbitSpeedLabel.text = ""
            orbitSpeedLabel.visibility = View.GONE
            orbitMetricsLabel.setTextColor(0xFF9AA6BF.toInt())
            orbitSpeedLabel.setTextColor(0xFF9AA6BF.toInt())
            return
        }
        val startedAt = settingsPrefs.getLong(KEY_VPN_CONNECTED_SINCE, 0L)
        val elapsedSeconds = if (startedAt > 0L) {
            ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / 1_000L)
        } else 0L
        val hours = elapsedSeconds / 3_600L
        val minutes = (elapsedSeconds % 3_600L) / 60L
        val seconds = elapsedSeconds % 60L
        val rate = vpnTrafficRateTracker.current(android.os.SystemClock.elapsedRealtime())
        orbitMetricsLabel.text = String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        orbitSpeedLabel.text = rate?.let {
            "↓ ${String.format(java.util.Locale.US, "%.1f", it.downloadMbps)} Мбит/с     ↑ ${String.format(java.util.Locale.US, "%.1f", it.uploadMbps)} Мбит/с"
        }.orEmpty()
        orbitSpeedLabel.visibility = if (rate == null) View.GONE else View.VISIBLE
        val fresh = rate != null && rate.sampleAgeMs <= 3_000L
        orbitMetricsLabel.setTextColor(0xFFFFD98A.toInt())
        orbitSpeedLabel.setTextColor(if (fresh) 0xFFE8C77C.toInt() else 0xFF9AA6BF.toInt())
    }

    private fun updateVpnTrafficMonitor() {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        updateOrbitMetrics()
        if (!connected) {
            vpnTrafficRateTracker.reset()
            lastVpnTrafficSnapshotAt = 0L
            speedDropPolicy.update(0.0, nowElapsed, vpnConnected = false, trafficIsVpnScoped = false)
            monitorVpnSpeed.text = "VPN ↓ — / ↑ — Мбит/с · не подключено"
            return
        }

        val prefs = getSharedPreferences(AstraVpnService.TRAFFIC_PREFS, MODE_PRIVATE)
        val updatedAt = prefs.getLong(AstraVpnService.TRAFFIC_UPDATED_AT, 0L)
        if (updatedAt > 0L && updatedAt != lastVpnTrafficSnapshotAt) {
            lastVpnTrafficSnapshotAt = updatedAt
            val proxyTotals = runCatching {
                val raw = prefs.getString(AstraVpnService.TRAFFIC_JSON, null) ?: return@runCatching null
                JSONObject(raw).optJSONObject("proxy")
            }.getOrNull()
            if (proxyTotals == null) {
                vpnTrafficRateTracker.reset()
            } else {
                val uploadBytes = proxyTotals.optLong("uploadBytes", -1L)
                val downloadBytes = proxyTotals.optLong("downloadBytes", -1L)
                if (uploadBytes >= 0L && downloadBytes >= 0L) {
                    vpnTrafficRateTracker.update(downloadBytes, uploadBytes, nowElapsed)
                } else {
                    vpnTrafficRateTracker.reset()
                }
            }
        }

        val rate = vpnTrafficRateTracker.current(nowElapsed)
        if (rate == null) {
            monitorVpnSpeed.text = "VPN ↓ — / ↑ — Мбит/с · ожидаем события sing-box"
            speedDropPolicy.update(0.0, nowElapsed, vpnConnected = true, trafficIsVpnScoped = false)
            return
        }

        monitorVpnSpeed.text = String.format(
            java.util.Locale.US,
            "↓ %.2f / ↑ %.2f Мбит/с  ·  %s",
            rate.downloadMbps, rate.uploadMbps,
            if (rate.sampleAgeMs > 3_000L) "нет свежих событий" else "оценка по событиям sing-box"
        )
        // This rate is derived from sing-box proxy counters, so it is VPN-scoped.
        // Ignore stale samples: they cannot prove a sustained drop in the active tunnel.
        val hasFreshVpnSample = rate.sampleAgeMs <= 3_000L
        if (speedDropPolicy.update(
                rate.downloadMbps,
                nowElapsed,
                vpnConnected = true,
                trafficIsVpnScoped = hasFreshVpnSample
            )) {
            maybeAutoSpeedFailover()
        }
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
        val hasValidatedUnderlay = hasValidatedPhysicalNetwork(connectivity)
        if (!NetworkRecoveryPolicy.shouldProbeTunnel(hasValidatedUnderlay)) {
            // No validated physical Internet: this is an access-network outage,
            // not evidence that the selected VPN server is broken.
            waitingForUnderlyingNetwork = true
            tunnelInternetOk = null
            status.text = "Ожидаем восстановления интернета…"
            status.setTextColor(AMBER)
            updateProtectionLabels()
            return
        }
        waitingForUnderlyingNetwork = false
        val vpnNetwork = connectivity.activeNetwork
        val vpnCapabilities = vpnNetwork?.let(connectivity::getNetworkCapabilities)
        if (vpnNetwork == null || vpnCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
            // During Android's VPN handover activeNetwork can briefly be absent
            // or still point at the physical route. This is not a completed
            // end-to-end failure and must not paint the protection state red.
            waitingForVpnRoute = true
            tunnelInternetOk = null
            lastHealthCheckAt = 0L
            status.text = "Восстанавливаем VPN-маршрут…"
            status.setTextColor(AMBER)
            updateProtectionLabels()
            return
        }
        waitingForVpnRoute = false
        // Use monotonic time so wall-clock changes cannot stall or spam health probes.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHealthCheckAt < 12_000L || !healthCheckInFlight.compareAndSet(false, true)) return
        lastHealthCheckAt = now
        val probeGeneration = tunnelHealthCheckGeneration.current()
        try {
            healthCheckExecutor.execute {
                val ok = try {
                    TunnelHealthChecker.check(vpnNetwork)
                } catch (_: Exception) {
                    false
                } finally {
                    // Always release the gate, even if a platform/network call fails unexpectedly.
                    healthCheckInFlight.set(false)
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && connected &&
                        tunnelHealthCheckGeneration.isCurrent(probeGeneration)
                    ) {
                        // Discard a probe result if Android replaced the VPN Network
                        // while the request was in flight; it says nothing about the new tunnel.
                        val currentNetwork = connectivity.activeNetwork
                        val currentCapabilities = currentNetwork?.let(connectivity::getNetworkCapabilities)
                        if (currentNetwork != vpnNetwork ||
                            currentCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true
                        ) {
                            lastHealthCheckAt = 0L
                            return@runOnUiThread
                        }
                        // The physical underlay may disappear while the HTTPS probe is
                        // in flight. Its timeout is then a network-handover event, not
                        // evidence that the VPN server failed; never count it toward failover.
                        if (!NetworkRecoveryPolicy.shouldProbeTunnel(
                                hasValidatedPhysicalNetwork(connectivity)
                            )
                        ) {
                            waitingForUnderlyingNetwork = true
                            tunnelInternetOk = null
                            lastHealthCheckAt = 0L
                            status.text = "Ожидаем восстановления интернета…"
                            status.setTextColor(AMBER)
                            updateProtectionLabels()
                            return@runOnUiThread
                        }
                        waitingForUnderlyingNetwork = false
                        tunnelInternetOk = ok
                        if (ok) {
                            status.text = "Подключено"
                            status.setTextColor(GREEN)
                            // A replacement is not committed merely because Android
                            // created its TUN. Keep the previous known-good profile until
                            // end-to-end traffic through this exact profile is verified.
                            if (pendingFailoverSelection?.let {
                                    FailoverSelectionPolicy.isCommitted(it, activeProfile)
                                } == true
                            ) {
                                pendingFailoverSelection = null
                            }
                            consecutiveHealthFailures = 0
                        } else {
                            status.text = "VPN без интернета"
                            status.setTextColor(RED)
                            consecutiveHealthFailures = AutoFailoverPolicy.nextFailureCount(consecutiveHealthFailures)
                            maybeAutoFailover()
                        }
                        updateProtectionLabels()
                        renderProfiles()
                    } else if (TunnelHealthProbeResumePolicy.shouldReschedule(
                            activityActive = !isFinishing && !isDestroyed,
                            connected = connected,
                            generationCurrent = tunnelHealthCheckGeneration.isCurrent(probeGeneration)
                        )
                    ) {
                        // A probe invalidated while the Activity was backgrounded
                        // must be replaced promptly when its worker releases the gate.
                        lastHealthCheckAt = 0L
                        scheduleTunnelHealthCheck()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Activity teardown can race with a scheduled probe after executor shutdown.
            healthCheckInFlight.set(false)
        }
    }

    /**
     * Switch only after repeated failed end-to-end checks. A single blocked
     * probe or transient network handover must not cause a server change.
     */
    private fun maybeAutoFailover() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val hasValidatedUnderlay = hasValidatedPhysicalNetwork(connectivity)
        if (!AutoFailoverEligibilityPolicy.shouldSwitch(
                connected = connected,
                connecting = connecting,
                disconnecting = disconnecting,
                selectingFastest = selectingFastest,
                hasPendingFailover = pendingFailoverSelection != null,
                tunnelInternetOk = tunnelInternetOk,
                hasValidatedPhysicalInternet = hasValidatedUnderlay
            )
        ) return
        // Use a monotonic clock: wall-clock corrections must not shorten or extend the cooldown.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!AutoFailoverPolicy.shouldSwitch(consecutiveHealthFailures, lastAutoFailoverAt, now)) return
        if (!autoFailoverBudget.canAttempt(now)) {
            status.text = "Автопереключения временно приостановлены"
            status.setTextColor(AMBER)
            updateProtectionLabels()
            return
        }
        val profiles = safeProfiles() ?: return
        val current = activeProfile ?: selectedProfile
        // A failed end-to-end health check invalidates the cached ping result
        // for this node; otherwise a later failover could bounce straight back.
        if (current != null) {
            latencyMs.remove(current)
            latencyMeasuredAt.remove(current)
            pingInfo[current] = "Не отвечает · проверка соединения не пройдена"
        }
        // Keep every other profile until freshness is evaluated. Filtering by
        // protocol before checking measured candidates would discard same-protocol
        // fallbacks even when every alternative-transport Ping is stale or missing.
        val eligibleProfiles = profiles.filterNot { it == current }
        val freshLatencies = LatencyFreshnessPolicy.freshLatencies(
            latencyMs, latencyMeasuredAt, now, eligibleProfiles
        )
        // Transport fallback is a real priority tier, not merely an ordering hint:
        // choose a fresh, measured alternative protocol first. If none has a fresh
        // result, retain the measured same-protocol candidates as a safe fallback.
        val measuredFailoverCandidates = AutoProtocolFallbackPolicy.candidatesWithFreshMeasurements(
            profiles = eligibleProfiles,
            currentProfile = current,
            measuredProfiles = freshLatencies.keys
        )
        // Record the node that just failed its end-to-end health checks. A
        // short quarantine prevents the next failover from immediately cycling
        // back to it, while preserving a last-resort path if all nodes failed.
        if (current != null) {
            runCatching {
                connectionHistory.record("error", profileName(current), protocolName(current))
            }
        }
        val recentHistory = connectionHistory.getEntries()
        val failoverCandidates = ServerFailureScorer.excludeRecentlyFailedProfiles(
            profiles = measuredFailoverCandidates,
            entries = recentHistory,
            now = System.currentTimeMillis(),
            nameOf = ::profileName,
            protocolOf = ::protocolName
        )
        val timeAdjustedLatencies = TimeOfDayServerOptimizer.adjustedLatencies(
            failoverCandidates, freshLatencies, { profile -> latencyHistory.samples(profile) }
        )
        val alternate = BestServerSelector.select(
            profiles = failoverCandidates,
            latencyMs = timeAdjustedLatencies,
            recentFailures = recentServerFailureCounts(failoverCandidates),
            latencyStabilityPenaltyMs = latencyStabilityPenalties(failoverCandidates),
            throughputSamples = throughputHistory.getFresh(failoverCandidates)
        ) ?: run {
            renderProfiles()
            return
        }
        if (!autoFailoverBudget.recordAttempt(now)) return
        lastAutoFailoverAt = now
        consecutiveHealthFailures = 0
        // If this is the second/third hop of one recovery chain, preserve
        // the original known-good profile rather than treating an unverified
        // intermediate tunnel as the rollback destination.
        pendingFailoverSelection = FailoverSelectionPolicy.nextTransaction(
            existing = pendingFailoverSelection,
            currentProfile = current,
            targetProfile = alternate
        )
        selectedProfile = alternate
        selectedInfo.text = profileSummary(alternate)
        status.text = "Переключаем сервер…"
        status.setTextColor(AMBER)
        updateConnectionCardAppearance("switching")
        Toast.makeText(this, "Связь нестабильна. Пробуем другой протокол или сервер…", Toast.LENGTH_LONG).show()
        connectSelectedProfile()
    }

    private fun updateProtectionLabels() {
        val label = when {
            connecting -> "◌ Проверяем VPN-подключение…" to AMBER
            !connected -> "○ Защита выключена" to MUTED
            waitingForUnderlyingNetwork -> "◌ Ожидаем восстановления Wi-Fi или мобильного интернета…" to AMBER
            waitingForVpnRoute -> "◌ Ожидаем восстановления VPN-маршрута…" to AMBER
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
        val rows = listOf(
            "ЗАЩИТА" to monitorProtection.text.toString(),
            "VPN-СЕРВЕР" to monitorVpnCountry.text.toString(),
            "ТИП СЕТИ" to monitorNetwork.text.toString(),
            "ОПЕРАТОР" to monitorOperator.text.toString(),
            "ЗАГРУЗКА" to monitorDownload.text.toString(),
            "ОТДАЧА" to monitorUpload.text.toString(),
            "МЕСТОПОЛОЖЕНИЕ" to monitorLocation.text.toString()
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = rounded(BG, 22)
        }
        content.addView(TextView(this).apply {
            text = "СОСТОЯНИЕ СЕТИ В РЕАЛЬНОМ ВРЕМЕНИ"
            textSize = 10f
            letterSpacing = 0.1f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF9EAEFF.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        rows.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(0xFF151F32.toInt(), 14)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 9f
                letterSpacing = 0.12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MUTED)
            })
            row.addView(TextView(this).apply {
                text = value.ifBlank { "—" }
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                setPadding(0, dp(3), 0, 0)
            })
            content.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) })
        }
        val outboundContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF151F32.toInt(), 14)
        }
        outboundTrafficRefresh = { renderOutboundTraffic(outboundContainer) }
        outboundTrafficRefresh?.invoke()
        content.addView(outboundContainer, matchWidth().apply { bottomMargin = dp(8) })
        content.addView(TextView(this).apply {
            text = if (hasAppUsageAccess()) {
                "Статистика приложений: доступ разрешён"
            } else {
                "Статистика приложений: нужен дополнительный доступ Android"
            }
            textSize = 12f
            setTextColor(if (hasAppUsageAccess()) GREEN else AMBER)
            setPadding(dp(12), dp(10), dp(12), dp(8))
        })
        content.addView(TextView(this).apply {
            text = "Открыть доступ к статистике приложений"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFF172238.toInt(), 13)
            setOnClickListener {
                val usageIntent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                runCatching { startActivity(usageIntent) }.onFailure {
                    Toast.makeText(this@MainActivity, "Не удалось открыть настройки доступа", Toast.LENGTH_SHORT).show()
                }
            }
        })
        val appTrafficContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xFF111B2D.toInt(), 16)
        }
        content.addView(appTrafficContainer, matchWidth().apply { topMargin = dp(8) })
        content.addView(TextView(this).apply {
            text = "Обновить статистику"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFB9C8FF.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { loadAppTrafficStats(appTrafficContainer) }
        })
        if (hasAppUsageAccess()) loadAppTrafficStats(appTrafficContainer)
        else appTrafficContainer.addView(TextView(this).apply {
            text = "Топ приложений по трафику появится здесь после предоставления доступа."
            textSize = 12f
            setTextColor(0xFFD8E0F2.toInt())
        })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Мониторинг сети")
            .setView(scroll)
            .setPositiveButton("Готово", null)
            .show()
        dialog.setOnDismissListener { outboundTrafficRefresh = null }
    }

    /** Refreshes the partial libbox outbound snapshot while the dialog is open. */
    private fun renderOutboundTraffic(container: LinearLayout) {
        if (isFinishing || isDestroyed) return
        container.removeAllViews()
        val prefs = getSharedPreferences(AstraVpnService.TRAFFIC_PREFS, MODE_PRIVATE)
        val raw = prefs.getString(AstraVpnService.TRAFFIC_JSON, null)
        val updatedAt = prefs.getLong(AstraVpnService.TRAFFIC_UPDATED_AT, 0L)
        val totals = runCatching {
            val json = raw?.let(::JSONObject) ?: JSONObject()
            json.keys().asSequence().mapNotNull { tag ->
                val value = json.optJSONObject(tag) ?: return@mapNotNull null
                val upload = value.optLong("uploadBytes", -1L)
                val download = value.optLong("downloadBytes", -1L)
                if (upload < 0L || download < 0L) null else Triple(tag, upload, download)
            }.toList()
        }.getOrDefault(emptyList())
        if (totals.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Пока нет данных libbox. Подключись к VPN и передай трафик."
                textSize = 12f
                setTextColor(MUTED)
            })
        } else {
            totals.sortedBy { it.first }.forEach { (tag, upload, download) ->
                container.addView(TextView(this).apply {
                    text = "$tag · ↓ ${formatTraffic(download)} / ↑ ${formatTraffic(upload)}"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setPadding(0, 0, 0, dp(5))
                })
            }
            container.addView(TextView(this).apply {
                text = "Сумма полученных дельт: ↓ ${formatTraffic(totals.sumOf { it.third })} / ↑ ${formatTraffic(totals.sumOf { it.second })}"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFB9C8FF.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
        val age = if (updatedAt > 0L) {
            val seconds = ((System.currentTimeMillis() - updatedAt).coerceAtLeast(0L) / 1000L)
            "Обновлено: ${seconds} сек. назад"
        } else "Время обновления неизвестно"
        container.addView(TextView(this).apply {
            text = "$age. Это сумма connection-event дельт libbox по outbound; direct/bypass и служебный трафик могут учитываться не полностью. Не использовать как общий VPN/TUN-счётчик."
            textSize = 10f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun hasAppUsageAccess(): Boolean = AppTrafficStatsReader.hasAccess(this)

    private fun loadAppTrafficStats(target: LinearLayout) {
        target.removeAllViews()
        if (!hasAppUsageAccess()) {
            target.addView(TextView(this).apply {
                text = "Разреши доступ Usage Access в настройках Android."
                textSize = 12f
                setTextColor(AMBER)
            })
            return
        }
        target.addView(TextView(this).apply {
            text = "Загружаю статистику Android…"
            textSize = 12f
            setTextColor(0xFFD8E0F2.toInt())
        })
        diagnosticsExecutor.execute {
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60L * 60L * 1000L
            val result = runCatching {
                val current = AppTrafficStatsReader.readPeriod(this, now - dayMs, now)
                val previous = runCatching {
                    AppTrafficStatsReader.readPeriod(this, now - 2L * dayMs, now - dayMs)
                }.getOrNull()
                val weekly = AppTrafficStatsReader.readLastSevenDays(this, now)
                Triple(current, previous, weekly)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                target.removeAllViews()
                result.fold(
                    onSuccess = { period ->
                        val stats = period.first
                        val previous = period.second
                        val weekly = period.third
                        val total = AppTrafficStatsReader.addCounters(stats.totalDownloadedBytes, stats.totalUploadedBytes)
                        val downloaded = stats.totalDownloadedBytes
                        val uploaded = stats.totalUploadedBytes
                        val currentTotals = TrafficUsageInsights.Totals(downloaded, uploaded)
                        val previousTotals = previous?.let {
                            TrafficUsageInsights.Totals(it.totalDownloadedBytes, it.totalUploadedBytes)
                        }
                        val availability = buildList {
                            add(if (stats.wifiAvailable) "Wi-Fi" else "Wi-Fi —")
                            add(if (stats.mobileAvailable) "Мобильная сеть" else "Мобильная сеть —")
                        }.joinToString(" · ")
                        target.addView(TextView(this).apply {
                            text = "ЗА 24 ЧАСА"
                            textSize = 9f
                            letterSpacing = 0.12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(MUTED)
                        })
                        target.addView(TextView(this).apply {
                            text = AppTrafficStatsReader.formatBytes(total)
                            textSize = 22f
                            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                            setTextColor(Color.WHITE)
                            setPadding(0, dp(3), 0, dp(2))
                        })
                        target.addView(TextView(this).apply {
                            text = "↓ ${AppTrafficStatsReader.formatBytes(downloaded)}    ↑ ${AppTrafficStatsReader.formatBytes(uploaded)}"
                            textSize = 11f
                            setTextColor(0xFFB9C8FF.toInt())
                        })
                        target.addView(TextView(this).apply {
                            text = availability + "\nОбщесистемные данные Android, не только VPN."
                            textSize = 10f
                            setTextColor(MUTED)
                            setPadding(0, dp(5), 0, dp(10))
                        })
                        target.addView(TextView(this).apply {
                            text = previousTotals?.let { TrafficUsageInsights.comparison(currentTotals, it) }
                                ?: "Сравнение со вчера недоступно: Android не вернул данные за предыдущие 24 часа."
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(0xFFD8E0F2.toInt())
                            setPadding(0, 0, 0, dp(5))
                        })
                        val warning = previousTotals?.let { TrafficUsageInsights.warning(currentTotals, it) }
                        if (warning != null) {
                            target.addView(TextView(this).apply {
                                text = "⚠ $warning"
                                textSize = 11f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(AMBER)
                                setPadding(dp(9), dp(7), dp(9), dp(7))
                                background = rounded(0xFF332819.toInt(), 10)
                            })
                        }
                        val top = stats.apps.take(8)
                        if (top.isEmpty()) {
                            target.addView(TextView(this).apply {
                                text = "Нет данных за выбранный период."
                                textSize = 12f
                                setTextColor(0xFFD8E0F2.toInt())
                            })
                        }
                        top.forEachIndexed { index, app ->
                            val share = if (total > 0L) (app.totalBytes.toDouble() * 100.0 / total).coerceIn(0.0, 100.0) else 0.0
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(9), dp(8), dp(9), dp(8))
                                background = rounded(0xFF19253A.toInt(), 12)
                            }
                            card.addView(TextView(this).apply {
                                text = "${index + 1}. ${app.label}  ·  ${String.format(java.util.Locale.getDefault(), "%.1f%%", share)}"
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                            })
                            card.addView(TextView(this).apply {
                                text = "↓ ${AppTrafficStatsReader.formatBytes(app.downloadedBytes)}   ↑ ${AppTrafficStatsReader.formatBytes(app.uploadedBytes)}"
                                textSize = 10f
                                setTextColor(0xFFB9C8FF.toInt())
                                setPadding(0, dp(3), 0, dp(6))
                            })
                            val track = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                background = rounded(0xFF29364D.toInt(), 5)
                            }
                            val fill = View(this).apply { background = rounded(0xFF7D91FF.toInt(), 5) }
                            track.addView(fill, LinearLayout.LayoutParams(0, dp(5), share.toFloat().coerceIn(0.5f, 100f)))
                            if (share < 100.0) track.addView(View(this), LinearLayout.LayoutParams(0, dp(5), (100.0 - share).toFloat().coerceAtLeast(0.5f)))
                            card.addView(track)
                            target.addView(card, matchWidth().apply { topMargin = dp(6) })
                        }
                        target.addView(TextView(this).apply {
                            text = "ИСТОРИЯ ПРИЛОЖЕНИЙ · 7 ДНЕЙ"
                            textSize = 9f
                            letterSpacing = 0.12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(MUTED)
                            setPadding(0, dp(16), 0, dp(5))
                        })
                        target.addView(TextView(this).apply {
                            text = "Трафик приложений: слева старые дни, справа сегодня. Сегодня учитывается не полностью."
                            textSize = 10f
                            setTextColor(MUTED)
                            setPadding(0, 0, 0, dp(5))
                        })
                        val bars = "▁▂▃▄▅▆▇█"
                        val dailyTotals = AppTrafficTrend.dailyTotals(weekly)
                        val maxDaily = dailyTotals.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                        val totalSparkline = dailyTotals.joinToString("") { value ->
                            val slot = if (value <= 0L) 0 else
                                ((value.toDouble() / maxDaily.toDouble()) * bars.lastIndex).toInt().coerceIn(1, bars.lastIndex)
                            bars[slot].toString()
                        }
                        target.addView(TextView(this).apply {
                            text = "Весь трафик по дням"
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setPadding(0, dp(5), 0, dp(3))
                        })
                        target.addView(TextView(this).apply {
                            text = totalSparkline + "    " + AppTrafficStatsReader.formatBytes(AppTrafficTrend.safeSum(dailyTotals))
                            textSize = 12f
                            setTextColor(0xFFB9C8FF.toInt())
                        })
                        target.addView(TextView(this).apply {
                            text = weekly.joinToString(" · ") { it.dayLabel }
                            textSize = 9f
                            setTextColor(MUTED)
                            setPadding(0, 0, 0, dp(7))
                        })
                        target.addView(TextView(this).apply {
                            text = "Динамика приложений · вчера к позавчера (изменение от 50 МБ)"
                            textSize = 10f
                            setTextColor(MUTED)
                            setPadding(0, dp(7), 0, dp(3))
                        })
                        val yesterdayApps = weekly.getOrNull(weekly.size - 2)?.apps.orEmpty().associateBy { it.uid }
                        val dayBeforeApps = weekly.getOrNull(weekly.size - 3)?.apps.orEmpty().associateBy { it.uid }
                        val weeklyApps = weekly.flatMap { it.apps }
                            .groupBy { it.uid }
                            .mapValues { (_, entries) -> AppTrafficTrend.safeSum(entries.map { it.totalBytes }) }
                            .entries.sortedByDescending { it.value }.take(5)
                        if (weeklyApps.isEmpty()) {
                            target.addView(TextView(this).apply {
                                text = "За эту неделю статистика приложений пока недоступна."
                                textSize = 11f
                                setTextColor(0xFFD8E0F2.toInt())
                            })
                        }
                        weeklyApps.forEachIndexed { index, entry ->
                            val label = weekly.asSequence().flatMap { it.apps.asSequence() }
                                .firstOrNull { it.uid == entry.key }?.label ?: "UID ${entry.key}"
                            val dailyValues = weekly.map { day ->
                                day.apps.firstOrNull { it.uid == entry.key }?.totalBytes ?: 0L
                            }
                            val maxDay = dailyValues.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                            val sparkline = dailyValues.joinToString("") { value ->
                                val slot = if (value <= 0L) 0 else
                                    ((value.toDouble() / maxDay.toDouble()) * (bars.lastIndex)).toInt().coerceIn(1, bars.lastIndex)
                                bars[slot].toString()
                            }
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(9), dp(8), dp(9), dp(8))
                                background = rounded(0xFF19253A.toInt(), 12)
                            }
                            card.addView(TextView(this).apply {
                                text = "${index + 1}. $label"
                                textSize = 11f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                            })
                            card.addView(TextView(this).apply {
                                text = sparkline + "    Всего: ${AppTrafficStatsReader.formatBytes(entry.value)}"
                                textSize = 12f
                                setTextColor(0xFFB9C8FF.toInt())
                                setPadding(0, dp(3), 0, 0)
                            })
                            val yesterdayBytes = yesterdayApps[entry.key]?.totalBytes ?: 0L
                            val previousBytes = dayBeforeApps[entry.key]?.totalBytes ?: 0L
                            val trend = AppTrafficTrend.describe(yesterdayBytes, previousBytes)
                            if (trend != null) {
                                card.addView(TextView(this).apply {
                                    text = trend
                                    textSize = 10f
                                    typeface = Typeface.DEFAULT_BOLD
                                    setTextColor(if (trend.startsWith("↑")) AMBER else 0xFF8FD6B5.toInt())
                                    setPadding(0, dp(3), 0, 0)
                                })
                            }
                            target.addView(card, matchWidth().apply { topMargin = dp(5) })
                        }
                    },
                    onFailure = { error ->
                        target.addView(TextView(this).apply {
                            text = "Не удалось прочитать статистику: ${error.message ?: "ошибка Android"}. Проверь доступ Usage Access."
                            textSize = 12f
                            setTextColor(AMBER)
                        })
                    }
                )
            }
        }
    }

    private fun requestPhoneStatePermissionIfNeeded() {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        // Explain the optional carrier details before Android displays a sensitive
        // permission prompt. Core VPN and network monitoring work without this grant.
        MaterialAlertDialogBuilder(this)
            .setTitle("Дополнительные данные мобильной сети")
            .setMessage("Разрешение нужно только для названия оператора SIM и определения типа мобильной сети (3G/4G/5G). Astra VPN продолжит работать и без него. Разрешить доступ?")
            .setNegativeButton("Не сейчас", null)
            .setPositiveButton("Продолжить") { _, _ ->
                if (!isFinishing && !isDestroyed &&
                    checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), REQUEST_PHONE_STATE_PERMISSION)
                }
            }
            .show()
    }

    private fun showFamilyQrDialog() {
        val profile = selectedProfile
        if (profile.isNullOrBlank()) {
            Toast.makeText(this, "Сначала выбери сервер", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Семейный доступ по QR")
            .setMessage("QR-код содержит ссылку сервера вместе с параметрами доступа. Любой человек, который отсканирует или получит изображение, сможет использовать эти данные. Передавай код только доверенным членам семьи. Продолжить?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Показать QR") { _, _ ->
                runCatching { ProfileQrCode.create(profile) }
                    .onSuccess { bitmap ->
                        val image = android.widget.ImageView(this).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            contentDescription = "QR-код доступа к выбранному серверу"
                        }
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Отсканируй на втором устройстве")
                            .setView(image)
                            .setMessage("На втором устройстве открой Astra VPN и используй сканер импорта. Не публикуй этот QR-код.")
                            .setPositiveButton("Готово", null)
                            .show()
                    }
                    .onFailure {
                        Toast.makeText(this, "Не удалось создать QR-код. Возможно, ссылка слишком длинная.", Toast.LENGTH_LONG).show()
                    }
            }
            .show()
    }

    private fun showProtectionStreakDialog() {
        val connectedDays = connectionHistory.getEntries()
            .filter { it.state == "connected" }
            .map { it.timestamp }
        val streak = ProtectionStreak.calculate(connectedDays)
        MaterialAlertDialogBuilder(this)
            .setTitle("Дни под защитой")
            .setMessage("Текущая серия: " + streak.currentStreak + " дн.\nВсего дней с подключением: " + streak.totalProtectedDays + ".\n\nСчётчик строится по локальной истории успешных подключений Astra. Он не означает, что VPN был включён непрерывно весь день.")
            .setPositiveButton("Отлично", null)
            .show()
    }

    private fun showOrbitMapDialog() {
        val profiles = safeProfiles() ?: return
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Сначала добавь серверы в Astra", Toast.LENGTH_SHORT).show()
            return
        }
        val nodes = profiles.take(36).map { profile ->
            // The orbit map must follow the same freshness rule as the server
            // list; an expired result is unknown, not a low-quality live Ping.
            val latency = freshLatencyMs(profile)
            val quality = LatencyQualityPolicy.band(latency)
            OrbitMapView.Node(
                label = profileName(profile),
                quality = quality,
                selected = profile == selectedProfile
            )
        }
        var chosen = (selectedProfile?.let { profiles.take(36).indexOf(it) } ?: -1).coerceAtLeast(0)
        val map = OrbitMapView(this, nodes) { index ->
            chosen = index
            val mappedProfile = profiles.take(36)[index]
            if (selectedProfile != mappedProfile) {
                cancelConnectRetry(resetAttempts = true)
                // The map mutates selection immediately, before the dialog's
                // positive button is pressed. Invalidate any consent request here.
                if (pendingConfig != null) cancelPendingConnect()
            }
            selectedProfile = mappedProfile
            selectedInfo.text = profileSummary(profiles.take(36)[index])
            Toast.makeText(this, "Выбрана орбита: ${profileName(profiles.take(36)[index])}", Toast.LENGTH_SHORT).show()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(BG, 20)
            addView(map, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
            ))
            addView(TextView(this@MainActivity).apply {
                text = "Нажми на звезду, чтобы выбрать сервер. Яркость золотого свечения показывает Ping: ярче — быстрее, тусклее — выше задержка или нет измерения."
                textSize = 12f
                setTextColor(0xFFD8E0F2.toInt())
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("✦ Карта орбит · ${nodes.size} серверов")
            .setView(content)
            .setPositiveButton("Выбрать") { _, _ ->
                val profile = profiles.take(36)[chosen]
                if (selectedProfile != profile) {
                    cancelConnectRetry(resetAttempts = true)
                    if (pendingConfig != null) cancelPendingConnect()
                }
                selectedProfile = profile
                selectedInfo.text = profileSummary(profile)
                renderProfiles()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showUsageAndReliabilityDialog() {
        val calendar = java.util.Calendar.getInstance()
        val todayKey = calendar.get(java.util.Calendar.YEAR) * 10000 +
            (calendar.get(java.util.Calendar.MONTH) + 1) * 100 +
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val forecast = TrafficForecast.estimate(dailyTrafficHistory.entries(), todayKey)
        val reliability = ReliabilityScore.calculate(connectionHistory.getEntries())
        val forecastText = forecast?.let {
            "Средний расход устройства: ${DailyTrafficAccumulator.formatBytes(it.averageDailyBytes)} в сутки. Расчёт по ${it.sampleDays} завершённым дням."
        } ?: "Для прогноза нужно минимум два завершённых дня истории. Astra собирает статистику, пока работает мониторинг."
        val reliabilityText = reliability?.let {
            "Оценка: ${it.score}/100\nУверенность: ${it.confidence}\nСобытий подключений: ${it.sessions}\nОшибок: ${it.failures}\n\nОценка основана только на сохранённых событиях Astra и не является гарантией доступности сервера."
        } ?: "Пока мало событий для расчёта. Нужно минимум три события подключения или ошибки."
        MaterialAlertDialogBuilder(this)
            .setTitle("Прогноз и надёжность")
            .setMessage("ПРОГНОЗ ТРАФИКА\n$forecastText\n\nНАДЁЖНОСТЬ СОЕДИНЕНИЯ\n$reliabilityText")
            .setPositiveButton("Понятно", null)
            .show()
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
        val diagnosticNetwork = capabilities
        val captivePortal = diagnosticNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true
        val dnsCount = network?.let { connectivity.getLinkProperties(it)?.dnsServers?.size } ?: 0
        val diagnosticAdvice = DiagnosticAdvisor.recommendations(
            DiagnosticAdvisor.Facts(
                hasNetwork = network != null,
                hasInternetCapability = internet,
                validated = validated,
                vpnActive = vpnTransport,
                tunnelInternetOk = tunnelInternetOk,
                captivePortal = captivePortal,
                dnsServerCount = dnsCount
            )
        )
        val calendar = java.util.Calendar.getInstance()
        val todayKey = calendar.get(java.util.Calendar.YEAR) * 10000 +
            (calendar.get(java.util.Calendar.MONTH) + 1) * 100 +
            calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val trafficForecast = TrafficForecast.estimate(dailyTrafficHistory.entries(), todayKey)
        val reliability = ReliabilityScore.calculate(connectionHistory.getEntries())
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
            appendLine("Captive portal: ${if (captivePortal) "обнаружен — требуется авторизация" else "не обнаружен"}")
            appendLine("DNS-серверов в активной сети: $dnsCount")
            appendLine()
            appendLine("АВТОМАТИЧЕСКИЕ РЕКОМЕНДАЦИИ")
            diagnosticAdvice.forEach { appendLine("• $it") }
            appendLine()
            appendLine("ПРОГНОЗ ТРАФИКА")
            appendLine(trafficForecast?.let {
                "Средний расход: ${DailyTrafficAccumulator.formatBytes(it.averageDailyBytes)} в день (по ${it.sampleDays} завершённым дням)."
            } ?: "Пока недостаточно истории: нужно минимум 2 завершённых дня.")
            appendLine("НАДЁЖНОСТЬ")
            appendLine(reliability?.let {
                "${it.score}/100 · ${it.confidence} уверенность · ошибок: ${it.failures}, подключений: ${it.sessions}. Это ориентир по локальной истории."
            } ?: "Недостаточно событий для оценки (нужно минимум 3 события подключения/ошибки).")
            appendLine("Количество серверов: ${safeProfiles()?.size?.toString() ?: "недоступно"}")
            appendLine()
            appendLine("ДОСТУПНОСТЬ СЕРВИСОВ")
            appendLine(if (vpnTransport) "Проверка выполняется через VPN…" else "Для проверки сервисов через VPN сначала подключись.")
            appendLine("Время отчёта: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = rounded(BG, 22)
        }
        val state = when {
            connected -> "●  VPN ПОДКЛЮЧЁН"
            connecting -> "◌  ИДЁТ ПОДКЛЮЧЕНИЕ"
            else -> "○  VPN ОТКЛЮЧЁН"
        }
        content.addView(TextView(this).apply {
            text = state
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (connected) GREEN else if (connecting) AMBER else MUTED)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF151F32.toInt(), 14)
        })
        val reportView = TextView(this).apply {
            text = report
            textSize = 12f
            setTextColor(0xFFD8E0F2.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        content.addView(reportView)
        var latestReport = report
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Диагностика соединения")
            .setView(scroll)
            .setPositiveButton("Поделиться", null)
            .setNegativeButton("Закрыть", null)
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Astra VPN — диагностика")
                putExtra(Intent.EXTRA_TEXT, latestReport)
            }
            startActivity(Intent.createChooser(share, "Отправить отчёт"))
        }
        if (vpnTransport && network != null) {
            diagnosticsExecutor.execute {
                val results = ServiceReachabilityChecker.checkAll(network)
                runOnUiThread {
                    if (isFinishing || isDestroyed || !dialog.isShowing) return@runOnUiThread
                    val serviceReport = results.joinToString("\n") { it.display() }
                    latestReport = report.replace("Проверка выполняется через VPN…", serviceReport)
                    reportView.text = latestReport
                }
            }
        }
    }

    private fun showConnectionHistoryDialog() {
        val entries = connectionHistory.getEntries()
        val weekAgo = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val recentEntries = entries.filter { it.timestamp >= weekAgo }
        val stability = ConnectionStability.summarize(
            recentEntries.map { ConnectionStability.Event(it.timestamp, it.state) },
            System.currentTimeMillis()
        )
        val stabilityText = "За 7 дней · по сохранённым событиям\n" +
            "Время под защитой: ${ConnectionStability.formatDuration(stability.connectedDurationMs)}\n" +
            "Завершённых сессий: ${stability.completedSessions} · обрывов: ${stability.unexpectedDrops}"
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
        val historyContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = rounded(BG, 22)
        }
        historyContent.addView(TextView(this).apply {
            text = "ИСТОРИЯ ПОДКЛЮЧЕНИЙ"
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF9EAEFF.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        historyContent.addView(TextView(this).apply {
            text = stabilityText
            textSize = 12f
            setTextColor(0xFFD8E0F2.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(0xFF202B43.toInt(), 14)
        })
        if (entries.isEmpty()) {
            historyContent.addView(TextView(this).apply {
                text = body
                textSize = 13f
                setTextColor(MUTED)
                setPadding(dp(12), dp(16), dp(12), dp(16))
                background = rounded(0xFF151F32.toInt(), 14)
            })
        } else entries.forEach { item ->
            val date = java.text.SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp))
            val (label, tint) = when (item.state) {
                "connecting" -> "◌  Подключение" to AMBER
                "connected" -> "●  Подключено" to GREEN
                "disconnected" -> "○  Отключено" to MUTED
                "error" -> "●  Ошибка подключения" to RED
                else -> item.state to LIGHT_ACCENT
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(0xFF151F32.toInt(), 14)
            }
            card.addView(TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(tint)
            })
            card.addView(TextView(this).apply {
                text = "$date\n${item.serverName} · ${item.protocol}"
                textSize = 11f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, 0)
            })
            historyContent.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(historyContent)
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
                    putExtra(Intent.EXTRA_TEXT, "ASTRA VPN — история подключений\n$stabilityText\n\n$shareText")
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

    /**
     * Android owns the real lockdown switch. Astra can guide the user to it,
     * but must never claim that lockdown is enabled or silently change it.
     */
    private fun showAndroidLockdownGuide() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Системная блокировка без VPN")
            .setMessage(
                "Android умеет блокировать весь сетевой трафик вне VPN. Это системная функция, и Astra VPN не может включить её самостоятельно.\n\n" +
                    "Чтобы настроить защиту:\n" +
                    "1. Откройте системные настройки VPN.\n" +
                    "2. Выберите Astra VPN.\n" +
                    "3. Включите «Постоянно включённая VPN».\n" +
                    "4. Включите «Блокировать подключения без VPN», если такой пункт доступен.\n\n" +
                    "Важно: после включения блокировки интернет будет недоступен, если VPN отключится или не сможет подключиться. Сначала проверьте работу сервера и убедитесь, что сможете вернуться в эти настройки."
            )
            .setPositiveButton("Открыть настройки VPN") { _, _ ->
                runCatching {
                    startActivity(Intent("android.settings.VPN_SETTINGS"))
                }.onFailure {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    }.onFailure {
                        Toast.makeText(this, "Не удалось открыть системные настройки", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun showNetworkSecurityDialog() {
        val snapshot = AstraNetworkSecurity.snapshot(this)
        val vpn = getString(if (snapshot.vpnTransportActive) R.string.network_vpn_active else R.string.network_vpn_missing)
        val internet = getString(if (snapshot.activeNetworkHasInternet) R.string.network_internet_yes else R.string.network_internet_unknown)
        val dns = snapshot.dnsServers.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: getString(R.string.network_dns_unavailable)
        val ipv6 = getString(if (snapshot.ipv6DefaultRoutePresent) R.string.network_ipv6_present else R.string.network_ipv6_absent)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_status_title)
            .setMessage(getString(R.string.network_status_message, vpn, internet, dns, ipv6))
            .setNeutralButton(R.string.network_vpn_settings) { _, _ -> AstraNetworkSecurity.openVpnSettings(this) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showWhatsNewDialog() {
        val message = "Astra VPN · обновления проекта\n\n" +
            "• Автоматический выбор и переключение серверов после повторных ошибок.\n" +
            "• Поддержка VLESS, Hysteria2 и TUIC.\n" +
            "• Виджет, быстрые ярлыки и диагностика сети.\n" +
            "• Проверка доступности YouTube, Telegram и ChatGPT.\n" +
            "• Статистика трафика, резервные копии и автообновление подписок.\n\n" +
            "Для обратной связи можно отправить сообщение через Telegram или другое установленное приложение."
        MaterialAlertDialogBuilder(this)
            .setTitle("Что нового")
            .setMessage(message)
            .setNeutralButton("Отправить отзыв") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Astra VPN — отзыв")
                    putExtra(Intent.EXTRA_TEXT, "Привет! Хочу оставить отзыв или сообщить об ошибке в Astra VPN.\n\nВерсия: 1.0")
                }
                runCatching { startActivity(Intent.createChooser(share, "Отправить отзыв")) }
                    .onFailure { Toast.makeText(this, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show() }
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showSecretOrbit() {
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        MaterialAlertDialogBuilder(this)
            .setTitle("✦ Тайная орбита найдена")
            .setMessage("Седьмая звезда открывает новый маршрут.\n\nСпасибо, что исследуешь Astra.")
            .setPositiveButton("Продолжить полёт", null)
            .show()
    }

    private fun showAppSettingsDialog() {
        if (isFinishing || isDestroyed) return
        // Keep the reference as the single-instance lock until onDismiss fires.
        // isShowing becomes false while a dialog is being dismissed; checking only
        // isShowing here allowed a rapid second tap to create a second window.
        settingsDialog?.let { existing ->
            if (existing.isShowing) existing.window?.decorView?.bringToFront()
            return
        }
        val sheet = android.app.Dialog(this)
        settingsDialog = sheet
        sheet.setOnDismissListener {
            if (settingsDialog === sheet) settingsDialog = null
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
            background = rounded(BG, 22)
        }
        var actionGroup: LinearLayout? = null
        fun addActionRow(row: TextView) {
            val group = actionGroup ?: LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFF141B2A.toInt(), 15).apply {
                    setStroke(dp(1), 0xFF29364C.toInt())
                }
                panel.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) })
                actionGroup = this
            }
            val marker = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setSize(dp(17), dp(17))
                setStroke(dp(1), 0xFFD6B36A.toInt())
            }
            row.setCompoundDrawablesWithIntrinsicBounds(marker, null, null, null)
            row.compoundDrawablePadding = dp(12)
            row.minHeight = dp(44)
            row.gravity = Gravity.CENTER_VERTICAL
            row.text = row.text.toString().trimEnd() + "     ›"
            row.setPadding(dp(14), dp(6), dp(12), dp(6))
            row.textSize = 13f
            row.maxLines = 1
            row.ellipsize = android.text.TextUtils.TruncateAt.END
            row.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            row.setTextColor(0xFFE9EDF6.toInt())
            row.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22D6B36A),
                null,
                null
            )
            // A settings row navigates to another screen/dialog. Dismiss this
            // sheet after click dispatch so repeated taps cannot stack windows.
            row.setOnTouchListener { view, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP && sheet.isShowing) {
                    view.post { if (sheet.isShowing) sheet.dismiss() }
                }
                false
            }
            group.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            if (group.childCount > 1) {
                val divider = View(this).apply { setBackgroundColor(0xFF29364C.toInt()) }
                group.addView(divider, group.childCount - 1, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                ).apply { leftMargin = dp(43); rightMargin = dp(12) })
            }
        }
        fun addToggleRow(toggle: android.widget.CompoundButton) {
            toggle.textSize = 13f
            toggle.setTextColor(Color.WHITE)
            toggle.minHeight = dp(52)
            toggle.setPadding(dp(12), dp(8), dp(10), dp(8))
            toggle.background = rounded(0xFF141B2A.toInt(), 13).apply {
                setStroke(dp(1), 0xFF29364C.toInt())
            }
            if (toggle is android.widget.Switch) {
                toggle.thumbTintList = android.content.res.ColorStateList.valueOf(0xFFD6B36A.toInt())
                toggle.trackTintList = android.content.res.ColorStateList.valueOf(0x665D6B80)
            } else {
                toggle.buttonTintList = android.content.res.ColorStateList.valueOf(0xFFD6B36A.toInt())
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(5)
            panel.addView(toggle, params)
        }
        fun addSectionLabel(label: String) {
            actionGroup = null
            panel.addView(TextView(this).apply {
                text = label.uppercase(java.util.Locale.getDefault())
                textSize = 10f
                letterSpacing = 0.14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFD6B36A.toInt())
                setPadding(dp(2), dp(14), 0, dp(5))
            })
        }
        addSectionLabel("Выбор сервера")
        panel.addView(TextView(this).apply {
            text = "Сортировка серверов"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(6))
        })
        val sortGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(0xFF0C1422.toInt(), 14)
        }
        val currentMode = settingsPrefs.getString("sort_mode", "original") ?: "original"
        listOf(
            "original" to "Подписка",
            "ping" to "Ping ↑",
            "alphabet" to "А–Я"
        ).forEach { (value, label) ->
            sortGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = value
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                buttonDrawable = null
                setPadding(dp(8), 0, dp(8), 0)
                minHeight = dp(42)
                gravity = Gravity.CENTER
                background = rounded(
                    if (currentMode == value) 0xFF3B3427.toInt() else Color.TRANSPARENT, 10
                ).apply {
                    if (currentMode == value) setStroke(dp(1), 0xFFD6B36A.toInt())
                }
                isChecked = currentMode == value
            }, RadioGroup.LayoutParams(0, dp(46), 1f))
        }
        panel.addView(sortGroup)
        addSectionLabel("Инструменты")
        val onboardingEntry = TextView(this).apply {
            text = "Повторить знакомство с Astra"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener {
                sheet.dismiss()
                settingsPrefs.edit().putBoolean("onboarding_completed", false).apply()
                window.decorView.post { if (!isFinishing && !isDestroyed) showFirstRunOnboarding(force = true) }
            }
        }
        addActionRow(onboardingEntry)
        val whatsNewEntry = TextView(this).apply {
            text = "Что нового и поддержка"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showWhatsNewDialog() }
        }
        addActionRow(whatsNewEntry)
        val monitorEntry = TextView(this).apply {
            text = "Мониторинг сети"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showNetworkMonitorDialog() }
        }
        addActionRow(monitorEntry)

        val speedTestEntry = TextView(this).apply {
            text = "Тест скорости и внешнего IP"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { runCombinedSpeedAndIpTest() }
        }
        addActionRow(speedTestEntry)

        val diagnosticsEntry = TextView(this).apply {
            text = "Диагностика соединения"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showConnectionDiagnostics() }
        }
        addActionRow(diagnosticsEntry)

        val orbitMapEntry = TextView(this).apply {
            text = "Интерактивная карта орбит"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener {
                sheet.dismiss()
                showOrbitMapDialog()
            }
        }
        addActionRow(orbitMapEntry)
        val insightsEntry = TextView(this).apply {
            text = "Прогноз трафика и надёжность"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener {
                sheet.dismiss()
                showUsageAndReliabilityDialog()
            }
        }
        addActionRow(insightsEntry)

        val familyQrEntry = TextView(this).apply {
            text = "Семейный доступ по QR"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showFamilyQrDialog() }
        }
        addActionRow(familyQrEntry)
        val streakEntry = TextView(this).apply {
            text = "Дни под защитой"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showProtectionStreakDialog() }
        }
        addActionRow(streakEntry)
        val speedFailoverEntry = CheckBox(this).apply {
            text = "Автопереключение при падении скорости (требует достоверной VPN-статистики)"
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("auto_speed_failover", false)
            setOnCheckedChangeListener { _, checked ->
                settingsPrefs.edit().putBoolean("auto_speed_failover", checked).apply()
            }
        }
        addToggleRow(speedFailoverEntry)

        val networkSecurityEntry = TextView(this).apply {
            text = getString(R.string.network_security_entry)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showNetworkSecurityDialog() }
        }
        addActionRow(networkSecurityEntry)

        val historyEntry = TextView(this).apply {
            text = "История подключений"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showConnectionHistoryDialog() }
        }
        addActionRow(historyEntry)

        val appRoutingEntry = TextView(this).apply {
            text = "Приложения через VPN"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showAppRoutingDialog() }
        }
        addActionRow(appRoutingEntry)

        val bankingEntry = TextView(this).apply {
            text = "Автопауза VPN для банков"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showBankingProtectionDialog() }
        }
        addActionRow(bankingEntry)

        addSectionLabel("Резервная копия")
        val backupExportEntry = TextView(this).apply {
            text = "Экспортировать серверы и подписки"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { startBackupExport() }
        }
        addActionRow(backupExportEntry)
        val backupImportEntry = TextView(this).apply {
            text = "Восстановить из резервной копии"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { startBackupImport() }
        }
        addActionRow(backupImportEntry)

        addSectionLabel("Защита соединения")
        val vpnRecovery = android.widget.Switch(this).apply {
            text = "Восстанавливать VPN после сбоя приложения"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("vpn_recovery_enabled", false)
        }
        addToggleRow(vpnRecovery)
        panel.addView(TextView(this).apply {
            text = "При системном перезапуске процесса Astra попробует восстановить последний сервер. Требуется ранее выданное разрешение Android. После force-stop или перезагрузки телефона потребуется открыть приложение вручную."
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, dp(8))
        })
        val lockdownEntry = TextView(this).apply {
            text = "Системная блокировка без VPN"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF172238.toInt(), 10)
            setOnClickListener { showAndroidLockdownGuide() }
        }
        addActionRow(lockdownEntry)

        addSectionLabel("Экономия энергии")
        val batterySaver = android.widget.Switch(this).apply {
            text = "Режим экономии батареи"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("battery_saver_mode", false)
        }
        addToggleRow(batterySaver)
        panel.addView(TextView(this).apply {
            text = "Снижает частоту обновления сетевого мониторинга с 1 до 4 секунд. Проверки безопасности туннеля продолжают работать."
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, dp(8))
        })

        addSectionLabel("Автообновление")
        val autoUpdate = android.widget.Switch(this).apply {
            text = "Автоматически обновлять подписки"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("auto_update", false)
        }
        addToggleRow(autoUpdate)
        val updateOnOpen = android.widget.Switch(this).apply {
            text = "Обновлять при открытии приложения"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("update_on_open", false)
        }
        addToggleRow(updateOnOpen)
        val subscriptionNotifications = android.widget.Switch(this).apply {
            text = "Уведомлять об обновлении подписок"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = settingsPrefs.getBoolean("subscription_notifications", true)
        }
        addToggleRow(subscriptionNotifications)
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
                else -> "Последняя попытка успешна"
            }
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(8))
        })
        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(4))
            addView(panel)
        }
        val sheetContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
            // Full-screen settings surface: no strip of the home screen may remain
            // visible at the side on narrow phones or with enlarged system fonts.
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF121D30.toInt(), 0xFF090F1B.toInt())
            ).apply {
                cornerRadius = 0f
            }
        }
        val sheetHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sheetContent.addView(View(this).apply {
            background = rounded(0xFF53617A.toInt(), 99)
        }, LinearLayout.LayoutParams(dp(38), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })
        sheetHeader.addView(TextView(this).apply {
            text = "Настройки"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        sheetHeader.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            contentDescription = "Закрыть настройки"
            setOnClickListener { sheet.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        sheetContent.addView(sheetHeader)
        sheetContent.addView(settingsScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        // Settings are applied immediately; there is no separate Save action.
        fun persistBoolean(key: String, value: Boolean) {
            settingsPrefs.edit().putBoolean(key, value).apply()
        }
        sortGroup.setOnCheckedChangeListener { group, checkedId ->
            val mode = group.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            for (index in 0 until group.childCount) {
                val chip = group.getChildAt(index) as? RadioButton ?: continue
                val active = chip.id == checkedId
                chip.background = rounded(
                    if (active) 0xFF3B3427.toInt() else Color.TRANSPARENT, 10
                ).apply {
                    if (active) setStroke(dp(1), 0xFFD6B36A.toInt())
                }
            }
            sortMode = mode
            settingsPrefs.edit().putString("sort_mode", mode).apply()
            renderProfiles()
        }
        autoUpdate.setOnCheckedChangeListener { _, checked ->
            persistBoolean("auto_update", checked)
            SubscriptionRefreshScheduler.sync(this@MainActivity)
            if (checked) maybeAutoUpdateSubscriptions(true)
        }
        updateOnOpen.setOnCheckedChangeListener { _, checked ->
            persistBoolean("update_on_open", checked)
        }
        subscriptionNotifications.setOnCheckedChangeListener { _, checked ->
            persistBoolean("subscription_notifications", checked)
            if (checked) requestNotificationPermissionIfNeeded()
        }
        batterySaver.setOnCheckedChangeListener { _, checked ->
            persistBoolean("battery_saver_mode", checked)
        }
        vpnRecovery.setOnCheckedChangeListener { _, checked ->
            persistBoolean("vpn_recovery_enabled", checked)
            runCatching {
                ProfileStore(this@MainActivity).setRecoveryProfile(
                    if (checked && (connected || connecting)) activeProfile else null
                )
            }.onFailure {
                persistBoolean("vpn_recovery_enabled", false)
                vpnRecovery.isChecked = false
                Toast.makeText(this@MainActivity, "Не удалось сохранить профиль восстановления", Toast.LENGTH_LONG).show()
            }
            if (connected || connecting) {
                runCatching {
                    val recoveryIntent = Intent(this@MainActivity, AstraVpnService::class.java)
                        .setAction(AstraVpnService.ACTION_UPDATE_RECOVERY)
                        .putExtra(AstraVpnService.EXTRA_PROFILE_LINK, activeProfile)
                    startService(recoveryIntent)
                }.onFailure {
                    Toast.makeText(this@MainActivity, "Не удалось применить настройку восстановления", Toast.LENGTH_LONG).show()
                }
            }
        }
        sheet.setContentView(sheetContent)
        sheet.window?.setBackgroundDrawableResource(android.R.color.transparent)
        sheet.window?.setDimAmount(0.62f)
        sheet.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        sheet.setOnShowListener {
            sheet.window?.let { dialogWindow ->
                dialogWindow.setGravity(Gravity.CENTER)
                dialogWindow.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                dialogWindow.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            sheetContent.post {
                sheetContent.translationX = -sheetContent.width.toFloat()
                sheetContent.animate().translationX(0f).setDuration(260L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
        sheet.show()
        sheet.window?.let { dialogWindow ->
            dialogWindow.setGravity(Gravity.CENTER)
            dialogWindow.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
        val checks = mutableMapOf<String, android.widget.Switch>()
        val appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apps.forEach { app ->
            val check = android.widget.Switch(this).apply {
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
            background = rounded(0xFF151F32.toInt(), 15)
            clipToPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(appList)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)
            )
        }
        val presetsEntry = TextView(this).apply {
            text = "Готовые пресеты маршрутизации"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFF202B43.toInt(), 13)
            setOnClickListener {
                val presets = arrayOf("Весь трафик через VPN", "Только мессенджеры", "Только браузеры", "Банковские приложения напрямую")
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Быстрый пресет")
                    .setItems(presets) { _, which ->
                        val packages = when (which) {
                            1 -> apps.map { it.packageName }.filter { name ->
                                val n = name.lowercase(java.util.Locale.ROOT)
                                n.contains("telegram") || n.contains("whatsapp") || n.contains("signal") || n.contains("viber")
                            }.toSet()
                            2 -> apps.map { it.packageName }.filter { name ->
                                val n = name.lowercase(java.util.Locale.ROOT)
                                n.contains("browser") || n.contains("chrome") || n.contains("firefox") ||
                                    n.contains("brave") || n.contains("opera") || n.contains("edge")
                            }.toSet()
                            3 -> apps.filter { app ->
                                val key = (app.packageName + " " + app.label).lowercase(java.util.Locale.ROOT)
                                listOf("bank", "банк", "sber", "сбер", "tinkoff", "тинькофф", "альфа", "alfa",
                                    "vtb", "втб", "gazprom", "газпром", "raiffeisen", "райффайзен",
                                    "россельхоз", "rshb", "почта банк", "pochtabank", "открытие", "openbank")
                                    .any(key::contains)
                            }.map { it.packageName }.toSet()
                            else -> emptySet()
                        }
                        val includeOnly = which == 1 || which == 2
                        if (includeOnly && packages.isEmpty()) {
                            Toast.makeText(this@MainActivity, "Подходящие приложения не найдены. Выбери их вручную.", Toast.LENGTH_LONG).show()
                            return@setItems
                        }
                        checks.forEach { (packageName, check) -> check.isChecked = packageName in packages }
                        modeGroup.findViewWithTag<RadioButton>(if (includeOnly) "include" else "exclude")
                            ?.let { modeGroup.check(it.id) }
                        val message = if (which == 3)
                            "Банковские приложения будут исключены из VPN. Проверь список и нажми «Сохранить»."
                        else "Пресет выбран. Нажми «Сохранить» для применения."
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
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
            setPadding(dp(16), dp(10), dp(16), dp(12))
            background = rounded(BG, 22)
            addView(TextView(this@MainActivity).apply {
                text = "Выбери приложения и режим маршрутизации. Изменения применятся после следующего подключения VPN."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, 0, 0, dp(10))
            })
            addView(TextView(this@MainActivity).apply {
                text = "ПРАВИЛА МАРШРУТИЗАЦИИ"
                textSize = 10f
                letterSpacing = 0.12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF9EAEFF.toInt())
                setPadding(0, dp(6), 0, dp(8))
            })
            addView(modeGroup)
            addView(TextView(this@MainActivity).apply {
                text = "ПОИСК ПРИЛОЖЕНИЙ"
                textSize = 10f
                letterSpacing = 0.12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MUTED)
                setPadding(dp(2), dp(10), 0, dp(5))
            })
            addView(appSearch, matchWidth().apply { bottomMargin = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = "УСТАНОВЛЕННЫЕ ПРИЛОЖЕНИЯ"
                textSize = 10f
                letterSpacing = 0.12f
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

    /**
     * User-facing setup for the opt-in banking-app monitor and guarded VPN automation.
     * The monitor is started only after Usage Access is granted; it fails closed
     * if that access is later revoked.
     */
    private fun showBankingProtectionDialog() {
        val prefs = settingsPrefs
        val enabled = CheckBox(this).apply {
            text = "Включить мониторинг банковских приложений"
            textSize = 14f
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("banking_auto_pause_enabled", false)
        }
        val selectedCount = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(8), 0, dp(8))
        }
        fun refreshCount() {
            val count = prefs.getStringSet("banking_app_packages", emptySet())?.size ?: 0
            selectedCount.text = "Выбрано банковских приложений: $count"
        }
        val chooseApps = TextView(this).apply {
            text = "Выбрать банковские приложения"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LIGHT_ACCENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(0xFF202B43.toInt(), 13)
            setOnClickListener {
                val apps = runCatching {
                    packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
                    ).mapNotNull { info ->
                        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                        val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                        InstalledApp(pkg, label, info.loadIcon(packageManager))
                    }.distinctBy { it.packageName }
                        .sortedBy { it.label.lowercase(java.util.Locale.getDefault()) }
                }.getOrElse { emptyList() }
                if (apps.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Не удалось получить список приложений", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val current = prefs.getStringSet("banking_app_packages", emptySet())?.toMutableSet() ?: mutableSetOf()
                val labels = apps.map { it.label }.toTypedArray()
                val checked = BooleanArray(apps.size) { apps[it].packageName in current }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Банковские приложения")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        if (isChecked) current.add(apps[which].packageName)
                        else current.remove(apps[which].packageName)
                    }
                    .setNeutralButton("Автоопределение") { _, _ ->
                        val guessed = apps.filter { app ->
                            val key = (app.packageName + " " + app.label).lowercase(java.util.Locale.ROOT)
                            listOf("bank", "банк", "sber", "сбер", "tinkoff", "тинькофф", "альфа", "alfa",
                                "vtb", "втб", "gazprom", "газпром", "raiffeisen", "райффайзен",
                                "россельхоз", "rshb", "pochtabank", "почта банк", "openbank", "открытие")
                                .any(key::contains)
                        }.map { it.packageName }.toSet()
                        prefs.edit().putStringSet("banking_app_packages", guessed).apply()
                        refreshCount()
                        Toast.makeText(this@MainActivity, "Найдено приложений: ${guessed.size}. Проверь список.", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Сохранить") { _, _ ->
                        prefs.edit().putStringSet("banking_app_packages", current).apply()
                        refreshCount()
                    }
                    .show()
            }
        }
        val permissionStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(8), 0, dp(4))
        }
        fun refreshPermission() {
            permissionStatus.text = if (UsageAccessPermission.hasAccess(this)) {
                "Доступ к статистике использования приложений предоставлен."
            } else {
                "Для мониторинга необходимо отдельно разрешить Astra доступ к статистике использования."
            }
        }
        refreshCount()
        refreshPermission()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = rounded(BG, 20)
            addView(TextView(this@MainActivity).apply {
                text = "Когда открываешь выбранное банковское приложение, Astra пытается приостановить активный VPN, а после выхода — восстановить тот же профиль. Нужен доступ Usage Access. При неоднозначном состоянии приложения автоматическое восстановление не выполняется; функция требует проверки на твоём устройстве."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, 0, 0, dp(10))
            })
            addView(enabled)
            addView(chooseApps, matchWidth().apply { topMargin = dp(8) })
            addView(selectedCount)
            addView(permissionStatus)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Банковская защита")
            .setView(content)
            .setNeutralButton("Разрешение Usage Access") { _, _ ->
                runCatching { startActivity(UsageAccessPermission.settingsIntent(this)) }
                    .onFailure { Toast.makeText(this, "Не удалось открыть настройки доступа", Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Сохранить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = prefs.getStringSet("banking_app_packages", emptySet()) ?: emptySet()
                if (enabled.isChecked && selected.isEmpty()) {
                    Toast.makeText(this, "Сначала выбери хотя бы одно банковское приложение.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putBoolean("banking_auto_pause_enabled", enabled.isChecked).apply()
                if (!enabled.isChecked) {
                    BankingForegroundMonitorService.stop(this)
                    Toast.makeText(this, "Мониторинг банковских приложений выключен.", Toast.LENGTH_SHORT).show()
                } else if (UsageAccessPermission.hasAccess(this)) {
                    runCatching { BankingForegroundMonitorService.start(this) }
                        .onFailure { Toast.makeText(this, "Не удалось запустить мониторинг: ${it.message}", Toast.LENGTH_LONG).show() }
                    Toast.makeText(this, "Мониторинг банковских приложений включён.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Предоставь Usage Access и вернись в Astra для запуска мониторинга.", Toast.LENGTH_LONG).show()
                    runCatching { startActivity(UsageAccessPermission.settingsIntent(this)) }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showFirstRunOnboarding(force: Boolean = false) {
        if ((!force && settingsPrefs.getBoolean("onboarding_completed", false)) || isFinishing || isDestroyed) return
        val pages = listOf(
            "Своя орбита. Своя скорость. Своя защита" to "Astra помогает подключаться к VPN-серверам и управлять ими в одном месте.",
            "Выбери свою орбиту" to "Добавь ссылку или подписку. Astra поддерживает VLESS, Hysteria2 и TUIC.",
            "Запуск — одним нажатием" to "Выбери сервер и нажми на звезду. Для лучшего результата сначала проверь задержку серверов."
        )
        var page = 0
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(BG, 20)
        }
        val star = TextView(this).apply {
            text = "✦"; textSize = 48f; gravity = Gravity.CENTER; setTextColor(LIGHT_ACCENT)
        }
        val heading = TextView(this).apply {
            textSize = 19f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(10))
        }
        val description = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(0xFFD0D9EC.toInt()); setPadding(dp(8), 0, dp(8), dp(12))
        }
        val pageLabel = TextView(this).apply {
            textSize = 11f; gravity = Gravity.CENTER; setTextColor(MUTED)
        }
        content.addView(star, matchWidth())
        content.addView(heading, matchWidth())
        content.addView(description, matchWidth())
        content.addView(pageLabel, matchWidth())
        fun renderPage() {
            heading.text = pages[page].first
            description.text = pages[page].second
            pageLabel.text = (page + 1).toString() + " / 3"
        }
        renderPage()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Знакомство с Astra")
            .setView(content)
            .setPositiveButton("Далее", null)
            .setNegativeButton("Пропустить") { _, _ ->
                settingsPrefs.edit().putBoolean("onboarding_completed", true).apply()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (page < pages.lastIndex) {
                    page++
                    renderPage()
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).text =
                        if (page == pages.lastIndex) "Начать" else "Далее"
                } else {
                    settingsPrefs.edit().putBoolean("onboarding_completed", true).apply()
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).text = "Далее"
    }

    private fun runCombinedSpeedAndIpTest() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Тест скорости и IP")
            .setMessage("Проверяем внешний IP и измеряем загрузку тестового файла…")
            .setNegativeButton("Закрыть", null)
            .create()
        dialog.show()
        diagnosticsExecutor.execute {
            val report = runCatching {
                val ipConnection = (URL("https://api.ipify.org").openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 7000; requestMethod = "GET"
                }
                val ip = try { ipConnection.inputStream.bufferedReader().use { it.readText().trim() } }
                    finally { ipConnection.disconnect() }
                require(ip.isNotBlank() && ip.length <= 64) { "Не удалось определить внешний IP" }
                val testBytes = 3_000_000L
                val speedConnection = (URL("https://speed.cloudflare.com/__down?bytes=" + testBytes)
                    .openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 15000; requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val started = System.nanoTime()
                var received = 0L
                try {
                    speedConnection.inputStream.use { input ->
                        val buffer = ByteArray(16 * 1024)
                        while (received < testBytes) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                        }
                    }
                } finally { speedConnection.disconnect() }
                require(received > 0) { "Тестовый сервер не передал данные" }
                val seconds = (System.nanoTime() - started).coerceAtLeast(1L) / 1_000_000_000.0
                val mbps = received * 8.0 / seconds / 1_000_000.0
                val route = if (connected) "через активный VPN" else "через текущее подключение без подтверждённого VPN"
                "Внешний IP: " + ip + "\nЗагрузка: " + String.format(java.util.Locale.US, "%.2f", mbps) +
                    " Мбит/с\nПолучено: " + (received / 1_000_000) + " МБ\nМаршрут: " + route +
                    "\n\nРезультат зависит от сети и тестового узла."
            }.getOrElse { error ->
                "Тест не завершён: " + (error.message ?: "ошибка сети") + ". Проверь подключение и повтори попытку."
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed && dialog.isShowing) {
                    dialog.setMessage(report)
                    dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Готово") { _, _ -> dialog.dismiss() }
                }
            }
        }
    }

    private fun maybeAutoUpdateSubscriptions(onOpen: Boolean) {
        if (refreshingSubscriptions) return
        val enabled = settingsPrefs.getBoolean("auto_update", false)
        val updateOnOpen = settingsPrefs.getBoolean("update_on_open", false)
        if (!enabled && !(onOpen && updateOnOpen)) return
        val now = System.currentTimeMillis()
        val last = settingsPrefs.getLong("last_subscription_update", 0L)
        val lastAttempt = settingsPrefs.getLong("last_subscription_attempt", last)
        val intervalMs = SubscriptionRefreshInterval.intervalMillis(
            settingsPrefs.getInt("update_interval_hours", 2)
        )
        val currentSources = safeSubscriptionSources() ?: return
        if (currentSources.isEmpty()) return
        val pending = settingsPrefs.getStringSet("subscription_retry_sources", emptySet())
            .orEmpty().map(SubscriptionSourceFingerprint::fromStoredValue).toSet()
        val known = settingsPrefs.getStringSet("subscription_known_sources", emptySet())
            .orEmpty().map(SubscriptionSourceFingerprint::fromStoredValue).toSet()
        val summaries = profileStore.subscriptionSummaries().associateBy { it.sourceUrl }
        val candidates = currentSources.map { source ->
            val history = SubscriptionRefreshHistory.get(settingsPrefs, source, now)
            SubscriptionRefreshCandidate(
                fingerprint = SubscriptionSourceFingerprint.of(source),
                intervalHours = summaries[source]?.updateIntervalHours
                    ?: settingsPrefs.getInt("update_interval_hours", 2),
                lastSuccessAt = history?.takeIf { it.state == "success" }?.timestamp
            )
        }
        val dueFingerprints = SubscriptionRefreshDuePolicy.select(
            candidates = candidates,
            pendingFingerprints = pending,
            knownFingerprints = known,
            defaultIntervalHours = settingsPrefs.getInt("update_interval_hours", 2),
            now = now
        ).toSet()
        val dueSources = currentSources.filter {
            SubscriptionSourceFingerprint.of(it) in dueFingerprints
        }
        if (dueSources.isEmpty()) return
        val onOpenThrottlePassed = now < lastAttempt || now - lastAttempt > 5 * 60_000L
        val periodicThrottlePassed = now < lastAttempt || now - lastAttempt >= intervalMs
        val shouldUpdate = (onOpen && updateOnOpen && onOpenThrottlePassed) ||
            (enabled && periodicThrottlePassed)
        if (!shouldUpdate) return
        refreshSubscriptions(showResult = false, onlySources = dueSources)
    }

    private fun latencyStabilityPenalties(profiles: List<String>): Map<String, Long> {
        val now = System.currentTimeMillis()
        return profiles.mapNotNull { profile ->
            val penalty = LatencyStabilityScorer.penaltyMs(latencyHistory.samples(profile, now), now)
            if (penalty > 0L) profile to penalty else null
        }.toMap()
    }

    private fun recentServerFailureCounts(profiles: List<String>): Map<String, Int> {
        val failuresByName = ServerFailureScorer.weightedFailures(
            entries = connectionHistory.getEntries(),
            now = System.currentTimeMillis()
        )
        return profiles.associateWith { profile ->
            val identity = "${profileName(profile).trim().lowercase(java.util.Locale.ROOT)}|${protocolName(profile).trim().lowercase(java.util.Locale.ROOT).let { if (it == "hy2") "hysteria2" else it }}"
            failuresByName[identity] ?: 0
        }
    }

    private fun profileName(link: String): String {
        val uri = Uri.parse(link)
        val fragment = uri.fragment?.let { Uri.decode(it) }?.trim().orEmpty()
        return fragment.ifBlank { uri.host.orEmpty().ifBlank { "Сервер" } }
    }

    private fun profileSummary(link: String): String {
        val uri = Uri.parse(link)
        val fragment = uri.fragment?.let { Uri.decode(it) }?.trim().orEmpty()
        val cleanFragment = fragment.removePrefix("#").replace(Regex("^\\d+[ _-]+"), "").trim()
            val looksEncoded = cleanFragment.startsWith("base64:", true) || (cleanFragment.length > 48 && cleanFragment.matches(Regex("[A-Za-z0-9+/=_-]+")))
            val name = cleanFragment.takeUnless { it.isBlank() || looksEncoded } ?: uri.host.orEmpty().ifBlank { "Сервер" }
        val protocol = when (uri.scheme?.lowercase()) {
            "vless" -> "VLESS"
            "hysteria2", "hy2" -> "Hysteria2"
            "tuic" -> "TUIC"
            else -> "VPN"
        }
        val flag = CountryFlagResolver.flagFor(name)
        val latency = pingInfo[link] ?: "Задержка не проверена"
        return "$flag  $name  ·  $protocol\n$latency"
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

    /** A short shooting-star accent appears only for nighttime VPN launches. */
    private fun launchNightShootingStarIfNeeded() {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour !in 21..23 && hour !in 0..5) return
        val decor = window.decorView as? ViewGroup ?: return
        val star = NightShootingStarView(this)
        decor.addView(star, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        star.play { decor.removeView(star) }
    }

    private class NightShootingStarView(context: Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD878.toInt()
            strokeWidth = 3f * resources.displayMetrics.density
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
            setShadowLayer(18f * resources.displayMetrics.density, 0f, 0f, 0xFFFFC95C.toInt())
        }
        private var progress = 0f
        private var animator: android.animation.ValueAnimator? = null

        fun play(onEnd: () -> Unit) {
            animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_050L
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
                })
                start()
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (progress <= 0f || progress >= 1f) return
            val density = resources.displayMetrics.density
            val x = width * (0.88f - 0.72f * progress)
            val y = height * (0.12f + 0.28f * progress)
            val tail = 52f * density
            paint.alpha = (255 * (1f - progress * 0.35f)).toInt()
            canvas.drawLine(x, y, x + tail, y - tail * 0.42f, paint)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawCircle(x, y, 3.2f * density, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            postInvalidateOnAnimation()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            super.onDetachedFromWindow()
        }
    }

    /** A restrained breathing glow makes the connected star feel active without distracting. */
    private fun startStarPulse() {
        if (!::connectButton.isInitialized || starPulseAnimator?.isRunning == true) return
        starPulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_700L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            addUpdateListener { animator ->
                if (!::connectButton.isInitialized || connectionVisualState != "connected") {
                    cancel()
                    return@addUpdateListener
                }
                val progress = animator.animatedValue as Float
                val scale = 1f + 0.025f * progress
                connectButton.scaleX = scale
                connectButton.scaleY = scale
                connectButton.elevation = dp(10 + (3 * progress).toInt()).toFloat()
            }
            start()
        }
    }

    private fun stopStarPulse() {
        starPulseAnimator?.cancel()
        starPulseAnimator = null
        if (::connectButton.isInitialized) {
            connectButton.scaleX = 1f
            connectButton.scaleY = 1f
            connectButton.elevation = dp(4).toFloat()
        }
    }

    /**
     * The primary button is silver while disconnected. With the selected server
     * actively connected: green = 1–200 ms, amber = 201+ ms or unknown latency,
     * red = a confirmed failed tunnel health check.
     */
    private fun updateConnectButtonServerHealth() {
        if (!::connectButton.isInitialized) return

        val selected = selectedProfile
        val measured = selected?.let { freshLatencyMs(it) }
        val selectedIsActive = connected && selected != null && selected == activeProfile
        val resolved = if (selectedIsActive) {
            ConnectButtonHealthPolicy.resolve(
                connected = true,
                latencyMs = measured,
                tunnelInternetOk = tunnelInternetOk
            )
        } else {
            when (connectionVisualState) {
                "connecting", "disconnecting", "switching" -> ConnectButtonHealthPolicy.Color.AMBER
                else -> ConnectButtonHealthPolicy.Color.SILVER
            }
        }
        val targetColor = when (resolved) {
            ConnectButtonHealthPolicy.Color.SILVER -> 0xFFC7CED8.toInt()
            ConnectButtonHealthPolicy.Color.GREEN -> GREEN
            ConnectButtonHealthPolicy.Color.AMBER -> AMBER
            ConnectButtonHealthPolicy.Color.RED -> RED
        }
        animateConnectButtonTint(targetColor)

        connectButton.contentDescription = when (resolved) {
            ConnectButtonHealthPolicy.Color.RED -> "VPN-сервер недоступен: проверка соединения завершилась ошибкой"
            ConnectButtonHealthPolicy.Color.AMBER -> if (measured != null && selectedIsActive) {
                "VPN подключён, повышенная задержка: ${measured} миллисекунд"
            } else if (selectedIsActive) {
                "VPN подключён. Задержка ещё не измерена или устарела"
            } else {
                "VPN не подключён или выполняется переключение"
            }
            ConnectButtonHealthPolicy.Color.GREEN ->
                "VPN-сервер работает, задержка ${measured} миллисекунд"
            ConnectButtonHealthPolicy.Color.SILVER -> "Подключить VPN"
        }
    }

    /** Smoothly interpolate the star tint without delaying or blocking UI actions. */
    private fun animateConnectButtonTint(targetColor: Int) {
        if (!::connectButton.isInitialized) return
        val current = connectButton.backgroundTintList?.defaultColor ?: targetColor
        if (current == targetColor) return
        connectTintAnimator?.cancel()
        connectTintAnimator = android.animation.ValueAnimator.ofArgb(current, targetColor).apply {
            duration = 220L
            addUpdateListener { animator ->
                if (::connectButton.isInitialized) {
                    connectButton.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(animator.animatedValue as Int)
                }
            }
            start()
        }
    }

    /** Updates the hero card only when its connection state changes. */
    private fun updateConnectionCardAppearance(state: String) {
        if (connectionVisualState == state || !::connectionCard.isInitialized) return
        connectionVisualState = state
        if (::starButtonFrame.isInitialized) {
            (0 until starButtonFrame.childCount).asSequence()
                .mapNotNull { starButtonFrame.getChildAt(it) as? OrbitHeroView }
                .firstOrNull()?.setConnectionState(state)
        }
        val colors = when (state) {
            "connected" -> intArrayOf(0xFF3A2D13.toInt(), 0xFF242018.toInt(), 0xFF10131B.toInt())
            "connecting" -> intArrayOf(0xFF4A3518.toInt(), 0xFF30251B.toInt(), 0xFF17131D.toInt())
            "switching" -> intArrayOf(0xFF34301F.toInt(), 0xFF242431.toInt(), 0xFF10131B.toInt())
            "disconnecting" -> intArrayOf(0xFF3A2B1B.toInt(), 0xFF28221F.toInt(), 0xFF10131B.toInt())
            else -> intArrayOf(0xFF302817.toInt(), 0xFF1C1D27.toInt(), 0xFF0D111B.toInt())
        }
        val stroke = when (state) {
            "connected" -> 0xFF4BAF91.toInt()
            "connecting" -> 0xFFD6A45C.toInt()
            "switching" -> 0xFFB49A5B.toInt()
            "disconnecting" -> 0xFFD6A45C.toInt()
            else -> 0xFF806B3A.toInt()
        }
        if (::constellationView.isInitialized) constellationView.setActive(state == "connected")
        // Keep the hero transparent in every VPN state; only the star and
        // the small status indicator communicate connection health.
        if (::connectButton.isInitialized) {
            val starColor = when (state) {
                "connected" -> 0xFF2A1B06.toInt()
                "connecting" -> 0xFF3A2608.toInt()
                "switching" -> 0xFF3A2B0D.toInt()
                "disconnecting" -> 0xFF3A2608.toInt()
                else -> 0xFF3A2B0D.toInt()
            }
            val buttonColor = when (state) {
                "connected" -> 0xFFFFD36E.toInt()
                "connecting" -> 0xFFFFC45C.toInt()
                "switching" -> 0xFFE7B95A.toInt()
                "disconnecting" -> 0xFFFFC45C.toInt()
                else -> 0xFFFFC95C.toInt()
            }
            connectButton.setTextColor(starColor)
            connectButton.backgroundTintList = android.content.res.ColorStateList.valueOf(buttonColor)
            connectButton.elevation = dp(if (state == "connected") 12 else 4).toFloat()
            connectButton.contentDescription = when (state) {
                "connected" -> "Отключить VPN. Звезда активна"
                "connecting" -> "Подключение VPN. Звезда загорается"
                "switching" -> "Переключение сервера"
                "disconnecting" -> "Отключение VPN выполняется"
                else -> "Подключить VPN. Звезда неактивна"
            }
            if (state == "connected") {
                connectButton.animate().cancel()
                connectButton.scaleX = 0.94f
                connectButton.scaleY = 0.94f
                connectButton.animate().scaleX(1.07f).scaleY(1.07f).setDuration(220L)
                    .withEndAction {
                        connectButton.animate().scaleX(1f).scaleY(1f).setDuration(360L)
                            .withEndAction { startStarPulse() }
                            .start()
                    }.start()
            } else {
                stopStarPulse()
                connectButton.animate().cancel()
                connectButton.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(180L).start()
            }
        }
        updateConnectButtonServerHealth()
        if (::connectionIndicator.isInitialized) {
            val indicatorColor = when (state) {
                "connected" -> GREEN
                "connecting" -> AMBER
                "switching" -> 0xFF9AA8FF.toInt()
                "disconnecting" -> AMBER
                else -> 0xFF71809D.toInt()
            }
            connectionIndicator.background = rounded(indicatorColor, 99)
            connectionIndicator.contentDescription = when (state) {
                "connected" -> "VPN подключён"
                "connecting" -> "VPN подключается"
                "switching" -> "VPN переключает сервер"
                else -> "VPN отключён"
            }
        }
    }

    /**
     * Selection uses Astra gold; green is reserved for the live VPN tunnel.
     * This keeps "chosen next" visually distinct from "currently connected".
     */
    private fun serverRowBackground(selected: Boolean, active: Boolean) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        when {
            selected && active -> intArrayOf(0xFF30291B.toInt(), 0xFF1C2630.toInt())
            selected -> intArrayOf(0xFF29251C.toInt(), 0xFF171F2D.toInt())
            active -> intArrayOf(0xFF193B3A.toInt(), 0xFF142B30.toInt())
            else -> intArrayOf(0xFF171F30.toInt(), 0xFF121A29.toInt())
        }
    ).apply {
        cornerRadius = dp(18).toFloat()
        val outline = when {
            selected && active -> 0xFFD6B36A.toInt()
            selected -> 0xFF9E824B.toInt()
            active -> 0xFF55B99A.toInt()
            else -> 0xFF26344D.toInt()
        }
        setStroke(dp(if (selected || active) 2 else 1), outline)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        cancelConnectRetry(resetAttempts = false)
        tunnelHealthCheckGeneration.advance()
        stopStarPulse()
        pendingBackupBytes?.fill(0)
        pendingBackupBytes = null
        autoUpdateHandler.removeCallbacks(autoUpdateTick)
        searchRenderHandler.removeCallbacks(searchRenderTask)
        monitorHandler.removeCallbacks(monitorTick)
        outboundTrafficRefresh = null
        if (activeThroughputSessionId != null || throughputBatchActive) {
            cancelThroughputProbe(showToast = false, refreshUi = false)
        }
        throughputBatchHandler.removeCallbacksAndMessages(null)
        if (throughputReceiverRegistered) {
            runCatching { unregisterReceiver(throughputReceiver) }
            throughputReceiverRegistered = false
        }
        if (receiverRegistered) {
            runCatching { unregisterReceiver(vpnStateReceiver) }
            receiverRegistered = false
        }
        monitorExecutor.shutdownNow()
        importExecutor.shutdownNow()
        latencyPreparationTask?.cancel(true)
        latencyPreparationTask = null
        pingExecutor.shutdownNow()
        autoSelectExecutor.shutdownNow()
        healthCheckExecutor.shutdownNow()
        diagnosticsExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_TILE_TOGGLE = "com.astra.vpn.action.TILE_TOGGLE"
        private const val REQUEST_IMPORT_FILE = 7004
        private const val REQUEST_EXPORT_BACKUP = 7005
        private const val REQUEST_IMPORT_BACKUP = 7006
        private const val REQUEST_VPN_PERMISSION = 7001
        private const val REQUEST_NOTIFICATION_PERMISSION = 7002
        private const val REQUEST_PHONE_STATE_PERMISSION = 7003
        private const val LATENCY_PREFS = "astra_latency_results"
        private const val KEY_LATENCY_PROFILE_MAP = "profile_map"
        private const val KEY_LATENCY_SESSION_ID = "active_session_id"
        private const val KEY_LATENCY_PENDING_RESULTS = "pending_results"
        private const val KEY_LATENCY_LAST_RESULTS = "last_results"
        private const val KEY_LATENCY_LAST_RESULTS_AT = "last_results_at"
        private const val KEY_VPN_CONNECTED_SINCE = "vpn_connected_since_wall"
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
