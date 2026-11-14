package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.example.AskScreenApp
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

@Composable
fun AccountScreen(onAdminAccess: () -> Unit = {}, onSupportAccess: () -> Unit = {}) {
    val auth = remember { FirebaseAuth.getInstance() }
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val http = remember { OkHttpClient() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signedInEmail by remember { mutableStateOf(auth.currentUser?.email) }
    var emailVerified by remember { mutableStateOf(auth.currentUser?.isEmailVerified == true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var createAccount by remember { mutableStateOf(false) }
    var accountStatus by remember { mutableStateOf<JSONObject?>(null) }
    var pricingSettings by remember { mutableStateOf<JSONObject?>(null) }
    var statusLoading by remember { mutableStateOf(false) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    LaunchedEffect(signedInEmail, emailVerified, refreshCounter) {
        accountStatus = null
        pricingSettings = null
        statusError = null
        val user = auth.currentUser
        if (user != null && signedInEmail != null) {
            statusLoading = true
            try {
                val token = user.getIdToken(true).await().token
                    ?: throw IllegalStateException("Could not verify your account.")
                val configured = prefs.backendUrl.trim().trimEnd('/')
                if (!configured.endsWith("/ask-screen")) {
                    throw IllegalStateException("Set your AskScreen backend URL in Settings to view account usage.")
                }
                val endpoint = configured.removeSuffix("/ask-screen") + "/account-status"
                val result = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $token").get().build()
                    http.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                        if (!response.isSuccessful) throw IllegalStateException(
                            json.optString("error").ifBlank { "Could not load your account status." }
                        )
                        json
                    }
                }
                accountStatus = result
                val pricingEndpoint = configured.removeSuffix("/ask-screen") + "/admin-settings"
                pricingSettings = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(pricingEndpoint).get().build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("Product prices are temporarily unavailable.")
                        val publicJson = try { JSONObject(response.body?.string().orEmpty()) } catch (_: Exception) { JSONObject() }
                        publicJson.optJSONObject("settings")
                    }
                }
            } catch (e: Exception) {
                statusError = e.message ?: "Could not load your account status."
            } finally {
                statusLoading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Slate950).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(12.dp))
        Text("YOUR ASKSCREEN ACCOUNT", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (signedInEmail != null) {
            Text("Signed in as", color = Slate400)
            Text(signedInEmail.orEmpty(), color = ElectricCyan, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (emailVerified) {
                Text("Email address verified", color = ElectricCyan, fontSize = 12.sp)
            } else {
                Text("Please verify your email address. Administrator access requires a verified email.", color = AmberWarning, fontSize = 12.sp)
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val user = auth.currentUser
                        if (user == null) message = "Please sign in again."
                        else {
                            busy = true
                            user.sendEmailVerification().addOnCompleteListener { result ->
                                busy = false
                                message = if (result.isSuccessful) "Verification email sent. Check your inbox and spam folder."
                                else result.exception?.localizedMessage ?: "Could not send verification email."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Please wait..." else "Resend verification email") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        val user = auth.currentUser
                        if (user == null) message = "Please sign in again."
                        else {
                            busy = true
                            user.reload().addOnCompleteListener { result ->
                                busy = false
                                if (result.isSuccessful) {
                                    emailVerified = auth.currentUser?.isEmailVerified == true
                                    message = if (emailVerified) "Email verified successfully."
                                    else "Your email is not verified yet. Open the verification link first."
                                } else message = result.exception?.localizedMessage ?: "Could not refresh account status."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("I've verified my email — refresh status") }
            }

            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your usage and subscription", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (statusLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Loading your account status…", color = Slate400, fontSize = 12.sp)
                    } else if (accountStatus != null) {
                        val trial = accountStatus!!.optJSONObject("trial") ?: JSONObject()
                        val usage = accountStatus!!.optJSONObject("usage") ?: JSONObject()
                        val subscription = accountStatus!!.optJSONObject("subscription") ?: JSONObject()
                        val active = subscription.optString("status") == "active"
                        Text("Subscription: ${subscription.optString("status", "inactive").replaceFirstChar { it.uppercase() }}",
                            color = if (active) ElectricCyan else AmberWarning, fontWeight = FontWeight.SemiBold)
                        if (active) {
                            Text("Your paid access is active.", color = Slate300, fontSize = 12.sp)
                            Text("Period ends: ${subscription.optString("currentPeriodEnd").ifBlank { "Not available" }}", color = Slate400, fontSize = 11.sp)
                        } else if (trial.optBoolean("eligible")) {
                            val limit = trial.optInt("limit", 2)
                            Text("Free trial: ${limit} uses per feature", color = Slate300, fontSize = 12.sp)
                            Text("Screen answers: ${usage.optInt("screen")}/$limit used • ${trial.optInt("screenRemaining")} remaining", color = Slate300, fontSize = 12.sp)
                            Text("Spoken answers: ${usage.optInt("spoken")}/$limit used • ${trial.optInt("spokenRemaining")} remaining", color = Slate300, fontSize = 12.sp)
                            Text("Transcription: ${usage.optInt("transcription")}/$limit used • ${trial.optInt("transcriptionRemaining")} remaining", color = Slate300, fontSize = 12.sp)
                        } else {
                            Text("This account is not eligible for the new-user free trial.", color = Slate300, fontSize = 12.sp)
                            Text("Subscribe to continue using paid features.", color = Slate400, fontSize = 12.sp)
                        }
                    } else {
                        Text(statusError ?: "Account status is not available.", color = AmberWarning, fontSize = 12.sp)
                        OutlinedButton(onClick = { refreshCounter++ }) { Text("Retry account status") }
                    }
                    Text("Usage is linked to this signed-in account.", color = Slate400, fontSize = 11.sp)
                }
            }
            pricingSettings?.let { settings ->
                Spacer(Modifier.height(14.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Slate900), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Available AI services and plans", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("Browse specialist tools and monthly prices. Specialist services are separate add-ons to DOM AI Core.", color = Slate400, fontSize = 12.sp)
                        val products = settings.optJSONArray("pricing")
                        if (products != null) {
                            for (i in 0 until products.length()) {
                                val product = products.optJSONObject(i) ?: continue
                                val cents = product.optInt("monthlyPriceCents")
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(product.optString("name"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(product.optString("category"), color = Slate400, fontSize = 10.sp)
                                    }
                                    Text("USD " + String.format(Locale.US,"%.2f",cents / 100.0) + "/mo", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        val bundles = settings.optJSONObject("bundles") ?: JSONObject()
                        val aiSuite = bundles.optJSONObject("aiSuite") ?: JSONObject()
                        val complete = bundles.optJSONObject("completeBusiness") ?: JSONObject()
                        HorizontalDivider(color = Slate700)
                        Text("AI Suite with ${settings.optInt("bundleDiscountPercent",10)}% discount: USD " + String.format(Locale.US,"%.2f",aiSuite.optInt("priceCents") / 100.0) + "/month", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Complete Business Bundle: USD " + String.format(Locale.US,"%.2f",complete.optInt("priceCents") / 100.0) + "/month", color = Color(0xFF8FE0B0), fontWeight = FontWeight.SemiBold)
                        Text("Email Management is an independent product. Prices are displayed for planning; checkout will be enabled after Paystack approval.", color = Slate400, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                auth.signOut()
                signedInEmail = null
                emailVerified = false
                accountStatus = null
                pricingSettings = null
                statusError = null
                message = "You have signed out."
            }) { Text("Sign out") }
        } else {
            Text("Sign in to keep your free usage and subscription connected to you.", color = Slate300, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = email, onValueChange = { email = it.trim() }, label = { Text("Email address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (at least 6 characters)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = !busy && email.contains("@") && password.length >= 6,
                onClick = {
                    busy = true
                    message = null
                    val task = if (createAccount) auth.createUserWithEmailAndPassword(email, password)
                               else auth.signInWithEmailAndPassword(email, password)
                    task.addOnCompleteListener { result ->
                        if (result.isSuccessful) {
                            val user = auth.currentUser
                            signedInEmail = user?.email
                            emailVerified = user?.isEmailVerified == true
                            if (createAccount && user != null) {
                                user.sendEmailVerification().addOnCompleteListener { verification ->
                                    busy = false
                                    message = if (verification.isSuccessful) "Account created. A verification link has been sent to your email. Please verify it."
                                    else "Account created, but the verification email could not be sent. Use Resend verification email below."
                                }
                            } else {
                                busy = false
                                message = if (emailVerified) "Signed in successfully." else "Signed in. Please verify your email address."
                            }
                        } else {
                            busy = false
                            message = result.exception?.localizedMessage ?: "Could not complete sign-in."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Please wait..." else if (createAccount) "Create account" else "Sign in") }
            TextButton(onClick = { createAccount = !createAccount; message = null }) {
                Text(if (createAccount) "Already have an account? Sign in" else "New here? Create an account", color = ElectricCyan)
            }
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = if (it.contains("success", true) || it.contains("sent", true) || it.contains("created", true)) ElectricCyan else Color(0xFFFFB4AB), fontSize = 12.sp)
        }
        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = Slate700)
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onSupportAccess, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.SupportAgent, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open Support Centre")
        }
        Spacer(Modifier.height(14.dp))
        Text("Platform owner?", color = Slate300, fontWeight = FontWeight.SemiBold)
        Text("Administrator access is separate and verified securely by the server.", color = Slate400, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onAdminAccess, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AdminPanelSettings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open Admin Centre")
        }
        Spacer(Modifier.height(18.dp))
        Text("Your password is handled by Firebase Authentication. AskScreen does not display or store it.", color = Slate400, fontSize = 11.sp)
    }
}
