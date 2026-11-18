package com.example.ui.screens

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.AskScreenApp
import com.example.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun WorkServiceWorkspaceDialog(productId: String, productName: String, onDismiss: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val http = remember { OkHttpClient() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var attachment by remember { mutableStateOf<JSONObject?>(null) }
    var attachmentName by remember { mutableStateOf<String?>(null) }
    var attachmentMime by remember { mutableStateOf<String?>(null) }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                val mime = context.contentResolver.getType(uri)?.substringBefore(';')?.lowercase()
                    ?: throw IllegalStateException("The selected file type could not be identified.")
                val allowed = mime.startsWith("image/") || mime.startsWith("audio/") || mime == "application/pdf" || mime == "text/plain"
                if (!allowed) throw IllegalStateException("Choose an image, audio file, PDF or plain-text file. Video upload is not enabled yet.")
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("The selected file could not be opened.")
                }
                if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) throw IllegalStateException("Choose a file smaller than 2 MB.")
                val name = uri.lastPathSegment?.substringAfterLast('/')?.take(100) ?: "Selected file"
                attachment = JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                attachmentName = name
                attachmentMime = mime
            } catch (e: Exception) {
                error = e.message ?: "The selected file could not be attached."
            } finally { busy = false }
        }
    }
    var task by remember { mutableStateOf("") }
    var contextText by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }

    suspend fun request(method: String, payload: JSONObject? = null): JSONObject {
        val user = auth.currentUser ?: throw IllegalStateException("Please sign in to use this workspace.")
        val token = user.getIdToken(true).await().token ?: throw IllegalStateException("Your sign-in could not be verified.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("The AskScreen backend URL is missing or incorrect. Open Settings and check it.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/work-service?productId=" + productId
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(endpoint).header("Authorization", "Bearer " + token)
            if (method == "GET") builder.get() else builder.post(
                (payload ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "The service could not complete the request. Please try again." })
                json
            }
        }
    }

    LaunchedEffect(productId) {
        try {
            val data = request("GET")
            val jobs = data.optJSONArray("jobs") ?: JSONArray()
            history = (0 until jobs.length()).mapNotNull { i ->
                jobs.optJSONObject(i)?.optString("result")?.takeIf { !it.isNullOrBlank() }
            }.take(8)
        } catch (e: Exception) { error = e.message }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.large,
            color = Slate950
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(productName, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("WORKSPACE", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text("Close") }
                }
                Text("Describe the actual work you need completed. The service will process it and save the result to your account history.", color = Slate300, fontSize = 12.sp)
                OutlinedTextField(
                    value = task, onValueChange = { task = it.take(8000) },
                    modifier = Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text("What should this service do?") },
                    placeholder = { Text("Enter the task and the result you expect…") }, enabled = !busy
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { uploadPicker.launch(arrayOf("image/*", "audio/*", "application/pdf", "text/plain")) }, enabled = !busy) {
                        Text(if (attachmentName == null) "Attach file" else "Replace file")
                    }
                    if (attachmentName != null) {
                        TextButton(onClick = { attachment = null; attachmentName = null; attachmentMime = null }, enabled = !busy) { Text("Remove") }
                    }
                }
                attachmentName?.let { Text("Attached: $it (${attachmentMime.orEmpty()})", color = ElectricCyan, fontSize = 11.sp) }
                Text("Image, audio, PDF and plain-text uploads are supported up to 2 MB per request. Video upload is not enabled yet.", color = Slate400, fontSize = 10.sp)
                OutlinedTextField(
                    value = contextText, onValueChange = { contextText = it.take(24000) },
                    modifier = Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text("Supporting information (optional)") },
                    placeholder = { Text("Paste text, customer details, data, instructions or source material…") }, enabled = !busy
                )
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            result = ""
                            try {
                                val payload = JSONObject().put("productId", productId).put("task", task.trim()).put("context", contextText.trim())
                                attachment?.let { file ->
                                    val mime = attachmentMime.orEmpty()
                                    when {
                                        mime.startsWith("image/") -> payload.put("images", JSONArray().put(file))
                                        mime.startsWith("audio/") -> payload.put("audio", file)
                                        else -> payload.put("files", JSONArray().put(file))
                                    }
                                }
                                val response = request("POST", payload)
                                result = response.optJSONObject("job")?.optString("result").orEmpty()
                                if (result.isBlank()) throw IllegalStateException("The service returned an empty result.")
                                history = listOf(result) + history
                            } catch (e: Exception) {
                                error = e.message ?: "The task could not be completed."
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy && task.isNotBlank(), modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Working…" else "Perform this work") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = AmberWarning, fontSize = 12.sp) }
                if (result.isNotBlank()) {
                    HorizontalDivider(color = Slate700)
                    Text("COMPLETED RESULT", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(result, color = Color.White, fontSize = 13.sp)
                }
                if (history.isNotEmpty()) {
                    HorizontalDivider(color = Slate700)
                    Text("RECENT SAVED RESULTS", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    history.forEachIndexed { i, item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Result " + (i + 1), color = Slate300, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                Text(item, color = Color.White, fontSize = 12.sp, maxLines = 8)
                            }
                        }
                    }
                }
            }
        }
    }
}
