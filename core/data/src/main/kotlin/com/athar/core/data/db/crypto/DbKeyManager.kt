package com.athar.core.data.db.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.athar.core.data.db.AtharDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the SQLCipher database key.
 *
 * The raw 32-byte DB key is stored on the device file system, wrapped with an
 * AndroidKeyStore-resident AES-256-GCM master key that never leaves secure hardware
 * (where available). On every app launch we unwrap to get the DB key, hand it to
 * SQLCipher, and let it open the database.
 *
 *  Wire format of the wrapped key on disk (`files/db_key.bin`):
 *
 *      [iv 12B] [ciphertext 32B] [tag 16B inside ciphertext per GCM]
 *
 * Creation only happens on a genuinely empty installation. If database artifacts
 * exist while `db_key.bin` is missing, [RetainedDataPreflight] fails closed before
 * any key material is generated or written (Master Brief §5.3 / WM028-13B0).
 *
 * Master Brief §5.3 / ADR-003.
 */
@Singleton
class DbKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun getOrCreateDbKey(): ByteArray {
        val file = File(context.filesDir, KEY_FILE_NAME)
        if (file.exists()) {
            return unwrap(file.readBytes())
        }
        // WM028-13B0: a missing wrapped key next to retained database artifacts
        // means the original key material is gone. Minting a replacement would
        // make every retained file permanently unreadable, so fail closed BEFORE
        // generating, wrapping or writing anything; recovery is an explicit later
        // operation. The existing-key path above keeps failing unchanged (invalid
        // key or lost Keystore key never regenerates or resets anything).
        RetainedDataPreflight.requireNoRetainedDatabaseArtifacts(
            databaseFile = context.getDatabasePath(AtharDatabase.NAME),
        )
        ensureMasterKey()
        val rawDbKey = ByteArray(DB_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val wrapped = wrap(rawDbKey)
        file.writeBytes(wrapped)
        return rawDbKey
    }

    private fun ensureMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(MASTER_ALIAS)) return

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(MASTER_KEY_BITS)
                .build(),
        )
        generator.generateKey()
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(MASTER_ALIAS, null) as SecretKey
    }

    private fun wrap(rawDbKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(rawDbKey)
        return iv + ciphertext
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > IV_BYTES) { "wrapped key too small" }
        val iv = wrapped.copyOfRange(0, IV_BYTES)
        val ciphertext = wrapped.copyOfRange(IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_ALIAS = "athar_master_v1"
        private const val MASTER_KEY_BITS = 256
        private const val DB_KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val KEY_FILE_NAME = "db_key.bin"
    }
}
