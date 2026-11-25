package com.karal.studio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.karal.studio.R
import com.karal.studio.data.repository.SettingsKeys
import com.karal.studio.runtime.RuntimeState
import com.karal.studio.ui.theme.Amber
import com.karal.studio.ui.theme.Cyan
import com.karal.studio.ui.theme.Green
import com.karal.studio.ui.theme.Red

@Composable
fun HomeScreen(vm: KaralViewModel, nav: NavController) {
    val count by vm.projectCount.collectAsState(initial = 0)
    val recent by vm.recentProjects.collectAsState(initial = emptyList())
    val bots by vm.bots.collectAsState(initial = emptyList())
    val commands by vm.commands.collectAsState(initial = emptyList())
    val logs by vm.logs.collectAsState(initial = emptyList())
    val snapshots by vm.runtime.collectAsState(initial = emptyMap())
    val running = snapshots.values.count { it.state in setOf(RuntimeState.READY, RuntimeState.CONNECTING, RuntimeState.RECONNECTING) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayLarge)
            Text("Local-first Discord bot studio", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.total_projects), count.toString(), Icons.Filled.Folder, Modifier.weight(1f))
                StatCard(stringResource(R.string.total_bots), bots.size.toString(), Icons.Filled.CloudUpload, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(stringResource(R.string.running_bots), running.toString(), Icons.Filled.PlayArrow, Modifier.weight(1f))
                StatCard(stringResource(R.string.total_commands), commands.size.toString(), Icons.Filled.Code, Modifier.weight(1f))
            }
        }
        item { SectionTitle(stringResource(R.string.quick_start)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("project/new") }) { Text(stringResource(R.string.new_project)) }
                OutlinedButton(onClick = { nav.navigate("templates?projectId=") }) { Text(stringResource(R.string.templates)) }
                OutlinedButton(onClick = { nav.navigate("console?botId=") }) { Text(stringResource(R.string.console)) }
            }
        }
        item { SectionTitle(stringResource(R.string.recent_projects)) }
        if (recent.isEmpty()) item { EmptyCard(stringResource(R.string.no_data)) }
        items(recent, key = { it.id }) { project ->
            Card(
                Modifier.fillMaxWidth().clickable { nav.navigate("project/${project.id}") },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(project.description.ifBlank { project.botName }, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionTitle(stringResource(R.string.recent_logs)) }
        if (logs.isEmpty()) item { EmptyCard(stringResource(R.string.no_data)) }
        items(logs.take(6), key = { it.id }) { log ->
            Text("${log.level}: ${log.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ProjectsScreen(vm: KaralViewModel, nav: NavController) {
    val all by vm.projects.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val filtered = all.filter {
        it.name.contains(query, true) || it.description.contains(query, true)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importProject(uri) { result ->
            result.onSuccess { nav.navigate("project/$it") }
                .onFailure { status = it.message ?: "Import failed" }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }
        if (status.isNotBlank()) item { InfoCard(status) }
        item { InfoCard(stringResource(R.string.uninstall_note)) }
        if (filtered.isEmpty()) item { EmptyCard(stringResource(R.string.no_data)) }
        items(filtered, key = { it.id }) { project ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium)
                    Text(project.description.ifBlank { "No description" }, style = MaterialTheme.typography.bodySmall)
                    if (project.archived) StatusChip("Archived", Amber)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { nav.navigate("project/${project.id}") }) { Text("Open") }
                        OutlinedButton(onClick = { vm.duplicateProject(project.id) { result ->
                            result.onSuccess { nav.navigate("project/$it") }
                                .onFailure { status = it.message ?: "Duplicate failed" }
                        } }) { Text(stringResource(R.string.duplicate)) }
                        OutlinedButton(onClick = {
                            vm.setArchived(project.id, !project.archived)
                        }) { Text(if (project.archived) "Unarchive" else stringResource(R.string.archive)) }
                        TextButton(onClick = { confirmDelete = project.id }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("project/new") }) { Text(stringResource(R.string.new_project)) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/zip", "*/*")) }) {
                    Text(stringResource(R.string.import_project))
                }
            }
        }
    }
    confirmDelete?.let { id ->
        ConfirmDialog(stringResource(R.string.delete), "Delete this project and all of its local data?", stringResource(R.string.delete),
            onConfirm = { vm.deleteProject(id); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
}

@Composable
fun StudioScreen(vm: KaralViewModel, nav: NavController) {
    val all by vm.commands.collectAsState(initial = emptyList())
    val projects by vm.projects.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val filtered = all.filter { it.name.contains(query, true) || it.description.contains(query, true) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.nav_studio), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (filtered.isEmpty()) item { EmptyCard(stringResource(R.string.no_data)) }
        items(filtered, key = { it.id }) { command ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("/${command.name}", style = MaterialTheme.typography.titleMedium)
                    Text(command.description.ifBlank { "No description" }, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(command.source, Cyan)
                        StatusChip(command.language, if (command.approved) Green else Amber)
                        if (!command.approved) StatusChip("NEEDS APPROVAL", Red)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            val route = when (command.source) {
                                "VISUAL" -> "command/${command.id}/visual"
                                "AI" -> "command/${command.id}/ai"
                                else -> "command/${command.id}/script"
                            }
                            nav.navigate(route)
                        }) { Text("Edit") }
                        if (!command.approved) {
                            OutlinedButton(onClick = { vm.approveCommand(command.id) {} }) { Text(stringResource(R.string.approve)) }
                        }
                        TextButton(onClick = { confirmDelete = command.id }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
        item {
            val firstProject = projects.firstOrNull()?.id.orEmpty()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("command/new?projectId=$firstProject") }, enabled = firstProject.isNotBlank()) {
                    Text(stringResource(R.string.new_command))
                }
                OutlinedButton(onClick = { nav.navigate("templates?projectId=$firstProject") }) { Text(stringResource(R.string.templates)) }
            }
        }
    }
    confirmDelete?.let { id ->
        ConfirmDialog(stringResource(R.string.delete), "Delete this command permanently?", stringResource(R.string.delete),
            onConfirm = { vm.deleteCommand(id); confirmDelete = null }, onDismiss = { confirmDelete = null })
    }
}

@Composable
fun HostingScreen(vm: KaralViewModel, nav: NavController) {
    val bots by vm.bots.collectAsState(initial = emptyList())
    val snapshots by vm.runtime.collectAsState(initial = emptyMap())
    val online by vm.networkOnline.collectAsState(initial = false)
    val projects by vm.projects.collectAsState(initial = emptyList())

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.nav_hosting), style = MaterialTheme.typography.headlineMedium)
            StatusChip(if (online) "Network online" else "Network offline", if (online) Green else Red)
            InfoCard(stringResource(R.string.battery_note))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::startAll) { Text(stringResource(R.string.start_all)) }
                OutlinedButton(onClick = vm::stopAll) { Text(stringResource(R.string.stop_all)) }
                OutlinedButton(onClick = { nav.navigate("console?botId=") }) { Text(stringResource(R.string.console)) }
            }
        }
        if (bots.isEmpty()) item { EmptyCard("Create a project and add a bot token first.") }
        items(bots, key = { it.id }) { bot ->
            val snapshot = snapshots[bot.id]
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bot.name, style = MaterialTheme.typography.titleMedium)
                    Text(projects.firstOrNull { it.id == bot.projectId }?.name.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    StatusChip(snapshot?.state?.name ?: "STOPPED", when (snapshot?.state) {
                        RuntimeState.READY -> Green
                        RuntimeState.ERROR -> Red
                        RuntimeState.STOPPED -> MaterialTheme.colorScheme.outline
                        else -> Amber
                    })
                    if (!snapshot?.lastError.isNullOrBlank()) Text(snapshot!!.lastError, color = Red, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { vm.startBot(bot.id) }) { Text(stringResource(R.string.start)) }
                        OutlinedButton(onClick = { vm.stopBot(bot.id) }) { Text(stringResource(R.string.stop)) }
                        OutlinedButton(onClick = { nav.navigate("bot/${bot.id}") }) { Text("Details") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    vm: KaralViewModel,
    nav: NavController,
    theme: String,
    localeTag: String,
    onLocaleChange: (String) -> Unit
) {
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var aiKey by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("24") }
    var hasKey by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.hasAiKey { hasKey = it } }
    LaunchedEffectUnit {
        endpoint = vm.setting(SettingsKeys.AI_ENDPOINT, "https://api.openai.com/v1/chat/completions")
        model = vm.setting(SettingsKeys.AI_MODEL, "gpt-4o-mini")
        interval = vm.setting(SettingsKeys.BACKUP_INTERVAL_HOURS, "24")
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineMedium) }
        item {
            SectionTitle(stringResource(R.string.theme))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(SettingsKeys.THEME_SYSTEM to "System", SettingsKeys.THEME_DARK to "Dark", SettingsKeys.THEME_LIGHT to "Light").forEach { (key, label) ->
                    if (key == theme) Button(onClick = { vm.putSetting(SettingsKeys.THEME, key) }) { Text(label) }
                    else OutlinedButton(onClick = { vm.putSetting(SettingsKeys.THEME, key) }) { Text(label) }
                }
            }
        }
        item {
            SectionTitle(stringResource(R.string.language))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (localeTag == SettingsKeys.LANGUAGE_HI) Button(onClick = { vm.putSetting(SettingsKeys.LANGUAGE, SettingsKeys.LANGUAGE_HI); onLocaleChange(SettingsKeys.LANGUAGE_HI) }) { Text("हिन्दी") }
                else OutlinedButton(onClick = { vm.putSetting(SettingsKeys.LANGUAGE, SettingsKeys.LANGUAGE_HI); onLocaleChange(SettingsKeys.LANGUAGE_HI) }) { Text("हिन्दी") }
                if (localeTag == SettingsKeys.LANGUAGE_EN) Button(onClick = { vm.putSetting(SettingsKeys.LANGUAGE, SettingsKeys.LANGUAGE_EN); onLocaleChange(SettingsKeys.LANGUAGE_EN) }) { Text("English") }
                else OutlinedButton(onClick = { vm.putSetting(SettingsKeys.LANGUAGE, SettingsKeys.LANGUAGE_EN); onLocaleChange(SettingsKeys.LANGUAGE_EN) }) { Text("English") }
            }
        }
        item {
            SectionTitle(stringResource(R.string.security))
            InfoCard(stringResource(R.string.uninstall_note))
            Text("Bot tokens are encrypted with Android Keystore and never exported.")
            Text("AI/imported code must be approved before it can execute.")
        }
        item {
            SectionTitle(stringResource(R.string.backup))
            OutlinedTextField(value = interval, onValueChange = { interval = it }, label = { Text("Backup interval (hours)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.putSetting(SettingsKeys.BACKUP_INTERVAL_HOURS, interval) }) { Text("Save interval") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate("backups") }) { Text(stringResource(R.string.backups, "")) }
                OutlinedButton(onClick = { nav.navigate("diagnostics") }) { Text(stringResource(R.string.diagnostics)) }
                OutlinedButton(onClick = { nav.navigate("about") }) { Text(stringResource(R.string.about)) }
            }
        }
        item {
            var aiStatus by remember { mutableStateOf("") }
            SectionTitle("AI builder")
            Field(endpoint, { endpoint = it }, "OpenAI-compatible endpoint")
            Field(model, { model = it }, "Model")
            Field(aiKey, { aiKey = it }, "API key (leave blank to keep)", visualTransformation = PasswordVisualTransformation())
            Text(if (hasKey) "Key stored in Keystore" else "No key configured", style = MaterialTheme.typography.bodySmall)
            if (aiStatus.isNotBlank()) Text(aiStatus, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.putSetting(SettingsKeys.AI_ENDPOINT, endpoint)
                    vm.putSetting(SettingsKeys.AI_MODEL, model)
                    if (aiKey.isNotBlank()) {
                        vm.setAiKey(aiKey) { r ->
                            aiStatus = r.fold({ "AI settings saved" }, { it.message ?: "Failed to save AI key" })
                            if (r.isSuccess) { aiKey = ""; vm.hasAiKey { hasKey = it } }
                        }
                    } else {
                        aiStatus = "AI settings saved"
                    }
                }) { Text(stringResource(R.string.save)) }
                OutlinedButton(onClick = {
                    vm.clearAiKey { r ->
                        aiStatus = r.fold({ "AI key cleared" }, { it.message ?: "Failed to clear key" })
                        if (r.isSuccess) vm.hasAiKey { hasKey = it }
                    }
                }) { Text("Clear key") }
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LaunchedEffectUnit(block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}
