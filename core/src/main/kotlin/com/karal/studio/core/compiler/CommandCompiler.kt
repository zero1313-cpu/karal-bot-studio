package com.karal.studio.core.compiler

import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.ExecutionPlan
import com.karal.studio.core.model.ExecutionStep
import com.karal.studio.core.model.NodeGroup
import com.karal.studio.core.model.ScriptLanguage
import com.karal.studio.core.model.ValidationIssue
import com.karal.studio.core.model.Workflow
import com.karal.studio.core.validation.CommandValidator

class CompileException(val issues: List<ValidationIssue>) :
    Exception(issues.joinToString("; ") { it.message })

/** Single entry point for every editor. All inputs become the same ExecutionPlan. */
object CommandCompiler {
    const val MAX_SCRIPT_CHARACTERS = 200_000
    const val MAX_STEPS = 500

    fun compile(command: CommandDefinition): ExecutionPlan {
        if (!command.approved) {
            throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR,
                        "UNAPPROVED_CODE",
                        "Generated or imported code must be explicitly approved before it can be compiled.",
                        "approved"
                    )
                )
            )
        }
        val validation = CommandValidator.validate(command)
        if (!validation.isValid) throw CompileException(validation.errors)

        val steps = when {
            command.workflow.nodes.isNotEmpty() -> WorkflowCompiler.compile(command.workflow)
            command.language == ScriptLanguage.BDSCRIPT -> BdsScriptCompiler.compile(command.script)
            else -> emptyList()
        }
        if (steps.size > MAX_STEPS) {
            throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR,
                        "TOO_MANY_STEPS",
                        "A command may contain at most $MAX_STEPS execution steps.",
                        ""
                    )
                )
            )
        }
        if (command.script.length > MAX_SCRIPT_CHARACTERS) {
            throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR,
                        "SCRIPT_TOO_LARGE",
                        "A script may contain at most $MAX_SCRIPT_CHARACTERS characters.",
                        ""
                    )
                )
            )
        }
        val compiledScript = if (command.workflow.nodes.isEmpty() && command.script.isNotBlank()) {
            when (command.language) {
                ScriptLanguage.TYPESCRIPT -> TypeScriptStripper.strip(command.script)
                ScriptLanguage.BDSCRIPT -> null
                else -> command.script
            }
        } else null

        return ExecutionPlan(
            commandId = command.id,
            trigger = command.trigger,
            steps = steps,
            permissions = command.permissions,
            timeoutMs = command.timeoutMs,
            compiledScript = compiledScript
        )
    }
}

/** Converts the node graph into a deterministic execution plan; unreachable nodes are an error. */
object WorkflowCompiler {
    fun compile(workflow: Workflow): List<ExecutionStep> {
        val trigger = workflow.nodes.firstOrNull { it.group == NodeGroup.TRIGGER }
            ?: throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR, "NO_TRIGGER", "Workflow has no trigger.", ""
                    )
                )
            )
        val visited = mutableSetOf(trigger.id)
        val steps = mutableListOf<ExecutionStep>()
        workflow.outgoing(trigger.id).forEach { edge ->
            steps += compileFrom(edge.to, workflow, visited, mutableSetOf(trigger.id))
        }
        val unreachable = workflow.nodes.map { it.id }.toSet() - visited
        if (unreachable.isNotEmpty()) {
            throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR,
                        "UNREACHABLE_NODES",
                        "Workflow contains nodes that cannot execute: ${unreachable.joinToString(", ")}.",
                        ""
                    )
                )
            )
        }
        return steps
    }

    private fun compileFrom(
        nodeId: String,
        workflow: Workflow,
        visited: MutableSet<String>,
        path: MutableSet<String>
    ): List<ExecutionStep> {
        if (nodeId in path) {
            throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR, "CYCLE", "Workflow cycle detected at $nodeId.", nodeId
                    )
                )
            )
        }
        val node = workflow.nodes.firstOrNull { it.id == nodeId }
            ?: throw CompileException(
                listOf(
                    ValidationIssue(
                        ValidationIssue.Severity.ERROR, "UNKNOWN_NODE", "Unknown node $nodeId.", nodeId
                    )
                )
            )
        visited += nodeId
        val nextPath = path + nodeId
        fun follow(port: String): List<ExecutionStep> = buildList {
            workflow.outgoing(nodeId, port).forEach { edge ->
                addAll(compileFrom(edge.to, workflow, visited, nextPath.toMutableSet()))
            }
        }

        return when (node.group) {
            NodeGroup.ACTION -> listOf(
                ExecutionStep.Action(
                    action = runCatching { ActionType.valueOf(node.type) }.getOrElse { ActionType.LOG_EVENT },
                    params = node.params,
                    nodeId = node.id
                )
            ) + follow("out")

            NodeGroup.CONDITION -> listOf(
                ExecutionStep.Branch(
                    condition = runCatching {
                        com.karal.studio.core.model.ConditionType.valueOf(node.type)
                    }.getOrElse { com.karal.studio.core.model.ConditionType.CUSTOM_CONDITION },
                    params = node.params,
                    thenSteps = follow("true") + follow("out"),
                    elseSteps = follow("false"),
                    nodeId = node.id
                )
            )

            NodeGroup.CONTROL -> when (node.type) {
                "IF_ELSE" -> listOf(
                    ExecutionStep.Branch(
                        condition = com.karal.studio.core.model.ConditionType.CUSTOM_CONDITION,
                        params = node.params + ("expression" to node.params["expression"].orEmpty()),
                        thenSteps = follow("true"),
                        elseSteps = follow("false"),
                        nodeId = node.id
                    )
                )
                "DELAY" -> listOf(
                    ExecutionStep.Delay(node.params["durationMs"]?.toLongOrNull() ?: 0L, node.id)
                ) + follow("out")
                "REPEAT", "LOOP" -> listOf(
                    ExecutionStep.Repeat(
                        (node.params["count"]?.toIntOrNull() ?: 1).coerceIn(1, 100),
                        follow("body"),
                        node.id
                    )
                ) + follow("out")
                "STOP_EXECUTION" -> listOf(ExecutionStep.Stop(node.id))
                "ERROR_HANDLER" -> listOf(
                    ExecutionStep.Action(ActionType.LOG_EVENT, node.params, node.id)
                ) + follow("out")
                "SWITCH" -> listOf(
                    ExecutionStep.Branch(
                        condition = com.karal.studio.core.model.ConditionType.VARIABLE_EQUALS,
                        params = node.params,
                        thenSteps = follow("true") + follow("out"),
                        elseSteps = follow("false"),
                        nodeId = node.id
                    )
                )
                else -> follow("out")
            }

            NodeGroup.TRIGGER -> follow("out")
        }
    }
}
