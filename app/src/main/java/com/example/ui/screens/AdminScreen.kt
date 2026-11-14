package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private data class AdminUserRow(val name:String,val email:String,val joined:String,val lastSignIn:String,val screen:Int,val spoken:Int,val transcription:Int,val trialEligible:Boolean,val screenRemaining:Int,val spokenRemaining:Int,val transcriptionRemaining:Int,val subscription:String,val expiry:String)
private data class AdminPaymentRow(val date:String,val email:String,val reference:String,val amount:String,val status:String)
private data class AdminTicketRow(val id:String,val date:String,val email:String,val subject:String,val message:String,val status:String,val reply:String)

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
    var payments by remember { mutableStateOf<List<AdminPaymentRow>>(emptyList()) }
    var supportTickets by remember { mutableStateOf<List<AdminTicketRow>>(emptyList()) }
    var totalUsers by remember { mutableIntStateOf(0) }
    var activeSubscribers by remember { mutableIntStateOf(0) }
    var trialUsers by remember { mutableIntStateOf(0) }
    var disabledUsers by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }

    fun dateLabel(value:String):String = try {
        if (value.isBlank() || value == "null") "—" else DateFormat.getDateTimeInstance().format(Date.from(Instant.parse(value)))
    } catch (_:Exception) { "—" }

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
                    item.optString("displayName").ifBlank { item.optString("email").ifBlank { "Unnamed account" } },
                    item.optString("email"), dateLabel(item.optString("createdAt")), dateLabel(item.optString("lastSignInAt")),
                    usage.optInt("screen"), usage.optInt("spoken"), usage.optInt("transcription"),
                    trial.optBoolean("eligible"), trial.optInt("screenRemaining"), trial.optInt("spokenRemaining"),
                    trial.optInt("transcriptionRemaining"),
                    sub.optString("status","inactive"), dateLabel(sub.optString("currentPeriodEnd"))
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
            Text("Registered users & free-trial usage",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            if(users.isEmpty()) Text("No registered users found.",color=Slate400)
            users.forEach { user ->
                Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(user.name,color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text(user.email,color=ElectricCyan,fontSize=12.sp)
                        Text("Joined: ${user.joined} • Last sign-in: ${user.lastSignIn}",color=Slate400,fontSize=11.sp)
                        Text("Used — Screen: ${user.screen}/2 • Spoken: ${user.spoken}/2 • Transcription: ${user.transcription}/2",color=Slate300,fontSize=12.sp)
                        Text(
                            if (user.trialEligible) "Free trial eligible • Remaining — Screen: ${user.screenRemaining}, Spoken: ${user.spokenRemaining}, Transcription: ${user.transcriptionRemaining}"
                            else "Not eligible for the new-user free trial",
                            color=if(user.trialEligible) ElectricCyan else Slate400,fontSize=11.sp
                        )
                        Text("Subscription: ${user.subscription} • Expires: ${user.expiry}",color=if(user.subscription=="active") EmeraldGreen else AmberWarning,fontSize=12.sp)
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
