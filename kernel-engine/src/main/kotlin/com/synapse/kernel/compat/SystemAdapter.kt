package com.synapse.kernel.compat

import android.content.Context
import android.os.UserHandle
import com.synapse.kernel.core.HookBridge
import com.synapse.protocol.LiveProcessSnapshot
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Startup-cached reflection adapter spanning Android 11 (API 30) to Android 17 (API 37),
 * including Android 15+ `OomAdjusterModernImpl` and OEM private services.
 * Zero dynamic reflection lookup on hot paths.
 */
class SystemAdapter(
    private val classLoader: ClassLoader,
    private val bridge: HookBridge
) {
    val amsClass: Class<*>? = loadClass("com.android.server.am.ActivityManagerService")
    val processRecordClass: Class<*>? = loadClass("com.android.server.am.ProcessRecord")
    val processStateRecordClass: Class<*>? = loadClass("com.android.server.am.ProcessStateRecord")
    val oomAdjusterClass: Class<*>? = loadClass("com.android.server.am.OomAdjuster")
    val oomAdjusterModernClass: Class<*>? = loadClass("com.android.server.am.OomAdjusterModernImpl")
    val cachedAppOptimizerClass: Class<*>? = loadClass("com.android.server.am.CachedAppOptimizer")
    val deviceIdleControllerClass: Class<*>? = loadClass("com.android.server.DeviceIdleController")
    val npmsClass: Class<*>? = loadClass("com.android.server.net.NetworkPolicyManagerService")
    val nmsClass: Class<*>? = loadClass("com.android.server.notification.NotificationManagerService")
    val computerEngineClass: Class<*>? = loadClass("com.android.server.pm.ComputerEngine")
    val appsFilterBaseClass: Class<*>? =
        loadClass("com.android.server.pm.AppsFilterBase")
            ?: loadClass("com.android.server.pm.AppsFilter")
    val pmsClass: Class<*>? = loadClass("com.android.server.pm.PackageManagerService")

    // ColorOS / Oplus Hans & AppStartup private classes (oplus-services.jar on SYSTEMSERVERCLASSPATH)
    // Supports both legacy ColorOS 12-13 (`com.android.server.am.*`) and ColorOS 14-17 (`com.android.server.hans.*`)
    val oplusHansManagerClass: Class<*>? = loadClass("com.android.server.am.OplusHansManager")
    val oplusHansImportanceClass: Class<*>? =
        loadClass("com.android.server.hans.OplusHansImportance")
            ?: loadClass("com.android.server.am.OplusHansImportance")
    val oplusHansCGroupClass: Class<*>? =
        loadClass("com.android.server.hans.freeze.HansCGroup")
            ?: loadClass("com.android.server.am.HansCGroup")
            ?: loadClass("com.android.server.am.OplusHansCGroup")
    val oplusActiveServicesExtClass: Class<*>? = loadClass("com.android.server.am.ActiveServicesExtImpl")
    val oplusAppStartupManagerClass: Class<*>? = loadClass("com.android.server.am.OplusAppStartupManager")
    val serviceRecordClass: Class<*>? = loadClass("com.android.server.am.ServiceRecord")
    val broadcastFilterClass: Class<*>? = loadClass("com.android.server.am.BroadcastFilter")

    val oomAdjusterImplName: String =
        if (oomAdjusterModernClass != null) "OomAdjusterModernImpl" else "OomAdjuster"

    // Cached ProcessRecord fields (A11 direct vs A12-A17 mState)
    private val prProcessNameField: Field? = findField(processRecordClass, "processName")
    private val prUidField: Field? = findField(processRecordClass, "uid")
    private val prUserIdField: Field? = findField(processRecordClass, "userId")
    private val prInfoField: Field? = findField(processRecordClass, "info")
    private val prPidField: Field? = findField(processRecordClass, "mPid") ?: findField(processRecordClass, "pid")
    private val prStateField: Field? = findField(processRecordClass, "mState")
    private val prOptRecordField: Field? = findField(processRecordClass, "mOptRecord")
    private val prProfileField: Field? = findField(processRecordClass, "mProfile")

    private val optRecordClass: Class<*>? = loadClass("com.android.server.am.ProcessCachedOptimizerRecord")
    private val optIsFrozenMethod: Method? = findMethod(optRecordClass, "isFrozen")
    private val optFrozenField: Field? = findField(optRecordClass, "mFrozen")

    private val profileRecordClass: Class<*>? = loadClass("com.android.server.am.ProcessProfileRecord")
    private val profileLastRssField: Field? = findField(profileRecordClass, "mLastRss")
    private val profileLastPssField: Field? = findField(profileRecordClass, "mLastPss")

    // Direct fields on ProcessRecord (Android 11)
    private val prDirectCurAdjField: Field? = findField(processRecordClass, "curAdj")
    private val prDirectCurProcStateField: Field? = findField(processRecordClass, "mCurProcState")
        ?: findField(processRecordClass, "curProcState")

    // Fields on ProcessStateRecord (Android 12 - 17)
    private val psrCurAdjField: Field? = findField(processStateRecordClass, "mCurAdj")
    private val psrCurProcStateField: Field? = findField(processStateRecordClass, "mCurProcState")
    private val psrSetCurAdjMethod: Method? =
        findMethod(processStateRecordClass, "setCurAdj", Int::class.javaPrimitiveType!!)
    private val psrSetCurProcStateMethod: Method? =
        findMethod(processStateRecordClass, "setCurProcState", Int::class.javaPrimitiveType!!)

    // AMS fields
    private val amsContextField: Field? = findField(amsClass, "mContext")
    private val amsLocalDeviceIdleField: Field? = findField(amsClass, "mLocalDeviceIdleController")
    private val amsProcessListField: Field? = findField(amsClass, "mProcessList")

    // ServiceRecord & BroadcastFilter fields (stable across Android 11-17)
    private val srPackageNameField: Field? = findField(serviceRecordClass, "packageName")
    private val srUserIdField: Field? = findField(serviceRecordClass, "userId")
    private val bfPackageNameField: Field? = findField(broadcastFilterClass, "packageName")
    private val bfOwningUserIdField: Field? = findField(broadcastFilterClass, "owningUserId")

    @Volatile
    var amsInstance: Any? = null
        private set

    fun captureAmsInstance(ams: Any?) {
        if (ams != null && amsInstance == null) {
            amsInstance = ams
        }
    }

    fun getAmsContext(ams: Any? = amsInstance): Context? {
        val target = ams ?: amsInstance ?: return null
        return runCatching { amsContextField?.get(target) as? Context }.getOrNull()
    }

    fun getLocalDeviceIdleController(ams: Any? = amsInstance): Any? {
        val target = ams ?: amsInstance ?: return null
        return runCatching { amsLocalDeviceIdleField?.get(target) }.getOrNull()
    }

    fun getProcessName(processRecord: Any?): String? =
        if (processRecord == null) null
        else runCatching { prProcessNameField?.get(processRecord) as? String }.getOrNull()

    fun getProcessUid(processRecord: Any?): Int =
        if (processRecord == null) -1
        else runCatching { (prUidField?.get(processRecord) as? Int) ?: -1 }.getOrDefault(-1)

    fun getProcessUserId(processRecord: Any?): Int {
        if (processRecord == null) return 0
        val direct = runCatching { prUserIdField?.get(processRecord) as? Int }.getOrNull()
        if (direct != null) return direct
        val uid = getProcessUid(processRecord)
        return if (uid >= 0) uid / PER_USER_RANGE else 0
    }

    fun getProcessPackageName(processRecord: Any?): String? {
        if (processRecord == null) return null
        val appInfo = runCatching { prInfoField?.get(processRecord) as? android.content.pm.ApplicationInfo }.getOrNull()
        return appInfo?.packageName
    }

    fun getProcessPid(processRecord: Any?): Int =
        if (processRecord == null) -1
        else runCatching { (prPidField?.get(processRecord) as? Int) ?: -1 }.getOrDefault(-1)

    fun getServiceRecordPackageName(serviceRecord: Any?): String? =
        if (serviceRecord == null) null
        else runCatching { srPackageNameField?.get(serviceRecord) as? String }.getOrNull()

    fun getServiceRecordUserId(serviceRecord: Any?): Int =
        if (serviceRecord == null) 0
        else runCatching { (srUserIdField?.get(serviceRecord) as? Int) ?: 0 }.getOrDefault(0)

    fun getBroadcastFilterPackageName(broadcastFilter: Any?): String? =
        if (broadcastFilter == null) null
        else runCatching { bfPackageNameField?.get(broadcastFilter) as? String }.getOrNull()

    fun getBroadcastFilterUserId(broadcastFilter: Any?): Int =
        if (broadcastFilter == null) 0
        else runCatching { (bfOwningUserIdField?.get(broadcastFilter) as? Int) ?: 0 }.getOrDefault(0)

    fun getCurAdj(processRecord: Any?): Int? {
        if (processRecord == null) return null
        val stateObj = runCatching { prStateField?.get(processRecord) }.getOrNull()
        val psrField = psrCurAdjField
        if (stateObj != null && psrField != null) {
            return runCatching { psrField.getInt(stateObj) }.getOrNull()
        }
        val directField = prDirectCurAdjField ?: return null
        return runCatching { directField.getInt(processRecord) }.getOrNull()
    }

    fun setCurAdj(processRecord: Any?, newAdj: Int): Boolean {
        if (processRecord == null) return false
        val stateObj = runCatching { prStateField?.get(processRecord) }.getOrNull()
        if (stateObj != null) {
            val setter = psrSetCurAdjMethod
            if (setter != null) {
                return runCatching { setter.invoke(stateObj, newAdj); true }.getOrDefault(false)
            }
            val field = psrCurAdjField
            if (field != null) {
                return runCatching { field.setInt(stateObj, newAdj); true }.getOrDefault(false)
            }
        }
        val directField = prDirectCurAdjField
        return if (directField != null) {
            runCatching { directField.setInt(processRecord, newAdj); true }.getOrDefault(false)
        } else {
            false
        }
    }

    fun getCurProcState(processRecord: Any?): Int? {
        if (processRecord == null) return null
        val stateObj = runCatching { prStateField?.get(processRecord) }.getOrNull()
        val psrField = psrCurProcStateField
        if (stateObj != null && psrField != null) {
            return runCatching { psrField.getInt(stateObj) }.getOrNull()
        }
        val directField = prDirectCurProcStateField ?: return null
        return runCatching { directField.getInt(processRecord) }.getOrNull()
    }

    fun setCurProcState(processRecord: Any?, newProcState: Int): Boolean {
        if (processRecord == null) return false
        val stateObj = runCatching { prStateField?.get(processRecord) }.getOrNull()
        if (stateObj != null) {
            val setter = psrSetCurProcStateMethod
            if (setter != null) {
                return runCatching { setter.invoke(stateObj, newProcState); true }.getOrDefault(false)
            }
            val field = psrCurProcStateField
            if (field != null) {
                return runCatching { field.setInt(stateObj, newProcState); true }.getOrDefault(false)
            }
        }
        val directField = prDirectCurProcStateField
        return if (directField != null) {
            runCatching { directField.setInt(processRecord, newProcState); true }.getOrDefault(false)
        } else {
            false
        }
    }

    fun isProcessAliveInAms(userId: Int, processName: String): Boolean {
        val ams = amsInstance ?: return false
        return runCatching {
            val procList = amsProcessListField?.get(ams) ?: return@runCatching false
            val lruField = findField(procList.javaClass, "mLruProcesses") ?: return@runCatching false
            val lru = lruField.get(procList) as? List<*> ?: return@runCatching false
            // Copy/iterate cautiously without locking AMS
            for (i in 0 until lru.size) {
                val pr = runCatching { lru[i] }.getOrNull() ?: continue
                if (getProcessName(pr) == processName &&
                    getProcessUserId(pr) == userId &&
                    getProcessPid(pr) > 0
                ) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    fun isProcessFrozen(processRecord: Any?): Boolean {
        if (processRecord == null) return false
        val opt = runCatching { prOptRecordField?.get(processRecord) }.getOrNull() ?: return false
        val method = optIsFrozenMethod
        if (method != null) {
            return runCatching { (method.invoke(opt) as? Boolean) ?: false }.getOrDefault(false)
        }
        val field = optFrozenField ?: return false
        return runCatching { field.getBoolean(opt) }.getOrDefault(false)
    }

    fun getProcessRssKb(processRecord: Any?, pid: Int): Long {
        // Fast direct /proc/<pid>/statm read inside system_server (~0.05ms, exact real-time resident RSS)
        if (pid > 0) {
            val procRss = runCatching {
                val content = File("/proc/$pid/statm").readText().trim()
                val parts = content.split(' ')
                if (parts.size >= 2) {
                    val pages = parts[1].toLongOrNull() ?: 0L
                    pages * 4L // 4KB pages -> KB
                } else {
                    0L
                }
            }.getOrDefault(0L)
            if (procRss > 0L) return procRss
        }
        // Fallback to cached ProcessProfileRecord fields
        if (processRecord == null) return 0L
        val profile = runCatching { prProfileField?.get(processRecord) }.getOrNull() ?: return 0L
        val rss = runCatching { profileLastRssField?.getLong(profile) ?: 0L }.getOrDefault(0L)
        if (rss > 0L) return rss
        return runCatching { profileLastPssField?.getLong(profile) ?: 0L }.getOrDefault(0L)
    }

    fun snapshotManagedProcesses(managedPkgUserPairs: Set<Pair<Int, String>>): List<LiveProcessSnapshot> {
        val ams = amsInstance ?: return emptyList()
        if (managedPkgUserPairs.isEmpty()) return emptyList()
        return runCatching {
            val procList = amsProcessListField?.get(ams) ?: return@runCatching emptyList()
            val lruField = findField(procList.javaClass, "mLruProcesses") ?: return@runCatching emptyList()
            val lru = lruField.get(procList) as? List<*> ?: return@runCatching emptyList()
            val results = ArrayList<LiveProcessSnapshot>(managedPkgUserPairs.size * 2)
            for (i in 0 until lru.size) {
                val pr = runCatching { lru[i] }.getOrNull() ?: continue
                val pid = getProcessPid(pr)
                if (pid <= 0) continue
                val pkg = getProcessPackageName(pr) ?: continue
                val userId = getProcessUserId(pr)
                if (!managedPkgUserPairs.contains(userId to pkg)) continue
                val procName = getProcessName(pr) ?: pkg
                val uid = getProcessUid(pr)
                val adj = getCurAdj(pr) ?: 999
                val procState = getCurProcState(pr) ?: 20
                val frozen = isProcessFrozen(pr)
                val rssKb = getProcessRssKb(pr, pid)
                results.add(
                    LiveProcessSnapshot(
                        pkg = pkg,
                        userId = userId,
                        processName = procName,
                        pid = pid,
                        uid = uid,
                        curAdj = adj,
                        curProcState = procState,
                        frozen = frozen,
                        rssKb = rssKb
                    )
                )
            }
            results
        }.getOrDefault(emptyList())
    }

    fun userHandleOf(userId: Int): UserHandle? {
        return runCatching {
            val m = UserHandle::class.java.getDeclaredMethod("of", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(null, userId) as? UserHandle
        }.getOrNull()
    }

    fun loadClass(name: String): Class<*>? =
        runCatching { Class.forName(name, false, classLoader) }.getOrNull()

    fun findField(clazz: Class<*>?, name: String): Field? {
        var cur = clazz
        while (cur != null && cur != Any::class.java) {
            runCatching { cur.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            cur = cur.superclass
        }
        return null
    }

    fun findMethod(clazz: Class<*>?, name: String, vararg paramTypes: Class<*>): Method? {
        var cur = clazz
        while (cur != null && cur != Any::class.java) {
            runCatching { cur.getDeclaredMethod(name, *paramTypes) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            cur = cur.superclass
        }
        return null
    }

    fun findAllMethodsByName(clazz: Class<*>?, name: String): List<Method> {
        if (clazz == null) return emptyList()
        return clazz.declaredMethods
            .filter { it.name == name }
            .onEach { it.isAccessible = true }
    }

    companion object {
        const val PER_USER_RANGE = 100_000

        /**
         * AOSP ActivityManager.PROCESS_STATE_SERVICE = 10 (stable across A11-A17).
         * Keeps the process above PROCESS_STATE_CACHED_* (16..19) so Android 15-17
         * NetworkPolicyManagerService / BpfNetMaps does not drop background sockets.
         */
        const val PROCESS_STATE_SERVICE = 10
    }
}
