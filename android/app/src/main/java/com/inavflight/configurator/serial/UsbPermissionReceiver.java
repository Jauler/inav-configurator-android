package com.inavflight.configurator.serial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Explicit (manifest-declared) receiver for the USB permission result. Android 14+
 * requires mutable PendingIntents to target an explicit component.
 */
public class UsbPermissionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        InavSerialPlugin.onUsbPermissionResult(intent);
    }
}
