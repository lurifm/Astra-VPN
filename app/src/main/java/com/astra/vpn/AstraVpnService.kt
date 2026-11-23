package com.astra.vpn

import android.content.Intent
import android.app.NotificationManager
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONObject
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus

class AstraVpnService : VpnService(), CommandServerHandler {
    private var commandServer: CommandServer? = null
    private var trafficMonitor: LibboxTrafficMonitor? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var activeConfig: String? = null
    // TUN creation may callback before libbox confirms startOrReloadService().
    private var configurationAccepted = false
    private val recoveryProfileTransaction = VpnRecoveryProfileTransaction()
    private var activeProfileName: String = "Сервер не выбран"
    // Exact URI supplied with the accepted start request; used only for banking snapshot matching.
    private var activeProfileLink: String? = null
    private var bankingAutoResumePending = false
    private var bankingAutoResumeTransitionId: Long? = null
    private var activeProtocol: String = "—"
    private var lastHistoryState: String? = null
    // Prevent callbacks from closeService() from re-entering stopTunnel().
    private var stopping = false
    // Shared across service recreation in this app process so generation IDs never go backwards.
    private val startRequestGeneration = processStartRequestGeneration
    // Reject TUN callbacks originating from a CommandServer instance that has been stopped.
    private val lifecycleGeneration = ServiceLifecycleGeneration()
    private val lifecycleLock = Any()
    // Serialize libbox configuration transitions; OpenTun is invoked synchronously
    // from startOrReloadService and must be attributed to that exact request.
    private val tunOperationLock = Any()
    private var activeTunRequestToken: Long? = null
    private var tunReadyRequestToken: Long? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // A distinct token is required for every TUN wait, including reloads that
    // reuse the same CommandServer and therefore the same lifecycle generation.
    private val tunTimeoutGeneration = TunCreationTimeoutGeneration()
    private var tunTimeout: Runnable? = null

    private fun awaitTunCreation(requestToken: Long) = synchronized(lifecycleLock) {
        cancelTunTimeoutLocked()
        val ownerToken = tunTimeoutGeneration.next()
        val timeout = Runnable {
            synchronized(lifecycleLock) {
                if (!tunTimeoutGeneration.isCurrent(ownerToken)) return@synchronized
                tunTimeout = null
                if (!stopping && startRequestGeneration.isCurrent(requestToken) && commandServer != null &&
                    !TunRequestReadinessPolicy.isReady(
                        configurationAccepted = configurationAccepted,
                        descriptorOwned = tunDescriptor != null,
                        descriptorRequestToken = tunReadyRequestToken,
                        requestToken = requestToken
                    )
                ) {
                    val message = "sing-box не создал VPN-интерфейс за отведённое время"
                    Log.e(TAG, message)
                    stopTunnel()
                    publishState(STATE_ERROR, message, retryable = true)
                }
            }
        }
        tunTimeout = timeout
        mainHandler.postDelayed(timeout, TUN_TIMEOUT_MS)
    }

    private fun cancelTunTimeoutLocked() {
        tunTimeoutGeneration.invalidate()
        tunTimeout?.let(mainHandler::removeCallbacks)
        tunTimeout = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_RECOVERY) {
            val enabled = getSharedPreferences("astra_settings", MODE_PRIVATE)
                .getBoolean(PREF_RECOVERY_ENABLED, false)
            val link = intent.getStringExtra(EXTRA_PROFILE_LINK)
            // Stage/cancel the recovery update and decide the restart mode in one
            // critical section. A concurrent ACTION_START must not flip stopping
            // between the lifecycle check and the sticky decision.
            val keepSticky = synchronized(lifecycleLock) {
                val currentRequest = startRequestGeneration.current()
                if (!enabled) {
                    recoveryProfileTransaction.cancel()
                    persistRecoveryProfile(null)
                } else if (stopping) {
                    recoveryProfileTransaction.cancel()
                } else if (commandServer != null && TunRequestReadinessPolicy.isReady(
                        configurationAccepted = configurationAccepted,
                        descriptorOwned = tunDescriptor != null,
                        descriptorRequestToken = tunReadyRequestToken,
                        requestToken = currentRequest
                    )
                ) {
                    recoveryProfileTransaction.cancel()
                    persistRecoveryProfile(link)
                } else {
                    recoveryProfileTransaction.stage(link)
                }
                VpnRecoveryPolicy.shouldKeepServiceStickyForUpdate(
                    recoveryEnabled = enabled,
                    stopping = stopping
                )
            }
            return if (keepSticky) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action == ACTION_AUTO_PAUSE) {
            // The executor must commit both encrypted snapshot and transition state first.
            // Never infer a resumable pause from the service request alone.
            val requestedTransitionId = intent.getLongExtra(EXTRA_TRANSITION_ID, -1L)
            val accepted = synchronized(lifecycleLock) {
                val state = runCatching { BankingAutomationStateStore(this).read() }.getOrNull()
                val snapshot = runCatching { ProfileStore(this).bankPauseSnapshot() }.getOrNull()
                val command = runCatching { BankingAutomationCommandLedger(this).read() }.getOrNull()
                val allowed = !stopping && commandServer != null && activeConfig != null &&
                    configurationAccepted && tunDescriptor != null &&
                    state != null &&
                    BankingAutomationCommandTokenPolicy.matches(requestedTransitionId, state) &&
                    command != null &&
                    command.transitionId == requestedTransitionId &&
                    command.command == BankingAutomationCommandRecord.Command.PAUSE &&
                    command.status == BankingAutomationCommandRecord.Status.PENDING &&
                    !activeProfileLink.isNullOrBlank() &&
                    snapshot?.profileLink == activeProfileLink
                if (!allowed) false else {
                    // Revalidation and tunnel stop share lifecycleLock with manual START/STOP.
                    stopTunnel()
                    check(BankingAutomationCommandLedger(this).complete(
                        requestedTransitionId, BankingAutomationCommandRecord.Command.PAUSE
                    ))
                    true
                }
            }
            if (!accepted) Log.w(TAG, "Banking auto-pause rejected: lifecycle or durable intent changed")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_AUTO_RESUME) {
            val requestedLink = intent.getStringExtra(EXTRA_PROFILE_LINK)
            val snapshot = runCatching { ProfileStore(this).bankPauseSnapshot() }.getOrNull()
            val state = runCatching { BankingAutomationStateStore(this).read() }.getOrNull()
            val requestedTransitionId = intent.getLongExtra(EXTRA_TRANSITION_ID, -1L)
            val command = runCatching { BankingAutomationCommandLedger(this).read() }.getOrNull()
            val allowed = UsageAccessPermission.hasAccess(this) &&
                state != null &&
                BankingAutomationCommandTokenPolicy.matches(requestedTransitionId, state) &&
                command != null &&
                command.transitionId == requestedTransitionId &&
                command.command == BankingAutomationCommandRecord.Command.RESUME &&
                command.status == BankingAutomationCommandRecord.Status.PENDING &&
                !requestedLink.isNullOrBlank() &&
                snapshot?.profileLink == requestedLink &&
                runCatching { requestedLink in ProfileStore(this).all() }.getOrDefault(false) &&
                VpnService.prepare(this) == null
            if (!allowed) {
                Log.w(TAG, "Banking auto-resume rejected: state, permission or exact snapshot unavailable")
                return START_NOT_STICKY
            }
            val config = runCatching { SingBoxConfigBuilder.build(requestedLink!!) }.getOrNull()
            if (config.isNullOrBlank()) {
                Log.w(TAG, "Banking auto-resume rejected: saved profile configuration invalid")
                return START_NOT_STICKY
            }
            val staged = synchronized(lifecycleLock) {
                val currentState = runCatching { BankingAutomationStateStore(this).read() }.getOrNull()
                val currentSnapshot = runCatching { ProfileStore(this).bankPauseSnapshot() }.getOrNull()
                val currentCommand = runCatching { BankingAutomationCommandLedger(this).read() }.getOrNull()
                val stillCurrent = !stopping &&
                    currentState != null &&
                    BankingAutomationCommandTokenPolicy.matches(requestedTransitionId, currentState) &&
                    currentCommand?.transitionId == requestedTransitionId &&
                    currentCommand.command == BankingAutomationCommandRecord.Command.RESUME &&
                    currentCommand.status == BankingAutomationCommandRecord.Status.PENDING &&
                    currentSnapshot?.profileLink == requestedLink
                if (stillCurrent) {
                    bankingAutoResumePending = true
                    bankingAutoResumeTransitionId = requestedTransitionId
                }
                stillCurrent
            }
            if (!staged) {
                Log.w(TAG, "Banking auto-resume rejected: durable transition changed before staging")
                return START_NOT_STICKY
            }
            return onStartCommand(
                Intent(intent)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CONFIG, config)
                    .putExtra(EXTRA_PROFILE_LINK, requestedLink)
                    .putExtra(EXTRA_TRANSITION_ID, requestedTransitionId)
                    .putExtra(EXTRA_BANKING_AUTO_RESUME, true),
                flags,
                startId
            )
        }
        if (intent?.action == ACTION_STOP) {
            bankingAutoResumePending = false
            bankingAutoResumeTransitionId = null
            // Serialize the durable manual override, snapshot invalidation and tunnel stop
            // with automatic pause/resume command acceptance.
            synchronized(lifecycleLock) {
                bankingAutoResumePending = false
                bankingAutoResumeTransitionId = null
                runCatching {
                    val stateStore = BankingAutomationStateStore(this)
                    val state = stateStore.read()
                    if (state.autoPausedByBanking) stateStore.write(state.onManualDisconnect())
                    val ledger = BankingAutomationCommandLedger(this)
                    ledger.read()?.takeIf { it.status == BankingAutomationCommandRecord.Status.PENDING }
                        ?.let { ledger.cancel(it.transitionId, it.command) }
                    ProfileStore(this).clearBankPauseSnapshot()
                    ProfileStore(this).setActiveTunnelProfile(null)
                }.onFailure { Log.w(TAG, "Не удалось отменить банковское восстановление при ручном отключении", it) }
                recoveryProfileTransaction.cancel()
                runCatching { ProfileStore(this).setRecoveryProfile(null) }
                    .onFailure { Log.w(TAG, "Не удалось очистить профиль восстановления") }
                stopTunnel()
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START) {
            val suppliedTransitionId = intent.getLongExtra(EXTRA_TRANSITION_ID, -1L)
            val internalAutoResume = intent.getBooleanExtra(EXTRA_BANKING_AUTO_RESUME, false)
            if (internalAutoResume) {
                // A queued internal resume is never reinterpreted as a manual connect.
                // Revalidate after staging: a manual STOP may have won the lock in between.
                val stillAllowed = synchronized(lifecycleLock) {
                    val state = runCatching { BankingAutomationStateStore(this).read() }.getOrNull()
                    val snapshot = runCatching { ProfileStore(this).bankPauseSnapshot() }.getOrNull()
                    val command = runCatching { BankingAutomationCommandLedger(this).read() }.getOrNull()
                    !stopping &&
                        bankingAutoResumePending &&
                        suppliedTransitionId == bankingAutoResumeTransitionId &&
                        state != null &&
                        BankingAutomationCommandTokenPolicy.matches(suppliedTransitionId, state) &&
                        command?.transitionId == suppliedTransitionId &&
                        command.command == BankingAutomationCommandRecord.Command.RESUME &&
                        command.status == BankingAutomationCommandRecord.Status.PENDING &&
                        snapshot?.profileLink == intent.getStringExtra(EXTRA_PROFILE_LINK) &&
                        UsageAccessPermission.hasAccess(this) &&
                        VpnService.prepare(this) == null
                }
                if (!stillAllowed) {
                    Log.w(TAG, "Discarding stale internal banking resume after lifecycle revalidation")
                    return START_NOT_STICKY
                }
            } else {
                // A manual connect is an explicit override of any pending banking pause/resume.
                // Persist the new transition before the new tunnel request can race monitor work.
                val accepted = synchronized(lifecycleLock) {
                    runCatching {
                        val stateStore = BankingAutomationStateStore(this)
                        stateStore.write(stateStore.read().onManualConnect())
                        val ledger = BankingAutomationCommandLedger(this)
                        ledger.read()?.takeIf { it.status == BankingAutomationCommandRecord.Status.PENDING }
                            ?.let { ledger.cancel(it.transitionId, it.command) }
                        ProfileStore(this).clearBankPauseSnapshot()
                        bankingAutoResumePending = false
                        bankingAutoResumeTransitionId = null
                    }.isSuccess
                }
                if (!accepted) {
                    Log.e(TAG, "Manual VPN start rejected: banking override could not be persisted")
                    return START_NOT_STICKY
                }
            }
        }

        val requestToken = synchronized(lifecycleLock) {
            stopping = false
            val token = startRequestGeneration.next()
            // A newly selected profile invalidates readiness from the previous one
            // immediately, even while its libbox operation is still unwinding.
            configurationAccepted = false
            tunReadyRequestToken = null
            activeProfileLink = intent?.getStringExtra(EXTRA_PROFILE_LINK)?.takeIf { it.isNotBlank() }
                ?: activeProfileLink
            activeProfileName = intent?.getStringExtra(EXTRA_PROFILE_NAME)
                ?.takeIf { it.isNotBlank() } ?: activeProfileName
            activeProtocol = intent?.getStringExtra(EXTRA_PROTOCOL)
                ?.takeIf { it.isNotBlank() } ?: activeProtocol
            if (intent?.action == ACTION_START) lastHistoryState = null
            token
        }

        startForeground(
            NOTIFICATION_ID,
            AstraNotification.create(this, STATE_CONNECTING, activeProfileName)
        )

        val recoveryEnabled = getSharedPreferences("astra_settings", MODE_PRIVATE)
            .getBoolean(PREF_RECOVERY_ENABLED, false)
        val restartMode = if (recoveryEnabled) START_STICKY else START_NOT_STICKY
        val config = if (intent == null) {
            val savedProfile = runCatching { ProfileStore(this).recoveryProfile() }
                .onFailure { Log.w(TAG, "Не удалось прочитать зашифрованный профиль восстановления") }
                .getOrNull()
            val decision = VpnRecoveryPolicy.decide(
                trigger = VpnRecoveryTrigger.SYSTEM_PROCESS_RESTART,
                recoveryEnabled = recoveryEnabled,
                userRequestedDisconnect = getSharedPreferences("astra_settings", MODE_PRIVATE)
                    .getBoolean("vpn_disconnect_requested", false),
                savedProfileAvailable = !savedProfile.isNullOrBlank(),
                vpnConsentGranted = VpnService.prepare(this) == null
            )
            when (decision) {
                VpnRecoveryDecision.START -> {
                    activeProfileName = "Восстановление VPN"
                    activeProtocol = "—"
                    runCatching { SingBoxConfigBuilder.build(savedProfile!!) }.getOrElse {
                        failStartRequest(requestToken, "Не удалось восстановить конфигурацию VPN")
                        return START_NOT_STICKY
                    }
                }
                VpnRecoveryDecision.REQUIRE_VPN_CONSENT -> {
                    failStartRequest(requestToken, "Открой Astra VPN и повторно подтверди системное разрешение VPN")
                    return START_NOT_STICKY
                }
                else -> {
                    stopTunnel()
                    return START_NOT_STICKY
                }
            }
        } else {
            intent.getStringExtra(EXTRA_CONFIG)
        }
        if (config.isNullOrBlank()) {
            failStartRequest(requestToken, "Не передана конфигурация sing-box")
            return START_NOT_STICKY
        }

        synchronized(lifecycleLock) {
            if (intent != null && recoveryEnabled) {
                // Keep the previous known-good recovery profile until TUN creation succeeds.
                recoveryProfileTransaction.stage(intent.getStringExtra(EXTRA_PROFILE_LINK))
            } else if (!recoveryEnabled) {
                recoveryProfileTransaction.cancel()
                persistRecoveryProfile(null)
            }
        }

        val existingServer = synchronized(lifecycleLock) {
            if (stopping || !startRequestGeneration.isCurrent(requestToken)) return START_NOT_STICKY
            commandServer
        }

        if (existingServer != null) {
            // A stop callback or another lifecycle transition may invalidate this
            // request after the server was captured above. Recheck immediately
            // before entering libbox so an already-obsolete request is not sent.
            val requestStillActive = synchronized(lifecycleLock) {
                !stopping &&
                    startRequestGeneration.isCurrent(requestToken) &&
                    commandServer === existingServer
            }
            if (!requestStillActive) return START_NOT_STICKY

            runCatching { runLibboxConfiguration(existingServer, config, requestToken) }
                .onSuccess {
                    synchronized(lifecycleLock) {
                        if (stopping || !startRequestGeneration.isCurrent(requestToken) ||
                            commandServer !== existingServer
                        ) return@synchronized
                        activeConfig = config
                        configurationAccepted = true
                        startTrafficMonitor()
                        if (TunRequestReadinessPolicy.isReady(
                                configurationAccepted = configurationAccepted,
                                descriptorOwned = tunDescriptor != null,
                                descriptorRequestToken = tunReadyRequestToken,
                                requestToken = requestToken
                            )
                        ) {
                            cancelTunTimeoutLocked()
                            commitRecoveryProfileLocked()
                            runCatching { ProfileStore(this).setActiveTunnelProfile(activeProfileLink) }
                                .onFailure { Log.e(TAG, "Не удалось сохранить активный профиль для банковской защиты", it) }
                            publishState(STATE_CONNECTED)
                            completeBankingAutoResumeLocked()
                        } else {
                            publishState(STATE_CONNECTING)
                            awaitTunCreation(requestToken)
                        }
                    }
                }
                .onFailure { error ->
                    failStartRequest(
                        requestToken,
                        "Не удалось перезапустить VPN. Проверь выбранный сервер и подключение.",
                        error
                    )
                }
            return restartMode
        }

        val ownerGeneration: Long
        val server: CommandServer
        try {
            synchronized(lifecycleLock) {
                if (stopping || !startRequestGeneration.isCurrent(requestToken)) return START_NOT_STICKY
                ownerGeneration = lifecycleGeneration.advance()
                val platform = AstraPlatform(this, onTunCreated = { descriptor ->
                    handleTunCreated(descriptor, ownerGeneration)
                })
                server = CommandServer(this, platform)
                commandServer = server
            }
            server.start()
            val requestStillActive = synchronized(lifecycleLock) {
                !stopping && startRequestGeneration.isCurrent(requestToken) && commandServer === server
            }
            if (!requestStillActive) return START_NOT_STICKY
            runLibboxConfiguration(server, config, requestToken)
            synchronized(lifecycleLock) {
                if (stopping || !startRequestGeneration.isCurrent(requestToken) || commandServer !== server) {
                    return START_NOT_STICKY
                }
                activeConfig = config
                configurationAccepted = true
                startTrafficMonitor()
                Log.i(TAG, "sing-box service accepted configuration")
                if (TunRequestReadinessPolicy.isReady(
                        configurationAccepted = configurationAccepted,
                        descriptorOwned = tunDescriptor != null,
                        descriptorRequestToken = tunReadyRequestToken,
                        requestToken = requestToken
                    )
                ) {
                    commitRecoveryProfileLocked()
                    runCatching { ProfileStore(this).setActiveTunnelProfile(activeProfileLink) }
                        .onFailure { Log.e(TAG, "Не удалось сохранить активный профиль для банковской защиты", it) }
                    publishState(STATE_CONNECTED)
                    completeBankingAutoResumeLocked()
                } else {
                    publishState(STATE_CONNECTING)
                    awaitTunCreation(requestToken)
                    Log.w(TAG, "sing-box accepted config; waiting for Android TUN creation")
                }
            }
        } catch (error: Exception) {
            failStartRequest(
                requestToken,
                "Не удалось запустить VPN. Проверь конфигурацию сервера и разрешение на VPN.",
                error
            )
        }
        return restartMode
    }

    private fun failStartRequest(requestToken: Long, userMessage: String, error: Throwable? = null) {
        synchronized(lifecycleLock) {
            if (stopping || !startRequestGeneration.isCurrent(requestToken)) return
            if (error != null) {
                Log.e(TAG, "Ошибка запуска VPN (" + error.javaClass.simpleName + ")")
            } else {
                Log.e(TAG, userMessage)
            }
            stopTunnel()
            publishState(STATE_ERROR, userMessage)
        }
    }

    override fun serviceStop() = stopTunnel()

    override fun serviceReload() {
        // Capture a consistent server/config/request triple, but never hold
        // lifecycleLock while calling into libbox: callbacks also need this lock.
        val reload = synchronized(lifecycleLock) {
            if (stopping) return
            val config = activeConfig ?: return
            val server = commandServer ?: return
            Triple(server, config, startRequestGeneration.current())
        }
        val (server, config, requestToken) = reload
        runCatching { runLibboxConfiguration(server, config, requestToken) }
            .onSuccess {
                synchronized(lifecycleLock) {
                    if (stopping ||
                        !startRequestGeneration.isCurrent(requestToken) ||
                        commandServer !== server ||
                        activeConfig != config
                    ) return@synchronized
                    configurationAccepted = true
                    startTrafficMonitor()
                    if (TunRequestReadinessPolicy.isReady(
                            configurationAccepted = configurationAccepted,
                            descriptorOwned = tunDescriptor != null,
                            descriptorRequestToken = tunReadyRequestToken,
                            requestToken = requestToken
                        )
                    ) {
                        cancelTunTimeoutLocked()
                        publishState(STATE_CONNECTED)
                    } else {
                        publishState(STATE_CONNECTING)
                        awaitTunCreation(requestToken)
                    }
                }
            }
            .onFailure { error ->
                synchronized(lifecycleLock) {
                    if (stopping ||
                        !startRequestGeneration.isCurrent(requestToken) ||
                        commandServer !== server ||
                        activeConfig != config
                    ) return@synchronized
                    Log.e(TAG, "Не удалось перезагрузить конфигурацию sing-box (" + error.javaClass.simpleName + ")")
                    // This is a reload of an already accepted profile, not a new
                    // user-supplied configuration. A transient libbox/network
                    // failure may recover; MainActivity applies its bounded 3s/8s
                    // retry schedule and cancels it on profile change or Disconnect.
                    stopTunnel()
                    publishState(
                        STATE_ERROR,
                        "Не удалось обновить VPN. Проверь конфигурацию сервера.",
                        retryable = true
                    )
                }
            }
    }
    /**
     * OpenTun is called synchronously by libbox while StartOrReloadService is running.
     * Keep the request token active only for that call, so an old TUN descriptor
     * can never satisfy readiness for a replacement profile.
     */
    private fun runLibboxConfiguration(server: CommandServer, config: String, requestToken: Long) {
        synchronized(tunOperationLock) {
            val acceptedForExecution = synchronized(lifecycleLock) {
                if (stopping || !startRequestGeneration.isCurrent(requestToken) || commandServer !== server) {
                    false
                } else {
                    configurationAccepted = false
                    tunReadyRequestToken = null
                    activeTunRequestToken = requestToken
                    publishState(STATE_CONNECTING)
                    true
                }
            }
            check(acceptedForExecution) { "Obsolete VPN configuration request" }
            try {
                server.startOrReloadService(config, OverrideOptions())
            } finally {
                synchronized(lifecycleLock) {
                    if (activeTunRequestToken == requestToken) activeTunRequestToken = null
                }
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        // libbox diagnostics may contain server addresses or other connection details.
        // Keep the callback observable without writing its raw payload to logcat.
        Log.d(TAG, "sing-box diagnostic message received")
    }
    override fun connectSSHAgent(): Int = -1

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        // stopTunnel() already releases every resource and publishes the final
        // state before stopSelf(). Do not run it a second time here: a second
        // DISCONNECTED broadcast could overwrite a preceding ERROR state that
        // the Activity needs to display after a failed connection attempt.
        // Include the traffic subscription explicitly: it owns a worker thread
        // and a libbox CommandClient independently of the TUN/config fields.
        if (commandServer != null || tunDescriptor != null || activeConfig != null ||
            trafficMonitor != null
        ) {
            stopTunnel()
        }
        super.onDestroy()
    }

    private fun handleTunCreated(descriptor: ParcelFileDescriptor, ownerGeneration: Long): Boolean =
        synchronized(lifecycleLock) {
            // Reject, rather than merely close, a descriptor from an obsolete
            // CommandServer. AstraPlatform must not hand a closed fd back to libbox.
            val requestToken = activeTunRequestToken
            if (!TunDescriptorOwnershipPolicy.shouldAccept(
                    stopping = stopping,
                    lifecycleIsCurrent = lifecycleGeneration.isCurrent(ownerGeneration)
                ) || requestToken == null || !startRequestGeneration.isCurrent(requestToken)
            ) {
                return@synchronized false
            }
            tunDescriptor?.let { previous ->
                runCatching { previous.close() }
                    .onFailure { Log.w(TAG, "Previous TUN descriptor close failed (" + it.javaClass.simpleName + ")") }
            }
            tunDescriptor = descriptor
            tunReadyRequestToken = requestToken
            cancelTunTimeoutLocked()
            if (TunRequestReadinessPolicy.isReady(
                    configurationAccepted = configurationAccepted,
                    descriptorOwned = true,
                    descriptorRequestToken = tunReadyRequestToken,
                    requestToken = requestToken
                )
            ) {
                commitRecoveryProfileLocked()
                runCatching { ProfileStore(this).setActiveTunnelProfile(activeProfileLink) }
                    .onFailure { Log.e(TAG, "Не удалось сохранить активный профиль для банковской защиты", it) }
                publishState(STATE_CONNECTED)
                completeBankingAutoResumeLocked()
            }
            true
        }

    private fun stopTunnel(): Unit = synchronized(lifecycleLock) {
        if (stopping) return@synchronized
        stopping = true
        // A failed or user-cancelled start must leave the previous recovery node intact.
        recoveryProfileTransaction.cancel()
        startRequestGeneration.invalidate()
        lifecycleGeneration.advance()
        // Match the upstream sing-box Android lifecycle: release the Android
        // TUN descriptor, then close the service and finally the command server.
        cancelTunTimeoutLocked()
        val server = commandServer
        commandServer = null
        trafficMonitor?.stop()
        trafficMonitor = null
        publishTrafficSnapshot(emptyMap())
        activeConfig = null
        activeProfileLink = null
        configurationAccepted = false
        activeTunRequestToken = null
        tunReadyRequestToken = null

        runCatching { tunDescriptor?.close() }
            .onFailure { Log.w(TAG, "TUN descriptor close failed (" + it.javaClass.simpleName + ")") }
        tunDescriptor = null

        if (server != null) {
            runCatching { server.closeService() }
                .onFailure { Log.w(TAG, "closeService failed (" + it.javaClass.simpleName + ")") }
            runCatching { server.close() }
                .onFailure { Log.w(TAG, "CommandServer close failed (" + it.javaClass.simpleName + ")") }
        }
        publishState(STATE_DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Clear the resumable banking snapshot only after Android confirms the replacement TUN is ready.
     * A failed start intentionally leaves the snapshot/state intact for a safe retry.
     */
    private fun completeBankingAutoResumeLocked() {
        if (!bankingAutoResumePending) return
        val pendingTransitionId = bankingAutoResumeTransitionId
        val store = BankingAutomationStateStore(this)
        val currentState = runCatching { store.read() }.getOrNull()
        if (pendingTransitionId == null || currentState == null ||
            !BankingAutomationCommandTokenPolicy.matches(pendingTransitionId, currentState)
        ) {
            bankingAutoResumePending = false
            bankingAutoResumeTransitionId = null
            Log.w(TAG, "Ignoring stale banking auto-resume completion")
            return
        }
        runCatching {
            store.write(currentState.completeAutomaticResume())
            check(BankingAutomationCommandLedger(this).complete(pendingTransitionId, BankingAutomationCommandRecord.Command.RESUME))
            // If encrypted snapshot cleanup fails, the completed state still prevents another resume.
            ProfileStore(this).clearBankPauseSnapshot()
            getSharedPreferences("astra_settings", MODE_PRIVATE).edit()
                .remove("banking_auto_resume_last_attempt_ms")
                .commit()
        }.onSuccess {
            bankingAutoResumePending = false
            bankingAutoResumeTransitionId = null
        }.onFailure {
            Log.e(TAG, "Не удалось завершить очистку банковского snapshot", it)
        }
    }

    /** Persist a staged recovery node only after the replacement TUN is ready. */
    private fun commitRecoveryProfileLocked() {
        val update = recoveryProfileTransaction.commit() ?: return
        val enabled = getSharedPreferences("astra_settings", MODE_PRIVATE)
            .getBoolean(PREF_RECOVERY_ENABLED, false)
        persistRecoveryProfile(if (enabled) update.profileLink else null)
    }

    private fun persistRecoveryProfile(profileLink: String?) {
        runCatching { ProfileStore(this).setRecoveryProfile(profileLink) }
            .onFailure { Log.w(TAG, "Не удалось сохранить профиль восстановления") }
    }

    private fun startTrafficMonitor() {
        if (trafficMonitor != null) return
        val monitor = LibboxTrafficMonitor(::publishTrafficSnapshot)
        trafficMonitor = monitor
        monitor.start()
    }

    private fun publishTrafficSnapshot(snapshot: Map<String, LibboxTrafficAccumulator.Totals>) {
        val payload = JSONObject()
        snapshot.forEach { (tag, totals) ->
            payload.put(tag, JSONObject()
                .put("uploadBytes", totals.uploadBytes)
                .put("downloadBytes", totals.downloadBytes))
        }
        getSharedPreferences(TRAFFIC_PREFS, MODE_PRIVATE).edit()
            .putString(TRAFFIC_JSON, payload.toString())
            .putLong(TRAFFIC_UPDATED_AT, System.currentTimeMillis())
            .apply()
        sendBroadcast(
            Intent(ACTION_TRAFFIC).setPackage(packageName)
                .putExtra(EXTRA_TRAFFIC_JSON, payload.toString())
        )
    }

    private fun publishState(state: String, error: String? = null, retryable: Boolean = false) {
        // Save the latest state so the Activity can restore its UI after
        // recreation instead of depending on a broadcast it may have missed.
        getSharedPreferences(STATE_PREFS, MODE_PRIVATE).edit()
            .putString(PREF_STATE, state)
            .putString(PREF_ERROR, error)
            .putLong(PREF_UPDATED_AT, System.currentTimeMillis())
            .apply()
        val historyState = when (state) {
            STATE_CONNECTING -> "connecting"
            // A ready TUN is not proof of working internet. MainActivity records
            // "connected" only after its end-to-end health check succeeds.
            STATE_CONNECTED -> null
            STATE_DISCONNECTED -> "disconnected"
            STATE_ERROR -> "error"
            else -> null
        }
        if (historyState != null && historyState != lastHistoryState) {
            val recorded = runCatching {
                ConnectionHistoryStore(this).record(
                    historyState,
                    activeProfileName,
                    activeProtocol,
                    activeProfileLink.orEmpty()
                )
                true
            }.getOrElse {
                Log.w(TAG, "Не удалось сохранить историю подключений (" + it.javaClass.simpleName + ")")
                false
            }
            // Keep the previous marker on failure so a later identical state
            // transition can retry instead of being silently deduplicated.
            if (recorded) lastHistoryState = historyState
        }
        val intent = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_REQUEST_GENERATION, startRequestGeneration.current())
        if (error != null) intent.putExtra(EXTRA_ERROR, error)
        if (retryable) intent.putExtra(EXTRA_RETRYABLE, true)
        sendBroadcast(intent)
        // Activity broadcasts are not guaranteed to be observed while the app
        // process/UI is absent. Refresh launcher and Quick Settings surfaces here
        // from the same authoritative persisted state.
        runCatching { AstraHomeWidgetProvider.refreshAll(this) }
            .onFailure { Log.w(TAG, "Не удалось обновить виджет VPN", it) }
        AstraQuickSettingsTileService.requestSync(this)
        runCatching {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(
                NOTIFICATION_ID,
                AstraNotification.create(this, state, activeProfileName, error)
            )
        }.onFailure { Log.w(TAG, "Не удалось обновить уведомление VPN (" + it.javaClass.simpleName + ")") }
    }

    companion object {
        const val ACTION_START = "com.astra.vpn.action.START"
        const val ACTION_STOP = "com.astra.vpn.action.STOP"
        const val ACTION_AUTO_PAUSE = "com.astra.vpn.action.AUTO_PAUSE"
        const val ACTION_AUTO_RESUME = "com.astra.vpn.action.AUTO_RESUME"
        private const val EXTRA_BANKING_AUTO_RESUME = "banking_auto_resume_internal"
        const val EXTRA_TRANSITION_ID = "com.astra.vpn.extra.BANKING_TRANSITION_ID"
        const val ACTION_UPDATE_RECOVERY = "com.astra.vpn.action.UPDATE_RECOVERY"
        const val ACTION_STATE = "com.astra.vpn.action.STATE"
        const val ACTION_TRAFFIC = "com.astra.vpn.action.TRAFFIC"
        const val EXTRA_TRAFFIC_JSON = "com.astra.vpn.extra.TRAFFIC_JSON"
        const val TRAFFIC_PREFS = "astra_libbox_traffic"
        const val TRAFFIC_JSON = "outbound_traffic_json"
        const val TRAFFIC_UPDATED_AT = "updated_at"
        const val EXTRA_CONFIG = "com.astra.vpn.extra.CONFIG"
        const val EXTRA_PROFILE_LINK = "com.astra.vpn.extra.PROFILE_LINK"
        private const val PREF_RECOVERY_ENABLED = "vpn_recovery_enabled"
        const val EXTRA_PROFILE_NAME = "com.astra.vpn.extra.PROFILE_NAME"
        const val EXTRA_PROTOCOL = "com.astra.vpn.extra.PROTOCOL"
        const val EXTRA_STATE = "com.astra.vpn.extra.STATE"
        const val EXTRA_REQUEST_GENERATION = "com.astra.vpn.extra.REQUEST_GENERATION"
        const val EXTRA_ERROR = "com.astra.vpn.extra.ERROR"
        const val EXTRA_RETRYABLE = "com.astra.vpn.extra.RETRYABLE"
        const val STATE_CONNECTED = "connected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
        const val STATE_PREFS = "astra_vpn_state"
        const val PREF_STATE = "state"
        const val PREF_ERROR = "error"
        const val PREF_UPDATED_AT = "updated_at"
        private val processStartRequestGeneration = ServiceStartRequestGeneration()
        private const val TAG = "AstraVpnService"
        private const val NOTIFICATION_ID = 1
        private const val TUN_TIMEOUT_MS = 20_000L
    }
}
