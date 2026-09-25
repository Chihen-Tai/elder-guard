package org.elderguard.detect

/** What the static scan could learn about one installed app (no behaviour). */
data class AppFacts(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val installer: String?,
    val firstInstallMs: Long,
    /** The app ships a launcher activity but every one of them is disabled: it hid its own icon. */
    val hiddenIcon: Boolean,
    /** The app ships no launcher activity at all (common for legit helpers such as keyboards; weak on its own). */
    val noLauncherActivity: Boolean,
    val adNetworks: List<String>,
    /** Broadcast actions the app listens to that can wake it without the user (unlock, power, battery, boot). */
    val triggers: List<String>,
    val requestsOverlay: Boolean,
    val requestsNotificationListener: Boolean,
    val requestsUsageOrAllFiles: Boolean,
    /** Bait category from the name (cleaner, antivirus, recovery, PDF/document, QR, GPS, vault...), or null. */
    val baitCategory: String?,
    /** false when the dex could not be read within the budget: ad-network count is then a lower bound. */
    val scanComplete: Boolean,
    /** Examples of deliberately scrambled component names (Obfuscation), or null. */
    val obfuscatedExamples: List<String>? = null,
)

enum class RiskLevel { HIGH, MEDIUM, NOTICE, NONE }

data class StaticVerdict(val level: RiskLevel, val score: Int, val reasons: List<String>)

/**
 * Static part of EGDA (ALGORITHM.md v0.4.1), reduced to what can be read on the phone without root.
 * Static evidence alone never produces "confirmed": it only says "suspected". Behaviour (IntrusionDetector) confirms.
 */
object StaticRisk {
    private val sideloadInstallers = setOf(null, "", "com.google.android.packageinstaller", "com.android.packageinstaller")

    fun assess(f: AppFacts): StaticVerdict {
        if (f.isSystem) return StaticVerdict(RiskLevel.NONE, 0, emptyList())
        val r = mutableListOf<Pair<Int, String>>()
        val n = f.adNetworks.size
        val a1 = when { n >= 7 -> 3; n >= 4 -> 2; n >= 2 -> 1; else -> 0 }
        if (a1 > 0) r += a1 to "裡面有 $n 家廣告公司的程式"
        val a3 = when { f.triggers.size >= 2 -> 2; f.triggers.isNotEmpty() -> 1; else -> 0 }
        if (a3 > 0) r += a3 to "它會在您打開手機或充電時自己啟動"
        if (f.baitCategory != null) r += 1 to "「${f.baitCategory}」這類 App 常被拿來騙人"
        val mismatch = f.baitCategory != null && (f.requestsOverlay || f.requestsNotificationListener || f.requestsUsageOrAllFiles)
        if (mismatch) r += 3 to "它要求的權限跟它的功能對不上"
        if (f.hiddenIcon) r += 3 to "它把自己在桌面上的圖示藏起來了"
        if (f.obfuscatedExamples != null) r += 2 to "它刻意把自己的程式藏起來"
        val nameMismatch = nameMismatch(f.label, f.packageName)
        if (nameMismatch) r += 1 to "它顯示的名字跟真正的程式名稱對不上"
        if (f.installer in sideloadInstallers) r += 2 to "它不是從官方商店下載的"
        val score = r.sumOf { it.first }
        // "abuse" = at least one signal beyond "has ads" (same idea as EGDA): hidden icon, mismatch, or wake-up triggers.
        val abuse = f.hiddenIcon || mismatch || a3 > 0 || f.obfuscatedExamples != null
        val level = when {
            f.hiddenIcon && (n >= 2 || f.baitCategory != null) -> RiskLevel.HIGH
            score >= 7 && abuse -> RiskLevel.MEDIUM
            score >= 4 || n >= 4 -> RiskLevel.NOTICE
            else -> RiskLevel.NONE
        }
        return StaticVerdict(level, score, r.sortedByDescending { it.first }.map { it.second })
    }

    private val bait: Map<String, List<String>> = mapOf(
        "清理、加速" to listOf("清理", "垃圾清", "加速", "clean", "cleaner", "booster", "junk"),
        "防毒" to listOf("防毒", "病毒", "殺毒", "antivirus", "virus"),
        "省電" to listOf("省電", "battery saver"),
        "救回檔案" to listOf("救援", "恢復", "恢复", "復原", "recover", "recovery", "restore", "undelete"),
        "看文件" to listOf("pdf", "文件", "文檔", "閱讀器", "document", "docx", "doc reader", "office reader"),
        "掃 QR 碼" to listOf("qr", "掃描", "條碼", "scanner", "barcode"),
        "手電筒" to listOf("手電筒", "flashlight", "torch"),
        "GPS、指南針" to listOf("gps", "compass", "指南針", "導航", "route finder"),
        "相簿保險箱" to listOf("vault", "locker", "相片保險箱", "私密相簿"),
    )

    /** "PDFReader" -> "PDF Reader", "CleanNow" -> "Clean Now" (a joined label hid the bait word on 2026-09-25). */
    fun splitCamel(s: String): String =
        s.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")

    /**
     * The label advertises a bait category (e.g. PDF reader) that the package name does not mention and the two share
     * no word: "PDFReader" shipped as com.daily.plan.list.todo. Labels without Latin letters are not judged.
     */
    fun nameMismatch(label: String, packageName: String): Boolean {
        val labelCat = baitCategory(label, "") ?: return false
        if (baitCategory("", packageName) == labelCat) return false
        val lw = Regex("[a-z]{3,}").findAll(splitCamel(label).lowercase()).map { it.value }.toSet()
        val pw = packageName.lowercase().split('.', '_').filter { it.length >= 3 }.toSet()
        return lw.isNotEmpty() && lw.none { l -> pw.any { p -> p.contains(l) || l.contains(p) } }
    }

    /** Matches whole words for Latin keywords; package dots/underscores are treated as separators. */
    fun baitCategory(label: String, packageName: String): String? {
        val text = (splitCamel(label) + " " + packageName.replace('.', ' ').replace('_', ' ')).lowercase()
        return bait.entries.firstOrNull { (_, kws) ->
            kws.any { kw ->
                if (kw.all { it in 'a'..'z' || it == ' ' }) Regex("(?<![a-z])" + Regex.escape(kw) + "(?![a-z])").containsMatchIn(text)
                else text.contains(kw)
            }
        }?.key
    }
}
