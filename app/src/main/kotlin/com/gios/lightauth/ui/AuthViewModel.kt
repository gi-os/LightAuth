package com.gios.lightauth.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightauth.backup.SnapshotStore
import com.gios.lightauth.data.StoredAccount
import com.gios.lightauth.data.TotpAccountRepository
import com.gios.lightauth.totp.OtpAuthUriParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the account list and the code screen need. Holds no plaintext secrets. */
class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TotpAccountRepository.getInstance(app)
    private val appContext = app.applicationContext

    val accounts: StateFlow<List<StoredAccount>> = repo.observeAccounts()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    /**
     * Stores a scanned `otpauth://` URI. [onAdded] runs on the main thread with the new
     * row's id so the caller can navigate straight to the code.
     */
    fun addFromQr(raw: String, onAdded: (Long) -> Unit) {
        viewModelScope.launch {
            val parsed = OtpAuthUriParser.parse(raw)
            val account = parsed.getOrElse { error ->
                _error.value = error.message ?: "Invalid QR Code"
                return@launch
            }
            val stored = runCatching {
                withContext(Dispatchers.IO) { repo.addAccount(account) }
            }.getOrElse { error ->
                _error.value = error.message ?: "Could not save this account"
                return@launch
            }
            refreshBackupSnapshot()
            onAdded(stored.id)
        }
    }

    /**
     * Rewrites the backup snapshot from the current accounts.
     *
     * Has to happen on every change, because a locked app cannot build one on demand — see
     * [SnapshotStore]. Fire-and-forget on IO: a snapshot one edit stale is a small loss, and
     * blocking the edit on a PBKDF2 would be a visible one.
     */
    private fun refreshBackupSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SnapshotStore.refresh(appContext) }
        }
    }

    /**
     * Called once the vault opens: applies anything LightSync restored while locked, then
     * writes a fresh snapshot. The PIN is needed to open a sealed restore payload and is not
     * retained past this call.
     */
    fun onVaultOpened(pin: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SnapshotStore.ingestPending(appContext, pin) }
            runCatching { SnapshotStore.refresh(appContext) }
        }
    }

    suspend fun loadAccount(id: Long): StoredAccount? =
        withContext(Dispatchers.IO) { repo.getAccount(id) }

    /**
     * The decrypted base32 secret. Kept out of any StateFlow deliberately: it is read
     * on entering the code screen and lives only in that composition.
     */
    suspend fun loadSecret(id: Long): String? =
        withContext(Dispatchers.IO) { runCatching { repo.decryptSecret(id) }.getOrNull() }

    fun remove(id: Long, onRemoved: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteAccount(id) }
            refreshBackupSnapshot()
            onRemoved()
        }
    }
}
