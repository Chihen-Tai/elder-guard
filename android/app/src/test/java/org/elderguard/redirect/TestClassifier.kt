package org.elderguard.redirect

/**
 * Unit tests have no PackageManager, so they state the two trust tests explicitly here (review B8: the production
 * constructor has no defaults for them, so a new caller cannot silently fall back to a name-based rule).
 * Test world: AOSP / Google platform names are the preinstalled system apps; every class is an Activity except a
 * keyboard's input window.
 */
val TEST_IS_SYSTEM: (String) -> Boolean = { p ->
    p == "android" || p in SystemFlowClassifier.CORE_SYSTEM || p.startsWith("com.android.") || p.startsWith("com.google.android.")
}
val TEST_IS_ACTIVITY: (String, String?) -> Boolean = { _, cls -> cls != null && !cls.endsWith("SoftInputWindow") }

fun testClassifier(
    launchers: Set<String>,
    imes: Set<String>,
    systemTargets: Set<String>,
    self: String,
    browsers: Set<String> = emptySet(),
    systemUis: Set<String> = setOf(SystemFlowClassifier.SYSTEM_UI),
    defaultHome: String? = null,
    userDefaults: Set<String> = emptySet(),
    isSystemApp: (String) -> Boolean = TEST_IS_SYSTEM,
) = SystemFlowClassifier(launchers, imes, systemTargets, self = self, browsers = browsers, systemUis = systemUis,
    isActivity = TEST_IS_ACTIVITY, isSystemApp = isSystemApp, defaultHome = defaultHome, userDefaults = userDefaults)
