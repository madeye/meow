package io.github.madeye.meow.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import io.github.madeye.meow.R
import kotlinx.serialization.Serializable

/**
 * Navigation destinations.
 *
 * The four tabs keep the Android app's existing structure; the engine-backed
 * screens (Connections / Rules / Logs) are pushed from Settings, which is where
 * meow-ios groups them too.
 */
sealed interface Dest {
    @Serializable data object Home : Dest

    @Serializable data object Subscribe : Dest

    @Serializable data object Traffic : Dest

    @Serializable data object Settings : Dest

    @Serializable data object PerAppProxy : Dest

    @Serializable data class YamlEditor(val profileId: Long) : Dest

    @Serializable data object Connections : Dest

    @Serializable data object Rules : Dest

    @Serializable data object Logs : Dest
}

data class TabDestination(
    val dest: Dest,
    @StringRes val label: Int,
    val icon: ImageVector,
    val testTag: String,
)

val TABS = listOf(
    TabDestination(Dest.Home, R.string.tab_home, Icons.Filled.Home, "tab_home"),
    TabDestination(Dest.Subscribe, R.string.tab_subscribe, Icons.Filled.Dns, "tab_subscribe"),
    TabDestination(Dest.Traffic, R.string.tab_traffic, Icons.Filled.ShowChart, "tab_traffic"),
    TabDestination(Dest.Settings, R.string.tab_settings, Icons.Filled.Settings, "tab_settings"),
)
