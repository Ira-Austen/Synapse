package com.synapse.kernel.plugins

import android.app.Notification
import android.os.Build
import com.synapse.kernel.core.KernelContext
import com.synapse.kernel.core.KernelPlugin
import com.synapse.kernel.core.SafeHook
import com.synapse.protocol.IpcProtocol
import com.synapse.protocol.PluginHealth
import com.synapse.protocol.PluginStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive, zero-settings-mutation push notification history collector inside `system_server`.
 * Optimizations:
 * - Single-pass indexed arg scanning (zero lambda allocations).
 * - Throttles high-frequency ongoing progress notifications (music/download bars) to 1 per 30s per channel.
 * - Deduplicates rapid identical updates from the same (pkg, title, text) within 1.5s.
 */
class NotificationAuditPlugin : KernelPlugin {
    override val name: String = "NotificationAuditPlugin"

    private lateinit var ctx: KernelContext
    private var hookCount = 0
    private var status = PluginStatus.DISABLED
    private var statusMessage = "Not initialized"

    @Volatile
    private var lastDedupeSig: String = ""

    @Volatile
    private var lastDedupeTs: Long = 0L

    private val ongoingThrottle = ConcurrentHashMap<String, Long>(16)

    override fun install(ctx: KernelContext): PluginHealth {
        this.ctx = ctx
        var count = 0

        val nmsClass = ctx.adapter.nmsClass
        if (nmsClass != null) {
            for (method in ctx.adapter.findAllMethodsByName(nmsClass, "enqueueNotificationInternal")) {
                SafeHook.hookAfter(ctx.bridge, ctx.audit, name, method) { call, _ ->
                    captureNotification(call.args)
                }
                count++
            }
        }

        hookCount = count
        status = if (count > 0) PluginStatus.ACTIVE else PluginStatus.HOOK_FAILED
        statusMessage = if (count > 0) {
            "Passive NMS capture active ($count hooks)"
        } else {
            "NotificationManagerService.enqueueNotificationInternal not found"
        }
        return health()
    }

    override fun health(): PluginHealth = PluginHealth(name, status, hookCount, statusMessage)

    private fun captureNotification(args: Array<Any?>) {
        val snap = ctx.currentSnapshot()
        if (!snap.masterEnabled) return

        // Single-pass scan for pkg (first String), Notification, and userId (Int right after Notification)
        var pkg: String? = null
        var notification: Notification? = null
        var notifIdx = -1
        for (i in args.indices) {
            val arg = args[i] ?: continue
            if (pkg == null && arg is String) {
                pkg = arg
            } else if (notification == null && arg is Notification) {
                notification = arg
                notifIdx = i
                if (pkg != null) break
            }
        }

        if (pkg.isNullOrBlank() || pkg == "android" || pkg == "com.android.systemui") return
        val notif = notification ?: return

        val isOngoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notif.channelId.orEmpty()
        } else {
            ""
        }

        val now = System.currentTimeMillis()
        // Throttle high-frequency ongoing progress bars (e.g. downloads / media players) BEFORE parsing extras
        if (isOngoing) {
            val key = "$pkg:$channelId"
            val prev = ongoingThrottle[key]
            if (prev != null && (now - prev) < 30_000L) return
            if (ongoingThrottle.size > 32) {
                ongoingThrottle.entries.removeIf { (now - it.value) > 30_000L }
            }
            ongoingThrottle[key] = now
        }

        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifBlank { extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim().orEmpty() }
            .ifBlank { extras?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty() }
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val text = bigText.ifBlank {
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        }.ifBlank {
            extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?.joinToString(" · ")
                .orEmpty()
        }.ifBlank {
            notif.tickerText?.toString()?.trim().orEmpty()
        }
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        val userId = if (notifIdx >= 0 && notifIdx + 1 < args.size) {
            (args[notifIdx + 1] as? Int)?.coerceAtLeast(0) ?: 0
        } else {
            0
        }

        val sig = "$userId|$pkg|$title|$text"
        if (sig == lastDedupeSig && (now - lastDedupeTs) < 1_500L) return
        lastDedupeSig = sig
        lastDedupeTs = now

        ctx.notifications.record(
            pkg = pkg,
            userId = userId,
            channelId = channelId,
            title = title,
            text = text,
            subText = subText,
            isOngoing = isOngoing,
            timestampMs = now
        )
        ctx.observerHub.notifyEvent(IpcProtocol.OBSERVER_EVENT_NOTIFICATION)
    }
}
