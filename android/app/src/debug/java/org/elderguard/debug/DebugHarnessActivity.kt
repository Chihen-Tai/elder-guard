package org.elderguard.debug

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.InetAddress
import org.elderguard.filter.FilterRepository
import org.elderguard.redirect.ProtectedAppManager
import org.elderguard.redirect.RedirectGuard
import org.elderguard.redirect.RedirectHistoryRepository
import org.elderguard.vpn.AdBlockVpnService
import org.elderguard.vpn.Dns
import org.elderguard.vpn.DnsQuestion
import org.elderguard.vpn.DnsStats
import org.elderguard.vpn.UdpUpstream
import org.elderguard.vpn.DohUpstream
import org.elderguard.vpn.VpnController
import org.elderguard.vpn.VpnRuntime

/**
 * DEBUG ONLY test bench. Every result is also written to logcat (tag EG-HARNESS) so an adb script can read it.
 * Note: the guard is excluded from its own VPN, so the DNS tests here check the filter and the upstream directly;
 * end-to-end "through the tunnel" checks run from adb shell (the shell user IS routed through the VPN).
 */
class DebugHarnessActivity : Activity() {
    private lateinit var out: TextView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        out = TextView(this).apply { textSize = 14f }
        fun btn(t: String, f: () -> Unit) = l.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        btn("VPN: start") { cmd("vpn_start") }
        btn("VPN: stop") { cmd("vpn_stop") }
        btn("VPN: status") { cmd("vpn_status") }
        btn("Test DNS Block (doubleclick.net)") { cmd("dns_block") }
        btn("Test DNS Allow (example.com)") { cmd("dns_allow") }
        btn("Test upstream IPv4 / IPv6 / DoH") { cmd("upstream") }
        btn("Test filter rule (user block example.org)") { cmd("rule_test") }
        btn("Open Chrome") { view("https://example.com/") }
        btn("Open YouTube") { pkg("com.google.android.youtube") }
        btn("Open Bilibili") { view("bilibili://video/1") }
        btn("Open Play Store") { view("market://details?id=org.elderguard") }
        btn("Open Shopee") { pkg("com.shopee.tw") }
        btn("Open Maps") { view("geo:25.0330,121.5654") }
        btn("Open HTTPS URL") { view("https://www.gov.tw/") }
        btn("Open Custom Scheme (egshop://)") { view("egshop://item/1") }
        btn("Delayed Launch 2s (shop)") { h.postDelayed({ pkg("org.elderguard.testing.shop") }, 2000) }
        btn("Repeated Launch x3 (shop)") { for (i in 0..2) h.postDelayed({ pkg("org.elderguard.testing.shop") }, 2000L * i) }
        btn("Background Launch Attempt (5s after leaving)") { moveTaskToBack(true); h.postDelayed({ pkg("org.elderguard.testing.shop") }, 5000) }
        btn("Redirect history (last 20)") { cmd("history") }
        l.addView(out)
        setContentView(ScrollView(this).apply { addView(l) })
        intent?.let { handle(it) }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    private fun handle(i: Intent) {
        i.getStringExtra("cmd")?.let { cmd(it, i.getStringExtra("arg")) }
    }

    private fun say(s: String) { Log.i("EG-HARNESS", s); runOnUiThread { out.append(s + "\n") } }

    private fun cmd(c: String, arg: String? = null) {
        when (c) {
            "vpn_start" -> {
                val consent = VpnController.consentIntent(this)
                if (consent != null) { say("vpn: asking consent"); startActivityForResult(consent, 1) } else { VpnController.start(this); say("vpn: start sent") }
            }
            "vpn_stop" -> { VpnController.stop(this); say("vpn: stop sent") }
            "vpn_pause" -> { VpnController.pause(this, arg?.toLongOrNull() ?: 1); say("vpn: pause sent") }
            "vpn_status" -> { val s = VpnController.status(this); say("vpn: state=${s.state} active=${s.active} ours=${s.systemHasOurVpn} other=${s.otherVpnActive} " +
                "privateDns=${s.privateDnsStrict} upstream=${VpnRuntime.upstreamLabel} fallback=${VpnRuntime.upstreamFallback} blockedToday=${DnsStats.get(this).blockedToday()} " +
                "queries=${DnsStats.get(this).queries.get()} rules=${FilterRepository.get(this).engine.blockRuleCount} msg=${s.message} err=${VpnRuntime.engineError}") }
            "dns_block", "dns_allow" -> Thread {
                val repo = FilterRepository.get(this); if (!repo.loaded) repo.reload()
                val name = arg ?: if (c == "dns_block") "doubleclick.net" else "example.com"
                say("filter: $name -> ${repo.engine.match(name)}")
            }.start()
            "rule_test" -> Thread {
                val repo = FilterRepository.get(this)
                repo.addUserRule("example.org", block = true); repo.addUserRule("ok.example.org", block = false)
                say("rule: example.org=${repo.engine.match("example.org")} www.example.org=${repo.engine.match("www.example.org")} ok.example.org=${repo.engine.match("ok.example.org")}")
                repo.removeUserRule("example.org"); repo.removeUserRule("ok.example.org")
            }.start()
            "upstream" -> Thread {
                val q = Dns.buildQuery(0x4242, "example.com", Dns.TYPE_A)
                val dq = DnsQuestion(0x4242, "example.com", 1, 1, q.size)
                for ((label, srv) in listOf("ipv4 1.1.1.1" to "1.1.1.1", "ipv6 2606:4700:4700::1111" to "2606:4700:4700::1111")) {
                    val t0 = System.nanoTime()
                    val r = UdpUpstream({ listOf(InetAddress.getByName(srv)) }, { true }).resolve(q, dq)
                    say("upstream $label: ${if (r != null) "OK ${r.size}B" else "FAIL"} ${(System.nanoTime() - t0) / 1_000_000} ms")
                }
                val t0 = System.nanoTime()
                val r = DohUpstream("https://cloudflare-dns.com/dns-query").resolve(q, dq)
                say("upstream doh: ${if (r != null) "OK ${r.size}B" else "FAIL"} ${(System.nanoTime() - t0) / 1_000_000} ms")
            }.start()
            "protect" -> { ProtectedAppManager.get(this).setProtected(arg ?: return, true); say("protect: $arg") }
            "unprotect" -> { ProtectedAppManager.get(this).setProtected(arg ?: return, false); say("unprotect: $arg") }
            "unlock" -> { RedirectGuard.sharedUnlock.unlock((arg?.toLongOrNull() ?: 30) * 1000); say("unlock: ${arg ?: 30}s") }
            "guard" -> say("guard: running=${RedirectGuard.instance != null} front=${RedirectGuard.instance?.engine?.tracker?.current}")
            "history" -> RedirectHistoryRepository.get(this).read().takeLast(20).forEach {
                say("hist: ${it.source} -> ${it.target} ${it.decision} ${it.reasons.joinToString(",")} score=${it.score} ia=${it.interactionAgeMs} o=${it.outcome}")
            }
            "prompt" -> org.elderguard.redirect.RedirectPromptActivity.ask(this,
                org.elderguard.redirect.Transition(arg ?: "org.elderguard.testing.redirsim", null, "org.elderguard.testing.shop", null, System.currentTimeMillis(), 5000),
                org.elderguard.redirect.Verdict(org.elderguard.redirect.Decision.ASK, emptyList(), org.elderguard.redirect.RiskLevel.MEDIUM, 40, null))
            "strict_pin" -> say("strict: pin -> ${org.elderguard.owner.AdminPin(this).set(arg ?: "123456", intent?.getStringExtra("old")) ?: "set"}")
            "strict_enter" -> say("strict: enter -> ${org.elderguard.owner.StrictModeManager(this).enter() ?: "ok"}")
            "strict_exit" -> say("strict: exit -> ${org.elderguard.owner.AdminPin(this).check(arg ?: "") ?: org.elderguard.owner.StrictModeManager(this).exit().let { "ok" }}")
            "strict_status" -> { val m = org.elderguard.owner.StrictModeManager(this); say("strict: owner=${m.isDeviceOwner} on=${m.enabled} allow=${m.allowlist.sorted()} lockTask=${m.lockTaskActive}") }
            "suspend" -> say("suspend ${arg}: ${org.elderguard.owner.StrictModeManager(this).setSuspended(arg ?: "", true) ?: "ok"}")
            "unsuspend" -> say("unsuspend ${arg}: ${org.elderguard.owner.StrictModeManager(this).setSuspended(arg ?: "", false) ?: "ok"}")
            "strict_release" -> say("strict: release -> ${org.elderguard.owner.AdminPin(this).check(arg ?: "") ?: org.elderguard.owner.StrictModeManager(this).releaseOwnership(emptyList()).let { "ok" }}")
            "upstream_set" -> { org.elderguard.vpn.VpnSettings(this).upstream = org.elderguard.vpn.UpstreamChoice.valueOf(arg ?: "AUTO"); VpnController.restart(this); say("upstream: $arg (restart sent)") }
            "block", "allow" -> Thread { say("rule $c $arg -> ${FilterRepository.get(this).addUserRule(arg ?: "", c == "block")}") }.start()
            "unrule" -> Thread { FilterRepository.get(this).removeUserRule(arg ?: ""); say("rule removed $arg") }.start()
            "bypass", "unbypass" -> { val s = org.elderguard.vpn.VpnSettings(this); s.bypass = if (c == "bypass") s.bypass + (arg ?: "") else s.bypass - (arg ?: ""); VpnController.restart(this); say("$c $arg (restart sent)") }
            "add_list" -> Thread { say("add_list: ${FilterRepository.get(this).addCustom("test", arg ?: "") ?: "ok"}") }.start()
            "update_lists" -> Thread { val retry = org.elderguard.filter.FilterUpdateJob.updateAll(this, force = arg == "force"); say("update_lists: retry=$retry " +
                FilterRepository.get(this).catalog().joinToString(" | ") { "${it.id} on=${it.enabled} rules=${it.ruleCount} err=${it.lastError}" }) }.start()
            "enable_list" -> Thread { FilterRepository.get(this).setEnabled(arg ?: "", true); say("enabled $arg") }.start()
            "trace" -> { getSharedPreferences("debug", MODE_PRIVATE).edit().putBoolean("trace", arg != "off").apply(); say("trace ${arg ?: "on"} (takes effect when the accessibility service reconnects)") }
            "clear_history" -> { RedirectHistoryRepository.get(this).clear(); say("history cleared") }
            else -> say("unknown cmd $c")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1 && resultCode == RESULT_OK) { VpnController.start(this); say("vpn: consent ok, start sent") } else say("vpn: consent refused")
    }

    private fun pkg(p: String) {
        val i = packageManager.getLaunchIntentForPackage(p)
        if (i == null) say("not installed: $p") else startActivity(i)
    }

    private fun view(u: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }.onFailure { say("no handler: $u") }

    @Suppress("unused") private val keep = AdBlockVpnService::class
}
