package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.json.JSONObject

private const val SUPPORT_EMAIL = "Dominiontools56@gmail.com"
private const val SUPPORT_WHATSAPP = "2347083299414"
private const val SUPPORT_PHONE = "+2347052520118"

private data class SupportTicket(val id:String,val subject:String,val message:String,val status:String,val reply:String)

@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val scope = rememberCoroutineScope()
    val http = remember { OkHttpClient() }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var tickets by remember { mutableStateOf<List<SupportTicket>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun launchSupportIntent(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No suitable app was found on this device.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open the selected support option.", Toast.LENGTH_LONG).show()
        }
    }

    suspend fun requestTickets(method:String, payload:JSONObject? = null):JSONObject {
        val user = auth.currentUser ?: throw IllegalStateException("Sign in to your account to use in-app support tickets.")
        user.reload().await()
        if (auth.currentUser?.isEmailVerified != true) throw IllegalStateException("Please verify your email address before submitting a support ticket.")
        val token = user.getIdToken(true).await().token ?: throw IllegalStateException("Could not verify your account.")
        val base = prefs.backendUrl.trim().trimEnd('/')
        if (!base.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val url = base.removeSuffix("/ask-screen") + "/support-tickets"
        return withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
            if (method == "POST") builder.post((payload ?: JSONObject()).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            else builder.get()
            http.newCall(builder.build()).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = try { JSONObject(raw) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Support request failed (HTTP ${response.code})." })
                json
            }
        }
    }

    fun refreshTickets() {
        scope.launch {
            busy = true
            try {
                val result = requestTickets("GET")
                val array = result.optJSONArray("tickets")
                tickets = buildList {
                    if (array != null) for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        add(SupportTicket(item.optString("id"),item.optString("subject"),item.optString("message"),item.optString("status","open"),item.optString("reply")))
                    }
                }
                statusMessage = "Your support requests are up to date."
            } catch (e:Exception) {
                statusMessage = e.message ?: "Could not load your support requests."
            } finally { busy = false }
        }
    }

    LaunchedEffect(auth.currentUser?.uid) {
        if (auth.currentUser != null) refreshTickets()
    }

    Column(
        Modifier.fillMaxSize().background(Slate950).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("Support Centre", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text("We are here to help with questions, account issues, payments and app problems.", color = Slate300)
        Card(colors = CardDefaults.cardColors(containerColor = Slate900)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("WhatsApp support", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("0708 329 9414", color = ElectricCyan)
                Button(onClick = { launchSupportIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$SUPPORT_WHATSAPP"))) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Chat, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Chat on WhatsApp")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Slate900)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Email support", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(SUPPORT_EMAIL, color = ElectricCyan)
                OutlinedButton(onClick = { launchSupportIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Email, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Send an email")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Slate900)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Call support", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("070 5252 0118", color = ElectricCyan)
                OutlinedButton(onClick = { launchSupportIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE"))) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Call, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Call support")
                }
            }
        }
        HorizontalDivider(color = Slate700)
        Text("In-app support request", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Submit a question or complaint here and it will be saved to your account so support can follow up. Sign in and verify your email first.", color = Slate400, fontSize = 12.sp)
        OutlinedTextField(value = subject, onValueChange = { subject = it.take(120) }, label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = message, onValueChange = { message = it.take(8000) }, label = { Text("Your question or complaint") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = !busy && subject.trim().length >= 3 && message.trim().length >= 10,
            onClick = {
                scope.launch {
                    busy = true; statusMessage = null
                    try {
                        requestTickets("POST", JSONObject().put("subject",subject.trim()).put("message",message.trim()))
                        subject = ""; message = ""
                        statusMessage = "Your support request was submitted successfully."
                        val result = requestTickets("GET")
                        val array = result.optJSONArray("tickets")
                        tickets = buildList {
                            if (array != null) for (i in 0 until array.length()) {
                                val item = array.optJSONObject(i) ?: continue
                                add(SupportTicket(item.optString("id"),item.optString("subject"),item.optString("message"),item.optString("status","open"),item.optString("reply")))
                            }
                        }
                    } catch (e:Exception) {
                        statusMessage = e.message ?: "Could not submit your support request."
                    } finally { busy = false }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp),color=Color.White,strokeWidth=2.dp)
            else Text("Submit support request")
        }
        statusMessage?.let { Text(it,color=if(it.contains("success",true)||it.contains("up to date",true)) ElectricCyan else AmberWarning,fontSize=12.sp) }
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("My support requests",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            TextButton(onClick={refreshTickets()},enabled=!busy && auth.currentUser!=null) { Text("Refresh",color=ElectricCyan) }
        }
        if (tickets.isEmpty()) {
            Text("No in-app support requests yet. Your WhatsApp, email and phone support options are always available above.",color=Slate400,fontSize=12.sp)
        } else tickets.forEach { ticket ->
            Card(colors=CardDefaults.cardColors(containerColor=Slate900)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(ticket.subject,color=Color.White,fontWeight=FontWeight.SemiBold)
                    Text(ticket.message,color=Slate300,fontSize=12.sp)
                    Text("Status: ${ticket.status}",color=ElectricCyan,fontSize=11.sp)
                    if(ticket.reply.isNotBlank()) {
                        HorizontalDivider(color=Slate700)
                        Text("Support reply",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                        Text(ticket.reply,color=Slate300,fontSize=12.sp)
                    }
                }
            }
        }
        Text("Your ticket history is private to your signed-in account. WhatsApp, email and call options open the corresponding app on your device.",color=Slate400,fontSize=11.sp)
    }
}
