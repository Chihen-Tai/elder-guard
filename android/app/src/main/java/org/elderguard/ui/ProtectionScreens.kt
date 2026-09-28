package org.elderguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- state shown by the protection screens

/** One protection function and its REAL state (from the running service / Android), never from a setting alone. */
data class Part(val name: String, val on: Boolean, val offText: String, val fix: (() -> Unit)?)

data class ProtectionHome(
    val adBlocking: Part,
    val antiRedirect: Part,
    val popups: Part,
    val fakeNotifications: Part,
    val blockedToday: Int,
    val suspiciousApps: Int,
    val unlockLeftSec: Long,
    val advancedAvailable: Boolean,
) {
    val allOn get() = adBlocking.on && antiRedirect.on && popups.on && fakeNotifications.on
}

data class LabeledPkg(val pkg: String, val label: String, val note: String? = null, val on: Boolean = false)

data class VpnPageState(
    val statusText: String,
    val active: Boolean,
    val wanted: Boolean,
    val blockedToday: Long,
    val ruleCount: Int,
    val lastUpdated: String?,
    val upstream: String,
    val upstreamChoices: List<Pair<String, String>>, // id to label
    val upstreamFallback: Boolean,
    val appModeAll: Boolean,
    val bypass: List<LabeledPkg>,
    val selected: List<LabeledPkg>,
    val warnings: List<String>,
    val alwaysOn: Boolean?,
)

data class FilterListRow(
    val id: String, val name: String, val enabled: Boolean, val rules: Int, val updated: String?, val error: String?,
    val license: String, val canRollback: Boolean, val remote: Boolean, val builtin: Boolean,
)

data class FiltersPageState(
    val lists: List<FilterListRow>,
    val remoteUpdates: Boolean,
    val wifiOnly: Boolean,
    val intervalHours: Int,
    val busy: Boolean,
    val message: String?,
    val userBlock: List<String>,
    val userAllow: List<String>,
)

data class DnsRow(val time: String, val name: String, val blocked: Boolean, val app: String?)

data class RedirectRow(val time: String, val from: String, val to: String, val decision: String, val reason: String, val blocked: Boolean,
    val fromPkg: String, val toPkg: String)

data class DiagRow(val name: String, val ok: Boolean?, val detail: String)

data class StrictPageState(
    val deviceOwner: Boolean,
    val strictOn: Boolean,
    val pinSet: Boolean,
    val allow: List<LabeledPkg>,
    val suspendable: List<LabeledPkg>,
    val message: String?,
)

// ---------------------------------------------------------------- home: the "長輩保護" card

@Composable
fun ProtectionCard(s: ProtectionHome, onSetup: () -> Unit, onUnlock: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = if (s.allOn) Palette.SafeSoft else Palette.CautionSoft, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShieldMark(if (s.allOn) ShieldState.Safe else ShieldState.Caution, 44.dp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("長輩保護", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Text(if (s.allOn) "全部開啟" else "有功能沒有開啟", style = MaterialTheme.typography.titleLarge,
                        color = if (s.allOn) Palette.Safe else Palette.Caution)
                }
            }
            Text("今天已阻止：${s.blockedToday} 次", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
            StatusLine(s.adBlocking.on, "廣告防護")
            StatusLine(s.antiRedirect.on, "防止亂跳 App")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(if (s.suspiciousApps == 0) Palette.Safe else Palette.Caution)
                Spacer(Modifier.size(12.dp))
                Text("可疑 App", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("${s.suspiciousApps} 個", style = MaterialTheme.typography.bodyMedium, color = if (s.suspiciousApps == 0) Palette.Safe else Palette.Caution)
            }
            if (s.unlockLeftSec > 0) Text("跳轉保護暫停中，\n${s.unlockLeftSec} 秒後自動恢復。", style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
            if (!s.allOn) PrimaryButton("一鍵開啟長輩保護", onSetup, Palette.Caution)
            else QuietButton("暫時停止跳轉保護", onUnlock)
            if (s.advancedAvailable) Text("進階保護可用（設定 → 嚴格模式）", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        }
    }
}

// ---------------------------------------------------------------- one-tap setup

@Composable
fun SetupScreen(parts: List<Part>, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text("開啟長輩保護", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text("請一項一項按「開啟」。\n每一項都會打開手機的\n設定頁，開好後按返回。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    parts.forEach { p ->
        Card(if (p.on) Palette.SafeSoft else Palette.Card) {
            StatusLine(p.on, p.name)
            if (!p.on) {
                Text(p.offText, style = MaterialTheme.typography.bodyMedium)
                p.fix?.let { SecondaryButton("開啟", it) }
            }
        }
    }
    if (parts.all { it.on }) Card(Palette.SafeSoft, border = false) {
        Text("全部開好了！", style = MaterialTheme.typography.titleLarge, color = Palette.Safe)
        PrimaryButton("回首頁", onBack, Palette.Safe)
    }
}

// ---------------------------------------------------------------- advanced menu

@Composable
fun AdvancedScreen(items: List<Pair<String, () -> Unit>>, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text("進階設定", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text("給家人或熟悉手機的人使用。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    items.forEach { (t, f) -> SecondaryButton(t, f) }
}

// ---------------------------------------------------------------- ad blocking (VPN)

@Composable
fun VpnScreen(
    s: VpnPageState,
    onToggle: (Boolean) -> Unit,
    onUpstream: (String) -> Unit,
    onModeAll: (Boolean) -> Unit,
    onPickApps: () -> Unit,
    onVpnSettings: () -> Unit,
    onFilters: () -> Unit,
    onDnsHistory: () -> Unit,
    onBack: () -> Unit,
) = Page {
    BrandBar(onBack)
    Text("廣告防護", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Card(if (s.active) Palette.SafeSoft else Palette.CautionSoft) {
        SwitchRow("廣告防護", s.wanted, onToggle)
        Text("狀態：${s.statusText}", style = MaterialTheme.typography.bodyLarge, color = if (s.active) Palette.Safe else Palette.Caution)
        Text("今天已阻止：${s.blockedToday}", style = MaterialTheme.typography.bodyMedium)
        Text("過濾規則：${"%,d".format(s.ruleCount)} 條", style = MaterialTheme.typography.bodyMedium)
        s.lastUpdated?.let { Text("清單更新：$it", style = MaterialTheme.typography.bodyMedium) }
    }
    s.warnings.forEach { w -> Card(Palette.CautionSoft, border = false) { Text(w, style = MaterialTheme.typography.bodyMedium, color = Palette.Ink) } }
    Card {
        Text("這個功能怎麼運作", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("廣告防護使用 Android 的\nVPN 功能，在手機上擋掉\n廣告和追蹤網站的網址。\n網頁內容不會經過守門員，\n也不會被解密或上傳。",
            style = MaterialTheme.typography.bodyMedium)
        Text("同一時間只能有一個 VPN。\n開啟後，公司 VPN、\nWireGuard 等會被關掉；\n開其他 VPN 也會關掉這裡。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
        Text("擋不到的：和正常內容\n同一個網址的廣告\n（例如 YouTube 影片廣告）。\n這種由「防止亂跳 App」處理。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    }
    Card {
        Text("DNS 伺服器", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        s.upstreamChoices.forEach { (id, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                RadioButton(selected = s.upstream == id, onClick = { onUpstream(id) })
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        if (s.upstreamFallback) Text("加密 DNS 連不上，\n暫時改用網路原本的 DNS。", style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
    }
    Card {
        Text("哪些 App 要擋廣告", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            RadioButton(selected = s.appModeAll, onClick = { onModeAll(true) }); Text("全部 App（可排除銀行等）", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            RadioButton(selected = !s.appModeAll, onClick = { onModeAll(false) }); Text("只有選定的 App", style = MaterialTheme.typography.bodyMedium)
        }
        val list = if (s.appModeAll) s.bypass else s.selected
        Text(if (s.appModeAll) "不經過廣告防護的 App：" else "經過廣告防護的 App：", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        Text(if (list.isEmpty()) "（沒有）" else list.joinToString("、") { it.label }, style = MaterialTheme.typography.bodyMedium)
        SecondaryButton("選擇 App", onPickApps)
    }
    SecondaryButton("過濾清單與自訂規則", onFilters)
    SecondaryButton("DNS 紀錄", onDnsHistory)
    Card {
        Text("永遠開啟 VPN（選用）", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text(when (s.alwaysOn) { true -> "已在系統設定開啟。"; false -> "目前沒有開啟。"; null -> "開啟廣告防護後才看得到。" },
            style = MaterialTheme.typography.bodyMedium)
        Text("Android 的「永遠開啟 VPN」\n可以讓廣告防護重開機後\n更快恢復。守門員不會\n自己更改這個設定。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        SecondaryButton("打開系統 VPN 設定", onVpnSettings)
    }
}

@Composable
fun AppPickerScreen(title: String, hint: String, apps: List<LabeledPkg>, onToggle: (String, Boolean) -> Unit, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text(hint, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    apps.forEach { a ->
        Column {
            SwitchRow(a.label, a.on) { onToggle(a.pkg, it) }
            a.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft) }
        }
    }
}

// ---------------------------------------------------------------- filter lists + custom rules

@Composable
fun FiltersScreen(
    s: FiltersPageState,
    onEnable: (String, Boolean) -> Unit,
    onUpdate: (String?) -> Unit,
    onRollback: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRemote: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onAddList: (String, String) -> Unit,
    onAddRule: (String, Boolean) -> Unit,
    onRemoveRule: (String) -> Unit,
    onBack: () -> Unit,
    onInterval: (Int) -> Unit = {},
) = Page {
    BrandBar(onBack)
    Text("過濾清單", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    s.message?.let { Card(Palette.TrustSoft, border = false) { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    s.lists.forEach { l ->
        Card {
            SwitchRow(l.name, l.enabled) { onEnable(l.id, it) }
            Text("規則：${"%,d".format(l.rules)} 條　授權：${l.license}", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            Text(if (l.updated != null) "更新：${l.updated}" else if (l.remote && !l.builtin) "還沒下載" else "還沒下載（開啟並更新後生效）",
                style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            l.error?.let { Text("上次更新失敗：$it\n（舊的規則繼續使用）", style = MaterialTheme.typography.bodyMedium, color = Palette.Caution) }
            Row {
                if (l.remote && l.enabled) TextButton(onClick = { onUpdate(l.id) }, enabled = !s.busy) { Text("現在更新", style = MaterialTheme.typography.labelMedium) }
                if (l.canRollback) TextButton(onClick = { onRollback(l.id) }) { Text("還原上一版", style = MaterialTheme.typography.labelMedium) }
                if (!l.builtin) TextButton(onClick = { onRemove(l.id) }) { Text("移除", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
    Card {
        Text("自動更新", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        SwitchRow("從網路更新清單", s.remoteUpdates, onRemote)
        SwitchRow("只在 Wi-Fi 更新", s.wifiOnly, onWifiOnly)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(6, 12, 24, 48).forEach { hrs ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.intervalHours == hrs, onClick = { onInterval(hrs) })
                    Text("$hrs 小時", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text("只下載清單（純文字），\n不會下載程式。\n下載失敗時，舊的規則照常使用。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        SecondaryButton(if (s.busy) "更新中…" else "全部現在更新") { if (!s.busy) onUpdate(null) }
    }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Card {
        Text("加入自訂清單", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        OutlinedTextField(name, { name = it }, label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("https:// 網址") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        SecondaryButton("加入") { onAddList(name, url); url = ""; name = "" }
    }
    var domain by remember { mutableStateOf("") }
    Card {
        Text("自訂規則", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("自訂規則優先於所有清單。\n擋 example.com 也會擋\n它底下的網址；允許\napi.example.com 會例外放行。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        OutlinedTextField(domain, { domain = it }, label = { Text("網域，例如 ads.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) { SecondaryButton("擋掉") { onAddRule(domain, true); domain = "" } }
            Column(Modifier.weight(1f)) { SecondaryButton("允許") { onAddRule(domain, false); domain = "" } }
        }
        (s.userBlock.map { it to true } + s.userAllow.map { it to false }).forEach { (d, b) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text((if (b) "擋　" else "允許　") + d, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    color = if (b) Palette.Caution else Palette.Safe)
                TextButton(onClick = { onRemoveRule(d) }) { Text("刪除", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
fun DnsHistoryScreen(on: Boolean, rows: List<DnsRow>, onToggle: (Boolean) -> Unit, onRule: (String, Boolean) -> Unit, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text("DNS 紀錄", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Card {
        SwitchRow("記錄查詢過的網址", on, onToggle)
        Text("預設關閉。開啟後，會在\n手機裡記下最近查詢的網域\n（最多 500 筆、24 小時），\n用來找出被誤擋的網站。\n不會上傳；關閉時會刪除。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    }
    if (on && rows.isEmpty()) Text("還沒有紀錄。", style = MaterialTheme.typography.bodyMedium)
    rows.forEach { r ->
        Card(if (r.blocked) Palette.CautionSoft else Palette.Card, border = !r.blocked) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge)
            Text("${r.time}　${if (r.blocked) "已擋" else "放行"}${r.app?.let { "　$it" } ?: ""}", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            TextButton(onClick = { onRule(r.name, !r.blocked) }) {
                Text(if (r.blocked) "以後允許這個網址" else "以後擋掉這個網址", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------------------------------------------------------------- anti-redirect

@Composable
fun RedirectScreen(
    on: Boolean,
    serviceOk: Boolean,
    modeName: String,
    protectedCount: Int,
    rules: List<Triple<String, String, Pair<String, String>>>, // from label, to label, (from, to)
    unlockLeftSec: Long,
    onToggle: (Boolean) -> Unit,
    onFixService: () -> Unit,
    onProtectedApps: () -> Unit,
    onUnlock: (Long) -> Unit,
    onCancelUnlock: () -> Unit,
    onRemoveRule: (String, String) -> Unit,
    onHistory: () -> Unit,
    onBack: () -> Unit,
) = Page {
    BrandBar(onBack)
    Text("防止亂跳 App", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Card(if (on && serviceOk) Palette.SafeSoft else Palette.CautionSoft) {
        SwitchRow("防止亂跳 App", on, onToggle)
        if (!serviceOk) {
            Text("需要重新啟用\n防跳轉權限（無障礙）。", style = MaterialTheme.typography.bodyLarge, color = Palette.Caution)
            PrimaryButton("去開啟", onFixService, Palette.Caution)
        }
        Text("App 在您沒有點按時，\n突然把您帶到購物 App、\n商店或瀏覽器，守門員會\n帶您回去，或先問您。\n您自己點的，照常打開。",
            style = MaterialTheme.typography.bodyMedium)
        Text("守門員用「無障礙」功能\n看是哪個 App 出現、您剛才\n有沒有點按。不會讀取畫面\n文字、輸入內容或密碼。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        Text("Android 不允許一般 App\n在別的 App 打開之前攔下；\n守門員是在跳過去的瞬間\n馬上帶您回來。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    }
    Card {
        Text("暫時停止跳轉保護", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        if (unlockLeftSec > 0) {
            Text("暫停中，$unlockLeftSec 秒後恢復。", style = MaterialTheme.typography.bodyLarge, color = Palette.Caution)
            SecondaryButton("現在恢復", onCancelUnlock)
        } else {
            Text("時間到會自動恢復；\n重新開機也會恢復。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            listOf(30L to "30 秒", 60L to "1 分鐘", 300L to "5 分鐘", 600L to "10 分鐘").forEach { (sec, t) -> SecondaryButton("暫停 $t") { onUnlock(sec * 1000) } }
        }
    }
    Card {
        Text("受保護的 App", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("$modeName，目前 $protectedCount 個。", style = MaterialTheme.typography.bodyMedium)
        SecondaryButton("選擇受保護的 App", onProtectedApps)
    }
    Card {
        Text("允許的 App 關係", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        if (rules.isEmpty()) Text("還沒有。在提問時按\n「以後都允許」就會加在這裡。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        rules.forEach { (f, t, key) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("$f → $t", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemoveRule(key.first, key.second) }) { Text("刪除", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
    SecondaryButton("跳轉紀錄", onHistory)
}

@Composable
fun ProtectedAppsScreen(mode: String, apps: List<LabeledPkg>, onMode: (String) -> Unit, onToggle: (String, Boolean) -> Unit, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text("受保護的 App", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text("受保護的 App 如果在您沒有\n點按時打開別的 App，\n守門員會阻止或先問您。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    Card {
        listOf("AUTO" to "自動：有廣告程式的 App（建議）", "MANUAL" to "只保護我選的 App", "ALL" to "保護全部 App").forEach { (id, t) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                RadioButton(selected = mode == id, onClick = { onMode(id) })
                Text(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    apps.forEach { a ->
        Column {
            SwitchRow(a.label, a.on) { onToggle(a.pkg, it) }
            a.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft) }
        }
    }
}

@Composable
fun RedirectHistoryScreen(rows: List<RedirectRow>, onAllow: (String, String) -> Unit, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text("跳轉紀錄", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text("最近 7 天，只存在這支手機。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    if (rows.isEmpty()) Text("還沒有紀錄。", style = MaterialTheme.typography.bodyLarge)
    rows.forEach { r ->
        Card(if (r.blocked) Palette.CautionSoft else Palette.Card, border = !r.blocked) {
            Text(r.time, style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            Text("${r.from}\n→ ${r.to}", style = MaterialTheme.typography.titleLarge)
            Text(r.decision, style = MaterialTheme.typography.bodyLarge, color = if (r.blocked) Palette.Caution else Palette.Safe)
            if (r.reason.isNotEmpty()) Text(r.reason, style = MaterialTheme.typography.bodyMedium)
            if (r.blocked) TextButton(onClick = { onAllow(r.fromPkg, r.toPkg) }) { Text("以後允許這個跳轉", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

// ---------------------------------------------------------------- diagnostics

@Composable
fun DiagnosticsScreen(rows: List<DiagRow>, onExport: () -> Unit, onBattery: () -> Unit, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text("診斷", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Card {
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(top = 10.dp)) { Dot(when (r.ok) { true -> Palette.Safe; false -> Palette.Caution; null -> Palette.Line }) }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleLarge)
                    Text(r.detail, style = MaterialTheme.typography.bodyMedium, color = if (r.ok == false) Palette.Caution else Palette.InkSoft)
                }
            }
        }
    }
    SecondaryButton("電池設定（不限制）", onBattery)
    Text("有些手機（OPPO、vivo、\n小米、華為等）還要在\n「自動啟動」或「背景執行」\n允許守門員，名稱依品牌不同。",
        style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    SecondaryButton("匯出記錄檔", onExport)
}

// ---------------------------------------------------------------- strict mode (device owner)

@Composable
fun StrictScreen(
    s: StrictPageState,
    onSetPin: (String) -> Unit,
    onEnter: () -> Unit,
    onExit: (String) -> Unit,
    onAllowToggle: (String, Boolean) -> Unit,
    onSuspend: (String, Boolean) -> Unit,
    onRelease: (String) -> Unit,
    onBack: () -> Unit,
) = Page {
    BrandBar(onBack)
    Text("嚴格模式", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    s.message?.let { Card(Palette.TrustSoft, border = false) { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    if (!s.deviceOwner) {
        Card {
            Text("這支手機還沒有設定", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
            Text("嚴格模式只給「專門給長輩\n用的手機」，需要家人用電腦\n設定一次（手機上不能有\nGoogle 帳號，通常是剛恢復\n原廠的手機）：",
                style = MaterialTheme.typography.bodyMedium)
            Text("adb shell dpm set-device-owner org.elderguard/.owner.GuardAdminReceiver", style = MaterialTheme.typography.bodyMedium, color = Palette.Ink)
            Text("一般使用不需要嚴格模式。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        }
        return@Page
    }
    var pin by remember { mutableStateOf("") }
    Card(if (s.strictOn) Palette.SafeSoft else Palette.Card) {
        StatusLine(s.strictOn, "嚴格模式")
        Text("開啟後，只有下面允許的\nApp 能打開；廣告想打開\n其他 App，手機會直接拒絕。\n電話、緊急電話、設定、\n通知和關機選單永遠可用。",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() }.take(12) }, label = { Text(if (s.pinSet) "管理密碼" else "設定管理密碼（6–12 位數字）") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        if (!s.pinSet) SecondaryButton("設定密碼") { onSetPin(pin); pin = "" }
        else if (s.strictOn) PrimaryButton("輸入密碼，離開嚴格模式", { onExit(pin); pin = "" })
        else PrimaryButton("開啟嚴格模式", onEnter)
    }
    Card {
        Text("允許的 App", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        if (s.strictOn) Text("嚴格模式開著時不能修改；\n請先輸入密碼離開。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        s.allow.forEach { a -> if (s.strictOn) { if (a.on) Text(a.label, style = MaterialTheme.typography.bodyLarge) } else SwitchRow(a.label, a.on) { onAllowToggle(a.pkg, it) } }
    }
    Card {
        Text("暫停 App", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("暫停後 App 不能打開、\n不能跳通知，資料都保留，\n隨時可以恢復。手機運作\n需要的 App 不能暫停。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        s.suspendable.forEach { a ->
            if (s.strictOn) { if (a.on) Text(a.label + "（已暫停）", style = MaterialTheme.typography.bodyLarge) }
            else SwitchRow(a.label + if (a.on) "（已暫停）" else "", a.on) { onSuspend(a.pkg, it) }
        }
    }
    Card {
        Text("解除裝置擁有者", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("會關閉嚴格模式、恢復所有\n暫停的 App。之後要再用\n嚴格模式，需要恢復原廠\n後重新設定。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        QuietButton("輸入密碼後解除") { onRelease(pin); pin = "" }
    }
}
