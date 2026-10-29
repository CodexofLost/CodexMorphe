package io.github.codexoflost.codexmorphe.service

import android.content.Context
import android.content.pm.PackageManager

sealed class VersionStatus {
    data class Supported(val installedVersion: String, val supportedVersions: List<String>) : VersionStatus()
    data class Universal(val installedVersion: String) : VersionStatus()
    data class Mismatch(val installedVersion: String, val supportedVersions: List<String>) : VersionStatus()
    data object NotInstalled : VersionStatus()
}

object VersionValidator {

    fun validate(
        context: Context,
        packageName: String,
        targetVersions: List<String>
    ): VersionStatus {
        val installedVersionName = runCatching {
            val pInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            pInfo.versionName
        }.getOrNull() ?: return VersionStatus.NotInstalled

        if (targetVersions.isEmpty()) {
            return VersionStatus.Universal(installedVersionName)
        }

        return if (targetVersions.contains(installedVersionName)) {
            VersionStatus.Supported(installedVersionName, targetVersions)
        } else {
            VersionStatus.Mismatch(installedVersionName, targetVersions)
        }
    }
}
