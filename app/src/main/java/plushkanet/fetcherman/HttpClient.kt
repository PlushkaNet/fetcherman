package plushkanet.fetcherman

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import org.json.JSONTokener

data class NetworkResponse(
    val error: Boolean,
    val text: String,
    val headers: String,
    val raw: ByteArray,
    val contentType: String?,
)

object HttpClient {
    private const val TIMEOUT_MS = 5000
    private val statusLine = Regex("""^HTTP/\d(?:\.\d)?\s+(\d{3})""")

    suspend fun request(method: String, url: String, body: String?): NetworkResponse =
        withContext(Dispatchers.IO) {
            try {
                if (method == "CONNECT") {
                    return@withContext connectTunnel(url)
                }
                body?.takeIf { it.isNotBlank() }?.let {
                    JSONTokener(it).nextValue()
                }
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = false
                    if (body != null && body.isNotBlank()) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
                try {
                    if (body != null && body.isNotBlank()) {
                        connection.outputStream.use { out ->
                            out.write(body.toByteArray(Charsets.UTF_8))
                        }
                    }
                    val status = connection.responseCode
                    val stream = if (status >= 400) connection.errorStream else connection.inputStream
                    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                    NetworkResponse(
                        error = false,
                        text = decode(bytes, connection.contentType),
                        headers = headers(connection),
                        raw = bytes,
                        contentType = connection.contentType,
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                NetworkResponse(
                    error = true,
                    text = "exception occured: $e",
                    headers = "",
                    raw = ByteArray(0),
                    contentType = null,
                )
            }
        }

    private fun connectTunnel(url: String): NetworkResponse {
        val uri = URI(url)
        val host = uri.host?.removeSurrounding("[", "]")
        if (host.isNullOrBlank()) {
            return NetworkResponse(
                error = true,
                text = "exception occured: no host in $url",
                headers = "",
                raw = ByteArray(0),
                contentType = null,
            )
        }
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        val target = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        val request = "CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n"
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val headerBytes = ByteArrayOutputStream()
            val input = socket.getInputStream()
            var done = false
            while (!done) {
                val b = input.read()
                if (b == -1) break
                headerBytes.write(b)
                val bytes = headerBytes.toByteArray()
                done = bytes.size >= 4 &&
                    bytes[bytes.size - 4] == '\r'.code.toByte() &&
                    bytes[bytes.size - 3] == '\n'.code.toByte() &&
                    bytes[bytes.size - 2] == '\r'.code.toByte() &&
                    bytes[bytes.size - 1] == '\n'.code.toByte()
            }
            val headerText = headerBytes.toByteArray().toString(Charsets.ISO_8859_1)
            val status = statusLine.find(headerText)?.groupValues?.get(1)?.toIntOrNull()
            NetworkResponse(
                error = status == null,
                text = headerText,
                headers = headerText,
                raw = headerBytes.toByteArray(),
                contentType = null,
            )
        }
    }

    private fun decode(bytes: ByteArray, contentType: String?): String {
        val name = contentType
            ?.let { Regex("charset\\s*=\\s*[\"']?([\\w.-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        return try {
            bytes.toString(Charset.forName(name ?: "UTF-8"))
        } catch (e: Exception) {
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun headers(connection: HttpURLConnection): String {
        return connection.headerFields
            .filterKeys { it != null }
            .flatMap { (key, values) -> values.map { "$key: $it" } }
            .joinToString("\n")
    }
}
