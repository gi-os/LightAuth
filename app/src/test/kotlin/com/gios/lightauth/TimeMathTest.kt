package com.gios.lightauth

import com.gios.lightauth.time.TimeMath
import com.gios.lightauth.totp.TotpGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeMathTest {

    @Test
    fun `correction shifts the clock TOTP counts in`() {
        assertEquals(1_000_030L, TimeMath.corrected(1_000_000L, 30))
        assertEquals(999_970L, TimeMath.corrected(1_000_000L, -30))
        assertEquals(1_000_000L, TimeMath.corrected(1_000_000L, 0))
    }

    @Test
    fun `offset is clamped to a day either way`() {
        assertEquals(TimeMath.MAX_OFFSET_SECONDS, TimeMath.clampOffset(Int.MAX_VALUE))
        assertEquals(-TimeMath.MAX_OFFSET_SECONDS, TimeMath.clampOffset(Int.MIN_VALUE))
        assertEquals(7, TimeMath.clampOffset(7))
    }

    /** Providers accept the neighbouring window, so drift under one period still works. */
    @Test
    fun `drift inside one period is survivable`() {
        assertTrue(TimeMath.isDriftHarmless(29, 30))
        assertTrue(TimeMath.isDriftHarmless(-29, 30))
        assertFalse(TimeMath.isDriftHarmless(30, 30))
        assertFalse(TimeMath.isDriftHarmless(-45, 30))
    }

    @Test
    fun `offset always carries a sign`() {
        assertEquals("+0s", TimeMath.formatOffset(0))
        assertEquals("-9s", TimeMath.formatOffset(-9))
        assertEquals("+2m 5s", TimeMath.formatOffset(125))
        assertEquals("-1h 1m", TimeMath.formatOffset(-3660))
    }

    @Test
    fun `utc clock is formatted by arithmetic`() {
        assertEquals("00:00:00", TimeMath.formatUtcClock(0))
        assertEquals("01:02:03", TimeMath.formatUtcClock(3723))
        assertEquals("23:59:59", TimeMath.formatUtcClock(86_399))
        // Wraps into the next day rather than overflowing past 24h.
        assertEquals("00:00:01", TimeMath.formatUtcClock(86_401))
    }

    /**
     * The bug this whole screen exists for: a phone 90 seconds behind produces a code the
     * site refuses, and the correction has to bring it back to the real one exactly.
     */
    @Test
    fun `correcting a drifted clock reproduces the real code`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val realNow = 1_111_111_111L
        val phoneNow = realNow - 90

        val drifted = TotpGenerator.generate(secret, 6, 30, "SHA1", phoneNow).code
        val real = TotpGenerator.generate(secret, 6, 30, "SHA1", realNow).code
        assertFalse("a 90s drift must change the code", drifted == real)

        val fixed = TotpGenerator
            .generate(secret, 6, 30, "SHA1", TimeMath.corrected(phoneNow, 90))
            .code
        assertEquals(real, fixed)
    }

    /** RFC 6238 appendix B, so a regression in the generator fails the build. */
    @Test
    fun `rfc 6238 test vectors`() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val vectors = listOf(
            59L to "94287082",
            1_111_111_109L to "07081804",
            1_111_111_111L to "14050471",
            1_234_567_890L to "89005924",
            2_000_000_000L to "69279037",
            20_000_000_000L to "65353130",
        )
        for ((time, expected) in vectors) {
            assertEquals(
                "TOTP at $time",
                expected,
                TotpGenerator.generate(secret, 8, 30, "SHA1", time).code,
            )
        }
    }
}
