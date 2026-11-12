package eu.wenjun.notes;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "SecureStore")
public class SecureStorePlugin extends Plugin {

    @PluginMethod
    public void set(PluginCall call) {
        String key = call.getString("key");
        String value = call.getString("value", "");
        if (key == null || key.isEmpty()) {
            call.reject("缺少 key");
            return;
        }
        try {
            SecureStore.set(getContext(), key, value);
            call.resolve();
        } catch (Exception e) {
            call.reject("加密存储写入失败: " + e.getMessage());
        }
    }

    @PluginMethod
    public void get(PluginCall call) {
        String key = call.getString("key");
        JSObject ret = new JSObject();
        ret.put("value", key == null ? null : SecureStore.get(getContext(), key));
        call.resolve(ret);
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String key = call.getString("key");
        if (key != null) SecureStore.remove(getContext(), key);
        call.resolve();
    }
}
