package org.elderguard.vpn

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process

enum class VpnState { OFF, STARTING, RUNNING, PAUSED, NEEDS_CONSENT, REVOKED, ERROR, FAILED_OPEN }

/** What the service is doing right now (same process as the UI). Reset to OFF when the process restarts. */
object VpnRuntime {
    @Volatile var state = VpnState.OFF
        private set
    @Volatile var message: String? = null
        private set
    @Volatile var sinceMs = 0L
        private set
    @Volatile var upstreamLabel: String? = null
    @Volatile var upstreamFallback = false
    @Volatile var lastHealthMs = 0L
    @Volatile var engineError: String? = null
    /** Android's "always-on VPN" / "block connections without VPN" for this app (API 29+, read by the service). */
    @Volatile var alwaysOn: Boolean? = null
    @Volatile var lockdown: Boolean? = null

    fun set(s: VpnState, msg: String? = null) { state = s; message = msg; sinceMs = System.currentTimeMillis() }
}

/**
 * The real state of ad blocking, for the UI and Diagnostics. "Active" requires ALL of: the user turned it on, the
 * service says RUNNING, and Android actually has a VPN network owned by this app. The UI never shows "on" from the
 * setting alone.
 */
data class VpnStatus(
    val wanted: Boolean,
    val state: VpnState,
    val systemHasOurVpn: Boolean,
    val otherVpnActive: Boolean,
    val alwaysOn: Boolean?,
    val lockdown: Boolean?,
    val privateDnsStrict: String?,
    val message: String?,
) {
    val active get() = wanted && state == VpnState.RUNNING && systemHasOurVpn
}

object VpnController {
    /** Non-null = Android must show its "allow VPN connection?" dialog first (launch it for a result). */
    fun consentIntent(c: Context): Intent? = VpnService.prepare(c)

    fun start(c: Context) {
        VpnSettings(c).enabled = true
        runCatching { c.startForegroundService(Intent(c, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_START)) }
    }

    fun stop(c: Context) {
        VpnSettings(c).enabled = false
        if (VpnRuntime.state == VpnState.OFF || VpnRuntime.state == VpnState.FAILED_OPEN || VpnRuntime.state == VpnState.REVOKED) return
        runCatching { c.startForegroundService(Intent(c, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP)) }
    }

    /** Rebuild the tunnel so changed settings (DNS server, per-app list) take effect. No-op when it is off. */
    fun restart(c: Context) {
        if (!VpnSettings(c).enabled) return
        runCatching { c.startForegroundService(Intent(c, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_RESTART)) }
    }

    fun pause(c: Context, minutes: Long) {
        runCatching { c.startForegroundService(Intent(c, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_PAUSE).putExtra(AdBlockVpnService.EXTRA_MINUTES, minutes)) }
    }

    /** Called at boot / app start: bring the tunnel back if the user left it on and consent is still valid. */
    fun resumeIfWanted(c: Context) {
        if (!VpnSettings(c).enabled) return
        if (VpnRuntime.state == VpnState.RUNNING || VpnRuntime.state == VpnState.STARTING || VpnRuntime.state == VpnState.PAUSED) return
        if (VpnService.prepare(c) != null) { VpnRuntime.set(VpnState.NEEDS_CONSENT, "需要再同意一次"); return }
        runCatching { c.startForegroundService(Intent(c, AdBlockVpnService::class.java)) }
    }

    fun status(c: Context): VpnStatus {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        var ours = false
        var other = false
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            // getOwnerUid() is only revealed to the owner itself; any other VPN shows up as "not ours"
            if (Build.VERSION.SDK_INT >= 29 && caps.ownerUid == Process.myUid()) ours = true else other = true
        }
        if (Build.VERSION.SDK_INT < 29 && VpnRuntime.state == VpnState.RUNNING && other) { ours = true; other = false }
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val strict = if (Build.VERSION.SDK_INT >= 28 && lp?.isPrivateDnsActive == true) lp.privateDnsServerName else null
        return VpnStatus(
            wanted = VpnSettings(c).enabled,
            state = VpnRuntime.state,
            systemHasOurVpn = ours,
            otherVpnActive = other,
            alwaysOn = VpnRuntime.alwaysOn,
            lockdown = VpnRuntime.lockdown,
            privateDnsStrict = strict,
            message = VpnRuntime.message,
        )
    }
}
