package com.karal.studio.core.templates

import com.karal.studio.core.model.CommandDefinition
import com.karal.studio.core.model.CommandPermissions
import com.karal.studio.core.model.CommandSource
import com.karal.studio.core.model.ScriptLanguage
import com.karal.studio.core.model.TriggerSpec
import com.karal.studio.core.model.TriggerType

data class TemplateDefinition(
    val key: String,
    val category: TemplateCategory,
    val title: String,
    val command: CommandDefinition
)

enum class TemplateCategory(val label: String) {
    MODERATION("Moderation"), MEMBER("Member"), SERVER("Server"), WELCOME("Welcome"),
    TICKETS("Tickets"), LOGGING("Logging"), UTILITY("Utility"), ECONOMY("Economy"),
    FUN("Fun"), AUTOMATION("Automation")
}

/**
 * Editable, ready-to-use command templates. Every template is a normal [CommandDefinition], so
 * installing one is identical to creating a command in any editor.
 */
object BuiltInTemplates {
    val all: List<TemplateDefinition> = buildList {
        fun add(
            category: TemplateCategory,
            name: String,
            description: String,
            botPermissions: List<String> = emptyList(),
            userPermissions: List<String> = emptyList(),
            trigger: TriggerType = TriggerType.SLASH_COMMAND,
            body: () -> String
        ) {
            val key = "${category.name.lowercase()}_${name.replace("/", "").replace("-", "_")}"
            add(
                TemplateDefinition(
                    key = key,
                    category = category,
                    title = name,
                    command = CommandDefinition(
                        id = "tpl_$key",
                        projectId = "",
                        name = name.removePrefix("/"),
                        description = description,
                        source = CommandSource.TEMPLATE,
                        language = ScriptLanguage.JAVASCRIPT,
                        trigger = TriggerSpec(trigger),
                        script = "command({ name: \"${name.removePrefix("/")}\", description: ${jsString(description)}, " +
                            "trigger: \"${trigger.name}\" }, async (ctx) => {\n${body()}\n});",
                        permissions = CommandPermissions(
                            requiredUserPermissions = userPermissions,
                            guildOnly = true
                        ),
                        requiredBotPermissions = botPermissions,
                        metadata = mapOf("templateKey" to key, "editable" to "true")
                    )
                )
            )
        }

        with(TemplateCategory.MODERATION) {
            add(this, "ban", "Ban a member", listOf("BAN_MEMBERS"), listOf("BAN_MEMBERS")) {
                """const id = ctx.args[0]; const reason = ctx.args.slice(1).join(" ") || "No reason";
if (!id) return ctx.reply("Mention a member to ban.", true);
await ctx.member.ban(id, reason);
await ctx.reply("Banned <@" + id + ">. Reason: " + reason);"""
            }
            add(this, "kick", "Kick a member", listOf("KICK_MEMBERS"), listOf("KICK_MEMBERS")) {
                """const id = ctx.args[0]; const reason = ctx.args.slice(1).join(" ") || "No reason";
await ctx.member.kick(id, reason); await ctx.reply("Kicked <@" + id + ">.");"""
            }
            add(this, "timeout", "Timeout a member", listOf("MODERATE_MEMBERS"), listOf("MODERATE_MEMBERS")) {
                """const id = ctx.args[0]; const minutes = Number(ctx.args[1] || 10);
await ctx.member.timeout(id, minutes * 60, "Timeout via /timeout");
await ctx.reply("Timed out <@" + id + "> for " + minutes + " minutes.");"""
            }
            add(this, "untimeout", "Remove a member timeout", listOf("MODERATE_MEMBERS"), listOf("MODERATE_MEMBERS")) {
                """await ctx.member.timeout(ctx.args[0], 0, "Timeout removed");
await ctx.reply("Timeout removed.");"""
            }
            add(this, "warn", "Warn a member and save the warning", listOf("SEND_MESSAGES"), listOf("MODERATE_MEMBERS")) {
                """const id = ctx.args[0]; const reason = ctx.args.slice(1).join(" ") || "No reason";
if (!id) return ctx.reply("Mention a member to warn.", true);
const key = "warnings:" + id;
const list = JSON.parse(await ctx.variables.get(key, "[]"));
list.push({ reason: reason, by: ctx.user.id, at: Date.now() });
await ctx.variables.set(key, JSON.stringify(list));
await ctx.embed("Warning issued", "<@" + id + "> has been warned.\nReason: " + reason, 0xffa500);"""
            }
            add(this, "warnings", "List warnings for a member") {
                """const id = ctx.args[0] || ctx.user.id;
const list = JSON.parse(await ctx.variables.get("warnings:" + id, "[]"));
await ctx.reply(list.length ? list.map((w, i) => (i + 1) + ". " + w.reason).join("\n") : "No warnings found.", true);"""
            }
            add(this, "clear", "Delete recent messages", listOf("MANAGE_MESSAGES"), listOf("MANAGE_MESSAGES")) {
                """const count = Math.min(Number(ctx.args[0] || 10), 100);
await ctx.delete.bulk(count); await ctx.reply("Deleted " + count + " messages.", true);"""
            }
            add(this, "lock", "Lock the current channel", listOf("MANAGE_CHANNELS"), listOf("MANAGE_CHANNELS")) {
                """await ctx.channel.lock(); await ctx.reply("Channel locked.");"""
            }
            add(this, "unlock", "Unlock the current channel", listOf("MANAGE_CHANNELS"), listOf("MANAGE_CHANNELS")) {
                """await ctx.channel.unlock(); await ctx.reply("Channel unlocked.");"""
            }
            add(this, "slowmode", "Set channel slowmode", listOf("MANAGE_CHANNELS"), listOf("MANAGE_CHANNELS")) {
                """const seconds = Math.min(Number(ctx.args[0] || 5), 21600);
await ctx.channel.slowmode(seconds); await ctx.reply("Slowmode set to " + seconds + "s.");"""
            }
        }

        with(TemplateCategory.MEMBER) {
            add(this, "userinfo", "Show information about a member") {
                """const id = ctx.args[0] || ctx.user.id;
const m = await ctx.member.get(id);
await ctx.embed("User info", "ID: " + m.id + "\nAccount created: " + m.createdAt, 0x7c5cff);"""
            }
            add(this, "avatar", "Show a member avatar") {
                """const id = ctx.args[0] || ctx.user.id; const m = await ctx.member.get(id);
await ctx.embed("Avatar", m.avatarUrl ? "[View avatar](" + m.avatarUrl + ")" : "No avatar.", 0x7c5cff);"""
            }
            add(this, "roles", "List member roles") {
                """const r = await ctx.roles.list(ctx.args[0] || ctx.user.id);
await ctx.reply(r.length ? r.join(", ") : "No roles.", true);"""
            }
            add(this, "profile", "Show a saved member profile") {
                """const id = ctx.args[0] || ctx.user.id;
const bio = await ctx.variables.get("profile:" + id, "No profile saved.");
await ctx.embed("Profile", "**" + id + "**\n" + bio, 0x7c5cff);"""
            }
            add(this, "membercount", "Show the server member count") {
                """await ctx.reply("Members: " + await ctx.guild.memberCount());"""
            }
        }

        with(TemplateCategory.SERVER) {
            add(this, "serverinfo", "Show server information") {
                """await ctx.embed(ctx.guild.name, "ID: " + ctx.guild.id + "\nMembers: " + await ctx.guild.memberCount(), 0x7c5cff);"""
            }
            add(this, "servericon", "Show the server icon") {
                """const url = await ctx.guild.iconUrl();
await ctx.embed("Server icon", url ? "[View icon](" + url + ")" : "No icon.", 0x7c5cff);"""
            }
            add(this, "serverbanner", "Show the server banner") {
                """const url = await ctx.guild.bannerUrl();
await ctx.embed("Server banner", url ? "[View banner](" + url + ")" : "No banner.", 0x7c5cff);"""
            }
            add(this, "channels", "List server channels") {
                """const list = await ctx.guild.channels();
await ctx.reply(list.map(c => "#" + c.name).join(", ") || "No channels.", true);"""
            }
            add(this, "serverroles", "List server roles") {
                """const list = await ctx.guild.roles();
await ctx.reply(list.map(r => r.name).join(", ") || "No roles.", true);"""
            }
        }

        with(TemplateCategory.WELCOME) {
            add(this, "welcome", "Welcome new members", trigger = TriggerType.MEMBER_JOIN) {
                """const channel = await ctx.variables.get("welcomeChannel", ctx.channel.id);
await ctx.send(channel, "Welcome <@" + ctx.user.id + "> to " + ctx.guild.name + "!");"""
            }
            add(this, "goodbye", "Announce member leaves", trigger = TriggerType.MEMBER_LEAVE) {
                """const channel = await ctx.variables.get("welcomeChannel", ctx.channel.id);
await ctx.send(channel, "Goodbye <@" + ctx.user.id + ">. We will miss you.");"""
            }
            add(this, "autorole", "Assign a role to new members", listOf("MANAGE_ROLES"), trigger = TriggerType.MEMBER_JOIN) {
                """const roleId = await ctx.variables.get("autoRoleId", "");
if (roleId) await ctx.roles.add(ctx.user.id, roleId);"""
            }
            add(this, "verification", "Send a verification button", listOf("MANAGE_ROLES")) {
                """const buttons = ctx.buttons().add("verify", "Verify", "success");
await ctx.embed("Verification", "Press the button to verify.", 0x00c853, buttons);"""
            }
        }

        with(TemplateCategory.TICKETS) {
            add(this, "ticket-panel", "Post a ticket panel", listOf("MANAGE_CHANNELS")) {
                """const buttons = ctx.buttons().add("ticket_create", "Create ticket", "primary");
await ctx.embed("Support tickets", "Press the button to open a ticket.", 0x7c5cff, buttons);"""
            }
            add(this, "create-ticket", "Create a private ticket channel", listOf("MANAGE_CHANNELS"), trigger = TriggerType.BUTTON_CLICK) {
                """const channel = await ctx.ticket.create(ctx.user.id, "ticket-" + ctx.user.id);
await ctx.reply("Ticket created: <#" + channel.id + ">", true);"""
            }
            add(this, "claim-ticket", "Claim the current ticket") {
                """await ctx.ticket.claim(ctx.channel.id, ctx.user.id);
await ctx.reply("Ticket claimed by <@" + ctx.user.id + ">.");"""
            }
            add(this, "close-ticket", "Close the current ticket", listOf("MANAGE_CHANNELS")) {
                """const transcript = await ctx.ticket.transcript(ctx.channel.id);
await ctx.variables.set("transcript:" + ctx.channel.id, transcript);
await ctx.ticket.close(ctx.channel.id);"""
            }
            add(this, "transcript", "Show the saved ticket transcript") {
                """const t = await ctx.variables.get("transcript:" + ctx.channel.id, "No transcript found.");
await ctx.reply(t.slice(0, 1900), true);"""
            }
        }

        with(TemplateCategory.LOGGING) {
            fun logTrigger(name: String, description: String, trigger: TriggerType) =
                add(this, name, description, trigger = trigger) {
                    """const line = "[" + new Date().toISOString() + "] " + ctx.trigger.type + " by " + ctx.user.id;
const logChannel = await ctx.variables.get("logChannel", ctx.channel.id);
await ctx.send(logChannel, line);"""
                }
            logTrigger("message-logs", "Log message events", TriggerType.MESSAGE_CREATE)
            logTrigger("member-logs", "Log member events", TriggerType.MEMBER_JOIN)
            logTrigger("role-logs", "Log role events", TriggerType.ROLE_UPDATE)
            logTrigger("channel-logs", "Log channel events", TriggerType.CHANNEL_CREATE)
            add(this, "moderation-logs", "Log moderation actions") {
                """await ctx.log("MODERATION", ctx.args.join(" "));"""
            }
        }

        with(TemplateCategory.UTILITY) {
            add(this, "ping", "Check bot latency") {
                """await ctx.reply("Pong! " + Math.round(ctx.latency()) + " ms", true);"""
            }
            add(this, "help", "List available commands") {
                """const list = await ctx.commands.list();
await ctx.embed("Commands", list.map(c => "/" + c.name + " - " + c.description).join("\n") || "No commands.", 0x7c5cff);"""
            }
            add(this, "embed", "Send a custom embed") {
                """await ctx.embed(ctx.args[0] || "Embed", ctx.args.slice(1).join(" ") || "No content", 0x7c5cff);"""
            }
            add(this, "poll", "Create a poll with reactions") {
                """const [question, ...options] = ctx.args; const m = await ctx.embed("Poll", question || "Poll", 0x7c5cff, null, options);
for (let i = 0; i < options.length && i < 10; i++) await m.react(["1️⃣","2️⃣","3️⃣","4️⃣","5️⃣","6️⃣","7️⃣","8️⃣","9️⃣","🔟"][i]);"""
            }
            add(this, "remind", "Set a reminder") {
                """const minutes = Number(ctx.args[0]); const text = ctx.args.slice(1).join(" ");
await ctx.schedule(minutes * 60, "ctx.reply('Reminder: " + text.replace("'", "\\'") + "');", ctx.channel.id, ctx.user.id);
await ctx.reply("Reminder scheduled.", true);"""
            }
            add(this, "translate", "Translate text (MyMemory HTTPS endpoint)") {
                """const parts = ctx.args.join(" "); const pair = ctx.args[0] || "en"; const text = ctx.args.slice(1).join(" ") || parts;
const url = "https://api.mymemory.translated.net/get?q=" + encodeURIComponent(text) + "&langpair=" + encodeURIComponent(pair + "|en");
const result = JSON.parse(await ctx.http.get(url));
await ctx.reply(result.responseData.translatedText, true);"""
            }
        }

        with(TemplateCategory.ECONOMY) {
            add(this, "balance", "Check your balance") {
                """await ctx.reply("Balance: " + await ctx.variables.get("balance:" + ctx.user.id, "0"), true);"""
            }
            add(this, "daily", "Claim daily coins") {
                """const key = "daily:" + ctx.user.id; const now = Date.now();
const last = Number(await ctx.variables.get(key, "0"));
if (now - last < 86400000) return ctx.reply("Come back tomorrow.", true);
await ctx.variables.set(key, String(now));
await ctx.variables.set("balance:" + ctx.user.id, String(Number(await ctx.variables.get("balance:" + ctx.user.id, "0")) + 100));
await ctx.reply("You received 100 coins.", true);"""
            }
            add(this, "weekly", "Claim weekly coins") {
                """const key = "weekly:" + ctx.user.id; const now = Date.now();
if (now - Number(await ctx.variables.get(key, "0")) < 604800000) return ctx.reply("Come back next week.", true);
await ctx.variables.set(key, String(now));
await ctx.variables.set("balance:" + ctx.user.id, String(Number(await ctx.variables.get("balance:" + ctx.user.id, "0")) + 500));
await ctx.reply("You received 500 coins.", true);"""
            }
            add(this, "pay", "Pay another member") {
                """const target = ctx.args[0]; const amount = Number(ctx.args[1]);
if (!target || !(amount > 0)) return ctx.reply("Usage: pay @member amount", true);
const from = Number(await ctx.variables.get("balance:" + ctx.user.id, "0"));
if (from < amount) return ctx.reply("Insufficient balance.", true);
await ctx.variables.set("balance:" + ctx.user.id, String(from - amount));
await ctx.variables.set("balance:" + target, String(Number(await ctx.variables.get("balance:" + target, "0")) + amount));
await ctx.reply("Paid " + amount + " coins to <@" + target + ">.");"""
            }
            add(this, "leaderboard", "Show the coin leaderboard") {
                """await ctx.reply("Use the Hosting dashboard to export balances for a full leaderboard.", true);"""
            }
            add(this, "shop", "Show the shop") {
                """await ctx.embed("Shop", "1. Role colour - 500 coins\n2. Custom title - 1000 coins", 0x7c5cff);"""
            }
        }

        with(TemplateCategory.FUN) {
            add(this, "coinflip", "Flip a coin") {
                """await ctx.reply(Math.random() < 0.5 ? "Heads" : "Tails");"""
            }
            add(this, "dice", "Roll a dice") {
                """const sides = Math.min(Number(ctx.args[0] || 6), 100);
await ctx.reply("Rolled " + (Math.floor(Math.random() * sides) + 1) + " (d" + sides + ")");"""
            }
            add(this, "random", "Pick a random option") {
                """const options = ctx.args; if (!options.length) return ctx.reply("Provide options.", true);
await ctx.reply(options[Math.floor(Math.random() * options.length)]);"""
            }
            add(this, "trivia", "Ask a trivia question") {
                """await ctx.embed("Trivia", "What is the capital of France?", 0x7c5cff, ctx.buttons().add("trivia_fr_paris", "Paris").add("trivia_fr_lyon", "Lyon"));"""
            }
            add(this, "game", "Start a number guessing game") {
                """const secret = Math.floor(Math.random() * 10) + 1;
await ctx.variables.set("game:" + ctx.channel.id, String(secret));
await ctx.reply("I picked a number from 1 to 10. Guess it!");"""
            }
        }

        with(TemplateCategory.AUTOMATION) {
            add(this, "auto-reply", "Reply when a message contains a word", trigger = TriggerType.MESSAGE_CREATE) {
                """const word = await ctx.variables.get("autoReplyWord", "karal");
if (ctx.args.join(" ").toLowerCase().includes(word)) await ctx.reply("I heard " + word + "!");"""
            }
            add(this, "scheduled-message", "Send a scheduled message", trigger = TriggerType.SCHEDULED_TRIGGER) {
                """const text = await ctx.variables.get("scheduledText", "Scheduled hello");
await ctx.send(await ctx.variables.get("scheduledChannel", ctx.channel.id), text);"""
            }
            add(this, "event-trigger", "Handle a custom event", trigger = TriggerType.GUILD_UPDATE) {
                """await ctx.log("EVENT", ctx.trigger.type + " in " + ctx.guild.id);"""
            }
            add(this, "auto-moderation", "Block banned words in messages", trigger = TriggerType.MESSAGE_CREATE) {
                """const words = (await ctx.variables.get("bannedWords", "spam,scam")).split(",");
if (words.some(w => ctx.args.join(" ").toLowerCase().includes(w.trim()))) await ctx.delete.message();"""
            }
        }
    }

    fun byCategory(category: TemplateCategory): List<TemplateDefinition> = all.filter { it.category == category }

    fun find(key: String): TemplateDefinition? = all.firstOrNull { it.key == key }

    private fun jsString(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
