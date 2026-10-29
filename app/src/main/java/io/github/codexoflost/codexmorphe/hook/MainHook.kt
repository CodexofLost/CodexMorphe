package io.github.codexoflost.codexmorphe.hook

import android.app.Application
import android.util.Log
import de.robv.android.xposed.XposedHelpers
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern LibXposed API 102 Module Entrypoint.
 */
class MainHook : XposedModule() {

    companion object {
        private const val TAG = "CodexMorphe/MainHook"
        var modulePath: String? = null
            private set
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        modulePath = moduleApplicationInfo.sourceDir
        Log.i(TAG, "CodexMorphe module loaded from $modulePath")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return

        val packageName = param.packageName
        Log.d(TAG, "Target package ready: $packageName")

        // 1. Read LSPosed Remote Preferences for this package
        val prefs = runCatching {
            getRemotePreferences(packageName)
        }.getOrNull() ?: runCatching {
            getRemotePreferences("prefs")
        }.getOrNull()

        if (prefs == null) {
            Log.w(TAG, "Could not resolve remote preferences for $packageName")
            return
        }

        val isAppEnabled = prefs.getBoolean("app_enabled_$packageName", true)
        if (!isAppEnabled) {
            Log.d(TAG, "App $packageName is disabled in CodexMorphe preferences; skipping hook.")
            return
        }

        val author = prefs.getString("provider_$packageName", "official") ?: "official"
        val bundleName = "$packageName-$author-bundle.apk"

        // 2. Request Bundle File Descriptor from Module Storage via IPC
        var pfd = runCatching { openRemoteFile(bundleName) }.getOrNull()
        if (pfd == null) {
            // Fallback to legacy unflavored name if present
            pfd = runCatching { openRemoteFile("$packageName-bundle.apk") }.getOrNull()
        }

        if (pfd == null) {
            Log.d(TAG, "No bundle APK installed for $packageName ($bundleName); skipping hook.")
            return
        }

        Log.i(TAG, "Streaming bundle $bundleName for $packageName via openRemoteFile")

        // 3. Hook Application.onCreate to attach context & inject bundle
        val appClassName = param.applicationInfo.className ?: "android.app.Application"
        val appClass = runCatching {
            XposedHelpers.findClass(appClassName, param.classLoader)
        }.getOrElse {
            Application::class.java
        }

        runCatching {
            val onCreateMethod = appClass.getMethod("onCreate")
            onCreateMethod.hookBefore { hookParam ->
                val app = hookParam.thisObject as? Application ?: return@hookBefore
                Log.d(TAG, "Target Application.onCreate reached for $packageName")

                try {
                    // Inject resources
                    BundleResourceInjector.inject(pfd, app)

                    // Load MultiDex classes directly into ART memory
                    val bundleLoader = BundleClassLoader.create(pfd, param.classLoader)

                    // Hook UI framework inflation for Morphe views/drawables
                    ExtensionClassLoading.hook(bundleLoader)

                    // Dispatch DexKit hooks
                    DexKitHookDispatcher(app, param.classLoader, bundleLoader, prefs).dispatch()

                    Log.i(TAG, "Successfully injected CodexMorphe into $packageName")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error injecting CodexMorphe bundle into $packageName", t)
                }
            }
        }.onFailure { t ->
            Log.e(TAG, "Failed to hook Application.onCreate for $packageName", t)
        }
    }
}
