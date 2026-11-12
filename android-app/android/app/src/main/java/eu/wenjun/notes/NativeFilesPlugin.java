package eu.wenjun.notes;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 存文件到系统「下载」目录（Android 10+ 走 MediaStore，不需要存储权限）。
 */
@CapacitorPlugin(name = "NativeFiles")
public class NativeFilesPlugin extends Plugin {

    @PluginMethod
    public void save(PluginCall call) {
        String filename = call.getString("filename", "note.html");
        String content = call.getString("content", "");
        String mime = call.getString("mime", "text/html");
        if (filename == null || filename.isEmpty()) {
            call.reject("缺少文件名");
            return;
        }
        filename = filename.replaceAll("[/\\\\:*?\"<>|]", "-");
        Context ctx = getContext();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        try {
            JSObject ret = new JSObject();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/记事本");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    call.reject("无法创建下载文件");
                    return;
                }
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                    if (os == null) {
                        call.reject("无法写入下载文件");
                        return;
                    }
                    os.write(bytes);
                }
                ret.put("path", "下载/记事本/" + filename);
                ret.put("uri", uri.toString());
            } else {
                File dir = new File(ctx.getExternalFilesDir(null), "记事本");
                if (!dir.exists() && !dir.mkdirs()) {
                    call.reject("无法创建目录");
                    return;
                }
                File out = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                ret.put("path", out.getAbsolutePath());
            }
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("保存失败: " + e.getMessage());
        }
    }
}
