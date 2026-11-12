package eu.wenjun.notes;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 直接读写系统剪贴板：WebView 里长按菜单有时不给「粘贴」，靠这个按钮兜底。
 */
@CapacitorPlugin(name = "Clipboard")
public class ClipboardPlugin extends Plugin {

    @PluginMethod
    public void read(PluginCall call) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            String text = "";
            if (manager != null && manager.hasPrimaryClip()) {
                ClipData clip = manager.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence value = clip.getItemAt(0).coerceToText(getContext());
                    if (value != null) text = value.toString();
                }
            }
            JSObject ret = new JSObject();
            ret.put("text", text);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("读取剪贴板失败: " + e.getMessage());
        }
    }

    @PluginMethod
    public void write(PluginCall call) {
        String text = call.getString("text", "");
        try {
            ClipboardManager manager =
                    (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("记事本", text));
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("写入剪贴板失败: " + e.getMessage());
        }
    }
}
