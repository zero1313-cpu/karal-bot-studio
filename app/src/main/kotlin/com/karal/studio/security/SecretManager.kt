package com.karal.studio.security

import com.karal.studio.core.security.SecretPolicy
import com.karal.studio.data.db.SecretDao
import com.karal.studio.data.db.SecretEntity

/** Credentials never appear in ordinary project rows, logs, backups or exports. */
class SecretManager(private val dao: SecretDao, private val crypto: KeystoreCrypto = KeystoreCrypto()) {
    suspend fun store(alias: String, value: String, kind: String = "GENERIC") {
        require(alias.isNotBlank()) { "Secret alias is required." }
        require(value.length <= 8_192) { "Secret is too large." }
        val blob = crypto.encrypt(alias, value.toByteArray(Charsets.UTF_8))
        dao.upsert(
            SecretEntity(
                alias = alias,
                kind = kind,
                cipherText = blob.cipherText,
                iv = blob.iv,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun read(alias: String): String? {
        val entity = dao.get(alias) ?: return null
        return String(crypto.decrypt(alias, EncryptedBlob(entity.cipherText, entity.iv)), Charsets.UTF_8)
    }

    suspend fun delete(alias: String) {
        dao.delete(alias)
        crypto.deleteKey(alias)
    }

    suspend fun aliases(): List<String> = dao.all().map { it.alias }

    /** Used before writing any string to a log or export file. */
    fun redact(value: String): String = SecretPolicy.redact(value)
}
