package studio.cluvex.aether.core

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * The one thing on a Xiaomi phone that an app cannot grant itself: MIUI/HyperOS
 * "Autostart".
 *
 * Battery optimisation being off is necessary but NOT sufficient there. MIUI adds
 * its own permission on top - a per-app Autostart switch in the Security app -
 * and while it is off, MIUI refuses to let the app start anything by itself in
 * the background (including the system alarm the VPN service uses to bring the
 * tunnel back after the task is swiped away). That is why the connection
 * survives a recents swipe on other phones and dies on Xiaomi, and why no code
 * inside the app can fix it alone: the switch lives in a vendor app.
 *
 * This opens that screen (and falls back to the app's own system page when a
 * build has moved or removed it). Nothing is granted automatically - the user
 * still flips the switch, which is the whole point of a permission.
 */
object OemBackground {

    private const val FILE = "oem_background"
    private const val KEY_AUTOSTART_OPENED = "xiaomi_autostart_opened"
    private const val KEY_AUTOSTART_CONFIRMED = "xiaomi_autostart_confirmed"

    /**
     * MIUI's own app-op id for Autostart. Not part of AOSP (hence no constant),
     * but MIUI has carried this number since MIUI 8 and its AppOpsService
     * answers it through the standard `checkOpNoThrow(int, int, String)`.
     */
    private const val MIUI_OP_AUTO_START = 10008

    /**
     * True on Xiaomi / Redmi / POCO. `Build.BRAND` is what catches POCO, whose
     * manufacturer is reported as Xiaomi, and Redmi phones sold under the Redmi
     * brand.
     */
    fun isXiaomi(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return manufacturer == "xiaomi" ||
            manufacturer == "redmi" ||
            manufacturer == "poco" ||
            brand == "xiaomi" ||
            brand == "redmi" ||
            brand == "poco"
    }

    /**
     * Whether the Autostart row still has something to ask the user for.
     *
     * The row is gone as soon as the permission is KNOWN to be on:
     *
     *  - [isAutostartGranted] reads it from the system where the ROM allows that
     *    (MIUI's app-op, and MIUI's hidden helper classes);
     *  - [markAutostartConfirmed] records the strongest evidence there is: the
     *    phone actually let the app start its own VPN service from the
     *    background, which is precisely what the switch controls (some HyperOS
     *    builds answer every query with "not allowed" long after the switch was
     *    turned on, so the behaviour, not the answer, is what decides there);
     *  - and opening the screen from here still hides it for good, as before.
     *
     * The previous version could only do the last one, so an owner who enabled
     * Autostart by themselves in the Security app (or reinstalled the app, which
     * clears this flag while the MIUI permission stays on) kept seeing a step
     * that was already done.
     */
    fun needsAutostartStep(context: Context): Boolean {
        if (!isXiaomi()) return false
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_AUTOSTART_OPENED, false)) return false
        if (prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false)) return false
        return !isAutostartGranted(context)
    }

    /** Records that the user has been taken to MIUI's Autostart screen once. */
    fun markAutostartOpened(context: Context) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART_OPENED, true).apply()
    }

    /**
     * Records that the phone let this app start its VPN service in the
     * background, i.e. that Autostart is on in practice.
     *
     * Called by the VPN service when a task-removal/alarm start reaches it.
     * Cheap and idempotent; it only ever turns the row off.
     */
    fun markAutostartConfirmed(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_AUTOSTART_CONFIRMED, false)) return
        prefs.edit().putBoolean(KEY_AUTOSTART_CONFIRMED, true).apply()
        DiagnosticsLog.i(
            "oem",
            "The phone started the VPN service in the background, so MIUI/HyperOS Autostart " +
                "is working; the Settings step will not be shown again.",
        )
    }

    /**
     * Best-effort read of MIUI/HyperOS' per-app Autostart switch.
     *
     * Three probes, because no single one works on every build: MIUI's own
     * app-op, `android.miui.AppOpsUtils.getApplicationAutoStart` (MIUI 8 to
     * ~13) and `miui.app.AppOpsUtils.isAutoStartAllowed` (newer builds). A
     * probe that is missing, blocked by the hidden-API policy or answers
     * anything unexpected counts as "not known", never as granted - showing the
     * step once too often is harmless, hiding it while the switch is off is not.
     */
    fun isAutostartGranted(context: Context): Boolean {
        if (!isXiaomi()) return false
        val granted = miuiAppOp(context) ?: miuiHelper(context) ?: false
        if (granted) {
            DiagnosticsLog.i("oem", "MIUI/HyperOS reports Autostart as enabled for this app.")
        }
        return granted
    }

    /** MIUI's Autostart app-op, or null when this ROM does not answer it. */
    private fun miuiAppOp(context: Context): Boolean? = runCatching {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // The id-based `checkOpNoThrow(int, int, String)` is not part of the
        // public SDK, so it is reached reflectively. On AOSP the op id is unknown
        // and this throws, which is why the whole call is guarded.
        val check = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val mode = check.invoke(
            manager,
            MIUI_OP_AUTO_START,
            Process.myUid(),
            context.packageName,
        ) as? Int
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> false
            // MODE_DEFAULT means the ROM has no answer stored: not decided.
            else -> null
        }
    }.getOrNull()

    /** MIUI's hidden Autostart helpers, or null when neither exists here. */
    private fun miuiHelper(context: Context): Boolean? {
        // MIUI 8..13: 0 = allowed, 1 = denied.
        val byPackage = runCatching {
            val get = Class.forName("android.miui.AppOpsUtils").getMethod(
                "getApplicationAutoStart",
                Context::class.java,
                String::class.java,
            )
            when (val answer = get.invoke(null, context, context.packageName)) {
                is Boolean -> answer
                is Int -> answer == 0
                is String -> answer.trim() == "0"
                else -> null
            }
        }.getOrNull()
        if (byPackage != null) return byPackage
        // Newer HyperOS builds expose a boolean helper instead.
        return runCatching {
            Class.forName("miui.app.AppOpsUtils")
                .getMethod("isAutoStartAllowed", Context::class.java, String::class.java)
                .invoke(null, context, context.packageName) as? Boolean
        }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Opens MIUI/HyperOS's Autostart list. Returns false when none of the known
     * entry points exist on this build (a ROM may rename them between versions),
     * in which case the caller's row is simply a no-op rather than a crash.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = listOf(
            // MIUI 8 through HyperOS 1: Security -> Permissions -> Autostart.
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            // Some HyperOS builds expose the per-app detail page directly.
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartDetailManagementActivity",
            ),
        )
        for (component in candidates) {
            val intent = Intent().setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        // Fallback: the app's own system page, where "Battery" and, on some
        // builds, "Autostart" live. Always present, so the row never dead-ends.
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(fallback) }.isSuccess
    }
}
