package com.synapse.kernel.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Parcel
import com.synapse.protocol.AuditEventType
import com.synapse.protocol.IpcProtocol
import com.synapse.protocol.PolicyCodec
import java.security.MessageDigest

/**
 * Stealth bidirectional RPC bridge hooked on `ActivityManagerService.onTransact`.
 *
 * Security & performance invariants:
 * - Only intercepts high-entropy `magicCode` in 0x00530000..0x007EFFFF.
 * - O(1) caller UID + cached APK SHA-256 signature verification.
 * - Unauthorized callers are silently passed through to native AMS with zero logging and zero Parcel consumption.
 */
class IpcBridge(
    private val ctx: KernelContext,
    private val onHealthRequest: () -> String,
    private val onResetBreaker: (String?) -> Unit,
    private val onQueryProcesses: (String?) -> String,
    private val onWakeRule: (String) -> Pair<Boolean, String>
) {
    @Volatile
    private var cachedModuleUid: Int = -1

    @Volatile
    private var signatureVerified: Boolean = false

    @Volatile
    private var lastUidCheckMs: Long = 0L

    @Volatile
    var installed: Boolean = false
        private set

    fun install(): Boolean {
        if (!IpcProtocol.isSafeMagicCode(ctx.magicCode)) {
            ctx.bridge.logError("IpcBridge", "Refusing unsafe magicCode=${ctx.magicCode}")
            return false
        }
        val amsClass = ctx.adapter.amsClass ?: return false
        val onTransact = ctx.adapter.findMethod(
            amsClass,
            "onTransact",
            Int::class.javaPrimitiveType!!,
            Parcel::class.java,
            Parcel::class.java,
            Int::class.javaPrimitiveType!!
        ) ?: return false

        SafeHook.hookReplaceable(ctx.bridge, ctx.audit, "IpcBridge", onTransact) { call ->
            val code = call.args[0] as? Int ?: return@hookReplaceable call.proceed()
            if (code != ctx.magicCode) {
                return@hookReplaceable call.proceed()
            }

            val callingUid = Binder.getCallingUid()
            val amsObj = call.thisObject
            if (!isAuthorizedCaller(callingUid, amsObj)) {
                // Silent passthrough: do not read Parcel or emit observable side effects.
                return@hookReplaceable call.proceed()
            }

            val data = call.args[1] as? Parcel ?: return@hookReplaceable call.proceed()
            val reply = call.args[2] as? Parcel ?: return@hookReplaceable call.proceed()

            val startPos = data.dataPosition()
            val header = runCatching { data.readInt() }.getOrElse {
                data.setDataPosition(startPos)
                return@hookReplaceable call.proceed()
            }
            if (header != ctx.headerMagic) {
                data.setDataPosition(startPos)
                return@hookReplaceable call.proceed()
            }

            handleAuthorizedTransaction(data, reply)
            true
        }
        installed = true
        ctx.bridge.logInfo(
            "IpcBridge",
            "Installed on ${onTransact.declaringClass.name}.onTransact magic=${ctx.magicCode}"
        )
        return true
    }

    fun invalidateCallerCache() {
        cachedModuleUid = -1
        signatureVerified = false
    }

    private fun isAuthorizedCaller(callingUid: Int, amsObj: Any?): Boolean {
        if (callingUid <= 0) return false
        val uid = cachedModuleUid
        if (uid > 0 && callingUid == uid && signatureVerified) {
            return true
        }
        val now = System.currentTimeMillis()
        if (uid > 0 && callingUid != uid && (now - lastUidCheckMs) < 5_000L) {
            return false
        }
        lastUidCheckMs = now
        return refreshAndVerifyModuleIdentity(callingUid, amsObj)
    }

    @Suppress("DEPRECATION")
    private fun refreshAndVerifyModuleIdentity(callingUid: Int, amsObj: Any?): Boolean {
        val context = ctx.adapter.getAmsContext(amsObj) ?: return false
        val pm = context.packageManager ?: return false
        val modulePkg = ctx.bridge.modulePackageName
        if (modulePkg.isBlank()) return false

        val token = Binder.clearCallingIdentity()
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val pkgInfo = pm.getPackageInfo(modulePkg, flags)
            val resolvedUid = pkgInfo.applicationInfo?.uid ?: -1
            if (resolvedUid <= 0) return false

            val sigOk = if (ctx.expectedSignatureSha256.isBlank()) {
                true
            } else {
                val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.signingInfo?.apkContentsSigners
                        ?: pkgInfo.signingInfo?.signingCertificateHistory
                        ?: emptyArray()
                } else {
                    pkgInfo.signatures ?: emptyArray()
                }
                certs.any { sig ->
                    sha256Hex(sig.toByteArray()).equals(ctx.expectedSignatureSha256, ignoreCase = true)
                }
            }

            cachedModuleUid = resolvedUid
            signatureVerified = sigOk
            sigOk && callingUid == resolvedUid
        } catch (_: Throwable) {
            false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun handleAuthorizedTransaction(data: Parcel, reply: Parcel) {
        try {
            val protoVersion = data.readInt()
            val cmd = data.readInt()
            val payload = data.readString() ?: ""

            if (protoVersion != IpcProtocol.PROTOCOL_VERSION) {
                reply.writeInt(IpcProtocol.STATUS_ERR_INVALID_PAYLOAD)
                reply.writeString("Unsupported protocol version: $protoVersion")
                return
            }

            when (cmd) {
                IpcProtocol.CMD_GET_HEALTH -> {
                    val json = onHealthRequest()
                    reply.writeInt(IpcProtocol.STATUS_OK)
                    reply.writeString(json)
                }

                IpcProtocol.CMD_PUSH_POLICY -> {
                    val snap = ctx.policyManager.applyJson(payload, source = "MagicBinder")
                    reply.writeInt(IpcProtocol.STATUS_OK)
                    reply.writeString("rev=${snap.bundle.revision};active=${snap.activeRuleCount}")
                }

                IpcProtocol.CMD_DRAIN_AUDIT -> {
                    val pkgFilter = payload.takeIf { it.isNotBlank() }
                    val events = ctx.audit.drain(pkgFilter = pkgFilter)
                    reply.writeInt(IpcProtocol.STATUS_OK)
                    reply.writeString(PolicyCodec.encodeAuditEvents(events))
                }

                IpcProtocol.CMD_RESET_BREAKER -> {
                    val target = payload.takeIf { it.isNotBlank() }
                    onResetBreaker(target)
                    ctx.audit.record(
                        type = AuditEventType.BREAKER_RESET,
                        processName = target ?: "*"
                    )
                    reply.writeInt(IpcProtocol.STATUS_OK)
                    reply.writeString("OK")
                }

                IpcProtocol.CMD_DRAIN_NOTIFICATIONS -> {
                    val pkgFilter = payload.takeIf { it.isNotBlank() }
                    val records = ctx.notifications.drain(pkgFilter = pkgFilter)
                    reply.writeInt(IpcProtocol.STATUS_OK)
                    reply.writeString(PolicyCodec.encodeNotifications(records))
                }

                IpcProtocol.CMD_QUERY_PROCESSES -> {
                    val pkgFilter = payload.takeIf { it.isNotBlank() }
                    val json = onQueryProcesses(pkgFilter)
                    reply.writeInt(IpcProtocol.STATUS_OK)
                    reply.writeString(json)
                }

                IpcProtocol.CMD_WAKE_PROCESS -> {
                    val (ok, msg) = onWakeRule(payload.trim())
                    reply.writeInt(if (ok) IpcProtocol.STATUS_OK else IpcProtocol.STATUS_ERR_INTERNAL)
                    reply.writeString(msg)
                }

                IpcProtocol.CMD_REGISTER_OBSERVER -> {
                    val binder = if (data.dataAvail() > 0) data.readStrongBinder() else null
                    if (binder != null) {
                        ctx.observerHub.register(binder)
                    } else {
                        ctx.observerHub.unregister()
                    }
                    reply.writeInt(IpcProtocol.STATUS_OK)
                    reply.writeString(if (binder != null) "REGISTERED" else "UNREGISTERED")
                }

                else -> {
                    reply.writeInt(IpcProtocol.STATUS_ERR_INVALID_CMD)
                    reply.writeString("Unknown cmd=$cmd")
                }
            }
        } catch (t: Throwable) {
            reply.writeInt(IpcProtocol.STATUS_ERR_INTERNAL)
            reply.writeString("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
