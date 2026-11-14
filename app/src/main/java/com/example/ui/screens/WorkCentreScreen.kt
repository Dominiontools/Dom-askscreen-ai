package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AskScreenApp
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

private const val EMAIL_MANAGEMENT_URL = "https://fancy-swan-734af6.netlify.app"

@Composable
fun WorkCentreScreen() {
    val context = LocalContext.current
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    var products by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    var emailPriceCents by remember { mutableIntStateOf(2000) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val configured = prefs.backendUrl.trim().trimEnd('/')
            if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set the AskScreen backend URL in Settings to load current prices.")
            val endpoint = configured.removeSuffix("/ask-screen") + "/admin-settings"
            val json = withContext(Dispatchers.IO) {
                OkHttpClient().newCall(Request.Builder().url(endpoint).get().build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Service prices could not be loaded.")
                    JSONObject(response.body?.string().orEmpty())
                }
            }
            val list = json.optJSONObject("settings")?.optJSONArray("pricing")
            val mapped = mutableListOf<Triple<String, String, Int>>()
            if (list != null) for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id == "email_management") emailPriceCents = item.optInt("monthlyPriceCents", 2000)
                else if (id != "core") mapped.add(Triple(item.optString("name"), id, item.optInt("monthlyPriceCents")) )
            }
            products = mapped
        } catch (e: Exception) { error = e.message ?: "Could not load Work Centre." }
        finally { loading = false }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Slate950).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DOM WORK CENTRE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Choose the work service you need. Each service is a separate monthly subscription. You do not need the $9 DOM AI Core plan to subscribe to a Work Centre service. Work subscriptions do not unlock the main chat or screen-reading features.", color = Slate300, fontSize = 13.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth().clickable {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EMAIL_MANAGEMENT_URL)))
        }) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Email, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f)) {
                    Text("DOM Email Management", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Open the separate Email Management System", color = Slate400, fontSize = 12.sp)
                    Text("USD " + String.format(Locale.US, "%.2f", emailPriceCents / 100.0) + "/month", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Icon(Icons.Default.OpenInNew, contentDescription = "Open email management", tint = ElectricCyan)
            }
        }
        Text("SPECIALIST WORK SERVICES", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = AmberWarning, fontSize = 12.sp) }
        products.forEach { (name, _, cents) ->
            Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.WorkOutline, contentDescription = null, tint = ElectricCyan)
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Independent monthly subscription", color = Slate400, fontSize = 11.sp)
                    }
                    Text("USD " + String.format(Locale.US, "%.2f", cents / 100.0) + "/mo", color = ElectricCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
        }
        Text("Payment and subscription activation will be enabled after Paystack approval.", color = Slate400, fontSize = 11.sp)
    }
}