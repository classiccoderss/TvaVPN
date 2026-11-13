package studio.cluvex.aether.core

import android.content.Context

/** User controls for the in-service connection guardian. Defaults preserve the
 * existing watchdog behaviour; preferences are read by the VPN service itself. */
object GuardianPrefs {
    private const val FILE = "connection_guardian"
    private const val MONITOR = "monitor_enabled"
    private const val RECOVERY = "quality_recovery_enabled"

    fun isMonitoringEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(MONITOR, true)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(MONITOR, enabled).apply()
    }

    fun isQualityRecoveryEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(RECOVERY, true)

    fun setQualityRecoveryEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(RECOVERY, enabled).apply()
    }
}
