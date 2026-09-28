package org.elderguard.vpn

import android.content.Context

/** Upstream DNS choices shown in VPN settings. AUTO = the DNS server of the Wi-Fi / mobile network in use. */
enum class UpstreamChoice(val label: String, val udp: List<String>, val doh: String?) {
    AUTO("自動（使用目前網路的 DNS）", emptyList(), null),
    CLOUDFLARE("Cloudflare 1.1.1.1", listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"), null),
    GOOGLE("Google 8.8.8.8", listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"), null),
    QUAD9("Quad9 9.9.9.9", listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe"), null),
    DOH_CLOUDFLARE("Cloudflare（加密 DoH）", emptyList(), "https://cloudflare-dns.com/dns-query"),
    DOH_GOOGLE("Google（加密 DoH）", emptyList(), "https://dns.google/dns-query"),
    DOH_QUAD9("Quad9（加密 DoH）", emptyList(), "https://dns.quad9.net/dns-query"),
}

/**
 * Per-app routing. ALL = every app except [VpnSettings.bypass] (and the guard itself); SELECTED = only
 * [VpnSettings.selected]. Android offers exactly these two (addDisallowedApplication / addAllowedApplication, never
 * both), and the choice only takes effect when the tunnel is (re)built.
 */
enum class AppMode { ALL, SELECTED }

class VpnSettings(c: Context) {
    private val p = c.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    /** The user wants ad blocking on (survives reboot; the boot receiver reads it). */
    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    var upstream: UpstreamChoice
        get() = runCatching { UpstreamChoice.valueOf(p.getString("upstream", "AUTO")!!) }.getOrDefault(UpstreamChoice.AUTO)
        set(v) = p.edit().putString("upstream", v.name).apply()

    var appMode: AppMode
        get() = runCatching { AppMode.valueOf(p.getString("appMode", "ALL")!!) }.getOrDefault(AppMode.ALL)
        set(v) = p.edit().putString("appMode", v.name).apply()

    var bypass: Set<String>
        get() = p.getStringSet("bypass", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("bypass", v).apply()

    var selected: Set<String>
        get() = p.getStringSet("selected", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("selected", v).apply()

    /** Download newer filter lists from the internet (off by default: the bundled list works offline). */
    var remoteUpdates: Boolean
        get() = p.getBoolean("remoteUpdates", false)
        set(v) = p.edit().putBoolean("remoteUpdates", v).apply()

    var updateOnWifiOnly: Boolean
        get() = p.getBoolean("wifiOnly", true)
        set(v) = p.edit().putBoolean("wifiOnly", v).apply()

    var updateIntervalHours: Int
        get() = p.getInt("intervalH", 24)
        set(v) = p.edit().putInt("intervalH", v.coerceIn(6, 168)).apply()

    /** Wall-clock end of a "pause"; 0 = not paused. */
    var pausedUntil: Long
        get() = p.getLong("pausedUntil", 0)
        set(v) = p.edit().putLong("pausedUntil", v).apply()
}

object PerAppVpnPolicy {
    data class Plan(val allowed: List<String>, val disallowed: List<String>, val error: String?)

    /**
     * What to pass to the Builder. The guard is always outside its own tunnel (its DoH and list downloads must not
     * loop through itself). SELECTED with nothing selected is refused: an empty allow-list means "all apps" to Android.
     */
    fun plan(mode: AppMode, bypass: Set<String>, selected: Set<String>, self: String, installed: (String) -> Boolean): Plan = when (mode) {
        AppMode.ALL -> Plan(emptyList(), (bypass.filter(installed) + self).distinct().sorted(), null)
        AppMode.SELECTED -> {
            val a = selected.filter { it != self && installed(it) }.sorted()
            if (a.isEmpty()) Plan(emptyList(), emptyList(), "還沒有選擇要保護的 App") else Plan(a, emptyList(), null)
        }
    }
}
