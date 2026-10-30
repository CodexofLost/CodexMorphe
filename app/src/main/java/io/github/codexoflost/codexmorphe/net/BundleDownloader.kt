package io.github.codexoflost.codexmorphe.net

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Progress(val percent: Int, val bytesRead: Long, val totalBytes: Long) : DownloadState()
    data class Success(val bundleFile: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class BundleDownloader(private val context: Context) {

    companion object {
        private const val TAG = "CodexMorphe/Downloader"
        const val BASE_DOWNLOAD_URL =
            "https://github.com/CodexofLost/codexmorphe-builder/releases/download/bundles/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val bundlesDir: File
        get() = File(context.filesDir, "bundles").apply { mkdirs() }

    fun computeSha256(file: File): String {
        if (!file.exists()) return ""
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    fun isBundleUpToDate(file: File, expectedSha256: String): Boolean {
        if (!file.exists() || expectedSha256.isBlank()) return false
        val localHash = computeSha256(file)
        return localHash.equals(expectedSha256, ignoreCase = true)
    }

    fun downloadBundle(
        packageName: String,
        assetName: String,
        expectedSha256: String,
        explicitDownloadUrl: String? = null
    ): Flow<DownloadState> = flow {
        val rawUrl = explicitDownloadUrl?.takeIf { it.isNotBlank() } ?: "$BASE_DOWNLOAD_URL$assetName"
        val downloadUrl = rawUrl.replace("CodexMorphe/codexmorphe-builder", "CodexofLost/codexmorphe-builder")
        Log.i(TAG, "Starting download: $downloadUrl")

        val tempFile = File(bundlesDir, "$assetName.tmp")
        val destinationFile = File(bundlesDir, assetName)

        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "CodexMorphe/1.0 (Android)")
                .header("Accept", "application/octet-stream, application/vnd.android.package-archive, */*")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadState.Error("HTTP ${response.code}: Download failed"))
                    return@flow
                }

                val body = response.body ?: run {
                    emit(DownloadState.Error("Empty response body"))
                    return@flow
                }

                val contentLength = body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                var totalBytesRead = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (contentLength > 0) {
                                val percent = ((totalBytesRead * 100) / contentLength).toInt()
                                emit(DownloadState.Progress(percent, totalBytesRead, contentLength))
                            }
                        }
                    }
                }

                // Verify SHA-256 with rolling build reconciliation
                val calculatedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                var hashValid = expectedSha256.isBlank() || calculatedSha256.equals(expectedSha256, ignoreCase = true)

                if (!hashValid) {
                    // Check against live rolling manifest before rejecting
                    Log.w(TAG, "Hash mismatch with cached value ($expectedSha256 vs $calculatedSha256), checking live manifest...")
                    val liveManifest = ManifestRepository(context).getManifest(forceRefresh = true).getOrNull()
                    val liveProvider = liveManifest?.applications?.get(packageName)?.providers?.values?.find { it.assetName == assetName }
                    if (liveProvider != null && liveProvider.sha256.equals(calculatedSha256, ignoreCase = true)) {
                        Log.i(TAG, "SHA-256 verified against live rolling release manifest!")
                        hashValid = true
                    }
                }

                if (!hashValid) {
                    tempFile.delete()
                    Log.e(TAG, "SHA-256 mismatch! Expected: $expectedSha256, got: $calculatedSha256")
                    emit(DownloadState.Error("SHA-256 verification failed"))
                    return@flow
                }

                // Atomic rename
                if (destinationFile.exists()) destinationFile.delete()
                if (!tempFile.renameTo(destinationFile)) {
                    tempFile.copyTo(destinationFile, overwrite = true)
                    tempFile.delete()
                }

                // Clean up any older bundles for this package
                cleanSupersededBundles(packageName, currentAsset = assetName)

                // Sync to LSPosed framework remote file store
                syncToRemoteStore(assetName, destinationFile)

                Log.i(TAG, "Successfully downloaded & verified: ${destinationFile.absolutePath}")
                emit(DownloadState.Success(destinationFile))
            }
        } catch (t: Throwable) {
            tempFile.delete()
            Log.e(TAG, "Download error", t)
            emit(DownloadState.Error(t.message ?: "Download failed"))
        }
    }.flowOn(Dispatchers.IO)

    private fun syncToRemoteStore(assetName: String, localFile: File) {
        // 1. Try LSPosed XposedService.openRemoteFile if bound
        val service = io.github.codexoflost.codexmorphe.CodexMorpheApp.mService
        if (service != null) {
            runCatching {
                service.deleteRemoteFile(assetName)
                service.openRemoteFile(assetName)?.use { pfd ->
                    java.io.FileOutputStream(pfd.fileDescriptor).use { outStream ->
                        localFile.inputStream().use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    Log.i(TAG, "Synced bundle $assetName via XposedService.openRemoteFile")
                    return
                }
            }.onFailure { Log.w(TAG, "Failed syncing via XposedService: ${it.message}") }
        }

        // 2. Direct privileged bridge fallback (Shizuku / su)
        runCatching {
            val cmd = arrayOf(
                "su", "-c",
                "mkdir -p /data/adb/lspd/modules/0/io.github.codexoflost.codexmorphe/files && " +
                "cp '${localFile.absolutePath}' '/data/adb/lspd/modules/0/io.github.codexoflost.codexmorphe/files/$assetName' && " +
                "chcon -R u:object_r:lsposed_file:s0 /data/adb/lspd/modules/0/io.github.codexoflost.codexmorphe && " +
                "chmod -R 755 /data/adb/lspd/modules/0/io.github.codexoflost.codexmorphe"
            )
            Runtime.getRuntime().exec(cmd).waitFor()
            Log.i(TAG, "Synced bundle $assetName via privileged bridge")
        }
    }

    private fun cleanSupersededBundles(packageName: String, currentAsset: String) {
        runCatching {
            bundlesDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(packageName) && file.name.endsWith(".apk") && file.name != currentAsset) {
                    Log.d(TAG, "Removing superseded bundle: ${file.name}")
                    file.delete()
                }
            }
        }
    }
}
