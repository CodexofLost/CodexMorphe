package io.github.codexoflost.codexmorphe.service

import android.util.Log
import io.github.codexoflost.codexmorphe.CodexMorpheApp
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScopeManager {
    private const val TAG = "CodexMorphe/Scope"

    private val _activeScope = MutableStateFlow<Set<String>>(emptySet())
    val activeScope: StateFlow<Set<String>> = _activeScope.asStateFlow()

    fun refreshScope() {
        val service = CodexMorpheApp.mService
        if (service != null) {
            runCatching {
                val scopeList = service.scope
                _activeScope.value = scopeList.toSet()
                Log.d(TAG, "Current LSPosed active scope: $scopeList")
            }.onFailure {
                Log.e(TAG, "Failed to query active scope", it)
            }
        }
    }

    fun isInScope(packageName: String): Boolean {
        refreshScope()
        return _activeScope.value.contains(packageName)
    }

    fun requestScope(
        packageName: String,
        onApproved: () -> Unit = {},
        onFailed: (String) -> Unit = {}
    ) {
        val service = CodexMorpheApp.mService
        if (service == null) {
            onFailed("LSPosed service is not connected.")
            return
        }

        Log.i(TAG, "Requesting dynamic LSPosed scope for: $packageName")
        service.requestScope(listOf(packageName), object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                Log.i(TAG, "Scope request approved: $approved")
                refreshScope()
                onApproved()
            }

            override fun onScopeRequestFailed(message: String) {
                Log.w(TAG, "Scope request failed/rejected: $message")
                onFailed(message)
            }
        })
    }
}
