package com.example.project2;

import android.util.Log;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class DeviceDiscovery {
    private static final String TAG = "DeviceDiscovery";
    private static final int DISCOVERY_PORT = 8888;
    private boolean isSearching = false;
    private final DiscoveryListener listener;

    public interface DiscoveryListener {
        void onServerFound(String ip, int port);
    }

    public DeviceDiscovery(DiscoveryListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (isSearching)
            return;
        isSearching = true;

        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(DISCOVERY_PORT);
                socket.setBroadcast(true);
                byte[] buffer = new byte[1024];

                Log.i(TAG, "Scanning for Spotify Automation Server...");

                while (isSearching) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String data = new String(packet.getData(), 0, packet.getLength());
                    JSONObject json = new JSONObject(data);

                    if ("SPOTIFY_SERVER".equals(json.optString("type"))) {
                        String ip = json.getString("ip");
                        int port = json.getInt("port");
                        Log.i(TAG, "Server Found at: " + ip + ":" + port);
                        listener.onServerFound(ip, port);
                        // Stop searching once found, or keep listening if you want to support IP
                        // changes
                        isSearching = false;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Discovery error", e);
            } finally {
                if (socket != null)
                    socket.close();
            }
        }).start();
    }

    public void stop() {
        isSearching = false;
    }
}
