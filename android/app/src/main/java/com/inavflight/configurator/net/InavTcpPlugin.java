package com.inavflight.configurator.net;

import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * TCP client transport (SITL on another machine, ESP/WiFi bridges).
 * Events: "data" {data: base64}, "end", "error" {message}.
 */
@CapacitorPlugin(name = "InavTcp")
public class InavTcpPlugin extends Plugin {
    private static final String TAG = "InavTcp";
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private volatile Socket socket;
    private OutputStream output;
    private final Object writeLock = new Object();

    @PluginMethod
    public void connect(PluginCall call) {
        final String host = call.getString("host");
        final Integer port = call.getInt("port");
        if (host == null || host.isEmpty() || port == null || port < 1 || port > 65535) {
            call.reject("Invalid host or port");
            return;
        }

        closeSocket();

        // Connecting blocks; keep it off the shared plugin thread.
        new Thread(() -> {
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                call.reject("TCP connect failed: " + e.getMessage());
                return;
            }

            try {
                synchronized (writeLock) {
                    socket = s;
                    output = s.getOutputStream();
                }
                InputStream input = s.getInputStream();
                startReader(s, input);
                call.resolve();
            } catch (IOException e) {
                closeSocket();
                call.reject("TCP connect failed: " + e.getMessage());
            }
        }, "InavTcpConnect").start();
    }

    @PluginMethod
    public void write(PluginCall call) {
        byte[] bytes = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
        synchronized (writeLock) {
            if (output == null) {
                call.reject("Socket closed or invalid");
                return;
            }
            try {
                output.write(bytes);
                output.flush();
            } catch (IOException e) {
                call.reject("TCP write failed: " + e.getMessage());
                return;
            }
        }
        JSObject result = new JSObject();
        result.put("bytesWritten", bytes.length);
        call.resolve(result);
    }

    @PluginMethod
    public void close(PluginCall call) {
        closeSocket();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        closeSocket();
        super.handleOnDestroy();
    }

    private void startReader(final Socket s, final InputStream input) {
        new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int n;
                while ((n = input.read(buffer)) != -1) {
                    JSObject event = new JSObject();
                    event.put("data", Base64.encodeToString(Arrays.copyOf(buffer, n), Base64.NO_WRAP));
                    notifyListeners("data", event);
                }
                if (s == socket) {
                    notifyListeners("end", new JSObject());
                }
            } catch (IOException e) {
                // A socket we closed ourselves is no longer the current one: stay quiet.
                if (s == socket) {
                    Log.w(TAG, "TCP read error", e);
                    JSObject error = new JSObject();
                    error.put("message", e.getMessage());
                    notifyListeners("error", error);
                }
            } finally {
                if (s == socket) {
                    closeSocket();
                }
            }
        }, "InavTcpReader").start();
    }

    private void closeSocket() {
        Socket s;
        synchronized (writeLock) {
            s = socket;
            socket = null;
            output = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
