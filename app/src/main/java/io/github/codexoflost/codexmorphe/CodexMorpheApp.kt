package io.github.codexoflost.codexmorphe

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class CodexMorpheApp : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "CodexMorphe/App"

        @Volatile
        var mService: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<(XposedService?) -> Unit>()

        fun addServiceListener(listener: (XposedService?) -> Unit) {
            listeners.add(listener)
            listener(mService)
        }

        fun removeServiceListener(listener: (XposedService?) -> Unit) {
            listeners.remove(listener)
        }

        val isModuleActive: Boolean
            get() = mService != null
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Registering XposedServiceHelper listener...")
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.i(TAG, "XposedService bound successfully! API version: ${runCatching { service.apiVersion }.getOrNull()}")
        mService = service
        listeners.forEach { it(service) }
    }

    override fun onServiceDied(service: XposedService) {
        Log.w(TAG, "XposedService disconnected / died.")
        mService = null
        listeners.forEach { it(null) }
    }
}
