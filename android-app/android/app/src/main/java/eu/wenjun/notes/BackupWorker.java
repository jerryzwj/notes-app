package eu.wenjun.notes;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 每日后台备份：拿加密存储里的令牌去 /api/export，落到 App 私有目录 backups/，只留最近 30 份。
 */
public class BackupWorker extends Worker {

    public static final String PREFS = "notes_backup";
    public static final String KEY_TIME = "lastTime";
    public static final String KEY_STATUS = "lastStatus";
    public static final String KEY_COUNT = "lastCount";
    private static final String BASE = "https://note.wenjun.eu.org";
    private static final int KEEP = 30;

    public BackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        String token = SecureStore.get(ctx, "token");
        if (token == null || token.isEmpty()) {
            sp.edit().putString(KEY_STATUS, "未登录，跳过本次备份").apply();
            return Result.success();
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + "/api/export").openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) notes-backup");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod("GET");

            int code = conn.getResponseCode();
            if (code == 401) {
                sp.edit().putString(KEY_STATUS, "登录已过期，请在 App 里重新登录").putLong(KEY_TIME, System.currentTimeMillis()).apply();
                return Result.failure();
            }
            if (code != 200) {
                sp.edit().putString(KEY_STATUS, "备份失败 HTTP " + code).apply();
                return Result.retry();
            }

            StringBuilder sb = new StringBuilder();
            try (InputStream is = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            }

            JSONObject obj = new JSONObject(sb.toString());
            JSONArray notes = obj.optJSONArray("notes");
            int count = notes == null ? 0 : notes.length();

            String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            File dir = new File(ctx.getFilesDir(), "backups");
            if (!dir.exists() && !dir.mkdirs()) {
                sp.edit().putString(KEY_STATUS, "无法创建备份目录").apply();
                return Result.failure();
            }
            File out = new File(dir, "notes-" + stamp + ".json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            prune(dir);

            sp.edit()
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .putString(KEY_STATUS, "成功，共 " + count + " 条")
                    .putInt(KEY_COUNT, count)
                    .apply();
            return Result.success();
        } catch (Exception e) {
            sp.edit().putString(KEY_STATUS, "网络异常：" + e.getMessage()).apply();
            return Result.retry();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void prune(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("notes-") && name.endsWith(".json"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = KEEP; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
