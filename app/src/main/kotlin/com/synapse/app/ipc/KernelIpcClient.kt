package com.synapse.app.ipc

import android.annotation.SuppressLint
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.synapse.app.BuildConfig
import com.synapse.protocol.AuditEvent
import com.synapse.protocol.IpcProtocol
import com.synapse.protocol.KernelHealthReport
import com.synapse.protocol.KernelPolicyBundle
import com.synapse.protocol.LiveProcessSnapshot
import com.synapse.protocol.NotificationHistoryRecord
import com.synapse.protocol.PolicyCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client for the stealth `ActivityManagerService.onTransact` Magic Binder channel,
 * supporting instant zero-root process queries, direct wake, and one-way event push.
 */
object KernelIpcClient {

    @Volatile
    private var activeObserverBinder: Binder? = null

    suspend fun getHealth(): Result<KernelHealthReport> = withContext(Dispatchers.IO) {
        transact(IpcProtocol.CMD_GET_HEALTH, "").mapCatching { json ->
            PolicyCodec.decodeHealth(json)
        }
    }

    suspend fun pushPolicy(bundle: KernelPolicyBundle): Result<String> = withContext(Dispatchers.IO) {
        transact(IpcProtocol.CMD_PUSH_POLICY, PolicyCodec.encodeBundle(bundle))
    }

    suspend fun drainAudit(pkgFilter: String? = null): Result<List<AuditEvent>> = withContext(Dispatchers.IO) {
        transact(IpcProtocol.CMD_DRAIN_AUDIT, pkgFilter.orEmpty()).mapCatching { json ->
            PolicyCodec.decodeAuditEvents(json)
        }
    }

    suspend fun resetBreaker(processKey: String? = null): Result<String> = withContext(Dispatchers.IO) {
        transact(IpcProtocol.CMD_RESET_BREAKER, processKey.orEmpty())
    }

    suspend fun drainNotifications(pkgFilter: String? = null): Result<List<NotificationHistoryRecord>> =
        withContext(Dispatchers.IO) {
            transact(IpcProtocol.CMD_DRAIN_NOTIFICATIONS, pkgFilter.orEmpty()).mapCatching { json ->
                PolicyCodec.decodeNotifications(json)
            }
        }

    suspend fun queryProcesses(pkgFilter: String? = null): Result<List<LiveProcessSnapshot>> =
        withContext(Dispatchers.IO) {
            transact(IpcProtocol.CMD_QUERY_PROCESSES, pkgFilter.orEmpty()).mapCatching { json ->
                PolicyCodec.decodeLiveProcesses(json)
            }
        }

    suspend fun wakeRule(ruleKey: String): Result<String> = withContext(Dispatchers.IO) {
        transact(IpcProtocol.CMD_WAKE_PROCESS, ruleKey)
    }

    suspend fun registerObserver(onEvent: (Int) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        val localBinder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IpcProtocol.OBSERVER_TX_EVENT) {
                    val mask = runCatching { data.readInt() }.getOrDefault(0)
                    if (mask != 0) onEvent(mask)
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        activeObserverBinder = localBinder
        transact(IpcProtocol.CMD_REGISTER_OBSERVER, "", extraBinder = localBinder)
    }

    suspend fun unregisterObserver(): Result<String> = withContext(Dispatchers.IO) {
        activeObserverBinder = null
        transact(IpcProtocol.CMD_REGISTER_OBSERVER, "", extraBinder = null)
    }

    @SuppressLint("PrivateApi")
    private fun getActivityManagerBinder(): IBinder? {
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            getService.invoke(null, "activity") as? IBinder
        }.getOrNull()
    }

    private fun transact(cmd: Int, payload: String, extraBinder: IBinder? = null): Result<String> {
        val binder = getActivityManagerBinder()
            ?: return Result.failure(IllegalStateException("ActivityManager binder unavailable"))
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInt(BuildConfig.IPC_HEADER_MAGIC)
            data.writeInt(IpcProtocol.PROTOCOL_VERSION)
            data.writeInt(cmd)
            data.writeString(payload)
            if (cmd == IpcProtocol.CMD_REGISTER_OBSERVER) {
                data.writeStrongBinder(extraBinder)
            }

            val ok = binder.transact(BuildConfig.IPC_MAGIC_CODE, data, reply, 0)
            if (!ok) {
                return Result.failure(IllegalStateException("Magic Binder transaction not handled (kernel inactive?)"))
            }
            val status = reply.readInt()
            val body = reply.readString() ?: ""
            if (status == IpcProtocol.STATUS_OK) {
                Result.success(body)
            } else {
                Result.failure(IllegalStateException("Kernel IPC status=$status: $body"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
