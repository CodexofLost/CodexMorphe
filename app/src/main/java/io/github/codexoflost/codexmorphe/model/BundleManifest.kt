package io.github.codexoflost.codexmorphe.model

import com.google.gson.annotations.SerializedName

data class BundleManifest(
    @SerializedName("version") val schemaVersion: Int = 1,
    @SerializedName("generated_at") val generatedAt: String = "",
    @SerializedName("apps") val applications: Map<String, ApplicationEntry> = emptyMap()
) {
    val totalBundles: Int
        get() = applications.values.sumOf { it.providers.size }
}

data class ApplicationEntry(
    @SerializedName("app_name") val displayName: String = "",
    @SerializedName("default_provider") val defaultProvider: String = "",
    @SerializedName("providers") val providers: Map<String, ProviderEntry> = emptyMap()
)

data class ProviderEntry(
    @SerializedName("source_id") val id: String = "",
    @SerializedName("author") val author: String = "",
    @SerializedName("version") val version: String = "",
    @SerializedName("bundle_file") val assetName: String = "",
    @SerializedName("download_url") val downloadUrl: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("sha256") val sha256: String = "",
    @SerializedName("size_bytes") val sizeBytes: Long = 0L,
    @SerializedName("target_versions") private val rawTargetVersions: List<String>? = emptyList(),
    @SerializedName("patches") private val rawPatches: List<PatchEntry>? = emptyList()
) {
    val name: String
        get() = author.ifEmpty { id }

    val repository: String
        get() = id

    val targetVersions: List<String>
        get() = rawTargetVersions ?: emptyList()

    val patches: List<PatchEntry>
        get() = rawPatches ?: emptyList()

    val patchCount: Int
        get() = patches.size
}

data class PatchEntry(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("key") val key: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("default") val defaultEnabled: Boolean = true
)
