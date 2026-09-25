package org.elderguard

import android.app.Application
import org.elderguard.data.Diagnostics
import org.elderguard.data.Store

class ElderGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.installCrashLogger(this)
        val store = Store(this)
        val code = Diagnostics.versionCode(this)
        if (store.lastVersionSeen != code) {
            store.diag("version", fields = mapOf("from" to store.lastVersionSeen, "to" to code, "name" to Diagnostics.versionName(this),
                "device" to Diagnostics.device()))
            store.lastVersionSeen = code
        }
    }
}
