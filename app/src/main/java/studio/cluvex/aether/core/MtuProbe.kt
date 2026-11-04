package studio.cluvex.aether.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.cluvex.aether.model.ConnectionProfile

/**
 * Best-effort automatic MTU detection (1.9.0).
 *
 * ## What this can and cannot know
 *
 * An unrooted Android app has no portable way to run true kernel-level Path MTU
 * Discovery: that needs either raw sockets or the `IP_MTU_DISCOVER` socket option,
 * neither of which the standard Java socket API exposes, and reaching them would mean
 * native (NDK) code this project does not have. What IS available, and what [probe]
 * uses instead: a plain [DatagramSocket] send of a candidate-sized UDP datagram toward
 * the path that matters. Linux's own routing layer already rejects an oversized send
 * locally (an `EMSGSIZE`-style [SocketException]) once it has learned the path is
 * narrower - often from a past ICMP "fragmentation needed" message - and [probe]
 * reads that rejection as "too big, try smaller". A send that does NOT throw is read
 * as "the OS let this size onto the path": the best signal available without an echo
 * back from the far end, good on most networks, but not a guarantee on one that
 * silently drops oversized packets instead of rejecting them (a "PMTUD black hole").
 * For that reason the result is a SUGGESTION the person sees before it is applied,
 * never something this silently writes into the profile on its own, and it is always
 * clamped to [ConnectionProfile.MTU_MIN]..[ConnectionProfile.MTU_MAX] regardless.
 *
 * ## What the number means
 *
 * The probe measures the UDP PAYLOAD size that gets through, not the TUN interface's
 * MTU: every tunnel protocol adds its own header on top, and different protocols need
 * different amounts of headroom. [HEADER_ALLOWANCE] is a deliberately generous,
 * one-size-fits-all estimate (comfortably covers WireGuard's ~60 bytes, MASQUE/QUIC
 * and Hysteria's own framing) subtracted from the probed payload size before it is
 * offered as an MTU - it trades a few bytes of headroom for one number that is safe
 * for whichever backend is actually in use, rather than a separate measurement per
 * protocol.
 */
object MtuProbe {

    private const val HEADER_ALLOWANCE = 100
    private const val SOCKET_TIMEOUT_MS = 1200
    private const val TAG = "mtu-probe"

    sealed interface Result {
        /** Already clamped to [ConnectionProfile.MTU_MIN]..[ConnectionProfile.MTU_MAX]. */
        data class Found(val mtu: Int) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Probes the path to [host]:[port] and returns a suggested TUN MTU, or why it
     * could not. Runs entirely on [Dispatchers.IO]; safe to call from the main thread.
     * Never throws.
     */
    suspend fun probe(host: String, port: Int): Result = withContext(Dispatchers.IO) {
        val address = try {
            InetSocketAddress(host, port)
        } catch (e: Exception) {
            return@withContext Result.Failed(e.message ?: "could not resolve $host")
        }
        if (address.isUnresolved) return@withContext Result.Failed("could not resolve $host")

        val socket = try {
            DatagramSocket().apply { soTimeout = SOCKET_TIMEOUT_MS }
        } catch (e: IOException) {
            return@withContext Result.Failed(e.message ?: "could not open a probe socket")
        }

        try {
            val minPayload = ConnectionProfile.MTU_MIN - HEADER_ALLOWANCE
            val maxPayload = ConnectionProfile.MTU_MAX - HEADER_ALLOWANCE
            // The floor itself must get through - if even that fails, the path is
            // down or filtering this traffic entirely, not merely narrow.
            if (!fits(socket, address, minPayload)) {
                return@withContext Result.Failed("the path did not accept even the smallest size")
            }
            var low = minPayload
            var high = maxPayload
            while (high - low > 8) {
                val mid = low + (high - low) / 2
                if (fits(socket, address, mid)) low = mid else high = mid
            }
            val mtu = (low + HEADER_ALLOWANCE).coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX)
            Result.Found(mtu)
        } finally {
            socket.close()
        }
    }

    /**
     * True when the OS accepted a [payloadSize]-byte send toward [address] without
     * complaining it was too large. [data] is random padding, not a real protocol
     * packet, so even a send that does arrive has nothing a server needs to parse.
     */
    private fun fits(socket: DatagramSocket, address: InetSocketAddress, payloadSize: Int): Boolean {
        if (payloadSize <= 0) return true
        val data = Random.nextBytes(payloadSize)
        return try {
            socket.send(DatagramPacket(data, data.size, address))
            true
        } catch (e: SocketException) {
            DiagnosticsLog.d(TAG, "size $payloadSize rejected: ${e.message}")
            false
        } catch (e: IOException) {
            DiagnosticsLog.d(TAG, "size $payloadSize failed: ${e.message}")
            false
        }
    }
}
