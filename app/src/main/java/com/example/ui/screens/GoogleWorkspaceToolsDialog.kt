package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
fun GoogleWorkspaceToolsDialog(onDismiss: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val http = remember { OkHttpClient() }
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf("Gmail") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf<List<String>>(emptyList()) }
    var to by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var emailBody by remember { mutableStateOf("") }
    var eventTitle by remember { mutableStateOf("") }
    var eventDescription by remember { mutableStateOf("") }
    var eventStart by remember { mutableStateOf("") }
    var eventEnd by remember { mutableStateOf("") }
    var confirmSend by remember { mutableStateOf(false) }

    suspend fun request(action: String, method: String, payload: JSONObject? = null): JSONObject {
        val user = auth.currentUser ?: throw IllegalStateException("Please sign in again.")
        val token = user.getIdToken(true).await().token ?: throw IllegalStateException("Your sign-in could not be verified.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Check the AskScreen backend URL in Settings.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/google-workspace?action=" + action
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(endpoint).header("Authorization", "Bearer " + token)
            if (method == "GET") builder.get()
            else builder.post((payload ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                if (!response.isSuccessful || !json.optBoolean("success")) {
                    val code = json.optString("error").ifBlank { "GOOGLE_ACTION_FAILED" }
                    val friendly = when (code) {
                        "GOOGLE_NOT_CONNECTED" -> "Connect your Google account first."
                        "GOOGLE_RECONNECT_REQUIRED" -> "Google needs you to reconnect your account."
                        "GOOGLE_SCOPE_MISSING" -> "Google did not grant all required permissions. Disconnect and reconnect."
                        "GOOGLE_PERMISSION_DENIED" -> "Google denied this action. Check the permissions granted to DomAskScreenAI."
                        else -> code.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                    }
                    throw IllegalStateException(friendly)
                }
                json
            }
        }
    }

    fun runAction(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            try { action() }
            catch (e: Exception) { message = e.message ?: "Google could not complete this action." }
            finally { busy = false }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.large,
            color = Slate950
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("GOOGLE WORKSPACE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Gmail and Google Calendar", color = ElectricCyan, fontSize = 11.sp)
                    }
                    TextButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) { Text("Close") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = section == "Gmail", onClick = { section = "Gmail"; message = null; output = emptyList() }, label = { Text("Gmail") })
                    FilterChip(selected = section == "Calendar", onClick = { section = "Calendar"; message = null; output = emptyList() }, label = { Text("Calendar") })
                }

                if (section == "Gmail") {
                    Text("RECENT INBOX", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    OutlinedButton(onClick = {
                        runAction {
                            val data = request("gmail-list", "GET")
                            val messages = data.optJSONArray("messages") ?: JSONArray()
                            output = (0 until messages.length()).mapNotNull { i ->
                                val item = messages.optJSONObject(i) ?: return@mapNotNull null
                                listOf(item.optString("from"), item.optString("subject"), item.optString("snippet")).filter { it.isNotBlank() }.joinToString("\n")
                            }
                            message = "Loaded " + output.size + " recent messages."
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Load recent emails") }
                    output.forEach { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth()) {
                            Text(item, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
                        }
                    }
                    HorizontalDivider(color = Slate700)
                    Text("WRITE AN EMAIL", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    OutlinedTextField(value = to, onValueChange = { to = it.take(254) }, modifier = Modifier.fillMaxWidth(), label = { Text("Recipient email") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(value = subject, onValueChange = { subject = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Subject") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(value = emailBody, onValueChange = { emailBody = it.take(100000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Email message") }, minLines = 4, enabled = !busy)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runAction {
                                request("gmail-draft", "POST", JSONObject().put("to", to).put("subject", subject).put("body", emailBody))
                                message = "Email draft saved in your Gmail Drafts."
                            }
                        }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Save draft") }
                        Button(onClick = { confirmSend = true }, enabled = !busy && to.isNotBlank() && subject.isNotBlank() && emailBody.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Send email") }
                    }
                } else {
                    Text("UPCOMING EVENTS", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    OutlinedButton(onClick = {
                        runAction {
                            val data = request("calendar-list", "GET")
                            val events = data.optJSONArray("events") ?: JSONArray()
                            output = (0 until events.length()).mapNotNull { i ->
                                val item = events.optJSONObject(i) ?: return@mapNotNull null
                                item.optString("summary") + "\n" + item.optString("start") + " – " + item.optString("end")
                            }
                            message = "Loaded " + output.size + " upcoming events."
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Load upcoming events") }
                    output.forEach { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth()) {
                            Text(item, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
                        }
                    }
                    HorizontalDivider(color = Slate700)
                    Text("CREATE A CALENDAR EVENT", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    OutlinedTextField(value = eventTitle, onValueChange = { eventTitle = it.take(200) }, modifier = Modifier.fillMaxWidth(), label = { Text("Event title") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(value = eventDescription, onValueChange = { eventDescription = it.take(5000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, minLines = 2, enabled = !busy)
                    OutlinedTextField(value = eventStart, onValueChange = { eventStart = it.take(40) }, modifier = Modifier.fillMaxWidth(), label = { Text("Start (ISO date/time)") }, placeholder = { Text("2026-11-20T10:00:00+01:00") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(value = eventEnd, onValueChange = { eventEnd = it.take(40) }, modifier = Modifier.fillMaxWidth(), label = { Text("End (ISO date/time)") }, placeholder = { Text("2026-11-20T11:00:00+01:00") }, singleLine = true, enabled = !busy)
                    Button(onClick = {
                        runAction {
                            val data = request("calendar-create", "POST", JSONObject()
                                .put("summary", eventTitle).put("description", eventDescription)
                                .put("start", eventStart).put("end", eventEnd))
                            val event = data.optJSONObject("event") ?: JSONObject()
                            message = "Calendar event created: " + event.optString("summary")
                            eventTitle = ""; eventDescription = ""; eventStart = ""; eventEnd = ""
                        }
                    }, enabled = !busy && eventTitle.isNotBlank() && eventStart.isNotBlank() && eventEnd.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Create calendar event") }
                }

                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = if (it.contains("saved") || it.contains("created") || it.contains("Loaded")) ElectricCyan else AmberWarning, fontSize = 12.sp) }
                Text("Sending an email or creating an event changes your Google account. Review the details before tapping the action button.", color = Slate400, fontSize = 10.sp)
            }
        }
    }

    if (confirmSend) {
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send this email?") },
            text = { Text("This will send the message from your connected Gmail account to $to. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSend = false
                    runAction {
                        request("gmail-send", "POST", JSONObject().put("to", to).put("subject", subject).put("body", emailBody))
                        message = "Email sent successfully."
                        to = ""; subject = ""; emailBody = ""
                    }
                }) { Text("Send now") }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } }
        )
    }
}
