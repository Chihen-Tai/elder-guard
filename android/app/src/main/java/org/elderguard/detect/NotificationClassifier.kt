package org.elderguard.detect

/**
 * Classifies one posted notification in memory. Only the resulting flags (and a web site's host) are stored;
 * the title/text are never persisted.
 *
 * "Disguised warning" = a device-threat word AND an urgency/action word, e.g. "手機中毒了！立即清理".
 * One of the two alone ("更新", "電池") is normal in legitimate notifications and is not flagged.
 */
object NotificationClassifier {
    private val threat = listOf("病毒", "中毒", "感染", "垃圾", "記憶體不足", "內存不足", "儲存空間不足", "空間不足", "電池", "耗電",
        "過熱", "變慢", "已損壞", "受損", "駭客", "virus", "infected", "malware", "junk", "memory", "storage", "battery",
        "overheat", "slow", "damaged", "hacked")
    private val urgency = listOf("警告", "危險", "立即", "馬上", "趕快", "緊急", "清理", "清除", "修復", "修理", "掃描", "最終",
        "!", "！", "⚠", "warning", "danger", "urgent", "now", "immediately", "clean", "fix", "repair", "scan")

    data class Result(val disguisedWarning: Boolean, val webHost: String?, val fullScreen: Boolean)

    fun classify(title: String?, text: String?, channelId: String?, fullScreen: Boolean): Result {
        val s = listOfNotNull(title, text).joinToString(" ").lowercase()
        val disguised = threat.any { it in s } && urgency.any { it in s }
        return Result(disguised, webHost(channelId), fullScreen)
    }

    /**
     * Whether a disguised warning counts against the app that posted it. System apps (Google Play, installers, ...)
     * only relay other apps' names: Play's "installing <Cleaner: 清理病毒 立即加速>" progress notification made Google
     * Play look like a fake-warning source (vivo 0.5.0 field log, 38 updates in 30 s). A browser's web-push is the
     * exception: it is attributed to the site, so it still counts.
     */
    fun countsAgainstSender(senderIsSystem: Boolean, webHost: String?) = !senderIsSystem || webHost != null

    /** Chrome names a site's notification channel "web:https://host;<id>"; returns the host, if any. */
    fun webHost(channelId: String?): String? {
        if (channelId == null || !channelId.startsWith("web:")) return null
        return channelId.removePrefix("web:").substringBefore(';').substringAfter("://").substringBefore('/').ifBlank { null }
    }
}
