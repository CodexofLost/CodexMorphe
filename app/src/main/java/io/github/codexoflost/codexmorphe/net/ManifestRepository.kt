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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val cacheFile: File
        get() = File(context.filesDir, "manifest_cache.json")

    suspend fun getManifest(forceRefresh: Boolean = false): Result<BundleManifest> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && cacheFile.exists()) {
                runCatching {
                    val cachedJson = cacheFile.readText()
                    val manifest = gson.fromJson(cachedJson, BundleManifest::class.java)
                    if (manifest != null && manifest.applications.isNotEmpty()) {
                        Log.d(TAG, "Loaded manifest from local cache (${manifest.totalBundles} bundles)")
                        return@withContext Result.success(manifest)
                    }
                }
            }

            // Fetch remote manifest
            runCatching {
                val request = Request.Builder().url(CANONICAL_MANIFEST_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}: Failed to fetch manifest")
                    }
                    val json = response.body?.string() ?: throw IllegalStateException("Empty body")
                    val manifest = gson.fromJson(json, BundleManifest::class.java)
                    // Persist to local cache
                    cacheFile.writeText(json)
                    Log.i(TAG, "Fetched fresh manifest: ${manifest.totalBundles} bundles")
                    manifest
                }
            }
        }
}
