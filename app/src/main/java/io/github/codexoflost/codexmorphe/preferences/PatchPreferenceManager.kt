package io.github.codexoflost.codexmorphe.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.codexoflost.codexmorphe.CodexMorpheApp
import io.github.codexoflost.codexmorphe.model.PatchEntry

class PatchPreferenceManager(private val context: Context) {

    companion object {
        private const val TAG = "CodexMorphe/Prefs"
        const val PREFS_NAME = "codexmorphe_prefs"

        fun isExcludedPatch(key: String, name: String): Boolean {
            val lowerKey = key.lowercase()
            val lowerName = name.lowercase()
            return lowerKey.contains("custom-branding") ||
                    lowerKey.contains("custombranding") ||
                    lowerName.contains("custom branding") ||
                    lowerName.contains("launcher icon") ||
                    lowerKey.contains("appicon")
        }
    }

    private val localPrefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActiveAuthor(packageName: String): String {
        return localPrefs.getString("provider_$packageName", "official") ?: "official"
    }

    fun setActiveAuthor(packageName: String, authorId: String) {
        localPrefs.edit()
            .putString("provider_$packageName", authorId)
            .putString("active_author", authorId)
            .apply()
        syncToRemote(packageName) {
            edit()
                .putString("provider_$packageName", authorId)
                .putString("active_author", authorId)
                .apply()
        }
    }

    fun isAppEnabled(packageName: String): Boolean {
        return localPrefs.getBoolean("app_enabled_$packageName", true)
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        localPrefs.edit().putBoolean("app_enabled_$packageName", enabled).apply()
        syncToRemote(packageName) { edit().putBoolean("app_enabled_$packageName", enabled).apply() }
    }

    fun isPatchEnabled(packageName: String, patchKey: String, defaultEnabled: Boolean): Boolean {
        val prefKey = "morphe_$patchKey"
        return localPrefs.getBoolean("${packageName}_$prefKey", defaultEnabled)
    }

    fun setPatchEnabled(packageName: String, patchKey: String, enabled: Boolean) {
        val prefKey = "morphe_$patchKey"
        localPrefs.edit().putBoolean("${packageName}_$prefKey", enabled).apply()
        syncToRemote(packageName) { edit().putBoolean(prefKey, enabled).apply() }
    }

    fun setAllPatches(packageName: String, patches: List<PatchEntry>, enabled: Boolean) {
        val localEditor = localPrefs.edit()
        val filtered = patches.filterNot { isExcludedPatch(it.key, it.name) }

        filtered.forEach { patch ->
            val prefKey = "morphe_${patch.key}"
            localEditor.putBoolean("${packageName}_$prefKey", enabled)
        }
        localEditor.apply()

        syncToRemote(packageName) {
            val remoteEditor = edit()
            filtered.forEach { patch ->
                remoteEditor.putBoolean("morphe_${patch.key}", enabled)
            }
            remoteEditor.apply()
        }
    }

    fun restoreDefaults(packageName: String, patches: List<PatchEntry>) {
        val localEditor = localPrefs.edit()
        val filtered = patches.filterNot { isExcludedPatch(it.key, it.name) }

        filtered.forEach { patch ->
            val prefKey = "morphe_${patch.key}"
            localEditor.putBoolean("${packageName}_$prefKey", patch.defaultEnabled)
        }
        localEditor.apply()

        syncToRemote(packageName) {
            val remoteEditor = edit()
            filtered.forEach { patch ->
                remoteEditor.putBoolean("morphe_${patch.key}", patch.defaultEnabled)
            }
            remoteEditor.apply()
        }
    }

    private fun syncToRemote(packageName: String, block: SharedPreferences.() -> Unit) {
        val service = CodexMorpheApp.mService ?: return
        runCatching {
            val remotePrefs = service.getRemotePreferences(packageName)
            remotePrefs.block()
            Log.d(TAG, "Synced preferences to remote LSPosed storage for $packageName")
        }.onFailure {
            Log.w(TAG, "Failed to sync to remote preferences: ${it.message}")
        }
    }
}
