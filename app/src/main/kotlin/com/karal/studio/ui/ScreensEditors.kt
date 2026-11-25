package com.karal.studio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.karal.studio.R
import com.karal.studio.core.compiler.CompileException
import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CommandSource
import com.karal.studio.core.model.NodeCatalog
import com.karal.studio.core.model.NodeGroup
import com.karal.studio.core.model.ScriptLanguage
import com.karal.studio.core.model.TriggerSpec
import com.karal.studio.core.model.Workflow
import com.karal.studio.core.model.WorkflowEdge
import com.karal.studio.core.model.WorkflowNode
import com.karal.studio.data.db.CommandEntity
import com.karal.studio.data.db.ProjectEntity
import com.karal.studio.data.repository.CommandRepository
import com.karal.studio.ui.theme.Amber
import com.karal.studio.ui.theme.Cyan
import com.karal.studio.ui.theme.Green
import com.karal.studio.ui.theme.Red
import java.util.UUID

@Composable
fun NewProjectScreen(vm: KaralViewModel, nav: NavController) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var botName by remember { mutableStateOf("") }
    var botId by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("!") }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.new_project), style = MaterialTheme.typography.headlineMedium)
        Field(name, { name = it }, stringResource(R.string.name))
        Field(description, { description = it }, stringResource(R.string.description))
        Field(botName, { botName = it }, stringResource(R.string.bot_name))
        Field(botId, { botId = it }, stringResource(R.string.bot_id))
        Field(appId, { appId = it }, stringResource(R.string.application_id))
        Field(prefix, { prefix = it }, "Command prefix")
        if (error.isNotBlank()) Text(error, color = Red)
        Button(onClick = {
            vm.createProject(name, description, botName, botId, appId, prefix) { result ->
                result.onSuccess { nav.navigate("project/$it") { popUpTo("project/new") { inclusive = true } } }
                    .onFailure { error = it.message ?: "Could not create project" }
            }
        }) { Text(stringResource(R.string.save)) }
    }
}

@Composable
fun ProjectDetailScreen(vm: KaralViewModel, nav: NavController, projectId: String) {
    var project by remember { mutableStateOf<ProjectEntity?>(null) }
    LaunchedEffect(projectId) { project = vm.project(projectId) }
    val bots by vm.observeProjectBots(projectId).collectAsState(initial = emptyList())
    val commands by vm.observeProjectCommands(projectId).collectAsState(initial = emptyList())
    val backups by vm.observeProjectBackups(projectId).collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var botName by remember { mutableStateOf("") }
    var botId by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var archived by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(project) {
        project?.let {
            name = it.name; description = it.description; botName = it.botName
            botId = it.botId; appId = it.applicationId; archived = it.archived
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportProject(uri, projectId) { result ->
            message = result.fold({ "Exported successfully" }, { it.message ?: "Export failed" })
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(project?.name ?: "Project", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(name, { name = it }, stringResource(R.string.name))
                    Field(description, { description = it }, stringResource(R.string.description))
                    Field(botName, { botName = it }, stringResource(R.string.bot_name))
                    Field(botId, { botId = it }, stringResource(R.string.bot_id))
                    Field(appId, { appId = it }, stringResource(R.string.application_id))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Archived"); Switch(checked = archived, onCheckedChange = { archived = it })
                    }
                    Button(onClick = {
                        vm.updateProject(projectId, name, description, botName, botId, appId, archived) { result ->
                            message = result.fold({ context.getString(R.string.saved) }, { it.message ?: "Save failed" })
                        }
                    }) { Text(stringResource(R.string.save)) }
                }
            }
        }
        item { SectionTitle("Bots and credentials") }
        items(bots, key = { it.id }) { bot ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bot.name, style = MaterialTheme.typography.titleMedium)
                    Text("Intents: ${bot.intents} • Prefix: ${bot.prefix}", style = MaterialTheme.typography.bodySmall)
                    var hasToken by remember(bot.id) { mutableStateOf(false) }
                    LaunchedEffect(bot.id) { vm.hasToken(bot.id) { hasToken = it } }
                    Text(if (hasToken) "Token stored securely" else "No token stored", color = if (hasToken) Green else Amber, style = MaterialTheme.typography.bodySmall)
                    Field(token, { token = it }, stringResource(R.string.token), visualTransformation = PasswordVisualTransformation())
                    Button(onClick = {
                        vm.saveToken(bot.id, token) { result ->
                            message = result.fold({ "Token saved to Keystore" }, { it.message ?: "Token rejected" })
                            if (result.isSuccess) token = ""
                        }
                    }) { Text("Save token") }
                }
            }
        }
        item {
            OutlinedButton(onClick = { vm.createBot(projectId, "Bot ${bots.size + 1}", "!") {} }) { Text(stringResource(R.string.new_bot)) }
        }
        item { SectionTitle(stringResource(R.string.nav_studio)) }
        items(commands, key = { it.id }) { command ->
            Row(Modifier.fillMaxWidth().clickable {
                val route = when (command.source) {
                    "VISUAL" -> "command/${command.id}/visual"
                    "AI" -> "command/${command.id}/ai"
                    else -> "command/${command.id}/script"
                }
                nav.navigate(route)
            }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("/${command.name}")
                StatusChip(command.source, Cyan)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("command/new?projectId=$projectId") }) { Text(stringResource(R.string.new_command)) }
                OutlinedButton(onClick = { nav.navigate("templates?projectId=$projectId") }) { Text(stringResource(R.string.templates)) }
                OutlinedButton(onClick = { vm.createBackup(projectId) { result ->
                    message = result.fold({ "Backup created" }, { it.message ?: "Backup failed" })
                } }) { Text(stringResource(R.string.backup)) }
                OutlinedButton(onClick = { exportLauncher.launch("karal-${project?.name ?: "project"}.zip") }) { Text(stringResource(R.string.export)) }
            }
        }
        item { SectionTitle(stringResource(R.string.backup)) }
        items(backups, key = { it.id }) { backup ->
            Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAt))} • ${backup.sizeBytes} bytes", style = MaterialTheme.typography.bodySmall)
        }
        if (message.isNotBlank()) item { InfoCard(message) }
        item {
            TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete)) }
        }
    }
    if (confirmDelete) ConfirmDialog(stringResource(R.string.delete), "Delete this project and all local data?", stringResource(R.string.delete),
        onConfirm = { vm.deleteProject(projectId); nav.popBackStack() }, onDismiss = { confirmDelete = false })
}

@Composable
fun NewCommandScreen(vm: KaralViewModel, nav: NavController, initialProjectId: String) {
    val projects by vm.projects.collectAsState(initial = emptyList())
    var projectId by remember { mutableStateOf(initialProjectId) }
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(CommandSource.SCRIPT.name) }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.new_command), style = MaterialTheme.typography.headlineMedium)
        Text("Project: ${projects.firstOrNull { it.id == projectId }?.name ?: "Select a project"}")
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(projects, key = { it.id }) { project ->
                FilterChip(selected = project.id == projectId, onClick = { projectId = project.id }, label = { Text(project.name) })
            }
        }
        Field(name, { name = it }, stringResource(R.string.name))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(CommandSource.SCRIPT.name, CommandSource.VISUAL.name, CommandSource.AI.name).forEach { option ->
                FilterChip(selected = source == option, onClick = { source = option }, label = { Text(option) })
            }
        }
        if (error.isNotBlank()) Text(error, color = Red)
        Button(onClick = {
            if (projectId.isBlank()) { error = "Create a project first"; return@Button }
            if (name.isBlank()) { error = "Command name is required"; return@Button }
            val chosen = CommandSource.valueOf(source)
            if (chosen == CommandSource.VISUAL) {
                vm.newVisualCommand(projectId, name) { result ->
                    result.onSuccess { nav.navigate("command/$it/visual") { popUpTo("command/new?projectId={projectId}") { inclusive = true } } }
                        .onFailure { error = it.message ?: "Failed" }
                }
            } else {
                vm.createCommand(projectId, name, chosen) { result ->
                    result.onSuccess { id ->
                        val suffix = if (chosen == CommandSource.AI) "ai" else "script"
                        nav.navigate("command/$id/$suffix") { popUpTo("command/new?projectId={projectId}") { inclusive = true } }
                    }.onFailure { error = it.message ?: "Failed" }
                }
            }
        }) { Text(stringResource(R.string.create)) }
    }
}

@Composable
fun ScriptEditorScreen(vm: KaralViewModel, nav: NavController, commandId: String) {
    var entity by remember { mutableStateOf<CommandEntity?>(null) }
    LaunchedEffect(commandId) { entity = vm.command(commandId) }
    var definition by remember { mutableStateOf<CommandDefinition?>(null) }
    var result by remember { mutableStateOf("") }
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(entity) {
        entity?.let { definition = CommandRepository.toDefinition(it) }
    }
    val current = definition
    if (current == null) { Text("Loading…", Modifier.padding(16.dp)); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Script editor", style = MaterialTheme.typography.headlineMedium)
        Field(current.name, { definition = current.copy(name = it) }, stringResource(R.string.name))
        Field(current.description, { definition = current.copy(description = it) }, stringResource(R.string.description))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScriptLanguage.entries.forEach { lang ->
                FilterChip(selected = current.language == lang, onClick = { definition = current.copy(language = lang) }, label = { Text(lang.name) })
            }
        }
        if (!current.approved) {
            InfoCard("This code is unapproved. It will not compile or execute until you review and approve it.")
            Button(onClick = {
                vm.approveCommand(commandId) { definition = current.copy(approved = it.isSuccess) }
            }) { Text(stringResource(R.string.approve)) }
        }
        OutlinedTextField(
            value = current.script,
            onValueChange = { definition = current.copy(script = it) },
            label = { Text("KARAL SDK script") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
            maxLines = 40
        )
        InfoCard("Available: command(config, callback), ctx.reply, ctx.send, ctx.embed, ctx.args, ctx.user, ctx.channel, console.log")
        if (result.isNotBlank()) Text(result, color = if (result.startsWith("Valid")) Green else Red)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                result = runCatching { vm.validateCommand(current).getOrThrow() }
                    .fold({ it }, { it.message ?: "Validation failed" })
            }) { Text(stringResource(R.string.validate)) }
            Button(onClick = {
                definition?.let { updated ->
                    vm.saveCommand(updated) { saveResult ->
                        result = saveResult.fold({ context.getString(R.string.saved) }, { it.message ?: "Save failed" })
                    }
                }
            }) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
fun VisualEditorScreen(vm: KaralViewModel, nav: NavController, commandId: String) {
    var entity by remember { mutableStateOf<CommandEntity?>(null) }
    LaunchedEffect(commandId) { entity = vm.command(commandId) }
    var definition by remember { mutableStateOf<CommandDefinition?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(entity) {
        entity?.let { definition = CommandRepository.toDefinition(it) }
    }
    val current = definition
    if (current == null) { Text("Loading…", Modifier.padding(16.dp)); return }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.visual_builder), style = MaterialTheme.typography.headlineMedium)
        Field(current.name, { definition = current.copy(name = it) }, stringResource(R.string.name))
        SectionTitle("Node palette")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            NodeCatalog.all.filter { it.group != NodeGroup.TRIGGER }.forEach { node ->
                OutlinedButton(onClick = {
                    val id = UUID.randomUUID().toString()
                    val newNode = WorkflowNode(id, node.id, node.group, emptyMap(), 0f, 0f, node.label)
                    definition = current.copy(workflow = current.workflow.copy(nodes = current.workflow.nodes + newNode))
                    selected = id
                }) { Text(node.label) }
            }
        }
        SectionTitle("Workflow")
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(current.workflow.nodes, key = { it.id }) { node ->
                Card(
                    Modifier.fillMaxWidth().clickable { selected = node.id }.padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (selected == node.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${node.group.label}: ${node.label.ifBlank { node.type }}", style = MaterialTheme.typography.titleMedium)
                        Text(node.id.take(8), style = MaterialTheme.typography.bodySmall)
                        if (selected != null && selected != node.id) {
                            TextButton(onClick = {
                                val edge = WorkflowEdge(UUID.randomUUID().toString(), selected!!, node.id)
                                definition = current.copy(workflow = current.workflow.copy(edges = current.workflow.edges + edge))
                            }) { Text("Connect from selected") }
                        }
                    }
                }
            }
        }
        selected?.let { id ->
            val node = current.workflow.nodes.firstOrNull { it.id == id }
            if (node != null) {
                SectionTitle("Node parameters")
                val required = NodeCatalog.byId(node.type)?.requiredParams.orEmpty()
                required.forEach { key ->
                    Field(node.params[key].orEmpty(), { value ->
                        val updated = node.copy(params = node.params + (key to value))
                        definition = current.copy(workflow = current.workflow.copy(
                            nodes = current.workflow.nodes.map { if (it.id == node.id) updated else it }
                        ))
                    }, key)
                }
                OutlinedButton(onClick = {
                    definition = current.copy(workflow = current.workflow.copy(
                        nodes = current.workflow.nodes.filterNot { it.id == id },
                        edges = current.workflow.edges.filterNot { it.from == id || it.to == id }
                    ))
                    selected = null
                }) { Text(stringResource(R.string.delete)) }
            }
        }
        if (result.isNotBlank()) Text(result, color = if (result.startsWith("Valid")) Green else Red)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                result = runCatching { vm.validateCommand(current).getOrThrow() }
                    .fold({ it }, { if (it is CompileException) it.message ?: "Compile failed" else it.message ?: "Validation failed" })
            }) { Text(stringResource(R.string.validate)) }
            Button(onClick = {
                definition?.let { updated ->
                    vm.saveCommand(updated) { result = it.fold({ "Saved" }, { e -> e.message ?: "Save failed" }) }
                }
            }) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
fun AiBuilderScreen(vm: KaralViewModel, nav: NavController, commandId: String) {
    var entity by remember { mutableStateOf<CommandEntity?>(null) }
    LaunchedEffect(commandId) { entity = vm.command(commandId) }
    var definition by remember { mutableStateOf<CommandDefinition?>(null) }
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(entity) {
        entity?.let { definition = CommandRepository.toDefinition(it) }
    }
    val current = definition
    if (current == null) { Text("Loading…", Modifier.padding(16.dp)); return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.ai_builder), style = MaterialTheme.typography.headlineMedium)
        InfoCard("AI output is never executed automatically. Review, approve, then save.")
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe the command") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
        Button(onClick = {
            status = "Generating…"
            vm.generateWithAi(prompt, current.projectId) { result ->
                result.onSuccess { generated ->
                    definition = generated.copy(id = current.id, name = current.name.ifBlank { generated.name })
                    status = "Generated. Review and approve before saving."
                }.onFailure { status = it.message ?: "Generation failed" }
            }
        }) { Text(stringResource(R.string.generate)) }
        Field(current.name, { definition = current.copy(name = it) }, stringResource(R.string.name))
        OutlinedTextField(value = current.script, onValueChange = { definition = current.copy(script = it) }, label = { Text("Generated script") }, modifier = Modifier.fillMaxWidth(), maxLines = 20)
        StatusChip(if (current.approved) "Approved" else "UNAPPROVED", if (current.approved) Green else Amber)
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("Generated")) Green else Red)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.approveCommand(commandId) { r -> definition = current.copy(approved = r.isSuccess) } }, enabled = !current.approved) {
                Text(stringResource(R.string.approve))
            }
            Button(onClick = {
                vm.saveCommand(current) { r -> status = r.fold({ "Saved" }, { it.message ?: "Save failed" }) }
            }) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
fun TemplatesScreen(vm: KaralViewModel, nav: NavController, projectId: String) {
    val templates by vm.templates.collectAsState(initial = emptyList())
    val projects by vm.projects.collectAsState(initial = emptyList())
    var selectedProject by remember { mutableStateOf(projectId) }
    var category by remember { mutableStateOf("ALL") }
    var status by remember { mutableStateOf("") }
    val categories = listOf("ALL") + templates.map { it.category }.distinct().sorted()
    val filtered = templates.filter { category == "ALL" || it.category == category }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.templates), style = MaterialTheme.typography.headlineMedium)
            Text("Project: ${projects.firstOrNull { it.id == selectedProject }?.name ?: "Select a project"}")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                categories.forEach { cat ->
                    FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                projects.forEach { project ->
                    FilterChip(selected = selectedProject == project.id, onClick = { selectedProject = project.id }, label = { Text(project.name) })
                }
            }
            if (status.isNotBlank()) Text(status)
        }
        items(filtered, key = { it.key }) { template ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(template.title, style = MaterialTheme.typography.titleMedium)
                    Text(template.category, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        if (selectedProject.isBlank()) status = "Create a project first"
                        else vm.applyTemplate(template.key, selectedProject) { result ->
                            status = result.fold({ "Added to project" }, { it.message ?: "Failed" })
                        }
                    }) { Text("Use template") }
                }
            }
        }
    }
}

@Composable
fun ConsoleScreen(vm: KaralViewModel, nav: NavController, botId: String) {
    val live by vm.liveLogs.collectAsState(initial = emptyList())
    val stored by (if (botId.isBlank()) vm.logs else vm.observeBotLogs(botId)).collectAsState(initial = emptyList())
    var level by remember { mutableStateOf("ALL") }
    val visible = live.filter { level == "ALL" || it.level == level }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(stringResource(R.string.console), style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "INFO", "WARN", "ERROR", "DEBUG").forEach { option ->
                    FilterChip(selected = level == option, onClick = { level = option }, label = { Text(option) })
                }
            }
            OutlinedButton(onClick = { vm.clearLogs() }) { Text(stringResource(R.string.clear)) }
        }
        items(visible, key = { it.timestamp.hashCode() + it.message.hashCode() }) { event ->
            val color = when (event.level) {
                "ERROR" -> Red
                "WARN" -> Amber
                "DEBUG" -> Cyan
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text("${event.level} • ${event.message}", color = color, style = MaterialTheme.typography.bodySmall)
        }
        item { SectionTitle("Persisted logs") }
        items(stored.take(60), key = { it.id }) { log ->
            Text("${log.level} • ${log.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun BotRuntimeScreen(vm: KaralViewModel, nav: NavController, botId: String) {
    var bot by remember { mutableStateOf<com.karal.studio.data.db.BotEntity?>(null) }
    LaunchedEffect(botId) { bot = vm.bot(botId) }
    val snapshots by vm.runtime.collectAsState(initial = emptyMap())
    val logs by vm.observeBotLogs(botId).collectAsState(initial = emptyList())
    val snapshot = snapshots[botId]
    var project by remember { mutableStateOf<ProjectEntity?>(null) }
    LaunchedEffect(bot?.projectId) { project = bot?.projectId?.let { vm.project(it) } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(bot?.name ?: "Bot", style = MaterialTheme.typography.headlineMedium)
            Text(project?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            StatusChip(snapshot?.state?.name ?: "STOPPED", when (snapshot?.state) {
                com.karal.studio.runtime.RuntimeState.READY -> Green
                com.karal.studio.runtime.RuntimeState.ERROR -> Red
                else -> Amber
            })
            Text("Uptime: ${(snapshot?.uptimeMs ?: 0L) / 1000}s • Servers: ${snapshot?.serverCount ?: 0}")
            if (!snapshot?.lastError.isNullOrBlank()) Text(snapshot!!.lastError, color = Red)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.startBot(botId) }) { Text(stringResource(R.string.start)) }
                OutlinedButton(onClick = { vm.stopBot(botId) }) { Text(stringResource(R.string.stop)) }
                OutlinedButton(onClick = { nav.navigate("console?botId=$botId") }) { Text(stringResource(R.string.console)) }
            }
        }
        items(logs, key = { it.id }) { log ->
            Text("${log.level}: ${log.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun BackupsScreen(vm: KaralViewModel, nav: NavController) {
    val backups by vm.backups.collectAsState(initial = emptyList())
    val projects by vm.projects.collectAsState(initial = emptyList())
    var status by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(R.string.backup), style = MaterialTheme.typography.headlineMedium)
            InfoCard(stringResource(R.string.uninstall_note))
            if (status.isNotBlank()) Text(status)
        }
        items(backups, key = { it.id }) { backup ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp)) {
                    Text(projects.firstOrNull { it.id == backup.projectId }?.name ?: backup.projectId)
                    Text("${backup.sizeBytes} bytes • verified=${backup.validated}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { confirm = backup.id }) { Text(stringResource(R.string.restore)) }
                }
            }
        }
    }
    confirm?.let { id ->
        ConfirmDialog(stringResource(R.string.restore), "Restore this backup as a new project?", stringResource(R.string.restore),
            onConfirm = { vm.restoreBackup(id) { r -> status = r.fold({ "Restored as a new project" }, { it.message ?: "Restore failed" }) }; confirm = null },
            onDismiss = { confirm = null })
    }
}

@Composable
fun AboutScreen(nav: NavController) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.about), style = MaterialTheme.typography.headlineMedium)
            Text("KARAL BOT STUDIO is a local-first Android workbench for building, testing and hosting Discord bots. No account is required and no project data leaves the device unless you export it or configure an AI provider.")
            InfoCard(stringResource(R.string.uninstall_note))
            InfoCard(stringResource(R.string.battery_note))
            Text("Discord Gateway/REST reliability depends on network conditions and Android battery policies. Background hosting may be stopped by the OS on some devices.")
        }
    }
}

@Composable
fun DiagnosticsScreen(vm: KaralViewModel, nav: NavController) {
    var text by remember { mutableStateOf("Loading…") }
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.diagnostics { text = it } }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.headlineMedium) }
        item { Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall) } }
    }
}
