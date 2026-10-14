package xyz.yophon.gopan;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/**
 * 壳 Activity:注册 ShareReceiver 插件,把系统分享的 intent 交给它。
 * launchMode=singleTask:App 已在后台时分享走 onNewIntent,否则走 onCreate。
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ShareReceiverPlugin.class);
        super.onCreate(savedInstanceState);
        ShareReceiverPlugin.accept(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ShareReceiverPlugin.accept(intent);
    }
}
