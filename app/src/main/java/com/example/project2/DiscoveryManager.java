package com.example.project2;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;
import org.json.JSONObject;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * AUTO-DISCOVERY ENGINE: Resolves backend connectivity without user input.
 * Strategy:
 * 1. Try hardcoded domain (if any).
 * 2. Try last known good IP.
 * 3. Scan local subnet (Parallel scanning).
 */
public class DiscoveryManager {
    private static final String TAG = "spotifybot";
    private static final int SERVER_PORT = 8000;
    private final Context context;
    private String lastKnownIp;

    public DiscoveryManager(Context context) {
        this.context = context;
        android.content.SharedPreferences prefs = context.getSharedPreferences("SpotifyPrefs", Context.MODE_PRIVATE);
        this.lastKnownIp = prefs.getString("last_known_ip", null);
    }

    public interface DiscoveryCallback {
        void onServerFound(String ip);
    }

    public void discover(DiscoveryCallback callback) {
        new Thread(() -> {
            // 1. Try last known IP first (Fastest) — but validate it's OUR server
            if (lastKnownIp != null) {
                Log.d(TAG, "[DISCOVERY] Trying last known: " + lastKnownIp);
                if (isOurServer(lastKnownIp)) {
                    callback.onServerFound(lastKnownIp);
                    return;
                }
                Log.w(TAG, "[DISCOVERY] Last known IP " + lastKnownIp + " is not our server — scanning.");
            }

            // 2. Listen for the backend's UDP discovery broadcast before scanning a /24.
            String discoveredIp = listenForBroadcastIp(6000);
            if (discoveredIp != null) {
                saveIp(discoveredIp);
                callback.onServerFound(discoveredIp);
                return;
            }

            // 3. Scan local subnet derived from the device's current IPv4 address
            String localIp = getLocalIpAddress();
            String subnet = deriveSubnetPrefix(localIp);
            if (subnet != null) {
                Log.i(TAG, "[DISCOVERY] Local IP: " + localIp);
                Log.i(TAG, "[DISCOVERY] Scanning subnet: " + subnet + "0/24");

                String foundIp = scanSubnet(subnet, localIp);
                if (foundIp != null) {
                    saveIp(foundIp);
                    callback.onServerFound(foundIp);
                    return;
                }
            } else {
                Log.w(TAG, "[DISCOVERY] Could not determine an IPv4 subnet from local address: " + localIp);
            }

            Log.w(TAG, "[DISCOVERY] No server found on local network.");
        }).start();
    }

    /**
     * Validates that a candidate IP is actually our Spotify Automation server
     * by calling /ping and checking the service identifier in the response.
     */
    private boolean isOurServer(String ip) {
        try {
            String urlString = "http://" + ip + ":" + SERVER_PORT + "/ping";
            Log.i(TAG, "[DISCOVERY][PROBING] " + urlString);

            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            Log.i(TAG, "[DISCOVERY][HTTP_CODE] " + ip + " -> " + code);
            if (code == 200) {
                java.io.InputStream is = conn.getInputStream();
                String body = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                is.close();
                return body.contains("spotify-automation-hub");
            }
        } catch (Exception e) {
            Log.w(TAG, "[DISCOVERY][PROBE_FAILED] " + ip + " -> " + e.getClass().getSimpleName());
        }
        return false;
    }

    private String listenForBroadcastIp(int timeoutMs) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(8888);
            socket.setBroadcast(true);
            socket.setSoTimeout(timeoutMs);

            byte[] buffer = new byte[1024];
            long deadline = System.currentTimeMillis() + timeoutMs;

            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String data = new String(packet.getData(), 0, packet.getLength(), java.nio.charset.StandardCharsets.UTF_8);
                JSONObject json = new JSONObject(data);
                if (!"SPOTIFY_SERVER".equals(json.optString("type"))) {
                    continue;
                }

                String sourceIp = packet.getAddress() != null ? packet.getAddress().getHostAddress() : null;
                String payloadIp = json.optString("ip", null);
                String chosenIp = sourceIp != null && !sourceIp.isEmpty() ? sourceIp : payloadIp;

                Log.i(TAG, "[DISCOVERY][BROADCAST_FOUND] source=" + sourceIp + " payload=" + payloadIp + " chosen=" + chosenIp);
                if (chosenIp != null && isOurServer(chosenIp)) {
                    return chosenIp;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "[DISCOVERY][BROADCAST_LISTEN_FAILED] " + e.getClass().getSimpleName());
        } finally {
            if (socket != null)
                socket.close();
        }
        return null;
    }

    private String scanSubnet(String subnetPrefix, String localIp) {
        ExecutorService executor = Executors.newFixedThreadPool(32);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 1; i < 255; i++) {
            final String ip = subnetPrefix + i;
            if (ip.equals(localIp)) {
                continue;
            }
            futures.add(executor.submit(() -> {
                // Direct HTTP validation only. Do not prefilter with ICMP/TCP reachability checks.
                if (isOurServer(ip)) {
                    return ip;
                }
                return null;
            }));
        }

        try {
            for (Future<String> future : futures) {
                String result = future.get(10, TimeUnit.SECONDS);
                if (result != null) {
                    executor.shutdownNow();
                    return result;
                }
            }
        } catch (Exception ignored) {
        }

        executor.shutdown();
        return null;
    }

    private String deriveSubnetPrefix(String ipAddress) {
        if (ipAddress == null)
            return null;

        String[] parts = ipAddress.split("\\.");
        if (parts.length != 4)
            return null;

        try {
            for (String part : parts) {
                Integer.parseInt(part);
            }
            return parts[0] + "." + parts[1] + "." + parts[2] + ".";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getLocalIpAddress() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null)
            return null;
        LinkProperties lp = cm.getLinkProperties(activeNetwork);
        if (lp == null)
            return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress addr = la.getAddress();
            if (!addr.isLoopbackAddress() && addr.getHostAddress().contains(".")) {
                return addr.getHostAddress();
            }
        }
        return null;
    }

    private void saveIp(String ip) {
        this.lastKnownIp = ip;
        context.getSharedPreferences("SpotifyPrefs", Context.MODE_PRIVATE)
                .edit().putString("last_known_ip", ip).apply();
    }

    public void clearLastKnownIp() {
        this.lastKnownIp = null;
        context.getSharedPreferences("SpotifyPrefs", Context.MODE_PRIVATE)
                .edit().remove("last_known_ip").apply();
        Log.i(TAG, "🗑️ Cleared cached IP address");
    }
}
