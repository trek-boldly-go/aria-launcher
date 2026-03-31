// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves human-readable app labels from package names using [PackageManager].
 *
 * Results are cached in memory. The cache can be invalidated when packages change
 * (install/uninstall/update) via [invalidate] or [invalidatePackage].
 */
@Singleton
class AppLabelResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Returns the user-visible app label for [packageName].
     * Falls back to extracting a readable name from the package name if the app
     * is not installed (e.g., recently uninstalled).
     */
    fun resolve(packageName: String): String = cache.getOrPut(packageName) {
        try {
            context.packageManager
                .getApplicationInfo(packageName, 0)
                .loadLabel(context.packageManager)
                .toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    /** Removes a single package from the cache (e.g., after update/uninstall). */
    fun invalidatePackage(packageName: String) {
        cache.remove(packageName)
    }

    /** Clears all cached labels. */
    fun invalidate() {
        cache.clear()
    }
}
