package io.github.codexoflost.codexmorphe.net

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import io.github.codexoflost.codexmorphe.model.BundleManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class ManifestRepository(private val context: Context) {

    companion object {
        private const val TAG = "CodexMorphe/Manifest"
        const val CANONICAL_MANIFEST_URL =
            "https://github.com/CodexofLost/codexmorphe-builder/releases/download/bundles/bundle-manifest.json"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val gson = Gson()
    private val cacheFile: File
        get() = File(context.filesDir, "manifest_cache.json")

    suspend fun getManifest(forceRefresh: Boolean = false): Result<BundleManifest> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cacheFile.exists()) {
                val cached = loadFromCache()
                if (cached != null) return@withContext Result.success(cached)
            }

            // Fetch remote manifest from canonical GitHub rolling release
            val remoteResult = runCatching {
                val request = Request.Builder()
                    .url(CANONICAL_MANIFEST_URL)
                    .header("User-Agent", "CodexMorphe/1.0 (Android)")
                    .header("Accept", "application/json, */*")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: Failed to fetch manifest from GitHub")
                    }
                    val json = response.body?.string() ?: throw IllegalStateException("Empty body from manifest URL")
                    val manifest = gson.fromJson(json, BundleManifest::class.java)
                        ?: throw IllegalStateException("Malformed JSON in bundle manifest")

                    if (manifest.applications.isEmpty()) {
                        throw IllegalStateException("Remote manifest returned empty application catalog")
                    }

                    // Persist to local cache
                    cacheFile.writeText(json)
                    Log.i(TAG, "Fetched fresh manifest: ${manifest.totalBundles} bundles across ${manifest.applications.size} apps")
                    manifest
                }
            }

            // If remote fetch succeeded, return it; otherwise fallback to cached version or bundled assets
            remoteResult.recoverCatching { error ->
                Log.w(TAG, "Remote manifest fetch failed: ${error.message}, falling back to cache or assets")
                loadFromCache() ?: loadFromAssets() ?: throw error
            }
        }

    private fun loadFromCache(): BundleManifest? {
        if (!cacheFile.exists()) return null
        return runCatching {
            val cachedJson = cacheFile.readText()
            val manifest = gson.fromJson(cachedJson, BundleManifest::class.java)
            if (manifest != null && manifest.applications.isNotEmpty()) {
                Log.d(TAG, "Loaded manifest from local cache (${manifest.totalBundles} bundles)")
                manifest
            } else {
                Log.w(TAG, "Local cache manifest is empty or invalid; purging stale cache")
                cacheFile.delete()
                null
            }
        }.getOrNull()
    }

    private fun loadFromAssets(): BundleManifest? {
        return runCatching {
            context.assets.open("bundle-manifest.json").use { input ->
                val json = input.bufferedReader().use { it.readText() }
                val manifest = gson.fromJson(json, BundleManifest::class.java)
                if (manifest != null && manifest.applications.isNotEmpty()) {
                    Log.i(TAG, "Loaded fallback manifest from APK assets (${manifest.totalBundles} bundles across ${manifest.applications.size} apps)")
                    manifest
                } else null
            }
        }.getOrNull()
    }
}
