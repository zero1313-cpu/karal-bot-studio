package com.karal.studio.core

import com.karal.studio.core.ai.AiProvider
import com.karal.studio.core.ai.AiRequests
import com.karal.studio.core.ai.AiResponseParser
import com.karal.studio.core.ai.AiPromptBuilder
import com.karal.studio.core.compiler.BdsScriptCompiler
import com.karal.studio.core.compiler.CommandCompiler
import com.karal.studio.core.compiler.CompileException
import com.karal.studio.core.compiler.TypeScriptStripper
import com.karal.studio.core.compiler.WorkflowCompiler
import com.karal.studio.core.engine.DiscordAdapter
import com.karal.studio.core.engine.ExecutionResult
import com.karal.studio.core.engine.CommandExecutor
import com.karal.studio.core.engine.RateLimiter
import com.karal.studio.core.engine.VariableStore
import com.karal.studio.core.json.Json
import com.karal.studio.core.json.JsonException
import com.karal.studio.core.json.JsonValue
import com.karal.studio.core.json.number
import com.karal.studio.core.json.obj
import com.karal.studio.core.json.string
import com.karal.studio.core.model.ActionType
import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CommandPermissions
import com.karal.studio.core.model.CommandSource
import com.karal.studio.core.model.ExecutionContextData
import com.karal.studio.core.model.ExecutionStep
import com.karal.studio.core.model.NodeGroup
import com.karal.studio.core.model.ProjectBundle
import com.karal.studio.core.model.ProjectData
import com.karal.studio.core.model.ScriptLanguage
import com.karal.studio.core.model.TriggerSpec
import com.karal.studio.core.model.TriggerType
import com.karal.studio.core.model.ValidationIssue
import com.karal.studio.core.model.Workflow
import com.karal.studio.core.model.WorkflowEdge
import com.karal.studio.core.model.WorkflowNode
import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.core.templates.BuiltInTemplates
import com.karal.studio.core.templates.TemplateCategory
import com.karal.studio.core.validation.CommandValidator
import com.karal.studio.core.validation.PermissionEngine
import com.karal.studio.core.validation.WorkflowValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private fun command(
    name: String = "ping",
    script: String = "command({name:'ping'}, async (ctx) => { await ctx.reply('pong'); });",
    permissions: CommandPermissions = CommandPermissions(),
    source: CommandSource = CommandSource.SCRIPT,
    language: ScriptLanguage = ScriptLanguage.JAVASCRIPT,
    workflow: Workflow = Workflow(),
    trigger: TriggerSpec = TriggerSpec(TriggerType.SLASH_COMMAND, mapOf("commandName" to name)),
    approved: Boolean = true
) = CommandDefinition(
    id = "cmd_$name", projectId = "p1", name = name, script = script,
    permissions = permissions, source = source, language = language,
    workflow = workflow, trigger = trigger, approved = approved
)

class JsonTests {
    @Test fun `round trip nested objects and escapes`() {
        val value = Json.parse("""{"a":[1,true,"line\nbreak"],"b":{"c":null},"d":-2.5e2}""")
        val out = Json.parse(Json.stringify(value))
        assertEquals("line\nbreak", (out.obj()!!["a"] as JsonValue.Arr).items[2].string())
        assertEquals(-250.0, out.obj()!!["d"]!!.number()!!)
    }

    @Test fun `malformed json is rejected`() {
        for (bad in listOf("""{"a":}""", """{"a":1,}""", """{"a":"unterminated}""", "not json")) {
            try { Json.parse(bad); fail("Expected JsonException for $bad") }
            catch (e: JsonException) { assertTrue(e.message!!.isNotBlank()) }
        }
    }
}

class SecurityTests {
    @Test fun `discord token is redacted`() {
        val token = "MTI3Mzg5MDEyMzQ1Njc4OS123456.AbCdEf.GhIjKlMnOpQrStUvWxYz0123456789"
        assertTrue(SecretPolicy.containsSecret(token))
        assertEquals(SecretPolicy.REDACTED, SecretPolicy.redact(token))
        assertFalse(SecretPolicy.containsSecret(SecretPolicy.redact(token)))
    }

    @Test fun `export never contains a token`() {
        val token = "MTI3Mzg5MDEyMzQ1Njc4OS123456.AbCdEf.GhIjKlMnOpQrStUvWxYz0123456789"
        val bundle = ProjectBundle(
            project = ProjectData(id = "p1", name = "Demo"),
            variables = mapOf("botToken" to token, "welcomeText" to "Hello"),
            commands = listOf(command(name = "ping", script = "const token = \"$token\";"))
        ).sanitized()
        val text = bundle.toJson()
        assertFalse(text.contains(token))
        assertEquals(SecretPolicy.REDACTED, bundle.variables["botToken"])
        assertTrue(bundle.warnings.isNotEmpty())
    }

    @Test fun `bundle round trip preserves commands`() {
        val cmd = command()
        val bundle = ProjectBundle(project = ProjectData(id = "p1", name = "Demo"), commands = listOf(cmd))
        val restored = ProjectBundle.fromJson(bundle.toJson())
        assertEquals("Demo", restored.project.name)
        assertEquals("ping", restored.commands.single().name)
        assertEquals(TriggerType.SLASH_COMMAND, restored.commands.single().trigger.type)
    }

    @Test fun `foreign bundles are rejected`() {
        try { ProjectBundle.fromJson("""{"format":"other","formatVersion":1}"""); fail("Expected rejection") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("not a KARAL", true)) }
    }
}

class CompilerTests {
    @Test fun `command compiles to shared plan`() {
        val plan = CommandCompiler.compile(command())
        assertEquals("cmd_ping", plan.commandId)
        assertNotNull(plan.compiledScript)
        assertTrue(plan.compiledScript!!.contains("ctx.reply"))
    }

    @Test fun `invalid name fails compilation`() {
        try { CommandCompiler.compile(command(name = "Bad Name")); fail("Expected CompileException") }
        catch (e: CompileException) { assertTrue(e.issues.any { it.code == "INVALID_NAME" }) }
    }

    @Test fun `ai generated code requires approval`() {
        val cmd = command(source = CommandSource.AI, approved = false)
        assertFalse(cmd.isExecutable)
        try { CommandCompiler.compile(cmd); fail("Expected CompileException") }
        catch (e: CompileException) { assertTrue(e.issues.any { it.code == "UNAPPROVED_CODE" }) }
    }

    @Test fun `disconnected workflow node is rejected`() {
        val trigger = WorkflowNode("t", "SLASH_COMMAND", NodeGroup.TRIGGER, mapOf("commandName" to "x"))
        val orphan = WorkflowNode("o", "SEND_MESSAGE", NodeGroup.ACTION, mapOf("message" to "hi"))
        val workflow = Workflow(listOf(trigger, orphan), listOf())
        val issues = WorkflowValidator.validate(workflow).errors
        assertTrue(issues.any { it.code == "DISCONNECTED_NODE" })
        assertTrue(issues.any { it.code == "DEAD_TRIGGER" })
    }

    @Test fun `valid workflow compiles in graph order`() {
        val trigger = WorkflowNode("t", "SLASH_COMMAND", NodeGroup.TRIGGER, mapOf("commandName" to "x"))
        val send = WorkflowNode("a", "SEND_MESSAGE", NodeGroup.ACTION, mapOf("message" to "hello"))
        val log = WorkflowNode("l", "LOG_EVENT", NodeGroup.ACTION, mapOf("message" to "done"))
        val workflow = Workflow(
            listOf(trigger, send, log),
            listOf(WorkflowEdge("e1", "t", "a"), WorkflowEdge("e2", "a", "l"))
        )
        assertTrue(WorkflowValidator.validate(workflow).isValid)
        val steps = WorkflowCompiler.compile(workflow)
        assertEquals(2, steps.size)
        assertEquals(ActionType.SEND_MESSAGE, (steps[0] as ExecutionStep.Action).action)
        assertEquals(ActionType.LOG_EVENT, (steps[1] as ExecutionStep.Action).action)
    }

    @Test fun `cycles are rejected`() {
        val t = WorkflowNode("t", "SLASH_COMMAND", NodeGroup.TRIGGER, mapOf("commandName" to "x"))
        val a = WorkflowNode("a", "SEND_MESSAGE", NodeGroup.ACTION, mapOf("message" to "hi"))
        val b = WorkflowNode("b", "LOG_EVENT", NodeGroup.ACTION, mapOf("message" to "log"))
        val w = Workflow(listOf(t, a, b), listOf(
            WorkflowEdge("1", "t", "a"), WorkflowEdge("2", "a", "b"), WorkflowEdge("3", "b", "a")
        ))
        assertTrue(WorkflowValidator.validate(w).errors.any { it.code == "CYCLE" })
    }

    @Test fun `typescript annotations are stripped but strings preserved`() {
        val ts = """
            interface User { id: string; name: string }
            type Result = User | null;
            function greet(user: User): string {
              const text: string = "keep: this literal";
              return "Hello " + user.name;
            }
        """.trimIndent()
        val js = TypeScriptStripper.strip(ts)
        assertFalse(js.contains("interface User"))
        assertFalse(js.contains("type Result"))
        assertTrue(js.contains("function greet(user"))
        assertTrue(js.contains("\"keep: this literal\""))
        assertTrue(TypeScriptStripper.balanced(js))
    }

    @Test fun `typescript limitations are reported`() {
        val warnings = TypeScriptStripper.unsupportedConstructs("@Injectable() enum E { A }")
        assertEquals(2, warnings.size)
    }

    @Test fun `bdscript compiles branch and variables`() {
        val program = BdsScriptCompiler.parse("""
            require permission BAN_MEMBERS
            if permission MANAGE_MESSAGES
              setvar last "hello"
              reply "Allowed"
            else
              reply "Denied"
            endif
            delay 100
        """.trimIndent())
        assertEquals(listOf("BAN_MEMBERS"), program.requiredBotPermissions)
        assertEquals(2, program.steps.size)
        val branch = program.steps[0] as ExecutionStep.Branch
        assertEquals(2, branch.thenSteps.size)
        assertEquals(1, branch.elseSteps.size)
        assertTrue(program.steps[1] is ExecutionStep.Delay)
    }

    @Test fun `bdscript rejects unknown statements`() {
        try { BdsScriptCompiler.compile("executeShell \"rm\""); fail("Expected CompileException") }
        catch (e: CompileException) { assertTrue(e.issues.any { it.code == "BDSCRIPT_ERROR" }) }
    }

    @Test fun `all built in templates compile`() {
        val categories = TemplateCategory.entries
        assertTrue(categories.size == 10)
        BuiltInTemplates.all.forEach { tpl ->
            val cmd = tpl.command.copy(
                trigger = TriggerSpec(tpl.command.trigger.type, mapOf("commandName" to tpl.command.name)),
                permissions = tpl.command.permissions.copy()
            )
            val result = runCatching { CommandCompiler.compile(cmd) }
            assertTrue(result.isSuccess, "Template ${tpl.key} failed: ${result.exceptionOrNull()?.message}")
        }
        assertTrue(BuiltInTemplates.all.size >= 50)
    }
}

class PermissionAndEngineTests {
    @Test fun `permission engine blocks missing permissions`() {
        val cmd = command(permissions = CommandPermissions(requiredUserPermissions = listOf("BAN_MEMBERS")))
        val denied = PermissionEngine.evaluate(cmd, ExecutionContextData("cmd_ping", userId = "1"))
        assertFalse(denied.allowed)
        val allowed = PermissionEngine.evaluate(cmd, ExecutionContextData(
            "cmd_ping", userId = "1", userPermissions = setOf("BAN_MEMBERS")
        ))
        assertTrue(allowed.allowed)
    }

    @Test fun `guild only command is blocked in dm`() {
        val cmd = command(permissions = CommandPermissions(guildOnly = true))
        assertFalse(PermissionEngine.evaluate(cmd, ExecutionContextData("cmd_ping")).allowed)
        assertTrue(PermissionEngine.evaluate(
            cmd, ExecutionContextData("cmd_ping", guildId = "g1")
        ).allowed)
    }

    @Test fun `executor refuses unapproved commands`() {
        // Approval is enforced by CommandCompiler before a plan can be handed to the engine.
        val cmd = command(approved = false)
        assertFalse(cmd.isExecutable)
        try { CommandCompiler.compile(cmd); fail("Expected CompileException") }
        catch (e: CompileException) { assertEquals("UNAPPROVED_CODE", e.issues.first().code) }
    }

    @Test fun `executor runs actions with budget and variables`() {
        val adapter = FakeAdapter()
        val variables = VariableStore("test")
        val executor = CommandExecutor(adapter, variables, com.karal.studio.core.engine.RuntimeMetrics(), RateLimiter())
        val plan = com.karal.studio.core.model.ExecutionPlan(
            "cmd_ping", TriggerSpec(), listOf(
                ExecutionStep.Action(ActionType.SET_VARIABLE, mapOf("variable" to "k", "value" to "v"), "1"),
                ExecutionStep.Branch(
                    com.karal.studio.core.model.ConditionType.VARIABLE_EQUALS,
                    mapOf("variable" to "k", "value" to "v"),
                    listOf(ExecutionStep.Action(ActionType.REPLY, mapOf("message" to "yes"), "2")),
                    listOf(ExecutionStep.Action(ActionType.REPLY, mapOf("message" to "no"), "3")), "b"
                )
            ), CommandPermissions(), 1000
        )
        val result = executor.execute(plan, ExecutionContextData("cmd_ping", guildId = "g"))
        assertTrue(result.success)
        assertEquals("v", variables.get("k"))
        assertEquals(listOf("yes"), adapter.messages)
    }

    @Test fun `rate limiter enforces burst and cooldown`() {
        val limiter = RateLimiter()
        assertTrue(limiter.tryAcquire("u", 2, 60_000))
        assertTrue(limiter.tryAcquire("u", 2, 60_000))
        assertFalse(limiter.tryAcquire("u", 2, 60_000))
        limiter.markUsed("cmd:1")
        assertTrue(limiter.cooldownRemaining("cmd:1", 5000) > 0)
    }

    @Test fun `variables reject credential looking values`() {
        val store = VariableStore("security")
        try {
            store.set("token", "MTI3Mzg5MDEyMzQ1Njc4OS123456.AbCdEf.GhIjKlMnOpQrStUvWxYz0123456789")
            fail("Expected rejection")
        } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Credential")) }
    }
}

class AiTests {
    @Test fun `prompt never includes a key`() {
        val prompt = AiPromptBuilder.build("create a warn command", "Demo", listOf("ping"))
        assertTrue(prompt.contains("create a warn command"))
        assertTrue(prompt.contains("Never"))
    }

    @Test fun `fenced AI JSON is parsed and stays unapproved`() {
        val text = """```json
{"name":"Warn User!","description":"warn","triggerType":"SLASH_COMMAND",
"script":"await ctx.reply('ok')","permissions":["manage_messages"],
"requiredBotPermissions":["SEND_MESSAGES"],"errorHandling":"try catch",
"testingInstructions":"1. run /warn","notes":"needs permission"}
```"""
        val draft = AiResponseParser.parse(text)
        assertEquals("warn-user", draft.name)
        assertFalse(draft.toCommand("p1").approved)
    }

    @Test fun `provider response extraction works for both formats`() {
        val gemini = """{"candidates":[{"content":{"parts":[{"text":"{\"name\":\"ping\"}"}]}}]}"""
        val openai = """{"choices":[{"message":{"content":"{\"name\":\"ping\"}"}}]}"""
        assertTrue(AiRequests.responseText(AiProvider.GEMINI, gemini).contains("ping"))
        assertTrue(AiRequests.responseText(AiProvider.GROQ, openai).contains("ping"))
    }
}

private class FakeAdapter : DiscordAdapter {
    val messages = mutableListOf<String>()
    override fun reply(message: String, ephemeral: Boolean): Result<Unit> {
        messages += message; return Result.success(Unit)
    }
    override fun sendMessage(channelId: String, message: String): Result<Unit> { messages += message; return Result.success(Unit) }
    override fun sendEmbed(channelId: String, title: String, description: String): Result<Unit> = Result.success(Unit)
    override fun editMessage(channelId: String, messageId: String, message: String): Result<Unit> = Result.success(Unit)
    override fun deleteMessage(channelId: String, messageId: String): Result<Unit> = Result.success(Unit)
    override fun moderate(
        action: ActionType, params: Map<String, String>, context: ExecutionContextData
    ): Result<Unit> = Result.success(Unit)
    override fun httpRequest(url: String, method: String, body: String): Result<String> = Result.success("{}")
    override fun log(level: String, message: String) {}
}
