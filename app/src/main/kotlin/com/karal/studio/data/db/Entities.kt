package com.karal.studio.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects", indices = [Index("modifiedAt"), Index("name")])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val botName: String = "",
    val botId: String = "",
    val applicationId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val runtimeSettingsJson: String = "{}",
    val databaseConfigJson: String = "{}"
)

@Entity(
    tableName = "bots",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index("enabled")]
)
data class BotEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val avatarUrl: String = "",
    /** Alias of the encrypted secret, never the token itself. */
    val tokenAlias: String = "",
    val enabled: Boolean = false,
    val intents: Long = 513L,
    val prefix: String = "!",
    val autoReconnect: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "commands",
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index("name")]
)
data class CommandEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val description: String = "",
    val source: String = "SCRIPT",
    val language: String = "JAVASCRIPT",
    val script: String = "",
    val triggerJson: String = "{}",
    val permissionsJson: String = "{}",
    val requiredBotPermissionsJson: String = "[]",
    val timeoutMs: Long = 5_000L,
    val enabled: Boolean = true,
    val version: Int = 1,
    val metadataJson: String = "{}",
    val updatedAt: Long = System.currentTimeMillis(),
    val approved: Boolean = true
)

@Entity(
    tableName = "command_versions",
    foreignKeys = [ForeignKey(CommandEntity::class, ["id"], ["commandId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("commandId")]
)
data class CommandVersionEntity(
    @PrimaryKey val id: String,
    val commandId: String,
    val projectId: String,
    val version: Int,
    val definitionJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "triggers",
    foreignKeys = [ForeignKey(CommandEntity::class, ["id"], ["commandId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index("commandId")]
)
data class TriggerEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val commandId: String,
    val type: String,
    val optionsJson: String = "{}",
    val enabled: Boolean = true
)

@Entity(
    tableName = "workflows",
    foreignKeys = [ForeignKey(CommandEntity::class, ["id"], ["commandId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("commandId")]
)
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val commandId: String,
    val nodesJson: String = "[]",
    val edgesJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "workflow_nodes",
    foreignKeys = [ForeignKey(WorkflowEntity::class, ["id"], ["workflowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workflowId")]
)
data class WorkflowNodeEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val type: String,
    val group: String,
    val paramsJson: String = "{}",
    val x: Float = 0f,
    val y: Float = 0f,
    val label: String = ""
)

@Entity(
    tableName = "workflow_edges",
    foreignKeys = [ForeignKey(WorkflowEntity::class, ["id"], ["workflowId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workflowId")]
)
data class WorkflowEdgeEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val fromNode: String,
    val toNode: String,
    val fromPort: String = "out",
    val toPort: String = "in"
)

@Entity(tableName = "variables", primaryKeys = ["projectId", "key"], indices = [Index("projectId")])
data class VariableEntity(
    val projectId: String,
    val key: String,
    val value: String,
    val scope: String = "project",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "secrets", indices = [Index("alias")])
data class SecretEntity(
    @PrimaryKey val alias: String,
    val kind: String,
    val cipherText: ByteArray,
    val iv: ByteArray,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecretEntity) return false
        return alias == other.alias && kind == other.kind &&
            cipherText.contentEquals(other.cipherText) && iv.contentEquals(other.iv) &&
            createdAt == other.createdAt && updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = alias.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + cipherText.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        return result
    }
}

@Entity(tableName = "runtime_sessions", indices = [Index("botId"), Index("state")])
data class RuntimeSessionEntity(
    @PrimaryKey val id: String,
    val botId: String,
    val projectId: String,
    val state: String,
    val startedAt: Long = System.currentTimeMillis(),
    val stoppedAt: Long? = null,
    val lastError: String = "",
    val uptimeMs: Long = 0L,
    val serverCount: Int = 0
)

@Entity(tableName = "runtime_logs", indices = [Index("timestamp"), Index("botId"), Index("level")])
data class RuntimeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val botId: String = "",
    val projectId: String = "",
    val sessionId: String = "",
    val level: String = "INFO",
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "backups", indices = [Index("projectId"), Index("createdAt")])
data class BackupEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val path: String,
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val encrypted: Boolean = false,
    val checksum: String = "",
    val validated: Boolean = false
)

@Entity(tableName = "templates", indices = [Index("category")])
data class TemplateEntity(
    @PrimaryKey val key: String,
    val category: String,
    val title: String,
    val definitionJson: String,
    val customized: Boolean = false
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "api_providers", indices = [Index("provider")])
data class ApiProviderEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val endpoint: String,
    val model: String,
    val keySecretAlias: String,
    val enabled: Boolean = false
)
