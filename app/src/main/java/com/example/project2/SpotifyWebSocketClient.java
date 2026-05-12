package com.example.project2;

import android.util.Log;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;
import java.net.URI;
import java.util.Timer;
import java.util.TimerTask;

/**
 * PROTOCOL HANDLER: Manages WebSocket connection and heartbeat.
 * Fix: Heartbeat no longer spawns new threads (send() is thread-safe).
 * Fix: Reconnect handled solely by MyAccessibilityService via onDisconnected callback.
 */
public class SpotifyWebSocketClient extends WebSocketClient {
    private static final String TAG = "spotifybot";
    private final String deviceId;
    private final CommandRunner runner;
    private final android.content.Context context;
    private final Runnable onDisconnected;
    private Timer heartbeatTimer;

    public SpotifyWebSocketClient(String serverUri, String deviceId, CommandRunner runner,
                                   android.content.Context context, Runnable onDisconnected) {
        super(URI.create(serverUri));
        this.deviceId = deviceId;
        this.runner = runner;
        this.context = context;
        this.onDisconnected = onDisconnected;
        Log.i(TAG, "Client Initialized for URI: " + serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        Log.i(TAG, "✅ [WS] Connected to Backend");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            android.widget.Toast.makeText(context, "✅ Bot Connected!", android.widget.Toast.LENGTH_SHORT).show()
        );
        sendHello();
        startHeartbeat();
    }

    private void sendHello() {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "HELLO");
            hello.put("deviceId", deviceId);
            hello.put("deviceName", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            hello.put("appVersion", "1.2");
            send(hello.toString()); 
            Log.i(TAG, "📩 [WS] Sent HELLO: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "❌ [WS] HELLO failed", e);
        }
    }

    private void startHeartbeat() {
        if (heartbeatTimer != null) heartbeatTimer.cancel();
        heartbeatTimer = new Timer("ws-heartbeat", true);
        heartbeatTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (isOpen()) {
                    try { 
                        JSONObject hb = new JSONObject();
                        hb.put("type", "HEARTBEAT");
                        hb.put("deviceId", deviceId);
                        hb.put("timestamp", System.currentTimeMillis());
                        
                        // Add Telemetry
                        android.content.Intent batteryStatus = context.registerReceiver(null, 
                            new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                        if (batteryStatus != null) {
                            int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                            int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                            hb.put("battery", (int)((level / (float)scale) * 100));
                        }

                        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) 
                            context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                        android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                        hb.put("network_type", (activeNetwork != null) ? activeNetwork.getTypeName() : "offline");

                        send(hb.toString()); 
                    } catch (Exception ignored) {}
                } else {
                    cancel();
                }
            }
        }, 15000, 15000);
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type");
            if ("COMMAND".equals(type) || "STOP_SESSION".equals(type)) {
                runner.enqueue(json);
            }
            // PONG ignored silently
        } catch (Exception e) {
            Log.e(TAG, "Message parse error", e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.w(TAG, "⚠️ [WS] Closed: code=" + code + " remote=" + remote);
        if (heartbeatTimer != null) {
            heartbeatTimer.cancel();
            heartbeatTimer = null;
        }
        // Only reconnect if the server dropped us (remote=true) or it was abnormal.
        // If WE shut it down (remote=false, code=1000), a new connection is already starting.
        if (remote && onDisconnected != null) {
            onDisconnected.run();
        }
    }

    @Override
    public void onError(Exception ex) {
        Log.e(TAG, "WS Error: " + ex.getMessage());
        // Don't show Toast here — too noisy on transient errors
    }

    public void sendEvent(String commandId, String step, String status, String message) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "EVENT");
            event.put("device_id", deviceId);
            event.put("command_id", commandId);
            event.put("step", step);
            event.put("status", status);
            event.put("message", message);
            if (isOpen()) send(event.toString());
        } catch (Exception e) {
            Log.e(TAG, "Event send failed", e);
        }
    }

    public void shutdown() {
        if (heartbeatTimer != null) { heartbeatTimer.cancel(); heartbeatTimer = null; }
        if (!isClosed()) close();
    }
}
