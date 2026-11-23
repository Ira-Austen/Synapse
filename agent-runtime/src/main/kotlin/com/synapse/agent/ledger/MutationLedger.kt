package com.synapse.agent.ledger

import com.synapse.agent.shell.RootCommandExecutor
import com.synapse.protocol.MiniJson
import com.synapse.protocol.int
import com.synapse.protocol.long
import com.synapse.protocol.str
import java.io.File
import java.util.UUID

enum class MutationType {
    OEM_AUTOSTART,
    APPOPS_MODE,
    TEMP_DEBUG_SETTING,
    CUSTOM_REVERSIBLE;

    companion object {
        fun fromString(raw: String?): MutationType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: CUSTOM_REVERSIBLE
    }
}

data class MutationRecord(
    val id: String,
    val targetPkg: String,
    val userId: Int = 0,
    val mutationType: MutationType,
    val key: String,
    val originalValue: String,
    val appliedValue: String,
    val rollbackCommand: String,
    val verifyCommand: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

data class RollbackReport(
    val attempted: Int,
    val succeeded: Int,
    val failedIds: List<String>,
    val details: List<String>
) {
    val allClean: Boolean get() = failedIds.isEmpty()
}

/**
 * Transactional ledger for RARE, explicitly user-authorized external OS tweaks.
 * Default Synapse operation writes ZERO OS files and creates ZERO ledger entries.
 * When used, guarantees LIFO rollback per-app or full clean-uninstall reset.
 */
class MutationLedger(
    private val storageFile: File
) {
    private val lock = Any()
    private val entries = ArrayList<MutationRecord>()

    init {
        loadFromDisk()
    }

    fun listAll(): List<MutationRecord> = synchronized(lock) { entries.toList() }

    fun listForPackage(pkg: String, userId: Int? = null): List<MutationRecord> = synchronized(lock) {
        entries.filter { it.targetPkg == pkg && (userId == null || it.userId == userId) }
    }

    /**
     * Captures baseline value first, persists the ledger entry BEFORE executing the mutation,
     * and returns the recorded entry.
     */
    suspend fun recordAndApply(
        shell: RootCommandExecutor,
        targetPkg: String,
        userId: Int = 0,
        mutationType: MutationType,
        key: String,
        queryBaselineCommand: String,
        applyCommand: String,
        appliedValue: String,
        rollbackCommandBuilder: (originalValue: String) -> String
    ): Result<MutationRecord> {
        val baselineRes = shell.exec(queryBaselineCommand)
        if (!baselineRes.isSuccess) {
            return Result.failure(IllegalStateException("Baseline capture failed: ${baselineRes.stdout}"))
        }
        val originalValue = baselineRes.stdout.trim()
        val record = MutationRecord(
            id = UUID.randomUUID().toString(),
            targetPkg = targetPkg,
            userId = userId,
            mutationType = mutationType,
            key = key,
            originalValue = originalValue,
            appliedValue = appliedValue,
            rollbackCommand = rollbackCommandBuilder(originalValue),
            verifyCommand = queryBaselineCommand
        )
        synchronized(lock) {
            entries.add(record)
            persistLocked()
        }
        val applyRes = shell.exec(applyCommand)
        if (!applyRes.isSuccess) {
            // Undo ledger entry if mutation never took effect
            synchronized(lock) {
                entries.removeAll { it.id == record.id }
                persistLocked()
            }
            return Result.failure(IllegalStateException("Apply failed: ${applyRes.stdout}"))
        }
        return Result.success(record)
    }

    suspend fun rollbackPackage(shell: RootCommandExecutor, pkg: String, userId: Int? = null): RollbackReport {
        val targets = synchronized(lock) {
            entries.filter { it.targetPkg == pkg && (userId == null || it.userId == userId) }.asReversed()
        }
        return executeRollback(shell, targets)
    }

    suspend fun rollbackAllForCleanUninstall(shell: RootCommandExecutor): RollbackReport {
        val targets = synchronized(lock) { entries.asReversed().toList() }
        return executeRollback(shell, targets)
    }

    private suspend fun executeRollback(
        shell: RootCommandExecutor,
        targets: List<MutationRecord>
    ): RollbackReport {
        var succeeded = 0
        val failed = ArrayList<String>()
        val details = ArrayList<String>()
        val removedIds = HashSet<String>()

        for (rec in targets) {
            val res = shell.exec(rec.rollbackCommand)
            if (res.isSuccess) {
                val verified = if (rec.verifyCommand.isNotBlank()) {
                    val v = shell.exec(rec.verifyCommand)
                    v.isSuccess && v.stdout.trim() == rec.originalValue
                } else {
                    true
                }
                succeeded++
                removedIds.add(rec.id)
                details.add("Reverted ${rec.targetPkg} [${rec.key}] -> '${rec.originalValue}' (verified=$verified)")
            } else {
                failed.add(rec.id)
                details.add("FAILED reverting ${rec.targetPkg} [${rec.key}]: ${res.stdout}")
            }
        }

        if (removedIds.isNotEmpty()) {
            synchronized(lock) {
                entries.removeAll { removedIds.contains(it.id) }
                persistLocked()
            }
        }

        return RollbackReport(
            attempted = targets.size,
            succeeded = succeeded,
            failedIds = failed,
            details = details
        )
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            entries.clear()
            if (!storageFile.exists()) return
            val raw = runCatching { storageFile.readText(Charsets.UTF_8) }.getOrNull() ?: return
            if (raw.isBlank()) return
            runCatching {
                val list = MiniJson.parseArray(raw)
                for (item in list) {
                    @Suppress("UNCHECKED_CAST")
                    val m = item as? Map<String, Any?> ?: continue
                    entries.add(
                        MutationRecord(
                            id = m.str("id"),
                            targetPkg = m.str("targetPkg"),
                            userId = m.int("userId", 0),
                            mutationType = MutationType.fromString(m.str("mutationType")),
                            key = m.str("key"),
                            originalValue = m.str("originalValue"),
                            appliedValue = m.str("appliedValue"),
                            rollbackCommand = m.str("rollbackCommand"),
                            verifyCommand = m.str("verifyCommand"),
                            timestampMs = m.long("timestampMs", 0L)
                        )
                    )
                }
            }
        }
    }

    private fun persistLocked() {
        storageFile.parentFile?.mkdirs()
        val encoded = MiniJson.encode(entries.map { r ->
            linkedMapOf<String, Any?>(
                "id" to r.id,
                "targetPkg" to r.targetPkg,
                "userId" to r.userId,
                "mutationType" to r.mutationType.name,
                "key" to r.key,
                "originalValue" to r.originalValue,
                "appliedValue" to r.appliedValue,
                "rollbackCommand" to r.rollbackCommand,
                "verifyCommand" to r.verifyCommand,
                "timestampMs" to r.timestampMs
            )
        })
        storageFile.writeText(encoded, Charsets.UTF_8)
    }
}
