package com.gios.lightauth.vault

import com.gios.lightauth.crypto.TotpKeystore
import javax.crypto.SecretKey

/**
 * The outer wrap's key — in production, the non-exportable AndroidKeyStore key.
 *
 * This is an interface for one reason: AndroidKeyStore does not exist off-device, so without a
 * seam here the entire lock/unlock state machine could only be tested by hand on the phone.
 * Given what it guards, "tested by hand" is not good enough. The default is the real keystore
 * and nothing in the app ever replaces it; [Vault.deviceKeyForTest] is the only other setter and
 * it exists for the JVM tests.
 */
internal interface DeviceKey {
    fun ensure()
    fun key(): SecretKey
}

internal class KeystoreDeviceKey(private val keystore: TotpKeystore = TotpKeystore()) : DeviceKey {
    override fun ensure() = keystore.ensureKey()
    override fun key(): SecretKey = keystore.getSecretKey()
}
