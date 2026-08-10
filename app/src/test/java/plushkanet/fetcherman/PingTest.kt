package plushkanet.fetcherman

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PingTest {

    @Test
    fun extractHostStripsSchemePathPortAndQuery() {
        assertEquals("example.com", Ping.extractHost("https://example.com/path?q=1#frag"))
        assertEquals("example.com", Ping.extractHost("http://example.com"))
        assertEquals("example.com", Ping.extractHost("example.com:8080"))
        assertEquals("example.com", Ping.extractHost("  https://example.com/  "))
        assertEquals("8.8.8.8", Ping.extractHost("8.8.8.8"))
        assertEquals("127.0.0.1", Ping.extractHost("http://127.0.0.1:8080/foo"))
        assertEquals("::1", Ping.extractHost("::1"))
    }

    @Test
    fun extractHostHandlesBracketedIPv6() {
        assertEquals("::1", Ping.extractHost("http://[::1]:8080/"))
        assertEquals("2001:db8::1", Ping.extractHost("https://[2001:db8::1]:8443/path?q=1"))
        assertEquals("::1", Ping.extractHost("[::1]"))
    }

    @Test
    fun extractHostStripsUserinfo() {
        assertEquals("example.com", Ping.extractHost("https://user:pass@example.com/"))
        assertEquals("::1", Ping.extractHost("https://user:pass@[::1]:8080/"))
    }

    @Test
    fun extractHostReturnsBlankForEmptyInput() {
        assertEquals("", Ping.extractHost(""))
        assertEquals("", Ping.extractHost("   "))
        assertEquals("", Ping.extractHost("https://"))
    }

    @Test
    fun pingBlankUrlReturnsUnreachable() = runBlocking {
        assertEquals(Ping.PingResult.Unreachable, Ping.ping(""))
        assertEquals(Ping.PingResult.Unreachable, Ping.ping("   "))
    }

    @Test
    fun pingUnknownHostReturnsUnreachable() = runBlocking {
        assertEquals(Ping.PingResult.Unreachable, Ping.ping("nonexistent.invalid", 1.0))
    }

    @Test
    fun pingBlackholeHostReturnsTimeout() = runBlocking {
        assertEquals(Ping.PingResult.Timeout, Ping.ping("192.0.2.1", 1.0))
    }

    @Test
    fun pingLocalhostReturnsRtt() = runBlocking {
        val rtt = Ping.ping("127.0.0.1", 2.0)

        assertTrue("localhost should be reachable: $rtt", rtt is Ping.PingResult.Success)
        assertTrue(
            "unexpected rtt: $rtt",
            rtt is Ping.PingResult.Success && rtt.rttSec > 0.0 && rtt.rttSec < 5.0,
        )
    }

    @Test
    fun pingUrlWithPathExtractsHost() = runBlocking {
        val rtt = Ping.ping("http://127.0.0.1:8080/foo", 2.0)

        assertTrue(
            "host should be extracted from the URL: $rtt",
            rtt is Ping.PingResult.Success,
        )
    }
}