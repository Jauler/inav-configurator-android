package com.inavflight.configurator.net;

import android.util.Base64;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * UDP transport. Matches js/main/udp.js: binds the local port equal to the remote
 * port and sends datagrams to host:port.
 * Events: "message" {data: base64}, "error" {message}.
 */
@CapacitorPlugin(name = "InavUdp")
public class InavUdpPlugin extends Plugin {
    private static final String TAG = "InavUdp";

    private DatagramSocket socket;
    private InetAddress remoteAddress;
    private int remotePort;

    @PluginMethod
    public void connect(PluginCall call) {
        final String host = call.getString("host");
        final Integer port = call.getInt("port");
        if (host == null || host.isEmpty() || port == null || port < 1 || port > 65535) {
            call.reject("Invalid host or port");
            return;
        }

        closeSocket();

        new Thread(() -> {
            try {
                InetAddress address = InetAddress.getByName(host);
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port));
                synchronized (this) {
                    socket = s;
                    remoteAddress = address;
                    remotePort = port;
                }
                startReader(s);
                call.resolve();
            } catch (IOException e) {
                call.reject("UDP bind failed: " + e.getMessage());
            }
        }, "InavUdpConnect").start();
    }

    @PluginMethod
    public void write(PluginCall call) {
        byte[] bytes = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
        DatagramSocket s;
        InetAddress address;
        int port;
        synchronized (this) {
            s = socket;
            address = remoteAddress;
            port = remotePort;
        }
        if (s == null) {
            call.reject("Socket closed or invalid");
            return;
        }
        try {
            s.send(new DatagramPacket(bytes, bytes.length, address, port));
            JSObject result = new JSObject();
            result.put("bytesWritten", bytes.length);
            call.resolve(result);
        } catch (IOException e) {
            call.reject("UDP send failed: " + e.getMessage());
        }
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

    private void startReader(final DatagramSocket s) {
        new Thread(() -> {
            byte[] buffer = new byte[65535];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (!s.isClosed()) {
                try {
                    packet.setLength(buffer.length);
                    s.receive(packet);
                    JSObject event = new JSObject();
                    event.put("data", Base64.encodeToString(
                            Arrays.copyOfRange(buffer, 0, packet.getLength()), Base64.NO_WRAP));
                    notifyListeners("message", event);
                } catch (IOException e) {
                    if (!s.isClosed()) {
                        Log.w(TAG, "UDP receive error", e);
                        JSObject error = new JSObject();
                        error.put("message", e.getMessage());
                        notifyListeners("error", error);
                    }
                    return;
                }
            }
        }, "InavUdpReader").start();
    }

    private synchronized void closeSocket() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
        remoteAddress = null;
    }
}
