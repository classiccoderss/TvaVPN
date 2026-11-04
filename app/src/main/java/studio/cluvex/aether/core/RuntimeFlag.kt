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
 */
object RuntimeFlag {
    private const val FILE = "aether_runtime"
    private const val KEY_WANT_CONNECTED = "want_connected"

    fun wantConnected(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_WANT_CONNECTED, false)

    /** Written synchronously: the process may be killed a moment after this call. */
    fun setWantConnected(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WANT_CONNECTED, value).commit()
    }
}
