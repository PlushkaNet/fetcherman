package plushkanet.fetcherman

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

data class NetworkResponse(
    val error: Boolean,
    val text: String,
    val headers: String,
    val raw: ByteArray,
    val contentType: String?,
)

object HttpClient {
    private const val TIMEOUT_MS = 5000

    suspend fun request(method: String, url: String, body: String?): NetworkResponse =
        withContext(Dispatchers.IO) {
            try {
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
