package org.elderguard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.elderguard.detect.RiskLevel

// ---------------------------------------------------------------- models shown by the UI

/** Behaviour evidence for one app, from the system's screen-change history and the notification watcher. */
data class Behavior(
    val confirmed: Boolean,
    val popups: Int,
    val lastTime: String?,
    val coveredLabels: List<String>,
    val warningNotifications: Int,
    val sites: List<String>,
    /** Times the optional ad-closer closed this app's pop-ups. */
    val closedCount: Int = 0,
)

data class AppRow(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val installed: Boolean,
    val installTime: String?,
    val installer: String?,
    val iconState: IconState,
    val isSystem: Boolean,
    val level: RiskLevel,
    val reasons: List<String>,
    val behavior: Behavior?,
    val overlayAllowed: Boolean?,
) {
    enum class IconState { VISIBLE, HIDDEN, NONE }
    /** Confirmed by behaviour beats any static guess. */
    val flagged get() = behavior?.confirmed == true || level == RiskLevel.HIGH || level == RiskLevel.MEDIUM
}

/** [required] = needed for "監測運作中"; optional rows (battery) are only shown in Settings. */
data class CapabilityRow(val ok: Boolean, val name: String, val missingEffect: String, val required: Boolean = true, val fix: (() -> Unit)?)

data class HomeState(
    val scanning: Boolean,
    val lastScan: String?,
    val flagged: List<AppRow>,
    val monitorOk: Boolean,
    val capabilities: List<CapabilityRow>,
    val lookupMessage: String?,
    val closerOn: Boolean = false,
    val closedLast24h: Int = 0,
    /** false until the user answered the one-time "help improve the guard?" question. */
    val consentAsked: Boolean = true,
)

data class SettingsState(
    val capabilities: List<CapabilityRow>,
    val closerOn: Boolean,
    val closedLast24h: Int,
    val diagOn: Boolean,
    val diagCount: Int,
    val diagSince: String?,
    val kept: List<Pair<String, String>>, // package to label
    val version: String,
    val message: String?,
)

// ---------------------------------------------------------------- building blocks

private val CardShape = RoundedCornerShape(24.dp)
private val ButtonShape = RoundedCornerShape(18.dp)

@Composable
private fun Page(bottom: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.Paper).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
        if (bottom != null) {
            Surface(color = Palette.Card, shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { bottom() }
            }
        }
    }
}

@Composable
private fun BrandBar(onBack: (() -> Unit)? = null, onSettings: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ShieldMark(ShieldState.Brand, 32.dp)
        Spacer(Modifier.size(10.dp))
        Text("守門員", style = MaterialTheme.typography.titleLarge, color = Palette.Trust, modifier = Modifier.weight(1f))
        if (onBack != null) TextButton(onClick = onBack) { Text("回首頁", style = MaterialTheme.typography.labelMedium, color = Palette.Trust) }
        if (onSettings != null) OutlinedButton(onClick = onSettings, shape = ButtonShape, border = BorderStroke(1.5.dp, Palette.Trust),
            modifier = Modifier.heightIn(min = 48.dp)) { Text("設定", style = MaterialTheme.typography.labelMedium, color = Palette.Trust) }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Safe))
    }
}

@Composable
private fun StatusLine(ok: Boolean, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(if (ok) Palette.Safe else Palette.Caution)
        Spacer(Modifier.size(12.dp))
        Text(name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(if (ok) "已開啟" else "沒有開啟", style = MaterialTheme.typography.bodyMedium, color = if (ok) Palette.Safe else Palette.Caution)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, color: Color = Palette.Trust) {
    Button(onClick = onClick, shape = ButtonShape, colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, shape = ButtonShape, border = BorderStroke(2.dp, Palette.Trust),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Trust, containerColor = Palette.Card),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun QuietButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Palette.InkSoft)
    }
}

@Composable
private fun Icon(bmp: ImageBitmap?, size: Int = 64) {
    val m = Modifier.size(size.dp).clip(RoundedCornerShape(16.dp))
    if (bmp != null) Image(bmp, contentDescription = null, modifier = m) else Box(m.background(Palette.Line))
}

@Composable
private fun Bullet(text: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 12.dp)) { Dot(color) }
        Spacer(Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.Ink)
    }
}

@Composable
private fun Card(color: Color = Palette.Card, border: Boolean = true, content: @Composable () -> Unit) {
    Surface(shape = CardShape, color = color, border = if (border) BorderStroke(1.dp, Palette.Line) else null, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

private fun iconText(s: AppRow.IconState) = when (s) {
    AppRow.IconState.VISIBLE -> "有"
    AppRow.IconState.HIDDEN -> "被藏起來了"
    AppRow.IconState.NONE -> "沒有（這個 App 本來就沒有圖示）"
}

private fun headline(r: AppRow) = when {
    r.behavior?.confirmed == true -> "疑似反覆跳出廣告"
    r.behavior != null && r.behavior.warningNotifications > 0 -> "發出假警告通知"
    r.level == RiskLevel.HIGH -> "藏起圖示，而且有很多廣告"
    r.level == RiskLevel.MEDIUM -> "可能會跳廣告騙人"
    r.level == RiskLevel.NOTICE -> "廣告比較多"
    else -> "目前沒有發現問題"
}

@Composable
private fun AppLine(r: AppRow, onOpen: () -> Unit) {
    Surface(shape = CardShape, color = Palette.Card, border = BorderStroke(1.dp, if (r.flagged) Palette.Caution else Palette.Line),
        onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(r.icon, 52)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.label, style = MaterialTheme.typography.titleLarge)
                Text(headline(r), style = MaterialTheme.typography.bodyMedium, color = if (r.flagged) Palette.Caution else Palette.InkSoft)
                if (r.iconState == AppRow.IconState.HIDDEN) Text("桌面上看不到它", style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
            }
        }
    }
}

// ---------------------------------------------------------------- screens

@Composable
fun HomeScreen(
    s: HomeState,
    onFindRecent: () -> Unit,
    onScanAll: () -> Unit,
    onOpen: (AppRow) -> Unit,
    onSettings: () -> Unit,
    onConsent: (Boolean) -> Unit,
    onCall165: () -> Unit,
) = Page {
    BrandBar(onSettings = onSettings)
    val found = s.flagged.isNotEmpty()
    Surface(shape = CardShape, color = if (found) Palette.CautionSoft else Palette.TrustSoft, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ShieldMark(if (found) ShieldState.Caution else ShieldState.Brand, 96.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                when {
                    found -> "找到 ${s.flagged.size} 個\n需要您處理的 App"
                    // never say "nothing found" before this check has finished (0.5.0 vivo: shown during the rescan)
                    s.scanning -> "正在檢查\n請稍等"
                    s.lastScan == null -> "還沒檢查過\n這支手機"
                    else -> "這次檢查\n沒有發現可疑的 App"
                },
                style = MaterialTheme.typography.headlineLarge, color = if (found) Palette.Caution else Palette.Trust,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.size(6.dp))
            Text(if (s.lastScan == null) "請按下面的「檢查所有 App」" else "上次檢查：${s.lastScan}",
                style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
            if (!found && !s.scanning && s.lastScan != null) Text("沒有發現，不代表一定安全。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        }
    }
    s.flagged.forEach { AppLine(it) { onOpen(it) } }
    s.lookupMessage?.let { Card(Palette.TrustSoft, border = false) { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    PrimaryButton("找出剛剛跳出來的廣告", onFindRecent)
    if (s.scanning) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = Palette.Trust, modifier = Modifier.size(30.dp))
            Spacer(Modifier.size(14.dp))
            Text("正在檢查所有 App…", style = MaterialTheme.typography.bodyLarge)
        }
    } else SecondaryButton("檢查所有 App", onScanAll)

    Card(if (s.monitorOk) Palette.SafeSoft else Palette.CautionSoft, border = false) {
        Text(if (s.monitorOk) "彈出廣告監測：運作中" else "彈出廣告監測：沒有完整運作",
            style = MaterialTheme.typography.titleLarge, color = if (s.monitorOk) Palette.Safe else Palette.Caution)
        if (s.monitorOk) Text("有 App 在您使用別的 App 時跳出畫面，守門員會通知您是哪一個。", style = MaterialTheme.typography.bodyMedium)
        else {
            Text("還沒開啟：" + s.capabilities.filter { !it.ok && it.required }.joinToString("、") { it.name }, style = MaterialTheme.typography.bodyMedium)
            PrimaryButton("去設定開啟", onSettings, Palette.Caution)
        }
        if (s.closerOn && s.closedLast24h > 0) Text("過去 24 小時自動關掉廣告 ${s.closedLast24h} 次。", style = MaterialTheme.typography.bodyMedium, color = Palette.Safe)
    }
    if (!s.consentAsked) Card(Palette.Card) {
        Text("要不要幫忙改進守門員？", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("同意的話，守門員會\n在手機裡記下檢查結果\n和出錯的情形，\n用來改進守門員。", style = MaterialTheme.typography.bodyMedium)
        Text("記錄只存在這支手機，\n不會自己傳出去。\n不記錄通知內容、\n照片、聯絡人和聊天。\n之後可以在設定裡改。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        SecondaryButton("好，幫忙記錄") { onConsent(true) }
        QuietButton("不要") { onConsent(false) }
    }
    Card(Palette.TrustSoft, border = false) {
        Text("有人打電話叫您匯款？", style = MaterialTheme.typography.titleLarge)
        Text("先掛掉，再打 165 問問看。\n免費，有真人接聽。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        SecondaryButton("撥打 165", onCall165)
    }
    QuietButton("設定（權限、自動關廣告、記錄檔）", onSettings)
}

@Composable
fun SettingsScreen(
    s: SettingsState,
    onBack: () -> Unit,
    onCloserSettings: () -> Unit,
    onDiag: (Boolean) -> Unit,
    onExport: () -> Unit,
    onUnkeep: (String) -> Unit,
    onAllApps: () -> Unit,
) = Page {
    BrandBar(onBack)
    Text("設定", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    s.message?.let { Card(Palette.TrustSoft, border = false) { Text(it, style = MaterialTheme.typography.bodyLarge) } }

    Card {
        Text("保護功能需要的權限", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("全部開啟，守門員才能\n找出跳廣告的 App。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        s.capabilities.forEach { c ->
            StatusLine(c.ok, c.name)
            if (!c.ok) {
                Text("沒有開啟的話，\n${c.missingEffect}", style = MaterialTheme.typography.bodyMedium)
                c.fix?.let { SecondaryButton("去開啟", it) }
            }
        }
    }

    Card(if (s.closerOn) Palette.SafeSoft else Palette.Card) {
        Text("自動關閉連續跳出的廣告", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        StatusLine(s.closerOn, "目前狀態")
        Text("同一個 App 在 30 分鐘內\n第二次跳出全螢幕廣告，\n蓋住您正在用的畫面，\n守門員就自動把它關掉。\n第一次跳出不會動作。",
            style = MaterialTheme.typography.bodyMedium)
        if (s.closerOn && s.closedLast24h > 0) Text("過去 24 小時已經關掉 ${s.closedLast24h} 次。", style = MaterialTheme.typography.bodyMedium, color = Palette.Safe)
        if (!s.closerOn) Text("要在手機的「無障礙」\n設定裡開啟「守門員」。\n守門員不會讀取\n畫面上的文字。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        SecondaryButton(if (s.closerOn) "去關閉" else "去開啟", onCloserSettings)
    }

    Card {
        Text("幫忙改進守門員", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        SwitchRow("記錄診斷資料", s.diagOn, onDiag)
        Text("會記錄：檢查結果、\n廣告被關掉的情形、\n守門員出錯的情形、\n手機型號和 Android 版本。",
            style = MaterialTheme.typography.bodyMedium)
        Text("不會記錄：通知內容、\n照片、聯絡人、聊天、\n帳號、完整網址。\n\n記錄只存在這支手機裡，\n最多保留 14 天。\n關掉這個選項，\n會刪除已經記下的資料。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        if (s.diagOn) Text(if (s.diagCount == 0) "目前還沒有記錄。" else "目前有 ${s.diagCount} 筆，\n從 ${s.diagSince}\n開始記錄。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.Safe)
        SecondaryButton("匯出記錄檔", onExport)
        Text("按下去會出現分享選單，\n由您決定傳給誰，\n例如用 LINE 或 Email。\n守門員沒有網路權限，\n不會自己上傳。",
            style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    }

    if (s.kept.isNotEmpty()) Card {
        Text("您說要保留的 App", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("這些 App 不會再出現在\n首頁，也不會再提醒。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        s.kept.forEach { (pkg, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onUnkeep(pkg) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("恢復提醒", style = MaterialTheme.typography.labelMedium, color = Palette.Trust)
                }
            }
        }
    }

    SecondaryButton("看手機上所有的 App", onAllApps)
    Text("桌面上看不到圖示的 App\n也會列出來。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    Text("守門員 ${s.version}", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
}

@Composable
fun AppListScreen(rows: List<AppRow>, showSystem: Boolean, onToggleSystem: () -> Unit, onOpen: (AppRow) -> Unit, onBack: () -> Unit) = Page {
    BrandBar(onBack)
    Text("手機上的 App", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    Text("共 ${rows.size} 個。桌面上看不到的 App 也會列在這裡。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
    QuietButton(if (showSystem) "不要顯示系統內建的 App" else "也顯示系統內建的 App", onToggleSystem)
    rows.forEach { AppLine(it) { onOpen(it) } }
}

@Composable
fun AppDetailScreen(
    r: AppRow,
    onAppInfo: () -> Unit,
    onUninstall: () -> Unit,
    onNotifications: () -> Unit,
    onOverlay: () -> Unit,
    onKeep: () -> Unit,
    onBack: () -> Unit,
) = Page(bottom = if (r.installed && r.flagged) ({
    PrimaryButton("前往設定移除", onAppInfo)
    Text("在設定頁先按「強制停止」，\n廣告就會停下來；\n再按「解除安裝」。\n照片和 LINE 不受影響。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
}) else null) {
    BrandBar(onBack)
    Surface(shape = CardShape, color = if (r.flagged) Palette.CautionSoft else Palette.Card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(r.icon, 72)
            Spacer(Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(r.label, style = MaterialTheme.typography.headlineMedium)
                Text(if (r.installed) headline(r) else "已經不在手機上了", style = MaterialTheme.typography.bodyMedium,
                    color = if (r.flagged) Palette.Caution else Palette.InkSoft)
            }
        }
    }
    r.behavior?.let { b ->
        if (b.popups > 0 || b.closedCount > 0) Card {
            Text(if (b.confirmed) "守門員看到的（已確認）" else "守門員看到的（只出現一次，還不確定）", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
            Bullet("您在用別的 App 時，它跳出全螢幕畫面 ${b.popups} 次", Palette.Caution)
            b.lastTime?.let { Bullet("最近一次：$it", Palette.Caution) }
            if (b.coveredLabels.isNotEmpty()) Bullet("蓋住了：${b.coveredLabels.joinToString("、")}", Palette.Caution)
            if (b.closedCount > 0) Bullet("守門員已自動幫您關掉 ${b.closedCount} 次", Palette.Safe)
        }
        if (b.warningNotifications > 0) Card {
            Text("它發出的假警告通知", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
            Bullet("共 ${b.warningNotifications} 則，假裝手機有問題", Palette.Caution)
            if (b.sites.isNotEmpty()) Bullet("來自網站：${b.sites.joinToString("、")}", Palette.Caution)
        }
    }
    if (r.reasons.isNotEmpty()) Card {
        Text("檢查 App 內容發現（疑似）", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        r.reasons.take(4).forEach { Bullet(it, Palette.Caution) }
    }
    Card {
        Text("基本資料", style = MaterialTheme.typography.titleLarge, color = Palette.Trust)
        Text("套件名稱：${r.packageName}", style = MaterialTheme.typography.bodyMedium)
        r.installTime?.let { Text("安裝時間：$it", style = MaterialTheme.typography.bodyMedium) }
        Text("從哪裡安裝：${r.installer ?: "無法取得"}", style = MaterialTheme.typography.bodyMedium)
        if (r.installed) Text("桌面圖示：${iconText(r.iconState)}", style = MaterialTheme.typography.bodyMedium)
        if (r.overlayAllowed == true) Text("可以蓋在其他 App 上面：是", style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
    }
    if (r.installed) {
        if (!r.flagged) SecondaryButton("前往這個 App 的設定", onAppInfo)
        SecondaryButton("直接解除安裝", onUninstall)
        SecondaryButton("關掉它的通知", onNotifications)
        if (r.overlayAllowed != false) SecondaryButton("不讓它蓋在其他 App 上面", onOverlay)
        Text("守門員不能替您關掉別的 App。\n請在設定頁按「解除安裝」或「強制停止」。", style = MaterialTheme.typography.bodyMedium, color = Palette.InkSoft)
        if (r.flagged) QuietButton("我認識這個 App，要保留", onKeep)
    }
}
