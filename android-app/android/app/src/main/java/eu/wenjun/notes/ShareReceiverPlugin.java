package eu.wenjun.notes;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 系统「分享到记事本」送进来的文本，网页启动时取走并新建一条笔记。
 */
@CapacitorPlugin(name = "ShareReceiver")
public class ShareReceiverPlugin extends Plugin {

    @PluginMethod
    public void getPending(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("text", MainActivity.pendingShare == null ? "" : MainActivity.pendingShare);
        call.resolve(ret);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        MainActivity.pendingShare = null;
        call.resolve();
    }
}
