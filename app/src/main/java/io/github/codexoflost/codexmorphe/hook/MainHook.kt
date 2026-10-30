package io.github.codexoflost.codexmorphe.hook

import android.app.Application
import android.util.Log
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
        var moduleNativeLibDir: String? = null
            private set
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        modulePath = moduleApplicationInfo.sourceDir
        moduleNativeLibDir = moduleApplicationInfo.nativeLibraryDir
        Log.i(TAG, "CodexMorphe module loaded for process ${param.processName} from $modulePath (API $apiVersion)")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        Log.d(TAG, "onPackageReady: $packageName, isFirstPackage: ${param.isFirstPackage}")

        if (!param.isFirstPackage) return

        // 1. Query remote preferences for active author
        val prefs = runCatching {
            getRemotePreferences(packageName)
        }.getOrNull() ?: run {
            Log.w(TAG, "Unable to load remote preferences for $packageName")
            return
        }

        val activeAuthor = prefs.getString("active_author", null)
        val requestedBundleName = if (!activeAuthor.isNullOrEmpty()) "$packageName-$activeAuthor-bundle.apk" else null

        // 2. Query available remote files via LibXposed API 102 IPC
        val remoteFiles = runCatching { listRemoteFiles() }.getOrNull() ?: emptyArray()
        Log.d(TAG, "Available remote files: ${remoteFiles.joinToString()}")

        val resolvedBundleName = when {
            requestedBundleName != null && remoteFiles.contains(requestedBundleName) -> requestedBundleName
            remoteFiles.contains("$packageName-official-bundle.apk") -> "$packageName-official-bundle.apk"
            else -> remoteFiles.firstOrNull { it.startsWith("$packageName-") && it.endsWith("-bundle.apk") }
                ?: requestedBundleName
        }

        val pfd = if (resolvedBundleName != null) {
            runCatching { openRemoteFile(resolvedBundleName) }.getOrNull() ?: runCatching {
                // Direct file fallback if module is running in system context
                val directFile = java.io.File("/data/adb/lspd/modules/0/io.github.codexoflost.codexmorphe/files/$resolvedBundleName")
                if (directFile.exists() && directFile.canRead()) {
                    android.os.ParcelFileDescriptor.open(directFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                } else null
            }.getOrNull()
        } else null

        if (pfd == null) {
            Log.d(TAG, "No bundle APK installed for $packageName; skipping hook.")
            return
        }

        Log.i(TAG, "Streaming bundle $resolvedBundleName for $packageName via openRemoteFile")

        // 3. Hook Application.onCreate to attach context & inject bundle
        val appClassName = param.applicationInfo.className ?: "android.app.Application"
        val appClass = runCatching {
            Class.forName(appClassName, false, param.classLoader)
        }.getOrElse {
            Application::class.java
        }

        val onCreateMethod = runCatching {
            appClass.getMethod("onCreate")
        }.getOrNull()

        if (onCreateMethod != null) {
            hook(onCreateMethod).intercept { chain ->
                val app = chain.thisObject as? Application
                if (app != null) {
                    Log.d(TAG, "Target Application.onCreate reached for $packageName")
                    try {
                        // Inject resources
                        BundleResourceInjector.inject(pfd, app)

                        // Load MultiDex classes directly into ART memory
                        val bundleLoader = BundleClassLoader.create(pfd, param.classLoader)

                        // Hook UI framework inflation for Morphe views/drawables
                        ExtensionClassLoading.hook(this@MainHook, bundleLoader)

                        // Dispatch dynamic DexKit hooks from bundle recipes
                        DexKitHookDispatcher(app, param.classLoader, bundleLoader, pfd, prefs, this@MainHook).dispatch()

                        Log.i(TAG, "Successfully injected CodexMorphe into $packageName")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed injecting CodexMorphe into $packageName: ${t.message}", t)
                    }
                }
                chain.proceed()
            }
        } else {
            Log.w(TAG, "Application.onCreate not found for $appClassName in $packageName")
        }
    }
}
