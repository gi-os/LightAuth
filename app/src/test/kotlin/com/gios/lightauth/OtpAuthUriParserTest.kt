package com.gios.lightauth

import com.gios.lightauth.totp.OtpAuthUriParser
import com.gios.lightauth.totp.TotpAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtpAuthUriParserTest {
    @Test
    fun parseStandardGoogleUri() {
        val account = OtpAuthUriParser.parse(
            "otpauth://totp/Google:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Google",
        ).getOrThrow()

        assertEquals("Google", account.issuer)
        assertEquals("user@example.com", account.label)
        assertEquals("JBSWY3DPEHPK3PXP", account.secret)
        assertEquals(6, account.digits)
        assertEquals(30, account.period)
        assertEquals(TotpAlgorithm.SHA1, account.algorithm)
        assertEquals("Google (user@example.com)", account.displayName)
    }

    @Test
    fun parseIssuerFromPath() {
        val account = OtpAuthUriParser.parse(
            "otpauth://totp/AWS:admin?secret=ABCDEFGH&digits=8&period=60",
        ).getOrThrow()

        assertEquals("AWS", account.issuer)
        assertEquals("admin", account.label)
        assertEquals(8, account.digits)
        assertEquals(60, account.period)
    }

    /** GitHub and others percent-encode the issuer prefix and pad the label with a space. */
    @Test
    fun parsePercentEncodedLabel()  {
        val account = OtpAuthUriParser.parse(
            "otpauth://totp/GitHub%3A%20gi-os?secret=JBSWY3DPEHPK3PXP&issuer=GitHub",
        ).getOrThrow()

        assertEquals("GitHub", account.issuer)
        assertEquals("gi-os", account.label)
    }

    /** A '+' in a path is a literal plus, not a space — Gmail-style labels rely on it. */
    @Test
    fun keepsLiteralPlusInLabel() {
        val account = OtpAuthUriParser.parse(
            "otpauth://totp/Example:user+2fa@example.com?secret=JBSWY3DPEHPK3PXP",
        ).getOrThrow()

        assertEquals("user+2fa@example.com", account.label)
    }

    @Test
    fun parseAlgorithmParam() {
        val account = OtpAuthUriParser.parse(
            "otpauth://totp/Example?secret=ABCDEFGH&algorithm=SHA256",
        ).getOrThrow()

        assertEquals(TotpAlgorithm.SHA256, account.algorithm)
    }

    @Test
    fun rejectsNonOtpauthUri() {
        assertTrue(OtpAuthUriParser.parse("https://example.com").isFailure)
    }

    @Test
    fun rejectsHotpAndJunk() {
        assertTrue(OtpAuthUriParser.parse("otpauth://hotp/Example?secret=ABCDEFGH&counter=1").isFailure)
        assertTrue(OtpAuthUriParser.parse("").isFailure)
        assertTrue(OtpAuthUriParser.parse("not a uri at all").isFailure)
    }

    @Test
    fun rejectsMissingOrUndecodableSecret() {
        assertTrue(OtpAuthUriParser.parse("otpauth://totp/Example?issuer=Example").isFailure)
        // 1 and 8 are not in the base32 alphabet.
        assertTrue(OtpAuthUriParser.parse("otpauth://totp/Example?secret=18181818").isFailure)
    }

    @Test
    fun rejectsNonPositiveDigitsOrPeriod() {
        assertTrue(OtpAuthUriParser.parse("otpauth://totp/E?secret=ABCDEFGH&digits=0").isFailure)
        assertTrue(OtpAuthUriParser.parse("otpauth://totp/E?secret=ABCDEFGH&period=0").isFailure)
    }
}
