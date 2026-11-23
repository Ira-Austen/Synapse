package com.synapse.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.synapse.protocol.WakeType

data class InstalledAppItem(
    val pkg: String,
    val label: String,
    val isSystemApp: Boolean
)

data class WakeComponentCandidate(
    val componentFlattened: String,
    val shortName: String,
    val processName: String,
    val wakeType: WakeType,
    val tag: String,
    val score: Int
)

data class ProcessCandidate(
    val processName: String,
    val shortLabel: String,
    val hintTag: String,
    val isMainProcess: Boolean,
    val recommended: Boolean,
    val verifiedByLiveSocket: Boolean,
    val isRunning: Boolean,
    val liveTcpCount: Int,
    val liveUdpCount: Int,
    val livePortsSummary: String,
    val isDormantOem: Boolean,
    val isNoiseProcess: Boolean,
    val score: Int,
    val wakeCandidates: List<WakeComponentCandidate>
)

data class AppInspectionResult(
    val pkg: String,
    val label: String,
    val deviceBrand: DeviceBrand,
    val anyProcessRunning: Boolean,
    val anyLiveSocketVerified: Boolean,
    val rootProbeAvailable: Boolean,
    val processCandidates: List<ProcessCandidate>,
    val recommendedProcess: ProcessCandidate?
)

/**
 * Hybrid static + live-socket app inspector:
 * 1. Reads AndroidManifest services/receivers via PackageManager (zero Root).
 * 2. Classifies 16+ mainstream Push/IM SDKs with device-brand awareness via [PushSdkClassifier].
 * 3. Overlays live ESTABLISHED TCP/UDP socket proof from [LiveSocketInspector] when available.
 */
object AppInspector {

    // Bounded LRU cache (64 icons @ 72x72 ≈ 1.3 MB ceiling) to keep manager RAM tiny
    private val iconCache = LruCache<String, ImageBitmap>(64)

    fun loadInstalledApps(context: Context): List<InstalledAppItem> {
        val pm = context.packageManager
        val apps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())

        return apps.asSequence()
            .filter { it.packageName != context.packageName }
            .map { info ->
                val isSys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                val label = runCatching { pm.getApplicationLabel(info).toString().trim() }
                    .getOrDefault(info.packageName)
                    .ifBlank { info.packageName }
                InstalledAppItem(
                    pkg = info.packageName,
                    label = label,
                    isSystemApp = isSys
                )
            }
            .sortedWith(compareBy<InstalledAppItem> { it.isSystemApp }.thenBy { it.label.lowercase() })
            .toList()
    }

    fun getAppIcon(context: Context, pkg: String): ImageBitmap? {
        iconCache.get(pkg)?.let { return it }
        val bmp = runCatching {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            drawableToBitmap(drawable, sizePx = 72).asImageBitmap()
        }.getOrNull() ?: return null
        iconCache.put(pkg, bmp)
        return bmp
    }

    fun inspectAppComponents(
        context: Context,
        pkg: String,
        liveProbe: PackageSocketProbeResult? = null
    ): AppInspectionResult {
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val deviceBrand = DeviceBrand.detect(Build.MANUFACTURER, Build.BRAND)
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, flags)
            }
        }.getOrNull()

        val servicesByProc = LinkedHashMap<String, MutableList<WakeComponentCandidate>>()
        val rawComponentNamesByProc = LinkedHashMap<String, MutableList<String>>()
        servicesByProc.getOrPut(pkg) { mutableListOf() }
        rawComponentNamesByProc.getOrPut(pkg) { mutableListOf() }

        // Ensure any currently running process (even if dynamically spawned) is represented
        liveProbe?.probesByProcess?.keys?.forEach { runningProc ->
            servicesByProc.getOrPut(runningProc) { mutableListOf() }
            rawComponentNamesByProc.getOrPut(runningProc) { mutableListOf() }
        }

        pkgInfo?.services.orEmpty().forEach { svc ->
            val proc = svc.processName?.takeIf { it.isNotBlank() } ?: pkg
            val comp = "$pkg/${svc.name}"
            val short = svc.name.substringAfterLast('.')
            val cls = PushSdkClassifier.scoreComponent(proc, svc.name, WakeType.SERVICE, proc != pkg, deviceBrand)
            rawComponentNamesByProc.getOrPut(proc) { mutableListOf() }.add(svc.name)
            if (cls.score >= -10) {
                servicesByProc.getOrPut(proc) { mutableListOf() }.add(
                    WakeComponentCandidate(
                        componentFlattened = comp,
                        shortName = short,
                        processName = proc,
                        wakeType = WakeType.SERVICE,
                        tag = cls.tag,
                        score = cls.score
                    )
                )
            }
        }

        pkgInfo?.receivers.orEmpty().forEach { rcv ->
            val proc = rcv.processName?.takeIf { it.isNotBlank() } ?: pkg
            val comp = "$pkg/${rcv.name}"
            val short = rcv.name.substringAfterLast('.')
            val cls = PushSdkClassifier.scoreComponent(proc, rcv.name, WakeType.BROADCAST, proc != pkg, deviceBrand)
            rawComponentNamesByProc.getOrPut(proc) { mutableListOf() }.add(rcv.name)
            if (cls.score >= 45 && !cls.isDormantOem) {
                servicesByProc.getOrPut(proc) { mutableListOf() }.add(
                    WakeComponentCandidate(
                        componentFlattened = comp,
                        shortName = short,
                        processName = proc,
                        wakeType = WakeType.BROADCAST,
                        tag = cls.tag,
                        score = cls.score
                    )
                )
            }
        }

        val candidates = servicesByProc.map { (procName, wakes) ->
            val sortedWakes = wakes
                .distinctBy { it.componentFlattened }
                .sortedByDescending { it.score }
                .take(8)
            val isMain = procName == pkg
            val rawNames = rawComponentNamesByProc[procName].orEmpty()
            val classification = PushSdkClassifier.classifyProcess(procName, isMain, rawNames, deviceBrand)

            val probe = liveProbe?.probesByProcess?.get(procName)
            val isRunning = probe != null
            val tcpCount = probe?.tcpCount ?: 0
            val udpCount = probe?.udpCount ?: 0
            val hasPushPort = probe?.matchedPushPorts?.isNotEmpty() == true
            val hasLiveSocket = tcpCount > 0 || udpCount > 0

            val topWakeScore = sortedWakes.firstOrNull()?.score?.coerceAtLeast(0) ?: 0
            val staticScore = if (isMain || classification.isNoiseProcess || classification.isDormantOem) {
                classification.baseScore
            } else {
                classification.baseScore + topWakeScore
            }

            // Live ESTABLISHED TCP/UDP socket proof overrides static guessing!
            val liveBonus = when {
                !isMain && tcpCount > 0 && hasPushPort -> 320
                !isMain && tcpCount > 0 -> 260
                !isMain && udpCount > 0 -> 180
                isMain && tcpCount > 0 && hasPushPort -> 220
                isMain && tcpCount > 0 -> 140
                !isMain && isRunning && !classification.isNoiseProcess -> 25
                else -> 0
            }

            val totalScore = staticScore + liveBonus
            val shortLabel = if (isMain) "主进程" else procName.substringAfter(':', procName)

            val portsSummary = when {
                probe == null -> ""
                probe.matchedPushPorts.isNotEmpty() ->
                    "含推送端口 ${probe.matchedPushPorts.joinToString("/")}"
                probe.samplePortsText.isNotBlank() ->
                    "连接端口 ${probe.samplePortsText}"
                else -> ""
            }

            ProcessCandidate(
                processName = procName,
                shortLabel = shortLabel,
                hintTag = classification.hintTag,
                isMainProcess = isMain,
                recommended = false,
                verifiedByLiveSocket = hasLiveSocket,
                isRunning = isRunning,
                liveTcpCount = tcpCount,
                liveUdpCount = udpCount,
                livePortsSummary = portsSummary,
                isDormantOem = classification.isDormantOem,
                isNoiseProcess = classification.isNoiseProcess,
                score = totalScore,
                wakeCandidates = sortedWakes
            )
        }
            // Hide pure noise processes unless they surprisingly hold a live socket
            .filter { !it.isNoiseProcess || it.verifiedByLiveSocket }
            .sortedWith(
                compareByDescending<ProcessCandidate> { it.score }
                    .thenByDescending { it.verifiedByLiveSocket }
                    .thenBy { it.isMainProcess }
            )

        val bestIdx = candidates.indexOfFirst { it.verifiedByLiveSocket && !it.isMainProcess }.takeIf { it >= 0 }
            ?: candidates.indexOfFirst { it.score >= 70 && !it.isDormantOem }.takeIf { it >= 0 }
            ?: candidates.indexOfFirst { it.verifiedByLiveSocket }.takeIf { it >= 0 }
            ?: candidates.indexOfFirst { !it.isMainProcess && !it.isDormantOem }.takeIf { it >= 0 }
            ?: 0

        val marked = candidates.mapIndexed { idx, item ->
            item.copy(recommended = idx == bestIdx)
        }

        return AppInspectionResult(
            pkg = pkg,
            label = label,
            deviceBrand = deviceBrand,
            anyProcessRunning = liveProbe?.anyProcessRunning == true,
            anyLiveSocketVerified = marked.any { it.verifiedByLiveSocket },
            rootProbeAvailable = liveProbe?.rootAvailable == true,
            processCandidates = marked,
            recommendedProcess = marked.getOrNull(bestIdx)
        )
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
