package com.gios.lightauth

import com.gios.lightauth.totp.TotpAlgorithm
import com.gios.lightauth.totp.TotpGenerator
import com.gios.lightauth.totp.formatExpiryCountdown
import com.gios.lightauth.totp.groupCodeDigits
import kotlin.test.Test
import kotlin.test.assertEquals

class TotpGeneratorTest {
    private val secret = "JBSWY3DPEHPK3PXP"

    @Test
    fun generateKnownTotpCodes() {
        assertEquals(
            "282760",
            TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 0).code,
        )
        assertEquals(
            "996554",
            TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 59).code,
        )
        assertEquals(
            "742275",
            TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 1_234_567_890).code,
        )
    }

    /** A code is stable for the whole window; only the countdown moves. */
    @Test
    fun codeIsStableWithinAWindow() {
        val start = TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 60)
        val end = TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 89)
        assertEquals(start.code, end.code)
        assertEquals(30, start.remainingSeconds)
        assertEquals(1, end.remainingSeconds)
    }

    @Test
    fun secretIsCaseAndSpacingInsensitive() {
        val plain = TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 0)
        val typed = TotpGenerator.generate(
            "jbsw y3dp ehpk 3pxp", 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 0,
        )
        assertEquals(plain.code, typed.code)
    }

    @Test
    fun formatsCountdown() {
        assertEquals("0:09", formatExpiryCountdown(9))
        assertEquals("0:30", formatExpiryCountdown(30))
        assertEquals("1:05", formatExpiryCountdown(65))
    }

    @Test
    fun groupsEvenLengthCodesOnly() {
        assertEquals("282 760", groupCodeDigits("282760"))
        assertEquals("1234 5678", groupCodeDigits("12345678"))
        assertEquals("1234567", groupCodeDigits("1234567"))
    }
}
