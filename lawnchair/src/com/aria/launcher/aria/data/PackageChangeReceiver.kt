// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aria.launcher.aria.engine.AppActivityCatalog
import com.aria.launcher.aria.engine.AppLabelResolver
import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Listens for package install/uninstall/update broadcasts and invalidates
 * caches in [AppLabelResolver], [AppActivityCatalog], and [DeviceCapabilityCatalog].
 */
@AndroidEntryPoint
class PackageChangeReceiver : BroadcastReceiver() {

    @Inject lateinit var appLabelResolver: AppLabelResolver

    @Inject lateinit var appActivityCatalog: AppActivityCatalog

    @Inject lateinit var capabilityCatalog: DeviceCapabilityCatalog

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart
        Log.d(TAG, "Package event: ${intent.action} pkg=$packageName")

        if (packageName != null) {
            appLabelResolver.invalidatePackage(packageName)
            appActivityCatalog.invalidatePackage(packageName)
        } else {
            appLabelResolver.invalidate()
            appActivityCatalog.invalidate()
        }
        // Capability catalog doesn't support per-package invalidation
        capabilityCatalog.invalidateCache()
    }

    companion object {
        private const val TAG = "ARIA.PkgChange"
    }
}
