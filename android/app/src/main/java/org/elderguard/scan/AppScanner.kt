package org.elderguard.scan

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.util.zip.ZipFile
import org.elderguard.detect.AdSdkCatalog
import org.elderguard.detect.AppFacts
import org.elderguard.detect.StaticRisk

/**
 * Inventory + static facts for every installed package visible to us (QUERY_ALL_PACKAGES), including packages
 * without a launcher icon. Must run off the main thread; ideally inside the monitor service (an activity's process
 * gets frozen when the screen turns off — M0 finding A).
 */
class AppScanner(private val context: Context) {
    private val pm = context.packageManager

    data class Entry(val facts: AppFacts, val installMs: Long, val updateMs: Long, val overlayAllowed: Boolean?)

    fun allPackages(): List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)

    fun scan(pkg: String): Entry? {
        val pi = try { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) } catch (e: PackageManager.NameNotFoundException) { return null }
        return scan(pi)
    }

    fun scan(pi: PackageInfo): Entry {
        val ai = pi.applicationInfo!!
        val pkg = pi.packageName
        // Preinstalled apps (including updated ones such as Play services, Gboard, Digital Wellbeing) are not scored
        // statically: they have no Play installer record and often no icon by design (vivo field test, 6 false "notice"
        // results). Behaviour monitoring still covers them.
        val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val label = pm.getApplicationLabel(ai).toString()
        val perms = pi.requestedPermissions?.toSet() ?: emptySet()
        val (hidden, noLauncher) = launcherState(pkg)
        val (networks, complete) = if (isSystem) emptyList<String>() to true else adNetworks(ai)
        val facts = AppFacts(
            packageName = pkg,
            label = label,
            isSystem = isSystem,
            installer = installer(pkg),
            firstInstallMs = pi.firstInstallTime,
            hiddenIcon = hidden,
            noLauncherActivity = noLauncher,
            adNetworks = networks,
            triggers = if (isSystem) emptyList() else triggers(pkg),
            requestsOverlay = "android.permission.SYSTEM_ALERT_WINDOW" in perms,
            requestsNotificationListener = hasNotificationListener(pkg),
            requestsUsageOrAllFiles = "android.permission.PACKAGE_USAGE_STATS" in perms || "android.permission.MANAGE_EXTERNAL_STORAGE" in perms,
            baitCategory = StaticRisk.baitCategory(label, pkg),
            scanComplete = complete,
            obfuscatedExamples = if (isSystem) null else obfuscation(context).examples(componentNames(pkg)),
        )
        return Entry(facts, pi.firstInstallTime, pi.lastUpdateTime, overlayAllowed(ai))
    }

    /**
     * hiddenIcon: the manifest has MAIN/LAUNCHER activities but all of them are disabled now (the app disabled its own
     * icon, or someone did). noLauncher: no MAIN/LAUNCHER activity at all.
     */
    fun launcherState(pkg: String): Pair<Boolean, Boolean> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val all = pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
        if (all.isEmpty()) return false to true
        val enabled = all.filter { ri ->
            val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
            when (pm.getComponentEnabledSetting(cn)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> ri.activityInfo.enabled && ri.activityInfo.applicationInfo.enabled
            }
        }
        return enabled.isEmpty() to false
    }

    /** Class names of the app's launcher activities (alias targets included), enabled or not. */
    fun launcherClasses(pkg: String): Set<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), PackageManager.MATCH_DISABLED_COMPONENTS)
            .flatMap { listOfNotNull(it.activityInfo.name, it.activityInfo.targetActivity) }.toSet()

    private fun installer(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    } catch (e: Exception) { null }

    private val triggerActions = listOf(
        Intent.ACTION_USER_PRESENT, Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
        Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY, Intent.ACTION_BOOT_COMPLETED,
    )

    private fun triggers(pkg: String): List<String> = triggerActions.filter { a ->
        pm.queryBroadcastReceivers(Intent(a).setPackage(pkg), PackageManager.MATCH_DISABLED_COMPONENTS).isNotEmpty()
    }.map { it.substringAfterLast('.') }

    private fun hasNotificationListener(pkg: String): Boolean =
        pm.queryIntentServices(Intent("android.service.notification.NotificationListenerService").setPackage(pkg), 0).isNotEmpty()

    /** Whether another app may draw over apps. Returns null when the platform does not let us read it. */
    private fun overlayAllowed(ai: ApplicationInfo): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, ai.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, ai.packageName)
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            else -> null // MODE_DEFAULT: depends on the permission grant, which we cannot read for other apps
        }
    } catch (e: SecurityException) { null }

    /** Distinct ad networks found in the app's dex string tables (base + splits), within a read budget. */
    private fun adNetworks(ai: ApplicationInfo): Pair<List<String>, Boolean> {
        val found = linkedSetOf<String>()
        var budget = READ_BUDGET
        var complete = true
        val prefixes = AdSdkCatalog.dexPrefixes.flatMap { (net, ps) -> ps.map { it.toByteArray() to net } }
        val apks = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
        for (path in apks) {
            try {
                ZipFile(path).use { z ->
                    for (e in z.entries()) {
                        if (!(e.name.startsWith("classes") && e.name.endsWith(".dex"))) continue
                        if (e.size > budget) { complete = false; continue }
                        val dex = z.getInputStream(e).use { it.readBytes() }
                        budget -= dex.size
                        DexStrings.forEachTypeDescriptor(dex) { off, len ->
                            for ((p, net) in prefixes) if (net !in found && DexStrings.startsWith(dex, off, len, p)) found += net
                        }
                    }
                }
            } catch (e: Exception) {
                complete = false
            }
        }
        return found.toList() to complete
    }

    private fun componentNames(pkg: String): List<String> = try {
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val pi = pm.getPackageInfo(pkg, flags)
        listOfNotNull(pi.activities, pi.services, pi.receivers, pi.providers).flatMap { arr -> arr.map { it.name } }
    } catch (e: Exception) { emptyList() }

    companion object {
        @Volatile private var obf: org.elderguard.detect.Obfuscation? = null

        /** Dictionary (≈73k words, 194 KB gz) and rules from assets, loaded once. */
        fun obfuscation(c: Context): org.elderguard.detect.Obfuscation = obf ?: synchronized(this) {
            obf ?: run {
                // AGP un-gzips ".gz" assets at build time (the APK holds assets/words.txt) — 0.4.0 crashed looking for .gz
                val words = (runCatching { c.assets.open("words.txt") }.getOrNull()
                    ?: java.util.zip.GZIPInputStream(c.assets.open("words.txt.gz"))).bufferedReader().readLines().toHashSet()
                val j = org.json.JSONObject(c.assets.open("obf_rules.json").bufferedReader().readText())
                fun list(k: String) = j.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
                org.elderguard.detect.Obfuscation(words, list("pinyin").toHashSet(), list("lib_prefixes"), list("suffixes")).also { obf = it }
            }
        }

        const val READ_BUDGET = 96L * 1024 * 1024
        fun ownUid() = Process.myUid()
    }
}

/** Minimal dex string-table reader (header string_ids; MUTF-8 data). Only what the scanner needs. */
object DexStrings {
    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    /** Calls [f] with (offset, length) of every string that looks like a type descriptor ("L...;"). */
    inline fun forEachTypeDescriptor(dex: ByteArray, f: (Int, Int) -> Unit) = forEachString(dex) { off, len ->
        if (len > 2 && dex[off] == 'L'.code.toByte() && dex[off + len - 1] == ';'.code.toByte()) f(off, len)
    }

    inline fun forEachString(dex: ByteArray, f: (Int, Int) -> Unit) {
        if (dex.size < 0x70 || dex[0] != 'd'.code.toByte() || dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte()) return
        val count = u32p(dex, 0x38)
        val idsOff = u32p(dex, 0x3C)
        for (i in 0 until count) {
            var p = u32p(dex, idsOff + 4 * i)
            if (p <= 0 || p >= dex.size) continue
            while (p < dex.size && (dex[p].toInt() and 0x80) != 0) p++ // skip ULEB128 utf16 length
            p++
            var end = p
            while (end < dex.size && dex[end].toInt() != 0) end++
            f(p, end - p)
        }
    }

    fun u32p(b: ByteArray, o: Int) = u32(b, o)

    fun startsWith(dex: ByteArray, off: Int, len: Int, prefix: ByteArray): Boolean {
        if (len < prefix.size) return false
        for (i in prefix.indices) if (dex[off + i] != prefix[i]) return false
        return true
    }
}
