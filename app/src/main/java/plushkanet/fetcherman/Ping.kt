package plushkanet.fetcherman

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Primary path is a real ICMP echo request over an unprivileged ping socket
 * (`SOCK_DGRAM` + `IPPROTO_ICMP`), implemented in native code. If the socket
 * cannot be created or the send fails (restricted SELinux policies, no IPv6
 * route), falls back to `InetAddress.isReachable()`.
 */
object Ping {
    @JvmStatic
    private external fun nativePing(ip: String, family: Int, timeoutSec: Double): Double

    private const val RESULT_TIMEOUT = -1.0
    private const val RESULT_UNREACHABLE = -2.0
    private const val RESULT_SOCKET_FAIL = -3.0

    private val nativeAvailable: Boolean = try {
        System.loadLibrary("ping")
        true
    } catch (t: Throwable) {
        false
    }

    sealed interface PingResult {
        data class Success(val rttSec: Double) : PingResult
        data object Timeout : PingResult
        data object Unreachable : PingResult
    }

    /** Returns RTT in seconds, or timeout/unreachable outcome. */
    suspend fun ping(url: String, timeoutSec: Double = 4.0): PingResult =
        withContext(Dispatchers.IO) {
            val host = extractHost(url)
            if (host.isBlank()) return@withContext PingResult.Unreachable

            val addr = try {
                InetAddress.getByName(host)
            } catch (e: Exception) {
                return@withContext PingResult.Unreachable
            }

            val ip = addr.hostAddress ?: return@withContext PingResult.Unreachable
            val family = if (addr is Inet6Address) 6 else 4
            val nativeRtt = if (nativeAvailable) {
                try {
                    nativePing(ip, family, timeoutSec)
                } catch (e: Exception) {
                    null
                }
            } else null
            when {
                nativeRtt != null && nativeRtt > 0 ->
                    PingResult.Success(nativeRtt / 1000.0)
                nativeRtt == RESULT_TIMEOUT ->
                    PingResult.Timeout
                nativeRtt == RESULT_UNREACHABLE ->
                    PingResult.Unreachable
                nativeRtt == RESULT_SOCKET_FAIL || nativeRtt == null ->
                    // Fallback: TCP connect to the echo port. isReachable cannot
                    // distinguish "unreachable" from "timeout", so use elapsed
                    // time as a heuristic: burning the whole budget looks like
                    // a timeout, failing early is an unreachable host.
                    isReachable(addr, timeoutSec)
                else ->
                    PingResult.Unreachable
            }
        }

    /** Extracts a hostname from a URL or bare address, e.g. "https://a.b/c" -> "a.b". */
    fun extractHost(url: String): String {
        var s = url.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (end >= 0) s = s.substring(0, end)
        val at = s.lastIndexOf('@')
        if (at >= 0) s = s.substring(at + 1)
        if (s.startsWith("[")) {
            return s.substringAfter("[").substringBefore("]")
        }
        val colon = s.indexOf(':')
        if (colon > 0 && s.substring(colon + 1).all { it.isDigit() }) {
            s = s.substring(0, colon)
        }
        return s.trim()
    }

    private fun isReachable(addr: InetAddress, timeoutSec: Double): PingResult {
        return try {
            val start = System.nanoTime()
            val ok = addr.isReachable((timeoutSec * 1000).toInt())
            val elapsed = (System.nanoTime() - start) / 1e9
            when {
                ok -> PingResult.Success(elapsed)
                elapsed >= timeoutSec -> PingResult.Timeout
                else -> PingResult.Unreachable
            }
        } catch (e: Exception) {
            PingResult.Unreachable
        }
    }
}