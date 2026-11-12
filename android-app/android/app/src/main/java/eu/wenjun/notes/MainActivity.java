package eu.wenjun.notes;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    public static String pendingShare = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SecureStorePlugin.class);
        registerPlugin(BiometricPlugin.class);
        registerPlugin(NativeFilesPlugin.class);
        registerPlugin(ShareReceiverPlugin.class);
        registerPlugin(BackupPlugin.class);
        registerPlugin(ClipboardPlugin.class);
        super.onCreate(savedInstanceState);
        captureShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureShare(intent);
    }

    private void captureShare(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (!"text/plain".equals(intent.getType())) return;
        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null && !text.toString().trim().isEmpty()) {
            pendingShare = text.toString();
        }
    }
}
