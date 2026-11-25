package com.inavflight.configurator.usb;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.inavflight.configurator.serial.UsbPermissionReceiver;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Raw USB access for DFU flashing (js/protocols/stm32usbdfu.js).
 *
 * The Android WebView has no WebUSB, so js/platform/capacitor.js wraps this plugin in
 * an object shaped like a WebUSB USBDevice. The DFU state machine stays in JavaScript;
 * this plugin only lists bootloader devices, handles USB permission and performs
 * control transfers. Binary data crosses the bridge as base64.
 *
 * Plugin methods run on Capacitor's plugin thread, so the blocking
 * UsbDeviceConnection.controlTransfer() calls do not stall the UI.
 *
 * Approach based on the Betaflight App's BetaflightDfuPlugin (GPL-3.0).
 */
@CapacitorPlugin(name = "InavUsb")
public class InavUsbPlugin extends Plugin {
    private static final String TAG = "InavUsb";
    public static final String ACTION_USB_PERMISSION = "com.inavflight.configurator.DFU_USB_PERMISSION";
    private static final int DEFAULT_TIMEOUT_MS = 5000;

    // DFU bootloaders. Keep in sync with usbDevices in js/port_handler.js and
    // android/app/src/main/res/xml/device_filter.xml.
    private static final int[][] DFU_DEVICES = {
        {1155, 57105},   // 0483:df11 STM32 DFU
        {11836, 57105},  // 2e3c:df11 AT32 DFU
        {12619, 262},    // 314b:0106 APM32 DFU
    };

    private static WeakReference<InavUsbPlugin> instance = new WeakReference<>(null);

    private UsbManager usbManager;
    private UsbDevice currentDevice;
    private UsbDeviceConnection connection;
    private final Map<Integer, UsbInterface> claimedInterfaces = new HashMap<>();

    private PluginCall pendingPermissionCall;
    private UsbDevice pendingPermissionDevice;

    @Override
    public void load() {
        super.load();
        instance = new WeakReference<>(this);
        usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
    }

    @Override
    protected void handleOnDestroy() {
        closeInternal();
        super.handleOnDestroy();
    }

    // ---- Discovery and permission ----------------------------------------------------

    @PluginMethod
    public void getDevices(PluginCall call) {
        JSArray devices = new JSArray();
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (!isDfuDevice(device)) {
                continue;
            }
            boolean permitted = usbManager.hasPermission(device);
            JSObject info = new JSObject();
            info.put("deviceName", device.getDeviceName());
            info.put("vendorId", device.getVendorId());
            info.put("productId", device.getProductId());
            info.put("productName", device.getProductName());
            info.put("manufacturerName", device.getManufacturerName());
            info.put("hasPermission", permitted);
            if (permitted) {
                try {
                    info.put("serialNumber", device.getSerialNumber());
                } catch (SecurityException ignored) {
                    // Not readable without permission on Android 10+.
                }
            }
            devices.put(info);
        }
        JSObject result = new JSObject();
        result.put("devices", devices);
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        UsbDevice device = findDevice(call.getString("deviceName"));
        if (device == null) {
            call.reject("USB device not found");
            return;
        }
        if (usbManager.hasPermission(device)) {
            resolveGranted(call, true);
            return;
        }
        if (pendingPermissionCall != null) {
            // The renderer gave up on the previous request (it times out); don't let
            // a lost permission result block every later one.
            resolveGranted(pendingPermissionCall, false);
        }
        pendingPermissionCall = call;
        pendingPermissionDevice = device;

        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setComponent(new ComponentName(getContext(), UsbPermissionReceiver.class));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(getContext(), device.getDeviceId(), intent, flags);
        usbManager.requestPermission(device, pendingIntent);
    }

    public static void onUsbPermissionResult(Intent intent) {
        InavUsbPlugin plugin = instance.get();
        if (plugin != null) {
            plugin.handlePermissionResult();
        }
    }

    private void handlePermissionResult() {
        PluginCall call = pendingPermissionCall;
        UsbDevice device = pendingPermissionDevice;
        pendingPermissionCall = null;
        pendingPermissionDevice = null;
        if (call == null || device == null) {
            return;
        }
        // Ask UsbManager instead of trusting the intent extras.
        resolveGranted(call, usbManager.hasPermission(device));
    }

    private static void resolveGranted(PluginCall call, boolean granted) {
        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
    }

    // ---- Device lifecycle ------------------------------------------------------------

    @PluginMethod
    public void open(PluginCall call) {
        UsbDevice device = findDevice(call.getString("deviceName"));
        if (device == null) {
            call.reject("USB device not found");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            call.reject("No permission for USB device");
            return;
        }
        closeInternal();
        UsbDeviceConnection opened = usbManager.openDevice(device);
        if (opened == null) {
            call.reject("Unable to open USB device");
            return;
        }
        connection = opened;
        currentDevice = device;
        Log.i(TAG, "Opened " + device.getDeviceName());
        call.resolve();
    }

    @PluginMethod
    public void close(PluginCall call) {
        closeInternal();
        call.resolve();
    }

    @PluginMethod
    public void claimInterface(PluginCall call) {
        if (connection == null) {
            call.reject("No USB device is open");
            return;
        }
        int number = call.getInt("interfaceNumber", 0);
        UsbInterface iface = findInterface(number, 0);
        if (iface == null) {
            call.reject("Interface " + number + " not found");
            return;
        }
        if (!connection.claimInterface(iface, true)) {
            call.reject("Unable to claim interface " + number);
            return;
        }
        claimedInterfaces.put(number, iface);
        call.resolve();
    }

    @PluginMethod
    public void releaseInterface(PluginCall call) {
        int number = call.getInt("interfaceNumber", 0);
        UsbInterface iface = claimedInterfaces.remove(number);
        if (connection != null && iface != null) {
            connection.releaseInterface(iface);
        }
        call.resolve();
    }

    @PluginMethod
    public void selectAlternateInterface(PluginCall call) {
        if (connection == null) {
            call.reject("No USB device is open");
            return;
        }
        int number = call.getInt("interfaceNumber", 0);
        int alternate = call.getInt("alternateSetting", 0);
        UsbInterface iface = findInterface(number, alternate);
        if (iface == null || !connection.setInterface(iface)) {
            call.reject("Unable to select interface " + number + " alternate " + alternate);
            return;
        }
        call.resolve();
    }

    // ---- Control transfers -----------------------------------------------------------

    /**
     * One control transfer. requestType is the full bmRequestType byte; bit 7 set means
     * device-to-host, in which case up to `length` bytes are read. Otherwise `data`
     * (base64) is sent. A failed transfer (stall, disconnect, timeout) resolves with
     * status "stall", like WebUSB, rather than rejecting.
     */
    @PluginMethod
    public void controlTransfer(PluginCall call) {
        UsbDeviceConnection current = connection;
        if (current == null) {
            call.reject("No USB device is open");
            return;
        }
        int requestType = call.getInt("requestType", 0);
        int request = call.getInt("request", 0);
        int value = call.getInt("value", 0);
        int index = call.getInt("index", 0);
        int timeout = call.getInt("timeout", DEFAULT_TIMEOUT_MS);
        boolean in = (requestType & 0x80) != 0;

        byte[] buffer;
        if (in) {
            buffer = new byte[Math.max(0, call.getInt("length", 0))];
        } else {
            String data = call.getString("data", "");
            buffer = (data == null || data.isEmpty()) ? new byte[0] : Base64.decode(data, Base64.DEFAULT);
        }

        int transferred = current.controlTransfer(requestType, request, value, index, buffer, buffer.length, timeout);

        JSObject result = new JSObject();
        if (transferred < 0) {
            result.put("status", "stall");
            result.put("length", 0);
            result.put("data", "");
        } else {
            result.put("status", "ok");
            result.put("length", transferred);
            result.put("data", in ? Base64.encodeToString(buffer, 0, transferred, Base64.NO_WRAP) : "");
        }
        call.resolve(result);
    }

    // ---- Helpers ---------------------------------------------------------------------

    private static boolean isDfuDevice(UsbDevice device) {
        for (int[] ids : DFU_DEVICES) {
            if (device.getVendorId() == ids[0] && device.getProductId() == ids[1]) {
                return true;
            }
        }
        return false;
    }

    private UsbDevice findDevice(String deviceName) {
        if (deviceName == null) {
            return null;
        }
        UsbDevice device = usbManager.getDeviceList().get(deviceName);
        return (device != null && isDfuDevice(device)) ? device : null;
    }

    private UsbInterface findInterface(int number, int alternate) {
        if (currentDevice == null) {
            return null;
        }
        for (int i = 0; i < currentDevice.getInterfaceCount(); i++) {
            UsbInterface iface = currentDevice.getInterface(i);
            if (iface.getId() == number && iface.getAlternateSetting() == alternate) {
                return iface;
            }
        }
        return null;
    }

    private synchronized void closeInternal() {
        if (connection != null) {
            for (UsbInterface iface : claimedInterfaces.values()) {
                connection.releaseInterface(iface);
            }
            connection.close();
            connection = null;
        }
        claimedInterfaces.clear();
        currentDevice = null;
    }
}
