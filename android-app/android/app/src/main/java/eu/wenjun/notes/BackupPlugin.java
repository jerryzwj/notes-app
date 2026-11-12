package eu.wenjun.notes;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "Backup")
public class BackupPlugin extends Plugin {

    private static final String WORK_NAME = "notes-daily-backup";

    @PluginMethod
    public void schedule(PluginCall call) {
        int hour = call.getInt("hour", 4) == null ? 4 : call.getInt("hour", 4);
        int minute = call.getInt("minute", 0) == null ? 0 : call.getInt("minute", 0);
        long delay = initialDelayMillis(hour, minute);

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(BackupWorker.class, 24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
                .build();

        WorkManager.getInstance(getContext())
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);

        JSObject ret = new JSObject();
        ret.put("scheduled", true);
        ret.put("firstRunIn", delay / 1000);
        call.resolve(ret);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        WorkManager.getInstance(getContext()).cancelUniqueWork(WORK_NAME);
        call.resolve();
    }

    @PluginMethod
    public void runNow(PluginCall call) {
        WorkManager.getInstance(getContext()).enqueue(
                new OneTimeWorkRequest.Builder(BackupWorker.class)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .build()
        );
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        Context ctx = getContext();
        SharedPreferences sp = ctx.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        JSObject ret = new JSObject();
        ret.put("lastTime", sp.getLong(BackupWorker.KEY_TIME, 0));
        ret.put("lastStatus", sp.getString(BackupWorker.KEY_STATUS, "还没跑过"));
        ret.put("lastCount", sp.getInt(BackupWorker.KEY_COUNT, -1));

        File dir = new File(ctx.getFilesDir(), "backups");
        File[] files = dir.listFiles((d, name) -> name.startsWith("notes-") && name.endsWith(".json"));
        if (files != null) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < Math.min(files.length, 5); i++) {
                if (names.length() > 0) names.append(',');
                names.append(files[i].getName());
            }
            ret.put("files", files.length);
            ret.put("recent", names.toString());
        } else {
            ret.put("files", 0);
            ret.put("recent", "");
        }
        call.resolve(ret);
    }

    private long initialDelayMillis(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return target.getTimeInMillis() - now.getTimeInMillis();
    }
}
