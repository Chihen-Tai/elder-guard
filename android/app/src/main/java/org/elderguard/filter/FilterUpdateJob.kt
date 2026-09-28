package org.elderguard.filter

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File
import org.elderguard.data.Store
import org.elderguard.vpn.VpnSettings

/**
 * Periodic filter-list update (only when the user turned "update lists from the internet" on). Uses the system
 * JobScheduler: it batches with other jobs, waits for a network (Wi-Fi only by default), and survives reboot
 * (setPersisted), so there is no timer of our own running in the background.
 */
class FilterUpdateJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread({
            val retry = runCatching { updateAll(this, force = false) }.getOrDefault(true)
            jobFinished(params, retry)
        }, "filter-update").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { worker?.interrupt(); return true }

    companion object {
        private const val JOB_ID = 4201

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            val s = VpnSettings(c)
            if (!s.remoteUpdates) { js.cancel(JOB_ID); return }
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, FilterUpdateJob::class.java))
                .setRequiredNetworkType(if (s.updateOnWifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(s.updateIntervalHours * 3600_000L)
                .setPersisted(true)
                .build())
        }

        /**
         * Updates every enabled list that has a URL. Returns true when something should be retried later.
         * Each list is independent: one broken list never touches the others' rules.
         */
        fun updateAll(c: Context, force: Boolean, onlyId: String? = null): Boolean {
            val repo = FilterRepository.get(c)
            val updater = FilterUpdater(File(c.cacheDir, "filter-dl").apply { mkdirs() })
            var retry = false
            var changed = false
            for (l in repo.catalog().filter { it.enabled && it.url != null && (onlyId == null || it.id == onlyId) }) {
                if (Thread.currentThread().isInterrupted) return true
                val r = updater.fetch(l.url!!, l.ruleCount, if (force) null else l.sha256)
                when {
                    r.unchanged -> repo.recordUpdate(l.id, null, l.sha256, null).also {
                        // "checked, nothing new": keep the rules and clear any old error
                    }
                    r.compiled != null -> { repo.recordUpdate(l.id, r.compiled, r.sha256, null); changed = true }
                    else -> { repo.recordUpdate(l.id, null, null, r.error); retry = true }
                }
                Store(c).diag("filter_update", fields = mapOf("list" to l.id, "ok" to (r.error == null), "err" to r.error,
                    "rules" to r.compiled?.ruleCount, "unchanged" to r.unchanged))
            }
            if (changed) repo.applyUpdates()
            return retry
        }
    }
}
