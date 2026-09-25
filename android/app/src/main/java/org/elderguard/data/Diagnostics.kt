package org.elderguard.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.elderguard.detect.AppFacts
import org.elderguard.detect.StaticVerdict
import org.elderguard.monitor.AdCloserService
import org.elderguard.monitor.Capabilities
import org.json.JSONObject

/**
 * Builds the file the user can send to family / the developer when testing on another phone.
 * It is a plain text file written to the app's cache and handed to Android's share sheet: the guard itself never
 * uploads anything. Contents: phone model and Android version (no serial, IMEI, account or Android ID), the guard's
 * settings and permission state, and the two local logs.
 */
object Diagnostics {
    private const val AUTHORITY = "org.elderguard.files"

    fun versionName(c: Context): String = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
    fun versionCode(c: Context): Long = runCatching {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    }.getOrDefault(0)

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    fun buildText(c: Context): String {
        val s = Store(c)
        val caps = Capabilities.read(c)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val events = s.readLog()
        val diag = s.readDiag()
        return buildString {
            appendLine("# 守門員記錄檔 / Elder Guard log")
            appendLine("app=${versionName(c)} (${versionCode(c)})")
            appendLine("device=${device()}")
            appendLine("rom=${Build.DISPLAY}")
            appendLine("exported=${stamp.format(Date())}")
            appendLine("diagConsent=${s.diagConsent}")
            appendLine("caps usage=${caps.usageAccess} notifAccess=${caps.notificationAccess} postNotif=${caps.postNotifications} " +
                "batteryUnrestricted=${caps.batteryUnrestricted} monitorAlive=${caps.monitorAlive} heartbeatAgeSec=${caps.heartbeatAgeSec} " +
                "closer=${AdCloserService.isEnabled(c)}")
            appendLine("lastScan=${if (s.lastScanMs > 0) stamp.format(Date(s.lastScanMs)) else "never"} flagged=${s.lastScanFlagged}")
            appendLine("kept=${s.keptPackages().joinToString(",")}")
            appendLine()
            appendLine("== events (${events.size}) ==")
            events.forEach { appendLine(fmt(it)) }
            appendLine()
            appendLine("== diag (${diag.size}) ==")
            if (s.diagConsent != true) appendLine("(使用者沒有開啟「記錄診斷資料」)")
            diag.forEach { appendLine(fmt(it)) }
        }
    }

    private val lineTime = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private fun fmt(o: JSONObject) = lineTime.format(Date(o.optLong("t"))) + " " + o.toString()

    /** Writes the export file and returns a share-sheet intent for it. */
    fun shareIntent(c: Context): Intent {
        val dir = File(c.cacheDir, "export").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest export
        val name = "elderguard-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
        val f = File(dir, name).apply { writeText(buildText(c)) }
        val uri = FileProvider.getUriForFile(c, AUTHORITY, f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "守門員記錄檔 $name")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        return Intent.createChooser(send, "把記錄檔傳給…").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** What the static scan saw, as codes (for tuning false alarms / misses). No app content beyond class names. */
    fun scanFields(f: AppFacts, v: StaticVerdict): Map<String, Any?> = mapOf(
        "level" to v.level.name, "score" to v.score,
        "ads" to f.adNetworks.joinToString(","),
        "trig" to f.triggers.size,
        "hidden" to f.hiddenIcon, "noLauncher" to f.noLauncherActivity,
        "bait" to f.baitCategory,
        "obf" to f.obfuscatedExamples?.take(2)?.joinToString(","),
        "perm" to listOfNotNull("overlay".takeIf { f.requestsOverlay }, "notifListener".takeIf { f.requestsNotificationListener },
            "usageOrFiles".takeIf { f.requestsUsageOrAllFiles }).joinToString(","),
        "installer" to (f.installer ?: "none"),
        "complete" to f.scanComplete,
    )

    /** Records uncaught crashes (only with consent), then lets Android handle them as usual. */
    fun installCrashLogger(c: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                Store(c).diag("crash", fields = mapOf(
                    "thread" to t.name,
                    "err" to (e.javaClass.name + ": " + e.message).take(300),
                    "at" to e.stackTrace.take(12).joinToString(" < ") { f -> "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}" },
                    "cause" to e.cause?.let { (it.javaClass.simpleName + ": " + it.message).take(200) },
                ))
            }
            previous?.uncaughtException(t, e)
        }
    }
}
