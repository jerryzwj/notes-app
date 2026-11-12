package eu.wenjun.notes;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * 令牌加密落盘：AES256-GCM 密钥放在 AndroidKeyStore 里，localStorage 里的明文只当缓存用。
 * 后台备份的 Worker 也走这里读令牌。
 */
public final class SecureStore {

    private static final String FILE = "notes_secure";
    private static SharedPreferences cached;

    private SecureStore() {}

    private static synchronized SharedPreferences prefs(Context ctx) throws Exception {
        if (cached != null) return cached;
        MasterKey key = new MasterKey.Builder(ctx.getApplicationContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        cached = EncryptedSharedPreferences.create(
                ctx.getApplicationContext(),
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
        return cached;
    }

    public static void set(Context ctx, String key, String value) throws Exception {
        prefs(ctx).edit().putString(key, value).apply();
    }

    public static String get(Context ctx, String key) {
        try {
            return prefs(ctx).getString(key, null);
        } catch (Exception e) {
            return null;
        }
    }

    public static void remove(Context ctx, String key) {
        try {
            prefs(ctx).edit().remove(key).apply();
        } catch (Exception ignored) {
        }
    }
}
