package com.om.offlineai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.om.offlineai.data.db.entities.Conversation
import com.om.offlineai.engine.ModelState
import com.om.offlineai.viewmodel.*
import java.text.SimpleDateFormat
import java.util.*

// ═══════════════════════════════════════════════════════════════════
//  Conversation List Screen
// ═══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onOpenChat: (Long) -> Unit,
    onNewChat: (Long) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenKnowledge: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: ConversationListViewModel = hiltViewModel(),
    chatVm: ChatViewModel = hiltViewModel()
) {
    val conversations by vm.conversations.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("OfflineAI") },
                actions = {
                    IconButton(onClick = onOpenMemory) {
                        Icon(Icons.Default.Psychology, contentDescription = "Memory")
                    }
                    IconButton(onClick = onOpenKnowledge) {
                        Icon(Icons.Default.MenuBook, contentDescription = "Knowledge")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            val scope = rememberCoroutineScope()
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        val id = chatVm.createNewConversation()
                        onNewChat(id)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New Chat") }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { vm.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search chats…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) IconButton(onClick = { vm.setSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ChatBubbleOutline, contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))
                        Text("No chats yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tap + to start", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(conversations, key = { it.id }) { conv ->
                        ConversationItem(
                            conv = conv,
                            onOpen   = { onOpenChat(conv.id) },
                            onRename = { renameTarget = conv; renameText = conv.title },
                            onDelete = { deleteTarget = conv },
                            onPin    = { vm.pinConversation(conv.id, !conv.isPinned) }
                        )
                    }
                }
            }
        }
    }

    // Rename dialog
    renameTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameConversation(conv.id, renameText)
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    // Delete confirm
    deleteTarget?.let { conv ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Chat?") },
            text = { Text("\"${conv.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteConversation(conv); deleteTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ConversationItem(
    conv: Conversation,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable { onOpen() },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conv.isPinned) {
                    Icon(Icons.Default.PushPin, contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = {
            Text(
                "${conv.messageCount} messages • ${fmt.format(Date(conv.updatedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Chat, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { onRename(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) })
                    DropdownMenuItem(
                        text = { Text(if (conv.isPinned) "Unpin" else "Pin") },
                        onClick = { onPin(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
    )
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

// ═══════════════════════════════════════════════════════════════════
//  Model Setup Screen
// ═══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSetupScreen(
    onModelLoaded: () -> Unit,
    vm: ModelViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.importModel(it) } }

    // Navigate away when model loaded
    LaunchedEffect(state.state) {
        if (state.state is ModelState.Loaded) onModelLoaded()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.SmartToy, contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("Welcome to OfflineAI", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Import a GGUF model to get started.\nRecommended: TinyLlama Q4_0 (~700MB)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)

            Spacer(Modifier.height(32.dp))

            when (val s = state.state) {
                is ModelState.Loading -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    if (state.isImporting) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { state.importProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Importing… ${(state.importProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text("Loading model…", style = MaterialTheme.typography.labelMedium)
                    }
                }
                is ModelState.Error -> {
                    Text(s.message, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    ImportButton { launcher.launch("*/*") }
                }
                else -> ImportButton { launcher.launch("*/*") }
            }

            state.deviceWarning?.let { warn ->
                Spacer(Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(warn, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { vm.clearWarning(); launcher.launch("*/*") }) {
                                Text("Import anyway")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            // Help card
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Where to get models?", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("• HuggingFace → search 'TinyLlama GGUF'\n• TheBloke models (Q4_0 variants)\n• Phi-2, Gemma-2B, Mistral 7B Q4\n\nCopy .gguf file to your phone, then import.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ImportButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.FolderOpen, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Import GGUF Model")
    }
}
