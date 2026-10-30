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

        val activeAuthor = prefs.getString("provider_$packageName", null)
            ?: prefs.getString("active_author", null)
        val requestedBundleName = if (!activeAuthor.isNullOrEmpty()) "$packageName-$activeAuthor-bundle.apk" else null

        // 2. Query available remote files via LibXposed API 102 IPC
        val remoteFiles = runCatching { listRemoteFiles() }.getOrNull() ?: emptyArray()
        Log.d(TAG, "Available remote files: ${remoteFiles.joinToString()}")

        val resolvedBundleName = when {
            requestedBundleName != null && remoteFiles.contains(requestedBundleName) -> requestedBundleName
            requestedBundleName != null -> remoteFiles.firstOrNull { it.startsWith("$packageName-") && it.contains(activeAuthor!!) && it.endsWith("-bundle.apk") }
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
        val candidateClasses = mutableListOf<Class<*>>()
        if (param.applicationInfo.className != null) {
            runCatching { Class.forName(param.applicationInfo.className, false, param.classLoader) }
                .getOrNull()?.let { candidateClasses.add(it) }
        }
        listOf(
            "com.instagram.app.InstagramAppShell",
            "com.google.android.apps.youtube.app.YouTubeApplication"
        ).forEach { knownShell ->
            runCatching { Class.forName(knownShell, false, param.classLoader) }
                .getOrNull()?.let { candidateClasses.add(it) }
        }
        candidateClasses.add(Application::class.java)

        val injected = java.util.concurrent.atomic.AtomicBoolean(false)
        var hookCount = 0

        for (targetCls in candidateClasses.distinct()) {
            val onCreateMethod = runCatching { targetCls.getDeclaredMethod("onCreate") }.getOrNull()
                ?: runCatching { targetCls.getMethod("onCreate") }.getOrNull()

            if (onCreateMethod != null) {
                hookCount++
                hook(onCreateMethod).intercept { chain ->
                    val origResult = chain.proceed()
                    val app = (chain.thisObject as? Application)
                        ?: (chain.thisObject as? android.content.ContextWrapper)?.baseContext as? Application

                    if (app != null && injected.compareAndSet(false, true)) {
                        Log.d(TAG, "Target Application.onCreate completed for $packageName on ${targetCls.name}; injecting CodexMorphe...")
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
                    origResult
                }
            }
        }

        if (hookCount == 0) {
            Log.w(TAG, "Application.onCreate not found for candidates in $packageName")
        }
    }
}
