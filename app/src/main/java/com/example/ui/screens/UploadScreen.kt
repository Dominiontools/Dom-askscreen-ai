package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.AskScreenApp
import com.example.data.model.MediaItem
import com.example.data.model.QueryType
import com.example.service.TtsManager
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

@Composable
fun UploadScreen() {
    val context = LocalContext.current
    val repository = remember { AskScreenApp.instance.repository }
    val scope = rememberCoroutineScope()
    val tts = remember { TtsManager(context) }

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMimeType by remember { mutableStateOf("") }
    var selectedFileName by remember { mutableStateOf("") }
    var selectedFileSizeKb by remember { mutableStateOf(0L) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var userCustomPrompt by remember { mutableStateOf("") }
    var analysisMode by remember { mutableStateOf("Answer Questions") }
    var isAnalyzing by remember { mutableStateOf(false) }
    var aiAnswer by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // Media Picker Launchers
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            handleFileSelected(context, it) { mime, name, size, bmp ->
                selectedUri = it
                selectedMimeType = mime
                selectedFileName = name
                selectedFileSizeKb = size
                previewBitmap = bmp
                errorMessage = ""
            }
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            handleFileSelected(context, it) { mime, name, size, bmp ->
                selectedUri = it
                selectedMimeType = mime
                selectedFileName = name
                selectedFileSizeKb = size
                previewBitmap = bmp
                errorMessage = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate950)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("upload_screen_content")
    ) {
        Text(
            text = "MEDIA & FILE UPLOADER",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400,
            letterSpacing = 1.sp
        )
        Text(
            text = "Upload any screenshot, photo, PDF, video, or audio file to analyze with Gemini",
            fontSize = 12.sp,
            color = Slate400
        )

        Spacer(modifier = Modifier.height(16.dp))

        // UPLOAD TYPE BUTTONS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UploadButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Image,
                label = "Screenshot",
                onClick = { imagePicker.launch("image/*") }
            )
            UploadButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CameraAlt,
                label = "Photo",
                onClick = { imagePicker.launch("image/*") }
            )
            UploadButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.PictureAsPdf,
                label = "PDF Doc",
                onClick = { documentPicker.launch("application/pdf") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            UploadButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.VideoFile,
                label = "Video",
                onClick = { documentPicker.launch("video/*") }
            )
            UploadButton(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.AudioFile,
                label = "Audio File",
                onClick = { documentPicker.launch("audio/*") }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // SELECTED FILE PREVIEW
        if (selectedUri != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Slate900,
                border = BorderStroke(1.dp, Slate700)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when {
                                    selectedMimeType.startsWith("image") -> Icons.Default.Image
                                    selectedMimeType.startsWith("audio") -> Icons.Default.AudioFile
                                    selectedMimeType.startsWith("video") -> Icons.Default.VideoFile
                                    else -> Icons.Default.Description
                                },
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = selectedFileName.ifEmpty { "Selected File" },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "${selectedMimeType.ifEmpty { "file" }} • ${selectedFileSizeKb} KB",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                        }

                        IconButton(onClick = {
                            selectedUri = null
                            previewBitmap = null
                            aiAnswer = ""
                        }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Clear", tint = Slate400)
                        }
                    }

                    if (previewBitmap != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ANALYSIS MODE CHIPS
            Text(
                text = "CHOOSE ANALYSIS GOAL",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Slate400
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modes = listOf("Answer Questions", "Summarize", "Translate", "Extract Text")
                modes.forEach { mode ->
                    FilterChip(
                        selected = analysisMode == mode,
                        onClick = { analysisMode = mode },
                        label = { Text(mode, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan,
                            selectedLabelColor = Slate950,
                            containerColor = Slate900,
                            labelColor = Slate300
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = userCustomPrompt,
                onValueChange = { userCustomPrompt = it },
                placeholder = { Text("Optional: Custom question about this file...", fontSize = 12.sp, color = Slate600) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = Slate700,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ANALYZE BUTTON
            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    isAnalyzing = true
                    errorMessage = ""
                    aiAnswer = ""

                    scope.launch {
                        try {
                            val base64Data = withContext(Dispatchers.IO) {
                                val stream = context.contentResolver.openInputStream(uri)
                                val bytes = stream?.readBytes() ?: ByteArray(0)
                                stream?.close()
                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                            }

                            if (base64Data.isEmpty()) {
                                errorMessage = "Could not read file."
                                isAnalyzing = false
                                return@launch
                            }

                            val promptText = if (userCustomPrompt.isNotBlank()) {
                                userCustomPrompt
                            } else {
                                when (analysisMode) {
                                    "Summarize" -> "Summarize the key information, formulas, or takeaways from this uploaded file in clear bullet points."
                                    "Translate" -> "Translate the main contents and any questions in this file into English and Spanish."
                                    "Extract Text" -> "Extract and transcribe all readable text verbatim from this file."
                                    else -> "Analyze this file for learning and reference. If it contains practice exercises or self-study questions, identify each question and give a concise answer. For multiple choice, return only the option letter and answer text. Explain only when the user asks or the question requires an explanation. Do not guess unreadable content."
                                }
                            }

                            val fileItem = MediaItem(
                                mimeType = selectedMimeType.ifEmpty { "application/octet-stream" },
                                base64Data = base64Data,
                                fileName = selectedFileName
                            )

                            val result = repository.askScreenAi(
                                prompt = promptText,
                                queryType = QueryType.FILE,
                                files = listOf(fileItem)
                            )

                            result.onSuccess { answer ->
                                aiAnswer = answer
                                isAnalyzing = false
                            }.onFailure { err ->
                                errorMessage = err.message ?: "Failed to analyze file."
                                isAnalyzing = false
                            }
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Unexpected error reading file."
                            isAnalyzing = false
                        }
                    }
                },
                enabled = !isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("analyze_file_button"),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
            ) {
                if (isAnalyzing) {
                    CircularProgressIndicator(color = Slate950, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyzing with Gemini...", color = Slate950, fontWeight = FontWeight.Bold)
                } else {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, tint = Slate950)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyze with Gemini", color = Slate950, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ERROR BANNER
        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                color = CrimsonRed.copy(alpha = 0.2f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, CrimsonRed)
            ) {
                Text(
                    text = errorMessage,
                    color = CrimsonRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // AI RESULT DISPLAY
        if (aiAnswer.isNotEmpty()) {
            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Slate900,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Analysis Result",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Row {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("AskScreen Result", aiAnswer))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Slate400)
                            }
                            IconButton(onClick = {
                                if (tts.isSpeaking.value) tts.stop() else tts.speak(aiAnswer)
                            }) {
                                Icon(Icons.Default.VolumeUp, contentDescription = "Speak", tint = ElectricCyan)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = aiAnswer,
                        fontSize = 13.sp,
                        color = Color.White,
                        lineHeight = 19.sp
                    )
                }
            }
        }
    }
}

@Composable
fun UploadButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Slate800, RoundedCornerShape(12.dp)),
        color = Slate900
    ) {
        Column(
            modifier = Modifier
                .clickable { onClick() }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = ElectricCyan, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, fontSize = 11.sp, color = Slate300, fontWeight = FontWeight.Medium)
        }
    }
}

private fun handleFileSelected(
    context: Context,
    uri: Uri,
    callback: (mime: String, name: String, sizeKb: Long, bmp: Bitmap?) -> Unit
) {
    try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var name = uri.lastPathSegment ?: "file"
        var sizeKb = 0L
        var bmp: Bitmap? = null

        val pfd = resolver.openFileDescriptor(uri, "r")
        sizeKb = (pfd?.statSize ?: 0L) / 1024
        pfd?.close()

        if (mime.startsWith("image")) {
            val input: InputStream? = resolver.openInputStream(uri)
            bmp = BitmapFactory.decodeStream(input)
            input?.close()
        }

        callback(mime, name, sizeKb, bmp)
    } catch (e: Exception) {
        callback("application/octet-stream", "file", 0L, null)
    }
}
