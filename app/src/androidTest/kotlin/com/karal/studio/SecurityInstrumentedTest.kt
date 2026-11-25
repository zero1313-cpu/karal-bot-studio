package com.karal.studio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.karal.studio.security.KeystoreCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {
    @Test
    fun keystoreEncryptsAndDecrypts() {
        val crypto = KeystoreCrypto()
        val alias = "instrumented_test_${System.currentTimeMillis()}"
        val plain = "MT${"A".repeat(24)}.${"B".repeat(6)}.${"C".repeat(30)}".toByteArray()
        val blob = crypto.encrypt(alias, plain)
        assertFalse(String(blob.cipherText).contains("MT"))
        assertArrayEquals(plain, crypto.decrypt(alias, blob))
        crypto.deleteKey(alias)
    }
}
