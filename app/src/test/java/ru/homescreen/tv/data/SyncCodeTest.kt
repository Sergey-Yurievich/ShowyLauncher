package ru.homescreen.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

class SyncCodeTest {
    @Test fun codeIsFourDigitsAndStable() {
        val secret = ByteArray(32) { it.toByte() }
        val c = Sync.code(secret)
        assertTrue(c, Regex("\\d{4}").matches(c))
        assertEquals(c, Sync.code(secret.copyOf()))
    }

    @Test fun differentSecretsMostlyGiveDifferentCodes() {
        val codes = (0 until 200).map { i -> Sync.code(ByteArray(32) { (it * 31 + i).toByte() }) }.toSet()
        assertTrue(codes.size > 190)
        assertNotEquals(Sync.code(ByteArray(32)), Sync.code(ByteArray(32).also { it[0] = 1 }))
    }

    @Test fun aesRoundTripAndWrongPurposeFails() {
        val key = ByteArray(32) { 7 }
        val iv = ByteArray(12) { 1 }
        val sealed = Sync.aes(Cipher.ENCRYPT_MODE, key, iv, "ok".toByteArray(), "confirm")
        assertArrayEquals("ok".toByteArray(), Sync.aes(Cipher.DECRYPT_MODE, key, iv, sealed, "confirm"))
        try {
            Sync.aes(Cipher.DECRYPT_MODE, key, iv, sealed, "pair")
            throw AssertionError("decrypted under another purpose")
        } catch (_: AEADBadTagException) {
        }
    }
}
