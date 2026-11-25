package com.karal.studio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.karal.studio.core.json.Json
import com.karal.studio.core.model.CoreJson
import com.karal.studio.core.templates.BuiltInTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Database(
    entities = [
        ProjectEntity::class, BotEntity::class, CommandEntity::class, CommandVersionEntity::class,
        TriggerEntity::class, WorkflowEntity::class, WorkflowNodeEntity::class, WorkflowEdgeEntity::class,
        VariableEntity::class, SecretEntity::class, RuntimeSessionEntity::class, RuntimeLogEntity::class,
        BackupEntity::class, TemplateEntity::class, SettingEntity::class, ApiProviderEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KaralDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun botDao(): BotDao
    abstract fun commandDao(): CommandDao
    abstract fun triggerDao(): TriggerDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun variableDao(): VariableDao
    abstract fun secretDao(): SecretDao
    abstract fun sessionDao(): SessionDao
    abstract fun logDao(): LogDao
    abstract fun backupDao(): BackupDao
    abstract fun templateDao(): TemplateDao
    abstract fun settingsDao(): SettingsDao
    abstract fun apiProviderDao(): ApiProviderDao

    companion object {
        @Volatile
        private var instance: KaralDatabase? = null

        fun get(context: Context): KaralDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, KaralDatabase::class.java, "karal.db")
                // No destructive fallback: future schema versions must provide explicit migrations.
                .build()
                .also { instance = it }
        }

        suspend fun seed(database: KaralDatabase) {
            if (database.templateDao().count() == 0) {
                database.templateDao().upsertAll(BuiltInTemplates.all.map { tpl ->
                    TemplateEntity(
                        key = tpl.key,
                        category = tpl.category.name,
                        title = tpl.title,
                        definitionJson = Json.stringify(CoreJson.encodeCommand(tpl.command))
                    )
                })
            }
            val defaults = mapOf(
                "theme" to "SYSTEM",
                "language" to "hi",
                "editor_font_size" to "14",
                "editor_tab_size" to "2",
                "runtime_timeout_ms" to "5000",
                "runtime_max_steps" to "500",
                "auto_backup" to "true",
                "backup_interval_hours" to "24",
                "log_retention_days" to "14",
                "notifications_enabled" to "true",
                "auto_start_enabled" to "true",
                "diagnostics_enabled" to "false",
                "ai_endpoint" to "https://api.openai.com/v1/chat/completions",
                "ai_model" to "gpt-4o-mini",
                "ai_provider" to "OPENAI_COMPATIBLE",
                "security_notice_seen" to "false"
            )
            defaults.forEach { (key, value) ->
                if (database.settingsDao().get(key) == null) {
                    database.settingsDao().put(SettingEntity(key, value))
                }
            }
        }
    }
}
