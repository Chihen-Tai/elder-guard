package org.elderguard.redirect

/**
 * Knows which windows are the system or the user acting, so the guard never blocks them.
 *
 * The sets are built on the phone from roles and intent handlers (RedirectGuard.buildClassifier: HOME, dialer, SMS,
 * camera, documents, installer, settings, IMEs, share sheet, WebView provider...) plus the fixed AOSP/GMS names
 * below — not from one brand's package list.
 */
class SystemFlowClassifier(
    /** Apps declaring HOME. Only system ones and [defaultHome] are trusted (see [launchers]). */
    launchers: Set<String>,
    /** ENABLED keyboards: only their input window is transparent, never their activities (see [isActivity]). */
    val imes: Set<String>,
    /** Handlers of system flows (dial, camera, pickers, settings, installer...). Only system ones are trusted. */
    systemTargets: Set<String>,
    /** Share sheet / "open with" chooser packages. */
    choosers: Set<String> = DEFAULT_CHOOSERS,
    val self: String,
    /** Browsers (resolved from "open https://" handlers). */
    val browsers: Set<String> = emptySet(),
    val appStores: Set<String> = KNOWN_STORES,
    val extraShopping: Set<String> = emptySet(),
    val extraFinance: Set<String> = emptySet(),
    /**
     * AOSP System UI plus OEM System UI plugins that are PREINSTALLED system apps (built from package flags on the
     * phone). Never by name alone: a third-party app named "com.cleaner.systemui.booster" would otherwise become
     * invisible and its jumps would look like "opened from the home screen" (security review, attack a1).
     */
    val systemUis: Set<String> = setOf(SYSTEM_UI),
    /**
     * Whether (pkg, cls) is one of the app's ACTIVITIES. Only a keyboard's own input window (not an Activity, e.g.
     * android.inputmethodservice.SoftInputWindow) is transparent; the IME package's activities (theme store,
     * settings) are tracked like any app (review B5/b7). The phone passes a PackageManager lookup. Required (review B8).
     */
    val isActivity: (String, String?) -> Boolean,
    /**
     * THE trust test (review B1/B5): is this a preinstalled system app? The phone passes the real FLAG_SYSTEM check;
     * anything an app merely DECLARES (a HOME filter, a camera / picker / installer filter, a name) earns nothing.
     * Required, with no default (review B8): a name-based default would be the B1 mistake waiting for the next caller.
     * Unit tests pass theirs through a test-only factory.
     */
    val isSystemApp: (String) -> Boolean,
    /** The home screen the user actually uses (a default the user chose; trusted even if not a system app). */
    val defaultHome: String? = null,
    /** Default phone / SMS apps the user chose: always reachable as targets, but their own switches are judged. */
    val userDefaults: Set<String> = emptySet(),
) {
    /** Trusted home screens: system ones and the user's default. A theme launcher that is not the default is an app. */
    val launchers: Set<String> = launchers.filter { isSystemApp(it) || it == defaultHome }.toSet()
    /** Trusted system-flow targets: system apps, plus the user's chosen default phone / SMS apps. */
    val systemTargets: Set<String> = systemTargets.filter { isSystemApp(it) }.toSet() + userDefaults
    /** Packages whose OWN switches are never judged: system apps only. */
    private val systemSources: Set<String> = systemTargets.filter { isSystemApp(it) }.toSet()
    val choosers: Set<String> = choosers.filter { it == "android" || isSystemApp(it) }.toSet()

    enum class Hop { LAUNCHER, RECENTS, CHOOSER, SHADE }

    /** Windows that do not change "which app the user is in" (keyboards, our own prompt, System UI overlays). */
    fun isTransparent(pkg: String, cls: String?): Boolean =
        (pkg in imes && !isActivity(pkg, cls)) || pkg == self || (isSystemUi(pkg) && hopOf(pkg, cls) == null)

    /** AOSP System UI and preinstalled OEM System UI plugins (vivo field test: com.vivo.systemuiplugin). */
    fun isSystemUi(pkg: String) = pkg in systemUis

    /** When the user passes through one of these, the next app was chosen by the user. */
    fun hopOf(pkg: String, cls: String?): Hop? = when {
        pkg in launchers -> if (cls?.contains("recents", ignoreCase = true) == true) Hop.RECENTS else Hop.LAUNCHER
        isSystemUi(pkg) && cls?.contains("recents", ignoreCase = true) == true -> Hop.RECENTS
        // By class name only inside a SYSTEM app (OEM resolvers): any app can name its own page "…ChooserActivity"
        // and would then pass as the share sheet (review B7).
        pkg in choosers || (cls != null && CHOOSER_CLASSES.any { cls.endsWith(it) } && isSystemApp(pkg)) -> Hop.CHOOSER
        else -> null
    }

    /** Opening one of these is never an ad redirect (camera for a photo, file picker, permission dialog, dialer...). */
    fun isSystemTarget(pkg: String): Boolean = pkg in systemTargets || pkg in launchers || pkg in choosers || pkg == "android" || isSystemUi(pkg)

    /** A system app whose own switches are not judged (never a user-installed app, whatever it declares). */
    fun isSystemSource(pkg: String): Boolean = pkg in systemSources || pkg in launchers || pkg == "android" || isSystemUi(pkg)

    fun kind(pkg: String): TargetKind = when {
        pkg in appStores -> TargetKind.APP_STORE
        isFinance(pkg) -> TargetKind.FINANCE
        isShopping(pkg) -> TargetKind.SHOPPING
        pkg in browsers -> TargetKind.BROWSER
        else -> TargetKind.OTHER
    }

    fun isFinance(pkg: String): Boolean = pkg in extraFinance || looksFinance(pkg)

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
        /**
         * Bank / payment / OTP by name. Attacker-controllable on purpose-accepted terms (review B8): it only turns a
         * BLOCK into an ASK (never an ALLOW), and it protects real banking apps from a wrong block.
         */
        fun looksFinance(pkg: String): Boolean = pkg in KNOWN_FINANCE ||
            FINANCE_WORDS.any { w -> pkg.split('.', '_').any { it == w || it.endsWith(w) && w.length >= 4 } }

        private val FINANCE_WORDS = listOf("bank", "pay", "wallet", "securities", "otp", "authenticator", "banking", "ebank", "mbank", "creditcard", "card")
    }
}
