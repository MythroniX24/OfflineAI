package com.om.offlineai.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.om.offlineai.data.db.entities.AppSettings
import com.om.offlineai.data.db.entities.KnowledgeItem
import com.om.offlineai.data.db.entities.Memory
import com.om.offlineai.viewmodel.*

// ═══════════════════════════════════════════════════════════════════
//  Memory Manager Screen
// ═══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit, vm: MemoryViewModel = hiltViewModel()) {
    val memories by vm.memories.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Memory?>(null) }
    var deleteTarget by remember { mutableStateOf<Memory?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Memory") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { showClearAll = true }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, contentDescription = "Add memory")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { vm.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Search memory…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = RoundedCornerShape(24.dp)
            )
            Text("${memories.size} memories",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                items(memories, key = { it.id }) { mem ->
                    MemoryItem(mem,
                        onEdit   = { editTarget = mem },
                        onDelete = { deleteTarget = mem },
                        onPin    = { vm.setPinned(mem, !mem.isPinned) },
                        onImportant = { vm.setImportant(mem, !mem.isImportant) })
                }
            }
        }
    }

    // Add dialog
    if (showAddDialog) {
        MemoryEditDialog(
            title = "Add Memory",
            initial = "",
            onConfirm = { content -> vm.add(content); showAddDialog = false },
            onDismiss = { showAddDialog = false }
        )
    }

    // Edit dialog
    editTarget?.let { mem ->
        MemoryEditDialog(
            title = "Edit Memory",
            initial = mem.content,
            onConfirm = { content -> vm.update(mem.copy(content = content)); editTarget = null },
            onDismiss = { editTarget = null }
        )
    }

    // Delete confirm
    deleteTarget?.let { mem ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Memory?") },
            text  = { Text(mem.content.take(80)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(mem); deleteTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    // Clear all confirm
    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("Clear All Memory?") },
            text  = { Text("This will delete all ${memories.size} memories permanently.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteAll(); showClearAll = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Clear All")
                }
            },
            dismissButton = { TextButton(onClick = { showClearAll = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MemoryItem(
    memory: Memory,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onImportant: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val categoryColor = when (memory.category) {
        "business"   -> MaterialTheme.colorScheme.tertiary
        "preference" -> MaterialTheme.colorScheme.secondary
        "fact"       -> MaterialTheme.colorScheme.primary
        else         -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ListItem(
        headlineContent = { Text(memory.content) },
        leadingContent = {
            when {
                memory.isPinned    -> Icon(Icons.Default.PushPin, null, tint = MaterialTheme.colorScheme.primary)
                memory.isImportant -> Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.secondary)
                else               -> Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        supportingContent = {
            SuggestionChip(onClick = {}, label = { Text(memory.category, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.height(22.dp))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Edit, null) })
                    DropdownMenuItem(text = { Text(if (memory.isPinned) "Unpin" else "Pin") },
                        onClick = { onPin(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) })
                    DropdownMenuItem(text = { Text(if (memory.isImportant) "Unmark" else "Mark Important") },
                        onClick = { onImportant(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Star, null) })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
    )
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun MemoryEditDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(), maxLines = 5,
                placeholder = { Text("e.g. I prefer short answers") })
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Knowledge Manager Screen
// ═══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeScreen(onBack: () -> Unit, vm: KnowledgeViewModel = hiltViewModel()) {
    val items by vm.items.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<KnowledgeItem?>(null) }
    var deleteTarget by remember { mutableStateOf<KnowledgeItem?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Base") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, null)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery, onValueChange = { vm.setSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("Search knowledge…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = RoundedCornerShape(24.dp)
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                items(items, key = { it.id }) { item ->
                    KnowledgeItemRow(item,
                        onEdit   = { editTarget = item },
                        onDelete = { deleteTarget = item },
                        onPin    = { vm.setPinned(item, !item.isPinned) })
                }
            }
        }
    }

    if (showAddDialog) {
        KnowledgeEditDialog(null, onConfirm = { t, c, cat ->
            vm.add(t, c, cat); showAddDialog = false
        }, onDismiss = { showAddDialog = false })
    }

    editTarget?.let { item ->
        KnowledgeEditDialog(item, onConfirm = { t, c, cat ->
            vm.update(item.copy(title = t, content = c, category = cat)); editTarget = null
        }, onDismiss = { editTarget = null })
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete?") },
            text  = { Text(item.title) },
            confirmButton = {
                TextButton(onClick = { vm.delete(item); deleteTarget = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun KnowledgeItemRow(item: KnowledgeItem, onEdit: () -> Unit, onDelete: () -> Unit, onPin: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(item.title) },
        supportingContent = { Text(item.content.take(80), maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = {
            Icon(if (item.isPinned) Icons.Default.PushPin else Icons.Default.MenuBook,
                null, tint = if (item.isPinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { onEdit(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Edit, null) })
                    DropdownMenuItem(text = { Text(if (item.isPinned) "Unpin" else "Pin") },
                        onClick = { onPin(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { onDelete(); showMenu = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
    )
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun KnowledgeEditDialog(
    item: KnowledgeItem?,
    onConfirm: (title: String, content: String, category: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(item?.title ?: "") }
    var content by remember { mutableStateOf(item?.content ?: "") }
    var category by remember { mutableStateOf(item?.category ?: "general") }
    val categories = listOf("general", "business", "product", "workflow", "template")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add Knowledge" else "Edit Knowledge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("Content") }, maxLines = 6, modifier = Modifier.fillMaxWidth())
                // Category selector
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.take(3).forEach { cat ->
                        FilterChip(selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.drop(3).forEach { cat ->
                        FilterChip(selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank() && content.isNotBlank()) onConfirm(title.trim(), content.trim(), category) },
                enabled = title.isNotBlank() && content.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Settings Screen
// ═══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onModelInfo: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsState()
    var showClearChats by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var editPrompt by remember { mutableStateOf(false) }
    var systemPromptText by remember { mutableStateOf(settings.systemPrompt) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SettingsSectionHeader("Model") }
            item {
                ListItem(
                    headlineContent = { Text("Model Info") },
                    supportingContent = { Text(if (settings.modelName.isNotBlank()) settings.modelName else "No model loaded") },
                    leadingContent = { Icon(Icons.Default.SmartToy, null) },
                    modifier = Modifier.clickable(onClick = onModelInfo)
                )
            }

            item { SettingsSectionHeader("Inference") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Max Tokens: ${settings.maxTokens}", style = MaterialTheme.typography.labelMedium)
                    Slider(value = settings.maxTokens.toFloat(), onValueChange = {
                        vm.save(settings.copy(maxTokens = it.toInt()))
                    }, valueRange = 64f..2048f, steps = 31)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Context Size: ${settings.nCtx}", style = MaterialTheme.typography.labelMedium)
                    Slider(value = settings.nCtx.toFloat(), onValueChange = {
                        vm.save(settings.copy(nCtx = it.toInt()))
                    }, valueRange = 512f..4096f, steps = 7)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Thread Count: ${if (settings.nThreads == 0) "Auto" else settings.nThreads}",
                        style = MaterialTheme.typography.labelMedium)
                    Slider(value = settings.nThreads.toFloat(), onValueChange = {
                        vm.save(settings.copy(nThreads = it.toInt()))
                    }, valueRange = 0f..8f, steps = 8)
                }
            }

            item { SettingsSectionHeader("Behavior") }
            item {
                SwitchListItem("Streaming Responses", "Show response as it's generated",
                    settings.streamingEnabled) { vm.save(settings.copy(streamingEnabled = it)) }
            }
            item {
                SwitchListItem("Auto-Memory Suggestions", "Suggest saving user preferences to memory",
                    settings.autoMemoryEnabled) { vm.save(settings.copy(autoMemoryEnabled = it)) }
            }
            item {
                SwitchListItem("Hinglish Mode", "Include Hinglish language context in prompts",
                    settings.hinglishMode) { vm.save(settings.copy(hinglishMode = it)) }
            }
            item {
                SwitchListItem("Dark Mode", "Use dark theme",
                    settings.darkMode) { vm.save(settings.copy(darkMode = it)) }
            }

            item { SettingsSectionHeader("System Prompt") }
            item {
                ListItem(
                    headlineContent = { Text("Edit System Prompt") },
                    supportingContent = { Text(settings.systemPrompt.take(60) + "…") },
                    leadingContent = { Icon(Icons.Default.Code, null) },
                    modifier = Modifier.clickable { editPrompt = true; systemPromptText = settings.systemPrompt }
                )
            }

            item { SettingsSectionHeader("Data") }
            item {
                ListItem(
                    headlineContent = { Text("Clear All Chats", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { showClearChats = true }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Reset App Data", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Clears chats, memory, knowledge, and settings") },
                    leadingContent = { Icon(Icons.Default.RestartAlt, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { showReset = true }
                )
            }
            item { SettingsSectionHeader("About") }
            item {
                ListItem(
                    headlineContent = { Text("Device") },
                    supportingContent = { Text(vm.deviceInfo) },
                    leadingContent = { Icon(Icons.Default.PhoneAndroid, null) }
                )
            }
        }
    }

    if (editPrompt) {
        AlertDialog(
            onDismissRequest = { editPrompt = false },
            title = { Text("System Prompt") },
            text = {
                OutlinedTextField(value = systemPromptText, onValueChange = { systemPromptText = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp), maxLines = 12)
            },
            confirmButton = {
                TextButton(onClick = { vm.save(settings.copy(systemPrompt = systemPromptText)); editPrompt = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editPrompt = false }) { Text("Cancel") } }
        )
    }
    if (showClearChats) {
        AlertDialog(
            onDismissRequest = { showClearChats = false },
            title = { Text("Clear All Chats?") },
            confirmButton = { TextButton(onClick = { vm.clearAllChats(); showClearChats = false },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { showClearChats = false }) { Text("Cancel") } }
        )
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset App Data?") },
            text  = { Text("All chats, memory, knowledge, and settings will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { vm.resetAll(); showReset = false },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(title,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SwitchListItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Model Info Screen
// ═══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelInfoScreen(onBack: () -> Unit, vm: ModelViewModel = hiltViewModel()) {
    val state by vm.uiState.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Model Info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Status", when (state.state) {
                        is ModelState.Loaded  -> "✅ Loaded"
                        is ModelState.Loading -> "⏳ Loading"
                        is ModelState.Error   -> "❌ Error"
                        else -> "⬜ Not loaded"
                    })
                    InfoRow("Name", state.modelName.ifBlank { "—" })
                    InfoRow("Path", state.modelPath.ifBlank { "—" })
                    InfoRow("Size", if (state.modelSizeMB > 0) "${state.modelSizeMB} MB" else "—")
                    if (state.modelInfo.isNotBlank() && state.modelInfo != "{}") {
                        InfoRow("Info", state.modelInfo)
                    }
                }
            }

            if (state.state is ModelState.Loaded) {
                Button(onClick = { vm.reloadModel() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Reload Model")
                }
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Model")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Model?") },
            text  = { Text("The model file will be deleted from storage. You will need to import again.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteModel(); showDeleteConfirm = false; onBack() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}
