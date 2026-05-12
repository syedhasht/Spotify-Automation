package com.example.project2;

import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import android.graphics.Rect;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;

public class SpotifyNavigator {
    private final MyAccessibilityService service;
    private static final String TAG = "SpotifyBot";

    public SpotifyNavigator(MyAccessibilityService service) {
        this.service = service;
    }

    public boolean selectFilter(String filterName) {
        String tag = "[FILTER][" + filterName.toUpperCase() + "]";
        Log.i(TAG, tag + "[SEARCHING]");

        // STEP 1: Wait for filter container to exist AND be populated
        if (!waitForFilterContainer()) {
            Log.w(TAG, tag + "[CONTAINER_NEVER_READY]");
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                try { Thread.sleep(500); } catch (Exception ignored) {}
                continue;
            }

            // FIND THE COMPOSE CONTAINER FIRST
            AccessibilityNodeInfo filterRow = findNodeByResId(root, "com.spotify.music:id/filter_compose");
            
            Log.d(TAG, tag + "[DFS_SCAN_START]");
            AccessibilityNodeInfo targetNode = findFilterNode(filterRow != null ? filterRow : root, filterName);

            if (targetNode == null) {
                Log.w(TAG, tag + "[TEXT_NOT_FOUND] attempt " + (attempt + 1));
                if (filterRow != null) filterRow.recycle();
                root.recycle();
                try { Thread.sleep(800); } catch (Exception ignored) {}
                continue;
            }
            Log.i(TAG, tag + "[TEXT_FOUND]");

            // Ancestor-Climbing Click Engine (8 levels)
            boolean clicked = false;
            AccessibilityNodeInfo current = targetNode;
            int depth = 0;

            while (current != null && depth < 8) {
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, tag + "[CLICK_ATTEMPT][depth=" + depth + "][result=" + clicked + "]");
                
                if (clicked) break;
                
                AccessibilityNodeInfo parent = current.getParent();
                // Careful not to recycle our main root or nodes we need
                current = parent;
                depth++;
            }

            // Gesture Fallback
            if (!clicked) {
                Log.w(TAG, tag + "[ACTION_CLICK_FAILED] Trying gesture fallback...");
                clicked = tapNodeCenter(targetNode);
            }

            if (filterRow != null) filterRow.recycle();
            root.recycle();

            if (clicked) {
                Log.i(TAG, tag + "[CLICK_OK]");
                try { Thread.sleep(1500); } catch (Exception ignored) {} // stabilization gate
                Log.i(TAG, tag + "[ACTIVE]");
                return true;
            } else {
                Log.e(TAG, tag + "[CLICK_FAILED]");
                return false;
            }
        }

        Log.e(TAG, filterName.toUpperCase() + "_FILTER_NOT_FOUND");
        Log.i(TAG, "[FALLBACK][FAILED] " + filterName.toUpperCase() + "_FILTER_NOT_FOUND");
        return false;
    }

    private boolean waitForFilterContainer() {
        for (int i = 0; i < 10; i++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) continue;

            AccessibilityNodeInfo container = findNodeByResId(root, "com.spotify.music:id/filter_compose");
            
            // Check if container exists AND is populated (hydrated)
            boolean ready = (container != null && container.getChildCount() >= 3);
            
            if (container != null) container.recycle();
            root.recycle();

            if (ready) {
                Log.i(TAG, "[FILTER][CONTAINER_READY_AND_POPULATED]");
                return true;
            }
            try { Thread.sleep(500); } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean hasNodeByCriteria(AccessibilityNodeInfo node, String text, String desc, String resId) {
        if (node == null) return false;
        
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().trim().equalsIgnoreCase(text)) return true;
        
        CharSequence nodeDesc = node.getContentDescription();
        if (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(desc.toLowerCase())) return true;
        
        String nodeResId = node.getViewIdResourceName();
        if (nodeResId != null && nodeResId.equals(resId)) return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasNodeByCriteria(node.getChild(i), text, desc, resId)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findFilterNode(AccessibilityNodeInfo root, String filterName) {
        if (root == null) return null;
        
        String target = filterName.toLowerCase();
        
        CharSequence nodeText = root.getText();
        if (nodeText != null) {
            String cleanText = nodeText.toString().trim().toLowerCase();
            Log.v(TAG, "[FILTER][NODE_TEXT] " + cleanText);
            // Multi-signal matching: exact, plural, or contains
            if (cleanText.equals(target) || 
                cleanText.equals(target + "s") || 
                (target.endsWith("s") && cleanText.equals(target.substring(0, target.length()-1))) ||
                cleanText.contains(target)) {
                return root;
            }
        }
        
        CharSequence nodeDesc = root.getContentDescription();
        if (nodeDesc != null) {
            String cleanDesc = nodeDesc.toString().trim().toLowerCase();
            Log.v(TAG, "[FILTER][NODE_DESC] " + cleanDesc);
            if (cleanDesc.contains(target)) return root;
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findFilterNode(root.getChild(i), filterName);
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByResId(AccessibilityNodeInfo node, String resId) {
        if (node == null) return null;
        String nodeResId = node.getViewIdResourceName();
        if (nodeResId != null && nodeResId.equals(resId)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByResId(node.getChild(i), resId);
            if (result != null) return result;
        }
        return null;
    }

    private void logAllNodesUnder(AccessibilityNodeInfo node, int depth) {
        if (node == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("[").append(node.getClassName()).append("]");
        sb.append(" text=").append(node.getText());
        sb.append(" desc=").append(node.getContentDescription());
        Log.d(TAG, "[COMPOSE_DEBUG]" + sb.toString());
        
        for (int i = 0; i < node.getChildCount(); i++) {
            logAllNodesUnder(node.getChild(i), depth + 1);
        }
    }

    public boolean tapNodeCenter(AccessibilityNodeInfo node) {

        if (node == null) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean success = tap(bounds.centerX(), bounds.centerY());
        if (success) {
            Log.i(TAG, "[SYS][SAFE_CLICK_EXECUTED] Node center tapped.");
        }
        return success;
    }

    private boolean isSafeGesture(int y) {
        if (y < 150) {
            Log.w(TAG, "[SYS][GESTURE_BLOCKED] unsafe_region (y=" + y + ")");
            service.logEvent("SYS", "GESTURE", "BLOCKED", "unsafe_region");
            return false;
        }
        
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "[SYS][GESTURE_BLOCKED] no_active_window");
            return false;
        }
        CharSequence pkg = root.getPackageName();
        if (pkg == null || !pkg.toString().equals("com.spotify.music")) {
            Log.w(TAG, "[SYS][GESTURE_BLOCKED] not_spotify (pkg=" + pkg + ")");
            service.logEvent("SYS", "GESTURE", "BLOCKED", "wrong_package");
            root.recycle();
            return false;
        }
        root.recycle();
        
        Log.i(TAG, "[SYS][PACKAGE_VERIFY_OK]");
        return true;
    }

    public boolean swipeUp() {
        Log.i(TAG, "[GESTURE][SWIPE_UP]");
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;

        android.util.DisplayMetrics metrics = service.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        
        float startY = height * 0.8f;
        float endY = height * 0.2f;

        if (!isSafeGesture((int)startY) || !isSafeGesture((int)endY)) {
            return false;
        }

        Path path = new Path();
        path.moveTo(width / 2f, startY);
        path.lineTo(width / 2f, endY);

        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 500);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        return service.dispatchGesture(builder.build(), null, null);
    }

    public boolean tap(int x, int y) {
        Log.i(TAG, "[GESTURE][TAP] coords: (" + x + ", " + y + ")");

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false;

        if (!isSafeGesture(y)) {
            return false;
        }

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 100);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        return service.dispatchGesture(builder.build(), null, null);
    }

    private AccessibilityNodeInfo findNodeByExactText(AccessibilityNodeInfo root, String text) {
        if (root == null) return null;
        
        // Check Text
        CharSequence nodeText = root.getText();
        if (nodeText != null && nodeText.toString().equalsIgnoreCase(text)) return root;
        
        // Check Content Description (Step 2 requirement)
        CharSequence nodeDesc = root.getContentDescription();
        if (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(text.toLowerCase())) return root;
        
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByExactText(root.getChild(i), text);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * INTERRUPTION HANDLER: Detects and dismisses "Play on a speaker?" popups.
     * 
     * @return true if an interruption was detected and handled.
     */
    public boolean checkAndDismissInterruptions() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        boolean detected = false;

        // Challenge 1: Login Gate
        if (findNodeByExactText(root, "Log in") != null || findNodeByExactText(root, "Sign up") != null) {
            root.recycle();
            throw new AutomationAbortException("LOGIN_REQUIRED");
        }

        // Challenge 2: Network Offline
        if (findNodeByExactText(root, "No connection") != null) {
            root.recycle();
            throw new AutomationAbortException("NETWORK_OFFLINE");
        }

        // Challenge 3: First-run Prompts (Permissions)
        AccessibilityNodeInfo allowBtn = findNodeByExactText(root, "Allow");
        if (allowBtn != null && allowBtn.isClickable()) {
            Log.i(TAG, "[INTERRUPTION][PERMISSION_PROMPT] Dismissing");
            allowBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            allowBtn.recycle();
            detected = true;
        }

        // Challenge 4: Ads
        AccessibilityNodeInfo closeBtn = findNodeByExactText(root, "Close");
        if (closeBtn == null) closeBtn = findNodeByExactText(root, "Dismiss");
        if (closeBtn != null) {
            if (closeBtn.isClickable()) {
                Log.i(TAG, "[INTERRUPTION][AD] Dismissing");
                closeBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else {
                Log.w(TAG, "[INTERRUPTION][AD] Waiting 30s for ad to expire...");
                try { Thread.sleep(30000); } catch (Exception ignored) {}
            }
            closeBtn.recycle();
            detected = true;
        }

        // Primary: Text detection
        if (findNodeByText(root, "Play on a speaker?") != null) {
            detected = true;
        }
        // Secondary: Resource ID detection
        else if (findNodeByResourceId(root, "com.spotify.music:id/slate_footer_container") != null) {
            detected = true;
        }

        if (detected) {
            Log.i(TAG, "[INTERRUPTION][DEVICE_PICKER][DETECTED]");

            int attempts = 0;
            while (attempts < 2) {
                Log.i(TAG, "[INTERRUPTION][DEVICE_PICKER][BACK_SENT]");
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                attempts++;

                try {
                    Thread.sleep(800);
                } catch (Exception ignored) {
                }

                // Verify dismissal
                AccessibilityNodeInfo newRoot = service.getRootInActiveWindow();
                if (newRoot != null) {
                    boolean stillThere = findNodeByText(newRoot, "Play on a speaker?") != null ||
                            findNodeByResourceId(newRoot, "com.spotify.music:id/slate_footer_container") != null;
                    newRoot.recycle();
                    if (!stillThere) {
                        Log.i(TAG, "[INTERRUPTION][DEVICE_PICKER][DISMISSED]");
                        Log.i(TAG, "[AUTOMATION][RESUME]");
                        root.recycle();
                        return true;
                    }
                }
            }
            Log.e(TAG, "DEVICE_PICKER_DISMISS_FAILED");
        }

        root.recycle();
        return false;
    }

    public AccessibilityNodeInfo findNodeByResourceId(AccessibilityNodeInfo root, String resId) {
        if (root == null)
            return null;
        if (resId.equals(root.getViewIdResourceName()))
            return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByResourceId(root.getChild(i), resId);
            if (result != null)
                return result;
        }
        return null;
    }

    public boolean navigateTo(String tab) {
        int retries = 3;
        while (retries > 0) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                retries--;
                try {
                    Thread.sleep(800);
                } catch (Exception e) {
                }
                continue;
            }

            AccessibilityNodeInfo targetNode = findNodeByDescription(root, tab);
            if (targetNode == null) {
                // FALLBACKS: Handle dynamic labels ("Your Library, Tab 3" vs "Library" vs "Search")
                if (tab.toLowerCase().contains("library")) {
                    targetNode = findNodeByDescription(root, "Your Library");
                    if (targetNode == null) targetNode = findNodeByDescription(root, "Library");
                    if (targetNode == null) targetNode = findNodeByText(root, "Library");
                    
                    // Specific logging for Playlist Auto
                    service.logEvent("PLAYLIST_AUTO", "LIBRARY_OPEN", "START", "Targeting Library tab");
                } else if (tab.toLowerCase().contains("search")) {
                    targetNode = findNodeByDescription(root, "Search");
                    if (targetNode == null) targetNode = findNodeByText(root, "Search");
                }
            }
            
            if (targetNode != null) {
                String safeLogName = tab.replace(", ", "_").toUpperCase();
                Log.i(TAG, "[NAVIGATION][" + safeLogName + "][FOUND]");
                service.logEvent("NAV", safeLogName, "FOUND", "Target node detected");

                if (clickNodeOrParent(targetNode)) {
                    Log.i(TAG, "[NAVIGATION][" + safeLogName + "][CLICK_OK]");

                    // Wait for stabilization gate
                    if (waitForScreenToStabilize(tab, 4000)) {
                        Log.i(TAG, "[NAVIGATION][" + safeLogName + "_SCREEN][VERIFIED]");
                        if (tab.toLowerCase().contains("library")) {
                            service.logEvent("PLAYLIST_AUTO", "LIBRARY_READY", "SUCCESS", "Library screen loaded");
                        }
                        root.recycle();
                        return true;
                    }
                }
            }

            Log.w(TAG, "Retrying navigation to " + tab + "... (" + retries + " left)");
            if (root != null)
                root.recycle();
            
            // Refresh root window before retry
            service.waitForIdle();
            
            retries--;
            try {
                Thread.sleep(1200);
            } catch (Exception e) {
            }

        }

        String safeLogName = tab.replace(", ", "_").toUpperCase();
        Log.e(TAG, safeLogName + "_NOT_FOUND");
        service.logEvent("NAV", safeLogName, "NOT_FOUND", "Failed after " + 3 + " retries.");
        
        AccessibilityNodeInfo dumpRoot = service.getRootInActiveWindow();
        if (dumpRoot != null) {
            service.dumpNode(dumpRoot, 0);
            // dumpRoot is recycled inside dumpNode
        }
        
        return false;

    }

    private boolean waitForScreenToStabilize(String tab, int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if ("search, tab".equals(tab) && isSearchScreenVisible()) {
                return true;
            }
            // Fallback for other tabs: just standard wait
            if (!"search, tab".equals(tab)) {
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                }
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (Exception e) {
            }
        }
        return false;
    }

    public boolean isSearchScreenVisible() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        boolean found = findNodeByText(root, "What do you want to listen to?") != null ||
                findNodeByText(root, "What do you want to listen to") != null ||
                findNodeByClass(root, "android.widget.EditText") != null ||
                findNodeByResId(root, "com.spotify.music:id/find_search_field") != null;

        root.recycle();
        return found;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        AccessibilityNodeInfo current = node;
        while (current != null && !current.isClickable()) {
            current = current.getParent();
        }

        if (current != null) {
            return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    public AccessibilityNodeInfo findNodeByDescription(AccessibilityNodeInfo node, String target) {
        if (node == null)
            return null;

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase().contains(target.toLowerCase())) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByDescription(node.getChild(i), target);
            if (result != null)
                return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByClass(AccessibilityNodeInfo root, String className) {
        if (root == null)
            return null;
        if (className.equals(String.valueOf(root.getClassName()))) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByClass(root.getChild(i), className);
            if (result != null)
                return result;
        }
        return null;
    }

    public AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo root, String query) {
        if (root == null)
            return null;
        CharSequence text = root.getText();
        if (text != null && text.toString().toLowerCase().contains(query.toLowerCase())) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByText(root.getChild(i), query);
            if (result != null)
                return result;
        }
        return null;
    }
    public boolean scrollDown() {
        Log.i(TAG, "[NAVIGATION][SCROLL_DOWN]");
        int width = service.getResources().getDisplayMetrics().widthPixels;
        int height = service.getResources().getDisplayMetrics().heightPixels;

        Path path = new Path();
        path.moveTo(width / 2f, height * 0.8f);
        path.lineTo(width / 2f, height * 0.2f);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 100, 500));
        return service.dispatchGesture(builder.build(), null, null);
    }

    /** Swipes finger downward — content scrolls DOWN, revealing content that was ABOVE (e.g. playlist header). */
    public boolean swipeDown() {
        Log.i(TAG, "[GESTURE][SWIPE_DOWN]");
        int width = service.getResources().getDisplayMetrics().widthPixels;
        int height = service.getResources().getDisplayMetrics().heightPixels;

        float startY = height * 0.2f;
        float endY   = height * 0.8f;

        Path path = new Path();
        path.moveTo(width / 2f, startY);
        path.lineTo(width / 2f, endY);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 100, 500));
        return service.dispatchGesture(builder.build(), null, null);
    }
}
