package eu.wenjun.notes;

import android.app.Activity;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Biometric")
public class BiometricPlugin extends Plugin {

    @PluginMethod
    public void isAvailable(PluginCall call) {
        BiometricManager manager = BiometricManager.from(getContext());
        int code = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL
        );
        JSObject ret = new JSObject();
        ret.put("available", code == BiometricManager.BIOMETRIC_SUCCESS);
        ret.put("code", code);
        call.resolve(ret);
    }

    @PluginMethod
    public void authenticate(final PluginCall call) {
        final Activity raw = getActivity();
        if (raw == null) {
            call.reject("没有可用界面");
            return;
        }
        // BridgeActivity 继承 FragmentActivity，BiometricPrompt 只认这个类型
        final FragmentActivity activity = (FragmentActivity) raw;
        final String title = call.getString("title", "解锁记事本");
        final String subtitle = call.getString("subtitle", "用指纹或锁屏密码继续");

        activity.runOnUiThread(() -> {
            BiometricPrompt prompt = new BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                            JSObject ret = new JSObject();
                            ret.put("ok", true);
                            call.resolve(ret);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            JSObject ret = new JSObject();
                            ret.put("ok", false);
                            ret.put("error", errString == null ? "已取消" : errString.toString());
                            call.resolve(ret);
                        }
                    }
            );

            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build();
            prompt.authenticate(info);
        });
    }
}
