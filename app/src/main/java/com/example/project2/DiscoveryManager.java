package com.example.project2;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
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

            // 2. Scan local subnet
            String localIp = getLocalIpAddress();
            if (localIp != null && localIp.startsWith("192.168.")) {
                String subnet = localIp.substring(0, localIp.lastIndexOf(".") + 1);
                Log.i(TAG, "[DISCOVERY] Scanning subnet: " + subnet + "0/24");

                String foundIp = scanSubnet(subnet);
                if (foundIp != null) {
                    saveIp(foundIp);
                    callback.onServerFound(foundIp);
                    return;
                }
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
            java.net.URL url = new java.net.URL("http://" + ip + ":" + SERVER_PORT + "/ping");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code == 200) {
                java.io.InputStream is = conn.getInputStream();
                String body = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                is.close();
                return body.contains("spotify-automation-hub");
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String scanSubnet(String subnetPrefix) {
        ExecutorService executor = Executors.newFixedThreadPool(32);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 1; i < 255; i++) {
            final String ip = subnetPrefix + i;
            futures.add(executor.submit(() -> {
                // Port check first (fast), then identity validation (slower, targeted)
                if (isPortOpen(ip, SERVER_PORT, 500) && isOurServer(ip)) {
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

    private boolean isPortOpen(String ip, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeout);
            return true;
        } catch (Exception e) {
            return false;
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
