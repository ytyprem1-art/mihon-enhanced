package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import eu.kanade.domain.source.service.SourcePreferences
import tachiyomi.core.common.preference.getAndSet

class TrustExtension(
    private val preferences: SourcePreferences,
) {

    @Suppress("UNUSED_PARAMETER")
    fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        return true
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions.getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions.delete()
    }
}
