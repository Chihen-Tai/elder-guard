package org.elderguard.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress
import org.elderguard.MainActivity
import org.elderguard.data.Store
import org.elderguard.filter.FilterRepository

/**
 * The ad-blocking VPN. It is a DNS-ONLY tunnel: the phone's DNS server is set to a fake address inside the tunnel
 * and ONLY that address is routed into it; every other packet goes straight to the real network. So:
 *  - blocked ad/tracker names get 0.0.0.0 before the ad loads;
 *  - web pages, video and downloads never pass through this app (no throughput cost, nothing to decrypt — there is
 *    no HTTPS interception, no certificate, no traffic inspection beyond the DNS question);
 *  - if this service dies, closing the tunnel gives the phone its normal DNS back.
 *
 * Android allows one VPN at a time: starting this one disconnects WireGuard / company VPNs, and vice versa
 * ([onRevoke]). The UI says so before the user turns it on.
 */
class AdBlockVpnService : VpnService() {
    private lateinit var ctl: HandlerThread
    private lateinit var h: Handler
    private lateinit var settings: VpnSettings
    private lateinit var stats: DnsStats
    private val filters by lazy { FilterRepository.get(this) }
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }

    private var tun: ParcelFileDescriptor? = null
    private var engine: TunnelEngine? = null
    private var startedMs = 0L
    private val restarts = ArrayList<Long>()
    @Volatile private var network: Network? = null
    @Volatile private var dnsServers: List<InetAddress> = emptyList()
    private var netCallbackOn = false

    override fun onCreate() {
        super.onCreate()
        settings = VpnSettings(this)
        stats = DnsStats.get(this)
        ctl = HandlerThread("vpn-ctl").apply { start() }
        h = Handler(ctl.looper)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always go foreground first: a stop/pause request also arrives through startForegroundService.
        goForeground(notification(getString(org.elderguard.R.string.vpn_starting), null))
        when (intent?.action) {
            ACTION_STOP -> h.post { stopVpn(VpnState.OFF, userStopped = true) }
            ACTION_PAUSE -> h.post { pause(intent.getLongExtra(EXTRA_MINUTES, 15)) }
            // settings changed (DNS server, per-app list): Android fixes these when the tunnel is built, so rebuild it
            ACTION_RESTART -> h.post { if (tun != null && engine?.alive == true) rebuild() else startVpn() }
            // ACTION_START, null (restart after the process was killed) and android.net.VpnService (always-on).
            // A pause survives a process restart; it never extends past the time the user chose.
            else -> h.post {
                val left = settings.pausedUntil - System.currentTimeMillis()
                if (intent?.action != ACTION_START && left > 0) pause((left + 59_999) / 60_000) else startVpn()
            }
        }
        return START_STICKY
    }

    // ------------------------------------------------------------ start / stop

    private fun startVpn() {
        h.removeCallbacks(resumeTask)
        settings.pausedUntil = 0
        if (prepare(this) != null) {
            // Consent is missing (another VPN app was chosen since, or the user revoked it): never fight for the slot.
            VpnRuntime.set(VpnState.NEEDS_CONSENT, "需要再同意一次")
            shutdown(); return
        }
        if (tun != null && engine?.alive == true) { VpnRuntime.set(VpnState.RUNNING); refreshNotification(); return }
        VpnRuntime.set(VpnState.STARTING)
        if (!filters.loaded) runCatching { filters.reload() }.onFailure { Store(this).diag("vpn_filters", fields = mapOf("err" to it.message)) }
        watchNetwork()
        var lastErr: String? = null
        for (attempt in 0 until 3) {
            val r = runCatching { establish() }
            if (r.isSuccess && r.getOrNull() != null) { lastErr = null; break }
            lastErr = r.exceptionOrNull()?.message ?: "establish() returned null"
            Thread.sleep(1000L shl attempt)
        }
        if (lastErr != null) {
            Store(this).diag("vpn_start_failed", fields = mapOf("err" to lastErr))
            VpnRuntime.set(VpnState.ERROR, "沒辦法開啟：$lastErr")
            shutdown(); return
        }
        startedMs = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 29) { VpnRuntime.alwaysOn = isAlwaysOn; VpnRuntime.lockdown = isLockdownEnabled }
        VpnRuntime.set(VpnState.RUNNING)
        Store(this).diag("vpn", fields = mapOf("state" to "running", "upstream" to settings.upstream.name, "mode" to settings.appMode.name,
            "rules" to filters.engine.blockRuleCount))
        refreshNotification()
        h.removeCallbacks(healthTask); h.postDelayed(healthTask, VpnHealthMonitor.CHECK_MS)
        h.removeCallbacks(flushTask); h.postDelayed(flushTask, DnsStats.FLUSH_MS)
    }

    /** Builds the TUN and starts the engine. Returns null when Android refuses (e.g. consent missing). */
    private fun establish(): ParcelFileDescriptor? {
        teardownTunnel()
        val plan = PerAppVpnPolicy.plan(settings.appMode, settings.bypass, settings.selected, packageName) { isInstalled(it) }
        if (plan.error != null) throw IllegalStateException(plan.error)
        val b = Builder()
            .setSession(getString(org.elderguard.R.string.vpn_session))
            .addAddress(TUN_V4, 32).addDnsServer(DNS_V4).addRoute(DNS_V4, 32)
            .addAddress(TUN_V6, 128).addDnsServer(DNS_V6).addRoute(DNS_V6, 128)
            .setMtu(1500)
            .setBlocking(false)
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (Build.VERSION.SDK_INT >= 29) {
            // Metered-ness follows Wi-Fi/mobile (VPNs default to "metered", which would pause Wi-Fi-only downloads).
            b.setMetered(false)
            network?.let { b.setUnderlyingNetworks(arrayOf(it)) }
        }
        for (p in plan.disallowed) runCatching { b.addDisallowedApplication(p) }
        for (p in plan.allowed) runCatching { b.addAllowedApplication(p) }
        // no allowBypass(): apps must not be able to opt out of the DNS filter
        val fd = b.establish() ?: return null
        tun = fd
        engine = TunnelEngine(fd, { filters.engine }, buildUpstream(), stats, ::ownerApp).also { it.start() }
        VpnRuntime.upstreamLabel = engine?.upstream?.label
        VpnRuntime.upstreamFallback = false
        return fd
    }

    private fun buildUpstream(): DnsUpstream {
        val choice = settings.upstream
        val networkDns = UdpUpstream({ dnsServers.ifEmpty { FALLBACK_DNS } }, { protect(it) }, label = "network")
        return when {
            choice.doh != null -> FailoverUpstream(DohUpstream(choice.doh), networkDns)
            choice.udp.isNotEmpty() -> FailoverUpstream(UdpUpstream({ choice.udp.map { InetAddress.getByName(it) } }, { protect(it) }, label = choice.name.lowercase()), networkDns)
            else -> networkDns
        }
    }

    private fun rebuild() {
        val ok = runCatching { establish() }.getOrNull() != null
        if (!ok) { failOpen("rebuild_failed"); return }
        startedMs = System.currentTimeMillis()
        restarts.clear()
        refreshNotification()
    }

    private fun isInstalled(p: String) = runCatching { packageManager.getApplicationInfo(p, 0); true }.getOrDefault(false)

    /** Which app sent a DNS question (only while DNS history is on). */
    private fun ownerApp(p: UdpPacket): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
            InetSocketAddress(InetAddress.getByAddress(p.src), p.srcPort), InetSocketAddress(InetAddress.getByAddress(p.dst), p.dstPort))
        if (uid < 0) return null
        return packageManager.getPackagesForUid(uid)?.firstOrNull() ?: packageManager.getNameForUid(uid)
    }

    private fun teardownTunnel() {
        engine?.stop(); engine = null
        runCatching { tun?.close() }; tun = null
    }

    private fun stopVpn(state: VpnState, userStopped: Boolean = false, message: String? = null) {
        if (userStopped) settings.enabled = false
        VpnRuntime.set(state, message)
        shutdown()
    }

    private fun shutdown() {
        h.removeCallbacks(healthTask); h.removeCallbacks(flushTask)
        stats.flush()
        teardownTunnel()
        unwatchNetwork()
        if (VpnRuntime.state == VpnState.RUNNING || VpnRuntime.state == VpnState.STARTING) VpnRuntime.set(VpnState.OFF)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val resumeTask = Runnable {
        val e = engine
        if (e != null && e.alive && e.passThrough) {
            e.passThrough = false; settings.pausedUntil = 0
            VpnRuntime.set(VpnState.RUNNING); refreshNotification()
            h.removeCallbacks(healthTask); h.postDelayed(healthTask, VpnHealthMonitor.CHECK_MS)
        } else startVpn()
    }

    private val lockdown get() = Build.VERSION.SDK_INT >= 29 && runCatching { isLockdownEnabled }.getOrDefault(false)

    private fun pause(minutes: Long) {
        val until = System.currentTimeMillis() + minutes * 60_000
        settings.pausedUntil = until
        h.removeCallbacks(healthTask)
        stats.flush()
        // With "block connections without VPN" on, removing the tunnel would cut the phone off: keep it and only stop
        // filtering. Otherwise remove it, so the phone uses its normal DNS.
        val e = engine
        if (lockdown && e != null && e.alive) e.passThrough = true else teardownTunnel()
        VpnRuntime.set(VpnState.PAUSED, until.toString())
        goForeground(notification(getString(org.elderguard.R.string.vpn_paused, java.text.SimpleDateFormat("a h:mm", java.util.Locale.TAIWAN).format(until)), pausedActions()))
        h.removeCallbacks(resumeTask)
        h.postDelayed(resumeTask, minutes * 60_000)
    }

    override fun onRevoke() {
        // Another VPN took the slot, or the user disconnected us in Android's VPN settings. Respect it.
        h.post {
            Store(this).diag("vpn", fields = mapOf("state" to "revoked"))
            stopVpn(VpnState.REVOKED, userStopped = true, message = "被其他 VPN 取代，或在系統設定中關閉")
        }
    }

    override fun onDestroy() {
        h.post { if (tun != null) { stats.flush(); teardownTunnel() }; unwatchNetwork() }
        ctl.quitSafely()
        if (VpnRuntime.state == VpnState.RUNNING || VpnRuntime.state == VpnState.STARTING) VpnRuntime.set(VpnState.OFF)
        super.onDestroy()
    }

    // ------------------------------------------------------------ network changes

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { h.post { onNetwork(n) } }
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { h.post { if (n == network) dnsServers = lp.dnsServers } }
        override fun onLost(n: Network) { h.post { if (n == network) { network = null; dnsServers = emptyList(); pickUnderlying()?.let { onNetwork(it) } } } }
    }

    /**
     * Follows the real (non-VPN) network the phone is using. NOT registerDefaultNetworkCallback: for the VPN's own
     * UID it reported our VPN itself (emulator, Android 16: UnderlyingNetworks=[our VPN], so DNS was forwarded back
     * into our own tunnel and every allowed name failed). Android 12+: the system's best NOT_VPN network;
     * older: every NOT_VPN network, choosing among them with [pickUnderlying].
     */
    private fun watchNetwork() {
        if (netCallbackOn) return
        val req = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) cm.registerBestMatchingNetworkCallback(req, netCallback, h)
            else cm.registerNetworkCallback(req, netCallback, h)
            netCallbackOn = true
        }
        pickUnderlying()?.let { network = it; dnsServers = cm.getLinkProperties(it)?.dnsServers ?: emptyList() }
    }

    /** The best non-VPN network: validated first, then Wi-Fi / Ethernet before mobile data. */
    private fun pickUnderlying(): Network? {
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .sortedWith(compareByDescending<Pair<Network, NetworkCapabilities>> { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                .thenByDescending { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.second.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) })
            .firstOrNull()?.first
    }

    private fun unwatchNetwork() {
        if (netCallbackOn) runCatching { cm.unregisterNetworkCallback(netCallback) }
        netCallbackOn = false
    }

    private fun onNetwork(n0: Network) {
        // never our own (or anyone's) VPN as the upstream path
        val n = if (cm.getNetworkCapabilities(n0)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) pickUnderlying() ?: return else n0
        if (Build.VERSION.SDK_INT < 31 && network != null && n != network && isBetter(network!!, n)) return
        val changed = n != network
        network = n
        dnsServers = cm.getLinkProperties(n)?.dnsServers ?: emptyList()
        if (Build.VERSION.SDK_INT >= 29) runCatching { setUnderlyingNetworks(arrayOf(n)) }
        if (changed) Store(this).diag("vpn_net", fields = mapOf("dns" to dnsServers.size))
        // Wi-Fi <-> mobile: the tunnel's routes do not depend on the network, so it stays up; only the upstream
        // server list changed (read live). Failure counters restart on a new network.
        if (changed) { engine?.upstreamFailuresInRow?.set(0); restarts.clear() }
    }

    /** Pre-Android-12 only: several NOT_VPN networks report onAvailable; keep the better one (not "last wins"). */
    private fun isBetter(current: Network, candidate: Network): Boolean = pickUnderlying() == current && candidate != current

    private fun networkValidated(): Boolean {
        val n = network ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    // ------------------------------------------------------------ health

    private val healthTask = object : Runnable {
        override fun run() {
            val e = engine
            val snap = VpnHealthMonitor.Snapshot(
                now = System.currentTimeMillis(),
                engineAlive = e != null && e.alive && e.threadAlive,
                tunValid = tun?.fileDescriptor?.valid() == true,
                filterLoaded = filters.loaded,
                upstreamFailuresInRow = e?.upstreamFailuresInRow?.get() ?: 0,
                lastUpstreamOkMs = e?.lastUpstreamOkMs ?: 0,
                startedMs = startedMs,
                networkValidated = networkValidated(),
                recentRestarts = restarts.toList(),
            )
            val v = VpnHealthMonitor.judge(snap)
            VpnRuntime.upstreamLabel = e?.upstream?.label
            VpnRuntime.upstreamFallback = (e?.upstream as? FailoverUpstream)?.fallingBack == true
            VpnRuntime.lastHealthMs = snap.now
            VpnRuntime.engineError = e?.lastError
            when (v.action) {
                VpnHealthMonitor.Action.NONE -> {}
                VpnHealthMonitor.Action.RELOAD_FILTERS -> runCatching { filters.reload() }
                VpnHealthMonitor.Action.RESTART -> {
                    Store(this@AdBlockVpnService).diag("vpn_restart", fields = mapOf("why" to v.problem, "err" to e?.lastError))
                    restarts += snap.now
                    if (runCatching { establish() }.getOrNull() == null) { failOpen(v.problem); return }
                }
                VpnHealthMonitor.Action.FAIL_OPEN -> { failOpen(v.problem); return }
            }
            refreshNotification()
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            h.postDelayed(this, if (interactive) VpnHealthMonitor.CHECK_MS else VpnHealthMonitor.CHECK_IDLE_MS)
        }
    }

    private val flushTask = object : Runnable {
        override fun run() { stats.flush(); h.postDelayed(this, DnsStats.FLUSH_MS) }
    }

    /** Turn the tunnel off so the phone uses its normal DNS, and tell the user plainly. Settings stay "on". */
    private val lockdownRetry = Runnable { if (settings.enabled && VpnRuntime.state == VpnState.FAILED_OPEN) startVpn() }

    private fun failOpen(why: String?) {
        Store(this).diag("vpn_fail_open", fields = mapOf("why" to why, "lockdown" to lockdown))
        restarts.clear()
        VpnRuntime.set(VpnState.FAILED_OPEN, why)
        val nm = getSystemService(NotificationManager::class.java)
        val retry = PendingIntent.getForegroundService(this, 3, Intent(this, AdBlockVpnService::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF_FAILED, Notification.Builder(this, CH_VPN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(org.elderguard.R.string.vpn_failed_title))
            .setContentText(getString(org.elderguard.R.string.vpn_failed_text))
            .setContentIntent(openApp())
            .addAction(Notification.Action.Builder(null, getString(org.elderguard.R.string.vpn_retry), retry).build())
            .setAutoCancel(true).build())
        if (lockdown) {
            // No VPN = no internet at all under lockdown, so "failing open" cannot help: keep retrying every minute.
            teardownTunnel(); h.removeCallbacks(healthTask)
            h.removeCallbacks(lockdownRetry); h.postDelayed(lockdownRetry, 60_000)
            return
        }
        // Fully stopped (not left half-alive): a sticky restart must not bring a broken tunnel back in a loop.
        // The user retries from the notification or the app; a reboot also retries.
        shutdown()
    }

    // ------------------------------------------------------------ notification

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun runningActions() = listOf(
        Notification.Action.Builder(null, getString(org.elderguard.R.string.vpn_pause_15), PendingIntent.getForegroundService(this, 1,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_PAUSE).putExtra(EXTRA_MINUTES, 15L), PendingIntent.FLAG_IMMUTABLE)).build(),
        Notification.Action.Builder(null, getString(org.elderguard.R.string.vpn_open_app), openApp()).build(),
    )

    private fun pausedActions() = listOf(
        Notification.Action.Builder(null, getString(org.elderguard.R.string.vpn_resume), PendingIntent.getForegroundService(this, 2,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE)).build(),
    )

    private fun notification(title: String, actions: List<Notification.Action>?): Notification {
        val b = Notification.Builder(this, CH_VPN)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        actions?.forEach { b.addAction(it) }
        return b.build()
    }

    private fun refreshNotification() {
        if (VpnRuntime.state != VpnState.RUNNING) return
        val n = notification(getString(org.elderguard.R.string.vpn_running_title), runningActions()).let {
            Notification.Builder.recoverBuilder(this, it).setContentText(getString(org.elderguard.R.string.vpn_running_text, stats.blockedToday())).build()
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    companion object {
        const val ACTION_START = "org.elderguard.vpn.START"
        const val ACTION_STOP = "org.elderguard.vpn.STOP"
        const val ACTION_PAUSE = "org.elderguard.vpn.PAUSE"
        const val ACTION_RESTART = "org.elderguard.vpn.RESTART"
        const val EXTRA_MINUTES = "minutes"
        const val CH_VPN = "vpn_status"
        const val NOTIF_ID = 7
        const val NOTIF_FAILED = 8

        /** Addresses from the documentation ranges (RFC 5737 / RFC 4193), never used by real networks. */
        const val TUN_V4 = "192.0.2.2"
        const val DNS_V4 = "192.0.2.53"
        const val TUN_V6 = "fd00:e1de:9a4d::2"
        const val DNS_V6 = "fd00:e1de:9a4d::53"

        /** Only used when the network reports no DNS server at all. */
        val FALLBACK_DNS: List<InetAddress> by lazy { listOf("1.1.1.1", "8.8.8.8").map { InetAddress.getByName(it) } }

        fun ensureChannel(c: Context) {
            c.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH_VPN, c.getString(org.elderguard.R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }
}
