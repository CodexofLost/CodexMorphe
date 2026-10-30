package io.github.codexoflost.codexmorphe.model

import android.graphics.drawable.Drawable

data class AppCatalogItem(
    val packageName: String,
    val displayName: String,
    val icon: Drawable? = null,
    val isInstalled: Boolean = false,
    val installedVersionName: String? = null,
    val isInLsposedScope: Boolean = false,
    val isVersionSupported: Boolean = true,
    val activeAuthorId: String = "official",
    val activeProvider: ProviderEntry? = null,
    val availableProviders: List<ProviderEntry> = emptyList(),
    val isDownloaded: Boolean = false,
    val downloadedFileSizeBytes: Long = 0L,
    val hasUpdate: Boolean = false,
    val localSha256: String? = null
)
