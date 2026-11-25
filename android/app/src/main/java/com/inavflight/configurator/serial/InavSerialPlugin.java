package com.inavflight.configurator.serial;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
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
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * USB serial transport for the INAV Configurator renderer (js/platform/capacitor.js).
 *
 * Devices are identified by their USB device path (e.g. /dev/bus/usb/001/002), which
 * the port picker shows as the port name. USB permission is requested on connect.
 * Data is exchanged as base64 strings.
 *
 * Structure based on the Betaflight App's BetaflightSerialPlugin (GPL-3.0).
 */
@CapacitorPlugin(name = "InavSerial")
public class InavSerialPlugin extends Plugin implements SerialInputOutputManager.Listener {
    private static final String TAG = "InavSerial";
    static final String ACTION_USB_PERMISSION = "com.inavflight.configurator.USB_PERMISSION";
    private static final int WRITE_TIMEOUT_MS = 2000;

    private static WeakReference<InavSerialPlugin> instance = new WeakReference<>(null);

    private UsbManager usbManager;
    private UsbDeviceConnection connection;
    private UsbSerialPort port;
    private UsbDevice connectedDevice;
    private SerialInputOutputManager ioManager;
    private volatile boolean closing = false;

    private PluginCall pendingConnectCall;
    private UsbSerialDriver pendingDriver;

    @Override
    public void load() {
        super.load();
        instance = new WeakReference<>(this);
        usbManager = (UsbManager) getContext().getSystemService(Context.USB_SERVICE);
    }

    @Override
    protected void handleOnDestroy() {
        closePort();
        super.handleOnDestroy();
    }

    @PluginMethod
    public void getDevices(PluginCall call) {
        JSArray devices = new JSArray();
        for (UsbSerialDriver driver : findDrivers()) {
            UsbDevice device = driver.getDevice();
            JSObject info = new JSObject();
            info.put("path", device.getDeviceName());
            info.put("vendorId", device.getVendorId());
            info.put("productId", device.getProductId());
            info.put("product", device.getProductName());
            info.put("manufacturer", device.getManufacturerName());
            info.put("hasPermission", usbManager.hasPermission(device));
            devices.put(info);
        }
        JSObject result = new JSObject();
        result.put("devices", devices);
        call.resolve(result);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        String path = call.getString("path");
        int baudRate = call.getInt("baudRate", 115200);
        if (path == null) {
            call.reject("path is required");
            return;
        }
        if (pendingConnectCall != null) {
            call.reject("Another connection attempt is waiting for USB permission");
            return;
        }

        closePort();

        UsbSerialDriver driver = null;
        for (UsbSerialDriver candidate : findDrivers()) {
            if (candidate.getDevice().getDeviceName().equals(path)) {
                driver = candidate;
                break;
            }
        }
        if (driver == null) {
            call.reject("USB serial device not found: " + path);
            return;
        }

        if (usbManager.hasPermission(driver.getDevice())) {
            openPort(call, driver, baudRate);
            return;
        }

        // Ask the user, then continue in onUsbPermissionResult().
        pendingConnectCall = call;
        pendingDriver = driver;
        requestPermission(driver.getDevice());
    }

    @PluginMethod
    public void write(PluginCall call) {
        UsbSerialPort current = port;
        if (current == null || !current.isOpen()) {
            call.reject("Serial port is not open");
            return;
        }
        String data = call.getString("data", "");
        byte[] bytes = Base64.decode(data, Base64.DEFAULT);
        try {
            current.write(bytes, WRITE_TIMEOUT_MS);
            JSObject result = new JSObject();
            result.put("bytesWritten", bytes.length);
            call.resolve(result);
        } catch (IOException e) {
            call.reject("Serial write failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void close(PluginCall call) {
        closePort();
        call.resolve();
    }

    // ---- SerialInputOutputManager.Listener ------------------------------------------

    @Override
    public void onNewData(byte[] data) {
        JSObject event = new JSObject();
        event.put("data", Base64.encodeToString(data, Base64.NO_WRAP));
        notifyListeners("data", event);
    }

    @Override
    public void onRunError(Exception e) {
        if (closing) {
            return;
        }
        Log.w(TAG, "Serial I/O error", e);
        JSObject error = new JSObject();
        error.put("message", e.getMessage() != null ? e.getMessage() : "Serial connection lost");
        notifyListeners("error", error);
        closePort();
        notifyListeners("close", new JSObject());
    }

    // ---- Permission handling ---------------------------------------------------------

    static void onUsbPermissionResult(Intent intent) {
        InavSerialPlugin plugin = instance.get();
        if (plugin != null) {
            plugin.handlePermissionResult();
        }
    }

    private void handlePermissionResult() {
        PluginCall call = pendingConnectCall;
        UsbSerialDriver driver = pendingDriver;
        pendingConnectCall = null;
        pendingDriver = null;
        if (call == null || driver == null) {
            return;
        }
        // Query the permission directly instead of trusting the intent extras, which
        // are only filled in for mutable PendingIntents.
        if (!usbManager.hasPermission(driver.getDevice())) {
            call.reject("USB permission denied");
            return;
        }
        openPort(call, driver, call.getInt("baudRate", 115200));
    }

    private void requestPermission(UsbDevice device) {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        intent.setComponent(new ComponentName(getContext(), UsbPermissionReceiver.class));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(getContext(), device.getDeviceId(), intent, flags);
        usbManager.requestPermission(device, pendingIntent);
    }

    // ---- Port lifecycle --------------------------------------------------------------

    private List<UsbSerialDriver> findDrivers() {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
    }

    private void openPort(PluginCall call, UsbSerialDriver driver, int baudRate) {
        try {
            connection = usbManager.openDevice(driver.getDevice());
            if (connection == null) {
                call.reject("Unable to open USB device");
                return;
            }
            if (driver.getPorts().isEmpty()) {
                closePort();
                call.reject("USB device has no serial ports");
                return;
            }
            port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(baudRate, 8, parseStopBits(call.getString("stopBits")), parseParity(call.getString("parity")));
            try {
                port.setDTR(true);
                port.setRTS(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Not all drivers support modem control lines.
            }
            connectedDevice = driver.getDevice();
            closing = false;
            ioManager = new SerialInputOutputManager(port, this);
            ioManager.start();
            Log.i(TAG, "Connected to " + connectedDevice.getDeviceName() + " @ " + baudRate);
            call.resolve();
        } catch (IOException e) {
            closePort();
            call.reject("Unable to open serial port: " + e.getMessage());
        }
    }

    // Names as used by the renderer (parityBit/stopBits connect options); the STM32 UART
    // bootloader needs 8E1.
    private static int parseParity(String parity) {
        if (parity == null) {
            return UsbSerialPort.PARITY_NONE;
        }
        switch (parity) {
            case "even":
                return UsbSerialPort.PARITY_EVEN;
            case "odd":
                return UsbSerialPort.PARITY_ODD;
            default:
                return UsbSerialPort.PARITY_NONE;
        }
    }

    private static int parseStopBits(String stopBits) {
        return "two".equals(stopBits) ? UsbSerialPort.STOPBITS_2 : UsbSerialPort.STOPBITS_1;
    }

    private synchronized void closePort() {
        closing = true;
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
        if (port != null) {
            try {
                port.close();
            } catch (IOException ignored) {
            }
            port = null;
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
        connectedDevice = null;
    }
}
