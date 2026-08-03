package com.gios.lightauth.time

/**
 * Clock arithmetic, kept free of Android imports so it unit-tests directly.
 *
 * A TOTP code is a pure function of the clock, so a phone whose clock has drifted shows
 * codes that are perfectly valid — for the wrong thirty-second window. Every site then
 * rejects every code, and nothing on screen says why. These helpers back the clock screen
 * that makes the drift visible and correctable.
 */
object TimeMath {

    /** Beyond this the offset is almost certainly a mistake rather than a real correction. */
    const val MAX_OFFSET_SECONDS = 24 * 60 * 60

    fun clampOffset(seconds: Int): Int = seconds.coerceIn(-MAX_OFFSET_SECONDS, MAX_OFFSET_SECONDS)

    /** The clock TOTP should actually use: the phone's idea of now, plus the correction. */
    fun corrected(deviceSeconds: Long, offsetSeconds: Int): Long = deviceSeconds + offsetSeconds

    /**
     * Providers accept the code either side of the current window, so a phone inside one
     * period is still usable. Past that, every code is refused.
     */
    fun isDriftHarmless(offsetSeconds: Int, period: Int): Boolean =
        offsetSeconds > -period && offsetSeconds < period

    /** Signed, always with a sign, because "0s" and "+0s" mean different things here. */
    fun formatOffset(seconds: Int): String {
        val sign = if (seconds < 0) "-" else "+"
        val magnitude = Math.abs(seconds.toLong())
        return when {
            magnitude < 60 -> "$sign${magnitude}s"
            magnitude < 3600 -> "$sign${magnitude / 60}m ${magnitude % 60}s"
            else -> "$sign${magnitude / 3600}h ${(magnitude % 3600) / 60}m"
        }
    }

    /**
     * HH:MM:SS in UTC, by arithmetic rather than a formatter.
     *
     * UTC on purpose: it is what TOTP counts in, and it is what time.is shows next to a
     * countdown, so the two can be read side by side without a timezone in the way.
     */
    fun formatUtcClock(unixSeconds: Long): String {
        val dayTime = Math.floorMod(unixSeconds, 86_400L)
        val hours = dayTime / 3600
        val minutes = (dayTime % 3600) / 60
        val seconds = dayTime % 60
        return "${pad(hours)}:${pad(minutes)}:${pad(seconds)}"
    }

    private fun pad(value: Long): String = value.toString().padStart(2, '0')
}
