package org.elderguard

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.elderguard.data.Store
import org.elderguard.filter.FilterRepository
import org.elderguard.filter.FilterUpdateJob
import org.elderguard.monitor.AdCloserService
import org.elderguard.monitor.Capabilities
import org.elderguard.owner.AdminPin
import org.elderguard.owner.StrictModeManager
import org.elderguard.redirect.Decision
import org.elderguard.redirect.ProtectedAppManager
import org.elderguard.redirect.RedirectGuard
import org.elderguard.redirect.RedirectHistoryRepository
import org.elderguard.redirect.RedirectRuleRepository
import org.elderguard.redirect.RedirectText
import org.elderguard.ui.DiagRow
import org.elderguard.ui.DnsRow
import org.elderguard.ui.FilterListRow
import org.elderguard.ui.FiltersPageState
import org.elderguard.ui.LabeledPkg
import org.elderguard.ui.Part
import org.elderguard.ui.ProtectionHome
import org.elderguard.ui.RedirectRow
import org.elderguard.ui.StrictPageState
import org.elderguard.ui.VpnPageState
import org.elderguard.vpn.AppMode
import org.elderguard.vpn.DnsStats
import org.elderguard.vpn.UpstreamChoice
import org.elderguard.vpn.VpnController
import org.elderguard.vpn.VpnRuntime
import org.elderguard.vpn.VpnSettings
import org.elderguard.vpn.VpnState

/**
 * State + actions for the protection pages (elder home card, one-tap setup, advanced pages). Every status is read
 * from the running services / Android at the time the page is shown — never from a saved switch alone.
 */
class ProtectionController(private val a: MainActivity) {
    private val store = Store(a)
    private val vpnSettings = VpnSettings(a)
    private val filters = FilterRepository.get(a)
    private val apps = ProtectedAppManager.get(a)
    private val rules = RedirectRuleRepository.get(a)
    private val history = RedirectHistoryRepository.get(a)
    private val strict = StrictModeManager(a)
    private val pin = AdminPin(a)

    var home by mutableStateOf<ProtectionHome?>(null); private set
    var vpnPage by mutableStateOf<VpnPageState?>(null); private set
    var filtersPage by mutableStateOf<FiltersPageState?>(null); private set
    var dnsRows by mutableStateOf(listOf<DnsRow>()); private set
    /** Compose state, not DnsStats.historyOn read during composition: turning it on with no rows yet changed no state,
     *  so the switch kept showing "off" and a second tap turned it off again (vivo field test). */
    var dnsHistoryOn by mutableStateOf(false); private set
    var redirectRows by mutableStateOf(listOf<RedirectRow>()); private set
    var diagRows by mutableStateOf(listOf<DiagRow>()); private set
    var strictPage by mutableStateOf<StrictPageState?>(null); private set
    var appList by mutableStateOf(listOf<LabeledPkg>()); private set
    private var filtersBusy = false
    private var filtersMessage: String? = null
    private var strictMessage: String? = null

    private val fmt = SimpleDateFormat("M/d a h:mm", Locale.TAIWAN)
    private val timeFmt = SimpleDateFormat("M/d HH:mm:ss", Locale.TAIWAN)

    fun label(pkg: String): String = runCatching { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    // ------------------------------------------------------------ home + setup

    fun parts(caps: Capabilities): List<Part> {
        val vpn = VpnController.status(a)
        val a11y = AdCloserService.isEnabled(a) && AdCloserService.connected
        return listOf(
            Part("廣告防護", vpn.active, when {
                vpn.otherVpnActive -> "手機正在使用別的 VPN。\n同一時間只能有一個 VPN，\n開啟廣告防護會把它關掉。"
                vpn.state == VpnState.FAILED_OPEN -> "廣告防護出了問題，已先關掉，\n網路照常可用。可以再試一次。"
                vpn.state == VpnState.REVOKED -> "廣告防護被其他 VPN 取代，\n或在系統設定中被關掉了。"
                else -> "在手機上擋掉廣告\n和追蹤網站。\n使用 Android 的 VPN，\n同時只能開一個。"
            }) { a.requestVpn() },
            Part("防止亂跳 App", a11y && apps.enabled, if (AdCloserService.isEnabled(a) && !AdCloserService.connected)
                "需要重新啟用防跳轉權限：\n到「無障礙」把 fkAd 關掉\n再打開。" else "在「無障礙」裡開啟 fkAd。\n守門員不會讀取畫面文字。") {
                apps.enabled = true; a.safeStart(AdCloserService.settingsIntent(a))
            },
            Part("彈出廣告偵測", caps.usageAccess, "開啟「使用情況存取」，\n才找得到是哪個 App\n跳出全螢幕廣告。") { a.safeStart(Capabilities.usageAccessIntent()) },
            Part("假通知偵測", caps.notificationAccess, "開啟「通知存取」，\n才找得到發假警告的 App。") { a.safeStart(Capabilities.notificationAccessIntent()) },
            Part("通知權限", caps.postNotifications, "找到問題時才能提醒您。") { a.safeStart(Capabilities.appNotificationSettings(a)) },
            Part("可疑 App 檢查", store.lastScanMs > 0, "檢查手機上所有的 App\n（約 1 分鐘）。") { a.startScan() },
        )
    }

    fun refreshHome(caps: Capabilities, suspicious: Int) {
        a.lifecycleScope.launch {
            val ps = parts(caps)
            val since = startOfToday()
            val (redirects, popups) = withContext(Dispatchers.IO) {
                history.blockedSince(since) to store.readLog(since).count { it.optString("type") == "blocked" && it.optInt("attempt") == 1 }
            }
            val vpnBlocked = DnsStats.get(a).blockedToday().toInt()
            home = ProtectionHome(ps[0], ps[1], ps[2], ps[3], vpnBlocked + redirects + popups, suspicious,
                RedirectGuard.sharedUnlock.remainingMs() / 1000, strict.isDeviceOwner && !strict.enabled)
        }
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ------------------------------------------------------------ VPN page

    fun refreshVpn() {
        val st = VpnController.status(a)
        val warnings = ArrayList<String>()
        if (st.otherVpnActive) warnings += "手機正在使用別的 VPN。\n同一時間只能有一個 VPN，\n開啟廣告防護會把它關掉。"
        st.privateDnsStrict?.let { warnings += "手機設定了「私人 DNS」\n（$it），這會讓廣告防護\n擋不到。可以到 設定 → 網路\n→ 私人 DNS 改成「自動」。" }
        if (st.lockdown == true && vpnSettings.bypass.isNotEmpty() && vpnSettings.appMode == AppMode.ALL)
            warnings += "系統開啟了「封鎖未使用 VPN\n的連線」，被排除的 App\n會完全無法上網。"
        if (st.state == VpnState.FAILED_OPEN) warnings += "廣告防護出了問題，已先關掉，\n網路照常可用。按上面的開關\n可以再試一次。"
        if (st.state == VpnState.NEEDS_CONSENT) warnings += "需要您再同意一次 VPN 連線。\n請把開關關掉再打開。"
        val statusText = when {
            st.active -> "運作中"
            st.state == VpnState.PAUSED -> "暫停中"
            st.state == VpnState.STARTING -> "啟動中"
            st.state == VpnState.REVOKED -> "被其他 VPN 取代或被關閉"
            st.state == VpnState.FAILED_OPEN -> "暫時停止（網路照常）"
            st.wanted && !st.systemHasOurVpn -> "沒有在運作"
            else -> "關閉"
        }
        val lists = filters.catalog().filter { it.enabled }
        vpnPage = VpnPageState(
            statusText = statusText, active = st.active, wanted = st.wanted, blockedToday = DnsStats.get(a).blockedToday(),
            ruleCount = filters.engine.blockRuleCount.takeIf { filters.loaded } ?: lists.sumOf { it.ruleCount },
            lastUpdated = lists.maxOfOrNull { it.lastUpdatedMs }?.takeIf { it > 0 }?.let { fmt.format(Date(it)) },
            upstream = vpnSettings.upstream.name, upstreamChoices = UpstreamChoice.entries.map { it.name to it.label },
            upstreamFallback = VpnRuntime.upstreamFallback, appModeAll = vpnSettings.appMode == AppMode.ALL,
            bypass = vpnSettings.bypass.map { LabeledPkg(it, label(it)) }, selected = vpnSettings.selected.map { LabeledPkg(it, label(it)) },
            warnings = warnings, alwaysOn = st.alwaysOn,
        )
    }

    fun setVpn(on: Boolean) { if (on) a.requestVpn() else VpnController.stop(a); a.lifecycleScope.launch { kotlinx.coroutines.delay(1500); refreshVpn() } }

    /** Settings that change the tunnel take effect by restarting it (the per-app list is fixed when it is built). */
    private fun restartVpnIfOn() { if (VpnController.status(a).active) VpnController.restart(a) }

    fun setUpstream(id: String) { vpnSettings.upstream = UpstreamChoice.valueOf(id); restartVpnIfOn(); refreshVpn() }
    fun setModeAll(all: Boolean) {
        if (!all && vpnSettings.selected.isEmpty()) { vpnSettings.appMode = AppMode.SELECTED; refreshVpn(); return } // restart after picking apps
        vpnSettings.appMode = if (all) AppMode.ALL else AppMode.SELECTED; restartVpnIfOn(); refreshVpn()
    }

    fun loadVpnApps() {
        a.lifecycleScope.launch {
            val all = vpnSettings.appMode == AppMode.ALL
            val chosen = if (all) vpnSettings.bypass else vpnSettings.selected
            appList = withContext(Dispatchers.Default) {
                userApps().map { (pkg, lbl) -> LabeledPkg(pkg, lbl, if (all && org.elderguard.redirect.SystemFlowClassifier.looksFinance(pkg)) "看起來是銀行或付款 App" else null, pkg in chosen) }
                    .sortedWith(compareByDescending<LabeledPkg> { it.on }.thenBy { it.label })
            }
        }
    }

    fun toggleVpnApp(pkg: String, on: Boolean) {
        if (vpnSettings.appMode == AppMode.ALL) vpnSettings.bypass = if (on) vpnSettings.bypass + pkg else vpnSettings.bypass - pkg
        else vpnSettings.selected = if (on) vpnSettings.selected + pkg else vpnSettings.selected - pkg
        appList = appList.map { if (it.pkg == pkg) it.copy(on = on) else it }
        restartVpnIfOn()
    }

    /** User-installed apps with a launcher icon (plus common preinstalled ones like Chrome / YouTube). */
    private fun userApps(): List<Pair<String, String>> {
        val pm = a.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != a.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
    }

    // ------------------------------------------------------------ filter lists

    fun refreshFilters() {
        a.lifecycleScope.launch {
            val (cat, user) = withContext(Dispatchers.IO) { filters.catalog() to filters.userRules() }
            filtersPage = FiltersPageState(
                lists = cat.map { FilterListRow(it.id, it.name, it.enabled, it.ruleCount, it.lastUpdatedMs.takeIf { t -> t > 0 }?.let { t -> fmt.format(Date(t)) },
                    it.lastError, it.license, it.hasBackup, it.url != null, it.builtin) },
                remoteUpdates = vpnSettings.remoteUpdates, wifiOnly = vpnSettings.updateOnWifiOnly, intervalHours = vpnSettings.updateIntervalHours,
                busy = filtersBusy, message = filtersMessage, userBlock = user.block, userAllow = user.allow,
            )
        }
    }

    private fun bg(work: () -> Unit) = a.lifecycleScope.launch { withContext(Dispatchers.IO) { runCatching(work) }; refreshFilters() }

    fun enableList(id: String, on: Boolean) = bg { filters.setEnabled(id, on) }
    fun rollback(id: String) = bg { filtersMessage = if (filters.rollback(id)) "已還原上一版。" else "沒有可以還原的版本。" }
    fun removeList(id: String) = bg { filters.remove(id) }
    fun addList(name: String, url: String) = bg { filtersMessage = filters.addCustom(name, url.trim()) ?: "已加入，\n請按「現在更新」下載。" }
    fun addRule(domain: String, block: Boolean) = bg { filtersMessage = if (filters.addUserRule(domain, block) == null) "「$domain」不是有效的網域。" else null }
    fun removeRule(domain: String) = bg { filters.removeUserRule(domain) }
    fun setRemote(on: Boolean) { vpnSettings.remoteUpdates = on; FilterUpdateJob.schedule(a); refreshFilters() }
    fun setInterval(h: Int) { vpnSettings.updateIntervalHours = h; FilterUpdateJob.schedule(a); refreshFilters() }
    fun setWifiOnly(on: Boolean) { vpnSettings.updateOnWifiOnly = on; FilterUpdateJob.schedule(a); refreshFilters() }

    fun updateNow(id: String?) {
        if (filtersBusy) return
        filtersBusy = true; filtersMessage = "正在下載…"; refreshFilters()
        a.lifecycleScope.launch {
            val retry = withContext(Dispatchers.IO) { runCatching { FilterUpdateJob.updateAll(a, force = false, onlyId = id) }.getOrDefault(true) }
            filtersBusy = false
            filtersMessage = if (retry) "有清單沒有更新成功，\n舊的規則照常使用。" else "更新完成。"
            refreshFilters()
        }
    }

    fun refreshDns() {
        val st = DnsStats.get(a)
        dnsHistoryOn = st.historyOn
        a.lifecycleScope.launch {
            dnsRows = withContext(Dispatchers.IO) { st.recent().take(200).map { DnsRow(timeFmt.format(Date(it.t)), it.name, it.blocked, it.app?.let(::label)) } }
        }
    }

    fun setDnsHistory(on: Boolean) { DnsStats.get(a).setHistory(on); refreshDns() }
    fun dnsRule(domain: String, block: Boolean) = bg { filters.addUserRule(domain, block); filtersMessage = null }

    // ------------------------------------------------------------ anti-redirect

    val redirectOn get() = apps.enabled
    val redirectServiceOk get() = AdCloserService.isEnabled(a) && AdCloserService.connected
    val protectedModeName get() = when (apps.mode) { ProtectedAppManager.Mode.AUTO -> "自動"; ProtectedAppManager.Mode.ALL -> "全部 App"; ProtectedAppManager.Mode.MANUAL -> "手動選擇" }
    fun protectedCount() = userApps().count { apps.isProtected(it.first) }
    fun redirectRules() = rules.all().filter { it.decision == Decision.ALLOW }.map { Triple(label(it.source), label(it.target), it.source to it.target) }
    fun setRedirect(on: Boolean) { apps.enabled = on }
    fun removeRedirectRule(s: String, t: String) = rules.remove(s, t)
    fun unlock(ms: Long) = RedirectGuard.sharedUnlock.unlock(ms)
    fun cancelUnlock() = RedirectGuard.sharedUnlock.cancel()
    val protectedMode get() = apps.mode.name

    fun loadProtectedApps() {
        a.lifecycleScope.launch {
            appList = withContext(Dispatchers.Default) {
                userApps().filter { (pkg, _) -> isUserInstalled(pkg) || pkg in apps.included }
                    .map { (pkg, lbl) -> LabeledPkg(pkg, lbl, when {
                        pkg in apps.adApps -> "裡面有廣告程式"
                        pkg in apps.learned -> "自動加入：它曾經\n自己跳到別的 App"
                        else -> null
                    }, apps.isProtected(pkg)) }
                    .sortedWith(compareByDescending<LabeledPkg> { it.on }.thenBy { it.label })
            }
        }
    }

    private fun isUserInstalled(pkg: String) = runCatching {
        (a.packageManager.getApplicationInfo(pkg, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
    }.getOrDefault(false)

    fun setProtectedMode(id: String) { apps.mode = ProtectedAppManager.Mode.valueOf(id); loadProtectedApps() }
    fun toggleProtected(pkg: String, on: Boolean) { apps.setProtected(pkg, on); appList = appList.map { if (it.pkg == pkg) it.copy(on = on) else it } }

    fun refreshRedirectHistory() {
        a.lifecycleScope.launch {
            redirectRows = withContext(Dispatchers.IO) {
                val all = history.read()
                val outcomes = all.filter { it.reasons.isEmpty() }
                all.filter { it.reasons.isNotEmpty() }.asReversed().take(200).map { e ->
                    val answered = outcomes.firstOrNull { it.source == e.source && it.target == e.target && it.timeMs >= e.timeMs && it.timeMs - e.timeMs < 5 * 60_000 }
                    RedirectRow(timeFmt.format(Date(e.timeMs)), label(e.source), label(e.target),
                        RedirectText.decision(e.decision, answered?.outcome) + if (e.decision != Decision.ALLOW) "（${RedirectText.headline(e.decision)}）" else "",
                        e.reasons.take(2).map { RedirectText.reason(it) }.filter { it.isNotEmpty() }.joinToString("、"),
                        e.decision == Decision.BLOCK && answered == null, e.source, e.target)
                }
            }
        }
    }

    fun allowRedirect(s: String, t: String) { rules.put(s, t, Decision.ALLOW); refreshRedirectHistory() }

    // ------------------------------------------------------------ diagnostics

    fun refreshDiagnostics(caps: Capabilities) {
        val vpn = VpnController.status(a)
        val eng = filters.engine
        val batteryOk = caps.batteryUnrestricted
        diagRows = listOf(
            DiagRow("廣告防護（VPN）", if (vpn.wanted) vpn.active else null, when {
                !vpn.wanted -> "沒有開啟"
                vpn.active -> "運作中\nDNS：${upstreamName(VpnRuntime.upstreamLabel)}" + if (VpnRuntime.upstreamFallback) "\n（加密 DNS 連不上，\n暫用網路原本的 DNS）" else ""
                else -> "設定是開啟，\n但 Android 沒有這個 VPN\n（${vpn.state}）${vpn.message?.let { "\n$it" } ?: ""}"
            }),
            DiagRow("過濾規則", filters.loaded && eng.blockRuleCount > 0, if (filters.loaded) "${"%,d".format(eng.blockRuleCount)} 條" + (filters.loadError?.let { "；$it" } ?: "") else "尚未載入\n（開啟廣告防護時載入）"),
            DiagRow("其他 VPN", !vpn.otherVpnActive, if (vpn.otherVpnActive) "有別的 VPN 正在使用" else "沒有"),
            DiagRow("私人 DNS", vpn.privateDnsStrict == null, vpn.privateDnsStrict?.let { "指定為 $it：會繞過廣告防護" } ?: "自動或關閉（正常）"),
            DiagRow("永遠開啟 VPN", null, when (vpn.alwaysOn) { true -> "已開啟" + if (vpn.lockdown == true) "，並封鎖未使用 VPN 的連線" else ""; false -> "沒有開啟（選用）"; null -> "開啟廣告防護後才看得到" }),
            DiagRow("防跳轉（無障礙）", AdCloserService.isEnabled(a) && AdCloserService.connected, when {
                !AdCloserService.isEnabled(a) -> "沒有開啟"
                !AdCloserService.connected -> "設定是開啟，\n但服務沒有在執行：\n請關掉再打開"
                else -> "運作中" + if (!apps.enabled) "（跳轉保護被關閉）" else ""
            }),
            DiagRow("通知存取", caps.notificationAccess, if (caps.notificationAccess) "已開啟" else "沒有開啟"),
            DiagRow("使用情況存取", caps.usageAccess, if (caps.usageAccess) "已開啟" else "沒有開啟"),
            DiagRow("背景監測", caps.monitorAlive, caps.heartbeatAgeSec?.let { "上次回報：$it 秒前" } ?: "沒有回報"),
            redirectStatsRow(),
            DiagRow("電池最佳化", batteryOk, if (batteryOk) "不限制（好）" else "受限制：部分手機會停掉守門員"),
            DiagRow("開機自動恢復", true, "廣告防護：" + (if (vpnSettings.enabled) "開" else "關") + "\n背景監測：開\n嚴格模式：" + (if (strict.enabled) "開" else "關")),
            DiagRow("Android 版本", null, "${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}），${Build.MANUFACTURER} ${Build.MODEL}"),
        )
    }

    /** Long-term jump statistics kept on the phone (counts only), for the family / the trial. */
    private fun redirectStatsRow(): DiagRow {
        val st = org.elderguard.redirect.RedirectHistoryRepository.get(a).stats()
        if (st.sinceMs == 0L) return DiagRow("跳轉統計", null, "還沒有紀錄")
        val taps = st.tapShop.sum() + st.tapOther.sum()
        val slow = st.tapShop[3] + st.tapOther[3]
        return DiagRow("跳轉統計", null, "從 ${java.text.SimpleDateFormat("M/d", java.util.Locale.TAIWAN).format(java.util.Date(st.sinceMs))} 開始\n" +
            "點了才開：$taps 次\n（3 秒以上：$slow 次）\n阻止 ${st.blocks} 次、先問 ${st.asks} 次\n事後仍然打開：${st.openedAnyway} 次")
    }

    private fun upstreamName(label: String?) = when {
        label == null -> "-"
        label.startsWith("doh") -> "加密 DNS（${vpnSettings.upstream.label.substringBefore('（')}）"
        label == "network" -> "目前網路的 DNS"
        else -> vpnSettings.upstream.label
    }

    // ------------------------------------------------------------ strict mode

    fun refreshStrict() {
        a.lifecycleScope.launch {
            val owner = strict.isDeviceOwner
            val (allow, susp) = if (!owner) emptyList<LabeledPkg>() to emptyList() else withContext(Dispatchers.Default) {
                val chosen = strict.allowlist
                val list = userApps()
                list.map { (p, l) -> LabeledPkg(p, l, null, p in chosen) }.sortedWith(compareByDescending<LabeledPkg> { it.on }.thenBy { it.label }) to
                    list.filter { strict.critical.whyCritical(it.first) == null }.map { (p, l) -> LabeledPkg(p, l, null, strict.isSuspended(p)) }
            }
            strictPage = StrictPageState(owner, strict.enabled, pin.isSet, allow, susp, strictMessage)
        }
    }

    fun setPin(p: String) { strictMessage = pin.set(p) ?: "密碼已設定。"; refreshStrict() }
    fun enterStrict() { strictMessage = strict.enter() ?: "嚴格模式已開啟。"; refreshStrict() }
    fun exitStrict(p: String) { strictMessage = pin.check(p) ?: run { strict.exit(); "已離開嚴格模式。" }; refreshStrict() }
    /** While strict mode is on nothing here changes without the PIN (leave strict mode first) — review M3. */
    fun strictAllow(pkg: String, on: Boolean) {
        if (strict.enabled) { strictMessage = "要修改，請先輸入\n密碼離開嚴格模式。"; refreshStrict(); return }
        strict.allowlist = if (on) strict.allowlist + pkg else strict.allowlist - pkg; refreshStrict()
    }
    fun suspend(pkg: String, on: Boolean) {
        if (strict.enabled) { strictMessage = "要修改，請先輸入\n密碼離開嚴格模式。"; refreshStrict(); return }
        strictMessage = strict.setSuspended(pkg, on); refreshStrict()
    }
    fun release(p: String) {
        strictMessage = pin.check(p) ?: run {
            strict.releaseOwnership(userApps().map { it.first }.filter { strict.isSuspended(it) }); "已解除裝置擁有者。"
        }
        refreshStrict()
    }
}
