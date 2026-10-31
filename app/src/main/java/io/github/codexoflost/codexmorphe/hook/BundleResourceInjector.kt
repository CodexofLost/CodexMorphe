package io.github.codexoflost.codexmorphe.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.WebView

/**
 * Injects compiled AAPT2 resources from the bundle APK into the target app.
 * Injects into both Application context and dynamically to all Activity contexts
 * via ActivityLifecycleCallbacks to ensure layouts and drawables inflate properly.
 */
object BundleResourceInjector {

    private const val TAG = "CodexMorphe/ResInject"

    @Volatile
    private var cachedLoader: Any? = null // ResourcesLoader on API 30+

    @Synchronized
    private fun ensureCachedLoader(pfd: ParcelFileDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cachedLoader == null) {
            runCatching {
                val loader = ResourcesLoader()
                val provider = ResourcesProvider.loadFromApk(pfd.dup())
                loader.addProvider(provider)
                cachedLoader = loader
                Log.d(TAG, "Cached ResourcesLoader initialized from bundle PFD (API ${Build.VERSION.SDK_INT})")
            }.onFailure { t ->
                Log.w(TAG, "ResourcesProvider initialization failed: ${t.message}")
            }
        }
    }

    fun inject(pfd: ParcelFileDescriptor, context: Context) {
        // 1. Initialize API 30+ ResourcesLoader if supported
        ensureCachedLoader(pfd)

        // 2. Inject into the given Context (Application)
        injectContext(pfd, context)

        // 3. Register ActivityLifecycleCallbacks if context is Application
        if (context is Application) {
            registerLifecycleCallbacks(pfd, context)
        }
    }

    fun injectContext(pfd: ParcelFileDescriptor, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ensureCachedLoader(pfd)
            val loader = cachedLoader as? ResourcesLoader
            if (loader != null) {
                runCatching {
                    context.resources.addLoaders(loader)
                    Log.d(TAG, "Attached cached ResourcesLoader to ${context.javaClass.simpleName}")
                }.onFailure { t ->
                    Log.w(TAG, "Failed to addLoaders to ${context.javaClass.simpleName}: ${t.message}")
                }
            }
        } else {
            // AssetManager fallback for Android 9 & 10 (API < 30)
            injectViaAssetManager(pfd, context)
        }
    }

    private fun registerLifecycleCallbacks(pfd: ParcelFileDescriptor, app: Application) {
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    Log.d(TAG, "onActivityCreated: ${activity.javaClass.name}")
                    injectContext(pfd, activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    injectContext(pfd, activity)
                }

                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            Log.i(TAG, "Successfully registered ActivityLifecycleCallbacks for bundle resource injection")
        }.onFailure { t ->
            Log.e(TAG, "Failed registering ActivityLifecycleCallbacks", t)
        }
    }

    private fun injectViaAssetManager(pfd: ParcelFileDescriptor, context: Context) {
        runCatching {
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val method = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
            }
            method.invoke(context.resources.assets, fdPath)
            Log.d(TAG, "Injected resources via AssetManager.addAssetPath($fdPath) to ${context.javaClass.simpleName}")
        }.onFailure { t ->
            Log.e(TAG, "Failed to inject resources via AssetManager to ${context.javaClass.simpleName}", t)
        }
    }
}
