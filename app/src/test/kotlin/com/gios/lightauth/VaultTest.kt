package com.gios.lightauth

import androidx.test.core.app.ApplicationProvider
import com.gios.lightauth.crypto.TotpSecretCipher
import com.gios.lightauth.vault.DeviceKey
import com.gios.lightauth.vault.Vault
import com.gios.lightauth.vault.VaultCrypto
import com.gios.lightauth.vault.VaultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import javax.crypto.SecretKey

/**
 * The lock, end to end, on real SharedPreferences.
 *
 * These are the tests that answer "does it actually work" rather than "is the maths right" —
 * the maths is [VaultCryptoTest]'s job. What matters here is that a locked vault genuinely
 * cannot produce a secret, that unlocking returns the *same* key so existing rows still
 * decrypt, and that turning the PIN on and off never strands an account.
 *
 * The AndroidKeyStore layer is stood in for by a fixed in-memory key, which is the one thing
 * Robolectric cannot give us. Everything else is the production code path.
 */
@RunWith(RobolectricTestRunner::class)
class VaultTest {

    /** Stable across a test so the outer wrap behaves like a persistent device key. */
    private class FakeDeviceKey(private val key: SecretKey) : DeviceKey {
        override fun ensure() = Unit
        override fun key(): SecretKey = key
    }

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    fun setUp() {
        context.getSharedPreferences("lightauth_vault", 0).edit().clear().commit()
        Vault.deviceKeyForTest(FakeDeviceKey(VaultCrypto.keyFromBytes(ByteArray(32) { 7 })))
        Vault.init(context)
    }

    // ------------------------------------------------------------------ no PIN

    @Test
    fun `with no pin the vault opens by itself`() {
        assertEquals(VaultState.Open, Vault.state.value)
        assertNotNull("the key must be available with no PIN set", Vault.key())
        assertFalse(Vault.isPinSet())
    }

    @Test
    fun `locking does nothing while no pin is set`() {
        Vault.lock()
        assertEquals(VaultState.Open, Vault.state.value)
        assertNotNull("re-locking with no PIN would be an unopenable app", Vault.key())
    }

    // ------------------------------------------------------------------ the core claim

    /**
     * The whole feature in one test: a secret stored while open is unreadable once locked,
     * and readable again only after the right PIN.
     */
    @Test
    fun `a locked vault cannot decrypt a stored secret`() {
        val plaintext = "JBSWY3DPEHPK3PXP"
        val row = TotpSecretCipher.encrypt(Vault.key()!!, plaintext)

        assertTrue(Vault.setPin("1234"))
        Vault.lock()

        assertEquals(VaultState.Locked, Vault.state.value)
        assertNull("a locked vault must hand out no key at all", Vault.key())

        assertTrue(Vault.unlock("1234") is Vault.UnlockResult.Success)
        assertEquals(
            "the same key must come back, or every existing row is stranded",
            plaintext,
            TotpSecretCipher.decrypt(Vault.key()!!, row),
        )
    }

    @Test
    fun `a wrong pin leaves the vault shut`() {
        Vault.setPin("1234")
        Vault.lock()

        val result = Vault.unlock("9999")
        assertTrue(result is Vault.UnlockResult.Wrong)
        assertNull(Vault.key())
        assertEquals(VaultState.Locked, Vault.state.value)
    }

    @Test
    fun `failures are counted across a restart`() {
        Vault.setPin("1234")
        Vault.lock()
        Vault.unlock("0000")
        Vault.unlock("0001")

        // A reboot is the obvious way to try to shake off a lockout, so the count has to
        // outlive the process.
        Vault.init(context)
        assertEquals(2, Vault.failedAttempts())
    }

    @Test
    fun `a correct pin clears the failure count`() {
        Vault.setPin("1234")
        Vault.lock()
        Vault.unlock("0000")
        assertEquals(1, Vault.failedAttempts())
        Vault.unlock("1234")
        assertEquals(0, Vault.failedAttempts())
    }

    @Test
    fun `the fourth wrong pin starts a lockout and it is enforced`() {
        Vault.setPin("1234")
        Vault.lock()
        repeat(3) { Vault.unlock("0000") }

        assertTrue("a lockout should be running", Vault.remainingLockoutSeconds() > 0)
        // And it applies to the *right* PIN too, or it would be trivially skippable.
        assertTrue(Vault.unlock("1234") is Vault.UnlockResult.Throttled)
        assertNull(Vault.key())
    }

    // ------------------------------------------------------------------ changing it

    @Test
    fun `changing the pin keeps every account readable`() {
        val plaintext = "JBSWY3DPEHPK3PXP"
        val row = TotpSecretCipher.encrypt(Vault.key()!!, plaintext)

        Vault.setPin("1234")
        assertTrue(Vault.setPin("87654321"))
        Vault.lock()

        assertTrue(Vault.unlock("1234") is Vault.UnlockResult.Wrong)
        assertTrue(Vault.unlock("87654321") is Vault.UnlockResult.Success)
        assertEquals(plaintext, TotpSecretCipher.decrypt(Vault.key()!!, row))
        assertEquals(8, Vault.pinLength())
    }

    @Test
    fun `turning the pin off keeps every account readable`() {
        val plaintext = "JBSWY3DPEHPK3PXP"
        val row = TotpSecretCipher.encrypt(Vault.key()!!, plaintext)

        Vault.setPin("1234")
        assertTrue(Vault.clearPin())

        assertEquals(VaultState.Open, Vault.state.value)
        assertEquals(plaintext, TotpSecretCipher.decrypt(Vault.key()!!, row))

        // And it stays open across a restart rather than asking for a PIN that is gone.
        Vault.init(context)
        assertEquals(VaultState.Open, Vault.state.value)
        assertEquals(plaintext, TotpSecretCipher.decrypt(Vault.key()!!, row))
    }

    @Test
    fun `a pin survives a restart`() {
        Vault.setPin("5678")
        Vault.init(context)

        assertEquals(VaultState.Locked, Vault.state.value)
        assertNull(Vault.key())
        assertTrue(Vault.isPinSet())
        assertTrue(Vault.unlock("5678") is Vault.UnlockResult.Success)
    }

    @Test
    fun `a pin that is not four to eight digits is refused`() {
        assertFalse(Vault.setPin("12"))
        assertFalse(Vault.setPin("123456789"))
        assertFalse(Vault.setPin("abcd"))
        assertFalse("a rejected PIN must not half-enable the lock", Vault.isPinSet())
    }

    @Test
    fun `verify checks a pin without changing the lock state`() {
        Vault.setPin("1234")
        Vault.lock()
        assertTrue(Vault.verifyPin("1234"))
        assertFalse(Vault.verifyPin("4321"))
        assertNull("verifying must not silently unlock", Vault.key())
    }

    // ------------------------------------------------------------------ wipe

    @Test
    fun `wipe is off by default`() {
        assertFalse(Vault.isWipeEnabled())
        assertEquals(Vault.DEFAULT_WIPE_AFTER, Vault.wipeAfter())
    }

    @Test
    fun `with wipe off the vault only throttles no matter how many tries`() {
        Vault.setPin("1234")
        Vault.lock()
        repeat(40) {
            ShadowSystemClock.advanceBy(Duration.ofMinutes(20))
            Vault.unlock("0000")
        }

        assertTrue("the PIN must still exist", Vault.isPinSet())
        assertFalse(Vault.state.value == VaultState.Open)
    }

    @Test
    fun `with wipe on the vault erases at the configured count`() {
        var rowsDropped = false
        Vault.onWipe = { rowsDropped = true }

        val row = TotpSecretCipher.encrypt(Vault.key()!!, "JBSWY3DPEHPK3PXP")
        Vault.setPin("1234")
        Vault.setWipeEnabled(true)
        Vault.setWipeAfter(5)
        Vault.lock()

        // The clock has to be advanced between guesses. The lockout gates the wipe counter —
        // a throttled attempt is not a failed one — so hammering in a tight loop never reaches
        // the threshold. That is the correct behaviour and worth stating: erasing takes a
        // patient attacker, not a fast one.
        var wiped = false
        repeat(5) {
            ShadowSystemClock.advanceBy(Duration.ofMinutes(20))
            if (Vault.unlock("0000") is Vault.UnlockResult.Wiped) wiped = true
        }

        assertTrue("five wrong PINs should have wiped", wiped)
        assertTrue("the accounts must be dropped too", rowsDropped)
        // The old row is now undecryptable, because the key it needed no longer exists.
        assertNull(TotpSecretCipher.decrypt(Vault.key()!!, row))
        // And the app is usable rather than bricked.
        assertEquals(VaultState.Open, Vault.state.value)
        assertFalse(Vault.isPinSet())
    }

    /** A throttled attempt must not count toward the wipe, or the two features fight. */
    @Test
    fun `throttled attempts do not push the vault toward a wipe`() {
        Vault.setPin("1234")
        Vault.setWipeEnabled(true)
        Vault.setWipeAfter(5)
        Vault.lock()

        repeat(30) { Vault.unlock("0000") }

        assertTrue("hammering must not erase anything", Vault.isPinSet())
        assertEquals(
            "only the un-throttled attempts should have counted",
            3,
            Vault.failedAttempts(),
        )
    }

    @Test
    fun `the wipe threshold is clamped to something sane`() {
        Vault.setWipeAfter(1)
        assertTrue("one attempt would be a footgun", Vault.wipeAfter() >= 3)
        Vault.setWipeAfter(9999)
        assertTrue(Vault.wipeAfter() <= 50)
    }

    // ------------------------------------------------------------------ at rest

    /**
     * What an attacker with the `shared_prefs` file actually has. Nothing here may resemble
     * the key, and nothing may hint at the PIN.
     */
    @Test
    fun `nothing usable is written to prefs`() {
        Vault.setPin("1234")
        val raw = Vault.key()!!.encoded
        Vault.lock()

        val all = context.getSharedPreferences("lightauth_vault", 0).all
        val dump = all.entries.joinToString("\n") { "${it.key}=${it.value}" }

        assertFalse("the PIN must never be stored", dump.contains("1234"))
        val hex = raw.joinToString("") { "%02x".format(it) }
        assertFalse("the vault key must never be stored", dump.lowercase().contains(hex))
        assertTrue("the wrapped key should be there", all.containsKey("wrapped_vault_key"))
    }
}
