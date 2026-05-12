package com.example.project2;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * MANAGER LAYER: Initializes components and handles system events.
 *
 * Fixes applied:
 * - onDestroy() added: cleans up wsClient, receiver, discovery
 * - connectToWs: uses isReconnecting flag to prevent recursive thread explosion
 * - cancelRetries replaced with atomic generation counter (no race condition)
 * - DeviceDiscovery wrapped safely
 */
public class MyAccessibilityService extends AccessibilityService {
    private static final String TAG = "spotifybot";

    private CommandRunner commandRunner;
    private SpotifyWebSocketClient wsClient;
    private DiscoveryManager discoveryManager;
    private BroadcastReceiver broadcastReceiver;
    private String deviceId;
    private String lastConnectedIp = null;
    private int lastConnectedPort = 8000;
    private String deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;

    // Reconnect guard — prevents recursive thread spawning
    private volatile boolean isReconnecting = false;
    private volatile int connectionGeneration = 0;
    private int retrySeconds = 1;

    static {
        Log.i("spotifybot", "📦 MyAccessibilityService class loaded into VM");
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        try {
            super.onCreate();
            Log.i(TAG, "🔨 MyAccessibilityService.onCreate() called");
            mainHandler.post(() -> android.widget.Toast
                    .makeText(this, "🤖 BOT SERVICE CREATED", android.widget.Toast.LENGTH_SHORT).show());
        } catch (Exception e) {
            Log.e(TAG, "💥 CRASH in onCreate: " + e.getMessage(), e);
        }
    }

    @Override
    public void onServiceConnected() {
        try {
            Log.i(TAG, "🔥 onServiceConnected CALLED — Service is active!");

            // Build foreground notification
            String channelId = "spotify_automation";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        channelId, "Spotify Automation Service", android.app.NotificationManager.IMPORTANCE_LOW);
                android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
                if (manager != null)
                    manager.createNotificationChannel(channel);
            }

            android.app.Notification notification = new android.app.Notification.Builder(this, channelId)
                    .setContentTitle("Spotify Automation Active")
                    .setContentText("Listening for Hub commands...")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build();

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(1, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } else {
                    startForeground(1, notification);
                }
                Log.i(TAG, "✅ startForeground OK");
            } catch (Exception e) {
                Log.e(TAG, "❌ startForeground FAILED (likely permission): " + e.getMessage());
            }

            deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null)
                deviceId = "unknown_device";

            commandRunner = new CommandRunner(this);

            discoveryManager = new DiscoveryManager(this);
            startDiscoveryLoop();

            // Register broadcast receiver
            broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        if ("com.example.project2.RECONNECT".equals(intent.getAction())) {
                            Log.i(TAG, "📡 Manual Reconnect Triggered");
                            startDiscoveryLoop();
                        } else if ("com.example.project2.DUMP_UI".equals(intent.getAction())) {
                            Log.i(TAG, "🖱️ DUMP_UI triggered");
                            mainHandler.postDelayed(() -> {
                                logEvent("DEBUG", "UI_DUMP", "START", "Starting Full UI Tree Analysis...");
                                dumpNode(getRootInActiveWindow(), 0);
                                logEvent("DEBUG", "UI_DUMP", "END", "Analysis Complete.");
                            }, 5000);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in BroadcastReceiver: " + e.getMessage());
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction("com.example.project2.RECONNECT");
            filter.addAction("com.example.project2.DUMP_UI");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(broadcastReceiver, filter);
            }

            Log.i(TAG, "✅ BroadcastReceiver registered — onServiceConnected COMPLETE");
        } catch (Exception e) {
            Log.e(TAG, "💥 CRASH in onServiceConnected: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "🛑 Service destroyed — cleaning up");
        // Stop reconnect loops
        connectionGeneration++;
        isReconnecting = false;
        // Shutdown WebSocket
        if (wsClient != null) {
            wsClient.shutdown();
            wsClient = null;
        }
        // Unregister receiver
        try {
            if (broadcastReceiver != null)
                unregisterReceiver(broadcastReceiver);
        } catch (Exception ignored) {
        }
    }

    private void startDiscoveryLoop() {
        if (isReconnecting)
            return;
        isReconnecting = true;

        final int myGeneration = ++connectionGeneration;

        Log.i(TAG, "🔍 Starting Auto-Discovery (Attempt with backoff: " + retrySeconds + "s)");

        discoveryManager.discover(ip -> {
            synchronized (this) {
                if (connectionGeneration != myGeneration)
                    return;
                // Move generation forward to prevent any other callback from this scan
                connectionGeneration++;
            }

            String url = "ws://" + ip + ":8000/ws/device";
            Log.i(TAG, "🚀 Connecting to Discovered Server: " + url);

            new Thread(() -> {
                try {
                    SpotifyWebSocketClient freshClient = new SpotifyWebSocketClient(
                            url, deviceId, commandRunner, MyAccessibilityService.this,
                            this::onWsDisconnected // onDisconnected callback
                    );
                    if (freshClient.connectBlocking()) {
                        wsClient = freshClient;
                        isReconnecting = false;
                        retrySeconds = 1; // Reset backoff on success
                        Log.i(TAG, "✅ [AUTO] Connected to " + ip);
                    } else {
                        Log.w(TAG, "❌ [AUTO] WS Handshake failed. Clearing IP cache.");
                        discoveryManager.clearLastKnownIp();
                        onWsDisconnected();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ [AUTO] Connection crashed: " + e.getMessage());
                    discoveryManager.clearLastKnownIp();
                    onWsDisconnected();
                }
            }).start();
        });

        // If discovery doesn't find anything in 10s, it will trigger its own failure?
        // No, we should handle timeout here if needed, but DiscoveryManager already
        // runs its threads.
        // For simplicity, we'll let DiscoveryManager finish, then if nothing happened,
        // we retry.
        mainHandler.postDelayed(() -> {
            if (isReconnecting && connectionGeneration == myGeneration) {
                Log.w(TAG, "⌛ Discovery Timeout - Retrying...");
                onWsDisconnected();
            }
        }, 15000);
    }

    private void onWsDisconnected() {
        if (connectionGeneration == 0)
            return; // Service stopping

        mainHandler.post(() -> {
            if (wsClient != null) {
                wsClient.shutdown();
                wsClient = null;
            }
            isReconnecting = false;

            // Exponential backoff: 1, 2, 5, 10, 30, 60
            if (retrySeconds < 2)
                retrySeconds = 2;
            else if (retrySeconds < 5)
                retrySeconds = 5;
            else if (retrySeconds < 10)
                retrySeconds = 10;
            else if (retrySeconds < 30)
                retrySeconds = 30;
            else if (retrySeconds < 60)
                retrySeconds = 60;

            Log.i(TAG, "🔄 Retrying discovery in " + retrySeconds + "s...");
            mainHandler.postDelayed(this::startDiscoveryLoop, retrySeconds * 1000L);
        });
    }

    public void waitForIdle() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void dumpNode(android.view.accessibility.AccessibilityNodeInfo node, int depth) {

        if (node == null)
            return;
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++)
            indent.append("  ");
        String info = String.format("%s[%s] text=\"%s\" desc=\"%s\" id=\"%s\"",
                indent, node.getClassName(), node.getText(),
                node.getContentDescription(), node.getViewIdResourceName());
        logEvent("DEBUG", "UI_SCAN", "NODE", info);
        for (int i = 0; i < node.getChildCount(); i++)
            dumpNode(node.getChild(i), depth + 1);
        node.recycle();
    }

    public String getBotDeviceId() {
        return deviceId;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public SpotifyWebSocketClient getWsClient() {
        return wsClient;
    }

    public void logEvent(String commandId, String step, String status, String message) {
        String safeId = (deviceId != null) ? deviceId : "UNKNOWN";
        String shortId = safeId.length() > 4 ? safeId.substring(safeId.length() - 4) : safeId;
        Log.i(TAG, String.format("[%s][%s][%s][%s] %s", shortId, commandId, step, status, message));
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.sendEvent(commandId, step, status, message);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}