package com.karal.studio.core.validation

import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CommandSource
import com.karal.studio.core.model.ExecutionContextData
import com.karal.studio.core.model.NodeCatalog
import com.karal.studio.core.model.NodeGroup
import com.karal.studio.core.model.ValidationIssue
import com.karal.studio.core.model.ValidationResult
import com.karal.studio.core.model.Workflow

private const val NAME_PATTERN = "^[a-z0-9_-]{1,32}$"

object SchemaValidator {
    fun validateCommand(command: CommandDefinition): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        fun error(code: String, message: String, path: String) {
            issues += ValidationIssue(ValidationIssue.Severity.ERROR, code, message, path)
        }

        if (!command.name.matches(Regex(NAME_PATTERN))) {
            error("INVALID_NAME", "Command name must be 1-32 lowercase letters, digits, '-' or '_'.", "name")
        }
        if (command.description.length > 100) {
            error("DESCRIPTION_TOO_LONG", "Command description must be at most 100 characters.", "description")
        }
        if (command.timeoutMs !in 100L..120_000L) {
            error("INVALID_TIMEOUT", "Execution timeout must be between 100 ms and 120000 ms.", "timeoutMs")
        }
        if (command.permissions.cooldownMs !in 0L..3_600_000L) {
            error("INVALID_COOLDOWN", "Cooldown must be between 0 and 3600000 ms.", "permissions.cooldownMs")
        }
        if (command.trigger.type.name.contains("COMMAND") &&
            command.trigger.options["commandName"].isNullOrBlank()
        ) {
            error("MISSING_COMMAND_NAME", "Command triggers require a commandName option.", "trigger.options.commandName")
        }
        if (command.language.name == "TYPESCRIPT" && command.script.isBlank() &&
            command.workflow.nodes.isEmpty()
        ) {
            error("EMPTY_COMMAND", "A command must have a script or a workflow.", "")
        }
        if (command.source == CommandSource.AI && !command.approved) {
            issues += ValidationIssue(
                ValidationIssue.Severity.WARNING,
                "UNAPPROVED_AI_CODE",
                "AI-generated code is not executable until explicitly approved.",
                "approved"
            )
        }
        return ValidationResult(issues)
    }
}

object WorkflowValidator {
    fun validate(workflow: Workflow): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        fun error(code: String, message: String, path: String) {
            issues += ValidationIssue(ValidationIssue.Severity.ERROR, code, message, path)
        }
        fun warn(code: String, message: String, path: String) {
            issues += ValidationIssue(ValidationIssue.Severity.WARNING, code, message, path)
        }

        if (workflow.nodes.isEmpty()) return ValidationResult(issues)

        val ids = workflow.nodes.map { it.id }
        if (ids.size != ids.toSet().size) error("DUPLICATE_NODE", "Node ids must be unique.", "nodes")

        val triggers = workflow.nodes.filter { it.group == NodeGroup.TRIGGER }
        when {
            triggers.isEmpty() -> error("NO_TRIGGER", "A workflow needs exactly one trigger node.", "nodes")
            triggers.size > 1 -> error("MULTIPLE_TRIGGERS", "A workflow must have exactly one trigger node.", "nodes")
        }

        val byId = workflow.nodes.associateBy { it.id }
        workflow.edges.forEach { edge ->
            if (!byId.containsKey(edge.from)) error("MISSING_EDGE_SOURCE", "Edge '${edge.id}' starts at unknown node.", edge.id)
            if (!byId.containsKey(edge.to)) error("MISSING_EDGE_TARGET", "Edge '${edge.id}' ends at unknown node.", edge.id)
            if (edge.from == edge.to) error("SELF_LOOP", "A node cannot connect to itself.", edge.id)
        }

        workflow.nodes.filter { it.group != NodeGroup.TRIGGER }.forEach { node ->
            if (workflow.incoming(node.id).isEmpty()) {
                error("DISCONNECTED_NODE", "Node '${node.label.ifBlank { node.type }}' is not connected to any predecessor.", node.id)
            }
        }
        triggers.forEach { trigger ->
            if (workflow.outgoing(trigger.id).isEmpty()) {
                error("DEAD_TRIGGER", "The trigger has no outgoing connection.", trigger.id)
            }
        }

        workflow.nodes.forEach { node ->
            val definition = NodeCatalog.byId(node.type)
            if (definition == null) {
                error("UNKNOWN_NODE", "Unknown node type '${node.type}'.", node.id)
                return@forEach
            }
            if (definition.group != node.group) {
                error("WRONG_GROUP", "Node '${node.type}' is declared in the wrong group.", node.id)
            }
            definition.requiredParams.forEach { param ->
                if (node.params[param].isNullOrBlank()) {
                    error("MISSING_PARAM", "Node '${definition.label}' requires parameter '$param'.", "${node.id}.params.$param")
                }
            }
        }

        // Control-flow branches use explicit ports; an unlabelled branch is a silent failure.
        workflow.nodes.filter { it.type == "IF_ELSE" }.forEach { node ->
            val ports = workflow.outgoing(node.id).map { it.fromPort }.toSet()
            if ("true" !in ports || "false" !in ports) {
                warn("INCOMPLETE_BRANCH", "If/Else should have both 'true' and 'false' branches.", node.id)
            }
        }

        val cycle = findCycle(workflow)
        if (cycle != null) {
            error("CYCLE", "Workflow contains a cycle: ${cycle.joinToString(" -> ")}. Use a Repeat node instead.", cycle.first())
        }
        return ValidationResult(issues)
    }

    private fun findCycle(workflow: Workflow): List<String>? {
        val adjacency = workflow.nodes.associate { node ->
            node.id to workflow.outgoing(node.id).map { it.to }
        }
        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        fun visit(id: String): List<String>? {
            if (state[id] == 1) {
                val start = stack.indexOf(id)
                return stack.subList(start, stack.size) + id
            }
            if (state[id] == 2) return null
            state[id] = 1
            stack += id
            adjacency[id].orEmpty().forEach { next ->
                val found = visit(next)
                if (found != null) return found
            }
            stack.removeAt(stack.lastIndex)
            state[id] = 2
            return null
        }
        workflow.nodes.forEach { node ->
            val found = visit(node.id)
            if (found != null) return found
        }
        return null
    }
}

data class PermissionResult(val allowed: Boolean, val reason: String = "")

object PermissionEngine {
    fun evaluate(command: CommandDefinition, context: ExecutionContextData): PermissionResult {
        val p = command.permissions
        if (p.guildOnly && context.guildId.isBlank()) {
            return PermissionResult(false, "This command can only be used in a server.")
        }
        if (!p.dmAllowed && context.guildId.isBlank()) {
            return PermissionResult(false, "Direct messages are disabled for this command.")
        }
        if (p.requireAdministrator && !context.isAdministrator &&
            !context.userPermissions.contains("ADMINISTRATOR")
        ) {
            return PermissionResult(false, "Administrator permission is required.")
        }
        val missing = p.requiredUserPermissions.filterNot {
            context.isAdministrator || context.userPermissions.contains(it)
        }
        if (missing.isNotEmpty()) return PermissionResult(false, "Missing permissions: ${missing.joinToString(", ")}")
        if (p.allowedRoleIds.isNotEmpty() &&
            p.allowedRoleIds.none { it in context.memberRoles }
        ) {
            return PermissionResult(false, "You do not have an allowed role for this command.")
        }
        if (p.allowedUserIds.isNotEmpty() && context.userId !in p.allowedUserIds) {
            return PermissionResult(false, "You are not allowed to use this command.")
        }
        val botMissing = command.requiredBotPermissions.filterNot { it in context.botPermissions }
        if (botMissing.isNotEmpty()) {
            return PermissionResult(false, "Bot is missing permissions: ${botMissing.joinToString(", ")}")
        }
        return PermissionResult(true)
    }

    fun evaluateCondition(
        type: String,
        params: Map<String, String>,
        context: ExecutionContextData
    ): Boolean = when (type) {
        "USER_HAS_PERMISSION" -> context.isAdministrator || params["permission"] in context.userPermissions
        "BOT_HAS_PERMISSION" -> params["permission"] in context.botPermissions
        "USER_HAS_ROLE" -> params["roleId"] in context.memberRoles
        "IS_ADMINISTRATOR" -> context.isAdministrator || "ADMINISTRATOR" in context.userPermissions
        "MESSAGE_CONTAINS" -> context.args.joinToString(" ").contains(params["text"].orEmpty(), ignoreCase = true)
        "VARIABLE_EQUALS" -> context.variables[params["variable"]] == params["value"]
        "NUMBER_COMPARISON" -> compareNumbers(
            context.variables[params["variable"]]?.toDoubleOrNull(),
            params["value"]?.toDoubleOrNull(),
            params["operator"] ?: "=="
        )
        "MEMBER_EXISTS" -> context.mentionedUserId.isNotBlank() && params["memberId"] != null
        "CHANNEL_EXISTS" -> params["channelId"]?.isNotBlank() == true
        "CUSTOM_CONDITION" -> false // Custom expressions require an explicitly bound safe evaluator.
        else -> false
    }

    private fun compareNumbers(left: Double?, right: Double?, operator: String): Boolean {
        if (left == null || right == null) return false
        return when (operator) {
            "==", "=" -> left == right
            "!=" -> left != right
            ">" -> left > right
            ">=" -> left >= right
            "<" -> left < right
            "<=" -> left <= right
            else -> false
        }
    }
}

object CommandValidator {
    fun validate(command: CommandDefinition): ValidationResult {
        val issues = SchemaValidator.validateCommand(command).issues.toMutableList()
        if (command.workflow.nodes.isNotEmpty()) issues += WorkflowValidator.validate(command.workflow).issues
        return ValidationResult(issues)
    }
}
