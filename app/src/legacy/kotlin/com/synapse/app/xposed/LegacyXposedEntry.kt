package com.synapse.app.xposed

import android.content.Context
import android.util.Log
import com.synapse.app.BuildConfig
import com.synapse.protocol.IpcProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class XposedServiceState(
    val connected: Boolean = false,
    val frameworkName: String = "Legacy API 82",
    val frameworkVersion: String = "",
    val apiVersion: Int = 82,
    val scopes: List<String> = listOf("android")
)

/**
 * Legacy API 82 fallback storage bridge (uses MODE_WORLD_READABLE when available on LSPosed v1.9.x).
 */
object XposedServiceBridge {

    private val _state = MutableStateFlow(XposedServiceState())
    val state: StateFlow<XposedServiceState> = _state.asStateFlow()

    fun init(context: Context) {
        _state.value = XposedServiceState(
            connected = true,
            frameworkName = "Legacy Xposed (API 82)",
            frameworkVersion = "1.9.x",
            apiVersion = 82
        )
    }

    fun requestSystemScope(onResult: (Boolean, String) -> Unit) {
        onResult(false, "Legacy API 82 请在管理器手动勾选系统框架 (android)")
    }

    @Suppress("DEPRECATION")
    fun writeRemotePolicyJson(context: Context, json: String): Boolean {
        val prefs = runCatching {
            context.getSharedPreferences(IpcProtocol.PREFS_NAME, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            context.getSharedPreferences(IpcProtocol.PREFS_NAME, Context.MODE_PRIVATE)
        }
        return prefs.edit().putString(IpcProtocol.PREFS_KEY_BUNDLE_JSON, json).commit()
    }

    fun readPolicyJson(context: Context): String? {
        return context.getSharedPreferences(IpcProtocol.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(IpcProtocol.PREFS_KEY_BUNDLE_JSON, null)
    }

    fun clearAllRemotePreferences(context: Context) {
        context.getSharedPreferences(IpcProtocol.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}

/**
 * Legacy API 82 entry placeholder for LSPosed v1.9.x compatibility builds.
 * Boots MicroKernel when loaded into `android` (`system_server`).
 */
class LegacyXposedEntry {
    fun handleLoadPackage(lpparam: Any) {
        val pkg = runCatching { lpparam.javaClass.getField("packageName").get(lpparam) as? String }.getOrNull()
        if (pkg != "android") return
        Log.i("SynapseLegacy", "Legacy entry loaded for ${BuildConfig.STEALTH_PACKAGE_NAME}; use modern flavor for libxposed API 101/102.")
    }
}
