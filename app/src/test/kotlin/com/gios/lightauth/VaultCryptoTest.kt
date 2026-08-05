package com.gios.lightauth

import com.gios.lightauth.vault.Lockout
import com.gios.lightauth.vault.VaultCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claims the PIN rests on, checked rather than asserted in a comment.
 *
 * Cheap iterations throughout — the KDF's cost is not what is under test here, and 210k
 * iterations times a dozen cases would make the suite the slowest thing in CI.
 */
class VaultCryptoTest {

    private val cheap = 1_000

    @Test
    fun `the right pin recovers the vault key and a wrong one recovers nothing`() {
        val vaultKey = VaultCrypto.newVaultKey()
        val salt = VaultCrypto.newSalt()
        val wrapped = VaultCrypto.seal(VaultCrypto.deriveKey("1234", salt, cheap), vaultKey)

        val opened = VaultCrypto.open(VaultCrypto.deriveKey("1234", salt, cheap), wrapped)
        assertArrayEquals(vaultKey, opened)

        // Every neighbouring guess, not just one, so an off-by-one in the framing cannot
        // pass by luck.
        for (wrong in listOf("1235", "0234", "4321", "12345", "123")) {
            assertNull(
                "PIN $wrong must not open the vault",
                VaultCrypto.open(VaultCrypto.deriveKey(wrong, salt, cheap), wrapped),
            )
        }
    }

    /** The whole point of the design: the ciphertext alone is not enough. */
    @Test
    fun `the wrapped key is useless without the pin`() {
        val vaultKey = VaultCrypto.newVaultKey()
        val salt = VaultCrypto.newSalt()
        val wrapped = VaultCrypto.seal(VaultCrypto.deriveKey("4821", salt, cheap), vaultKey)

        assertFalse(
            "the key must not appear verbatim in its own wrapper",
            wrapped.toList().windowed(vaultKey.size).any { it.toByteArray().contentEquals(vaultKey) },
        )
        // A different salt is a different key, so the same PIN cannot open it either.
        assertNull(
            VaultCrypto.open(VaultCrypto.deriveKey("4821", VaultCrypto.newSalt(), cheap), wrapped),
        )
    }

    @Test
    fun `salt makes the same pin derive a different key every time`() {
        val a = VaultCrypto.deriveKey("1234", VaultCrypto.newSalt(), cheap).encoded
        val b = VaultCrypto.deriveKey("1234", VaultCrypto.newSalt(), cheap).encoded
        assertFalse("two salts must not produce the same key", a.contentEquals(b))
    }

    @Test
    fun `sealing twice never repeats a nonce`() {
        val key = VaultCrypto.deriveKey("1234", VaultCrypto.newSalt(), cheap)
        val first = VaultCrypto.seal(key, "hello".toByteArray())
        val second = VaultCrypto.seal(key, "hello".toByteArray())
        assertNotEquals(
            "GCM reusing an IV on the same key would be catastrophic",
            first.take(VaultCrypto.GCM_IV_BYTES),
            second.take(VaultCrypto.GCM_IV_BYTES),
        )
    }

    /** GCM authenticates, so a flipped bit must fail rather than decrypt to rubbish. */
    @Test
    fun `a tampered blob does not open`() {
        val key = VaultCrypto.deriveKey("1234", VaultCrypto.newSalt(), cheap)
        val sealed = VaultCrypto.seal(key, "secret".toByteArray())

        for (index in sealed.indices) {
            val tampered = sealed.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertNull("byte $index was flipped and it still opened", VaultCrypto.open(key, tampered))
        }
    }

    @Test
    fun `truncated blobs are rejected rather than throwing`() {
        val key = VaultCrypto.deriveKey("1234", VaultCrypto.newSalt(), cheap)
        assertNull(VaultCrypto.open(key, ByteArray(0)))
        assertNull(VaultCrypto.open(key, ByteArray(VaultCrypto.GCM_IV_BYTES)))
        assertNull(VaultCrypto.open(key, ByteArray(VaultCrypto.GCM_IV_BYTES + 1)))
    }

    @Test
    fun `pin validation accepts four to eight digits and nothing else`() {
        assertTrue(VaultCrypto.isValidPin("1234"))
        assertTrue(VaultCrypto.isValidPin("12345678"))
        assertFalse(VaultCrypto.isValidPin("123"))
        assertFalse(VaultCrypto.isValidPin("123456789"))
        assertFalse(VaultCrypto.isValidPin(""))
        assertFalse(VaultCrypto.isValidPin("12a4"))
        assertFalse(VaultCrypto.isValidPin("12 4"))
        assertFalse(VaultCrypto.isValidPin("１２３４"))
    }

    @Test
    fun `iterations are not accidentally lowered`() {
        // A stray edit here is the kind of change that looks harmless in a diff and quietly
        // makes every PIN cheap to grind.
        assertTrue("PBKDF2 iterations must stay high", VaultCrypto.ITERATIONS >= 200_000)
        assertEquals(256, VaultCrypto.VAULT_KEY_BITS)
        assertEquals(128, VaultCrypto.GCM_TAG_BITS)
        assertTrue(VaultCrypto.SALT_BYTES >= 16)
    }

    @Test
    fun `a longer pin still works end to end`() {
        val vaultKey = VaultCrypto.newVaultKey()
        val salt = VaultCrypto.newSalt()
        val wrapped = VaultCrypto.seal(VaultCrypto.deriveKey("86420135", salt, cheap), vaultKey)
        assertArrayEquals(
            vaultKey,
            VaultCrypto.open(VaultCrypto.deriveKey("86420135", salt, cheap), wrapped),
        )
    }
}

class LockoutTest {

    @Test
    fun `the first few tries are free and then it climbs`() {
        assertEquals(0, Lockout.delaySecondsAfter(1))
        assertEquals(0, Lockout.delaySecondsAfter(2))
        assertEquals(5, Lockout.delaySecondsAfter(3))
        assertEquals(30, Lockout.delaySecondsAfter(5))
        assertEquals(60, Lockout.delaySecondsAfter(7))
        assertEquals(300, Lockout.delaySecondsAfter(10))
        assertEquals(900, Lockout.delaySecondsAfter(15))
        assertEquals(900, Lockout.delaySecondsAfter(500))
    }

    @Test
    fun `the schedule never goes backwards`() {
        var previous = 0L
        for (attempt in 0..40) {
            val delay = Lockout.delaySecondsAfter(attempt)
            assertTrue("delay fell at attempt $attempt", delay >= previous)
            previous = delay
        }
    }

    /**
     * The security claim, as arithmetic: exhausting 10,000 four-digit PINs against this
     * schedule cannot be done in an afternoon.
     */
    @Test
    fun `brute forcing every four digit pin takes months`() {
        val totalSeconds = (1..10_000).sumOf { Lockout.delaySecondsAfter(it) }
        val days = totalSeconds / 86_400.0
        assertTrue("only $days days to try every PIN", days > 60)
    }

    @Test
    fun `waits read as english`() {
        assertEquals("", Lockout.formatWait(0))
        assertEquals("1 second", Lockout.formatWait(1))
        assertEquals("30 seconds", Lockout.formatWait(30))
        assertEquals("1 minute", Lockout.formatWait(60))
        assertEquals("5 minutes", Lockout.formatWait(300))
        // Rounded up, so it never says "0 minutes" or promises a shorter wait than reality.
        assertEquals("2 minutes", Lockout.formatWait(61))
    }
}
