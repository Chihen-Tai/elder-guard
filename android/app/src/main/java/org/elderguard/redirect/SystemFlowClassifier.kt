package org.elderguard.redirect

/**
 * Knows which windows are the system or the user acting, so the guard never blocks them.
 *
 * The sets are built on the phone from roles and intent handlers (RedirectGuard.buildClassifier: HOME, dialer, SMS,
 * camera, documents, installer, settings, IMEs, share sheet, WebView provider...) plus the fixed AOSP/GMS names
 * below — not from one brand's package list.
 */
class SystemFlowClassifier(
    /** Default and other installed home screens. */
    val launchers: Set<String>,
    /** Keyboards: their windows never mean "the user switched app". */
    val imes: Set<String>,
    /** Everything a user must always be able to reach (dialer, emergency, settings, permission and install dialogs...). */
    val systemTargets: Set<String>,
    /** Share sheet / "open with" chooser packages. */
    val choosers: Set<String> = DEFAULT_CHOOSERS,
    val self: String,
    /** Browsers (resolved from "open https://" handlers). */
    val browsers: Set<String> = emptySet(),
    val appStores: Set<String> = KNOWN_STORES,
    val extraShopping: Set<String> = emptySet(),
    val extraFinance: Set<String> = emptySet(),
) {
    enum class Hop { LAUNCHER, RECENTS, CHOOSER, SHADE }

    /** Windows that do not change "which app the user is in" (keyboards, our own prompt, System UI overlays). */
    fun isTransparent(pkg: String, cls: String?): Boolean =
        pkg in imes || pkg == self || (pkg == SYSTEM_UI && hopOf(pkg, cls) == null)

    /** When the user passes through one of these, the next app was chosen by the user. */
    fun hopOf(pkg: String, cls: String?): Hop? = when {
        pkg in launchers -> if (cls?.contains("recents", ignoreCase = true) == true) Hop.RECENTS else Hop.LAUNCHER
        pkg == SYSTEM_UI && cls?.contains("recents", ignoreCase = true) == true -> Hop.RECENTS
        pkg in choosers || cls?.let { c -> CHOOSER_CLASSES.any { c.endsWith(it) } } == true -> Hop.CHOOSER
        else -> null
    }

    /** Opening one of these is never an ad redirect (camera for a photo, file picker, permission dialog, dialer...). */
    fun isSystemTarget(pkg: String): Boolean = pkg in systemTargets || pkg in launchers || pkg in choosers || pkg == "android" || pkg == SYSTEM_UI

    fun kind(pkg: String): TargetKind = when {
        pkg in appStores -> TargetKind.APP_STORE
        isFinance(pkg) -> TargetKind.FINANCE
        isShopping(pkg) -> TargetKind.SHOPPING
        pkg in browsers -> TargetKind.BROWSER
        else -> TargetKind.OTHER
    }

    fun isFinance(pkg: String): Boolean = pkg in extraFinance || pkg in KNOWN_FINANCE || FINANCE_WORDS.any { w -> pkg.split('.', '_').any { it == w || it.endsWith(w) && w.length >= 4 } }

    fun isShopping(pkg: String): Boolean = pkg in extraShopping || KNOWN_SHOPPING.any { pkg == it || pkg.startsWith("$it.") } ||
        SHOPPING_WORDS.any { w -> pkg.split('.', '_').any { it == w || it.startsWith(w) } }

    companion object {
        const val SYSTEM_UI = "com.android.systemui"
        val DEFAULT_CHOOSERS = setOf("android", "com.android.intentresolver", "com.google.android.intentresolver")
        val CHOOSER_CLASSES = listOf("ChooserActivity", "ResolverActivity", "ChooserActivityLauncher")

        /** Fixed AOSP / GMS components that are part of normal flows on every brand. */
        val CORE_SYSTEM = setOf(
            "android", SYSTEM_UI, "com.android.settings", "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.google.android.dialer", "com.android.dialer", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.documentsui", "com.google.android.documentsui",
            "com.android.providers.media", "com.android.providers.media.module", "com.google.android.providers.media.module",
            "com.android.photopicker", "com.google.android.photopicker", "com.android.credentialmanager", "com.google.android.gms",
            "com.google.android.gsf", "com.android.camera", "com.android.camera2", "com.android.bluetooth", "com.android.nfc",
            "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver", "com.android.stk",
            "com.android.managedprovisioning", "com.android.vpndialogs", "com.android.printspooler", "com.android.shell",
        )

        val KNOWN_STORES = setOf(
            "com.android.vending", "com.heytap.market", "com.oppo.market", "com.vivo.appstore", "com.bbk.appstore",
            "com.xiaomi.market", "com.xiaomi.mipicks", "com.huawei.appmarket", "com.hihonor.appmarket",
            "com.sec.android.app.samsungapps", "com.tencent.android.qqdownloader", "com.qihoo.appstore", "com.baidu.appsearch",
            "com.wandoujia.phoenix2", "com.meizu.mstore", "com.lenovo.leos.appstore", "com.amazon.venezia", "com.apkpure.aegon",
            "com.oplus.market", "com.transsion.phoenix", "com.sec.android.app.samsungapps",
        )

        val KNOWN_SHOPPING = setOf(
            "com.shopee", "com.taobao.taobao", "com.taobao.litetao", "com.tmall.wireless", "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo", "com.lazada.android", "com.momo.mobile.shoppingv2", "com.pchome", "com.amazon.mShop.android.shopping",
            "com.alibaba.aliexpresshd", "com.einnovation.temu", "com.coupang.mobile", "com.ruten", "com.yahoo.mobile.client.android.ecauction",
            "com.yahoo.mobile.client.android.ecshopping", "com.shein.android", "com.zzkko", "com.ss.android.ugc.livelite",
            "com.jd.jdlite", "com.dewu.poizon", "com.kaola", "com.vipshop", "com.achievo.vipshop", "com.suning.mobile.ebuy",
        )
        private val SHOPPING_WORDS = listOf("shop", "mall", "taobao", "tmall", "ecshopping", "buy")

        val KNOWN_FINANCE = setOf("com.eg.android.AlipayGphone", "com.unionpay", "com.jkos.app", "tw.com.pxmart", "com.google.android.apps.walletnfcrel")
        private val FINANCE_WORDS = listOf("bank", "pay", "wallet", "securities", "otp", "authenticator", "banking", "ebank", "mbank", "creditcard", "card")
    }
}
