package com.karal.studio.core.model

/** Every trigger exposed by the visual builder and the shared command engine. */
enum class TriggerType(val label: String) {
    SLASH_COMMAND("Slash Command"),
    PREFIX_COMMAND("Prefix Command"),
    MESSAGE_CREATE("Message Create"),
    MEMBER_JOIN("Member Join"),
    MEMBER_LEAVE("Member Leave"),
    REACTION_ADD("Reaction Add"),
    BUTTON_CLICK("Button Click"),
    SELECT_MENU("Select Menu"),
    MODAL_SUBMIT("Modal Submit"),
    SCHEDULED_TRIGGER("Scheduled Trigger"),
    VOICE_STATE_UPDATE("Voice State Update"),
    ROLE_UPDATE("Role Update"),
    CHANNEL_CREATE("Channel Create"),
    GUILD_UPDATE("Guild Update")
}

enum class ConditionType(val label: String) {
    USER_HAS_PERMISSION("User Has Permission"),
    USER_HAS_ROLE("User Has Role"),
    BOT_HAS_PERMISSION("Bot Has Permission"),
    MESSAGE_CONTAINS("Message Contains"),
    VARIABLE_EQUALS("Variable Equals"),
    NUMBER_COMPARISON("Number Comparison"),
    IS_ADMINISTRATOR("Is Administrator"),
    MEMBER_EXISTS("Member Exists"),
    CHANNEL_EXISTS("Channel Exists"),
    CUSTOM_CONDITION("Custom Condition")
}

enum class ActionType(val label: String) {
    SEND_MESSAGE("Send Message"),
    SEND_EMBED("Send Embed"),
    REPLY("Reply"),
    EDIT_MESSAGE("Edit Message"),
    DELETE_MESSAGE("Delete Message"),
    BAN_MEMBER("Ban Member"),
    KICK_MEMBER("Kick Member"),
    TIMEOUT_MEMBER("Timeout Member"),
    ADD_ROLE("Add Role"),
    REMOVE_ROLE("Remove Role"),
    CREATE_CHANNEL("Create Channel"),
    DELETE_CHANNEL("Delete Channel"),
    CREATE_THREAD("Create Thread"),
    CREATE_TICKET("Create Ticket"),
    SEND_DM("Send DM"),
    LOG_EVENT("Log Event"),
    SET_VARIABLE("Set Variable"),
    GET_VARIABLE("Get Variable"),
    HTTP_REQUEST("HTTP Request"),
    EXECUTE_SAFE_SCRIPT("Execute Safe Script")
}

enum class ControlType(val label: String) {
    IF_ELSE("If / Else"),
    SWITCH("Switch"),
    REPEAT("Repeat"),
    LOOP("Loop"),
    DELAY("Delay"),
    STOP_EXECUTION("Stop Execution"),
    ERROR_HANDLER("Error Handler")
}

enum class NodeGroup(val label: String) { TRIGGER("Triggers"), CONDITION("Conditions"), ACTION("Actions"), CONTROL("Control Flow") }

/** Static catalogue used by the palette and by [com.karal.studio.core.validation.WorkflowValidator]. */
data class NodeDefinition(
    val id: String,
    val group: NodeGroup,
    val label: String,
    val requiredParams: List<String> = emptyList(),
    val description: String = ""
)

object NodeCatalog {
    val all: List<NodeDefinition> = buildList {
        TriggerType.entries.forEach {
            add(NodeDefinition(it.name, NodeGroup.TRIGGER, it.label, requiredTriggerParams(it)))
        }
        ConditionType.entries.forEach {
            add(NodeDefinition(it.name, NodeGroup.CONDITION, it.label, requiredConditionParams(it)))
        }
        ActionType.entries.forEach {
            add(NodeDefinition(it.name, NodeGroup.ACTION, it.label, requiredActionParams(it)))
        }
        ControlType.entries.forEach {
            add(NodeDefinition(it.name, NodeGroup.CONTROL, it.label, requiredControlParams(it)))
        }
    }

    private fun requiredTriggerParams(t: TriggerType) = when (t) {
        TriggerType.SLASH_COMMAND, TriggerType.PREFIX_COMMAND -> listOf("commandName")
        TriggerType.SCHEDULED_TRIGGER -> listOf("cronExpression")
        else -> emptyList()
    }

    private fun requiredConditionParams(c: ConditionType) = when (c) {
        ConditionType.USER_HAS_PERMISSION, ConditionType.BOT_HAS_PERMISSION -> listOf("permission")
        ConditionType.USER_HAS_ROLE -> listOf("roleId")
        ConditionType.MESSAGE_CONTAINS -> listOf("text")
        ConditionType.VARIABLE_EQUALS -> listOf("variable", "value")
        ConditionType.NUMBER_COMPARISON -> listOf("variable", "operator", "value")
        ConditionType.MEMBER_EXISTS -> listOf("memberId")
        ConditionType.CHANNEL_EXISTS -> listOf("channelId")
        ConditionType.CUSTOM_CONDITION -> listOf("expression")
        else -> emptyList()
    }

    private fun requiredActionParams(a: ActionType) = when (a) {
        ActionType.SEND_MESSAGE, ActionType.REPLY, ActionType.SEND_DM, ActionType.LOG_EVENT -> listOf("message")
        ActionType.SEND_EMBED -> listOf("title", "description")
        ActionType.EDIT_MESSAGE -> listOf("messageId", "message")
        ActionType.BAN_MEMBER, ActionType.KICK_MEMBER -> listOf("memberId")
        ActionType.TIMEOUT_MEMBER -> listOf("memberId", "durationSeconds")
        ActionType.ADD_ROLE, ActionType.REMOVE_ROLE -> listOf("memberId", "roleId")
        ActionType.CREATE_CHANNEL, ActionType.DELETE_CHANNEL -> listOf("channelName")
        ActionType.CREATE_THREAD -> listOf("channelId", "threadName")
        ActionType.CREATE_TICKET -> listOf("channelName")
        ActionType.SET_VARIABLE -> listOf("variable", "value")
        ActionType.GET_VARIABLE -> listOf("variable")
        ActionType.HTTP_REQUEST -> listOf("url", "method")
        ActionType.DELETE_MESSAGE -> listOf("messageId")
        ActionType.EXECUTE_SAFE_SCRIPT -> listOf("script")
        else -> emptyList()
    }

    private fun requiredControlParams(c: ControlType) = when (c) {
        ControlType.REPEAT, ControlType.LOOP -> listOf("count")
        ControlType.DELAY -> listOf("durationMs")
        ControlType.SWITCH -> listOf("variable")
        ControlType.ERROR_HANDLER -> listOf("message")
        else -> emptyList()
    }

    fun byId(id: String): NodeDefinition? = all.firstOrNull { it.id == id }
}

enum class CommandSource { VISUAL, SCRIPT, AI, TEMPLATE, BDSCRIPT }
enum class ScriptLanguage { JAVASCRIPT, TYPESCRIPT, BDSCRIPT, VISUAL }

data class TriggerSpec(
    val type: TriggerType = TriggerType.SLASH_COMMAND,
    val options: Map<String, String> = emptyMap()
)

data class CommandPermissions(
    val requiredUserPermissions: List<String> = emptyList(),
    val allowedRoleIds: List<String> = emptyList(),
    val allowedUserIds: List<String> = emptyList(),
    val requireAdministrator: Boolean = false,
    val guildOnly: Boolean = false,
    val dmAllowed: Boolean = true,
    val cooldownMs: Long = 0L
)

/**
 * The single command definition produced by every editor. Script code is compiled by the
 * same pipeline as visual workflows; none of the editors bypass this type.
 */
data class CommandDefinition(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String = "",
    val source: CommandSource = CommandSource.SCRIPT,
    val language: ScriptLanguage = ScriptLanguage.JAVASCRIPT,
    val script: String = "",
    val trigger: TriggerSpec = TriggerSpec(),
    val workflow: Workflow = Workflow(),
    val permissions: CommandPermissions = CommandPermissions(),
    val requiredBotPermissions: List<String> = emptyList(),
    val timeoutMs: Long = 5_000L,
    val enabled: Boolean = true,
    val version: Int = 1,
    val metadata: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** AI-generated and imported code stays false until the user explicitly approves it. */
    val approved: Boolean = true
) {
    val isExecutable: Boolean get() = enabled && approved
}

sealed interface ExecutionStep {
    data class Action(val action: ActionType, val params: Map<String, String>, val nodeId: String) : ExecutionStep
    data class Branch(
        val condition: ConditionType,
        val params: Map<String, String>,
        val thenSteps: List<ExecutionStep>,
        val elseSteps: List<ExecutionStep>,
        val nodeId: String
    ) : ExecutionStep
    data class Delay(val durationMs: Long, val nodeId: String) : ExecutionStep
    data class Repeat(val count: Int, val steps: List<ExecutionStep>, val nodeId: String) : ExecutionStep
    data class Stop(val nodeId: String) : ExecutionStep
}

data class ExecutionPlan(
    val commandId: String,
    val trigger: TriggerSpec,
    val steps: List<ExecutionStep>,
    val permissions: CommandPermissions,
    val timeoutMs: Long,
    /** Script-backed commands keep their compiled source for the QuickJS adapter. */
    val compiledScript: String? = null
)

/** Pure data snapshot handed to permission and condition evaluation. */
data class ExecutionContextData(
    val commandId: String,
    val userId: String = "",
    val guildId: String = "",
    val channelId: String = "",
    val userPermissions: Set<String> = emptySet(),
    val botPermissions: Set<String> = emptySet(),
    val memberRoles: Set<String> = emptySet(),
    val isAdministrator: Boolean = false,
    val mentionedUserId: String = "",
    val args: List<String> = emptyList(),
    val variables: Map<String, String> = emptyMap()
)

data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val path: String = ""
) {
    enum class Severity { ERROR, WARNING }
}

data class ValidationResult(val issues: List<ValidationIssue>) {
    val isValid: Boolean get() = issues.none { it.severity == ValidationIssue.Severity.ERROR }
    val errors: List<ValidationIssue> get() = issues.filter { it.severity == ValidationIssue.Severity.ERROR }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == ValidationIssue.Severity.WARNING }
}
