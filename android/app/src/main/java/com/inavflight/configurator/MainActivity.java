package com.inavflight.configurator;

import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

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

        // When launched by a USB attach intent (the "open INAV Configurator when this
        // device is connected" prompt), replace it with a plain launcher intent before
        // the bridge loads; otherwise Capacitor treats it as a deep link and reloads
        // the WebView. Approach taken from the Betaflight App (GPL-3.0).
        if (getIntent() != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(getIntent().getAction())) {
            setIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER));
        }

        super.onCreate(savedInstanceState);
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
