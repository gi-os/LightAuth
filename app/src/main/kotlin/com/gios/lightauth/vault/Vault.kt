package com.gios.lightauth.vault

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey

/** What the UI is allowed to know about the vault without holding the key. */
enum class VaultState {
    /** No PIN configured. The vault key is available and the app opens straight up. */
    Open,

    /** A PIN is set and has not been entered this foregrounding. No secret is readable. */
    Locked,

    /** A PIN is set and has been entered. The key is in memory only. */
    Unlocked,
}

/**
 * The vault key's whole life, and the only thing that hands it out.
 *
 * The key is wrapped by the PIN *and* by the non-exportable AndroidKeyStore key — see
 * [VaultCrypto] for why both. Wrapping order is PIN on the inside, KeyStore on the outside,
 * so what sits in `shared_prefs` is inert off the device and still PIN-locked on it.
 *
 * When no PIN is set the KeyStore layer is the only one, which is exactly the protection the
 * app had before this existed: the database is useless without the phone. Setting a PIN adds
 * the second layer to the same key, so no account has to be re-encrypted to turn the PIN on
 * or off.
 *
 * The unwrapped key is held in a plain field, cleared on [lock]. It is never written
 * anywhere, never put in a StateFlow, and never survives the app leaving the foreground.
 */
object Vault {

    private const val PREFS_NAME = "lightauth_vault"

    private const val KEY_WRAPPED = "wrapped_vault_key"
    private const val KEY_PIN_SET = "pin_set"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_ITERATIONS = "pin_iterations"
    private const val KEY_PIN_LENGTH = "pin_length"
    private const val KEY_FAILURES = "failed_attempts"
    private const val KEY_WIPE_ENABLED = "wipe_enabled"
    private const val KEY_WIPE_AFTER = "wipe_after"
    private const val KEY_MIGRATED = "vault_version"
    private const val KEY_BACKUP_SALT = "backup_salt"

    const val DEFAULT_WIPE_AFTER = 10
    const val CURRENT_VAULT_VERSION = 1

    private lateinit var prefs: SharedPreferences

    /**
     * The outer wrap. Always the AndroidKeyStore key in the app; see [DeviceKey] for why it
     * is replaceable at all.
     */
    private var deviceKey: DeviceKey = KeystoreDeviceKey()

    /** JVM tests only — AndroidKeyStore has no off-device implementation to test against. */
    internal fun deviceKeyForTest(replacement: DeviceKey) {
        deviceKey = replacement
    }

    /** Cleared the moment the app leaves the foreground. */
    @Volatile
    private var vaultKey: SecretKey? = null

    /**
     * PIN-derived, for sealing the backup snapshot. Also cleared on lock.
     *
     * Held instead of the PIN itself. The snapshot has to be re-sealed whenever an account
     * changes, which would otherwise mean keeping the digits in memory for the whole session;
     * a derived key does the same job and cannot be typed into anything.
     *
     * Its salt is persistent and separate from the vault-key salt, so a restored snapshot is
     * openable with the PIN alone on a phone whose AndroidKeyStore is a different keystore.
     */
    @Volatile
    private var backupKey: SecretKey? = null

    /**
     * Deadline for the current lockout, on the monotonic clock.
     *
     * [SystemClock.elapsedRealtime] rather than wall time on purpose: wall time is
     * attacker-settable, and the phone's clock is — as the clock screen exists to prove —
     * not always right anyway. A reboot clears the deadline, but the failure *count* is
     * persisted, so the next wrong PIN after a reboot lands further down the schedule
     * instead of starting over.
     */
    @Volatile
    private var lockoutUntil: Long = 0

    private val _state = MutableStateFlow(VaultState.Locked)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** True once [init] has run. Guards the repository against a cold-start race. */
    @Volatile
    var ready: Boolean = false
        private set

    // ---------------------------------------------------------------- setup

    /**
     * Loads or creates the wrapped key. Must run before anything asks for a secret.
     *
     * With no PIN set this also unwraps immediately, so the very first launch after
     * upgrading behaves exactly as before: the app opens, and migration can re-encrypt the
     * existing rows without anyone typing anything.
     */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceKey.ensure()

        if (!prefs.contains(KEY_WRAPPED)) {
            // First run on this version: mint a vault key and wrap it with the device key
            // only. No PIN yet, so there is no second layer to add.
            val fresh = VaultCrypto.newVaultKey()
            storeWrapped(fresh, pin = null)
            vaultKey = VaultCrypto.keyFromBytes(fresh)
            fresh.fill(0)
            _state.value = VaultState.Open
        } else if (!isPinSet()) {
            vaultKey = unwrap(pin = null)
            _state.value = if (vaultKey != null) VaultState.Open else VaultState.Locked
        } else {
            vaultKey = null
            _state.value = VaultState.Locked
        }
        ready = true
    }

    fun isPinSet(): Boolean = prefs.getBoolean(KEY_PIN_SET, false)

    /** Only so the keypad knows how many dots to draw. Not a secret. */
    fun pinLength(): Int = prefs.getInt(KEY_PIN_LENGTH, VaultCrypto.MIN_PIN_LENGTH)

    fun isWipeEnabled(): Boolean = prefs.getBoolean(KEY_WIPE_ENABLED, false)

    fun wipeAfter(): Int = prefs.getInt(KEY_WIPE_AFTER, DEFAULT_WIPE_AFTER)

    fun setWipeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIPE_ENABLED, enabled).apply()
    }

    fun setWipeAfter(attempts: Int) {
        prefs.edit().putInt(KEY_WIPE_AFTER, attempts.coerceIn(3, 50)).apply()
    }

    fun failedAttempts(): Int = prefs.getInt(KEY_FAILURES, 0)

    // ---------------------------------------------------------------- the key

    /**
     * The key, or null while locked.
     *
     * Callers must treat null as "not now" rather than an error: a background LightSync
     * export can legitimately arrive while the vault is shut, and the right answer then is
     * to export nothing rather than to prompt or to weaken the wrap.
     */
    fun key(): SecretKey? = vaultKey

    /** The key the backup snapshot is sealed with, or null when no PIN is set. */
    fun backupSealKey(): SecretKey? = backupKey

    fun backupSalt(): ByteArray? = prefs.getString(KEY_BACKUP_SALT, null)?.fromBase64()

    /**
     * Drops the key. A no-op when no PIN is set — and that early return is not a shortcut.
     *
     * This is called from `onStop`, so without it a phone with no PIN would lose its key the
     * first time the screen slept and then have no way to get it back: nothing would ask for a
     * PIN, because there is none, and every code screen would read "secret could not be read"
     * until the app was force-restarted. Caught by a test, not by using it.
     */
    fun lock() {
        if (!isPinSet()) return
        vaultKey = null
        backupKey = null
        _state.value = VaultState.Locked
    }

    // ---------------------------------------------------------------- unlocking

    sealed interface UnlockResult {
        data object Success : UnlockResult
        data class Wrong(val attemptsUsed: Int, val waitSeconds: Long) : UnlockResult
        data class Throttled(val waitSeconds: Long) : UnlockResult
        data object Wiped : UnlockResult
    }

    /** Seconds still to wait, or 0. */
    fun remainingLockoutSeconds(): Long {
        val left = lockoutUntil - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else (left + 999) / 1000
    }

    fun unlock(pin: String): UnlockResult {
        val waiting = remainingLockoutSeconds()
        if (waiting > 0) return UnlockResult.Throttled(waiting)

        val opened = unwrap(pin)
        if (opened != null) {
            vaultKey = opened
            backupKey = deriveBackupKey(pin)
            prefs.edit().putInt(KEY_FAILURES, 0).apply()
            lockoutUntil = 0
            _state.value = VaultState.Unlocked
            return UnlockResult.Success
        }

        val failures = failedAttempts() + 1
        prefs.edit().putInt(KEY_FAILURES, failures).apply()

        if (isWipeEnabled() && failures >= wipeAfter()) {
            wipe()
            return UnlockResult.Wiped
        }

        val wait = Lockout.delaySecondsAfter(failures)
        if (wait > 0) lockoutUntil = SystemClock.elapsedRealtime() + wait * 1000
        return UnlockResult.Wrong(failures, wait)
    }

    // ---------------------------------------------------------------- changing the PIN

    /**
     * Turns the PIN on, or changes it. Requires the vault to be open already, which is what
     * makes "change PIN" safe: the old PIN had to unlock it first, so a stolen unlocked
     * phone is the only way to reach here, and that is a threat a PIN cannot fix anyway.
     */
    fun setPin(newPin: String): Boolean {
        if (!VaultCrypto.isValidPin(newPin)) return false
        val raw = rawKeyOrNull() ?: return false
        // A new PIN gets a new snapshot salt. Old backups keep theirs inside the payload.
        prefs.edit().remove(KEY_BACKUP_SALT).apply()
        storeWrapped(raw, newPin)
        raw.fill(0)
        backupKey = deriveBackupKey(newPin)
        prefs.edit()
            .putBoolean(KEY_PIN_SET, true)
            .putInt(KEY_PIN_LENGTH, newPin.length)
            .putInt(KEY_FAILURES, 0)
            .apply()
        lockoutUntil = 0
        _state.value = VaultState.Unlocked
        return true
    }

    /** Drops the PIN layer, leaving the device-key wrap the app has always had. */
    fun clearPin(): Boolean {
        val raw = rawKeyOrNull() ?: return false
        storeWrapped(raw, pin = null)
        raw.fill(0)
        backupKey = null
        prefs.edit()
            .putBoolean(KEY_PIN_SET, false)
            .remove(KEY_SALT)
            .remove(KEY_PIN_LENGTH)
            .putInt(KEY_FAILURES, 0)
            .apply()
        lockoutUntil = 0
        _state.value = VaultState.Open
        return true
    }

    /** Confirms a PIN without changing the unlock state — used before letting it be changed. */
    fun verifyPin(pin: String): Boolean = unwrap(pin) != null

    // ---------------------------------------------------------------- wipe

    /**
     * Destroys the key, which destroys the secrets, because every one of them is encrypted
     * under it. The rows are dropped too, but the key going is what makes it irreversible —
     * there is no copy anywhere, so nothing can be walked back later.
     */
    fun wipe() {
        vaultKey = null
        backupKey = null
        prefs.edit().clear().apply()
        onWipe?.invoke()
        // A fresh empty vault, so the app is usable rather than bricked.
        val fresh = VaultCrypto.newVaultKey()
        storeWrapped(fresh, pin = null)
        vaultKey = VaultCrypto.keyFromBytes(fresh)
        fresh.fill(0)
        _state.value = VaultState.Open
    }

    /** Set by the repository, so the vault can drop the rows without depending on Room. */
    @Volatile
    var onWipe: (() -> Unit)? = null

    // ---------------------------------------------------------------- migration

    fun vaultVersion(): Int = prefs.getInt(KEY_MIGRATED, 0)

    fun markMigrated() {
        prefs.edit().putInt(KEY_MIGRATED, CURRENT_VAULT_VERSION).apply()
    }

    // ---------------------------------------------------------------- wrapping

    /**
     * KeyStore on the outside, PIN on the inside.
     *
     * That order is the point. The outer layer means the blob is inert once copied off the
     * phone, so the four digits are never exposed to an offline attack; the inner layer
     * means that on the phone, holding the KeyStore key is not enough.
     */
    private fun storeWrapped(rawVaultKey: ByteArray, pin: String?) {
        val inner = if (pin == null) {
            rawVaultKey
        } else {
            val salt = VaultCrypto.newSalt()
            prefs.edit()
                .putString(KEY_SALT, salt.toBase64())
                .putInt(KEY_ITERATIONS, VaultCrypto.ITERATIONS)
                .apply()
            VaultCrypto.seal(VaultCrypto.deriveKey(pin, salt), rawVaultKey)
        }
        val outer = VaultCrypto.seal(deviceKey.key(), inner)
        prefs.edit().putString(KEY_WRAPPED, outer.toBase64()).apply()
    }

    private fun unwrap(pin: String?): SecretKey? {
        val raw = unwrapRaw(pin) ?: return null
        val key = VaultCrypto.keyFromBytes(raw)
        raw.fill(0)
        return key
    }

    private fun unwrapRaw(pin: String?): ByteArray? {
        val stored = prefs.getString(KEY_WRAPPED, null)?.fromBase64() ?: return null
        val inner = VaultCrypto.open(deviceKey.key(), stored) ?: return null
        if (!isPinSet()) return inner
        if (pin == null) return null
        val salt = prefs.getString(KEY_SALT, null)?.fromBase64() ?: return null
        val iterations = prefs.getInt(KEY_ITERATIONS, VaultCrypto.ITERATIONS)
        return VaultCrypto.open(VaultCrypto.deriveKey(pin, salt, iterations), inner)
    }

    /**
     * The snapshot-sealing key for this PIN, minting the salt on first use.
     *
     * A fresh salt each time the PIN is set means an old backup is still openable with the old
     * PIN — [Snapshot] writes its salt into the payload, so nothing depends on the current one.
     */
    private fun deriveBackupKey(pin: String): SecretKey {
        val salt = backupSalt() ?: VaultCrypto.newSalt().also {
            prefs.edit().putString(KEY_BACKUP_SALT, it.toBase64()).apply()
        }
        return VaultCrypto.deriveKey(pin, salt)
    }

    /** The unwrapped key material, for re-wrapping under a new PIN. Only while unlocked. */
    private fun rawKeyOrNull(): ByteArray? = vaultKey?.encoded

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray? =
        runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrNull()
}
