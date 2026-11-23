package com.synapse.agent.shell

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val timedOut: Boolean = false
) {
    val isSuccess: Boolean get() = !timedOut && exitCode == 0
}

/**
 * Abstraction for executing root shell commands (enables clean unit testing & mock injection).
 */
interface RootCommandExecutor : Closeable {
    suspend fun exec(command: String, timeoutMs: Long = 8_000L): ShellResult
    fun isAlive(): Boolean
}

/**
 * Single persistent interactive `su` pipeline using UUID output markers (<5ms latency per command).
 * Opened strictly on-demand during an AI diagnostic session and closed immediately afterwards.
 * Never writes temporary files to `/sdcard` or `/data/local/tmp`.
 */
class PersistentRootShell(
    private val suBinary: String = "su"
) : RootCommandExecutor {

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    fun open(): Boolean = synchronized(lock) {
        if (isAliveLocked()) return true
        return try {
            val pb = ProcessBuilder(suBinary)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            true
        } catch (_: Throwable) {
            closeLocked()
            false
        }
    }

    override fun isAlive(): Boolean = synchronized(lock) { isAliveLocked() }

    /**
     * Verifies that this shell genuinely holds uid=0 root privileges (not just that `su` spawned and exited).
     */
    suspend fun verifyRootAccess(timeoutMs: Long = 3_000L): Boolean {
        val res = exec("id -u", timeoutMs = timeoutMs)
        return res.isSuccess && res.stdout.trim() == "0"
    }

    private fun isAliveLocked(): Boolean = process?.isAlive == true

    override suspend fun exec(command: String, timeoutMs: Long): ShellResult =
        withContext(Dispatchers.IO) {
            if (FORBIDDEN_WRITE_REGEX.containsMatchIn(command)) {
                return@withContext ShellResult(
                    exitCode = -2,
                    stdout = "Blocked by Synapse sandbox: writing to external storage or /data/local/tmp is forbidden."
                )
            }
            try {
                withTimeout(timeoutMs) {
                    execBlocking(command)
                }
            } catch (_: Throwable) {
                // Recycle stuck pipeline on timeout
                close()
                ShellResult(exitCode = -1, stdout = "Command timed out after ${timeoutMs}ms", timedOut = true)
            }
        }

    private fun execBlocking(command: String): ShellResult = synchronized(lock) {
        if (!isAliveLocked() && !open()) {
            return ShellResult(exitCode = 127, stdout = "Failed to acquire persistent root shell ($suBinary)")
        }
        val marker = "__SYNAPSE_EOF_${UUID.randomUUID().toString().replace("-", "")}__"
        val w = writer ?: return ShellResult(127, "Shell writer unavailable")
        val r = reader ?: return ShellResult(127, "Shell reader unavailable")

        val sb = StringBuilder(512)
        var exitCode = -1
        try {
            w.write(command)
            w.newLine()
            w.write("printf '\\n%s:%d\\n' '$marker' \$?")
            w.newLine()
            w.flush()

            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith(marker) && line.length > marker.length && line[marker.length] == ':') {
                    exitCode = line.substring(marker.length + 1).trim().toIntOrNull() ?: -1
                    break
                }
                if (sb.length < MAX_OUTPUT_CHARS) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
            }
        } catch (t: Throwable) {
            closeLocked()
            return ShellResult(exitCode = -1, stdout = "Shell IO error: ${t.message}")
        }
        ShellResult(exitCode = exitCode, stdout = sb.toString().trim())
    }

    override fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        runCatching {
            writer?.write("exit\n")
            writer?.flush()
        }
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching {
            val p = process
            if (p != null && !p.waitFor(300, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
            }
        }
        writer = null
        reader = null
        process = null
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 128 * 1024
        private val FORBIDDEN_WRITE_REGEX =
            Regex("""(>|tee\s+|cp\s+.*\s+|mv\s+.*\s+)(/sdcard|/storage/emulated|/data/local/tmp)""")
    }
}
