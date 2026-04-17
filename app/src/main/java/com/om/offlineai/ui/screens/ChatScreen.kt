package com.om.offlineai.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.om.offlineai.data.db.entities.Message
import com.om.offlineai.ui.theme.*
import com.om.offlineai.viewmodel.ChatViewModel
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    convId: Long,
    onBack: () -> Unit,
    onMemory: () -> Unit,
    vm: ChatViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

    // Open the conversation when screen loads
    LaunchedEffect(convId) { vm.openConversation(convId) }

    // Auto-scroll to bottom on new message
    LaunchedEffect(state.messages.size, state.streamingText) {
        if (state.messages.isNotEmpty() || state.streamingText.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(Int.MAX_VALUE) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("OfflineAI", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onMemory) {
                        Icon(Icons.Default.Psychology, contentDescription = "Memory")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Message list ───────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Empty state
                if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
                    item {
                        EmptyStateHint()
                    }
                }

                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(message = msg)
                }

                // Streaming bubble
                if (state.streamingText.isNotEmpty()) {
                    item {
                        StreamingBubble(text = state.streamingText)
                    }
                }

                // Typing indicator
                if (state.isGenerating && state.streamingText.isEmpty()) {
                    item { TypingIndicator() }
                }
            }

            // ── Memory suggestion banner ───────────────────────────────────
            AnimatedVisibility(visible = state.suggestedMemory != null) {
                MemorySuggestionBanner(
                    text = state.suggestedMemory ?: "",
                    onAccept = { vm.acceptMemorySuggestion() },
                    onDismiss = { vm.dismissMemorySuggestion() }
                )
            }

            // ── Error bar ─────────────────────────────────────────────────
            state.error?.let { err ->
                ErrorBar(err) { vm.clearError() }
            }

            // ── Input row ─────────────────────────────────────────────────
            InputRow(
                text       = inputText,
                onTextChange = { inputText = it },
                isGenerating = state.isGenerating,
                onSend = {
                    if (inputText.isNotBlank()) {
                        vm.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                onStop = { vm.stopGeneration() }
            )
        }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == "user"
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // AI avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("AI", fontSize = 10.sp, color = Color.White)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = if (isUser) 16.dp else 4.dp,
                            topEnd   = if (isUser) 4.dp  else 16.dp,
                            bottomStart = 16.dp,
                            bottomEnd   = 16.dp
                        )
                    )
                    .background(if (isUser) UserBubble else AssistantBubble)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (isUser) {
                    Text(
                        text  = message.content,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    // Render markdown for assistant messages
                    MarkdownText(
                        markdown = message.content,
                        color    = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp
                    )
                }
            }

            // Copy action for assistant
            if (!isUser) {
                Row(horizontalArrangement = Arrangement.Start) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(message.content)) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (isUser) Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) { Text("AI", fontSize = 10.sp, color = Color.White) }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(AssistantBubble)
                .padding(12.dp)
        ) {
            MarkdownText(markdown = "$text ▊", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
        }
    }
}

@Composable
private fun TypingIndicator() {
    val dots = remember { androidx.compose.runtime.mutableStateOf("") }
    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            dots.value = ".".repeat((i % 3) + 1)
            i++
            kotlinx.coroutines.delay(500)
        }
    }
    Row(Modifier.padding(start = 40.dp)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(AssistantBubble)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(dots.value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
        }
    }
}

@Composable
private fun InputRow(
    text: String,
    onTextChange: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = if (isGenerating) onStop else onSend,
                enabled = isGenerating || text.isNotBlank(),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = if (isGenerating) Icons.Default.Stop else Icons.Default.Send,
                    contentDescription = if (isGenerating) "Stop" else "Send",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun MemorySuggestionBanner(text: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Psychology, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Save to memory?  \"${text.take(50)}…\"",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(onClick = onAccept) { Text("Save") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Error, contentDescription = null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun EmptyStateHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.SmartToy, contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))
        Text("OfflineAI", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        Text("Fully local. Fully private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
