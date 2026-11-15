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
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val EMAIL_MANAGEMENT_URL = "https://fancy-swan-734af6.netlify.app"

private data class WorkCentreProduct(
    val name: String,
    val id: String,
    val priceCents: Int,
    val status: String = "inactive",
    val currentPeriodEnd: String? = null
)

private fun workAccessLabel(product: WorkCentreProduct): String {
    if (product.status == "active") {
        val expiry = product.currentPeriodEnd?.let { value ->
            try {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                parser.parse(value)?.let { DateFormat.getDateInstance().format(it) }
            } catch (_: Exception) { null }
        }
        return if (expiry != null) "Access active until $expiry" else "Access active"
    }
    return when (product.status) {
        "expired" -> "Expired"
        "cancelled", "canceled" -> "Access revoked"
        "past_due" -> "Payment overdue"
        "pending" -> "Pending"
        else -> "Not active"
    }
}

@Composable
fun WorkCentreScreen() {
    val context = LocalContext.current
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val auth = remember { FirebaseAuth.getInstance() }
    val http = remember { OkHttpClient() }
    var products by remember { mutableStateOf<List<WorkCentreProduct>>(emptyList()) }
    var emailProduct by remember { mutableStateOf(WorkCentreProduct("DOM Email Management", "email_management", 2000)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var accessError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(auth.currentUser?.uid, prefs.backendUrl) {
        loading = true
        error = null
        accessError = null
        try {
            val configured = prefs.backendUrl.trim().trimEnd('/')
            if (!configured.endsWith("/ask-screen")) {
                throw IllegalStateException("Set the AskScreen backend URL in Settings to load Work Centre services.")
            }
            val base = configured.removeSuffix("/ask-screen")
            val settings = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$base/admin-settings").get().build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Service prices could not be loaded.")
                    JSONObject(response.body?.string().orEmpty()).optJSONObject("settings") ?: JSONObject()
                }
            }
            val subscriptionById = mutableMapOf<String, Pair<String, String?>>()
            val user = auth.currentUser
            if (user != null) {
                try {
                    val token = user.getIdToken(true).await().token
                        ?: throw IllegalStateException("Could not verify your account.")
                    val account = withContext(Dispatchers.IO) {
                        val request = Request.Builder().url("$base/account-status")
                            .header("Authorization", "Bearer $token").get().build()
                        http.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                val message = try { JSONObject(body).optString("error") } catch (_: Exception) { "" }
                                throw IllegalStateException(message.ifBlank { "Your access status could not be checked." })
                            }
                            JSONObject(body)
                        }
                    }
                    val subscriptions = account.optJSONArray("productSubscriptions")
                    if (subscriptions != null) {
                        for (index in 0 until subscriptions.length()) {
                            val item = subscriptions.optJSONObject(index) ?: continue
                            val id = item.optString("id")
                            if (id.isNotBlank()) {
                                val expiry = item.optString("currentPeriodEnd").takeIf { it.isNotBlank() && it != "null" }
                                subscriptionById[id] = item.optString("status", "inactive") to expiry
                            }
                        }
                    }
                } catch (e: Exception) {
                    accessError = e.message ?: "Your access status could not be checked."
                }
            }
            val list = settings.optJSONArray("pricing")
            val mapped = mutableListOf<WorkCentreProduct>()
            if (list != null) for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                val cents = item.optInt("monthlyPriceCents")
                val access = subscriptionById[id]
                val product = WorkCentreProduct(name, id, cents, access?.first ?: "inactive", access?.second)
                if (id == "email_management") emailProduct = product
                else if (id != "core") mapped.add(product)
            }
            products = mapped
        } catch (e: Exception) {
            error = e.message ?: "Could not load Work Centre."
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Slate950).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DOM WORK CENTRE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Choose an independent work service. You do not need the $9 DOM AI Core plan to subscribe to a Work Centre service. Work subscriptions do not unlock the main chat or screen-reading features.",
            color = Slate300, fontSize = 13.sp
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = AmberWarning, fontSize = 12.sp) }
        if (auth.currentUser == null) {
            Text("Sign in to see whether Work Centre access has been granted to your account.", color = Slate400, fontSize = 12.sp)
        } else {
            accessError?.let { Text("Access status: $it", color = AmberWarning, fontSize = 12.sp) }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Slate900),
            modifier = Modifier.fillMaxWidth().clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EMAIL_MANAGEMENT_URL))) }
            }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Email, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(30.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("DOM Email Management", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Open the separate Email Management System", color = Slate400, fontSize = 12.sp)
                    Text("USD " + String.format(Locale.US, "%.2f", emailProduct.priceCents / 100.0) + "/month", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(workAccessLabel(emailProduct), color = if (emailProduct.status == "active") ElectricCyan else Slate400, fontSize = 11.sp)
                }
                Icon(Icons.Default.OpenInNew, contentDescription = "Open email management", tint = ElectricCyan)
            }
        }

        Text("SPECIALIST WORK SERVICES", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (!loading && products.isEmpty() && error == null) {
            Text("No specialist services are currently listed.", color = Slate400, fontSize = 12.sp)
        }
        products.forEach { product ->
            Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.WorkOutline, contentDescription = null, tint = ElectricCyan)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(product.name, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("Independent monthly service", color = Slate400, fontSize = 11.sp)
                        Text(
                            workAccessLabel(product),
                            color = if (product.status == "active") ElectricCyan else Slate400,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        "USD " + String.format(Locale.US, "%.2f", product.priceCents / 100.0) + "/mo",
                        color = ElectricCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                    )
                }
            }
        }
        Text(
            "Prices are displayed for planning. Online checkout and automatic subscription activation will be enabled after Paystack approval. An access label shows the account record; it does not mean every specialist tool is already available inside this app.",
            color = Slate400, fontSize = 11.sp
        )
    }
}
