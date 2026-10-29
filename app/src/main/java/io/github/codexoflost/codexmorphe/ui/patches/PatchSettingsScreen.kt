package io.github.codexoflost.codexmorphe.ui.patches

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.codexoflost.codexmorphe.model.PatchEntry
import io.github.codexoflost.codexmorphe.preferences.PatchPreferenceManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchSettingsScreen(
    appName: String,
    packageName: String,
    authorName: String,
    patches: List<PatchEntry>,
    prefManager: PatchPreferenceManager,
    onNavigateBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    var isAppEnabled by remember { mutableStateOf(prefManager.isAppEnabled(packageName)) }

    // Filter out custom branding and apply search
    val availablePatches = remember(patches) {
        patches.filterNot { PatchPreferenceManager.isExcludedPatch(it.key, it.name) }
    }

    val displayedPatches = remember(availablePatches, searchQuery) {
        if (searchQuery.isBlank()) availablePatches
        else availablePatches.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.description.contains(searchQuery, ignoreCase = true) ||
                    it.key.contains(searchQuery, ignoreCase = true)
        }
    }

    // Local toggle state map
    val patchStates = remember(packageName, availablePatches) {
        mutableStateMapOf<String, Boolean>().apply {
            availablePatches.forEach { patch ->
                this[patch.key] = prefManager.isPatchEnabled(packageName, patch.key, patch.defaultEnabled)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(appName, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Author: $authorName • ${availablePatches.size} patches",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Enable All Patches") },
                            onClick = {
                                menuExpanded = false
                                prefManager.setAllPatches(packageName, availablePatches, true)
                                availablePatches.forEach { patchStates[it.key] = true }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Disable All Patches") },
                            onClick = {
                                menuExpanded = false
                                prefManager.setAllPatches(packageName, availablePatches, false)
                                availablePatches.forEach { patchStates[it.key] = false }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Restore Defaults") },
                            onClick = {
                                menuExpanded = false
                                prefManager.restoreDefaults(packageName, availablePatches)
                                availablePatches.forEach { patchStates[it.key] = it.defaultEnabled }
                            }
                        )
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
            // Master App Enable Switch
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAppEnabled)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable CodexMorphe for $appName",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isAppEnabled) "Hooks are active on app launch" else "App will launch stock (hooks disabled)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isAppEnabled,
                        onCheckedChange = { checked ->
                            isAppEnabled = checked
                            prefManager.setAppEnabled(packageName, checked)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search patches...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Individual Patch Switches
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(displayedPatches, key = { it.key }) { patch ->
                    val isChecked = patchStates[patch.key] ?: patch.defaultEnabled
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = patch.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = patch.key,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (patch.description.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = patch.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isChecked,
                                enabled = isAppEnabled,
                                onCheckedChange = { checked ->
                                    patchStates[patch.key] = checked
                                    prefManager.setPatchEnabled(packageName, patch.key, checked)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
