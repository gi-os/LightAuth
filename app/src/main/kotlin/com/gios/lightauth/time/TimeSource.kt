package com.gios.lightauth.time

import android.content.Context

/**
 * The one clock the whole app reads.
 *
 * Everything used to call [System.currentTimeMillis] inline, which meant a phone with a
 * drifted clock had no way back: the codes were right for the time the phone believed in,
 * and the app could neither show that nor correct it. The correction is a plain signed
 * second count, held in prefs — not in the encrypted database, because it is not a secret
 * and because a clock this app cannot read is exactly the situation where the database
 * might not open.
 *
 * Deliberately no SNTP: fetching real time needs the INTERNET permission, and an
 * authenticator that holds every one of your 2FA secrets and can also open a socket is a
 * worse trade than typing an offset in by hand once.
 */
object TimeSource {

    private const val PREFS_NAME = "lightauth_clock"
    private const val KEY_OFFSET = "offset_seconds"

    @Volatile
    private var offset: Int = 0

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    /** Called once from the activity, before the first code screen can be composed. */
    fun init(context: Context) {
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        offset = TimeMath.clampOffset(store.getInt(KEY_OFFSET, 0))
    }

    /** The correction currently applied, in seconds. */
    fun offsetSeconds(): Int = offset

    fun setOffsetSeconds(seconds: Int) {
        val clamped = TimeMath.clampOffset(seconds)
        offset = clamped
        prefs?.edit()?.putInt(KEY_OFFSET, clamped)?.apply()
    }

    /** What the phone thinks the time is, uncorrected. Shown next to the corrected clock. */
    fun deviceSeconds(): Long = System.currentTimeMillis() / 1000

    /** What TOTP should count in. */
    fun nowSeconds(): Long = TimeMath.corrected(deviceSeconds(), offset)
}
