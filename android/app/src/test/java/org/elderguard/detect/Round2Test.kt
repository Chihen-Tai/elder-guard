package org.elderguard.detect

import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gaps found on the vivo test phone on 2026-09-25 (second batch of Play apps). */
class Round2Test {
    @Test fun joinedLabel_isSplit() {
        assertEquals("看文件", StaticRisk.baitCategory("PDFReader", "com.daily.plan.list.todo"))
        assertEquals("清理、加速", StaticRisk.baitCategory("CleanNow", "com.junkfile.clean.tool"))
    }

    @Test fun nameMismatch() {
        assertTrue(StaticRisk.nameMismatch("PDFReader", "com.daily.plan.list.todo"))
        assertFalse(StaticRisk.nameMismatch("PDF Reader - Document Viewer", "com.pdfviewer.imagetopdf.document.office"))
        assertFalse(StaticRisk.nameMismatch("LINE", "jp.naver.line.android"))
        assertFalse(StaticRisk.nameMismatch("Gmail", "com.google.android.gm"))
        assertFalse(StaticRisk.nameMismatch("Threads", "com.instagram.barcelona"))
        assertFalse(StaticRisk.nameMismatch("QR和条码扫描器", "com.gamma.scan"))
        assertFalse(StaticRisk.nameMismatch("Adobe Acrobat", "com.adobe.reader"))
    }

    @Test fun nextGenGoogleAdsActivity_isAnAdPage() {
        assertTrue(AdSdkCatalog.isAdActivity("com.google.android.libraries.ads.mobile.sdk.common.AdActivity"))
    }

    private fun obf(): Obfuscation {
        val assets = File("src/main/assets")
        val words = GZIPInputStream(File(assets, "words.txt.gz").inputStream()).bufferedReader().readLines().toHashSet()
        val j = JSONObject(File(assets, "obf_rules.json").readText())
        fun list(k: String) = j.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        return Obfuscation(words, list("pinyin").toHashSet(), list("lib_prefixes"), list("suffixes"))
    }

    @Test fun obfuscation_realNames() {
        val o = obf()
        // PDF Photos (com.qysga.pdfphoto.hrmn) — flagged by the desktop engine, missed on the phone before this change
        val qysga = listOf("GuideActivity", "MainActivity", "FootServi", "KeeperWhiteServi", "PinkServi", "YellowVery", "NoticeStaticReceiver")
            .map { "com.qysga.pdfphoto.hrmn.hots.$it" }
        assertNotNull(o.examples(qysga))
        // All Document Reader (removed earlier): scrambled package path + class names
        val paper = listOf("stabwomb.malatio.WatexoduActivity", "EstujostlActivity", "coopilabl.GratausActivity", "spothalt.TreatditActivity",
            "com.cherry.lib.doc.DocViewerActivity").map { if (it.startsWith("com.")) it else "famifou.rigtran.belttag.homescis.geogtiv.$it" }
        assertNotNull(o.examples(paper))
        // normal apps must not be flagged
        val line = listOf("jp.naver.line.android.activity.SplashActivity", "jp.naver.line.android.activity.main.MainActivity",
            "jp.naver.line.android.activity.chathistory.ChatHistoryActivity", "jp.naver.line.android.service.NotificationService",
            "jp.naver.line.android.NavigationServiceAsyncActivity", "jp.naver.line.android.design_botanic_pink")
        assertNull(o.examples(line))
        val shopee = listOf("com.shopee.app.ui.home.HomeActivity_", "com.shopee.app.ui.auth.login.LoginActivity_",
            "com.shopee.app.ui.setting.SettingActivity_", "com.shopee.app.pushnotification.NotificationService")
        assertNull(o.examples(shopee))
    }

    @Test fun todoApp_nowMedium() {
        val f = AppFacts("com.daily.plan.list.todo", " PDFReader", false, "com.android.vending", 0, false, false,
            (1..16).map { "N$it" }, listOf("BOOT_COMPLETED", "USER_PRESENT"), true, false, true, StaticRisk.baitCategory(" PDFReader", "com.daily.plan.list.todo"), true)
        val v = StaticRisk.assess(f)
        assertEquals(RiskLevel.MEDIUM, v.level)
        assertTrue(v.reasons.any { it.contains("對不上") })
    }
}
