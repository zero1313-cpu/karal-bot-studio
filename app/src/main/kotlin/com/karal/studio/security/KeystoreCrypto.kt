package com.karal.studio.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a device Keystore key. Keys are non-exportable. */
class KeystoreCrypto(private val aliasPrefix: String = "karal_") {
    private val keyStore: KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keyAlias(name: String) =
        aliasPrefix + name.filter { it.isLetterOrDigit() || it == '_' }.take(64)

    private fun ensureKey(alias: String) {
        val real = keyAlias(alias)
        if (!keyStore.containsAlias(real)) {
            val spec = KeyGenParameterSpec.Builder(
                real,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            ).apply {
                init(spec)
                generateKey()
            }
        }
    }

    fun encrypt(alias: String, plain: ByteArray): EncryptedBlob {
        ensureKey(alias)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keyStore.getKey(keyAlias(alias), null)
        )

        val iv = cipher.iv
            ?: throw IllegalStateException("Keystore did not generate an IV.")

        return EncryptedBlob(cipher.doFinal(plain), iv)
    }

    fun decrypt(alias: String, blob: EncryptedBlob): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyStore.getKey(keyAlias(alias), null),
            GCMParameterSpec(128, blob.iv)
        )
        return cipher.doFinal(blob.cipherText)
    }

    fun deleteKey(alias: String) {
        val real = keyAlias(alias)
        if (keyStore.containsAlias(real)) keyStore.deleteEntry(real)
    }
}

data class EncryptedBlob(val cipherText: ByteArray, val iv: ByteArray)
