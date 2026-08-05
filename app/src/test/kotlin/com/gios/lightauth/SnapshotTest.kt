package com.gios.lightauth

import com.gios.lightauth.backup.Snapshot
import com.gios.lightauth.vault.VaultCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The backup payload, which is the only thing standing between a lost phone and lost accounts.
 *
 * The property that matters: a snapshot must open with the **PIN alone**. Anything that also
 * needed the phone's AndroidKeyStore key would restore into nothing on a new device — the exact
 * failure the whole `otpauth://` export exists to avoid.
 */
@RunWith(RobolectricTestRunner::class)
class SnapshotTest {

    private val uris = listOf(
        "otpauth://totp/GitHub:gi-os?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA1&digits=6&period=30",
        "otpauth://totp/Google:g.lupo%40lrparis.com?secret=GEZDGNBVGY3TQOJQ&issuer=Google&algorithm=SHA1&digits=6&period=30",
    )

    private fun sealWith(pin: String): String {
        val salt = VaultCrypto.newSalt()
        return Snapshot.seal(uris, VaultCrypto.deriveKey(pin, salt), salt)
    }

    @Test
    fun `a snapshot opens with the pin alone`() {
        val payload = sealWith("1234")
        assertTrue(Snapshot.isSealed(payload))
        assertEquals(uris, Snapshot.open(payload, "1234"))
    }

    @Test
    fun `a wrong pin opens nothing`() {
        val payload = sealWith("1234")
        for (wrong in listOf("1235", "4321", "0000", "12345")) {
            assertNull("PIN $wrong must not open the backup", Snapshot.open(payload, wrong))
        }
    }

    /** The reason the salt travels inside the payload rather than in prefs. */
    @Test
    fun `an old snapshot still opens after the pin is changed`() {
        val old = sealWith("1234")
        // A new PIN mints a new salt, but the old payload carries its own.
        sealWith("8888")
        assertEquals(uris, Snapshot.open(old, "1234"))
    }

    @Test
    fun `no secret appears in the sealed payload`() {
        val payload = sealWith("1234")
        assertFalse("the base32 secret must not be readable", payload.contains("JBSWY3DPEHPK3PXP"))
        assertFalse(payload.contains("GEZDGNBVGY3TQOJQ"))
        assertFalse("the account name must not leak either", payload.contains("GitHub"))
        assertFalse("nor the PIN", payload.contains("1234"))
    }

    @Test
    fun `plaintext exports are recognised as unsealed`() {
        assertFalse(Snapshot.isSealed(uris.joinToString("\n")))
        assertFalse(Snapshot.isSealed(""))
    }

    @Test
    fun `a corrupt payload returns null rather than throwing`() {
        assertNull(Snapshot.open("LIGHTAUTH-BACKUP-1", "1234"))
        assertNull(Snapshot.open("LIGHTAUTH-BACKUP-1\n210000\nnotbase64!!\nalsonot", "1234"))
        assertNull(Snapshot.open("garbage", "1234"))
        assertNull(Snapshot.open("", "1234"))
    }

    /** A flipped byte anywhere in the ciphertext must fail the tag check, not half-restore. */
    @Test
    fun `a tampered payload does not open`() {
        val payload = sealWith("1234")
        val lines = payload.lines().toMutableList()
        val body = lines[3]
        lines[3] = body.take(body.length - 4) + if (body.endsWith("A")) "BBBB" else "AAAA"
        assertNull(Snapshot.open(lines.joinToString("\n"), "1234"))
    }

    /**
     * A backup written by a build with different iteration counts still has to open, which is
     * why the count is a field rather than a constant read at restore time.
     */
    @Test
    fun `the iteration count is read from the payload`() {
        val salt = VaultCrypto.newSalt()
        val payload = Snapshot.seal(uris, VaultCrypto.deriveKey("1234", salt, 1_000), salt)
            .lines().toMutableList()
            .also { it[1] = "1000" }
            .joinToString("\n")
        assertEquals(uris, Snapshot.open(payload, "1234"))
    }

    @Test
    fun `an empty vault seals and opens as empty`() {
        val salt = VaultCrypto.newSalt()
        val payload = Snapshot.seal(emptyList(), VaultCrypto.deriveKey("1234", salt), salt)
        assertEquals(emptyList<String>(), Snapshot.open(payload, "1234"))
    }
}
