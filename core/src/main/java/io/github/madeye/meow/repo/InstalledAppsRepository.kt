package io.github.madeye.meow.repo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

/**
 * The installed-app list behind the per-app proxy picker.
 *
 * Enumerating packages and resolving their labels is slow enough to be visible
 * (hundreds of apps, each a separate PackageManager round trip), so the list is
 * loaded once off the main thread and icons are fetched lazily per row.
 */
class InstalledAppsRepository(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            // An app with no launcher entry and no internet permission cannot
            // generate tunnelled traffic, but filtering on that is unreliable
            // across OEMs; keep every package and let the UI filter instead.
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = packageManager.getApplicationLabel(info).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Null when the package vanished between listing and drawing. */
    suspend fun icon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
