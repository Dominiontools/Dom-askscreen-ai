package com.example.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BubbleState
import com.example.data.model.ChatMessage
import com.example.data.model.QueryType
import com.example.data.model.WindowSizePreset
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingAnswerWindow(
    messages: List<ChatMessage>,
    bubbleState: BubbleState,
    liveCaptions: String,
    isTtsSpeaking: Boolean,
    currentPreset: WindowSizePreset,
    onPresetChange: (WindowSizePreset) -> Unit,
    onResizeDelta: (dx: Float, dy: Float) -> Unit,
    onDragWindowDelta: (dx: Float, dy: Float) -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onSendQuestion: (String) -> Unit,
    onStartVoiceInput: () -> Unit,
    onStopVoiceInput: () -> Unit,
    onActionClick: (String) -> Unit,
    onSpeakToggle: () -> Unit,
    onPickFile: () -> Unit,
    onExplainAnswer: (String) -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    var textScale by remember { mutableFloatStateOf(1.0f) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Pulse animation for active processing
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .border(
                1.5.dp,
                Brush.linearGradient(
                    listOf(
                        Color(0xFF38BDF8),
                        Color(0xFF818CF8),
                        Color(0xFFA855F7)
                    )
                ),
                RoundedCornerShape(16.dp)
            ),
        color = Color(0xFF0F172A).copy(alpha = 0.96f),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // DRAGGABLE TITLE BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragWindowDelta(dragAmount.x, dragAmount.y)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Icon + Title + State Indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .scale(if (bubbleState != BubbleState.IDLE) pulseScale else 1.0f)
                            .clip(CircleShape)
                            .background(
                                when (bubbleState) {
                                    BubbleState.IDLE -> Color(0xFF10B981)
                                    BubbleState.CAPTURING_SCREEN -> Color(0xFF38BDF8)
                                    BubbleState.LISTENING_AUDIO -> Color(0xFFF59E0B)
                                    BubbleState.PROCESSING_AI -> Color(0xFFA855F7)
                                    BubbleState.ERROR -> Color(0xFFEF4444)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Dominion Ask AI",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (bubbleState != BubbleState.IDLE) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (bubbleState) {
                                BubbleState.CAPTURING_SCREEN -> "Reading..."
                                BubbleState.LISTENING_AUDIO -> "Listening..."
                                BubbleState.PROCESSING_AI -> "Thinking..."
                                BubbleState.ERROR -> "Error"
                                else -> ""
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }

                // Right: Controls (A-, A+, Size Preset, TTS, Minimize, Close)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Text Zoom Down
                    IconButton(
                        onClick = { if (textScale > 0.8f) textScale -= 0.1f },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("A-", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    // Text Zoom Up
                    IconButton(
                        onClick = { if (textScale < 1.4f) textScale += 0.1f },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("A+", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // TTS Speaker Button
                    IconButton(
                        onClick = onSpeakToggle,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isTtsSpeaking) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                            contentDescription = "Text to Speech",
                            tint = if (isTtsSpeaking) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Preset Size Button
                    IconButton(
                        onClick = {
                            val next = when (currentPreset) {
                                WindowSizePreset.SMALL -> WindowSizePreset.MEDIUM
                                WindowSizePreset.MEDIUM -> WindowSizePreset.LARGE
                                WindowSizePreset.LARGE -> WindowSizePreset.FULLSCREEN
                                WindowSizePreset.FULLSCREEN -> WindowSizePreset.SMALL
                            }
                            onPresetChange(next)
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = when (currentPreset) {
                                WindowSizePreset.FULLSCREEN -> Icons.Default.FullscreenExit
                                else -> Icons.Default.AspectRatio
                            },
                            contentDescription = "Toggle Window Size",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Minimize Button
                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Minimize",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Close Button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // LIVE CAPTIONS / STATUS BANNER
            AnimatedVisibility(visible = liveCaptions.isNotEmpty()) {
                Surface(
                    color = Color(0xFF1E1B4B),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Live Audio",
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = liveCaptions,
                            color = Color(0xFFFDE68A),
                            fontSize = 11.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // QUICK ACTION CHIPS ROW
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val chips = listOf(
                    "📸 Read Screen" to "READ_SCREEN",
                    "🎧 Listen Screen" to "LISTEN_SCREEN",
                    "📺 Read & Listen" to "READ_AND_LISTEN",
                    "✂️ Select Region" to "SELECT_REGION",
                    "💡 Explain More" to "EXPLAIN_MORE",
                    "📝 Summarize" to "SUMMARIZE",
                    "🌐 Translate" to "TRANSLATE",
                    "📋 Extract Text" to "EXTRACT_TEXT",
                    "📋 Copy Answer" to "COPY_ANSWER",
                    "🗑️ Clear" to "CLEAR_CHAT"
                )
                items(chips) { (label, action) ->
                    SuggestionChip(
                        onClick = { onActionClick(action) },
                        label = { Text(label, fontSize = 11.sp, color = Color.White) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF1E293B)
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = Color(0xFF334155)
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            // MESSAGE LIST (ANSWERS & QUESTIONS)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Ready to assist your screen!",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "Tap 'Read Screen' or 'Listen' above to analyze this app.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg = msg, textScale = textScale, onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AskScreen Answer", msg.text))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }, onExplain = { onExplainAnswer(msg.text) })
                }
            }

            // INPUT ROW (VOICE + ATTACHMENT + TEXT + SEND)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice input microphone button
                IconButton(
                    onClick = {
                        if (bubbleState == BubbleState.LISTENING_AUDIO) {
                            onStopVoiceInput()
                        } else {
                            onStartVoiceInput()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (bubbleState == BubbleState.LISTENING_AUDIO) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Voice Input",
                        tint = if (bubbleState == BubbleState.LISTENING_AUDIO) Color(0xFFEF4444) else Color(0xFF38BDF8)
                    )
                }

                // File attachment button
                IconButton(
                    onClick = onPickFile,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Upload File",
                        tint = Color(0xFF94A3B8)
                    )
                }

                // Text field
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask follow-up...", fontSize = 12.sp, color = Color(0xFF64748B)) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 90.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569),
                        cursorColor = Color(0xFF38BDF8)
                    ),
                    shape = RoundedCornerShape(20.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Send button
                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isNotEmpty()) {
                            onSendQuestion(text)
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && bubbleState == BubbleState.IDLE,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank() && bubbleState == BubbleState.IDLE) Color(0xFF38BDF8) else Color(0xFF475569)
                    )
                }

                // RESIZE CORNER HANDLE (Bottom-right corner)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onResizeDelta(dragAmount.x, dragAmount.y)
                            }
                        },
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        imageVector = Icons.Default.SouthEast,
                        contentDescription = "Drag to Resize Window",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage, textScale: Float, onCopy: () -> Unit, onExplain: () -> Unit = {}) {
    val isUser = msg.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Query Type Tag
        if (msg.queryType != QueryType.TEXT) {
            Surface(
                color = if (isUser) Color(0xFF1E3A8A) else Color(0xFF312E81),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Text(
                    text = msg.queryType.displayName,
                    fontSize = (9 * textScale).sp,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Surface(
            color = if (isUser) Color(0xFF2563EB) else Color(0xFF1E293B),
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 2.dp,
                bottomEnd = if (isUser) 2.dp else 12.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // If this is an AI answer, check if there is a highlighted correct option
                val correctOptionMatch = remember(msg.text) {
                    Regex("(?i)(\\*\\*correct option:?.*?\\*\\*|correct answer:?.*)", RegexOption.MULTILINE).find(msg.text)?.value
                }

                if (correctOptionMatch != null && !isUser) {
                    Surface(
                        color = Color(0xFF065F46),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = correctOptionMatch.replace("*", "").trim(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = (12 * textScale).sp
                            )
                        }
                    }
                }

                Text(
                    text = msg.text,
                    color = Color.White,
                    fontSize = (12 * textScale).sp,
                    lineHeight = (17 * textScale).sp
                )

                if (!isUser) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Explain",
                            fontSize = 10.sp,
                            color = Color(0xFF34D399),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onExplain() }
                                .padding(4.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Copy",
                            fontSize = 10.sp,
                            color = Color(0xFF38BDF8),
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onCopy() }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}
