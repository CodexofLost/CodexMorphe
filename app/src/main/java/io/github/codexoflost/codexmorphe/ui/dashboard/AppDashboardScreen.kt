package io.github.codexoflost.codexmorphe.ui.dashboard

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.codexoflost.codexmorphe.CodexMorpheApp
import io.github.codexoflost.codexmorphe.model.AppCatalogItem
import io.github.codexoflost.codexmorphe.model.BundleManifest
import io.github.codexoflost.codexmorphe.model.ProviderEntry
import io.github.codexoflost.codexmorphe.net.BundleDownloader
import io.github.codexoflost.codexmorphe.net.DownloadState
import io.github.codexoflost.codexmorphe.net.ManifestRepository
import io.github.codexoflost.codexmorphe.preferences.PatchPreferenceManager
import io.github.codexoflost.codexmorphe.service.ScopeManager
import io.github.codexoflost.codexmorphe.service.VersionStatus
import io.github.codexoflost.codexmorphe.service.VersionValidator
import io.github.codexoflost.codexmorphe.ui.dialogs.AuthorSelectorDialog
import io.github.codexoflost.codexmorphe.ui.dialogs.InspectPatchesSheet
import io.github.codexoflost.codexmorphe.ui.dialogs.VersionWarningDialog
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDashboardScreen(
    onNavigateToPatches: (packageName: String, appName: String, authorName: String, patches: List<io.github.codexoflost.codexmorphe.model.PatchEntry>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val manifestRepo = remember { ManifestRepository(context) }
    val downloader = remember { BundleDownloader(context) }
    val prefManager = remember { PatchPreferenceManager(context) }

    var manifest by remember { mutableStateOf<BundleManifest?>(null) }
    var isLoadingManifest by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Installed, 1: All
    var searchQuery by remember { mutableStateOf("") }

    // Dialog & Sheet States
    var inspectingProvider by remember { mutableStateOf<ProviderEntry?>(null) }
    var selectingAuthorForApp by remember { mutableStateOf<AppCatalogItem?>(null) }
    var versionWarningForApp by remember { mutableStateOf<AppCatalogItem?>(null) }

    // Download state tracking: packageName -> DownloadState
    val activeDownloads = remember { mutableStateMapOf<String, DownloadState>() }

    // Active LSPosed Scope state
    val activeScope by ScopeManager.activeScope.collectAsState()

    // Helper to refresh app catalog list
    fun buildCatalogList(currentManifest: BundleManifest?): List<AppCatalogItem> {
        if (currentManifest == null) return emptyList()

        val pm = context.packageManager
        return currentManifest.applications.map { (pkg, appEntry) ->
            val isInstalled = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            }.isSuccess

            val installedVersionName = if (isInstalled) {
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0).versionName
                    }
                }.getOrNull()
            } else null

            val icon = if (isInstalled) {
                runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            } else null

            val activeAuthorId = prefManager.getActiveAuthor(pkg)
            val providers = appEntry.providers.values.toList()
            val activeProvider = providers.find { it.id == activeAuthorId } ?: providers.firstOrNull()

            val bundleFile = activeProvider?.let { File(downloader.bundlesDir, it.assetName) }
            val isDownloaded = bundleFile?.exists() == true
            val downloadedSize = bundleFile?.length() ?: 0L

            val isUpToDate = if (isDownloaded && activeProvider != null) {
                downloader.isBundleUpToDate(bundleFile!!, activeProvider.sha256)
            } else false
            val hasUpdate = isDownloaded && !isUpToDate
            val localSha256 = if (isDownloaded) downloader.computeSha256(bundleFile!!) else null

            val isVersionSupported = if (isInstalled && activeProvider != null) {
                when (VersionValidator.validate(context, pkg, activeProvider.targetVersions)) {
                    is VersionStatus.Mismatch -> false
                    else -> true
                }
            } else true

            AppCatalogItem(
                packageName = pkg,
                displayName = appEntry.displayName.ifBlank { pkg },
                icon = icon,
                isInstalled = isInstalled,
                installedVersionName = installedVersionName,
                isInLsposedScope = activeScope.contains(pkg),
                isVersionSupported = isVersionSupported,
                activeAuthorId = activeAuthorId,
                activeProvider = activeProvider,
                availableProviders = providers,
                isDownloaded = isDownloaded,
                downloadedFileSizeBytes = downloadedSize,
                hasUpdate = hasUpdate,
                localSha256 = localSha256
            )
        }
    }

    // Refresh manifest
    fun loadManifest(force: Boolean = false) {
        coroutineScope.launch {
            isLoadingManifest = true
            ScopeManager.refreshScope()
            manifestRepo.getManifest(force).onSuccess {
                manifest = it
            }.onFailure {
                Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
            }
            isLoadingManifest = false
        }
    }

    fun triggerDownload(item: AppCatalogItem, provider: ProviderEntry) {
        coroutineScope.launch {
            prefManager.setActiveAuthor(item.packageName, provider.id)
            downloader.downloadBundle(item.packageName, provider.assetName, provider.sha256, provider.downloadUrl).collect { state ->
                activeDownloads[item.packageName] = state
                if (state is DownloadState.Success) {
                    Toast.makeText(context, "Bundle for ${item.displayName} synced successfully!", Toast.LENGTH_SHORT).show()
                } else if (state is DownloadState.Error) {
                    Toast.makeText(context, "Download failed for ${item.displayName}: ${state.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Comprehensive fetch: updates catalog and auto-downloads missing/updated bundles for installed apps
    fun fetchLatestAndSyncBundles() {
        coroutineScope.launch {
            isLoadingManifest = true
            ScopeManager.refreshScope()
            manifestRepo.getManifest(forceRefresh = true).onSuccess { freshManifest ->
                manifest = freshManifest
                val updatedCatalog = buildCatalogList(freshManifest)
                val installedItemsToDownload = updatedCatalog.filter { it.isInstalled && (it.hasUpdate || !it.isDownloaded) }
                if (installedItemsToDownload.isEmpty()) {
                    Toast.makeText(context, "Manifest refreshed! All installed app bundles are up to date.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Updating ${installedItemsToDownload.size} installed bundle(s)...", Toast.LENGTH_SHORT).show()
                    installedItemsToDownload.forEach { item ->
                        val provider = item.activeProvider ?: return@forEach
                        triggerDownload(item, provider)
                    }
                }
            }.onFailure {
                Toast.makeText(context, "Failed fetching manifest: ${it.message}", Toast.LENGTH_LONG).show()
            }
            isLoadingManifest = false
        }
    }

    LaunchedEffect(Unit) {
        loadManifest(false)
    }

    val catalog = remember(manifest, activeScope, activeDownloads.toMap()) {
        buildCatalogList(manifest)
    }

    val displayedItems = remember(catalog, selectedTab, searchQuery) {
        catalog.filter { item ->
            val matchesTab = if (selectedTab == 0) item.isInstalled else true
            val matchesSearch = searchQuery.isBlank() ||
                    item.displayName.contains(searchQuery, ignoreCase = true) ||
                    item.packageName.contains(searchQuery, ignoreCase = true)
            matchesTab && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CodexMorphe", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (CodexMorpheApp.isModuleActive) "LSPosed API 102 Active" else "Module Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (CodexMorpheApp.isModuleActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            fetchLatestAndSyncBundles()
                        },
                        enabled = !isLoadingManifest,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isLoadingManifest) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Fetch Latest Bundles", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Module Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (CodexMorpheApp.isModuleActive)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (CodexMorpheApp.isModuleActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (CodexMorpheApp.isModuleActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (CodexMorpheApp.isModuleActive) "LSPosed Module Active" else "Module Inactive in LSPosed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (CodexMorpheApp.isModuleActive) "Dynamic hook IPC is ready" else "Please enable CodexMorphe in LSPosed and restart apps",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps or packages...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Installed Apps (${catalog.count { it.isInstalled }})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("All Catalogs (${catalog.size})") }
                )
            }

            // Pending Updates Banner
            val pendingUpdates = catalog.filter { it.isInstalled && it.hasUpdate && it.activeProvider != null }
            if (pendingUpdates.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${pendingUpdates.size} Bundle Update(s) Available",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                "Fresh patch recipes compiled from latest releases.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Button(
                            onClick = {
                                pendingUpdates.forEach { item ->
                                    item.activeProvider?.let { triggerDownload(item, it) }
                                }
                            }
                        ) {
                            Text("Update All")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // App List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(displayedItems, key = { it.packageName }) { item ->
                    val downloadState = activeDownloads[item.packageName]

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // App Icon
                                if (item.icon != null) {
                                    Image(
                                        bitmap = item.icon.toBitmap(96, 96).asImageBitmap(),
                                        contentDescription = item.displayName,
                                        modifier = Modifier.size(48.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Android,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = item.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (item.installedVersionName != null) {
                                        Text(
                                            text = "Installed: v${item.installedVersionName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Badges Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // LSPosed Scope Badge
                                if (item.isInstalled) {
                                    if (item.isInLsposedScope) {
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text("Scope: Active", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    } else {
                                        SuggestionChip(
                                            onClick = {
                                                ScopeManager.requestScope(
                                                    item.packageName,
                                                    onApproved = {
                                                        Toast.makeText(context, "${item.displayName} added to scope!", Toast.LENGTH_SHORT).show()
                                                    },
                                                    onFailed = {
                                                        Toast.makeText(context, "Scope error: $it", Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            },
                                            label = { Text("Enable in LSPosed", style = MaterialTheme.typography.labelSmall) },
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                            )
                                        )
                                    }
                                }

                                // Version Warning Badge
                                if (item.isInstalled && !item.isVersionSupported) {
                                    SuggestionChip(
                                        onClick = { versionWarningForApp = item },
                                        label = { Text("Version Mismatch", style = MaterialTheme.typography.labelSmall) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer
                                        )
                                    )
                                }

                                // Update Available Badge
                                if (item.hasUpdate) {
                                    SuggestionChip(
                                        onClick = {
                                            if (item.activeProvider != null) {
                                                triggerDownload(item, item.activeProvider)
                                            }
                                        },
                                        label = { Text("Update Available", style = MaterialTheme.typography.labelSmall) },
                                        colors = SuggestionChipDefaults.suggestionChipColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    )
                                }

                                // Download Status Badge
                                if (item.isDownloaded) {
                                    SuggestionChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                "Bundle Ready (${(item.downloadedFileSizeBytes / 1024 / 1024.0).let { "%.1f MB".format(it) }})",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                            }

                            // Download Progress Indicator
                            if (downloadState is DownloadState.Progress) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { downloadState.percent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Downloading: ${downloadState.percent}% (${downloadState.bytesRead / 1024} KB / ${downloadState.totalBytes / 1024} KB)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (item.isDownloaded && item.activeProvider != null) {
                                    if (item.hasUpdate) {
                                        Button(
                                            onClick = { triggerDownload(item, item.activeProvider) },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Update Bundle")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                onNavigateToPatches(
                                                    item.packageName,
                                                    item.displayName,
                                                    item.activeProvider.name.ifBlank { item.activeProvider.author },
                                                    item.activeProvider.patches
                                                )
                                            },
                                            modifier = Modifier.weight(0.9f)
                                        ) {
                                            Text("Configure")
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                onNavigateToPatches(
                                                    item.packageName,
                                                    item.displayName,
                                                    item.activeProvider.name.ifBlank { item.activeProvider.author },
                                                    item.activeProvider.patches
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Configure Patches")
                                        }
                                    }

                                    if (item.availableProviders.size > 1) {
                                        OutlinedButton(
                                            onClick = { selectingAuthorForApp = item },
                                            modifier = Modifier.weight(0.9f)
                                        ) {
                                            Text("Change Author")
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            if (item.availableProviders.size > 1) {
                                                selectingAuthorForApp = item
                                            } else if (item.activeProvider != null) {
                                                triggerDownload(item, item.activeProvider)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (item.availableProviders.size > 1) "Choose Author & Download" else "Download Bundle")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet: Inspect Patches
    inspectingProvider?.let { provider ->
        InspectPatchesSheet(
            provider = provider,
            onDismiss = { inspectingProvider = null },
            onDownload = {
                selectingAuthorForApp?.let { app ->
                    triggerDownload(app, provider)
                }
            }
        )
    }

    // Dialog: Author Selection
    selectingAuthorForApp?.let { app ->
        AuthorSelectorDialog(
            appName = app.displayName,
            providers = app.availableProviders,
            currentAuthorId = app.activeAuthorId,
            onInspectAuthor = { provider ->
                inspectingProvider = provider
            },
            onSelectAuthor = { provider ->
                triggerDownload(app, provider)
            },
            onDismiss = { selectingAuthorForApp = null }
        )
    }

    // Dialog: Version Warning
    versionWarningForApp?.let { app ->
        val provider = app.activeProvider
        VersionWarningDialog(
            appName = app.displayName,
            installedVersion = app.installedVersionName ?: "Unknown",
            supportedVersions = provider?.targetVersions ?: emptyList(),
            authorName = provider?.author ?: "Author",
            hasAlternativeAuthors = app.availableProviders.size > 1,
            onSwitchAuthor = {
                versionWarningForApp = null
                selectingAuthorForApp = app
            },
            onProceedAnyway = {
                versionWarningForApp = null
            },
            onDismiss = { versionWarningForApp = null }
        )
    }
}
