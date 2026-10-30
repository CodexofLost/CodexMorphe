package io.github.codexoflost.codexmorphe.hook

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        // Find and invoke setContext(app) across supported community patch authors
        val utilsCandidates = listOf(
            "app.morphe.extension.shared.Utils",
            "app.hushgram.extension.shared.Utils",
            "app.piko.extension.shared.Utils",
            "app.rushiranpise.extension.shared.Utils",
            "app.devanced.extension.shared.Utils",
            "app.okish.extension.shared.Utils",
            "app.morphe.extension.shared.utils.Utils"
        )
        for (className in utilsCandidates) {
            val success = runCatching {
                val clazz = bundleLoader.loadClass(className)
                val method = clazz.declaredMethods.firstOrNull {
                    it.name == "setContext" && it.parameterTypes.size == 1 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
                }?.apply { isAccessible = true }
                method?.invoke(null, app)
                Log.d(TAG, "Successfully initialized context via $className")
                true
            }.getOrDefault(false)
            if (success) break
        }
    }

    private fun initializeExtensions(bridge: DexKitBridge) {
        // Candidate initializers across authors and applications
        val entryCandidates = listOf(
            "app.hushgram.extension.instagram.settings.SettingsEntry",
            "app.piko.extension.instagram.settings.SettingsEntry",
            "app.piko.extension.twitter.settings.SettingsEntry",
            "app.morphe.extension.reddit.settings.SettingsEntry",
            "app.morphe.extension.youtube.addon.AddOnApi",
            "app.morphe.extension.music.addon.AddOnApi"
        )

        for (className in entryCandidates) {
            runCatching {
                val clazz = bundleLoader.loadClass(className)
                val entryMethod = clazz.declaredMethods.firstOrNull { m ->
                    java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    (m.name == "onApplicationCreate" || m.name == "initialize" || m.name == "init") &&
                    m.parameterTypes.size == 1 &&
                    (Context::class.java.isAssignableFrom(m.parameterTypes[0]) || Application::class.java.isAssignableFrom(m.parameterTypes[0]))
                }?.apply { isAccessible = true }

                if (entryMethod != null) {
                    entryMethod.invoke(null, app)
                    Log.i(TAG, "Successfully invoked extension entrypoint: $className.${entryMethod.name}")
                }
            }
        }

        // Initialize SponsorBlock if toggle is enabled (defaults to true)
        val sponsorBlockEnabled = prefs.getBoolean("morphe_sponsorblock", true)
        if (sponsorBlockEnabled) {
            val sbCandidates = listOf(
                "app.morphe.extension.shared.sponsorblock.SponsorBlockApi",
                "app.hushgram.extension.shared.sponsorblock.SponsorBlockApi",
                "app.piko.extension.shared.sponsorblock.SponsorBlockApi"
            )
            for (sbName in sbCandidates) {
                val sbClass = runCatching { bundleLoader.loadClass(sbName) }.getOrNull()
                if (sbClass != null) {
                    Log.d(TAG, "SponsorBlock enabled and class loaded: $sbName")
                    break
                }
            }
        }
    }
}
