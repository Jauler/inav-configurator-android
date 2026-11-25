package com.inavflight.configurator;

import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;
import com.inavflight.configurator.file.InavFilePlugin;
import com.inavflight.configurator.net.InavTcpPlugin;
import com.inavflight.configurator.net.InavUdpPlugin;
import com.inavflight.configurator.serial.InavSerialPlugin;
import com.inavflight.configurator.usb.InavUsbPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(InavSerialPlugin.class);
        registerPlugin(InavTcpPlugin.class);
        registerPlugin(InavUdpPlugin.class);
        registerPlugin(InavFilePlugin.class);
        registerPlugin(InavUsbPlugin.class);
        registerPlugin(InavBackPlugin.class);

        // When launched by a USB attach intent (the "open INAV Configurator when this
        // device is connected" prompt), replace it with a plain launcher intent before
        // the bridge loads; otherwise Capacitor treats it as a deep link and reloads
        // the WebView. Approach taken from the Betaflight App (GPL-3.0).
        if (getIntent() != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        }

        super.onCreate(savedInstanceState);

        // Back button/gesture. The callback is only enabled while the web UI has
        // something to do with it (js/mobileNav.js: drawer or options window open, a
        // modal dialog shown, or connected to an FC), so ordinary presses never wait for
        // the WebView and predictive back keeps working.
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (bridge == null || bridge.getWebView() == null) {
                performDefaultBack();
                return;
            }
            bridge.getWebView().evaluateJavascript(
                "typeof window.inavHandleBackButton === 'function' && window.inavHandleBackButton() === true",
                consumed -> {
                    if (!"true".equals(consumed)) {
                        performDefaultBack();
                    }
                });
        }
    };

    /** Called by InavBackPlugin on the UI thread. */
    void setBackIntercept(boolean enabled) {
        backCallback.setEnabled(enabled);
    }

    /** Runs the default back action (leave/background the app), skipping our callback. */
    void performDefaultBack() {
        boolean wasEnabled = backCallback.isEnabled();
        backCallback.setEnabled(false);
        getOnBackPressedDispatcher().onBackPressed();
        backCallback.setEnabled(wasEnabled);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // Re-plugging the FC while the app is open must not reload the UI.
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            setIntent(intent);
            return;
        }
        super.onNewIntent(intent);
    }
}
