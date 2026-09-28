package org.elderguard.owner

import android.app.ActivityOptions
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.elderguard.data.Store

/**
 * Device-owner admin. Only becomes active when the family provisions it on a phone with no accounts:
 *   adb shell dpm set-device-owner org.elderguard/.owner.GuardAdminReceiver
 * It never makes itself device owner.
 */
class GuardAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(c: Context, i: Intent) { Store(c).diag("owner", fields = mapOf("state" to "enabled")) }
    override fun onDisabled(c: Context, i: Intent) { Store(c).diag("owner", fields = mapOf("state" to "disabled")) }

    companion object { fun component(c: Context) = ComponentName(c, GuardAdminReceiver::class.java) }
}

/**
 * Decides whether a package may be suspended / left out of strict mode. Built from THIS phone's roles and intent
 * handlers plus system flags — not a hardcoded brand list. Rule of thumb: only user-installed apps that hold no
 * system role may ever be suspended.
 */
class CriticalPackageGuard(private val c: Context) {
    private val pm = c.packageManager

    val critical: Set<String> by lazy {
        fun handlers(i: Intent) = runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        val s = HashSet<String>()
        s += listOf(c.packageName, "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.server.telecom",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.emergency", "com.android.providers.telephony", "com.android.shell")
        s += handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        s += handlers(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        s += handlers(Intent(Settings.ACTION_SETTINGS))
        s += handlers(Intent("android.content.pm.action.REQUEST_PERMISSIONS"))
        s += handlers(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(Uri.parse("content://x/y.apk")))
        runCatching { c.getSystemService(TelecomManager::class.java).defaultDialerPackage }.getOrNull()?.let { s += it }
        runCatching { Telephony.Sms.getDefaultSmsPackage(c) }.getOrNull()?.let { s += it }
        runCatching { c.getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName } }.getOrNull()?.let { s += it }
        runCatching { WebView.getCurrentWebViewPackage()?.packageName }.getOrNull()?.let { s += it }
        s
    }

    /** Why [pkg] must not be suspended, or null when it may be. */
    fun whyCritical(pkg: String): String? {
        if (pkg in critical) return "手機運作需要它"
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return "找不到這個 App"
        if ((ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return "這是手機內建的 App"
        if ((ai.flags and ApplicationInfo.FLAG_PERSISTENT) != 0) return "手機運作需要它"
        return null
    }
}

/**
 * Admin PIN for leaving strict mode. PBKDF2-SHA256 (120k rounds, random salt); only the hash is stored, in private
 * app storage excluded from backup and device transfer. 6–12 digits. After 5 wrong tries each further try waits
 * 30 s, 60 s, 120 s ... (max 1 h); the wait runs on the time since boot, so changing the clock does not skip it, and
 * a reboot does not reset the count. An existing PIN is only replaced after the old one was entered.
 */
class AdminPin(c: Context) {
    private val p = c.getSharedPreferences("owner", Context.MODE_PRIVATE)
    private val cr = c.contentResolver
    val isSet get() = p.contains("pinHash")

    /** Sets the PIN; when one exists, [old] must be correct. Returns an error message or null. */
    fun set(pin: String, old: String? = null): String? {
        if (pin.length !in 6..12 || !pin.all { it.isDigit() }) return "密碼要 6–12 位數字。"
        if (isSet) { val e = check(old ?: ""); if (e != null) return "要先輸入舊密碼：$e" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit().putString("pinSalt", hex(salt)).putString("pinHash", hex(hash(pin, salt))).putInt("pinFails", 0).apply()
        return null
    }

    private fun bootCount() = runCatching { Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** Wait still required before the next try, in ms. */
    private fun waitLeftMs(): Long {
        val fails = p.getInt("pinFails", 0)
        if (fails < 5) return 0
        val delay = minOf(30_000L shl minOf(fails - 5, 7), 3600_000L)
        val now = android.os.SystemClock.elapsedRealtime()
        // after a reboot the old elapsed time means nothing: count the wait from boot
        val last = if (p.getInt("pinFailBoot", -2) == bootCount()) p.getLong("pinFailAt", 0) else 0L
        return (last + delay - now).coerceAtLeast(0)
    }

    /** null = correct; otherwise a message (wrong / wait). */
    fun check(pin: String): String? {
        val wait = waitLeftMs()
        if (wait > 0) return "錯太多次了，請 ${wait / 1000 + 1} 秒後再試"
        val salt = unhex(p.getString("pinSalt", null) ?: return "還沒有設定密碼")
        val ok = java.security.MessageDigest.isEqual(hash(pin, salt), unhex(p.getString("pinHash", "")!!))
        if (ok) { p.edit().putInt("pinFails", 0).apply(); return null }
        p.edit().putInt("pinFails", p.getInt("pinFails", 0) + 1).putLong("pinFailAt", android.os.SystemClock.elapsedRealtime())
            .putInt("pinFailBoot", bootCount()).apply()
        return "密碼不對"
    }

    private fun hash(pin: String, salt: ByteArray) =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Strict protection for a phone dedicated to an older user (device owner only, never the default):
 *  - Lock-task mode with an allowlist: Android itself refuses to bring any other app to the front, so an ad cannot
 *    open a shop BEFORE the fact (the only real "before launch" control a non-root app has).
 *  - Suspending chosen junk apps (setPackagesSuspended), guarded by [CriticalPackageGuard].
 * Safety: the phone app, emergency calls, the home screen, notifications, the power menu and Settings always stay
 * available; the admin PIN leaves strict mode (clearing the allowlist ends lock-task mode at once); a reboot does not
 * lock anyone out (strict mode is re-entered only after boot completes, and the power menu stays available).
 */
class StrictModeManager(private val c: Context) {
    private val dpm = c.getSystemService(DevicePolicyManager::class.java)
    private val admin = GuardAdminReceiver.component(c)
    private val p = c.getSharedPreferences("owner", Context.MODE_PRIVATE)
    val critical = CriticalPackageGuard(c)

    val isDeviceOwner get() = runCatching { dpm.isDeviceOwnerApp(c.packageName) }.getOrDefault(false)

    var enabled: Boolean
        get() = p.getBoolean("strict", false)
        private set(v) = p.edit().putBoolean("strict", v).apply()

    var allowlist: Set<String>
        get() = p.getStringSet("allow", null)?.toSet() ?: defaultAllowlist()
        set(v) = p.edit().putStringSet("allow", v).apply()

    /** Launcher, phone, messages, camera, photos, Settings, a browser, and common messaging / video apps if installed. */
    fun defaultAllowlist(): Set<String> {
        val pm = c.packageManager
        fun h(i: Intent) = runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        val s = HashSet<String>()
        s += h(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        s += h(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        s += h(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:1")))
        s += h(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        s += h(Intent(Intent.ACTION_VIEW).setType("image/*"))
        s += h(Intent(Settings.ACTION_SETTINGS))
        s += h(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))).take(1)
        s += listOf("jp.naver.line.android", "com.tencent.mm", "com.google.android.youtube", "tv.danmaku.bili", "com.whatsapp")
            .filter { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
        return s
    }

    /** Always in the lock-task allowlist, whatever the family chose (never lock the user out of calls or the guard). */
    private fun mandatory(): Set<String> = critical.critical.filter { runCatching { c.packageManager.getApplicationInfo(it, 0) }.isSuccess }.toSet()

    /** Returns an error message, or null when strict mode is now on. */
    fun enter(): String? {
        if (!isDeviceOwner) return "這支手機還沒有把守門員設為裝置擁有者"
        val pkgs = (allowlist + mandatory() + c.packageName).toTypedArray()
        return runCatching {
            dpm.setLockTaskPackages(admin, pkgs)
            if (Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
            enabled = true
            startLockedHome()
            null
        }.getOrElse { "沒辦法開啟：${it.message}" }
    }

    /**
     * Starts the guard's own screen as the root of the locked task (device owners may start activities from the
     * background). NOT the launcher: a HOME-type task cannot be the locked task (emulator test: lock-task state stayed
     * NONE). From the guard the user reaches the home screen with the Home button (LOCK_TASK_FEATURE_HOME) and opens
     * allowlisted apps as usual; anything else is refused by Android.
     */
    fun startLockedHome() {
        val i = Intent(c, org.elderguard.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opts = if (Build.VERSION.SDK_INT >= 28) ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle() else null
        runCatching { c.startActivity(i, opts) }
    }

    /** Called by the guard's screen: re-enter lock-task mode if strict mode is on but the phone is not locked. */
    fun ensureLocked(activity: android.app.Activity) {
        if (!enabled || !isDeviceOwner) return
        val am = c.getSystemService(android.app.ActivityManager::class.java)
        if (am.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE && dpm.isLockTaskPermitted(c.packageName))
            runCatching { activity.startLockTask() }
    }

    val lockTaskActive get() = c.getSystemService(android.app.ActivityManager::class.java).lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE

    /** Leaves strict mode immediately: an empty allowlist ends lock-task mode (Android exits it for us). */
    fun exit() {
        runCatching { dpm.setLockTaskPackages(admin, emptyArray()) }
        enabled = false
    }

    /** Re-enter after a reboot if it was on (called from the boot receiver). */
    fun resumeAfterBoot() { if (enabled && isDeviceOwner) enter() }

    /** Suspend / unsuspend one app. Returns an error message or null. */
    fun setSuspended(pkg: String, suspend: Boolean): String? {
        if (!isDeviceOwner) return "需要裝置擁有者權限"
        if (suspend) critical.whyCritical(pkg)?.let { return "不能暫停：$it" }
        return runCatching {
            val failed = dpm.setPackagesSuspended(admin, arrayOf(pkg), suspend)
            if (failed.isNotEmpty()) "Android 不允許暫停這個 App" else null
        }.getOrElse { it.message }
    }

    fun isSuspended(pkg: String) = runCatching { c.packageManager.isPackageSuspended(pkg) }.getOrDefault(false)

    /** Gives up device ownership entirely (after the PIN): strict mode off, suspended apps restored first. */
    fun releaseOwnership(suspended: Collection<String>) {
        exit()
        runCatching { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false) }
        runCatching { dpm.clearDeviceOwnerApp(c.packageName) }
    }
}
