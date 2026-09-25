package org.elderguard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.elderguard.data.Diagnostics
import org.elderguard.data.Store
import org.elderguard.detect.IntrusionSummary
import org.elderguard.detect.NotificationClassifier
import org.elderguard.detect.RiskLevel
import org.elderguard.detect.StaticRisk
import org.elderguard.monitor.Capabilities
import org.elderguard.monitor.GuardService
import org.elderguard.monitor.UsageMonitor
import org.elderguard.scan.AppScanner
import org.elderguard.ui.AppDetailScreen
import org.elderguard.ui.AppListScreen
import org.elderguard.ui.AppRow
import org.elderguard.ui.Behavior
import org.elderguard.ui.CapabilityRow
import org.elderguard.ui.GuardTheme
import org.elderguard.ui.HomeScreen
import org.elderguard.ui.HomeState
import org.elderguard.ui.SettingsScreen
import org.elderguard.ui.SettingsState

class MainActivity : ComponentActivity() {
    private sealed interface Screen {
        data object Home : Screen
        data object List : Screen
        data class Detail(val pkg: String) : Screen
        data object Settings : Screen
    }

    /** Pages the user walked through; the phone's Back key goes one step back, and only leaves the app from Home. */
    private var stack by mutableStateOf(listOf<Screen>(Screen.Home))
    private val screen get() = stack.last()
    private fun go(s: Screen) { if (stack.last() != s) stack = stack + s }
    private fun back() { if (stack.size > 1) stack = stack.dropLast(1) }
    private fun home() { stack = listOf(Screen.Home) }

    private var consent by mutableStateOf<Boolean?>(null)
    private var settingsMessage by mutableStateOf<String?>(null)
    /** Diagnostic-log count / first time and kept apps, loaded off the main thread when Settings opens. */
    private var diagInfo by mutableStateOf(0 to (null as String?))
    private var keptRows by mutableStateOf(listOf<Pair<String, String>>())
    private var rows by mutableStateOf(mapOf<String, AppRow>())
    private var scanning by mutableStateOf(false)
    private var lookupMessage by mutableStateOf<String?>(null)
    private var caps by mutableStateOf<Capabilities?>(null)
    private var showSystem by mutableStateOf(false)
    private lateinit var store: Store
    /** Pop-up times per package from the last usage-history query (24 h). */
    @Volatile private var usageTimes: Map<String, List<Long>> = emptyMap()

    private fun queryUsage(windowMs: Long): List<IntrusionSummary> {
        if (!Capabilities.read(this).usageAccess) return emptyList()
        val (list, summaries) = runCatching { UsageMonitor(this).findIntrusions(windowMs) }.getOrElse { return emptyList() }
        usageTimes = list.groupBy { it.packageName }.mapValues { (_, v) -> v.map { it.timeMs } }
        return summaries
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(this)
        consent = store.diagConsent
        GuardService.start(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        intent?.getStringExtra(EXTRA_OPEN_PKG)?.let { openFromAlert(it) }
        setContent {
            GuardTheme {
                // Before 0.5.0 there was no handler, so Back on any inner page closed the whole app (field report).
                BackHandler(enabled = stack.size > 1) { back() }
                when (val s = screen) {
                    Screen.Home -> HomeScreen(homeState(), ::findRecent, ::scanAll, { go(Screen.Detail(it.packageName)) },
                        onSettings = ::openSettings, onConsent = ::setConsent,
                        onCall165 = { safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:165"))) })
                    Screen.List -> AppListScreen(
                        rows.values.filter { showSystem || !it.isSystem }.sortedWith(compareByDescending<AppRow> { it.flagged }.thenBy { it.label }),
                        showSystem, { showSystem = !showSystem }, { go(Screen.Detail(it.packageName)) }, ::home)
                    Screen.Settings -> SettingsScreen(settingsState(), onBack = ::home,
                        onCloserSettings = { safeStart(org.elderguard.monitor.AdCloserService.settingsIntent(this)) },
                        onDiag = ::setConsent, onExport = ::exportLog,
                        onUnkeep = { store.setKept(it, false); store.diag("user_unkeep", it); loadSettingsInfo() },
                        onAllApps = { if (rows.isEmpty()) scanAll(); go(Screen.List) })
                    is Screen.Detail -> {
                        val r = rows[s.pkg] ?: placeholder(s.pkg)
                        AppDetailScreen(r,
                            onAppInfo = { userAction(r, "app_info"); safeStart(Capabilities.appInfo(r.packageName)) },
                            onUninstall = { userAction(r, "uninstall"); safeStart(Capabilities.uninstall(r.packageName)) },
                            onNotifications = { userAction(r, "notifications"); safeStart(Capabilities.appNotificationSettings(this, r.packageName)) },
                            onOverlay = { userAction(r, "overlay"); safeStart(Capabilities.overlaySettings(r.packageName)) },
                            onKeep = { userAction(r, "keep"); store.setKept(r.packageName, true); refreshRow(r.packageName); back() },
                            onBack = ::home)
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // The user closed the guard with Back: whatever page is underneath is a return, not a pop-up (see PopupPolicy).
        if (isFinishing) {
            userLeftAtMs = System.currentTimeMillis()
            store.log("user_exit", packageName)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_PKG)?.let { openFromAlert(it) }
    }

    override fun onResume() {
        super.onResume()
        caps = Capabilities.read(this)
        GuardService.start(this)
        // Coming back from Settings (uninstall / notification settings): refresh what we show.
        (screen as? Screen.Detail)?.let { refreshRow(it.pkg) }
        if (screen == Screen.Settings) loadSettingsInfo()
        // the monitor writes its first heartbeat a moment after (re)starting; read the state again then
        lifecycleScope.launch { kotlinx.coroutines.delay(3_000); caps = Capabilities.read(this@MainActivity) }
        if (rows.isEmpty() && store.lastScanMs != 0L) scanAll()
    }

    private fun openFromAlert(pkg: String) {
        store.diag("open_alert", pkg)
        stack = listOf(Screen.Home, Screen.Detail(pkg))
        refreshRow(pkg)
    }

    /** What the user did with a flagged/checked app: "keep" on a flagged app is our main false-alarm signal. */
    private fun userAction(r: AppRow, action: String) =
        store.diag("user_action", r.packageName, mapOf("action" to action, "level" to r.level.name, "popups" to r.behavior?.popups, "flagged" to r.flagged))

    private fun setConsent(on: Boolean) {
        store.diagConsent = on
        consent = on
        if (on) {
            store.lastCapsSnapshot = "" // the monitor writes the current permission state on its next poll
            store.diag("consent", fields = mapOf("on" to true, "app" to Diagnostics.versionName(this), "device" to Diagnostics.device()))
        }
        loadSettingsInfo()
    }

    private fun openSettings() {
        settingsMessage = null
        caps = Capabilities.read(this)
        loadSettingsInfo()
        go(Screen.Settings)
    }

    private fun loadSettingsInfo() {
        lifecycleScope.launch {
            val (d, k) = withContext(Dispatchers.IO) {
                val diag = store.readDiag()
                val kept = store.keptPackages().map { pkg ->
                    pkg to runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                }
                (diag.size to diag.firstOrNull()?.let { fmt(it.optLong("t")) }) to kept
            }
            diagInfo = d
            keptRows = k
        }
    }

    private fun exportLog() {
        lifecycleScope.launch {
            val i = withContext(Dispatchers.IO) { runCatching { Diagnostics.shareIntent(this@MainActivity) }.getOrNull() }
            if (i == null) { settingsMessage = "記錄檔做不出來，請再試一次。"; return@launch }
            store.diag("export")
            safeStart(i)
        }
    }

    private fun capabilityRows(c: Capabilities) = listOf(
            CapabilityRow(c.usageAccess, "使用情況存取", "找不到是哪個 App\n跳出全螢幕廣告。") { safeStart(Capabilities.usageAccessIntent()) },
            CapabilityRow(c.notificationAccess, "通知存取", "找不到是哪個 App 或網站\n發出假警告通知。") { safeStart(Capabilities.notificationAccessIntent()) },
            CapabilityRow(c.postNotifications, "通知權限", "找到來源時\n沒辦法提醒您。") { safeStart(Capabilities.appNotificationSettings(this)) },
            CapabilityRow(c.monitorAlive || !c.usageAccess, "背景監測", "監測被系統停止了，\n請開啟下面的「不限制電池」。") {
                GuardService.start(this); safeStart(Capabilities.batteryIntent(this))
            },
            CapabilityRow(c.batteryUnrestricted, "不限制電池", "有些手機會停掉守門員，\n就不能即時提醒您。\n打開後請選「不限制」；\n如果只看到開關，\n請點「允許在背景使用」\n這幾個字，再選「不限制」。", required = false) {
                safeStart(Capabilities.batteryIntent(this))
            },
        )

    private fun closedLast24h() =
        store.readLog(System.currentTimeMillis() - 24 * 3600 * 1000L).count { it.optString("type") == "blocked" && it.optInt("attempt") == 1 }

    private fun settingsState(): SettingsState {
        val c = caps ?: Capabilities.read(this)
        return SettingsState(
            capabilities = capabilityRows(c),
            closerOn = org.elderguard.monitor.AdCloserService.isEnabled(this),
            closedLast24h = closedLast24h(),
            diagOn = consent == true,
            diagCount = diagInfo.first,
            diagSince = diagInfo.second,
            kept = keptRows,
            version = Diagnostics.versionName(this),
            message = settingsMessage,
        )
    }

    private fun homeState(): HomeState {
        val c = caps ?: Capabilities.read(this)
        return HomeState(
            scanning = scanning,
            lastScan = store.lastScanMs.takeIf { it > 0 }?.let { fmt(it) },
            // Only apps still on the phone need action; removed ones keep their evidence on the detail page (bug 3, emulator test).
            flagged = rows.values.filter { it.installed && it.flagged && !store.isKept(it.packageName) }.sortedByDescending { it.behavior?.popups ?: 0 },
            monitorOk = c.fullProtection,
            capabilities = capabilityRows(c),
            lookupMessage = lookupMessage,
            closerOn = org.elderguard.monitor.AdCloserService.isEnabled(this),
            closedLast24h = closedLast24h(),
            consentAsked = consent != null,
        )
    }

    /** Uses the system's own screen-change history (works even if the guard was not running at that moment). */
    private fun findRecent() {
        val c = Capabilities.read(this)
        caps = c
        if (!c.usageAccess) {
            lookupMessage = "要先開啟「使用情況存取」，守門員才看得到是哪個 App 跳出畫面。"
            safeStart(Capabilities.usageAccessIntent())
            return
        }
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.Default) { queryUsage(24 * 3600 * 1000L).filter { it.lastMs >= System.currentTimeMillis() - 60 * 60 * 1000L } }
            summaries.forEach { rows = rows + (it.packageName to buildRow(it.packageName, it)) }
            store.diag("find_recent", fields = mapOf("found" to summaries.size, "pkgs" to summaries.joinToString(",") { "${it.packageName}:${it.count}" }))
            lookupMessage = if (summaries.isEmpty())
                "過去 1 小時，沒有 App 在您使用別的 App 時跳出全螢幕畫面。\n如果廣告出現在 YouTube 影片或網頁「裡面」，那是 YouTube 或網站自己的廣告，守門員無法替它找來源。"
            else null
            summaries.firstOrNull()?.let { go(Screen.Detail(it.packageName)) }
        }
    }

    private fun scanAll() {
        if (scanning) return
        scanning = true
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val built = withContext(Dispatchers.Default) {
                val usage = queryUsage(24 * 3600 * 1000L)
                val byPkg = usage.associateBy { it.packageName }
                val installed = AppScanner(this@MainActivity).allPackages().map { it.packageName }
                // include uninstalled sources that still have evidence in the local log
                val extra = store.readLog().filter { it.optString("type") in setOf("intrusion", "notif") }.map { it.optString("pkg") }
                (installed + byPkg.keys + extra).distinct().associateWith { buildRow(it, byPkg[it], logScan = true) }
            }
            rows = built
            store.lastScanMs = System.currentTimeMillis()
            store.lastScanFlagged = built.values.count { it.flagged }
            store.diag("scan_all", fields = mapOf("apps" to built.size, "user" to built.values.count { !it.isSystem },
                "flagged" to built.values.count { it.flagged }, "ms" to System.currentTimeMillis() - t0,
                "levels" to built.values.groupingBy { it.level.name }.eachCount().toString(),
                "incomplete" to built.values.count { it.installed && it.reasons.any { r -> r.contains("沒有檢查完成") || r.contains("沒有完整檢查") } }))
            scanning = false
            caps = Capabilities.read(this@MainActivity)
        }
    }

    private fun refreshRow(pkg: String) {
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.Default) { queryUsage(24 * 3600 * 1000L).firstOrNull { it.packageName == pkg } }
            val r = withContext(Dispatchers.Default) { buildRow(pkg, usage) }
            rows = rows + (pkg to r)
        }
    }

    private fun placeholder(pkg: String) = AppRow(pkg, pkg, null, false, null, null, AppRow.IconState.NONE, false, RiskLevel.NONE, emptyList(), null, null)

    /** Merges static facts, screen-change evidence and notification evidence for one package. */
    private fun buildRow(pkg: String, usage: IntrusionSummary?, logScan: Boolean = false): AppRow {
        val scanner = AppScanner(this)
        val result = runCatching { scanner.scan(pkg) }
        result.exceptionOrNull()?.let { store.diag("scan_error", pkg, mapOf("err" to (it.javaClass.simpleName + ": " + it.message).take(300), "at" to it.stackTrace.take(4).joinToString(" < ") { f -> "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}" })) }
        val e = result.getOrNull()
        val log = store.readLog().filter { it.optString("pkg") == pkg }
        // also cleans up entries logged before 0.5.0 blamed Google Play for the names of apps it was installing
        val senderIsSystem = runCatching { (packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 }.getOrDefault(false)
        val notifWarn = log.count { it.optString("type") == "notif" && it.optBoolean("warn") &&
            NotificationClassifier.countsAgainstSender(senderIsSystem, it.optString("site").ifBlank { null }) }
        val sites = log.mapNotNull { it.optString("site").ifBlank { null } }.distinct()
        // The ad-closer logs the same pop-ups again ("src":"closer"); count each pop-up once.
        val loggedPopups = log.filter { it.optString("type") == "intrusion" && it.optString("src") != "closer" }
        val closed = log.count { it.optString("type") == "blocked" && it.optInt("attempt") == 1 && it.optLong("t") >= System.currentTimeMillis() - 24 * 3600 * 1000L }
        // Distinct pop-ups in the last 24 h from both monitors; the same pop-up seen by both (within 3 s) counts once.
        val since = System.currentTimeMillis() - 24 * 3600 * 1000L
        val allTimes = (usageTimes[pkg].orEmpty() + log.filter { it.optString("type") == "intrusion" }.map { it.optLong("t") })
            .filter { it >= since }.sorted()
        val distinct = allTimes.fold(mutableListOf<Long>()) { acc, t -> if (acc.isEmpty() || t - acc.last() >= 3_000) acc.add(t); acc }
        val popups = distinct.size
        val lastMs = distinct.maxOrNull()
        val covered = ((usage?.coveredPackages ?: emptyList()) + loggedPopups.map { it.optString("over") }).distinct().filter { it.isNotBlank() }
        val behavior = if (popups > 0 || notifWarn > 0 || closed > 0) Behavior(
            confirmed = popups >= 2 || notifWarn >= 2,
            popups = popups,
            lastTime = lastMs?.let { fmt(it) },
            coveredLabels = covered.map { coveredName(it) }.distinct(),
            warningNotifications = notifWarn,
            sites = sites,
            closedCount = closed,
        ) else null
        if (e == null) {
            // A scan error must never make an installed app look removed (vivo, 0.4.0: Cleanify shown as "已經不在手機上了").
            val stillInstalled = runCatching { packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
            return placeholder(pkg).copy(
                installed = stillInstalled,
                label = if (stillInstalled) runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) else pkg,
                reasons = if (stillInstalled) listOf("這個 App 沒有檢查完成，請稍後再試") else emptyList(),
                behavior = behavior,
            )
        }
        val v = StaticRisk.assess(e.facts)
        // Tuning data: every app with any signal, so both false alarms and misses (e.g. Fast PDF Reader) can be studied.
        if (logScan && !e.facts.isSystem && (v.score > 0 || e.facts.adNetworks.isNotEmpty() || behavior != null))
            store.diag("scan", pkg, Diagnostics.scanFields(e.facts, v) + mapOf("src" to "scan_all", "popups" to behavior?.popups))
        val ai = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val icon = ai?.let { runCatching { packageManager.getApplicationIcon(it).toBitmap(144, 144).asImageBitmap() }.getOrNull() }
        return AppRow(
            packageName = pkg,
            label = e.facts.label,
            icon = icon,
            installed = true,
            installTime = fmt(e.installMs),
            installer = installerName(e.facts.installer),
            // A missing icon is only meaningful for user-installed apps; system components often have none by design.
            iconState = when {
                e.facts.isSystem -> AppRow.IconState.VISIBLE
                e.facts.hiddenIcon -> AppRow.IconState.HIDDEN
                e.facts.noLauncherActivity -> AppRow.IconState.NONE
                else -> AppRow.IconState.VISIBLE
            },
            isSystem = e.facts.isSystem,
            level = v.level,
            reasons = v.reasons + if (!e.facts.scanComplete) listOf("App 太大，內容沒有完整檢查") else emptyList(),
            behavior = behavior,
            overlayAllowed = e.overlayAllowed,
        )
    }

    private fun coveredName(pkg: String): String {
        val homes = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
        return when {
            pkg in homes -> "桌面"
            pkg == packageName -> "守門員"
            pkg == "com.android.vending" -> "Play 商店"
            pkg.contains("packageinstaller") -> "解除安裝的確認畫面"
            pkg.contains("permissioncontroller") -> "權限確認畫面"
            pkg == "com.android.settings" -> "設定"
            else -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }

    private fun installerName(i: String?) = when (i) {
        "com.android.vending" -> "Google Play 商店"
        null, "" -> "無法取得（可能是自行安裝的 APK）"
        "com.google.android.packageinstaller", "com.android.packageinstaller" -> "自行安裝的 APK"
        else -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(i, 0)).toString() }.getOrDefault(i)
    }

    /** Opening a system page must never crash the guard; fall back to the general Settings page. */
    private fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            lookupMessage = "這支手機不能直接打開那個設定頁，已經幫您打開「設定」。"
        }
    }

    private fun fmt(t: Long) = SimpleDateFormat("M/d a h:mm", Locale.TAIWAN).format(Date(t))

    companion object {
        const val EXTRA_OPEN_PKG = "open_pkg"
        /** Same process as the ad-closer (accessibility service), which reads it in real time. */
        @Volatile var userLeftAtMs = 0L
    }
}
