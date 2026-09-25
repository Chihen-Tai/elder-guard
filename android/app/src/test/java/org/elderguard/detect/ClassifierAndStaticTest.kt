package org.elderguard.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierAndStaticTest {
    @Test fun disguisedWarnings_areFlagged() {
        assertTrue(NotificationClassifier.classify("⚠ 警告", "您的手機中毒了，請立即清理", null, false).disguisedWarning)
        assertTrue(NotificationClassifier.classify("記憶體不足！", "馬上清理加速", null, false).disguisedWarning)
        assertTrue(NotificationClassifier.classify("Warning", "Your phone is infected with a virus, clean now", null, false).disguisedWarning)
    }

    @Test fun normalNotifications_areNotFlagged() {
        val normal = listOf("王小明" to "今天晚上一起吃飯嗎？", "App 更新" to "新版本已經可以更新了", "電池" to "電池已充飽",
            "Gmail" to "您有 3 封新郵件", "LINE" to "媽媽：記得吃藥", "天氣" to "明天下午有雨，出門記得帶傘",
            "Google Play" to "3 個應用程式已更新", "備份" to "照片已備份完成")
        normal.forEach { (t, x) -> assertFalse("$t/$x", NotificationClassifier.classify(t, x, null, false).disguisedWarning) }
    }

    @Test fun chromeWebPushChannel_givesSiteHost() {
        assertEquals("alert-warning-hh.com", NotificationClassifier.webHost("web:https://alert-warning-hh.com;13357223600147786"))
        assertEquals(null, NotificationClassifier.webHost("miscellaneous"))
    }

    private fun facts(label: String, pkg: String, hidden: Boolean = false, ads: Int = 0, trig: Int = 0, overlay: Boolean = false,
                      installer: String? = "com.android.vending") = AppFacts(pkg, label, false, installer, 0, hidden, false,
        (1..ads).map { "N$it" }, (1..trig).map { "T$it" }, overlay, false, false, StaticRisk.baitCategory(label, pkg), true)

    @Test fun simulatorLikeApp_isHigh() {
        // hidden icon + PDF bait + 2 ad networks + overlay request + 2 triggers (the emulator simulator's profile)
        val v = StaticRisk.assess(facts("測試用 PDF 閱讀器", "org.elderguard.testing.adsim", hidden = true, ads = 2, trig = 2, overlay = true))
        assertEquals(RiskLevel.HIGH, v.level)
    }

    @Test fun adHeavyButWellBehavedApp_isOnlyNotice() {
        // e.g. a game with many ad networks but no hidden icon, no bait name, no permission mismatch
        val v = StaticRisk.assess(facts("Candy Crush Saga", "com.king.candycrushsaga", ads = 13, trig = 1))
        assertEquals(RiskLevel.NOTICE, v.level)
    }

    @Test fun ordinaryApps_areNone() {
        assertEquals(RiskLevel.NONE, StaticRisk.assess(facts("LINE", "jp.naver.line.android", ads = 1, trig = 1)).level)
        assertEquals(RiskLevel.NONE, StaticRisk.assess(facts("健保快易通", "com.nhiApp.v1")).level)
    }

    @Test fun baitCategory_usesPackageSeparators() {
        assertEquals("GPS、指南針", StaticRisk.baitCategory("GPS Route Finder", "map.ly.gps.navigation.route.planer"))
        assertEquals(null, StaticRisk.baitCategory("Maps", "com.google.android.apps.maps"))
    }

    /** vivo 0.5.0: Google Play's install-progress notification carried an adware app's name ("清理病毒 立即加速"). */
    @Test fun systemAppRelayingAnotherAppsName_isNotAFakeWarningSource() {
        val r = NotificationClassifier.classify("正在安裝「Cleaner: 清理病毒 立即加速」", null, "download_progress", false)
        org.junit.Assert.assertTrue("the text itself does look like a warning", r.disguisedWarning)
        org.junit.Assert.assertFalse(NotificationClassifier.countsAgainstSender(senderIsSystem = true, webHost = r.webHost))
        org.junit.Assert.assertTrue("a user app posting it still counts", NotificationClassifier.countsAgainstSender(false, null))
        org.junit.Assert.assertTrue("Chrome web-push is attributed to the site",
            NotificationClassifier.countsAgainstSender(true, NotificationClassifier.webHost("web:https://scam.example;7")))
    }
}
