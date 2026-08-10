package plushkanet.fetcherman

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.Charset

class HttpClientTest {

    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    private fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path, handler)
    }

    private fun respond(ex: HttpExchange, status: Int, bytes: ByteArray, vararg headers: Pair<String, String>) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun getReturnsBodyAndHeaders() = runBlocking {
        route("/hello") { ex ->
            respond(
                ex, 200, "hello world".toByteArray(),
                "Content-Type" to "text/plain; charset=UTF-8",
                "X-Test-Header" to "yes",
            )
        }

        val response = HttpClient.request("GET", "$baseUrl/hello", null)

        assertFalse(response.error)
        assertEquals("hello world", response.text)
        assertEquals("text/plain; charset=UTF-8", response.contentType)
        assertEquals("hello world", response.raw.toString(Charsets.UTF_8))
        assertTrue(response.headers.lowercase().contains("content-type: text/plain; charset=utf-8"))
        assertTrue(response.headers.lowercase().contains("x-test-header: yes"))
    }

    @Test
    fun getWithQueryStringWorks() = runBlocking {
        var path = ""
        route("/query") { ex ->
            path = ex.requestURI.toString()
            ex.sendResponseHeaders(200, -1)
        }

        val response = HttpClient.request("GET", "$baseUrl/query?a=1&b=2", null)

        assertFalse(response.error)
        assertEquals("/query?a=1&b=2", path)
    }

    @Test
    fun postSendsJsonBody() = runBlocking {
        var receivedBody = ""
        var receivedContentType: String? = null
        route("/echo") { ex ->
            receivedBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            receivedContentType = ex.requestHeaders.getFirst("Content-Type")
            respond(ex, 200, receivedBody.toByteArray())
        }
        val json = """{"key":"value","n":42}"""

        val response = HttpClient.request("POST", "$baseUrl/echo", json)

        assertFalse(response.error)
        assertEquals(json, receivedBody)
        assertEquals("application/json", receivedContentType)
        assertEquals(json, response.text)
    }

    @Test
    fun putWithBodyWorks() = runBlocking {
        var receivedMethod = ""
        var receivedBody = ""
        route("/put") { ex ->
            receivedMethod = ex.requestMethod
            receivedBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(204, -1)
        }
        val body = """{"a":1}"""

        val response = HttpClient.request("PUT", "$baseUrl/put", body)

        assertFalse(response.error)
        assertEquals("PUT", receivedMethod)
        assertEquals(body, receivedBody)
    }

    @Test
    fun deleteWithoutBodyWorks() = runBlocking {
        var receivedMethod = ""
        route("/delete") { ex ->
            receivedMethod = ex.requestMethod
            ex.sendResponseHeaders(200, -1)
        }

        val response = HttpClient.request("DELETE", "$baseUrl/delete", null)

        assertFalse(response.error)
        assertEquals("DELETE", receivedMethod)
    }

    @Test
    fun headRequestWorks() = runBlocking {
        var receivedMethod = ""
        route("/head") { ex ->
            receivedMethod = ex.requestMethod
            ex.sendResponseHeaders(200, -1)
        }

        val response = HttpClient.request("HEAD", "$baseUrl/head", null)

        assertFalse(response.error)
        assertEquals("HEAD", receivedMethod)
    }

    @Test
    fun blankBodyIsTreatedAsNoBody() = runBlocking {
        var receivedBody = ""
        route("/blank") { ex ->
            receivedBody = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            ex.sendResponseHeaders(200, -1)
        }

        val response = HttpClient.request("POST", "$baseUrl/blank", "   ")

        assertFalse(response.error)
        assertEquals("", receivedBody)
    }

    @Test
    fun httpErrorBodyIsReturnedWithoutErrorFlag() = runBlocking {
        route("/missing") { ex ->
            respond(ex, 404, "not found".toByteArray())
        }

        val response = HttpClient.request("GET", "$baseUrl/missing", null)

        assertFalse(response.error)
        assertEquals("not found", response.text)
    }

    @Test
    fun serverErrorIsReturnedWithoutErrorFlag() = runBlocking {
        route("/crash") { ex ->
            respond(ex, 500, "boom".toByteArray())
        }

        val response = HttpClient.request("GET", "$baseUrl/crash", null)

        assertFalse(response.error)
        assertEquals("boom", response.text)
    }

    @Test
    fun redirectIsNotFollowed() = runBlocking {
        route("/redirect") { ex ->
            respond(ex, 302, ByteArray(0), "Location" to "/target")
        }

        val response = HttpClient.request("GET", "$baseUrl/redirect", null)

        assertFalse(response.error)
        assertTrue(response.headers.lowercase().contains("location: /target"))
    }

    @Test
    fun charsetFromContentTypeIsRespected() = runBlocking {
        val text = "héllo wörld"
        val bytes = text.toByteArray(Charset.forName("ISO-8859-1"))
        route("/latin") { ex ->
            respond(ex, 200, bytes, "Content-Type" to "text/plain; charset=ISO-8859-1")
        }

        val response = HttpClient.request("GET", "$baseUrl/latin", null)

        assertFalse(response.error)
        assertEquals(text, response.text)
        assertEquals("text/plain; charset=ISO-8859-1", response.contentType)
    }

    @Test
    fun utf8IsDefaultWhenNoCharsetGiven() = runBlocking {
        val text = "crème brûlée"
        route("/plain") { ex ->
            respond(ex, 200, text.toByteArray(Charsets.UTF_8), "Content-Type" to "text/plain")
        }

        val response = HttpClient.request("GET", "$baseUrl/plain", null)

        assertFalse(response.error)
        assertEquals(text, response.text)
    }

    @Test
    fun unknownCharsetFallsBackToUtf8() = runBlocking {
        val text = "crème brûlée"
        route("/bogus-charset") { ex ->
            respond(
                ex, 200, text.toByteArray(Charsets.UTF_8),
                "Content-Type" to "text/plain; charset=no-such-charset",
            )
        }

        val response = HttpClient.request("GET", "$baseUrl/bogus-charset", null)

        assertFalse(response.error)
        assertEquals(text, response.text)
    }

    @Test
    fun emptyBodyResponseWorks() = runBlocking {
        route("/empty") { ex ->
            ex.sendResponseHeaders(204, -1)
        }

        val response = HttpClient.request("GET", "$baseUrl/empty", null)

        assertFalse(response.error)
        assertEquals("", response.text)
        assertEquals(0, response.raw.size)
    }

    @Test
    fun invalidJsonBodyYieldsErrorResponse() = runBlocking {
        val response = HttpClient.request("POST", "$baseUrl/any", "{not valid json")

        assertTrue(response.error)
        assertTrue(response.text.startsWith("exception occured"))
        assertEquals(0, response.raw.size)
    }

    @Test
    fun connectionRefusedYieldsErrorResponse() = runBlocking {
        val closed = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val port = closed.localPort
        closed.close()

        val response = HttpClient.request("GET", "http://127.0.0.1:$port/", null)

        assertTrue(response.error)
        assertTrue(response.text.contains("exception"))
        assertEquals("", response.headers)
    }

    @Test
    fun malformedUrlYieldsErrorResponse() = runBlocking {
        val response = HttpClient.request("GET", "not a url", null)

        assertTrue(response.error)
        assertTrue(response.text.contains("exception"))
    }
}
