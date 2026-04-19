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
import com.om.offlineai.viewmodel.DOWNLOADABLE_MODELS
import com.om.offlineai.viewmodel.DownloadableModel
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
//  Model Setup Screen — with built-in download support
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

    LaunchedEffect(state.state) {
        if (state.state is ModelState.Loaded) onModelLoaded()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ──────────────────────────────────────────────
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SmartToy, null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("OfflineAI", style = MaterialTheme.typography.headlineMedium)
                    Text("Pehle ek model download ya import karo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── Error / Loading / Download Progress ─────────────────
            item {
                when {
                    state.isDownloading -> {
                        DownloadProgressCard(
                            modelName = state.downloadingModel,
                            progress = state.downloadProgress,
                            downloadedMB = state.downloadedMB,
                            totalMB = state.totalMB,
                            onCancel = { vm.cancelDownload() }
                        )
                    }
                    state.isImporting || state.state is ModelState.Loading -> {
                        LoadingCard(
                            message = when {
                                state.isImporting -> "Importing… ${(state.importProgress * 100).toInt()}%"
                                else -> "Model load ho raha hai…"
                            },
                            progress = if (state.isImporting) state.importProgress else null
                        )
                    }
                    state.error != null -> {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(state.error!!, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.clearError() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ── Download models section ──────────────────────────────
            if (!state.isDownloading && state.state !is ModelState.Loading) {
                item {
                    Text("📥  App se seedha download karo",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }

                items(DOWNLOADABLE_MODELS) { model ->
                    DownloadableModelCard(
                        model = model,
                        onDownload = { vm.downloadModel(model) }
                    )
                }

                // ── Divider ──────────────────────────────────────────
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text("  ya  ", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(Modifier.weight(1f))
                    }
                }

                // ── Manual import ────────────────────────────────────
                item {
                    OutlinedButton(
                        onClick = { launcher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Phone se .gguf file import karo")
                    }
                }
            }

            // ── Device warning ───────────────────────────────────────
            state.deviceWarning?.let { warn ->
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp)) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(warn, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = { vm.clearWarning(); launcher.launch("*/*") }) {
                                    Text("Phir bhi import karo")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadableModelCard(
    model: DownloadableModel,
    onDownload: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (model.recommended)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.name, style = MaterialTheme.typography.labelLarge)
                    if (model.recommended) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        ) {
                            Text("BEST", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Text(model.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${model.sizeMB} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onDownload,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download ${model.name}")
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(
    modelName: String,
    progress: Float,
    downloadedMB: Int,
    totalMB: Int,
    onCancel: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Downloading, null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Downloading $modelName", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                if (totalMB > 0) "${downloadedMB}MB / ${totalMB}MB  (${(progress * 100).toInt()}%)"
                else "Connecting…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LoadingCard(message: String, progress: Float?) {
    Card(colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
            Text(message, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ImportButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.FolderOpen, null)
        Spacer(Modifier.width(8.dp))
        Text("Import GGUF Model")
    }
}
