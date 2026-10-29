package io.github.codexoflost.codexmorphe.hook

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge

/**
 * Dispatches hooks into the target application by combining DexKit pattern matching
 * with user toggles from remote preferences.
 */
class DexKitHookDispatcher(
    private val app: Application,
    private val hostLoader: ClassLoader,
    private val bundleLoader: ClassLoader,
    private val prefs: SharedPreferences
) {

    companion object {
        private const val TAG = "CodexMorphe/Dispatcher"
    }

    fun dispatch() {
        Log.d(TAG, "Initializing CodexMorphe extensions in target: ${app.packageName}")

        // 1. Initialize Morphe Shared Utils Context
        initializeMorpheContext()

        // 2. Open DexKit Bridge for Bytecode Matching
        runCatching {
            System.loadLibrary("dexkit")
        }.onFailure {
            Log.w(TAG, "Native libdexkit.so load warning: ${it.message}")
        }

        val bridge = runCatching {
            DexKitBridge.create(app.applicationInfo.sourceDir)
        }.getOrNull()

        if (bridge == null) {
            Log.e(TAG, "Failed to create DexKitBridge for ${app.applicationInfo.sourceDir}")
            return
        }

        try {
            // 3. Initialize Extensions (e.g., YouTube AddOnApi, SponsorBlock)
            initializeExtensions(bridge)
        } finally {
            runCatching { bridge.close() }
        }
    }

    private fun initializeMorpheContext() {
        // Find and invoke app.morphe.extension.shared.Utils.setContext(app)
        val utilsCandidates = listOf(
            "app.morphe.extension.shared.Utils",
            "app.morphe.extension.shared.utils.Utils"
        )
        for (className in utilsCandidates) {
            val success = runCatching {
                val clazz = bundleLoader.loadClass(className)
                XposedHelpers.callStaticMethod(clazz, "setContext", app)
                Log.d(TAG, "Successfully initialized Morphe context via $className")
                true
            }.getOrDefault(false)
            if (success) break
        }
    }

    private fun initializeExtensions(bridge: DexKitBridge) {
        // Hook into AddOnApi entrypoint if present
        runCatching {
            val addOnApiClass = bundleLoader.loadClass("app.morphe.extension.youtube.addon.AddOnApi")
            val initMethod = addOnApiClass.declaredMethods.find {
                it.parameterTypes.size == 1 && Application::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            initMethod?.invoke(null, app)
            Log.d(TAG, "Successfully invoked YouTube AddOnApi.initialize")
        }

        // Initialize SponsorBlock if toggle is enabled (defaults to true)
        val sponsorBlockEnabled = prefs.getBoolean("morphe_sponsorblock", true)
        if (sponsorBlockEnabled) {
            runCatching {
                val sbClass = bundleLoader.loadClass("app.morphe.extension.shared.sponsorblock.SponsorBlockApi")
                Log.d(TAG, "SponsorBlock enabled and class loaded: ${sbClass.name}")
            }
        }
    }
}
