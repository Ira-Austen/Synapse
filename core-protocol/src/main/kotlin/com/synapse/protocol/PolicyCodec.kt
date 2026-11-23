package com.synapse.protocol

/**
 * Deterministic, zero-reflection JSON codec for all Synapse cross-process models.
 */
object PolicyCodec {

    fun encodeBundle(bundle: KernelPolicyBundle): String {
        val root = linkedMapOf<String, Any?>(
            "version" to bundle.version,
            "revision" to bundle.revision,
            "romFingerprint" to bundle.romFingerprint,
            "globalSettings" to mapGlobalSettings(bundle.globalSettings),
            "stealthAllowlistPkgs" to bundle.stealthAllowlistPkgs.toList(),
            "processRules" to bundle.processRules.values.map(::mapProcessRule),
            "componentBlockRules" to bundle.componentBlockRules,
            "intentFirewallRules" to bundle.intentFirewallRules.map {
                linkedMapOf<String, Any?>(
                    "id" to it.id,
                    "callerPkg" to it.callerPkg,
                    "targetPkg" to it.targetPkg,
                    "action" to it.action,
                    "block" to it.block
                )
            },
            "alarmThrottleRules" to bundle.alarmThrottleRules.values.map {
                linkedMapOf<String, Any?>(
                    "pkg" to it.pkg,
                    "minIntervalMs" to it.minIntervalMs,
                    "alignWindowMs" to it.alignWindowMs,
                    "enabled" to it.enabled
                )
            }
        )
        return MiniJson.encode(root)
    }

    fun decodeBundle(json: String): KernelPolicyBundle {
        if (json.isBlank()) return KernelPolicyBundle()
        val obj = MiniJson.parseObject(json)
        val version = obj.int("version", KernelPolicyBundle.CURRENT_SCHEMA_VERSION)
        val revision = obj.long("revision", 1L)
        val romFingerprint = obj.str("romFingerprint", "")
        val gs = obj.obj("globalSettings")?.let(::parseGlobalSettings) ?: GlobalSettings()

        val allowlist = obj.arr("stealthAllowlistPkgs")
            ?.mapNotNull { it as? String }
            ?.filter { it.isNotBlank() }
            ?.toCollection(LinkedHashSet())
            ?: KernelPolicyBundle.DEFAULT_STEALTH_ALLOWLIST

        val rulesMap = LinkedHashMap<String, ProcessRule>()
        val rulesRaw = obj["processRules"]
        when (rulesRaw) {
            is List<*> -> {
                for (entry in rulesRaw) {
                    @Suppress("UNCHECKED_CAST")
                    val map = entry as? Map<String, Any?> ?: continue
                    val rule = parseProcessRule(map) ?: continue
                    rulesMap[rule.ruleKey] = rule
                }
            }
            is Map<*, *> -> {
                for ((_, v) in rulesRaw) {
                    @Suppress("UNCHECKED_CAST")
                    val map = v as? Map<String, Any?> ?: continue
                    val rule = parseProcessRule(map) ?: continue
                    rulesMap[rule.ruleKey] = rule
                }
            }
        }

        val compBlocks = LinkedHashMap<String, List<String>>()
        obj.obj("componentBlockRules")?.forEach { (k, v) ->
            val list = (v as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            compBlocks[k] = list
        }

        val intentRules = obj.arr("intentFirewallRules")?.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            IntentRule(
                id = m.str("id"),
                callerPkg = m.str("callerPkg"),
                targetPkg = m.str("targetPkg"),
                action = m.strOrNull("action"),
                block = m.bool("block", true)
            )
        } ?: emptyList()

        val alarmRules = LinkedHashMap<String, AlarmRule>()
        obj.arr("alarmThrottleRules")?.forEach { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@forEach
            val pkg = m.str("pkg")
            if (pkg.isNotBlank()) {
                alarmRules[pkg] = AlarmRule(
                    pkg = pkg,
                    minIntervalMs = m.long("minIntervalMs", 300_000L),
                    alignWindowMs = m.long("alignWindowMs", 60_000L),
                    enabled = m.bool("enabled", true)
                )
            }
        }

        return KernelPolicyBundle(
            version = version,
            revision = revision,
            romFingerprint = romFingerprint,
            globalSettings = gs,
            stealthAllowlistPkgs = allowlist,
            processRules = rulesMap,
            componentBlockRules = compBlocks,
            intentFirewallRules = intentRules,
            alarmThrottleRules = alarmRules
        )
    }

    fun encodeHealth(report: KernelHealthReport): String {
        val root = linkedMapOf<String, Any?>(
            "kernelVersion" to report.kernelVersion,
            "apiLevel" to report.apiLevel,
            "frameworkName" to report.frameworkName,
            "frameworkApiVersion" to report.frameworkApiVersion,
            "oomAdjusterImpl" to report.oomAdjusterImpl,
            "currentFingerprint" to report.currentFingerprint,
            "cachedFingerprint" to report.cachedFingerprint,
            "otaChanged" to report.otaChanged,
            "policyRevision" to report.policyRevision,
            "activeRuleCount" to report.activeRuleCount,
            "auditCount" to report.auditCount,
            "uptimeMs" to report.uptimeMs,
            "plugins" to report.plugins.map { p ->
                linkedMapOf<String, Any?>(
                    "name" to p.name,
                    "status" to p.status.name,
                    "hookCount" to p.hookCount,
                    "message" to p.message
                )
            }
        )
        return MiniJson.encode(root)
    }

    fun decodeHealth(json: String): KernelHealthReport {
        val obj = MiniJson.parseObject(json)
        val plugins = obj.arr("plugins")?.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            PluginHealth(
                name = m.str("name"),
                status = PluginStatus.fromString(m.str("status")),
                hookCount = m.int("hookCount"),
                message = m.str("message")
            )
        } ?: emptyList()

        return KernelHealthReport(
            kernelVersion = obj.str("kernelVersion"),
            apiLevel = obj.int("apiLevel"),
            frameworkName = obj.str("frameworkName"),
            frameworkApiVersion = obj.int("frameworkApiVersion"),
            oomAdjusterImpl = obj.str("oomAdjusterImpl"),
            currentFingerprint = obj.str("currentFingerprint"),
            cachedFingerprint = obj.str("cachedFingerprint"),
            otaChanged = obj.bool("otaChanged"),
            policyRevision = obj.long("policyRevision"),
            activeRuleCount = obj.int("activeRuleCount"),
            auditCount = obj.int("auditCount"),
            uptimeMs = obj.long("uptimeMs"),
            plugins = plugins
        )
    }

    fun encodeAuditEvents(events: List<AuditEvent>): String {
        val list = events.map { e ->
            linkedMapOf<String, Any?>(
                "seq" to e.seq,
                "ts" to e.timestampMs,
                "type" to e.type.name,
                "pkg" to e.pkg,
                "proc" to e.processName,
                "uid" to e.userId,
                "detail" to e.detail
            )
        }
        return MiniJson.encode(list)
    }

    fun decodeAuditEvents(json: String): List<AuditEvent> {
        if (json.isBlank()) return emptyList()
        return MiniJson.parseArray(json).mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            AuditEvent(
                seq = m.long("seq"),
                timestampMs = m.long("ts"),
                type = AuditEventType.fromString(m.str("type")),
                pkg = m.str("pkg"),
                processName = m.str("proc"),
                userId = m.int("uid"),
                detail = m.str("detail")
            )
        }
    }

    fun encodeNotifications(records: List<NotificationHistoryRecord>): String {
        val list = records.map { r ->
            linkedMapOf<String, Any?>(
                "seq" to r.seq,
                "ts" to r.timestampMs,
                "pkg" to r.pkg,
                "uid" to r.userId,
                "ch" to r.channelId,
                "title" to r.title,
                "text" to r.text,
                "sub" to r.subText,
                "ongoing" to r.isOngoing
            )
        }
        return MiniJson.encode(list)
    }

    fun decodeNotifications(json: String): List<NotificationHistoryRecord> {
        if (json.isBlank()) return emptyList()
        return MiniJson.parseArray(json).mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            NotificationHistoryRecord(
                seq = m.long("seq"),
                timestampMs = m.long("ts"),
                pkg = m.str("pkg"),
                userId = m.int("uid"),
                channelId = m.str("ch"),
                title = m.str("title"),
                text = m.str("text"),
                subText = m.str("sub"),
                isOngoing = m.bool("ongoing", false)
            )
        }
    }

    fun encodeLiveProcesses(records: List<LiveProcessSnapshot>): String {
        val list = records.map { r ->
            linkedMapOf<String, Any?>(
                "pkg" to r.pkg,
                "userId" to r.userId,
                "proc" to r.processName,
                "pid" to r.pid,
                "uid" to r.uid,
                "adj" to r.curAdj,
                "state" to r.curProcState,
                "frozen" to r.frozen,
                "rssKb" to r.rssKb
            )
        }
        return MiniJson.encode(list)
    }

    fun decodeLiveProcesses(json: String): List<LiveProcessSnapshot> {
        if (json.isBlank()) return emptyList()
        return MiniJson.parseArray(json).mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            LiveProcessSnapshot(
                pkg = m.str("pkg"),
                userId = m.int("userId"),
                processName = m.str("proc"),
                pid = m.int("pid"),
                uid = m.int("uid"),
                curAdj = m.int("adj", 999),
                curProcState = m.int("state", 20),
                frozen = m.bool("frozen", false),
                rssKb = m.long("rssKb", 0L)
            )
        }
    }

    private fun mapGlobalSettings(gs: GlobalSettings): Map<String, Any?> = linkedMapOf(
        "masterEnabled" to gs.masterEnabled,
        "stealthEnabled" to gs.stealthEnabled,
        "oemHansBypassEnabled" to gs.oemHansBypassEnabled,
        "memoryDozeBypassEnabled" to gs.memoryDozeBypassEnabled,
        "resurrectDelayMs" to gs.resurrectDelayMs,
        "breakerWindowMs" to gs.breakerWindowMs,
        "breakerThreshold" to gs.breakerThreshold,
        "breakerCooldownMs" to gs.breakerCooldownMs
    )

    private fun parseGlobalSettings(m: Map<String, Any?>): GlobalSettings = GlobalSettings(
        masterEnabled = m.bool("masterEnabled", true),
        stealthEnabled = m.bool("stealthEnabled", true),
        oemHansBypassEnabled = m.bool("oemHansBypassEnabled", true),
        memoryDozeBypassEnabled = m.bool("memoryDozeBypassEnabled", true),
        resurrectDelayMs = m.long("resurrectDelayMs", 1500L).coerceIn(500L, 15_000L),
        breakerWindowMs = m.long("breakerWindowMs", 30_000L).coerceIn(5_000L, 300_000L),
        breakerThreshold = m.int("breakerThreshold", 3).coerceIn(1, 20),
        breakerCooldownMs = m.long("breakerCooldownMs", 300_000L).coerceIn(10_000L, 3_600_000L)
    )

    private fun mapProcessRule(r: ProcessRule): Map<String, Any?> = linkedMapOf(
        "pkg" to r.pkg,
        "userId" to r.userId,
        "appName" to r.appName,
        "enabled" to r.enabled,
        "pushMode" to r.pushMode.name,
        "targetProcess" to r.targetProcess,
        "wakeComponent" to r.wakeComponent,
        "wakeType" to r.wakeType.name,
        "wakeAction" to r.wakeAction,
        "keepAliveAdj" to r.keepAliveAdj,
        "protectProcState" to r.protectProcState,
        "exemptAospFreezer" to r.exemptAospFreezer,
        "exemptOemFreezer" to r.exemptOemFreezer,
        "memoryDozeBypass" to r.memoryDozeBypass,
        "allowResurrect" to r.allowResurrect,
        "verifiedByAgent" to r.verifiedByAgent,
        "aiSummary" to r.aiSummary
    )

    private fun parseProcessRule(m: Map<String, Any?>): ProcessRule? {
        val pkg = m.str("pkg")
        if (pkg.isBlank()) return null
        val targetProcess = m.str("targetProcess", pkg).ifBlank { pkg }
        val adj = m.int("keepAliveAdj", 200)
            .coerceIn(ProcessRule.MIN_SAFE_ADJ, ProcessRule.MAX_SAFE_ADJ)
        val userId = m.int("userId", 0).coerceAtLeast(0)
        return ProcessRule(
            pkg = pkg,
            userId = userId,
            appName = m.str("appName", pkg),
            enabled = m.bool("enabled", true),
            pushMode = PushMode.fromString(m.str("pushMode")),
            targetProcess = targetProcess,
            wakeComponent = m.strOrNull("wakeComponent"),
            wakeType = WakeType.fromString(m.str("wakeType")),
            wakeAction = m.strOrNull("wakeAction"),
            keepAliveAdj = adj,
            protectProcState = m.bool("protectProcState", true),
            exemptAospFreezer = m.bool("exemptAospFreezer", true),
            exemptOemFreezer = m.bool("exemptOemFreezer", true),
            memoryDozeBypass = m.bool("memoryDozeBypass", true),
            allowResurrect = m.bool("allowResurrect", true),
            verifiedByAgent = m.bool("verifiedByAgent", false),
            aiSummary = m.str("aiSummary", "")
        )
    }
}
