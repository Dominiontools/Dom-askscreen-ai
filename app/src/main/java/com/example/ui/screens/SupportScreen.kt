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
import com.example.ui.theme.*

private const val SUPPORT_EMAIL = "Dominiontools56@gmail.com"
private const val SUPPORT_WHATSAPP = "2347083299414"
private const val SUPPORT_PHONE = "+2347052520118"

@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun launchSupportIntent(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No suitable app was found on this device.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open the selected support option.", Toast.LENGTH_LONG).show()
        }
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
                Button(
                    onClick = {
                        launchSupportIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$SUPPORT_WHATSAPP")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Chat on WhatsApp")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Slate900)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Email support", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(SUPPORT_EMAIL, color = ElectricCyan)
                OutlinedButton(
                    onClick = {
                        val uri = Uri.parse("mailto:$SUPPORT_EMAIL")
                        launchSupportIntent(Intent(Intent.ACTION_SENDTO, uri))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Email, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send an email")
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Slate900)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Call support", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("070 5252 0118", color = ElectricCyan)
                OutlinedButton(
                    onClick = {
                        launchSupportIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$SUPPORT_PHONE")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Call, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Call support")
                }
            }
        }
        HorizontalDivider(color = Slate700)
        Text("Send a question or complaint", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Write your message below. Your email app will open with the support address and message ready to send.", color = Slate400, fontSize = 12.sp)
        OutlinedTextField(
            value = subject, onValueChange = { subject = it },
            label = { Text("Subject") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = message, onValueChange = { message = it },
            label = { Text("Your question or complaint") },
            minLines = 4, modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = subject.isNotBlank() && message.isNotBlank(),
            onClick = {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$SUPPORT_EMAIL"))
                    .putExtra(Intent.EXTRA_SUBJECT, subject.trim())
                    .putExtra(Intent.EXTRA_TEXT, message.trim())
                launchSupportIntent(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Email, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Continue to send email")
        }
        Text("For your privacy, the app opens your email app; the message is sent only when you press Send there.", color = Slate400, fontSize = 11.sp)
    }
}
