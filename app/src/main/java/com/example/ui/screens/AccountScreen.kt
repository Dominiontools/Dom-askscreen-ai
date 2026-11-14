package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
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
import com.example.ui.theme.*

@Composable
fun AccountScreen(onAdminAccess: () -> Unit = {}) {
    val auth = remember { FirebaseAuth.getInstance() }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signedInEmail by remember { mutableStateOf(auth.currentUser?.email) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var createAccount by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(Slate950).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(54.dp))
        Spacer(Modifier.height(12.dp))
        Text("YOUR ASKSCREEN ACCOUNT", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (signedInEmail != null) {
            Text("Signed in as", color = Slate400)
            Text(signedInEmail.orEmpty(), color = ElectricCyan, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            Text("Your free feature allowances and subscription will be linked to this account.", color = Slate300)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { auth.signOut(); signedInEmail = null; message = "You have signed out." }) { Text("Sign out") }
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
                        busy = false
                        if (result.isSuccessful) {
                            signedInEmail = auth.currentUser?.email
                            message = if (createAccount) "Account created successfully." else "Signed in successfully."
                        } else {
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
            Text(it, color = if (it.contains("success", true)) ElectricCyan else Color(0xFFFFB4AB), fontSize = 12.sp)
        }
        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = Slate700)
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
