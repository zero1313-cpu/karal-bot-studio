package com.karal.studio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karal.studio.data.db.KaralDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun templatesAndDefaultsAreSeeded() = runBlocking {
        val db = KaralDatabase.get(context)
        KaralDatabase.seed(db)
        assertTrue(db.templateDao().count() > 50)
        assertEquals("hi", db.settingsDao().get("language"))
    }

    @Test
    fun projectCascadesToCommands() = runBlocking {
        val db = KaralDatabase.get(context)
        val repo = com.karal.studio.data.repository.ProjectRepository(db)
        val commands = com.karal.studio.data.repository.CommandRepositoryImpl(db)
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
}
