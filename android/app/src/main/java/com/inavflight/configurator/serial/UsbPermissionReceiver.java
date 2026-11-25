package com.inavflight.configurator.serial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.inavflight.configurator.usb.InavUsbPlugin;

/**
 * Explicit (manifest-declared) receiver for USB permission results, for both the
 * serial plugin and the DFU (InavUsb) plugin. Android 14+ requires mutable
 * PendingIntents to target an explicit component.
 */
public class UsbPermissionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (InavUsbPlugin.ACTION_USB_PERMISSION.equals(intent.getAction())) {
            InavUsbPlugin.onUsbPermissionResult(intent);
        } else {
            InavSerialPlugin.onUsbPermissionResult(intent);
        }
    }
}
