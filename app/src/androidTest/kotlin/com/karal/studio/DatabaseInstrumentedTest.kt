package com.karal.studio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karal.studio.data.db.KaralDatabase
import com.karal.studio.data.repository.BotRepository
import com.karal.studio.data.repository.CommandRepositoryImpl
import com.karal.studio.data.repository.ProjectRepository
import com.karal.studio.security.SecretManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun db() = KaralDatabase.get(context)

    @Test
    fun templatesAndDefaultsAreSeeded() = runBlocking {
        val db = db()
        KaralDatabase.seed(db)
        assertTrue(db.templateDao().count() > 50)
        assertEquals("hi", db.settingsDao().get("language"))
    }

    @Test
    fun projectCascadesToCommands() = runBlocking {
        val db = db()
        val repo = ProjectRepository(db)
        val commands = CommandRepositoryImpl(db)
        val project = repo.create("Instrumented project")
        val definition = com.karal.studio.core.model.CommandDefinition(
            id = java.util.UUID.randomUUID().toString(),
            projectId = project.id,
            name = "ping"
        )
        commands.save(definition, createVersion = false)
        assertEquals(1, db.commandDao().getAllForProject(project.id).size)
        repo.delete(project.id)
        assertTrue(db.commandDao().getAllForProject(project.id).isEmpty())
    }

    @Test
    fun projectCreationCreatesDefaultBotWithTokenAlias() = runBlocking {
        val db = db()
        val repo = ProjectRepository(db)
        val project = repo.create("Project with bot", botName = "MainBot")
        val bots = db.botDao().getAllForProject(project.id)
        assertEquals(1, bots.size)
        assertTrue(bots.first().tokenAlias.isNotBlank())
    }

    @Test
    fun botCreationGeneratesUniqueTokenAlias() = runBlocking {
        val db = db()
        val projectRepo = ProjectRepository(db)
        val project = projectRepo.create("Bot project")
        val botRepo = BotRepository(db, SecretManager(db.secretDao()))
        val bot = botRepo.create(project.id, "TestBot")
        assertTrue(bot.tokenAlias.isNotBlank())
        val loaded = botRepo.get(bot.id)
        assertNotNull(loaded)
        assertTrue(loaded!!.tokenAlias.isNotBlank())
    }

    @Test
    fun tokenSavingEncryptsAndRetrieves() = runBlocking {
        val db = db()
        val projectRepo = ProjectRepository(db)
        val project = projectRepo.create("Token project")
        val botRepo = BotRepository(db, SecretManager(db.secretDao()))
        val bot = botRepo.create(project.id, "TokenBot")
        val token = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0.ABCDEF.abcdefghijklmnopqrstuvwxyz123456"
        botRepo.saveToken(bot.id, token)
        val stored = botRepo.token(bot.id)
        assertEquals(token, stored)
    }

    @Test
    fun commandSavingPersistsDefinition() = runBlocking {
        val db = db()
        val projectRepo = ProjectRepository(db)
        val project = projectRepo.create("Command project")
        val commands = CommandRepositoryImpl(db)
        val definition = com.karal.studio.core.model.CommandDefinition(
            id = java.util.UUID.randomUUID().toString(),
            projectId = project.id,
            name = "hello",
            description = "Says hello",
            script = "command({name:'hello'}, (ctx)=>ctx.reply('hi'))",
            approved = true
        )
        commands.save(definition, createVersion = false)
        val saved = commands.get(definition.id)
        assertNotNull(saved)
        assertEquals("hello", saved!!.name)
        assertEquals(project.id, saved.projectId)
    }
}
