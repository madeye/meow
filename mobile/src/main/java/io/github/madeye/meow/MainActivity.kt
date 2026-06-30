package io.github.madeye.meow

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent
import io.github.madeye.meow.aidl.MihomoConnection
import io.github.madeye.meow.aidl.TrafficStats
import io.github.madeye.meow.bg.BaseService
import io.github.madeye.meow.database.ClashProfile
import io.github.madeye.meow.database.DailyTraffic
import io.github.madeye.meow.database.PrivateDatabase
import io.github.madeye.meow.editor.SoraEditorViewFactory
import io.github.madeye.meow.editor.SoraEditorViewType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import io.github.madeye.meow.preference.DataStore
import io.github.madeye.meow.subscription.SubscriptionService
import androidx.core.view.WindowCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import timber.log.Timber

class MainActivity : FlutterActivity(), MihomoConnection.Callback {
    companion object {
        private const val VPN_CHANNEL = "io.github.madeye.meow/vpn"
        private const val STATE_CHANNEL = "io.github.madeye.meow/vpn_state"
        private const val TRAFFIC_CHANNEL = "io.github.madeye.meow/traffic"
        private const val REQUEST_VPN = 1
        private const val REQUEST_IMPORT_CONFIG = 2
        private const val REQUEST_EXPORT_CONFIG = 3
    }

    private val analytics by lazy { FirebaseAnalytics.getInstance(this) }
    private val connection = MihomoConnection(listenForBandwidth = true)
    private var state = BaseService.State.Idle
    private var stateEventSink: EventChannel.EventSink? = null
    private var trafficEventSink: EventChannel.EventSink? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingConnect = false
    // SAF file-picker round-trip state. Only one config import/export can be
    // in flight at a time; the pending channel Result is resolved from
    // onActivityResult once the user picks (or cancels) a file.
    private var pendingFileResult: MethodChannel.Result? = null
    private var pendingExportContent: String? = null
    private var lastTrafficTx = 0L
    private var lastTrafficRx = 0L
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        connection.connect(this, this)

        // Seed GeoIP DBs + register the engine home dir up front so config
        // validation (YAML editor, config import) works before the first VPN
        // start. Idempotent; off the main thread to avoid first-run jank.
        scope.launch(Dispatchers.IO) {
            try {
                io.github.madeye.meow.bg.MihomoInstance.prepareEngineHome(applicationContext)
            } catch (e: Exception) {
                Timber.w(e, "prepareEngineHome failed")
            }
        }

        if (intent?.getBooleanExtra("auto_connect", false) == true) {
            pendingConnect = true
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine.platformViewsController.registry.registerViewFactory(
            SoraEditorViewType.VIEW_TYPE,
            SoraEditorViewFactory(flutterEngine.dartExecutor.binaryMessenger),
        )

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "connect" -> {
                        analytics.logEvent("vpn_connect") {}
                        startVpnWithPermission(); result.success(null)
                    }
                    "disconnect" -> {
                        analytics.logEvent("vpn_disconnect") {}
                        sendBroadcast(Intent(io.github.madeye.meow.utils.Action.CLOSE).setPackage(packageName))
                        result.success(null)
                    }
                    "getState" -> result.success(state.ordinal)
                    "getProfiles" -> {
                        val profiles = PrivateDatabase.profileDao.getAll()
                        result.success(profiles.map { it.toFlutterMap() })
                    }
                    "getSelectedProfile" -> {
                        val p = PrivateDatabase.profileDao.getSelected()
                        result.success(p?.toFlutterMap())
                    }
                    "addSubscription" -> {
                        val name = call.argument<String>("name") ?: ""
                        val url = call.argument<String>("url") ?: ""
                        analytics.logEvent("subscription_add") {}
                        scope.launch {
                            try {
                                SubscriptionService.addSubscription(name, url)
                                result.success(null)
                            } catch (e: Exception) {
                                result.error("SUB_ERROR", e.message, null)
                            }
                        }
                    }
                    "updateSubscription" -> {
                        val id = call.argument<Int>("id")?.toLong() ?: 0L
                        val name = call.argument<String>("name") ?: ""
                        val url = call.argument<String>("url") ?: ""
                        analytics.logEvent("subscription_edit") {}
                        val existing = PrivateDatabase.profileDao.getById(id)
                        if (existing != null) {
                            PrivateDatabase.profileDao.update(existing.copy(name = name, url = url))
                            scope.launch {
                                try {
                                    val updated = SubscriptionService.fetchSubscription(
                                        existing.copy(name = name, url = url)
                                    )
                                    PrivateDatabase.profileDao.update(updated)
                                    result.success(null)
                                } catch (e: Exception) {
                                    result.error("SUB_ERROR", e.message, null)
                                }
                            }
                        } else {
                            result.error("NOT_FOUND", "Profile not found", null)
                        }
                    }
                    "deleteSubscription" -> {
                        val id = call.argument<Int>("id")?.toLong() ?: 0L
                        analytics.logEvent("subscription_delete") {}
                        val p = PrivateDatabase.profileDao.getById(id)
                        if (p != null) PrivateDatabase.profileDao.delete(p)
                        result.success(null)
                    }
                    "selectProfile" -> {
                        val id = call.argument<Int>("id")?.toLong() ?: 0L
                        analytics.logEvent("profile_select") {}
                        PrivateDatabase.profileDao.deselectAll()
                        PrivateDatabase.profileDao.select(id)
                        result.success(null)
                    }
                    "saveSelectedProxy" -> {
                        val id = call.argument<Int>("id")?.toLong() ?: 0L
                        val proxyName = call.argument<String>("proxyName") ?: ""
                        analytics.logEvent("proxy_node_select") {
                            param("proxy_name", proxyName)
                        }
                        PrivateDatabase.profileDao.updateSelectedProxy(id, proxyName)
                        result.success(null)
                    }
                    "updateProfileYaml" -> {
                        val id = call.argument<Int>("id")?.toLong() ?: 0L
                        val yaml = call.argument<String>("yamlContent") ?: ""
                        analytics.logEvent("profile_yaml_edit") {}
                        PrivateDatabase.profileDao.updateYamlContent(id, yaml)
                        result.success(null)
                    }
                    "validateConfig" -> {
                        // Config is parsed/checked by meow-rs, never in Dart.
                        // Returns null when valid, else the engine's error string.
                        val yaml = call.argument<String>("yamlContent") ?: ""
                        val core = io.github.madeye.meow.core.MihomoCore
                        val code = core.nativeValidateConfig(yaml)
                        result.success(if (code == 0) null else core.nativeGetLastError())
                    }
                    "importConfig" -> {
                        // Open the system file picker; the chosen YAML becomes a
                        // local profile (parsed/validated by meow-rs, not Dart).
                        if (pendingFileResult != null) {
                            result.error("BUSY", "a file operation is already in progress", null)
                        } else {
                            pendingFileResult = result
                            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            try {
                                startActivityForResult(intent, REQUEST_IMPORT_CONFIG)
                            } catch (e: Exception) {
                                pendingFileResult = null
                                result.error("NO_PICKER", e.message, null)
                            }
                        }
                    }
                    "exportConfig" -> {
                        // Save a profile's YAML to a user-chosen file via SAF.
                        if (pendingFileResult != null) {
                            result.error("BUSY", "a file operation is already in progress", null)
                        } else {
                            val name = call.argument<String>("name") ?: "config"
                            pendingExportContent = call.argument<String>("yamlContent") ?: ""
                            pendingFileResult = result
                            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/x-yaml"
                                putExtra(Intent.EXTRA_TITLE, "$safeName.yaml")
                            }
                            try {
                                startActivityForResult(intent, REQUEST_EXPORT_CONFIG)
                            } catch (e: Exception) {
                                pendingFileResult = null
                                pendingExportContent = null
                                result.error("NO_PICKER", e.message, null)
                            }
                        }
                    }
                    "revertProfileYaml" -> {
                        val id = call.argument<Int>("id")?.toLong() ?: 0L
                        analytics.logEvent("profile_yaml_revert") {}
                        PrivateDatabase.profileDao.revertYamlContent(id)
                        val p = PrivateDatabase.profileDao.getById(id)
                        result.success(p?.yamlContent ?: "")
                    }
                    "refreshSubscription" -> {
                        val id = call.argument<Int>("id")?.toLong() ?: 0L
                        analytics.logEvent("subscription_refresh") {}
                        val p = PrivateDatabase.profileDao.getById(id)
                        if (p != null) {
                            scope.launch {
                                try {
                                    val updated = SubscriptionService.fetchSubscription(p)
                                    PrivateDatabase.profileDao.update(updated)
                                    result.success(null)
                                } catch (e: Exception) {
                                    result.error("SUB_ERROR", e.message, null)
                                }
                            }
                        } else {
                            result.error("NOT_FOUND", "Profile not found", null)
                        }
                    }
                    "refreshAll" -> {
                        analytics.logEvent("subscription_refresh_all") {}
                        scope.launch {
                            try {
                                SubscriptionService.refreshAll()
                                result.success(null)
                            } catch (e: Exception) {
                                result.error("SUB_ERROR", e.message, null)
                            }
                        }
                    }
                    "getVersion" -> {
                        try {
                            result.success(io.github.madeye.meow.core.MihomoCore.nativeVersion())
                        } catch (_: Exception) {
                            result.success("unknown")
                        }
                    }
                    "getAppVersion" -> {
                        // App-level version — what the user expects under
                        // "Version" in Settings. nativeVersion() reports the
                        // embedded engine and is exposed separately.
                        try {
                            val pkg = packageManager.getPackageInfo(packageName, 0)
                            val versionName = pkg.versionName ?: "?"
                            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                                pkg.longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                pkg.versionCode.toLong()
                            }
                            result.success("$versionName ($versionCode)")
                        } catch (_: Exception) {
                            result.success("unknown")
                        }
                    }
                    "getInstalledApps" -> {
                        scope.launch(Dispatchers.IO) {
                            val pm = packageManager
                            val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                                .filter { it.packageName != packageName }
                                .map { appInfo ->
                                    mapOf(
                                        "packageName" to appInfo.packageName,
                                        "appName" to (pm.getApplicationLabel(appInfo)?.toString() ?: appInfo.packageName),
                                        "isSystem" to ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0),
                                    )
                                }
                                .sortedBy { (it["appName"] as String).lowercase() }
                            withContext(Dispatchers.Main) { result.success(apps) }
                        }
                    }
                    "getAppIcon" -> {
                        val pkg = call.argument<String>("packageName") ?: ""
                        scope.launch(Dispatchers.IO) {
                            try {
                                val drawable = packageManager.getApplicationIcon(pkg)
                                val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) {
                                    drawable.bitmap
                                } else {
                                    val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                                    val canvas = android.graphics.Canvas(bmp)
                                    drawable.setBounds(0, 0, 48, 48)
                                    drawable.draw(canvas)
                                    bmp
                                }
                                val stream = java.io.ByteArrayOutputStream()
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                                withContext(Dispatchers.Main) { result.success(stream.toByteArray()) }
                            } catch (_: Exception) {
                                withContext(Dispatchers.Main) { result.success(null) }
                            }
                        }
                    }
                    "getPerAppConfig" -> {
                        result.success(mapOf(
                            "mode" to DataStore.perAppMode,
                            "packages" to DataStore.perAppPackages,
                        ))
                    }
                    "setPerAppConfig" -> {
                        val mode = call.argument<String>("mode") ?: "proxy"
                        val packages = call.argument<String>("packages") ?: "[]"
                        analytics.logEvent("per_app_proxy_save") {
                            param("mode", mode)
                        }
                        DataStore.perAppMode = mode
                        DataStore.perAppPackages = packages
                        result.success(null)
                    }
                    "getLogs" -> {
                        val raw = try {
                            io.github.madeye.meow.core.MihomoCore.nativeGetLogs()
                        } catch (e: Throwable) {
                            ""
                        }
                        val lines = if (raw.isEmpty()) {
                            emptyList()
                        } else {
                            raw.split("\n").filter { it.isNotEmpty() }
                        }
                        result.success(lines)
                    }
                    "getTrafficHistory" -> {
                        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -31) }
                        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        PrivateDatabase.dailyTrafficDao.deleteBefore(fmt.format(cutoff.time))
                        val entries = PrivateDatabase.dailyTrafficDao.getAll()
                        result.success(entries.map { mapOf("date" to it.date, "tx" to it.tx, "rx" to it.rx) })
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, STATE_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    stateEventSink = events
                    events?.success(state.ordinal)
                }
                override fun onCancel(arguments: Any?) { stateEventSink = null }
            })

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, TRAFFIC_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    trafficEventSink = events
                }
                override fun onCancel(arguments: Any?) { trafficEventSink = null }
            })
    }

    private fun startVpnWithPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnect = true
            startActivityForResult(intent, REQUEST_VPN)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        startService(Intent(this, io.github.madeye.meow.bg.VpnService::class.java))
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN -> {
                if (resultCode == RESULT_OK) startVpn()
                pendingConnect = false
            }
            REQUEST_IMPORT_CONFIG -> handleImportResult(resultCode, data?.data)
            REQUEST_EXPORT_CONFIG -> handleExportResult(resultCode, data?.data)
        }
    }

    private fun handleImportResult(resultCode: Int, uri: android.net.Uri?) {
        val result = pendingFileResult ?: return
        pendingFileResult = null
        if (resultCode != RESULT_OK || uri == null) {
            result.success(null) // user cancelled
            return
        }
        scope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { it.readText() }
                        ?: throw java.io.IOException("could not read file")
                    // Seed GeoIP DBs + set the home dir so meow-rs can validate
                    // configs that use GEOIP/GEOSITE rules even with the VPN off.
                    io.github.madeye.meow.bg.MihomoInstance.prepareEngineHome(applicationContext)
                    Timber.d("import: read ${content.length} chars from $uri, validating")
                    val core = io.github.madeye.meow.core.MihomoCore
                    if (core.nativeValidateConfig(content) != 0) {
                        throw IllegalArgumentException(core.nativeGetLastError())
                    }
                    SubscriptionService.addLocal(displayName(uri), content)
                }
                Timber.i("import: created profile '${profile.name}' (id=${profile.id})")
                analytics.logEvent("config_import") {}
                result.success(profile.toFlutterMap())
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "import: config rejected by meow-rs")
                result.error("INVALID_CONFIG", e.message, null)
            } catch (e: Exception) {
                Timber.e(e, "import: failed")
                result.error("IMPORT_ERROR", e.message, null)
            }
        }
    }

    private fun handleExportResult(resultCode: Int, uri: android.net.Uri?) {
        val result = pendingFileResult ?: return
        val content = pendingExportContent ?: ""
        pendingFileResult = null
        pendingExportContent = null
        if (resultCode != RESULT_OK || uri == null) {
            result.success(false) // user cancelled
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()
                        ?.use { it.write(content) }
                        ?: throw java.io.IOException("could not open file for writing")
                }
                Timber.i("export: wrote ${content.length} chars to $uri")
                analytics.logEvent("config_export") {}
                result.success(true)
            } catch (e: Exception) {
                Timber.e(e, "export: failed")
                result.error("EXPORT_ERROR", e.message, null)
            }
        }
    }

    /// Best-effort profile name from a picked document: the file's display
    /// name without extension, falling back to "Imported".
    private fun displayName(uri: android.net.Uri): String {
        var name: String? = null
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        } catch (_: Exception) {
        }
        val base = name?.substringBeforeLast('.')?.trim().orEmpty()
        return base.ifEmpty { "Imported" }
    }

    override fun onDestroy() {
        super.onDestroy()
        connection.disconnect(this)
        scope.cancel()
    }

    override fun stateChanged(state: BaseService.State, profileName: String, msg: String?) {
        this.state = state
        runOnUiThread { stateEventSink?.success(state.ordinal) }

        analytics.logEvent("vpn_state_change") {
            param("state", state.name)
            if (profileName.isNotEmpty()) param("profile", profileName)
        }

        if (state == BaseService.State.Connected) {
            lastTrafficTx = 0L
            lastTrafficRx = 0L
        }

        if (pendingConnect && state == BaseService.State.Stopped) {
            pendingConnect = false
            startVpnWithPermission()
        }
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        // Persist daily traffic
        val deltaTx = if (lastTrafficTx > 0) stats.txTotal - lastTrafficTx else 0L
        val deltaRx = if (lastTrafficRx > 0) stats.rxTotal - lastTrafficRx else 0L
        lastTrafficTx = stats.txTotal
        lastTrafficRx = stats.rxTotal

        if (deltaTx > 0 || deltaRx > 0) {
            val today = dateFmt.format(System.currentTimeMillis())
            val dao = PrivateDatabase.dailyTrafficDao
            val entry = dao.getByDate(today) ?: DailyTraffic(date = today)
            if (deltaTx > 0) entry.tx += deltaTx
            if (deltaRx > 0) entry.rx += deltaRx
            dao.upsert(entry)
        }

        runOnUiThread {
            trafficEventSink?.success(mapOf(
                "txRate" to stats.txRate,
                "rxRate" to stats.rxRate,
                "txTotal" to stats.txTotal,
                "rxTotal" to stats.rxTotal,
            ))
        }
    }

    override fun trafficPersisted(profileId: Long) {}

    private fun ClashProfile.toFlutterMap() = mapOf(
        "id" to id.toInt(),
        "name" to name,
        "url" to url,
        "yamlContent" to yamlContent,
        "selected" to selected,
        // lastUpdated/tx/rx are epoch-millis and byte counters — Longs that
        // overflow a 32-bit Int (e.g. millis truncated to garbage produced the
        // "Updated: 2013-…" bug). Send them as full 64-bit Longs; the channel
        // codec maps Kotlin Long → Dart int.
        "lastUpdated" to lastUpdated,
        "tx" to tx,
        "rx" to rx,
        "selectedProxy" to selectedProxy,
        "yamlBackup" to yamlBackup,
    )
}
