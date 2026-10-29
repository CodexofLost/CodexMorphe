package io.github.codexoflost.codexmorphe.hook

import android.content.Context
import android.content.res.AssetManager
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import de.robv.android.xposed.XposedHelpers

/**
 * Injects compiled AAPT2 resources from the bundle APK into the target app.
 */
object BundleResourceInjector {

    private const val TAG = "CodexMorphe/ResInject"

    fun inject(pfd: ParcelFileDescriptor, context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val loader = ResourcesLoader()
                val provider = ResourcesProvider.loadFromApk(pfd.dup())
                loader.addProvider(provider)
                context.resources.addLoaders(loader)
                Log.d(TAG, "Successfully injected resources via ResourcesProvider (API ${Build.VERSION.SDK_INT})")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "ResourcesProvider injection failed, falling back to addAssetPath: ${t.message}")
            }
        }

        // Fallback for API 28-29 or on loader failure
        injectViaAssetManager(pfd, context)
    }

    private fun injectViaAssetManager(pfd: ParcelFileDescriptor, context: Context) {
        runCatching {
            val fdPath = "/proc/self/fd/${pfd.fd}"
            XposedHelpers.callMethod(context.resources.assets, "addAssetPath", fdPath)
            Log.d(TAG, "Injected resources via AssetManager.addAssetPath($fdPath)")
        }.onFailure { t ->
            Log.e(TAG, "Failed to inject resources via AssetManager", t)
        }
    }
}
