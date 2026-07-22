package com.vela.android.lab.data.market.source.alpaca

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android Keystore-backed implementation of
 * [SecureAlpacaCredentialsStore].
 *
 * Implementation notes:
 *
 *  - Uses `EncryptedSharedPreferences` from `androidx.security:security-crypto`.
 *  - Master key is generated with `MasterKey.KeyScheme.AES256_GCM`
 *    and is sealed by the Android Keystore. On devices with a
 *    Strongbox-capable keymaster, the key never leaves secure
 *    hardware.
 *  - The prefs file (`vela_alpaca_credentials`) uses
 *    `AES256_SIV` for key encryption and `AES256_GCM` for value
 *    encryption.
 *  - All disk I/O hops to `Dispatchers.IO` so the suspend
 *    contract on [SecureAlpacaCredentialsStore] is honored.
 *
 * The class never logs, prints, or otherwise reflects credential
 * values back to the caller as a side-effect.
 */
class EncryptedPrefsAlpacaCredentialsStore(
    context: Context,
) : SecureAlpacaCredentialsStore {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences by lazy { buildPrefs() }

    private fun buildPrefs(): SharedPreferences {
        val masterKey: MasterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun save(credentials: AlpacaCredentials) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_ID, credentials.keyId)
            .putString(SECRET, credentials.secret)
            .apply()
    }

    override suspend fun load(): AlpacaCredentials? = withContext(Dispatchers.IO) {
        val keyId = prefs.getString(KEY_ID, null)?.trim().orEmpty()
        val secret = prefs.getString(SECRET, null)?.trim().orEmpty()
        if (keyId.isEmpty() || secret.isEmpty()) null
        else AlpacaCredentials(keyId, secret)
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_ID).remove(SECRET).apply()
    }

    override suspend fun hasCredentials(): Boolean = withContext(Dispatchers.IO) {
        val keyId = prefs.getString(KEY_ID, null)?.trim().orEmpty()
        val secret = prefs.getString(SECRET, null)?.trim().orEmpty()
        keyId.isNotEmpty() && secret.isNotEmpty()
    }

    private companion object {
        const val PREFS_FILE = "vela_alpaca_credentials"
        const val MASTER_KEY_ALIAS = "vela_alpaca_master_key"
        const val KEY_ID = "alpaca.key_id"
        const val SECRET = "alpaca.secret"
    }
}
