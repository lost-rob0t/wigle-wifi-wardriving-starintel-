package net.wigle.wigleandroid.starintel;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import net.wigle.wigleandroid.util.Logging;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the StarIntel API credential behind Android Keystore.
 * Only ciphertext and IV are persisted in SharedPreferences.
 */
public final class StarIntelCredentialStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "wigle_starintel_api_key_v1";
    private static final String PREFS = "StarIntelSecure";
    private static final String TOKEN = "token";
    private static final String IV = "iv";

    private final Context context;

    public StarIntelCredentialStore(final Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void saveToken(final String value) {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (value == null || value.trim().isEmpty()) {
            prefs.edit().remove(TOKEN).remove(IV).apply();
            return;
        }
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            final byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                    .putString(TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception ex) {
            Logging.error("Unable to store StarIntel credential in Android Keystore", ex);
            throw new IllegalStateException("Unable to store StarIntel credential", ex);
        }
    }

    public synchronized String loadToken() {
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String encryptedValue = prefs.getString(TOKEN, null);
        final String ivValue = prefs.getString(IV, null);
        if (encryptedValue == null || ivValue == null) {
            return "";
        }
        try {
            final KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            final SecretKey key = (SecretKey) store.getKey(ALIAS, null);
            if (key == null) {
                return "";
            }
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP))
            );
            return new String(
                    cipher.doFinal(Base64.decode(encryptedValue, Base64.NO_WRAP)),
                    StandardCharsets.UTF_8
            );
        } catch (Exception ex) {
            Logging.error("Unable to read StarIntel credential from Android Keystore", ex);
            return "";
        }
    }

    public boolean hasToken() {
        return !loadToken().isEmpty();
    }

    private SecretKey getOrCreateKey() throws Exception {
        final KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        final SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) {
            return existing;
        }

        final KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(
                new KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
        );
        return generator.generateKey();
    }
}
