package dev.gpxit.app.data.komoot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Komoot credentials decrypted in-memory. Pass through Basic auth and
 * drop the reference as soon as the request is built.
 *
 * `userId` is the numeric Komoot user ID (visible in the URL of the
 * user's own Komoot profile page, e.g. `komoot.com/user/12345678`).
 * Optional: only required for the "Browse my Komoot tours" picker —
 * URL / share-link imports work with email+password alone.
 */
data class KomootCredentials(
    val email: String,
    val password: String,
    val userId: String? = null,
) {
    /** RFC 7617 Basic-auth header value (`Basic base64(email:password)`). */
    fun toBasicAuthHeader(): String {
        val raw = "$email:$password".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}

private val Context.komootDataStore by preferencesDataStore(name = "gpxit_komoot")

/**
 * Encrypts Komoot email + password with AES-256-GCM, with the key
 * locked inside the Android Keystore (alias [KEY_ALIAS]).
 *
 * The Keystore-backed key never leaves the TEE on devices that have
 * one; on devices without secure hardware Android falls back to a
 * software-protected key, which is still strictly better than plain
 * DataStore.
 *
 * On-disk layout (base64 of `iv || ciphertext || gcmTag`):
 *   - 12-byte IV (random per write — required for GCM)
 *   - ciphertext
 *   - 16-byte GCM tag (appended by the cipher)
 */
class KomootCredentialStore(private val context: Context) {

    suspend fun load(): KomootCredentials? = withContext(Dispatchers.IO) {
        val prefs = context.komootDataStore.data.first()
        val emailEnc = prefs[KEY_EMAIL_ENC] ?: return@withContext null
        val passwordEnc = prefs[KEY_PASSWORD_ENC] ?: return@withContext null
        try {
            val email = decrypt(emailEnc) ?: return@withContext null
            val password = decrypt(passwordEnc) ?: return@withContext null
            // userId stored plaintext: it's already public on the user's
            // Komoot profile URL and is needed even when decryption of
            // the password somehow lags. Treat it as low-sensitivity.
            val userId = prefs[KEY_USER_ID]?.takeIf { it.isNotBlank() }
            KomootCredentials(email, password, userId)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun save(email: String, password: String, userId: String? = null) =
        withContext(Dispatchers.IO) {
            val emailEnc = encrypt(email)
            val passwordEnc = encrypt(password)
            context.komootDataStore.edit { prefs ->
                prefs[KEY_EMAIL_ENC] = emailEnc
                prefs[KEY_PASSWORD_ENC] = passwordEnc
                if (userId.isNullOrBlank()) {
                    prefs.remove(KEY_USER_ID)
                } else {
                    prefs[KEY_USER_ID] = userId.trim()
                }
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        context.komootDataStore.edit { prefs ->
            prefs.remove(KEY_EMAIL_ENC)
            prefs.remove(KEY_PASSWORD_ENC)
            prefs.remove(KEY_USER_ID)
        }
    }

    /** Cheap presence check — does NOT decrypt. */
    fun isConfiguredFlow(): Flow<Boolean> = context.komootDataStore.data.map {
        it[KEY_EMAIL_ENC] != null && it[KEY_PASSWORD_ENC] != null
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ct.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ct, 0, it, iv.size, ct.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size < IV_LEN + 1) return null
        val iv = combined.copyOfRange(0, IV_LEN)
        val ct = combined.copyOfRange(IV_LEN, combined.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "gpxit_komoot_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val GCM_TAG_BITS = 128

        val KEY_EMAIL_ENC = stringPreferencesKey("email_enc")
        val KEY_PASSWORD_ENC = stringPreferencesKey("password_enc")
        val KEY_USER_ID = stringPreferencesKey("user_id")
    }
}
