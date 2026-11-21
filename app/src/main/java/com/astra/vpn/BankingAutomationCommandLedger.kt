package com.astra.vpn

/** Durable, non-secret command envelope shared by the monitor and VPN service. */
internal data class BankingAutomationCommandRecord(
    val transitionId: Long,
    val command: Command,
    val originatingPackage: String,
    val status: Status
) {
    enum class Command { PAUSE, RESUME }
    enum class Status { PENDING, COMPLETED }
}

internal object BankingAutomationCommandLedgerCodec {
    private const val VERSION = "1"
    fun encode(record: BankingAutomationCommandRecord): String {
        require(record.transitionId > 0L)
        require(record.originatingPackage.isNotBlank() && '|' !in record.originatingPackage)
        return listOf(VERSION, record.transitionId.toString(), record.command.name,
            record.originatingPackage, record.status.name).joinToString("|")
    }
    fun decode(raw: String?): BankingAutomationCommandRecord? {
        if (raw.isNullOrBlank()) return null
        val fields = raw.split('|')
        if (fields.size != 5 || fields[0] != VERSION) return null
        val id = fields[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val command = runCatching { BankingAutomationCommandRecord.Command.valueOf(fields[2]) }.getOrNull() ?: return null
        val origin = fields[3].takeIf { it.isNotBlank() } ?: return null
        val status = runCatching { BankingAutomationCommandRecord.Status.valueOf(fields[4]) }.getOrNull() ?: return null
        return BankingAutomationCommandRecord(id, command, origin, status)
    }
}

/** Single-record write-ahead ledger. Mutations are synchronous before service dispatch. */
internal class BankingAutomationCommandLedger(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("astra_settings", android.content.Context.MODE_PRIVATE)
    @Synchronized fun read(): BankingAutomationCommandRecord? {
        val raw = prefs.getString(KEY, null) ?: return null
        val decoded = BankingAutomationCommandLedgerCodec.decode(raw)
        if (decoded == null) check(prefs.edit().remove(KEY).commit())
        return decoded
    }
    @Synchronized fun writePending(record: BankingAutomationCommandRecord): Boolean {
        require(record.status == BankingAutomationCommandRecord.Status.PENDING)
        val current = read()
        if (current != null && current.status == BankingAutomationCommandRecord.Status.PENDING &&
            (current.transitionId != record.transitionId || current.command != record.command || current.originatingPackage != record.originatingPackage)) return false
        return prefs.edit().putString(KEY, BankingAutomationCommandLedgerCodec.encode(record)).commit()
    }
    @Synchronized fun complete(transitionId: Long, command: BankingAutomationCommandRecord.Command): Boolean {
        val current = read() ?: return false
        if (current.transitionId != transitionId || current.command != command || current.status != BankingAutomationCommandRecord.Status.PENDING) return false
        return prefs.edit().putString(KEY, BankingAutomationCommandLedgerCodec.encode(current.copy(status = BankingAutomationCommandRecord.Status.COMPLETED))).commit()
    }
    @Synchronized fun clearCompleted() {
        val current = read() ?: return
        if (current.status == BankingAutomationCommandRecord.Status.COMPLETED) check(prefs.edit().remove(KEY).commit())
    }
    private companion object { const val KEY = "banking_automation_command_v1" }
}
