package io.github.codexoflost.codexmorphe.model

import com.google.gson.annotations.SerializedName

data class BundleManifest(
    @SerializedName("schema_version") val schemaVersion: Int = 1,
    @SerializedName("generated_at") val generatedAt: String = "",
    @SerializedName("total_bundles") val totalBundles: Int = 0,
    @SerializedName("applications") val applications: Map<String, ApplicationEntry> = emptyMap()
)

data class ApplicationEntry(
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("providers") val providers: Map<String, ProviderEntry> = emptyMap()
)

data class ProviderEntry(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("author") val author: String = "",
    @SerializedName("repository") val repository: String = "",
    @SerializedName("asset_name") val assetName: String = "",
    @SerializedName("sha256") val sha256: String = "",
    @SerializedName("size_bytes") val sizeBytes: Long = 0L,
    @SerializedName("version") val version: String = "",
    @SerializedName("target_versions") val targetVersions: List<String> = emptyList(),
    @SerializedName("patch_count") val patchCount: Int = 0,
    @SerializedName("patches") val patches: List<PatchEntry> = emptyList()
)

data class PatchEntry(
    @SerializedName("name") val name: String = "",
    @SerializedName("key") val key: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("default_enabled") val defaultEnabled: Boolean = true
)
