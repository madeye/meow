package io.github.madeye.meow.ui.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.madeye.meow.core.MeowCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App and engine version strings.
 *
 * A plain class rather than something the ViewModel holds a Context for —
 * ViewModels outlive configuration changes, so a Context field there is a leak.
 */
class AppVersions(private val context: Context) {

    suspend fun read(): Pair<String, String> = withContext(Dispatchers.IO) {
        val app = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.versionCodeCompat()})"
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
        // Comes from the linked Rust library, so it is readable whether or not
        // the tunnel is running.
        val engine = runCatching { MeowCore.nativeVersion() }.getOrDefault("")
        app to engine
    }

    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
}
