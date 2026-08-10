package plushkanet.fetcherman

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FetchermanViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf<ResponseState>(ResponseState.Idle)
        private set

    private var job: Job? = null

    fun request(method: String, url: String, body: String?) {
        job?.cancel()
        job = viewModelScope.launch {
            state = ResponseState.Loading
            val response = HttpClient.request(method, url, body)
            state = if (response.error) {
                ResponseState.Message(response.text)
            } else {
                ResponseState.Success(response)
            }
        }
    }

    fun ping(url: String) {
        job?.cancel()
        job = viewModelScope.launch {
            state = ResponseState.Loading
            state = pingMessage(url)
        }
    }

    fun save(context: Context, uri: Uri, format: SaveFormat, response: NetworkResponse) {
        job?.cancel()
        job = viewModelScope.launch {
            state = ResponseState.Message(saveTo(context, uri, format, response))
        }
    }

    private suspend fun pingMessage(url: String): ResponseState.Message {
        val context = getApplication<Application>()
        if (url.isBlank()) {
            return ResponseState.Message(context.getString(R.string.address_must_not_be_empty))
        }
        return when (val result = Ping.ping(url)) {
            is Ping.PingResult.Success -> ResponseState.Message(
                context.getString(R.string.got_response_in_ms, (result.rttSec * 1000).toInt()),
            )
            Ping.PingResult.Timeout ->
                ResponseState.Message(context.getString(R.string.ping_timeout))
            Ping.PingResult.Unreachable ->
                ResponseState.Message(context.getString(R.string.server_unreachable))
        }
    }
}