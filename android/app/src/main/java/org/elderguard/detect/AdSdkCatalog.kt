package org.elderguard.detect

/**
 * Ad-network signatures shared by the static scan (dex type descriptors) and the runtime monitor
 * (full-screen ad activity class names). Mirrors engine/rules.json (EGDA v0.4.1) "ad_sdks".
 */
object AdSdkCatalog {
    /** network -> dex type-descriptor prefixes. */
    val dexPrefixes: Map<String, List<String>> = mapOf(
        "AdMob" to listOf("Lcom/google/android/gms/ads/", "Lcom/google/android/libraries/ads/mobile/sdk/"), // + next-gen GMA SDK (missed on 2026-09-25)
        "AppLovin" to listOf("Lcom/applovin/"),
        "UnityAds" to listOf("Lcom/unity3d/ads/", "Lcom/unity3d/services/ads/"),
        "ironSource" to listOf("Lcom/ironsource/"),
        "Liftoff" to listOf("Lcom/vungle/"),
        "Mintegral" to listOf("Lcom/mbridge/msdk/"),
        "Pangle" to listOf("Lcom/bytedance/sdk/openadsdk/", "Lcom/bytedance/sdk/pangle/"),
        "InMobi" to listOf("Lcom/inmobi/"),
        "Chartboost" to listOf("Lcom/chartboost/"),
        "DTExchange" to listOf("Lcom/fyber/", "Lcom/digitalturbine/"),
        "MetaAN" to listOf("Lcom/facebook/ads/"),
        "Moloco" to listOf("Lcom/moloco/"),
        "Bigo" to listOf("Lsg/bigo/ads/"),
        "Yandex" to listOf("Lcom/yandex/mobile/ads/"),
        "PubMatic" to listOf("Lcom/pubmatic/"),
        "AmazonAds" to listOf("Lcom/amazon/device/ads/"),
        "Smaato" to listOf("Lcom/smaato/"),
        "TradPlus" to listOf("Lcom/tradplus/", "Lcom/tp/adx/"),
        "TopOn" to listOf("Lcom/anythink/", "Lcom/thinkup/"),
        "GDT" to listOf("Lcom/qq/e/ads/"),
        "Kuaishou" to listOf("Lcom/kwad/sdk/"),
        "BaiduAds" to listOf("Lcom/baidu/mobads/", "Lcc/admaster/android/proxy/"),
        "Sigmob" to listOf("Lcom/sigmob/"),
        "HuaweiAds" to listOf("Lcom/huawei/hms/ads/"),
        "MiAds" to listOf("Lcom/miui/zeus/"),
        "OppoAds" to listOf("Lcom/heytap/msp/mobad/"),
        "VivoAds" to listOf("Lcom/vivo/mobilead/"),
    )

    /**
     * Class-name prefixes of ad SDK activities that show full-screen ads. When one of these is brought to the top
     * while the user was in a *different* app, the ad was shown outside its own app (the 2026-09-25 incident:
     * com.bytedance.sdk.openadsdk.activity.TTFullWebActivity over the launcher, Play Store and Settings).
     */
    val adActivityPrefixes: List<String> = listOf(
        "com.bytedance.sdk.openadsdk.", "com.bytedance.sdk.pangle.",
        "com.applovin.adview.", "com.applovin.impl.adview.", "com.applovin.mediation.",
        "com.google.android.gms.ads.", "com.google.android.libraries.ads.mobile.sdk.",
        "com.facebook.ads.",
        "com.mbridge.msdk.",
        "com.vungle.",
        "com.unity3d.ads.", "com.unity3d.services.ads.",
        "com.ironsource.",
        "com.inmobi.ads.",
        "sg.bigo.ads.",
        "com.moloco.sdk.",
        "com.chartboost.",
        "com.fyber.", "com.digitalturbine.",
        "com.yandex.mobile.ads.",
        "com.tp.adx.", "com.tradplus.",
        "com.anythink.", "com.thinkup.",
        "com.kwad.sdk.", "com.qq.e.ads.", "com.baidu.mobads.", "cc.admaster.",
        "com.sigmob.", "com.pubmatic.", "com.smaato.", "com.amazon.device.ads.",
    )

    fun isAdActivity(className: String?): Boolean =
        className != null && adActivityPrefixes.any { className.startsWith(it) }
}
