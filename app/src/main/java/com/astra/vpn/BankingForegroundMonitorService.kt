package com.astra.vpn

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * User-started foreground observer and executor for conservative banking transitions.
 * It never guesses on ambiguous UsageEvents and persists state before issuing VPN commands.
 */
internal class BankingForegroundMonitorService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val activeActivities = linkedSetOf<String>()
    private val observationPolicy = BankingForegroundObservationPolicy()
    private var lastQueryTime = 0L
    private var running = false

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            val settings = getSharedPreferences("astra_settings", MODE_PRIVATE)
            if (!settings.getBoolean("banking_auto_pause_enabled", false) ||
                !UsageAccessPermission.hasAccess(this@BankingForegroundMonitorService)
            ) {
                cancelAutomationAndStop()
                return
            }
            val observed = runCatching { readForegroundPackage() }
                .onFailure { Log.w(TAG, "Usage events unavailable", it) }
                .getOrNull()
            // Require two consecutive non-ambiguous observations before publishing
            // a foreground change. Null means unknown (transition, split-screen,
            // missing Usage Access), never proof that a banking app was closed.
            observationPolicy.observe(observed)?.let { stablePackage ->
                val observedAt = System.currentTimeMillis()
                sendBroadcast(
                    Intent(ACTION_OBSERVATION)
                        .setPackage(packageName)
                        .putExtra(EXTRA_FOREGROUND_PACKAGE, stablePackage)
                        .putExtra(EXTRA_OBSERVED_AT, observedAt)
                )
                runCatching { executeBankingTransition(stablePackage, observedAt) }
                    .onFailure { Log.e(TAG, "Banking transition failed closed", it) }
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Explicitly disabling the feature cancels any pending automatic resume.
                runCatching {
                    val stateStore = BankingAutomationStateStore(this)
                    val state = stateStore.read()
                    if (state.autoPausedByBanking) stateStore.write(state.cancelAutomaticResume())
                    ProfileStore(this).clearBankPauseSnapshot()
                }.onFailure { Log.e(TAG, "Не удалось очистить банковскую автопаузу при выключении функции", it) }
                running = false
                handler.removeCallbacksAndMessages(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!UsageAccessPermission.hasAccess(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                if (!running) {
                    running = true
                    lastQueryTime = (System.currentTimeMillis() - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
                    handler.post(poll)
                }
                return START_STICKY
            }
            null -> {
                val enabled = getSharedPreferences("astra_settings", MODE_PRIVATE)
                    .getBoolean("banking_auto_pause_enabled", false)
                if (!enabled || !UsageAccessPermission.hasAccess(this)) return START_NOT_STICKY
                startAsForeground()
                running = true
                lastQueryTime = (System.currentTimeMillis() - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
                handler.post(poll)
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    /**
     * If the user disables automation or revokes Usage Access, fail closed:
     * cancel any pending resume, erase the saved bank snapshot and leave VPN off.
     */
    private fun cancelAutomationAndStop() {
        runCatching {
            val stateStore = BankingAutomationStateStore(this)
            val state = stateStore.read()
            if (state.autoPausedByBanking) {
                stateStore.write(state.cancelAutomaticResume())
            }
            ProfileStore(this).clearBankPauseSnapshot()
        }.onFailure { Log.e(TAG, "Не удалось отменить банковскую автоматизацию", it) }
        running = false
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun executeBankingTransition(foregroundPackage: String, observedAt: Long) {
        val ledger = BankingAutomationCommandLedger(this)
        ledger.clearTerminal()
        val settings = getSharedPreferences("astra_settings", MODE_PRIVATE)
        if (!settings.getBoolean("banking_auto_pause_enabled", false) ||
            !UsageAccessPermission.hasAccess(this)
        ) return
        val banks = settings.getStringSet("banking_app_packages", emptySet())
            ?.toSet().orEmpty()
        if (banks.isEmpty()) return

        val vpnPrefs = getSharedPreferences(AstraVpnService.STATE_PREFS, MODE_PRIVATE)
        val persistedVpnState = vpnPrefs.getString(AstraVpnService.PREF_STATE, null)
        // Do not enqueue another resume while a previous automatic start is in flight.
        // The tunnel stop path publishes DISCONNECTED; a new start publishes CONNECTING.
        if (persistedVpnState == AstraVpnService.STATE_CONNECTING) return
        val persistedConnected = persistedVpnState == AstraVpnService.STATE_CONNECTED
        // A persisted service state can outlive a killed process or revoked TUN.
        // Require Android's live VPN transport as an independent confirmation.
        val activeNetwork = getSystemService(ConnectivityManager::class.java)?.activeNetwork
        val vpnConnected = persistedConnected && activeNetwork?.let { network ->
            getSystemService(ConnectivityManager::class.java)
                ?.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } == true
        val profileStore = ProfileStore(this)
        val snapshot = profileStore.bankPauseSnapshot()
        val activeLink = profileStore.activeTunnelProfile()
        val stateStore = BankingAutomationStateStore(this)
        val state = stateStore.read()
        val now = System.currentTimeMillis()

        // Reconcile a write-ahead command before asking the transition engine for a new one.
        // Never replay it after the originating app leaves foreground.
        val pending = ledger.read()?.takeIf { it.status == BankingAutomationCommandRecord.Status.PENDING }
        if (pending != null) {
            val bankOrigin = pending.originatingPackage in banks
            val sameOrigin = foregroundPackage == pending.originatingPackage
            val sameTransition = state.autoPausedByBanking && state.transitionId == pending.transitionId
            if (!sameOrigin || !sameTransition ||
                (pending.command == BankingAutomationCommandRecord.Command.PAUSE && !bankOrigin) ||
                (pending.command == BankingAutomationCommandRecord.Command.RESUME && bankOrigin)
            ) {
                ledger.cancel(pending.transitionId, pending.command)
                return
            }
            when (pending.command) {
                BankingAutomationCommandRecord.Command.PAUSE -> {
                    // If the tunnel is already down, do not replay a pause. The next
                    // foreground transition will decide whether it is safe to resume.
                    if (!vpnConnected) {
                        ledger.cancel(pending.transitionId, pending.command)
                        return
                    }
                    val exactSnapshot = snapshot?.profileLink
                    if (exactSnapshot.isNullOrBlank() || exactSnapshot != activeLink ||
                        exactSnapshot !in profileStore.all()
                    ) {
                        ledger.cancel(pending.transitionId, pending.command)
                        return
                    }
                    startService(Intent(this, AstraVpnService::class.java)
                        .setAction(AstraVpnService.ACTION_AUTO_PAUSE)
                        .putExtra(AstraVpnService.EXTRA_TRANSITION_ID, pending.transitionId))
                    return
                }
                BankingAutomationCommandRecord.Command.RESUME -> {
                    if (vpnConnected) return
                    val exactSnapshot = snapshot?.profileLink
                    if (exactSnapshot.isNullOrBlank() || exactSnapshot !in profileStore.all()) {
                        ledger.cancel(pending.transitionId, pending.command)
                        return
                    }
                    val lastAttempt = settings.getLong(PREF_AUTO_RESUME_LAST_ATTEMPT, 0L)
                    if (!BankingResumeRetryPolicy.shouldAttempt(lastAttempt, now)) return
                    if (!settings.edit().putLong(PREF_AUTO_RESUME_LAST_ATTEMPT, now).commit()) return
                    startForegroundService(Intent(this, AstraVpnService::class.java)
                        .setAction(AstraVpnService.ACTION_AUTO_RESUME)
                        .putExtra(AstraVpnService.EXTRA_PROFILE_LINK, exactSnapshot)
                        .putExtra(AstraVpnService.EXTRA_TRANSITION_ID, pending.transitionId))
                    return
                }
            }
        }

        val plan = BankingAutomationTransitionEngine.evaluate(
            state,
            BankingAutomationTransitionEngine.Input(
                foregroundPackage = foregroundPackage,
                bankPackages = banks,
                vpnConnected = vpnConnected,
                usageAccessGranted = true,
                observationAtEpochMillis = observedAt,
                nowEpochMillis = now,
                savedProfileAvailable = if (state.autoPausedByBanking) snapshot != null
                    else !activeLink.isNullOrBlank() && activeLink in profileStore.all()
            )
        )

        when (plan.command) {
            BankingAutomationTransitionEngine.Command.SAVE_PROFILE_AND_PAUSE -> {
                val link = activeLink ?: return
                // Commit encrypted exact profile first, then durable transition state,
                // then issue the service command. Any failure leaves the tunnel untouched.
                profileStore.setBankPauseSnapshot(link, now)
                stateStore.write(plan.nextState)
                val gate = BankingVpnCommandPolicy.decide(
                    BankingVpnCommandPolicy.Input(
                        requestedPause = true, requestedResume = false,
                        vpnConnected = vpnConnected,
                        activeProfileLink = link,
                        activeProfileExists = link in profileStore.all(),
                        snapshotProfileLink = profileStore.bankPauseSnapshot()?.profileLink,
                        snapshotProfileExists = profileStore.bankPauseSnapshot() != null,
                        // Gate validates the pre-transition state; the durable next state
                        // was already committed above for the VPN service's independent guard.
                        state = state,
                        observationFresh = now >= observedAt &&
                            now - observedAt <= BankingAutomationTransitionEngine.MAX_OBSERVATION_AGE_MS,
                        foregroundPackage = foregroundPackage,
                        bankPackages = banks,
                        usageAccessGranted = true
                    )
                )
                if (gate == BankingVpnCommandPolicy.Command.PAUSE_ACTIVE_TUNNEL) {
                    // A new bank-entry episode must not inherit a cooldown from an
                    // earlier failed resume attempt.
                    settings.edit().remove(PREF_AUTO_RESUME_LAST_ATTEMPT).commit()
                    val command = BankingAutomationCommandRecord(plan.nextState.transitionId, BankingAutomationCommandRecord.Command.PAUSE, foregroundPackage, BankingAutomationCommandRecord.Status.PENDING)
                    if (!ledger.writePending(command)) return
                    startService(
                        Intent(this, AstraVpnService::class.java)
                            .setAction(AstraVpnService.ACTION_AUTO_PAUSE)
                            .putExtra(AstraVpnService.EXTRA_TRANSITION_ID, plan.nextState.transitionId)
                    )
                } else {
                    stateStore.write(state)
                    profileStore.clearBankPauseSnapshot()
                }
            }
            BankingAutomationTransitionEngine.Command.RESUME_SAVED_PROFILE -> {
                val saved = snapshot?.profileLink ?: return
                val gate = BankingVpnCommandPolicy.decide(
                    BankingVpnCommandPolicy.Input(
                        requestedPause = false, requestedResume = true,
                        vpnConnected = vpnConnected,
                        activeProfileLink = activeLink,
                        activeProfileExists = activeLink != null && activeLink in profileStore.all(),
                        snapshotProfileLink = saved,
                        snapshotProfileExists = saved in profileStore.all(),
                        state = state,
                        observationFresh = now >= observedAt &&
                            now - observedAt <= BankingAutomationTransitionEngine.MAX_OBSERVATION_AGE_MS,
                        foregroundPackage = foregroundPackage,
                        bankPackages = banks,
                        usageAccessGranted = true
                    )
                )
                if (gate == BankingVpnCommandPolicy.Command.RESUME_SNAPSHOT) {
                    val lastAttempt = settings.getLong(PREF_AUTO_RESUME_LAST_ATTEMPT, 0L)
                    if (!BankingResumeRetryPolicy.shouldAttempt(lastAttempt, now)) return
                    // Commit before dispatch: if Android rejects the service start,
                    // the next stable observation retries after a bounded cooldown.
                    if (!settings.edit()
                            .putLong(PREF_AUTO_RESUME_LAST_ATTEMPT, now)
                            .commit()
                    ) return
                    val command = BankingAutomationCommandRecord(state.transitionId, BankingAutomationCommandRecord.Command.RESUME, foregroundPackage, BankingAutomationCommandRecord.Status.PENDING)
                    if (!ledger.writePending(command)) return
                    startForegroundService(
                        Intent(this, AstraVpnService::class.java)
                            .setAction(AstraVpnService.ACTION_AUTO_RESUME)
                            .putExtra(AstraVpnService.EXTRA_PROFILE_LINK, saved)
                            .putExtra(AstraVpnService.EXTRA_TRANSITION_ID, state.transitionId)
                    )
                }
            }
            BankingAutomationTransitionEngine.Command.CLEAR_SNAPSHOT -> {
                stateStore.write(plan.nextState)
                profileStore.clearBankPauseSnapshot()
            }
            BankingAutomationTransitionEngine.Command.NONE -> Unit
        }
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Astra VPN · защита банковских приложений")
            .setContentText("Мониторинг приложений включён")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun readForegroundPackage(): String? {
        if (!UsageAccessPermission.hasAccess(this)) return null
        val manager = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val begin = (lastQueryTime - 1L).coerceAtLeast(0L)
        val events = manager.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName?.takeIf { it.isNotBlank() } ?: continue
            val activity = "$pkg/${event.className.orEmpty()}"
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> activeActivities.add(activity)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> activeActivities.remove(activity)
            }
        }
        lastQueryTime = now
        // Multi-window / split-screen can have more than one resumed package.
        // Returning null is safer than guessing which app is actually foreground.
        return activeActivities.map { it.substringBefore('/') }.distinct().singleOrNull()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Банковская защита",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Показывает, когда включён мониторинг банковских приложений." }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.banking_monitor.START"
        const val ACTION_STOP = "com.astra.vpn.banking_monitor.STOP"
        const val ACTION_OBSERVATION = "com.astra.vpn.banking_monitor.OBSERVATION"
        const val EXTRA_FOREGROUND_PACKAGE = "foreground_package"
        const val EXTRA_OBSERVED_AT = "observed_at"
        private const val TAG = "AstraBankMonitor"
        private const val CHANNEL_ID = "astra_banking_monitor"
        private const val NOTIFICATION_ID = 43
        private const val POLL_INTERVAL_MS = 1_000L
        private const val INITIAL_LOOKBACK_MS = 60_000L
        private const val PREF_AUTO_RESUME_LAST_ATTEMPT = "banking_auto_resume_last_attempt_ms"

        fun start(context: Context) {
            val intent = Intent(context, BankingForegroundMonitorService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BankingForegroundMonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/** Usage Access is a user-granted AppOps capability, not a runtime permission dialog. */
internal object UsageAccessPermission {
    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
