package com.synapse.app

import android.app.Application
import com.synapse.app.xposed.XposedServiceBridge

class SynapseApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        XposedServiceBridge.init(this)
    }
}
