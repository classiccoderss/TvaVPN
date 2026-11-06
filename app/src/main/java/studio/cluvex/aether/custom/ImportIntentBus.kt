package studio.cluvex.aether.custom

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * External-import handoff (tvaVPN): tvaVPN opened because the user tapped a
 * supported config link/file somewhere else, or shared one into the app, via
 * the VIEW/SEND intent-filters declared on MainActivity in the manifest.
 *
 * MainActivity is the only thing that ever sees the raw android.content.Intent
 * (onCreate / onNewIntent), and CustomConfigsPage is the only thing that
 * already knows how to import a config (ConfigImporter.fromText / fromFile,
 * the exact path a manual paste or file pick goes through). This StateFlow is
 * the handoff between the two, since they are not otherwise connected:
 *
 *   MainActivity.handleIncomingIntent() -> post()
 *   HomeScreen (navigates to Settings -> Custom configs if nothing else is open)
 *   CustomConfigsPage (collects pending, runs the import, calls consume())
 *
 * A StateFlow rather than a one-shot Channel/event on purpose: MainActivity
 * may post before CustomConfigsPage has been composed at all (the app was not
 * running, or was sitting on the home screen), so the payload has to wait
 * here until a collector actually shows up.
 */
object ImportIntentBus {

    /** What arrived from outside the app. */
    sealed interface Payload {
        /** A share link or pasted/forwarded config text - the value IS the config. */
        data class Text(val value: String) : Payload

        /** A file (JSON, `.npvt`, or a QR-code image) opened or shared in. */
        data class FileUri(val uri: Uri) : Payload
    }

    private val _pending = MutableStateFlow<Payload?>(null)
    val pending: StateFlow<Payload?> = _pending

    fun post(payload: Payload) {
        _pending.value = payload
    }

    /** CustomConfigsPage calls this once it has started the import, so it only runs once. */
    fun consume() {
        _pending.value = null
    }
}
