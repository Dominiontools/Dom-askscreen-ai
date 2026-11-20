package com.example.ui.screens

import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.DateFormat
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.math.BigDecimal
import java.math.RoundingMode

private data class AdminUserRow(val uid:String,val name:String,val email:String,val joined:String,val lastSignIn:String,val screen:Int,val spoken:Int,val transcription:Int,val trialEligible:Boolean,val screenRemaining:Int,val spokenRemaining:Int,val transcriptionRemaining:Int,val subscription:String,val expiry:String,val productAccess:String,val disabled:Boolean,val emailVerified:Boolean)
private data class AdminPaymentRow(val id:String,val provider:String,val productName:String,val network:String,val transactionHash:String,val destinationAddress:String,val date:String,val email:String,val reference:String,val amount:String,val status:String)
private data class AdminTicketRow(val id:String,val date:String,val email:String,val subject:String,val message:String,val status:String,val reply:String)
private data class AdminAuditRow(val action:String,val targetEmail:String,val adminEmail:String,val detail:String,val date:String)
private data class AdminPriceRow(val id:String,val name:String,val category:String,val monthlyPriceCents:Int,val priceText:String)
private data class AdminActivityRow(val email:String,val feature:String,val outcome:String,val code:String,val provider:String,val requestId:String,val date:String)

@Composable
fun AdminScreen(onBack: () -> Unit) {
    val prefs = remember { AskScreenApp.instance.preferencesManager }
    val scope = rememberCoroutineScope()
    val http = remember { OkHttpClient() }
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var adminAccessCode by remember { mutableStateOf("") }
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
    var pricingRows by remember { mutableStateOf<List<AdminPriceRow>>(emptyList()) }
    var bundleDiscountPercent by remember { mutableStateOf("10") }
    var totalUsers by remember { mutableIntStateOf(0) }
    var activeSubscribers by remember { mutableIntStateOf(0) }
    var trialUsers by remember { mutableIntStateOf(0) }
    var disabledUsers by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var releaseVersionCode by remember { mutableStateOf("3") }
    var releaseVersionName by remember { mutableStateOf("1.1.0") }
    var releaseNotes by remember { mutableStateOf("") }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var selectedApkName by remember { mutableStateOf("") }
    var selectedApkSize by remember { mutableLongStateOf(0L) }
    var releaseMessage by remember { mutableStateOf<String?>(null) }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedApkUri = uri
            selectedApkName = "selected.apk"
            selectedApkSize = 0L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) selectedApkName = cursor.getString(nameIndex) ?: "selected.apk"
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) selectedApkSize = cursor.getLong(sizeIndex)
                }
            }
            releaseMessage = null
        }
    }
    var grantDialogOpen by remember { mutableStateOf(false) }
    var grantTargetUid by remember { mutableStateOf("") }
    var selectedGrantProduct by remember { mutableStateOf("video_annotation") }
    var grantExpiry by remember { mutableStateOf("") }
    var showGrantProductMenu by remember { mutableStateOf(false) }

    fun dateLabel(value:String):String {
        if (value.isBlank() || value == "null") return "—"
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "EEE, dd MMM yyyy HH:mm:ss zzz"
        )
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = parser.parse(value)
                if (parsed != null) return DateFormat.getDateTimeInstance().format(parsed)
            } catch (_: Exception) { }
        }
        return "—"
    }

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
        bundleDiscountPercent = settings.optInt("bundleDiscountPercent",10).toString()
        val priceArray = settings.optJSONArray("pricing")
        if (priceArray != null) {
            pricingRows = buildList {
                for (i in 0 until priceArray.length()) {
                    val item = priceArray.optJSONObject(i) ?: continue
                    val cents = item.optInt("monthlyPriceCents")
                    add(AdminPriceRow(item.optString("id"),item.optString("name"),item.optString("category"),cents,String.format(Locale.US,"%.2f",cents / 100.0)))
                }
            }
        }
    }

    suspend fun saveSettings(token:String) {
        val limit = freeTrialLimit.toIntOrNull() ?: throw IllegalArgumentException("Free-trial limit must be a whole number from 0 to 10.")
        if (limit !in 0..10) throw IllegalArgumentException("Free-trial limit must be a whole number from 0 to 10.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/admin-settings"
        val features = JSONObject().put("screen",screenEnabled).put("spoken",spokenEnabled).put("transcription",transcriptionEnabled)
        val discount = bundleDiscountPercent.toIntOrNull() ?: throw IllegalArgumentException("Bundle discount must be a whole number from 0 to 30.")
        if (discount !in 0..30) throw IllegalArgumentException("Bundle discount must be a whole number from 0 to 30.")
        if (pricingRows.isEmpty() || pricingRows.any { it.priceText.toBigDecimalOrNull() == null || it.priceText.toBigDecimal() < BigDecimal.ZERO }) throw IllegalArgumentException("Enter a valid non-negative monthly price for every product.")
        val priceArray = org.json.JSONArray()
        pricingRows.forEach { row ->
            priceArray.put(JSONObject().put("id",row.id).put("monthlyPriceCents",row.monthlyPriceCents))
        }
        val payload = JSONObject().put("announcement",announcement).put("features",features).put("freeTrialLimit",limit)
            .put("pricing",priceArray).put("bundleDiscountPercent",discount)
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
                val productArray = item.optJSONArray("productSubscriptions")
                val activeProducts = buildList {
                    if (productArray != null) for (productIndex in 0 until productArray.length()) {
                        val product = productArray.optJSONObject(productIndex) ?: continue
                        if (product.optString("status") == "active") {
                            add(product.optString("name") + " (until " + dateLabel(product.optString("currentPeriodEnd")) + ")")
                        }
                    }
                }.joinToString(" • ").ifBlank { "No active Work Centre products" }
                add(AdminUserRow(
                    item.optString("uid"), item.optString("displayName").ifBlank { item.optString("email").ifBlank { "Unnamed account" } },
                    item.optString("email"), dateLabel(item.optString("createdAt")), dateLabel(item.optString("lastSignInAt")),
                    usage.optInt("screen"), usage.optInt("spoken"), usage.optInt("transcription"),
                    trial.optBoolean("eligible"), trial.optInt("screenRemaining"), trial.optInt("spokenRemaining"),
                    trial.optInt("transcriptionRemaining"),
                    sub.optString("status","inactive"), dateLabel(sub.optString("currentPeriodEnd")), activeProducts,
                    item.optBoolean("disabled"), item.optBoolean("emailVerified")
                ))
            }
        }
        val paymentArray = result.optJSONArray("payments")
        payments = buildList {
            if (paymentArray != null) for (i in 0 until paymentArray.length()) {
                val item = paymentArray.optJSONObject(i) ?: continue
                add(AdminPaymentRow(item.optString("id"),item.optString("provider"),item.optString("productName"),item.optString("network"),item.optString("transactionHash"),item.optString("destinationAddress"),dateLabel(item.optString("createdAt")),item.optString("email"),item.optString("reference").ifBlank { item.optString("id") },item.optString("amount","—")+" "+item.optString("currency","NGN"),item.optString("status","unknown")))
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

    suspend fun setProductAccess(uid:String, productId:String, action:String, expiry:String = "") {
        if (idToken.isBlank()) throw IllegalStateException("Sign in to the administrator account again.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/admin-product-subscriptions"
        val payload = JSONObject().put("uid",uid).put("productId",productId).put("action",action)
        if (action == "grant") payload.put("currentPeriodEnd",expiry)
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint)
                .header("Authorization","Bearer $idToken")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Could not update product access." })
            }
        }
        loadData(idToken)
    }

    suspend fun reviewCryptoPayment(paymentId:String, action:String) {
        if (idToken.isBlank()) throw IllegalStateException("Sign in to the administrator account again.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your Netlify AskScreen endpoint in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/admin-crypto-payments"
        val payload = JSONObject().put("paymentId",paymentId).put("action",action)
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint).header("Authorization","Bearer $idToken")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val json = try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Could not review this payment." })
            }
        }
        loadData(idToken)
    }

    suspend fun publishApk() {
        val versionCode = releaseVersionCode.toIntOrNull()
            ?: throw IllegalArgumentException("Version code must be a whole number.")
        if (versionCode < 2) throw IllegalArgumentException("Version code must be at least 2.")
        if (releaseVersionName.isBlank()) throw IllegalArgumentException("Enter the version name.")
        val selectedUri = selectedApkUri ?: throw IllegalArgumentException("Choose the signed APK from this device.")
        if (!selectedApkName.lowercase(Locale.US).endsWith(".apk")) throw IllegalArgumentException("Choose a file ending in .apk.")
        val configured = prefs.backendUrl.trim().trimEnd('/')
        if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set your AskScreen backend URL in Settings first.")
        val endpoint = configured.removeSuffix("/ask-screen") + "/app-update"
        val tempApk = File.createTempFile("dom-askscreen-release-", ".apk", context.cacheDir)
        try {
            val sizeAndHash = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(selectedUri)?.use { input ->
                    FileOutputStream(tempApk).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("The selected APK could not be opened.")
                val archiveInfo = context.packageManager.getPackageArchiveInfo(tempApk.absolutePath, 0)
                    ?: throw IllegalArgumentException("Android could not read this APK. Choose a valid APK file.")
                if (archiveInfo.packageName != "com.dominion.askscreenai") {
                    throw IllegalArgumentException("This APK is not the DOM Ask AI app package.")
                }
                val apkVersionCode = if (Build.VERSION.SDK_INT >= 28) archiveInfo.longVersionCode else archiveInfo.versionCode.toLong()
                if (apkVersionCode != versionCode.toLong()) {
                    throw IllegalArgumentException("The selected APK version code is $apkVersionCode, but you entered $versionCode. Make them match.")
                }
                val size = tempApk.length()
                if (size <= 0L || size > 300L * 1024L * 1024L) {
                    throw IllegalArgumentException("The APK must be between 1 byte and 300 MB.")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                tempApk.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val checksum = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                size to checksum
            }
            val preparePayload = JSONObject()
                .put("action", "prepare_upload")
                .put("versionCode", versionCode)
                .put("versionName", releaseVersionName.trim())
                .put("releaseNotes", releaseNotes.trim())
                .put("sizeBytes", sizeAndHash.first)
                .put("sha256", sizeAndHash.second)
            val prepared = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $idToken")
                    .post(preparePayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Could not prepare the APK upload." })
                    json
                }
            }
            val uploadUrl = prepared.optString("uploadUrl")
            val uploadId = prepared.optString("uploadId")
            if (uploadUrl.isBlank() || uploadId.isBlank()) throw IllegalStateException("The server did not return a secure APK upload session.")
            withContext(Dispatchers.IO) {
                val uploadRequest = Request.Builder().url(uploadUrl)
                    .header("Content-Type", "application/vnd.android.package-archive")
                    .put(tempApk.asRequestBody("application/vnd.android.package-archive".toMediaType()))
                    .build()
                http.newCall(uploadRequest).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("APK upload failed (HTTP ${response.code}). Please try again.")
                }
            }
            val publishPayload = JSONObject().put("action", "publish_uploaded").put("uploadId", uploadId)
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $idToken")
                    .post(publishPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "The APK uploaded, but publishing failed." })
                }
            }
            releaseMessage = "Version ${releaseVersionName.trim()} has been uploaded and published. Customer apps can now check for this update."
            selectedApkUri = null
            selectedApkName = ""
            selectedApkSize = 0L
        } finally {
            tempApk.delete()
        }
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
                    Text("Enter the administrator email approved by the owner and the separate access code. No Firebase password is required.",color=Slate300,fontSize=13.sp)
                    OutlinedTextField(value=email,onValueChange={email=it.trim()},label={Text("Admin email")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=adminAccessCode,onValueChange={adminAccessCode=it.take(128)},label={Text("Administrator access code")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                    Button(onClick={
                        scope.launch {
                            busy=true; error=null
                            try {
                                val configured=prefs.backendUrl.trim().trimEnd('/')
                                if (!configured.endsWith("/ask-screen")) throw IllegalStateException("Set the Netlify AskScreen endpoint in Settings first.")
                                val endpoint=configured.removeSuffix("/ask-screen") + "/admin-login"
                                val payload=JSONObject().put("email",email.trim()).put("accessCode",adminAccessCode)
                                val token=withContext(Dispatchers.IO) {
                                    val request=Request.Builder().url(endpoint)
                                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                                    http.newCall(request).execute().use { response ->
                                        val body=response.body?.string().orEmpty()
                                        val json=try { JSONObject(body) } catch (_:Exception) { JSONObject() }
                                        if (!response.isSuccessful) throw IllegalStateException(json.optString("error").ifBlank { "Admin sign-in failed (HTTP ${response.code})." })
                                        json.optString("token").takeIf { it.isNotBlank() }
                                            ?: throw IllegalStateException("The server did not return an administrator session.")
                                    }
                                }
                                idToken=token
                                adminAccessCode=""
                                loadData(token)
                            } catch(e:Exception) {
                                idToken=""
                                loaded=false
                                error=e.message ?: "Admin sign-in failed or this account is not authorized."
                            } finally { busy=false }
                        }
                    },enabled=!busy && email.contains("@") && adminAccessCode.length>=16,modifier=Modifier.fillMaxWidth()) {
                        if(busy) CircularProgressIndicator(Modifier.size(18.dp),color=Color.White,strokeWidth=2.dp) else Text("Sign in to Admin Centre")
                    }
                }
            }
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={refresh()},enabled=!busy,modifier=Modifier.weight(1f)) { Icon(Icons.Default.Refresh,contentDescription=null); Spacer(Modifier.width(6.dp)); Text("Refresh data") }
                OutlinedButton(onClick={idToken="";loaded=false;users=emptyList();payments=emptyList();supportTickets=emptyList();auditLogs=emptyList();activityLogs=emptyList();error=null}) { Text("Sign out") }
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
                    HorizontalDivider(color=Slate700)
                    Text("Subscription & product pricing",color=Color.White,fontSize=16.sp,fontWeight=FontWeight.Bold)
                    Text("Set each product's monthly price in US dollars. Email Management remains a separate product. Prices are stored in cents for accurate billing.",color=Slate400,fontSize=11.sp)
                    OutlinedTextField(value=bundleDiscountPercent,onValueChange={bundleDiscountPercent=it.filter(Char::isDigit).take(2)},label={Text("Complete bundle discount (0–30%)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    pricingRows.groupBy { it.category }.forEach { (category, rows) ->
                        Text(category,color=Color(0xFF8CB4FF),fontSize=13.sp,fontWeight=FontWeight.Bold)
                        rows.forEach { row ->
                            OutlinedTextField(
                                value=row.priceText,
                                onValueChange={ value ->
                                    val cents = value.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }?.multiply(BigDecimal(100))?.setScale(0,RoundingMode.HALF_UP)?.toInt()
                                    pricingRows = pricingRows.map { if (it.id == row.id) it.copy(priceText=value,monthlyPriceCents=cents?.takeIf { amount -> amount <= 1000000 } ?: it.monthlyPriceCents) else it }
                                },
                                label={Text(if (row.id == "core") "DOM AI Core — fixed USD 9/month" else "${row.name} — USD/month")},
                                singleLine=true,
                                enabled=row.id != "core",
                                modifier=Modifier.fillMaxWidth()
                            )
                        }
                    }
                    val coreCents = pricingRows.firstOrNull { it.id == "core" }?.monthlyPriceCents ?: 900
                    val specialistCents = pricingRows.filter { it.id != "core" && it.id != "email_management" }.sumOf { it.monthlyPriceCents }
                    val emailCents = pricingRows.firstOrNull { it.id == "email_management" }?.monthlyPriceCents ?: 2000
                    val discount = bundleDiscountPercent.toIntOrNull()?.coerceIn(0,30) ?: 10
                    val aiRegular = coreCents + specialistCents
                    val fullRegular = aiRegular + emailCents
                    val aiDiscounted = (aiRegular * (100-discount) + 50) / 100
                    val fullDiscounted = (fullRegular * (100-discount) + 50) / 100
                    Text("AI Suite: USD ${String.format(Locale.US,"%.2f",aiDiscounted/100.0)} / month",color=Color.White,fontWeight=FontWeight.SemiBold)
                    Text("Complete Business Bundle (including Email Management): USD ${String.format(Locale.US,"%.2f",fullDiscounted/100.0)} / month",color=Color(0xFF8FE0B0),fontWeight=FontWeight.SemiBold)
                    Button(onClick={
                        scope.launch {
                            busy=true; error=null
                            try { saveSettings(idToken); loadData(idToken) }
                            catch(e:Exception) { error=e.message ?: "Could not save app settings." }
                            finally { busy=false }
                        }
                    },enabled=!busy && idToken.isNotBlank() && (freeTrialLimit.toIntOrNull() ?: -1) in 0..10 && (bundleDiscountPercent.toIntOrNull() ?: -1) in 0..30 && pricingRows.isNotEmpty() && pricingRows.all { it.priceText.toBigDecimalOrNull() != null && it.priceText.toBigDecimal() >= BigDecimal.ZERO && it.priceText.toBigDecimal().multiply(BigDecimal(100)) <= BigDecimal(1000000) },modifier=Modifier.fillMaxWidth()) {
                        Text("Save app settings")
                    }
                }
            }
            Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("App Update Centre",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
                    Text("Select the signed APK directly from this device. It uploads securely to the app's Firebase Storage bucket; you do not need to create a GitHub Release or paste a link.",color=Slate400,fontSize=11.sp)
                    OutlinedTextField(value=releaseVersionName,onValueChange={releaseVersionName=it.take(40)},label={Text("Version name (example: 1.1)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=releaseVersionCode,onValueChange={releaseVersionCode=it.filter(Char::isDigit).take(9)},label={Text("Version code (must increase each release)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(onClick={apkPicker.launch(arrayOf("application/vnd.android.package-archive","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                        Text(if(selectedApkName.isBlank()) "Choose APK from this device" else "Choose a different APK")
                    }
                    if(selectedApkName.isNotBlank()) {
                        Text("Selected: $selectedApkName",color=Color.White,fontSize=12.sp)
                        if(selectedApkSize>0L) Text("File size: ${String.format(Locale.US,"%.2f",selectedApkSize/1024.0/1024.0)} MB",color=Slate400,fontSize=11.sp)
                    }
                    OutlinedTextField(value=releaseNotes,onValueChange={releaseNotes=it.take(2000)},label={Text("What's new in this update")},supportingText={Text("${releaseNotes.length}/2000 characters")},minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth())
                    Button(onClick={
                        scope.launch {
                            busy = true; error = null; releaseMessage = null
                            try { publishApk() }
                            catch(e:Exception) { error = e.message ?: "Could not upload this app update." }
                            finally { busy = false }
                        }
                    },enabled=!busy && idToken.isNotBlank() && selectedApkUri!=null && releaseVersionCode.toIntOrNull()!=null && releaseVersionName.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
                        Text(if(busy) "Uploading and publishing…" else "Upload and publish APK")
                    }
                    releaseMessage?.let { Text(it,color=ElectricCyan,fontSize=12.sp) }
                    Text("Important: upload only the signed APK built with the same package name and signing key as the customer app. Increase versionCode for every release. The app calculates the APK SHA-256 checksum automatically. Android will still ask customers to approve installation.",color=AmberWarning,fontSize=11.sp,lineHeight=16.sp)
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
                        Text("Work Centre access: ${user.productAccess}",color=if(user.productAccess.startsWith("No active")) Slate400 else EmeraldGreen,fontSize=11.sp)
                        OutlinedButton(onClick={
                            grantTargetUid=user.uid
                            grantExpiry=""
                            selectedGrantProduct=pricingRows.firstOrNull { it.id != "core" }?.id ?: "video_annotation"
                            grantDialogOpen=true
                        },enabled=!busy && user.uid.isNotBlank()) { Text("Manage Work Centre access") }
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
            Text("Payment review queue",color=Color.White,fontSize=17.sp,fontWeight=FontWeight.Bold)
            Text("Verify every USDT transaction directly on the selected blockchain before approving. A screenshot alone is not proof of payment.",color=AmberWarning,fontSize=11.sp)
            if(payments.isEmpty()) Text("No payment records have been submitted yet.",color=Slate400,fontSize=12.sp)
            payments.forEach { payment ->
                Card(colors=CardDefaults.cardColors(containerColor=Slate900),shape=RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(payment.productName.ifBlank { "Customer payment" },color=Color.White,fontWeight=FontWeight.SemiBold)
                        Text(payment.email,color=ElectricCyan,fontSize=12.sp)
                        Text("${payment.amount} • ${payment.network.ifBlank { payment.provider }} • ${payment.status.uppercase()}",color=ElectricCyan,fontSize=12.sp)
                        if(payment.transactionHash.isNotBlank()) Text("Transaction: ${payment.transactionHash}",color=Slate300,fontSize=10.sp)
                        if(payment.destinationAddress.isNotBlank()) Text("Destination: ${payment.destinationAddress}",color=Slate400,fontSize=10.sp)
                        Text("${payment.reference} • ${payment.date}",color=Slate400,fontSize=11.sp)
                        if(payment.provider=="USDT_MANUAL" && payment.status=="pending") {
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Button(onClick={
                                    scope.launch {
                                        busy=true; error=null
                                        try { reviewCryptoPayment(payment.id,"approve") }
                                        catch(e:Exception) { error=e.message ?: "Could not approve payment." }
                                        finally { busy=false }
                                    }
                                },enabled=!busy && payment.id.isNotBlank(),modifier=Modifier.weight(1f)) { Text("Approve & activate") }
                                OutlinedButton(onClick={
                                    scope.launch {
                                        busy=true; error=null
                                        try { reviewCryptoPayment(payment.id,"reject") }
                                        catch(e:Exception) { error=e.message ?: "Could not reject payment." }
                                        finally { busy=false }
                                    }
                                },enabled=!busy && payment.id.isNotBlank(),modifier=Modifier.weight(1f)) { Text("Reject") }
                            }
                        }
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
        if (grantDialogOpen) {
            val targetUser = users.firstOrNull { it.uid == grantTargetUid }
            val selectedProductName = pricingRows.firstOrNull { it.id == selectedGrantProduct }?.name ?: selectedGrantProduct
            AlertDialog(
                onDismissRequest = { if (!busy) grantDialogOpen = false },
                title = { Text("Manage Work Centre access") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Customer: ${targetUser?.email ?: grantTargetUid}",fontSize=12.sp)
                        Box {
                            OutlinedButton(onClick={showGrantProductMenu=true},modifier=Modifier.fillMaxWidth()) { Text(selectedProductName) }
                            DropdownMenu(expanded=showGrantProductMenu,onDismissRequest={showGrantProductMenu=false}) {
                                pricingRows.filter { it.id != "core" }.forEach { product ->
                                    DropdownMenuItem(text={Text(product.name)},onClick={selectedGrantProduct=product.id;showGrantProductMenu=false})
                                }
                            }
                        }
                        OutlinedTextField(
                            value=grantExpiry,onValueChange={grantExpiry=it.take(40)},
                            label={Text("Access expiry (ISO date/time)")},
                            placeholder={Text("2027-11-15T00:00:00Z")},
                            supportingText={Text("Required for a manual grant. Maximum two years ahead.")},
                            singleLine=true,modifier=Modifier.fillMaxWidth()
                        )
                        Text("Manual grants are recorded in the administrator audit log. They do not represent a Paystack payment.",color=Slate400,fontSize=11.sp)
                    }
                },
                confirmButton = {
                    TextButton(enabled=!busy && grantExpiry.isNotBlank(),onClick={
                        scope.launch {
                            busy=true;error=null
                            try { setProductAccess(grantTargetUid,selectedGrantProduct,"grant",grantExpiry);grantDialogOpen=false }
                            catch(e:Exception) { error=e.message ?: "Could not grant product access." }
                            finally { busy=false }
                        }
                    }) { Text("Grant access") }
                },
                dismissButton = {
                    Row {
                        TextButton(enabled=!busy,onClick={
                            scope.launch {
                                busy=true;error=null
                                try { setProductAccess(grantTargetUid,selectedGrantProduct,"revoke");grantDialogOpen=false }
                                catch(e:Exception) { error=e.message ?: "Could not revoke product access." }
                                finally { busy=false }
                            }
                        }) { Text("Revoke") }
                        TextButton(enabled=!busy,onClick={grantDialogOpen=false}) { Text("Cancel") }
                    }
                }
            )
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
