package studio.cluvex.aether.core

import android.content.Context

/**
 * One persisted fact: "the user wants the tunnel up".
 *
 * Android can destroy the VPN service without the user asking: a swipe in the
 * recent-apps list on some phones, or the system reclaiming memory. The service
 * is START_STICKY, so the system restarts it afterwards, but it comes back with no
 * intent at all. This flag is what tells that restart whether to bring the tunnel
 * back (the user had it on) or to stay down (the user had turned it off).
 *
 * It is set when a connect is requested and cleared by every deliberate stop
 * (Disconnect, VPN permission revoked, a failed connect), so a tunnel the user
 * switched off never comes back by itself.
 *
 * The second value here is bookkeeping for the same story: how far the
 * task-removal recovery chain has got. It has to be on DISK, next to the flag,
 * because the chain exists precisely for the case where the process - and its
 * memory - is gone between one attempt and the next (see
 * [studio.cluvex.aether.vpn.AetherVpnService.scheduleTaskRemovalRecovery]).
 */
object RuntimeFlag {
    private const val FILE = "aether_runtime"
    private const val KEY_WANT_CONNECTED = "want_connected"
    private const val KEY_RECOVERY_ATTEMPTS = "recovery_attempts"

    fun wantConnected(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_WANT_CONNECTED, false)

    /** Written synchronously: the process may be killed a moment after this call. */
    fun setWantConnected(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WANT_CONNECTED, value).commit()
    }

    /** Recovery attempts already spent on the current outage. */
    fun recoveryAttempts(context: Context): Int =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_RECOVERY_ATTEMPTS, 0)

    /**
     * Counts one more attempt and returns the new total. Synchronous for the same
     * reason as [setWantConnected]: the alarm may start a NEW process seconds
     * later, and it must see the count this process just wrote.
     */
    fun bumpRecoveryAttempts(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_RECOVERY_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_RECOVERY_ATTEMPTS, next).commit()
        return next
    }

    /**
     * Back to zero: a tunnel is up again, or the user made a deliberate
     * connect/disconnect, so the next outage gets the full retry budget.
     */
    fun resetRecoveryAttempts(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_RECOVERY_ATTEMPTS, 0).commit()
    }
}
