package com.synapse.app.xposed

import android.content.Context
import android.content.SharedPreferences
import com.synapse.protocol.IpcProtocol
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class XposedServiceState(
    val connected: Boolean = false,
    val frameworkName: String = "Unbound",
    val frameworkVersion: String = "",
    val apiVersion: Int = 0,
    val scopes: List<String> = emptyList()
)

/**
 * App-side bridge to `io.github.libxposed:service` for reading/writing `RemotePreferences`.
 */
object XposedServiceBridge {

    @Volatile
    private var boundService: XposedService? = null

    private val _state = MutableStateFlow(XposedServiceState())
    val state: StateFlow<XposedServiceState> = _state.asStateFlow()

    fun init(context: Context) {
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    boundService = service
                    _state.value = XposedServiceState(
                        connected = true,
                        frameworkName = service.frameworkName,
                        frameworkVersion = service.frameworkVersion,
                        apiVersion = service.apiVersion,
                        scopes = runCatching { service.scope }.getOrDefault(emptyList())
                    )
                }

                override fun onServiceDied(service: XposedService) {
                    boundService = null
                    _state.value = XposedServiceState(connected = false, frameworkName = "Disconnected")
                }
            })
        }
    }

    fun requestSystemScope(onResult: (Boolean, String) -> Unit) {
        val svc = boundService
        if (svc == null) {
            onResult(false, "XposedService 未连接")
            return
        }
        runCatching {
            val current = runCatching { svc.scope }.getOrDefault(emptyList())
            if (current.contains("android")) {
                runCatching { svc.removeScope(listOf("android")) }
            }
            svc.requestScope(
                listOf("system"),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: MutableList<String>) {
                        refreshScopeState(svc)
                        onResult(true, "已获批作用域: ${approved.joinToString(",")}，请重启设备生效")
                    }

                    override fun onScopeRequestFailed(message: String) {
                        refreshScopeState(svc)
                        onResult(false, "作用域申请失败: $message")
                    }
                }
            )
        }.onFailure { t ->
            onResult(false, "调用 requestScope 异常: ${t.message}")
        }
    }

    private fun refreshScopeState(service: XposedService) {
        _state.value = XposedServiceState(
            connected = true,
            frameworkName = service.frameworkName,
            frameworkVersion = service.frameworkVersion,
            apiVersion = service.apiVersion,
            scopes = runCatching { service.scope }.getOrDefault(emptyList())
        )
    }

    fun writeRemotePolicyJson(context: Context, json: String): Boolean {
        // Always persist a local mirror first so cold starts / overwrite installs never lose rules
        // even before XposedServiceHelper.onServiceBind() finishes asynchronously.
        val localOk = context.getSharedPreferences(IpcProtocol.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(IpcProtocol.PREFS_KEY_BUNDLE_JSON, json)
            .commit()

        val svc = boundService
        val remoteOk = if (svc != null) {
            runCatching {
                val prefs: SharedPreferences = svc.getRemotePreferences(IpcProtocol.PREFS_NAME)
                prefs.edit().putString(IpcProtocol.PREFS_KEY_BUNDLE_JSON, json).commit()
            }.getOrDefault(false)
        } else {
            false
        }
        return remoteOk || localOk
    }

    fun readRemotePolicyJsonOnly(): String? {
        val svc = boundService ?: return null
        return runCatching {
            svc.getRemotePreferences(IpcProtocol.PREFS_NAME)
                .getString(IpcProtocol.PREFS_KEY_BUNDLE_JSON, null)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun readLocalPolicyJsonOnly(context: Context): String? {
        return context.getSharedPreferences(IpcProtocol.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(IpcProtocol.PREFS_KEY_BUNDLE_JSON, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun readPolicyJson(context: Context): String? {
        return readRemotePolicyJsonOnly() ?: readLocalPolicyJsonOnly(context)
    }

    fun clearAllRemotePreferences(context: Context) {
        runCatching { boundService?.deleteRemotePreferences(IpcProtocol.PREFS_NAME) }
        context.getSharedPreferences(IpcProtocol.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
