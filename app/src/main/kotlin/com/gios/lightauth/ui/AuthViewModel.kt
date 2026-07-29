package com.gios.lightauth.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
            onAdded(stored.id)
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
            onRemoved()
        }
    }
}
