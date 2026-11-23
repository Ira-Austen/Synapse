package com.synapse.app.xposed

import android.content.SharedPreferences
import android.util.Log
import com.synapse.app.BuildConfig
import com.synapse.kernel.core.HookBridge
import com.synapse.kernel.core.HookCall
import com.synapse.kernel.core.HookInterceptor
import com.synapse.kernel.core.MicroKernel
import com.synapse.kernel.core.UnhookHandle
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Executable
import java.lang.reflect.Member

/**
 * Modern libxposed API 101/102 module entry point.
 *
 * Contract:
 * - Public no-arg constructor (zero initialization work inside constructor).
 * - Strictly scoped to `android` (`system_server`) via `META-INF/xposed/scope.list` + `staticScope=true`.
 */
class ModernXposedEntry : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        if (BuildConfig.DEBUG) {
            Log.i("SynapseKernel", "onModuleLoaded: process=${param.processName} isSystemServer=${param.isSystemServer}")
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (BuildConfig.DEBUG) {
            Log.i("SynapseKernel", "onSystemServerStarting: cl=${param.classLoader} magic=${BuildConfig.IPC_MAGIC_CODE}")
        }
        val bridge = LibXposedHookBridge(this)
        MicroKernel.boot(
            classLoader = param.classLoader,
            bridge = bridge,
            magicCode = BuildConfig.IPC_MAGIC_CODE,
            headerMagic = BuildConfig.IPC_HEADER_MAGIC
        )
    }
}

private class LibXposedHookBridge(
    private val module: XposedModule
) : HookBridge {

    override val frameworkName: String
        get() = runCatching { module.frameworkName }.getOrDefault("libxposed")

    override val frameworkApiVersion: Int
        get() = runCatching { module.apiVersion }.getOrDefault(XposedInterface.API_101)

    override val modulePackageName: String
        get() = runCatching { module.moduleApplicationInfo.packageName }
            .getOrDefault(BuildConfig.STEALTH_PACKAGE_NAME)

    private val strongListeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun hook(member: Member, interceptor: HookInterceptor): UnhookHandle {
        val executable = member as? Executable
            ?: throw IllegalArgumentException("libxposed only hooks Executable members, got $member")
        val handle = module.hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val call = object : HookCall {
                    override val member: Member = chain.executable
                    override val thisObject: Any? = chain.thisObject
                    override val args: Array<Any?> = chain.args.toTypedArray()
                    override fun proceed(): Any? = chain.proceed()
                    override fun proceed(vararg newArgs: Any?): Any? = chain.proceed(arrayOf(*newArgs))
                }
                interceptor.intercept(call)
            }
        return object : UnhookHandle {
            override fun unhook() {
                handle.unhook()
            }
        }
    }

    override fun readRemotePrefsJson(prefsName: String, key: String): String? {
        val prefs = module.getRemotePreferences(prefsName)
        return prefs.getString(key, null)
    }

    override fun registerRemotePrefsListener(
        prefsName: String,
        key: String,
        onChanged: (String?) -> Unit
    ) {
        val prefs = module.getRemotePreferences(prefsName)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changedKey ->
            if (changedKey == null || changedKey == key) {
                onChanged(sp.getString(key, null))
            }
        }
        strongListeners.add(listener)
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun logInfo(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i("SynapseKernel", "[$tag] $message")
        runCatching { module.log(Log.INFO, tag, message) }
    }

    override fun logError(tag: String, message: String, throwable: Throwable?) {
        if (BuildConfig.DEBUG) Log.e("SynapseKernel", "[$tag] $message", throwable)
        runCatching {
            if (throwable != null) {
                module.log(Log.ERROR, tag, message, throwable)
            } else {
                module.log(Log.ERROR, tag, message)
            }
        }
    }
}
