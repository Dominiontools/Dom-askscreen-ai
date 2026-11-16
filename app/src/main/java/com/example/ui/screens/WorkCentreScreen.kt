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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val EMAIL_MANAGEMENT_URL = "https://fancy-swan-734af6.netlify.app"

private val FALLBACK_WORK_PRODUCTS = listOf(
    WorkCentreProduct("General Data Annotation AI", "data_annotation", 2900),
    WorkCentreProduct("Image Annotation AI", "image_annotation", 2000),
    WorkCentreProduct("Video Annotation AI", "video_annotation", 3500),
    WorkCentreProduct("Audio Annotation AI", "audio_annotation", 2000),
    WorkCentreProduct("Text Annotation AI", "text_annotation", 1800),
    WorkCentreProduct("AI Training & Evaluation", "ai_training", 3000),
    WorkCentreProduct("Virtual Assistant AI", "virtual_assistant", 3900),
    WorkCentreProduct("Lead Generation AI", "lead_generation", 2900),
    WorkCentreProduct("Customer Support AI", "customer_support", 2900),
    WorkCentreProduct("Inventory Management AI", "inventory", 2500),
    WorkCentreProduct("Purchasing & Procurement AI", "procurement", 2500),
    WorkCentreProduct("Calendar & Scheduling AI", "calendar", 1500),
    WorkCentreProduct("Spreadsheet & Document AI", "documents_spreadsheets", 2000),
    WorkCentreProduct("Customer & CRM Management AI", "crm", 2900),
    WorkCentreProduct("Project & Task Management AI", "project_tasks", 2000),
    WorkCentreProduct("Business Administration AI", "business_admin", 2500),
    WorkCentreProduct("Marketing & Social Media AI", "marketing_social", 2900),
    WorkCentreProduct("Research Assistant AI", "research", 2000),
    WorkCentreProduct("Translation & Transcription AI", "translation_transcription", 2000),
    WorkCentreProduct("Technical Assistant AI", "technical_assistant", 3900)
)

private data class WalletNetwork(val id:String,val name:String,val address:String)

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
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var wallets by remember { mutableStateOf<List<WalletNetwork>>(emptyList()) }
    var checkoutProduct by remember { mutableStateOf<WorkCentreProduct?>(null) }
    var paymentEmail by remember { mutableStateOf(auth.currentUser?.email.orEmpty()) }
    var paymentNetwork by remember { mutableStateOf("BEP20") }
    var paymentHash by remember { mutableStateOf("") }
    var paymentAmount by remember { mutableStateOf("") }
    var paymentBusy by remember { mutableStateOf(false) }
    var paymentMessage by remember { mutableStateOf<String?>(null) }
    var products by remember { mutableStateOf<List<WorkCentreProduct>>(emptyList()) }
    var liveCatalogueLoaded by remember { mutableStateOf(false) }
    var emailProduct by remember { mutableStateOf(WorkCentreProduct("DOM Email Management", "email_management", 2000)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var accessError by remember { mutableStateOf<String?>(null) }
    var openedWorkspace by remember { mutableStateOf<WorkCentreProduct?>(null) }

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
            val paymentInstructions = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$base/crypto-payments").get().build()).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("USDT payment instructions could not be loaded.")
                    JSONObject(response.body?.string().orEmpty())
                }
            }
            val networkArray = paymentInstructions.optJSONArray("networks")
            val loadedWallets = mutableListOf<WalletNetwork>()
            if (networkArray != null) for (index in 0 until networkArray.length()) {
                val item = networkArray.optJSONObject(index) ?: continue
                loadedWallets.add(WalletNetwork(item.optString("id"), item.optString("name"), item.optString("address")))
            }
            wallets = loadedWallets
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
            liveCatalogueLoaded = true
        } catch (e: Exception) {
            liveCatalogueLoaded = false
            wallets = emptyList()
            products = FALLBACK_WORK_PRODUCTS
            error = e.message ?: "Live service settings are unavailable. Showing the service catalogue."
        } finally {
            loading = false
        }
    }

    suspend fun submitCryptoPayment() {
        val product = checkoutProduct ?: return
        if (!paymentEmail.contains("@") || paymentEmail.length > 254) throw IllegalArgumentException("Enter a valid email address.")
        if (paymentAmount.toBigDecimalOrNull() == null || paymentAmount.toBigDecimal() <= java.math.BigDecimal.ZERO) throw IllegalArgumentException("Enter the USDT amount you sent.")
        if (paymentHash.isBlank()) throw IllegalArgumentException("Enter the transaction hash from your wallet.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set the AskScreen backend URL in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/crypto-payments"
        val payload = JSONObject().put("email",paymentEmail.trim()).put("productId",product.id)
            .put("network",paymentNetwork).put("transactionHash",paymentHash.trim()).put("amountUSDT",paymentAmount.trim())
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Payment details could not be submitted." })
                paymentMessage = json.optString("message","Payment details submitted for manual review.")
            }
        }
        checkoutProduct = null
        paymentHash = ""
        paymentAmount = ""
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Slate950).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DOM WORK CENTRE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Choose an independent Work Centre service. Each has its own monthly price and USDT checkout. Paystack is Coming Soon.",
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
            modifier = Modifier.fillMaxWidth()
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
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EMAIL_MANAGEMENT_URL))) }.onFailure { paymentMessage = "Email Management website could not be opened." } }) { Text("Visit Email Management website") }
                    Text("USD " + String.format(Locale.US, "%.2f", emailProduct.priceCents / 100.0) + "/month", color = ElectricCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(workAccessLabel(emailProduct), color = if (emailProduct.status == "active") ElectricCyan else Slate400, fontSize = 11.sp)
                    if (emailProduct.status == "active" && auth.currentUser?.isEmailVerified == true) {
                        Button(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EMAIL_MANAGEMENT_URL))) }
                                .onFailure { paymentMessage = "Email Management could not be opened on this device." }
                        }) { Text("Open Email Management") }
                    } else {
                        Button(onClick = {
                            paymentEmail = auth.currentUser?.email.orEmpty()
                            paymentMessage = null
                            checkoutProduct = emailProduct
                        }, enabled = !loading && liveCatalogueLoaded && wallets.isNotEmpty()) { Text("Pay with USDT") }
                        if (auth.currentUser == null) Text("After payment approval, register with this email and verify it to claim access.", color = Slate400, fontSize = 10.sp)
                    }
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
                        Text(if (product.status == "active") "Your service workspace is ready to open" else "Subscribe to unlock this service workspace", color = Slate400, fontSize = 11.sp)
                        Text(
                            workAccessLabel(product),
                            color = if (product.status == "active") ElectricCyan else Slate400,
                            fontSize = 11.sp
                        )
                        Button(onClick = {
                            if (product.status == "active" && auth.currentUser?.isEmailVerified == true) openedWorkspace = product
                            else { paymentEmail = auth.currentUser?.email.orEmpty(); paymentMessage = null; checkoutProduct = product }
                        }, enabled = !loading && liveCatalogueLoaded && (product.status == "active" && auth.currentUser?.isEmailVerified == true || wallets.isNotEmpty())) {
                            Text(if (product.status == "active" && auth.currentUser?.isEmailVerified == true) "Open workspace" else "Pay with USDT")
                        }
                    }
                    Text(
                        "USD " + String.format(Locale.US, "%.2f", product.priceCents / 100.0) + "/mo",
                        color = ElectricCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                    )
                }
            }
        }
        paymentMessage?.let { Text(it, color = ElectricCyan, fontSize = 12.sp) }
        Text(
            "USDT payments are manually reviewed. Submitting details or a screenshot does not activate access. Only an administrator's blockchain verification and approval unlocks the selected product. Send USDT on the exact network selected.",
            color = AmberWarning, fontSize = 11.sp
        )
        Text(
            "Workspaces currently support AI-assisted text tasks with saved results. Image, audio and video uploads, external account connections and actions such as publishing or sending still require their dedicated integrations.",
            color = Slate400, fontSize = 11.sp
        )
    }
    openedWorkspace?.let { product ->
        WorkServiceWorkspaceDialog(productId = product.id, productName = product.name, onDismiss = { openedWorkspace = null })
    }
    checkoutProduct?.let { product ->
        val wallet = wallets.firstOrNull { it.id == paymentNetwork }
        AlertDialog(
            onDismissRequest = { if (!paymentBusy) checkoutProduct = null },
            title = { Text("USDT payment • ${product.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Monthly catalogue price: USD ${String.format(Locale.US, "%.2f", product.priceCents / 100.0)}. Confirm the USDT amount with the current exchange rate before sending.", fontSize = 12.sp)
                    Text("Payment email (use the same email when registering)", fontSize = 11.sp)
                    OutlinedTextField(value = paymentEmail, onValueChange = { paymentEmail = it.take(254) }, singleLine = true, label = { Text("Email address") })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = paymentNetwork == "BEP20", onClick = { paymentNetwork = "BEP20" }, label = { Text("BEP20") })
                        FilterChip(selected = paymentNetwork == "TRC20", onClick = { paymentNetwork = "TRC20" }, label = { Text("TRC20") })
                    }
                    Text(wallet?.name ?: "Loading network…", color = ElectricCyan, fontSize = 12.sp)
                    Text(wallet?.address ?: "Wallet address unavailable", fontSize = 10.sp)
                    TextButton(onClick = { wallet?.let { clipboard.setText(AnnotatedString(it.address)) } }, enabled = wallet != null) { Text("Copy receiving address") }
                    OutlinedTextField(value = paymentAmount, onValueChange = { paymentAmount = it.filter { ch -> ch.isDigit() || ch == '.' }.take(20) }, singleLine = true, label = { Text("USDT amount sent") })
                    OutlinedTextField(value = paymentHash, onValueChange = { paymentHash = it.filterNot(Char::isWhitespace).take(140) }, singleLine = true, label = { Text("Transaction hash") })
                    Text("Do not send on the wrong network. Access stays locked until the admin verifies the transaction. Never submit a wallet recovery phrase or private key.", color = AmberWarning, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        paymentBusy = true
                        paymentMessage = null
                        try { submitCryptoPayment() }
                        catch (e: Exception) { paymentMessage = e.message ?: "Payment details could not be submitted." }
                        finally { paymentBusy = false }
                    }
                }, enabled = !paymentBusy && paymentEmail.contains("@") && paymentAmount.toBigDecimalOrNull() != null && paymentHash.isNotBlank() && wallet != null) {
                    Text(if (paymentBusy) "Submitting…" else "Submit payment details")
                }
            },
            dismissButton = { TextButton(onClick = { if (!paymentBusy) checkoutProduct = null }) { Text("Cancel") } }
        )
    }
}
