package com.karal.studio.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY modifiedAt DESC")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY modifiedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY modifiedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ProjectEntity>>

    @Query("SELECT COUNT(*) FROM projects WHERE archived = 0")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name, description = :description, botName = :botName, botId = :botId, applicationId = :applicationId, archived = :archived, modifiedAt = :now WHERE id = :id")
    suspend fun updateDetails(id: String, name: String, description: String, botName: String, botId: String, applicationId: String, archived: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface BotDao {
    @Query("SELECT * FROM bots ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BotEntity>>

    @Query("SELECT * FROM bots WHERE projectId = :projectId")
    fun observeByProject(projectId: String): Flow<List<BotEntity>>

    @Query("SELECT * FROM bots WHERE projectId = :projectId")
    suspend fun getAllForProject(projectId: String): List<BotEntity>

    @Query("SELECT * FROM bots WHERE id = :id")
    suspend fun get(id: String): BotEntity?

    @Query("SELECT COUNT(*) FROM bots")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bot: BotEntity)

    @Query("UPDATE bots SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM bots WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface CommandDao {
    @Query("SELECT * FROM commands WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeByProject(projectId: String): Flow<List<CommandEntity>>

    @Query("SELECT * FROM commands WHERE projectId = :projectId")
    suspend fun getAllForProject(projectId: String): List<CommandEntity>

    @Query("SELECT * FROM commands ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CommandEntity>>

    @Query("SELECT * FROM commands WHERE id = :id")
    suspend fun get(id: String): CommandEntity?

    @Query("SELECT COUNT(*) FROM commands")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(command: CommandEntity)

    @Query("DELETE FROM commands WHERE id = :id")
    suspend fun delete(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: CommandVersionEntity)

    @Query("SELECT * FROM command_versions WHERE commandId = :commandId ORDER BY version DESC")
    fun versions(commandId: String): Flow<List<CommandVersionEntity>>
}

@Dao
interface TriggerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trigger: TriggerEntity)

    @Query("SELECT * FROM triggers WHERE projectId = :projectId")
    fun observeByProject(projectId: String): Flow<List<TriggerEntity>>

    @Query("DELETE FROM triggers WHERE commandId = :commandId")
    suspend fun deleteForCommand(commandId: String)
}

@Dao
interface WorkflowDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workflow: WorkflowEntity)

    @Query("SELECT * FROM workflows WHERE commandId = :commandId")
    suspend fun getForCommand(commandId: String): WorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNodes(nodes: List<WorkflowNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdges(edges: List<WorkflowEdgeEntity>)

    @Query("DELETE FROM workflow_nodes WHERE workflowId = :workflowId")
    suspend fun clearNodes(workflowId: String)

    @Query("DELETE FROM workflow_edges WHERE workflowId = :workflowId")
    suspend fun clearEdges(workflowId: String)
}

@Dao
interface VariableDao {
    @Query("SELECT * FROM variables WHERE projectId = :projectId")
    fun observeByProject(projectId: String): Flow<List<VariableEntity>>

    @Query("SELECT * FROM variables WHERE projectId = :projectId")
    suspend fun getAllForProject(projectId: String): List<VariableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(variable: VariableEntity)

    @Query("DELETE FROM variables WHERE projectId = :projectId AND key = :key")
    suspend fun delete(projectId: String, key: String)

    @Query("DELETE FROM variables WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface SecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(secret: SecretEntity)

    @Query("SELECT * FROM secrets WHERE alias = :alias")
    suspend fun get(alias: String): SecretEntity?

    @Query("SELECT * FROM secrets")
    suspend fun all(): List<SecretEntity>

    @Query("DELETE FROM secrets WHERE alias = :alias")
    suspend fun delete(alias: String)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RuntimeSessionEntity)

    @Query("SELECT * FROM runtime_sessions WHERE botId = :botId ORDER BY startedAt DESC LIMIT 1")
    fun observeBot(botId: String): Flow<RuntimeSessionEntity?>

    @Query("SELECT * FROM runtime_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RuntimeSessionEntity>>

    @Query("SELECT state, COUNT(*) AS count FROM runtime_sessions GROUP BY state")
    fun observeCounts(): Flow<List<SessionCount>>

    @Query("SELECT * FROM runtime_sessions WHERE state = 'RUNNING'")
    suspend fun running(): List<RuntimeSessionEntity>
}

data class SessionCount(val state: String, val count: Int)

@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: RuntimeLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<RuntimeLogEntity>)

    @Query("SELECT * FROM runtime_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RuntimeLogEntity>>

    @Query("SELECT * FROM runtime_logs WHERE botId = :botId ORDER BY timestamp DESC LIMIT :limit")
    fun observeForBot(botId: String, limit: Int): Flow<List<RuntimeLogEntity>>

    @Query("DELETE FROM runtime_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM runtime_logs WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("DELETE FROM runtime_logs")
    suspend fun clear()
}

@Dao
interface BackupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(backup: BackupEntity)

    @Query("SELECT * FROM backups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BackupEntity>>

    @Query("SELECT * FROM backups WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeForProject(projectId: String): Flow<List<BackupEntity>>

    @Query("SELECT * FROM backups WHERE id = :id")
    suspend fun get(id: String): BackupEntity?

    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: TemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(templates: List<TemplateEntity>)

    @Query("SELECT * FROM templates ORDER BY category, title")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT COUNT(*) FROM templates")
    suspend fun count(): Int
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}

@Dao
interface ApiProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provider: ApiProviderEntity)

    @Query("SELECT * FROM api_providers")
    fun observeAll(): Flow<List<ApiProviderEntity>>

    @Query("SELECT * FROM api_providers WHERE id = :id")
    suspend fun get(id: String): ApiProviderEntity?
}
