package plushkanet.fetcherman

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val MAX_DIALOG_CHARS = 50_000

sealed interface ResponseState {
    data object Idle : ResponseState
    data object Loading : ResponseState
    data class Message(val message: String) : ResponseState
    data class Success(val response: NetworkResponse) : ResponseState
}

@Composable
fun FetchermanScreen(darkTheme: Boolean, onToggleTheme: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("GET") }
    var dialogContent by remember { mutableStateOf<String?>(null) }
    var saveFormat by rememberSaveable { mutableStateOf(SaveFormat.TXT) }
    val viewModel: FetchermanViewModel = viewModel()
    val state = viewModel.state
    val context = LocalContext.current
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            val response = (viewModel.state as? ResponseState.Success)?.response
            if (response != null) {
                viewModel.save(context, uri, saveFormat, response)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (darkTheme) MaterialTheme.colorScheme.primary else Color.Black,
            )
            IconButton(onClick = onToggleTheme) {
                Icon(
                    imageVector = if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    contentDescription = stringResource(
                        if (darkTheme) R.string.toggle_theme_light else R.string.toggle_theme_dark,
                    ),
                    tint = if (darkTheme) Color.White else Color.Black,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.url_label)) },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (method !in setOf("GET", "HEAD", "TRACE", "CONNECT")) {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.data_label)) },
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        MethodDropdown(method, Modifier.fillMaxWidth()) { method = it }
        Spacer(Modifier.height(8.dp))
        UIBtn(stringResource(R.string.request_button), enabled = state !is ResponseState.Loading) {
            viewModel.request(method, url, body.ifBlank { null })
        }
        Spacer(Modifier.height(4.dp))
        UIBtn(stringResource(R.string.ping_button), enabled = state !is ResponseState.Loading) {
            viewModel.ping(url)
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        when (val s = state) {
            ResponseState.Idle -> Text(
                stringResource(R.string.no_response_yet),
                color = MaterialTheme.colorScheme.onBackground,
            )
            ResponseState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ResponseState.Message -> Text(s.message, color = MaterialTheme.colorScheme.onBackground)
            is ResponseState.Success -> Column(Modifier.fillMaxWidth()) {
                UIBtn(stringResource(R.string.txt_button)) {
                    dialogContent = s.response.text.take(MAX_DIALOG_CHARS)
                }
                Spacer(Modifier.height(4.dp))
                UIBtn(stringResource(R.string.headers_button)) {
                    dialogContent = s.response.headers.take(MAX_DIALOG_CHARS)
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FormatDropdown(saveFormat, Modifier.weight(1f)) { saveFormat = it }
                    Button(
                        onClick = {
                            val ext = if (saveFormat == SaveFormat.RAW) {
                                MimeTypeMap.getSingleton()
                                    .getExtensionFromMimeType(s.response.contentType)
                                    ?: "bin"
                            } else {
                                saveFormat.extension
                            }
                            saveLauncher.launch("response.$ext")
                        },
                        shape = RoundedCornerShape(0.dp),
                    ) {
                        Text(stringResource(R.string.save_button))
                    }
                }
            }
        }
        }
    }

    dialogContent?.let { content ->
        AlertDialog(
            onDismissRequest = { dialogContent = null },
            confirmButton = {
                TextButton(onClick = { dialogContent = null }) { Text(stringResource(R.string.ok_button)) }
            },
            text = {
                Text(
                    content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}

@Composable
private fun UIBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MethodDropdown(selected: String, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.method_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                "GET", "POST", "PUT", "OPTIONS", "DELETE", "PATCH", "HEAD", "TRACE", "CONNECT",
            ).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

enum class SaveFormat(val label: String, val extension: String) {
    RAW("RAW", "bin"),
    TXT("TXT", "txt"),
    JSON("JSON", "json"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatDropdown(
    selected: SaveFormat,
    modifier: Modifier = Modifier,
    onSelect: (SaveFormat) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.format_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SaveFormat.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

suspend fun saveTo(
    context: Context,
    uri: Uri,
    format: SaveFormat,
    response: NetworkResponse,
): String = withContext(Dispatchers.IO) {
    try {
        val ok = context.contentResolver.openOutputStream(uri)?.use { out ->
            when (format) {
                SaveFormat.RAW -> out.write(response.raw)
                SaveFormat.TXT -> out.write(response.text.toByteArray(Charsets.UTF_8))
                SaveFormat.JSON -> out.write(prettyJson(response.text).toByteArray(Charsets.UTF_8))
            }
            true
        } ?: false
        if (ok) {
            context.getString(R.string.saved_to, uri.toString())
        } else {
            context.getString(R.string.save_failed_open_stream)
        }
    } catch (e: Exception) {
        context.getString(R.string.save_failed, e.message ?: e.toString())
    }
}

private fun prettyJson(text: String): String {
    return try {
        when (val value = JSONTokener(text).nextValue()) {
            is JSONObject -> value.toString(4)
            is JSONArray -> value.toString(4)
            else -> text
        }
    } catch (e: Exception) {
        text
    }
}