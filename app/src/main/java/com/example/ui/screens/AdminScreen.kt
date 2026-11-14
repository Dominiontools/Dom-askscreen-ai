package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import java.text.DateFormat
import java.time.Instant
import java.util.Date

private data class AdminUserRow(val uid:String,val name:String,val email:String,val joined:String,val lastSignIn:String,val screen:Int,val spoken:Int,val transcription:Int,val trialEligible:Boolean,val screenRemaining:Int,val spokenRemaining:Int,val transcriptionRemaining:Int,val subscription:String,val expiry:String,val disabled:Boolean,val emailVerified:Boolean)
private data class AdminPaymentRow(val date:String,val email:String,val reference:String,val amount:String,val status:String)
private data class AdminTicketRow(val id:String,val date:String,val email:String,val subject:String,val message:String,val status:String,val reply:String)
private data class AdminAuditRow(val action:String,val targetEmail:String,val adminEmail:String,val detail:String,val date:String)
private data class AdminActivityRow(val email:String,val feature:String,val outcome:String,val code:String,val provider:String,val requestId:String,val date:String)

@Composable
fun AdminScreen(onBack: () -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val scope = rememberCoroutineScope()
    val http = remember { OkHttpClient() }
    var email by remember { mutableStateOf(auth.currentUser?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var idToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var users by remember { mutableStateOf<List<AdminUserRow>>(emptyList()) }
    var userSearch by remember { mutableStateOf("") }
    var userFilter by remember { mutableStateOf("All") }
    var payments by remember { mutableStateOf<List<AdminPaymentRow>>(emptyList()) }
    var supportTickets by remember { mutableStateOf<List<AdminTicketRow>>(emptyList()) }
    var auditLogs by remember { mutableStateOf<List<AdminAuditRow>>(emptyList()) }
    var activityLogs by remember { mutableStateOf<List<AdminActivityRow>>(emptyList()) }
    var announcement by remember { mutableStateOf("") }
    var screenEnabled by remember { mutableStateOf(true) }
    var spokenEnabled by remember { mutableStateOf(true) }
    var transcriptionEnabled by remember { mutableStateOf(true) }
    var freeTrialLimit by remember { mutableStateOf("2") }
    var totalUsers by remember { mutableIntStateOf(0) }
    var activeSubscribers by remember { mutableIntStateOf(0) }
    var trialUsers by remember { mutableIntStateOf(0) }
    var disabledUsers by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    fun dateLabel(value:String):String = try {
        if (value.isBlank() || value == "null") "—" else DateFormat.getDateTimeInstance().format(Date.from(Instant.parse(value)))
    } catch (_:Exception) { "—" }

    suspend fun loadSettings(token:String) {
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/admin-settings"
        val result = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint).header("Authorization","Bearer $token").get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Could not load app settings." })
                json
            }
        }
        val settings = result.optJSONObject("settings") ?: JSONObject()
        val features = settings.optJSONObject("features") ?: JSONObject()
        announcement = settings.optString("announcement")
        screenEnabled = features.optBoolean("screen",true)
        spokenEnabled = features.optBoolean("spoken",true)
        transcriptionEnabled = features.optBoolean("transcription",true)
        freeTrialLimit = settings.optInt("freeTrialLimit",2).toString()
    }

    suspend fun saveSettings(token:String) {
        val limit = freeTrialLimit.toIntOrNull() ?: throw IllegalArgumentException("Free-trial limit must be a whole number from 0 to 10.")
        if (limit !in 0..10) throw IllegalArgumentException("Free-trial limit must be a whole number from 0 to 10.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/admin-settings"
        val features = JSONObject().put("screen",screenEnabled).put("spoken",spokenEnabled).put("transcription",transcriptionEnabled)
        val payload = JSONObject().put("announcement",announcement).put("features",features).put("freeTrialLimit",limit)
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint).header("Authorization","Bearer $token")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Could not save app settings." })
            }
        }
        loadSettings(token)
    }

    suspend fun loadData(token:String) {
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first. Its URL must end with /ask-screen.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/admin-dashboard"
        val result = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint).header("Authorization","Bearer $token").get().build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Admin access denied or dashboard unavailable (HTTP ${response.code})." })
                json
            }
        }
        val totals = result.optJSONObject("totals") ?: JSONObject()
        totalUsers = totals.optInt("users")
        activeSubscribers = totals.optInt("activeSubscribers")
        trialUsers = totals.optInt("trialUsers")
        disabledUsers = totals.optInt("disabledUsers")
        val userArray = result.optJSONArray("users")
        users = buildList {
            if (userArray != null) for (i in 0 until userArray.length()) {
                val item = userArray.optJSONObject(i) ?: continue
                val usage = item.optJSONObject("usage") ?: JSONObject()
                val trial = item.optJSONObject("freeTrial") ?: JSONObject()
                val sub = item.optJSONObject("subscription") ?: JSONObject()
                add(AdminUserRow(
                    item.optString("uid"), item.optString("displayName").ifBlank { item.optString("email").ifBlank { "Unnamed account" } },
                    item.optString("email"), dateLabel(item.optString("createdAt")), dateLabel(item.optString("lastSignInAt")),
                    usage.optInt("screen"), usage.optInt("spoken"), usage.optInt("transcription"),
                    trial.optBoolean("eligible"), trial.optInt("screenRemaining"), trial.optInt("spokenRemaining"),
                    trial.optInt("transcriptionRemaining"),
                    sub.optString("status","inactive"), dateLabel(sub.optString("currentPeriodEnd")), item.optBoolean("disabled"), item.optBoolean("emailVerified")
                ))
            }
        }
        val paymentArray = result.optJSONArray("payments")
        payments = buildList {
            if (paymentArray != null) for (i in 0 until paymentArray.length()) {
                val item = paymentArray.optJSONObject(i) ?: continue
                add(AdminPaymentRow(dateLabel(item.optString("createdAt")),item.optString("email"),item.optString("reference").ifBlank { item.optString("id") },item.optString("amount","—")+" "+item.optString("currency","NGN"),item.optString("status","unknown")))
            }
        }
        val ticketArray = result.optJSONArray("supportTickets")
        supportTickets = buildList {
            if (ticketArray != null) for (i in 0 until ticketArray.length()) {
                val item = ticketArray.optJSONObject(i) ?: continue
                add(AdminTicketRow(item.optString("id"),dateLabel(item.optString("createdAt")),item.optString("email"),item.optString("subject"),item.optString("message"),item.optString("status","open"),item.optString("reply")))
            }
        }
        val auditArray = result.optJSONArray("auditLogs")
        auditLogs = buildList {
            if (auditArray != null) for (i in 0 until auditArray.length()) {
                val item = auditArray.optJSONObject(i) ?: continue
                add(AdminAuditRow(
                    item.optString("action","admin_action"),
                    item.optString("targetEmail").ifBlank { item.optString("targetUid") },
                    item.optString("adminEmail"),
                    buildString {
                        if (item.optString("ticketId").isNotBlank()) append("Ticket: ").append(item.optString("ticketId"))
                        if (item.optString("ticketSubject").isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append(item.optString("ticketSubject"))
                        }
                        if (item.optString("status").isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append("Status: ").append(item.optString("status"))
                        }
                        if (item.has("replySent")) {
                            if (isNotEmpty()) append(" • ")
                            append(if (item.optBoolean("replySent")) "Reply sent" else "Status updated")
                        }
                    },
                    dateLabel(item.optString("createdAt"))
                ))
            }
        }
        val activityArray = result.optJSONArray("activityLogs")
        activityLogs = buildList {
            if (activityArray != null) for (i in 0 until activityArray.length()) {
                val item = activityArray.optJSONObject(i) ?: continue
                add(AdminActivityRow(
                    item.optString("email"),
                    item.optString("feature"),
                    item.optString("outcome"),
                    item.optString("code"),
                    item.optString("provider"),
                    item.optString("requestId"),
                    dateLabel(item.optString("createdAt"))
                ))
            }
        }
        loadSettings(token)
        loaded = true
    }

    fun refresh() {
        if (idToken.isBlank()) return
        scope.launch {
            busy = true; error = null
            try { loadData(idToken) } catch (e:Exception) { error = e.message ?: "Could not load admin data." }
            finally { busy = false }
        }
    }

    suspend fun setUserDisabled(uid:String, disabled:Boolean) {
        if (idToken.isBlank()) throw IllegalStateException("Sign in to the administrator account again.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/admin-users"
        val payload = JSONObject().put("uid",uid).put("disabled",disabled)
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint)
                .header("Authorization","Bearer $idToken")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Could not update account." })
            }
        }
        loadData(idToken)
    }

    suspend fun updateSupportTicket(ticketId:String, reply:String, status:String) {
        if (idToken.isBlank()) throw IllegalStateException("Sign in to the administrator account again.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/support-admin"
        val payload = JSONObject().put("ticketId",ticketId).put("reply",reply).put("status",status)
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint)
                .header("Authorization","Bearer $idToken")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Could not update ticket." })
            }
        }
        loadData(idToken)
    }

    Column(Modifier.fillMaxSize().background(Slate950).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,contentDescription="Back",tint=Color.White) }
            Column(Modifier.weight(1f)) {
                Text("DOM ASK AI",color=ElectricCyan,fontSize=12.sp,fontWeight=FontWeight.Bold)
                Text("Administrator Centre",color=Color.White,fontSize=21.sp,fontWeight=FontWeight.Bold)
            }
            Icon(Icons.Default.Shield,contentDescription="Protected admin area",tint=ElectricCyan,modifier=Modifier.size(28.dp))
        }
        if (!loaded) {
            Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Admin sign-in",color=Color.White,fontSize=18.sp,fontWeight=FontWeight.Bold)
                    Text("Only the verified Firebase account authorized by the owner can enter this area.",color=Slate300,fontSize=13.sp)
                    OutlinedTextField(value=email,onValueChange={email=it.trim()},label={Text("Admin email")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=password,onValueChange={password=it},label={Text("Password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={
                        scope.launch {
                            busy=true; error=null
                            try {
                                val user=auth.signInWithEmailAndPassword(email.trim(),password).await().user ?: throw IllegalStateException("Sign-in failed.")
                                val token=user.getIdToken(true).await().token ?: throw IllegalStateException("Could not verify account.")
                                idToken=token
                                loadData(token)
                                password=""
                            } catch(e:Exception) {
                                idToken=""
                                error=e.message ?: "Admin sign-in failed or this account is not authorized."
                                if(auth.currentUser!=null) auth.signOut()
                            } finally { busy=false }
                        }
                    },enabled=!busy && email.contains("@") && password.length>=6,modifier=Modifier.fillMaxWidth()) {
                        if(busy) CircularProgressIndicator(Modifier.size(18.dp),color=Color.White,strokeWidth=2.dp) else Text("Sign in to Admin Centre")
                    }
                }
            }
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={refresh()},enabled=!busy,modifier=Modifier.weight(1f)) { Icon(Icons.Default.Refresh,contentDescription=null); Spacer(Modifier.width(6.dp)); Text("Refresh data") }
                OutlinedButton(onClick={auth.signOut();idToken="";loaded=false;users=emptyList();payments=emptyList();error=null}) { Text("Sign out") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AdminMetric("Registered users",totalUsers.toString(),Modifier.weight(1f))
                AdminMetric("Subscribers",activeSubscribers.toString(),Modifier.weight(1f))
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AdminMetric("Trial-eligible users",trialUsers.toString(),Modifier.weight(1f))
                AdminMetric("Disabled accounts",disabledUsers.toString(),Modifier.weight(1f))
            }
            Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("App & feature settings",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
                    Text("Make routine app changes here without editing source code. Changes are saved on the server.",color=Slate400,fontSize=11.sp)
                    OutlinedTextField(value=announcement,onValueChange={if(it.length<=240) announcement=it},label={Text("Customer announcement (optional)")},supportingText={Text("${announcement.length}/240 characters")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=3)
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text("Screen reading",color=Color.White); Text("Allow screen and image questions",color=Slate400,fontSize=11.sp) }
                        Switch(checked=screenEnabled,onCheckedChange={screenEnabled=it})
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text("Spoken answers",color=Color.White); Text("Allow voice-based AI answers",color=Slate400,fontSize=11.sp) }
                        Switch(checked=spokenEnabled,onCheckedChange={spokenEnabled=it})
                    }
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { Text("Transcription",color=Color.White); Text("Allow speech transcription",color=Slate400,fontSize=11.sp) }
                        Switch(checked=transcriptionEnabled,onCheckedChange={transcriptionEnabled=it})
                    }
                    OutlinedTextField(value=freeTrialLimit,onValueChange={freeTrialLimit=it.filter(Char::isDigit).take(2)},label={Text("Free uses per feature (0–10)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={
                        scope.launch {
                            busy=true; error=null
                            try { saveSettings(idToken); loadData(idToken) }
                            catch(e:Exception) { error=e.message ?: "Could not save app settings." }
                            finally { busy=false }
                        }
                    },enabled=!busy && idToken.isNotBlank() && (freeTrialLimit.toIntOrNull() ?: -1) in 0..10,modifier=Modifier.fillMaxWidth()) {
                        Text("Save app settings")
                    }
                }
            }
            Text("Registered users & free-trial usage",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            OutlinedTextField(value=userSearch,onValueChange={userSearch=it},label={Text("Search by name or email")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("All","Active","Disabled","Trial eligible").forEach { filter ->
                    FilterChip(selected=userFilter==filter,onClick={userFilter=filter},label={Text(filter)})
                }
            }
            val filteredUsers = users.filter { user ->
                val matchesText = user.name.contains(userSearch,true) || user.email.contains(userSearch,true)
                val matchesFilter = when(userFilter) {
                    "Active" -> !user.disabled
                    "Disabled" -> user.disabled
                    "Trial eligible" -> user.trialEligible
                    else -> true
                }
                matchesText && matchesFilter
            }
            Text("Showing ${filteredUsers.size} of ${users.size} accounts",color=Slate400,fontSize=11.sp)
            if(users.isEmpty()) Text("No registered users found.",color=Slate400)
            else if(filteredUsers.isEmpty()) Text("No accounts match this search or filter.",color=Slate400)
            filteredUsers.forEach { user ->
                Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(user.name,color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text(user.email,color=ElectricCyan,fontSize=12.sp)
                        Text("Joined: ${user.joined} • Last sign-in: ${user.lastSignIn}",color=Slate400,fontSize=11.sp)
                        Text("Used — Screen: ${user.screen}/${freeTrialLimit.toIntOrNull() ?: 2} • Spoken: ${user.spoken}/${freeTrialLimit.toIntOrNull() ?: 2} • Transcription: ${user.transcription}/${freeTrialLimit.toIntOrNull() ?: 2}",color=Slate300,fontSize=12.sp)
                        Text(
                            if (user.trialEligible) "Free trial eligible • Remaining — Screen: ${user.screenRemaining}, Spoken: ${user.spokenRemaining}, Transcription: ${user.transcriptionRemaining}"
                            else "Not eligible for the new-user free trial",
                            color=if(user.trialEligible) ElectricCyan else Slate400,fontSize=11.sp
                        )
                        Text("Subscription: ${user.subscription} • Expires: ${user.expiry}",color=if(user.subscription=="active") EmeraldGreen else AmberWarning,fontSize=12.sp)
                        Text("Email: ${if(user.emailVerified) "Verified" else "Not verified"} • Account: ${if(user.disabled) "Disabled" else "Enabled"}",color=if(user.disabled) AmberWarning else Slate400,fontSize=11.sp)
                        OutlinedButton(onClick={
                            scope.launch {
                                busy=true; error=null
                                try { setUserDisabled(user.uid,!user.disabled) }
                                catch(e:Exception) { error=e.message ?: "Could not update account." }
                                finally { busy=false }
                            }
                        },enabled=!busy && user.uid.isNotBlank()) {
                            Text(if(user.disabled) "Enable account" else "Disable account")
                        }
                    }
                }
            }
            Text("Payment history",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            if(payments.isEmpty()) Text("No payment records yet. This section will populate after Paystack integration is connected.",color=Slate400,fontSize=12.sp)
            payments.forEach { payment ->
                Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(payment.email.ifBlank { "Customer payment" },color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text("${payment.amount} • ${payment.status}",color=ElectricCyan,fontSize=12.sp)
                        Text("${payment.reference} • ${payment.date}",color=Slate400,fontSize=11.sp)
                    }
                }
            }
            Text("Recent app activity and errors",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            Text("Only technical event details are stored here. Customer questions and uploaded content are not recorded.",color=Slate400,fontSize=11.sp)
            if(activityLogs.isEmpty()) Text("No activity records yet.",color=Slate400,fontSize=12.sp)
            activityLogs.forEach { activity ->
                Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("${activity.outcome.uppercase()} • ${activity.feature.ifBlank { "unknown feature" }}",color=if(activity.outcome=="success") ElectricCyan else Color(0xFFFFB74D),fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                        Text(activity.email.ifBlank { "Unknown account" },color=Slate300,fontSize=12.sp)
                        if(activity.provider.isNotBlank()) Text("Provider: ${activity.provider}",color=Slate400,fontSize=11.sp)
                        if(activity.code.isNotBlank()) Text("Error: ${activity.code}",color=Color(0xFFFF8A80),fontSize=11.sp)
                        Text("${activity.date} • Request: ${activity.requestId.take(12)}",color=Slate400,fontSize=10.sp)
                    }
                }
            }
            Text("Administrator activity log",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            Text("Recent account and customer-support actions are recorded here for accountability.",color=Slate400,fontSize=11.sp)
            if(auditLogs.isEmpty()) Text("No administrator actions recorded yet.",color=Slate400,fontSize=12.sp)
            auditLogs.forEach { log ->
                Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(
                            when(log.action) {
                                "account_disabled" -> "Account disabled"
                                "account_enabled" -> "Account re-enabled"
                                else -> log.action.replace('_',' ').replaceFirstChar { it.uppercase() }
                            },
                            color=Color.White,fontWeight=FontWeight.SemiBold
                        )
                        Text("Affected account: ${log.targetEmail.ifBlank { "Unknown" }}",color=Slate300,fontSize=12.sp)
                        if (log.detail.isNotBlank()) Text(log.detail,color=Slate300,fontSize=11.sp)
                        Text("Performed by: ${log.adminEmail.ifBlank { "Administrator" }}",color=ElectricCyan,fontSize=11.sp)
                        Text(log.date,color=Slate400,fontSize=11.sp)
                    }
                }
            }
            Text("Customer support requests",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            if(supportTickets.isEmpty()) Text("No customer support tickets yet.",color=Slate400,fontSize=12.sp)
            supportTickets.forEach { ticket ->
                var replyDraft by remember(ticket.id) { mutableStateOf(ticket.reply) }
                Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        Text(ticket.subject,color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text(ticket.email,color=ElectricCyan,fontSize=11.sp)
                        Text(ticket.message,color=Slate300,fontSize=12.sp)
                        Text("Status: ${ticket.status} • ${ticket.date}",color=AmberWarning,fontSize=11.sp)
                        if(ticket.reply.isNotBlank()) Text("Previous reply: ${ticket.reply}",color=Slate300,fontSize=12.sp)
                        OutlinedTextField(value=replyDraft,onValueChange={replyDraft=it.take(8000)},label={Text("Reply to customer")},minLines=2,modifier=Modifier.fillMaxWidth())
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(onClick={
                                scope.launch {
                                    busy=true; error=null
                                    try { updateSupportTicket(ticket.id,replyDraft,"in_progress") }
                                    catch(e:Exception) { error=e.message ?: "Could not send reply." }
                                    finally { busy=false }
                                }
                            },enabled=!busy && replyDraft.trim().length>=2,modifier=Modifier.weight(1f)) { Text("Reply") }
                            OutlinedButton(onClick={
                                scope.launch {
                                    busy=true; error=null
                                    try { updateSupportTicket(ticket.id,"","resolved") }
                                    catch(e:Exception) { error=e.message ?: "Could not resolve ticket." }
                                    finally { busy=false }
                                }
                            },enabled=!busy && ticket.status!="resolved",modifier=Modifier.weight(1f)) { Text("Resolve") }
                            if(ticket.status=="resolved") OutlinedButton(onClick={
                                scope.launch {
                                    busy=true; error=null
                                    try { updateSupportTicket(ticket.id,"","open") }
                                    catch(e:Exception) { error=e.message ?: "Could not reopen ticket." }
                                    finally { busy=false }
                                }
                            },enabled=!busy,modifier=Modifier.weight(1f)) { Text("Reopen") }
                        }
                    }
                }
            }
        }
        error?.let { Text(it,color=Color(0xFFFF8A80),fontSize=12.sp) }
        if(busy && loaded) LinearProgressIndicator(Modifier.fillMaxWidth(),color=ElectricCyan)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AdminMetric(label:String,value:String,modifier:Modifier=Modifier) {
    Card(modifier=modifier,colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(label,color=Slate400,fontSize=11.sp)
            Text(value,color=Color.White,fontSize=25.sp,fontWeight=FontWeight.Bold)
        }
    }
}
