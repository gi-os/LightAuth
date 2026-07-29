package com.gios.lightauth.totp

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A scanned account, before it has been stored. */
data class Account(
    val issuer: String,
    val label: String,
    val secret: String,
    val digits: Int,
    val period: Int,
    val algorithm: String = TotpAlgorithm.SHA1,
) {
    val displayName: String
        get() = when {
            issuer.isNotBlank() && label.isNotBlank() -> "$issuer ($label)"
            issuer.isNotBlank() -> issuer
            label.isNotBlank() -> label
            else -> "Unknown"
        }
}

/** Parses the `otpauth://totp/...` key URI that every provider's QR code encodes. */
object OtpAuthUriParser {
    fun parse(uriString: String): Result<Account> {
        val trimmed = uriString.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("QR code is empty"))
        }

        val uri = try {
            URI(trimmed)
        } catch (error: Exception) {
            return Result.failure(IllegalArgumentException("Invalid QR Code"))
        }
        if (uri.scheme?.equals("otpauth", ignoreCase = true) != true) {
            return Result.failure(IllegalArgumentException("Invalid QR Code"))
        }

        val type = uri.host?.lowercase()
        if (type != "totp") {
            return Result.failure(IllegalArgumentException("Only TOTP accounts are supported"))
        }

        val query = parseQuery(uri.rawQuery)
        val secret = query["secret"]?.trim()
            ?: return Result.failure(IllegalArgumentException("Missing secret"))

        // Fail here rather than on the code screen: an unparseable secret shown as a
        // stored account is a code that silently never works.
        runCatching { Base32.decode(secret) }
            .onFailure { return Result.failure(IllegalArgumentException("Invalid secret")) }
            .onSuccess { if (it.isEmpty()) return Result.failure(IllegalArgumentException("Missing secret")) }

        val pathLabel = decodePathLabel(uri.rawPath)
        val issuerParam = query["issuer"]?.trim().orEmpty()
        val (pathIssuer, label) = splitIssuerAndLabel(pathLabel)

        val issuer = issuerParam.ifBlank { pathIssuer }
        val digits = query["digits"]?.toIntOrNull() ?: 6
        val period = query["period"]?.toIntOrNull() ?: 30
        val algorithm = runCatching { TotpAlgorithm.normalize(query["algorithm"]) }
            .getOrElse { return Result.failure(it) }

        if (digits <= 0) {
            return Result.failure(IllegalArgumentException("Invalid digits: $digits"))
        }
        if (period <= 0) {
            return Result.failure(IllegalArgumentException("Invalid period: $period"))
        }

        return Result.success(
            Account(
                issuer = issuer,
                label = label,
                secret = secret,
                digits = digits,
                period = period,
                algorithm = algorithm,
            ),
        )
    }

    /**
     * Decoded from the *raw* path, exactly once.
     *
     * `URI.getPath()` has already un-escaped the percent triplets, so decoding that
     * again would eat a literal '%' in a label. And URLDecoder is a form decoder, which
     * turns '+' into a space — wrong for a path, and Gmail-style labels really do
     * contain '+' — so escape any literal plus before handing it over.
     */
    private fun decodePathLabel(rawPath: String?): String {
        val raw = rawPath?.removePrefix("/").orEmpty()
        if (raw.isEmpty()) return ""
        return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }

    private fun splitIssuerAndLabel(pathLabel: String): Pair<String, String> {
        val colonIndex = pathLabel.indexOf(':')
        if (colonIndex < 0) {
            return "" to pathLabel
        }
        return pathLabel.substring(0, colonIndex) to pathLabel.substring(colonIndex + 1).trim()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val equalsIndex = part.indexOf('=')
            if (equalsIndex < 0) return@mapNotNull null
            val key = URLDecoder.decode(part.substring(0, equalsIndex), StandardCharsets.UTF_8.name())
            val value = URLDecoder.decode(part.substring(equalsIndex + 1), StandardCharsets.UTF_8.name())
            key to value
        }.toMap()
    }
}
