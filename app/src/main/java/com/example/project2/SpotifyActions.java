package com.example.project2;

import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import android.util.Log;
import android.graphics.Rect;
import java.util.List;

public class SpotifyActions {
    private static final String TAG = "SpotifyBot";
    private final MyAccessibilityService service;
    private final SpotifyNavigator navigator;
    private boolean searchAlreadySubmitted = false;
    private String lastSearchQuery = "";

    // Playlist Automation Session State
    private String targetPlaylistName = "";
    private int targetSongsToAdd = 0;
    private int autoLikeCount = 0;

    // === PERSISTENT PLAYLIST CACHE ===
    // Caches discovered playlists across multiple commands to avoid re-scanning
    private static final java.util.Map<String, PlaylistCacheEntry> playlistCache = 
        new java.util.LinkedHashMap<String, PlaylistCacheEntry>(50, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<String, PlaylistCacheEntry> eldest) {
                // Keep cache size manageable; evict oldest accessed entry if over 50 items
                return size() > 50;
            }
        };

    private static class PlaylistCacheEntry {
        String normalizedName;
        long discoveredAt;
        String displayName;

        PlaylistCacheEntry(String normalized, String display) {
            this.normalizedName = normalized;
            this.displayName = display;
            this.discoveredAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            // Cache entry expires after 1 hour
            return System.currentTimeMillis() - discoveredAt > 3600000L;
        }
    }

    public void setAutoLikeCount(int count) {
        this.autoLikeCount = count;
    }

    private void logFallback(String type, String action, String message) {
        String logLine = String.format("[FALLBACK][%s] %s %s", type, action, message).trim();
        Log.i(TAG, logLine);
        // Also pipe it back to the backend EVENT log if possible
        service.logEvent("SYS", "FALLBACK", type, action + " " + message);
    }

    public SpotifyActions(MyAccessibilityService service, SpotifyNavigator navigator) {
        this.service = service;
        this.navigator = navigator;
    }

    public void resetAutomationState() {
        searchAlreadySubmitted = false;
        lastSearchQuery = "";
        targetPlaylistName = "";
        targetSongsToAdd = 0;
        autoLikeCount = 0;
    }

    private String normalize(String text) {
        if (text == null)
            return "";
        return text.toLowerCase()
                .trim()
                .replaceAll("[^a-z0-9\\s]", " ") // Remove special chars
                .replaceAll("\\s+", " ")
                .replaceAll("\\bplaylist\\b", "")
                .replaceAll("\\bspotify\\b", "")
                .trim();
    }

    /**
     * Checks if normalized playlist name matches target with high confidence.
     * Uses exact match or similarity scoring (Levenshtein-like).
     */
    private boolean isPlaylistNameMatch(String discovered, String target) {
        String normDiscovered = normalize(discovered);
        String normTarget = normalize(target);

        if (normDiscovered.isEmpty() || normTarget.isEmpty())
            return false;

        // Exact match after normalization
        if (normDiscovered.equals(normTarget))
            return true;

        // Spotify often exposes playlist rows with extra labels/counts in the same node.
        // A normalized containment match prevents duplicate creation for existing lists.
        if (normDiscovered.contains(normTarget) || normTarget.contains(normDiscovered)) {
            Log.i(TAG, "[MATCH_CONTAINS] discovered=\"" + discovered + "\" target=\"" + target + "\"");
            return true;
        }

        // Similarity scoring: if >= 92% similar, consider it a match
        double similarity = calculateSimilarity(normDiscovered, normTarget);
        if (similarity >= 0.92) {
            Log.i(TAG, "[MATCH_SCORING] discovered=\"" + discovered + "\" target=\"" + target
                    + "\" similarity=" + String.format("%.2f", similarity * 100) + "%");
            return true;
        }

        return false;
    }

    /**
     * Levenshtein-based similarity (0.0 to 1.0).
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null)
            return 0.0;
        if (s1.isEmpty() && s2.isEmpty())
            return 1.0;
        if (s1.isEmpty() || s2.isEmpty())
            return 0.0;

        int maxLen = Math.max(s1.length(), s2.length());
        int distance = levenshteinDistance(s1, s2);
        return 1.0 - ((double) distance / maxLen);
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++)
            dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++)
            dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[s1.length()][s2.length()];
    }

    /**
     * Checks playlist cache for known playlists.
     * Returns true if playlist was previously discovered (cache hit).
     */
    private boolean checkPlaylistCache(String targetPlaylistName) {
        synchronized (playlistCache) {
            for (PlaylistCacheEntry entry : playlistCache.values()) {
                if (entry.isExpired())
                    continue;

                if (isPlaylistNameMatch(entry.displayName, targetPlaylistName)) {
                    Log.i(TAG, "[PLAYLIST_CACHE][HIT] Cached playlist found: " + entry.displayName);
                    service.logEvent("PLAYLIST_AUTO", "CACHE_HIT", "SUCCESS", "playlist=" + entry.displayName);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Records discovered playlist in cache.
     */
    private void addToPlaylistCache(String displayName) {
        String normalized = normalize(displayName);
        synchronized (playlistCache) {
            if (!playlistCache.containsKey(normalized)) {
                playlistCache.put(normalized, new PlaylistCacheEntry(normalized, displayName));
                Log.i(TAG, "[PLAYLIST_CACHE][ADD] Cached: " + displayName);
            }
        }
    }

    private void findTextNodes(AccessibilityNodeInfo node, java.util.List<String> results) {
        if (node == null)
            return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            results.add(text.toString());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findTextNodes(node.getChild(i), results);
        }
    }

    private AccessibilityNodeInfo findClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable())
                return AccessibilityNodeInfo.obtain(current);
            AccessibilityNodeInfo parent = current.getParent();
            current = parent;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null)
            return null;
        if (text.equals(node.getText() != null ? node.getText().toString() : null)) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByText(node.getChild(i), text);
            if (result != null)
                return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByTextRecursive(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null)
            return null;
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.toString().toLowerCase().contains(text.toLowerCase())) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByTextRecursive(node.getChild(i), text);
            if (result != null)
                return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByClass(AccessibilityNodeInfo node, String className) {
        if (node == null || className == null)
            return null;
        if (className.equals(node.getClassName() != null ? node.getClassName().toString() : null)) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByClass(node.getChild(i), className);
            if (result != null)
                return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByDescriptionRecursive(AccessibilityNodeInfo node, String desc) {
        if (node == null || desc == null)
            return null;
        CharSequence nodeDesc = node.getContentDescription();
        if (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(desc.toLowerCase())) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByDescriptionRecursive(node.getChild(i), desc);
            if (result != null)
                return result;
        }
        return null;
    }

    private void findNodesByDescriptionStart(AccessibilityNodeInfo node, String prefix,
            java.util.List<AccessibilityNodeInfo> results) {
        if (node == null || prefix == null)
            return;
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase().startsWith(prefix.toLowerCase())) {
            results.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findNodesByDescriptionStart(node.getChild(i), prefix, results);
        }
    }

    public boolean executeSearch(String query, String filter) {
        String normalizedQuery = query.toLowerCase().trim();

        // 1. REDUNDANCY CHECK: Avoid re-searching if already playing the target
        String nowPlaying = getNowPlayingTitle().toLowerCase().trim();
        if (!nowPlaying.isEmpty() && nowPlaying.contains(normalizedQuery)) {
            Log.i(TAG, "[SEARCH][REDUNDANCY_CHECK] Already playing query. Skipping search.");
            return true;
        }

        // 2. IDEMPOTENCY & STATE LOCK: If already searched and verified, skip typing
        if (searchAlreadySubmitted && normalizedQuery.equals(lastSearchQuery)
                && isSearchAlreadyActive(normalizedQuery, filter)) {
            Log.i(TAG, "[SEARCH][IDEMPOTENT_BYPASS] Already on results screen for: " + normalizedQuery);
            return true;
        }

        Log.i(TAG, "Executing Search. Original: [" + query + "] Cleaned: [" + normalizedQuery + "] Filter: [" + filter
                + "]");

        // Reset state for new search
        searchAlreadySubmitted = false;
        lastSearchQuery = normalizedQuery;

        ensureSpotifyIsForeground();
        navigator.checkAndDismissInterruptions();

        if (!navigator.navigateTo("search, tab")) {
            return false;
        }

        query = normalizedQuery;

        try {
            Thread.sleep(1000);
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        // PHASE 2: Open Search Input Screen
        AccessibilityNodeInfo launcher = findNodeByText(root, "What do you want to listen to?");
        if (launcher != null) {
            Log.i(TAG, "[SEARCH][SEARCH_BAR_CLICKED]");
            service.logEvent("SEARCH", "BAR", "CLICKED", "Launcher container found");
            clickNodeWithParentTraversal(launcher, 3);
            launcher.recycle();
            try {
                Thread.sleep(1200);
            } catch (Exception ignored) {
            } // wait for input screen to open
            Log.i(TAG, "[SEARCH][INPUT_VISIBLE]");
            service.logEvent("SEARCH", "INPUT", "VISIBLE", "Search field active");
        }
        root.recycle();

        // PHASE 3: Find Real Input Field
        root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        AccessibilityNodeInfo searchInput = findSearchField(root);
        if (searchInput == null) {
            Log.e(TAG, "[SEARCH][SEARCH_FIELD_NOT_FOUND]");
            root.recycle();
            return false;
        }

        if (!activateNode(searchInput)) {
            Log.e(TAG, "[SEARCH][FOCUS_FAILED]");
            root.recycle();
            return false;
        }

        // Re-fetch node after focus (Spotify dynamic UI)
        root.recycle();
        try {
            Thread.sleep(800);
        } catch (Exception ignored) {
        }
        root = service.getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo newSearchInput = findSearchField(root);
            if (newSearchInput != null)
                searchInput = newSearchInput;
        }

        if (searchInput == null) {
            Log.e(TAG, "[SEARCH][TEXT_SET_FAILED] lost field after focus");
            if (root != null)
                root.recycle();
            return false;
        }

        searchInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

        if (!setTextWithVerification(searchInput, query)) {
            Log.e(TAG, "[SEARCH][TEXT_SET_FAILED] verification failure");
            if (root != null)
                root.recycle();
            return false;
        }
        Log.i(TAG, "[SEARCH][TEXT_VERIFIED]");
        service.logEvent("SEARCH", "TEXT", "VERIFIED", query);

        searchAlreadySubmitted = true;

        // STEP 5: WAIT FOR UI STABILITY
        if (waitForResultsStability(5000)) {
            Log.i(TAG, "[SEARCH][RESULTS_SCREEN_CONFIRMED]");

            // Phase B: FILTER ONLY

            if (filter != null && !filter.isEmpty()) {
                if (navigator.selectFilter(filter)) {
                    Log.i(TAG, "[FILTER][" + filter.toUpperCase() + "][ACTIVE]");
                    // WAIT for list to refresh after filter (Crucial to avoid stale results)
                    try {
                        Thread.sleep(1500);
                    } catch (Exception ignored) {
                    }
                }
            }
            return true;
        }

        Log.e(TAG, "[SEARCH][RESULTS_NOT_VISIBLE]");
        return false;
    }

    private boolean waitForResultsStability(int timeoutMs) {
        Log.i(TAG, "[SEARCH][UI_STABILIZING]");
        long start = System.currentTimeMillis();
        int stabilityCount = 0;
        String lastTreeInfo = "";

        while (System.currentTimeMillis() - start < timeoutMs) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                try {
                    Thread.sleep(300);
                } catch (Exception ignored) {
                }
                continue;
            }

            // Check for valid results indicators
            boolean hasResults = hasText(root, "Songs") || hasText(root, "Artists") ||
                    hasText(root, "Albums") || hasText(root, "Playlists");

            // Simple hash of the current screen to detect stability
            String currentTreeInfo = root.getClassName() + ":" + root.getChildCount();

            if (hasResults && currentTreeInfo.equals(lastTreeInfo)) {
                stabilityCount++;
            } else {
                stabilityCount = 0;
            }

            lastTreeInfo = currentTreeInfo;
            root.recycle();

            if (stabilityCount >= 2) {
                Log.i(TAG, "[SEARCH][UI_STABLE]");
                return true;
            }

            try {
                Thread.sleep(500);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean isSearchAlreadyActive(String query, String filter) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        try {
            // Check if search field has the text
            AccessibilityNodeInfo searchInput = findSearchField(root);
            if (searchInput != null) {
                CharSequence text = searchInput.getText();
                boolean queryMatches = (text != null && text.toString().toLowerCase().contains(query));
                searchInput.recycle();

                if (queryMatches) {
                    // Check if we have results or if filter is active
                    if (filter == null || filter.isEmpty())
                        return true;
                    return isFilterActive(root, filter);
                }
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private boolean isFilterActive(AccessibilityNodeInfo root, String filter) {
        if (root == null || filter == null)
            return false;
        // Simple heuristic: if filter text is found and it looks selected
        AccessibilityNodeInfo filterNode = findNodeByText(root, filter);
        if (filterNode != null) {
            boolean active = filterNode.isSelected() || filterNode.isFocused();
            filterNode.recycle();
            return active;
        }
        return false;
    }

    private void triggerSearchSubmission(AccessibilityNodeInfo node) {
        if (node == null)
            return;
        Log.i(TAG, "[SEARCH][SUBMIT_TRIGGERED]");

        // Try modern IME_ENTER (API 30+)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }

        // Fallback 1: Click the node (some Spotify versions trigger on focus/click)
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);

        // Better: Use Navigator to dispatch a KeyEvent if possible.
        // For now, let's use a small delay and a second click.
        try {
            Thread.sleep(500);
        } catch (Exception ignored) {
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean confirmResultsScreen(int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                try {
                    Thread.sleep(300);
                } catch (Exception ignored) {
                }
                continue;
            }

            // STRICT SIGNALS: Home Page Detection
            boolean isHome = hasText(root, "Browse all") || hasText(root, "What do you want to listen to?");
            if (isHome) {
                Log.w(TAG, "[SEARCH][HOME_SCREEN_DETECTED] Resubmitting...");
                // Find search field and try submit again
                AccessibilityNodeInfo field = findSearchField(root);
                if (field != null) {
                    triggerSearchSubmission(field);
                    field.recycle();
                }
                root.recycle();
                try {
                    Thread.sleep(800);
                } catch (Exception ignored) {
                }
                continue;
            }

            // VALID SIGNALS: Results Page Detection
            boolean isResults = hasText(root, "Songs") || hasText(root, "Artists") || hasText(root, "Albums");
            root.recycle();

            if (isResults)
                return true;
            try {
                Thread.sleep(500);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        if (root == null)
            return false;
        CharSequence nodeText = root.getText();
        if (nodeText != null && nodeText.toString().equalsIgnoreCase(text))
            return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (hasText(root.getChild(i), text))
                return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findSearchField(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        // Ensure the node is actually capable of receiving input
        boolean isEditableClass = "android.widget.EditText".equals(node.getClassName());
        boolean hasSetTextAction = node.getActionList()
                .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT);

        if ((node.isEditable() || isEditableClass || hasSetTextAction) && node.isFocusable() && node.isEnabled()) {
            // Also check for common resource IDs just in case
            String resId = node.getViewIdResourceName();
            if (resId != null && (resId.contains("find_search_field") || resId.contains("query"))) {
                return AccessibilityNodeInfo.obtain(node);
            }
            // Return any editable node that is focusable and enabled
            return AccessibilityNodeInfo.obtain(node);
        }

        // DFS Search
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findSearchField(node.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean activateNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            boolean focusOk = current.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean clickOk = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (focusOk || clickOk) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean setTextWithVerification(AccessibilityNodeInfo initialNode, String text) {
        return setTextWithVerificationInternal(initialNode, text, true);
    }

    private boolean setTextOnly(AccessibilityNodeInfo initialNode, String text) {
        return setTextWithVerificationInternal(initialNode, text, false);
    }

    private boolean setTextWithVerificationInternal(AccessibilityNodeInfo initialNode, String text, boolean submit) {
        if (initialNode == null)
            return false;

        for (int cycle = 1; cycle <= 3; cycle++) {
            Log.i(TAG, "[SEARCH][INPUT_CYCLE] attempt=" + cycle);

            // 1. Refetch Node (Stale Node Recovery)
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            AccessibilityNodeInfo activeNode = (root != null) ? findSearchField(root) : null;
            if (activeNode == null)
                activeNode = initialNode;

            // 2. Refocus Field
            Log.i(TAG, "[SEARCH][FIELD_REFOCUS]");
            if (!activeNode.isFocused()) {
                activeNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            activeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            try {
                Thread.sleep(600);
            } catch (Exception ignored) {
            }

            // 3. Clear Field
            clearFieldAtomic(activeNode);
            try {
                Thread.sleep(400);
            } catch (Exception ignored) {
            }

            // 4. Input Priority
            Log.i(TAG, "[SEARCH][TYPING_START] " + text);
            boolean setOk = false;

            // PRIORITY 1: ACTION_SET_TEXT
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            setOk = activeNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);

            // PRIORITY 2: Accessibility Paste
            if (!setOk) {
                Log.w(TAG, "[SEARCH][PASTE_FALLBACK_USED]");
                setOk = injectViaClipboard(activeNode, text);
            }

            if (setOk) {
                Log.i(TAG, "[SEARCH][TEXT_SET_OK]");
                try {
                    Thread.sleep(1200);
                } catch (Exception ignored) {
                }

                // 5. Global Verification
                if (verifyInputGlobal(text)) {
                    Log.i(TAG, "[SEARCH][TEXT_VERIFY_OK]");
                    if (submit) {
                        Log.i(TAG, "[SEARCH][SUBMIT_TRIGGERED]");
                        triggerSearchSubmission(activeNode);
                    }
                    if (activeNode != initialNode)
                        activeNode.recycle();
                    if (root != null)
                        root.recycle();
                    return true;
                }
            }

            Log.w(TAG, "[SEARCH][STALE_NODE_REFETCH] Verification failed. Recovering...");
            Log.i(TAG, "[SEARCH][INPUT_RECOVERY]");
            if (activeNode != initialNode)
                activeNode.recycle();
            if (root != null)
                root.recycle();
            try {
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }
        }

        Log.e(TAG, "[SEARCH][INPUT_ABANDONED] Typing failed after 3 cycles.");
        return false;

    }

    private boolean verifyInputGlobal(String expected) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        try {
            // Check ANY node in the window for the text
            boolean found = hasText(root, expected);
            if (found) {
                Log.i(TAG, "[SEARCH][GLOBAL_VERIFIED] " + expected);
                return true;
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private void clearFieldAtomic(AccessibilityNodeInfo node) {
        if (node == null)
            return;

        for (int attempt = 0; attempt < 2; attempt++) {
            // Strategy 1: Standard Accessibility Clear
            Bundle clearArgs = new Bundle();
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
            try {
                Thread.sleep(200);
            } catch (Exception ignored) {
            }

            if (isFieldEmpty(node))
                return;

            // Strategy 2: Look for the "X" (Clear) button in siblings
            if (clickClearButton(node)) {
                try {
                    Thread.sleep(300);
                } catch (Exception ignored) {
                }
                if (isFieldEmpty(node))
                    return;
            }

            // Strategy 3: Select All + Cut
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle selectArgs = new Bundle();
            selectArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            selectArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 1000);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs);
            node.performAction(AccessibilityNodeInfo.ACTION_CUT);
            try {
                Thread.sleep(200);
            } catch (Exception ignored) {
            }

            if (isFieldEmpty(node))
                return;
        }
    }

    private boolean isFieldEmpty(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        return text == null || text.length() == 0;
    }

    private boolean clickClearButton(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null)
            return false;

        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo child = parent.getChild(i);
            if (child == null)
                continue;

            CharSequence desc = child.getContentDescription();
            if (desc != null) {
                String d = desc.toString().toLowerCase();
                if (d.contains("clear") || d.contains("delete")) {
                    Log.i(TAG, "[SEARCH][X_BUTTON_FOUND]");
                    boolean ok = child.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    child.recycle();
                    return ok;
                }
            }
            child.recycle();
        }
        return false;
    }

    private AccessibilityNodeInfo findFocusedNode(AccessibilityNodeInfo root) {
        if (root == null)
            return null;
        if (root.isFocused())
            return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo result = findFocusedNode(root.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean injectViaClipboard(AccessibilityNodeInfo node, String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) service
                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("spotify_query", text);
            clipboard.setPrimaryClip(clip);
            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean simulateTyping(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null)
            return false;

        // Ensure field is empty before typing
        clearFieldAtomic(node);
        try {
            Thread.sleep(400);
        } catch (Exception ignored) {
        }

        StringBuilder currentText = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (!node.isFocused()) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                try {
                    Thread.sleep(300);
                } catch (Exception ignored) {
                }
            }
            currentText.append(text.charAt(i));
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    currentText.toString());

            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                return false;
            }
            try {
                Thread.sleep(80);
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    private boolean isKeyboardLikelyVisible() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        // Search for common keyboard package names or window types
        boolean visible = checkRecursiveForKeyboard(root);
        root.recycle();
        return visible;
    }

    private boolean checkRecursiveForKeyboard(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        String pkg = String.valueOf(node.getPackageName());
        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg.contains("gboard")) {
            return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (checkRecursiveForKeyboard(node.getChild(i)))
                return true;
        }
        return false;
    }

    private boolean forceFocus(AccessibilityNodeInfo node) {
        Log.i(TAG, "[SEARCH][FOCUS_CONFIRMATION_LOOP]");
        for (int i = 0; i < 5; i++) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            try {
                Thread.sleep(200);
            } catch (Exception ignored) {
            }

            // Re-fetch node state if possible? No, we check the current object's property
            // first
            if (node.isFocused()) {
                Log.i(TAG, "[SEARCH][NODE_IS_FOCUSED]");
                return true;
            }
        }
        return false;
    }

    private boolean waitForResultsVisible(int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isResultsVisible())
                return true;
            try {
                Thread.sleep(500);
            } catch (Exception e) {
            }
        }
        return false;
    }

    private boolean isResultsVisible() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        boolean found = hasResultsIndicators(root);
        root.recycle();
        return found;
    }

    private boolean hasResultsIndicators(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        CharSequence text = node.getText();
        if (text != null) {
            String t = text.toString().toLowerCase();
            if (t.equals("songs") || t.equals("artists") || t.equals("albums") || t.equals("profiles")) {
                return true;
            }
        }

        if ("androidx.recyclerview.widget.RecyclerView".equals(node.getClassName())) {
            if (node.getChildCount() > 0)
                return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasResultsIndicators(node.getChild(i)))
                return true;
        }
        return false;
    }

    public boolean executeAlbumFlow(String query, int autoLikeCount) {
        Log.i(TAG, "[ALBUM][FLOW_STARTED] query=[" + query + "]");

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        java.util.List<AccessibilityNodeInfo> rows = new java.util.ArrayList<>();
        findNodesByResourceId(root, "com.spotify.music:id/row_root", rows);

        if (rows.isEmpty()) {
            Log.e(TAG, "[ALBUM][MATCH_FAILED] No album rows found");
            root.recycle();
            return false;
        }

        AccessibilityNodeInfo bestMatch = rows.get(0); // Take first result for Album
        Log.i(TAG, "[ALBUM][BEST_MATCH] title=" + extractNodeText(bestMatch, "com.spotify.music:id/title"));

        boolean opened = clickNodeOrParent(bestMatch);
        root.recycle();
        if (!opened)
            return false;

        try {
            Thread.sleep(2500);
        } catch (Exception ignored) {
        }

        // Start playback
        startPlaybackFromHeader("");

        // Perform Batch Actions
        int targetCount = (targetSongsToAdd > 0) ? targetSongsToAdd : (autoLikeCount > 0 ? autoLikeCount : 0);
        if (targetCount > 0) {
            batchAlbumActions(targetCount, autoLikeCount > 0);
        }

        return true;
    }

    private void batchAlbumActions(int targetCount, boolean doLike) {
        Log.i(TAG, "[ALBUM][BATCH_ACTIONS_START] target=" + targetCount);
        int successCount = 0;
        java.util.Set<String> processed = new java.util.HashSet<>();
        int scrollCycles = 0;  // Track cycles for deterministic bounded flow

        while (successCount < targetCount && scrollCycles < 3 && CommandRunner.shouldContinueCurrentTask()) {
            // === COMPLETION LOCK CHECK: Stop if task completed ===
            if (CommandRunner.isCurrentTaskCompleted()) {
                Log.i(TAG, "[ALBUM][BATCH_TERMINATED] Task marked completed. Exiting batch loop.");
                break;
            }
            
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                break;

            java.util.List<AccessibilityNodeInfo> rows = new java.util.ArrayList<>();
            findNodesByResourceId(root, "com.spotify.music:id/row_root", rows);
            if (rows.isEmpty())
                findNodesByResourceId(root, "com.spotify.music:id/recycler_view", rows); // Fallback

            boolean foundAny = false;
            for (AccessibilityNodeInfo row : rows) {
                if (successCount >= targetCount)
                    break;

                String title = extractNodeText(row, "com.spotify.music:id/title");
                if (title == null || title.isEmpty() || processed.contains(title)) {
                    row.recycle();
                    continue;
                }

                Log.i(TAG, "[ALBUM][SONG_ROW_FOUND] track=\"" + title + "\"");
                AccessibilityNodeInfo menuBtn = navigator.findNodeByResourceId(row,
                        "com.spotify.music:id/entity_action_short_row_end_action");
                if (menuBtn == null)
                    menuBtn = findNodeByDescriptionRecursive(row, "More options for song");

                if (menuBtn != null) {
                    Log.i(TAG, "[ALBUM][SONG_MENU_FOUND]");
                    processed.add(title);
                    foundAny = true;

                    if (executeAlbumSongActions(menuBtn, doLike)) {
                        successCount++;
                        Log.i(TAG, "[ALBUM][PROGRESS] " + successCount + "/" + targetCount);
                        
                        // === EARLY TERMINATION: Completed target ===
                        if (successCount >= targetCount) {
                            CommandRunner.lockCompletion();
                            Log.i(TAG, "[ALBUM][TARGET_REACHED] Marking task complete and locked");
                        }
                    }
                    menuBtn.recycle();
                }
                row.recycle();
                if (successCount >= targetCount)
                    break;
            }
            root.recycle();

            if (successCount >= targetCount) {
                Log.i(TAG, "[ALBUM][TARGET_REACHED]");
                break;
            }

            if (!foundAny) {
                Log.i(TAG, "[ALBUM][SCROLLING] Cycle " + (scrollCycles + 1) + "/3");
                navigator.scrollDown();
                scrollCycles++;
                try {
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean executeAlbumSongActions(AccessibilityNodeInfo menuBtn, boolean doLike) {
        Log.i(TAG, "[ALBUM][SONG_MENU_CLICK]");
        clickNodeOrParent(menuBtn);
        try {
            Thread.sleep(1200);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "[ALBUM][SONG_MENU_OPEN]");

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        boolean success = false;

        // 1. LIKE FLOW
        if (doLike) {
            AccessibilityNodeInfo likeOpt = navigator.findNodeByDescription(root, "Add to Liked Songs");
            if (likeOpt == null)
                likeOpt = findNodeByTextRecursive(root, "Add to Liked Songs");

            if (likeOpt != null) {
                Log.i(TAG, "[ALBUM][LIKE_OPTION_FOUND]");
                Log.i(TAG, "[ALBUM][LIKE_OPTION_CLICK]");
                clickNodeOrParent(likeOpt);
                likeOpt.recycle();
                success = true;
                Log.i(TAG, "[ALBUM][SONG_LIKED]");
                try {
                    Thread.sleep(1500);
                } catch (Exception ignored) {
                }

                // If we also need to add to playlist, we might need to RE-OPEN menu
                // But typically users want one or the other per batch.
                // We'll return true here.
            }
        }

        // 2. PLAYLIST FLOW
        if (!targetPlaylistName.isEmpty() && (!doLike || success)) {
            // Re-open if closed by Like action
            if (success) {
                // Wait for list to be back
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
                // We actually need to re-find the row if we want to do both.
                // For now, if we do both, we'll just handle one per iteration.
            } else {
                AccessibilityNodeInfo addOpt = navigator.findNodeByDescription(root, "Add to playlist");
                if (addOpt == null)
                    addOpt = findNodeByTextRecursive(root, "Add to playlist");

                if (addOpt != null) {
                    Log.i(TAG, "[ALBUM][PLAYLIST_OPTION_FOUND]");
                    Log.i(TAG, "[ALBUM][PLAYLIST_OPTION_CLICK]");
                    clickNodeOrParent(addOpt);
                    addOpt.recycle();
                    try {
                        Thread.sleep(1800);
                    } catch (Exception ignored) {
                    }

                    AccessibilityNodeInfo pickerRoot = service.getRootInActiveWindow();
                    if (pickerRoot != null) {
                        AccessibilityNodeInfo targetRow = findNormalizedTextRow(pickerRoot,
                                normalize(targetPlaylistName));
                        if (targetRow != null) {
                            Log.i(TAG, "[ALBUM][TARGET_PLAYLIST_FOUND]");
                            if (clickNodeOrParent(targetRow)) {
                                Log.i(TAG, "[ALBUM][PLAYLIST_SELECTED]");
                                Log.i(TAG, "[ALBUM][SONG_ADDED]");
                                success = true;
                            }
                            targetRow.recycle();
                        }
                        pickerRoot.recycle();
                    }
                    // Return back to album list
                    try {
                        Thread.sleep(1200);
                    } catch (Exception ignored) {
                    }
                    service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                    Log.i(TAG, "[ALBUM][RETURN_TO_TRACKLIST]");
                }
            }
        }

        if (!success) {
            // Close menu if nothing done
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        }

        root.recycle();
        return success;
    }

    public boolean executePlaylistFlow(String query, int autoLikeCount) {
        Log.i(TAG, "[PLAYLIST][FLOW_STARTED] query=[" + query + "] auto_like=" + autoLikeCount);

        String trackBefore = getNowPlayingTitle();
        Log.i(TAG, "[PLAYLIST][TRACK_BEFORE] " + (trackBefore.isEmpty() ? "None" : trackBefore));

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        // STAGE 1: Find Best Playlist
        java.util.List<AccessibilityNodeInfo> rows = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
        if (rows == null || rows.isEmpty()) {
            Log.e(TAG, "[PLAYLIST][ROWS_NOT_FOUND]");
            root.recycle();
            return false;
        }

        AccessibilityNodeInfo bestPlaylist = null;
        int bestScore = -1;
        String normalizedQuery = query.toLowerCase().trim();

        for (AccessibilityNodeInfo row : rows) {
            String title = extractNodeText(row, "com.spotify.music:id/title");
            String subtitle = extractNodeText(row, "com.spotify.music:id/subtitle");
            if (!subtitle.toLowerCase().contains("playlist"))
                continue;

            int score = calculateRelevanceScore(title, subtitle, normalizedQuery);
            if (score > bestScore) {
                bestScore = score;
                bestRowToRecycle(bestPlaylist);
                bestPlaylist = row;
            } else {
                row.recycle();
            }
        }

        if (bestPlaylist == null) {
            root.recycle();
            return false;
        }

        Log.i(TAG, "[PLAYLIST][BEST_MATCH] title=" + extractNodeText(bestPlaylist, "com.spotify.music:id/title"));
        boolean opened = clickNodeOrParent(bestPlaylist);
        bestPlaylist.recycle();

        if (opened) {
            waitForPlaylistContainer(5);
            try {
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }

            Log.i(TAG, "[PLAYLIST_AUTO][PLAYBACK_START]");
            boolean playbackStarted = startPlaybackFromHeader(trackBefore);
            if (!playbackStarted) {
                return false;
            }

            // Optional enhancement actions happen only after playback success.
            if (autoLikeCount > 0) {
                batchLikePlaylistTracks(autoLikeCount);
            }
            if (targetSongsToAdd > 0 && !targetPlaylistName.isEmpty()) {
                batchAddPlaylistTracks(targetSongsToAdd);
            }
            return true;
        }

        root.recycle();
        return false;
    }

    private boolean startPlaybackFromHeader(String trackBefore) {
        Log.i(TAG, "[PLAYBACK][READY_CHECK] Waiting for list container...");
        waitForPlaylistContainer(5);

        Log.i(TAG, "[PLAYBACK][CTA_WAIT] Looking for Play button...");

        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            AccessibilityNodeInfo playBtn = findPlaylistPlayButton(root);

            if (playBtn != null) {
                Log.i(TAG, "[PLAYBACK][FOUND] Clicking Play button");
                boolean ok = clickPlaylistPlayButton(playBtn);
                playBtn.recycle();
                root.recycle();
                if (ok) {
                    try {
                        Thread.sleep(2500);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
            }

            // Use exact match for "Play" to avoid colliding with "Search in playlist" or
            // other descriptions containing 'play'
            playBtn = findNodeByDescriptionExact(root, "Play");
            if (playBtn == null)
                playBtn = findNodeByDescriptionExact(root, "Shuffle play");
            if (playBtn == null)
                playBtn = findNodeByDescriptionExact(root, "Play playlist");

            if (playBtn != null) {
                Log.i(TAG, "[PLAYBACK][FOUND] Clicking Play button");
                boolean ok = clickNodeOrParent(playBtn);
                playBtn.recycle();
                root.recycle();
                if (ok) {
                    try {
                        Thread.sleep(2500);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
            }
            root.recycle();
            Log.w(TAG, "[PLAYBACK][CTA_RETRY] attempt=" + (attempt + 1));
            try {
                Thread.sleep(1200);
            } catch (Exception ignored) {
            }
        }

        Log.w(TAG, "[PLAYBACK][CTA_MISSING] Falling back to first track row click");
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) {
            boolean ok = playFirstTrackInRow(root);
            root.recycle();
            return ok;
        }
        return false;
    }

    private boolean clickPlaylistPlayButton(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        String resId = node.getViewIdResourceName();
        boolean isPlayPauseButton = resId != null && resId.contains("button_play_and_pause");

        if (isPlayPauseButton) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return true;

            return navigator.tapNodeCenter(node);
        }

        return clickNodeWithParentTraversal(node, 3);
    }

    private AccessibilityNodeInfo findNodeByDescriptionExact(AccessibilityNodeInfo node, String desc) {
        if (node == null || desc == null)
            return null;
        CharSequence nodeDesc = node.getContentDescription();
        if (nodeDesc != null && nodeDesc.toString().equalsIgnoreCase(desc)) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findNodeByDescriptionExact(node.getChild(i), desc);
            if (result != null)
                return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findPlayableRowSemantic(AccessibilityNodeInfo node, String query) {
        if (node == null)
            return null;

        // STEP 1: Identify nodes with meaningful text
        CharSequence nodeText = node.getText();
        if (nodeText != null && nodeText.length() > 2) {
            String text = nodeText.toString().toLowerCase().trim();

            // Filter UI keywords
            if (!text.equals("cancel") && !text.equals("filter") && !text.equals("search")) {

                // STEP 2: Verify presence of "3-dot" overflow menu within the SAME interactive
                // container
                AccessibilityNodeInfo clickableRow = findClickableParent(node);
                if (clickableRow != null) {
                    if (hasMoreOptionsDeep(clickableRow)) {
                        Log.i(TAG, "[SEMANTIC][DEEP_ANCHOR_FOUND] text: " + text);
                        return clickableRow;
                    }
                    clickableRow.recycle();
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findPlayableRowSemantic(node.getChild(i), query);
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean hasMoreOptionsDeep(AccessibilityNodeInfo node) {

        if (node == null)
            return false;
        if (isMoreOptionsIndicator(node))
            return true;

        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasMoreOptionsDeep(node.getChild(i)))
                return true;
        }
        return false;
    }

    private boolean waitForPlaylistContainer(int maxSeconds) {
        for (int i = 0; i < maxSeconds; i++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                boolean ready = hasListContainer(root);
                root.recycle();
                if (ready) {
                    Log.i(TAG, "[PLAYLIST][CONTAINER_READY]");
                    return true;
                }
            }
            try {
                Thread.sleep(800);
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "[PLAYLIST][CONTAINER_TIMEOUT] Proceeding with CTA scan anyway...");
        return false;
    }

    private boolean hasListContainer(AccessibilityNodeInfo node) {
        if (node == null)
            return false;
        String className = (node.getClassName() != null) ? node.getClassName().toString() : "";
        if (className.contains("RecyclerView") || className.contains("ComposeView") || node.isScrollable()) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasListContainer(node.getChild(i)))
                return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findPlayButtonScoped(AccessibilityNodeInfo node, int yThreshold) {
        if (node == null)
            return null;

        // STEP 1: CONTEXT LOCK - Never look inside a list container or track row for
        // the PLAY button
        String resId = (node.getViewIdResourceName() != null) ? node.getViewIdResourceName() : "";
        String className = (node.getClassName() != null) ? node.getClassName().toString() : "";

        if (resId.contains("RecyclerView") || resId.contains("row_root") || resId.contains("track_list") ||
                className.contains("RecyclerView") || resId.contains("search_content_elements")) {
            return null; // STOP DFS: Do not enter the list content
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            // STEP 2: BLACKLIST - Explicitly ignore non-play actions that contain the word
            // 'play'
            boolean isBlacklisted = d.contains("search") || d.contains("find") || d.contains("add to") ||
                    d.contains("save") || d.contains("subtitle") || d.contains("artist") ||
                    d.contains("track") || d.contains("more options") || d.contains("miniplayer");

            // STEP 3: HIGH-CONFIDENCE ANCHORS
            boolean isExactCTA = d.equals("play playlist") || d.equals("play album") || d.equals("play");
            boolean isShuffleCTA = d.contains("shuffle play");

            boolean isHeaderRegion = bounds.centerY() < yThreshold || isExactCTA;

            if (isHeaderRegion && (isExactCTA || isShuffleCTA) && !isBlacklisted) {
                // FALLBACK: Also check for specific resource IDs if desc is generic
                if (d.equals("play") && !resId.contains("button_play_and_pause") && !resId.contains("header_layout")) {
                    // This 'play' button might be a generic icon in a row, skip if no ID anchor
                    // (But keep if it's the only thing we have and it's in the header)
                }

                Log.i(TAG, "[PLAYBACK][LOCKED_CTA] desc=" + d + " bounds=" + bounds.toShortString());
                return AccessibilityNodeInfo.obtain(node);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findPlayButtonScoped(node.getChild(i), yThreshold);
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean isMoreOptionsIndicator(AccessibilityNodeInfo node) {
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            if (d.contains("more") || d.contains("options"))
                return true;
        }

        CharSequence text = node.getText();
        if (text != null && text.toString().contains("⋮"))
            return true;

        return false;
    }

    private boolean handleTrackRows(AccessibilityNodeInfo playlistRoot, java.util.List<AccessibilityNodeInfo> trackRows,
            String trackBefore, AccessibilityNodeInfo mainRoot) {
        AccessibilityNodeInfo firstValidRow = null;

        for (AccessibilityNodeInfo row : trackRows) {
            String title = extractNodeText(row, "com.spotify.music:id/title");
            if (title == null || title.length() < 2 ||
                    title.contains("Recommended") || title.contains("More like") ||
                    title.contains("Fans also like")) {
                row.recycle();
                continue;
            }

            if (firstValidRow == null) {
                Log.i(TAG, "[PLAYLIST][FIRST_TRACK] title=" + title);
                firstValidRow = row;
            } else {
                row.recycle();
            }
        }

        if (firstValidRow != null) {
            Rect bounds = new Rect();
            firstValidRow.getBoundsInScreen(bounds);
            boolean clicked = performTripleActionClick(firstValidRow, bounds);
            firstValidRow.recycle();

            if (clicked) {
                Log.i(TAG, "[PLAYLIST][FIRST_TRACK_CLICKED]");
                if (verifyPlaybackWithRetry(playlistRoot, trackBefore)) {
                    playlistRoot.recycle();
                    mainRoot.recycle();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean verifyPlaybackWithRetry(AccessibilityNodeInfo root, String trackBefore) {
        try {
            Thread.sleep(2500);
        } catch (Exception ignored) {
        }

        // Signal 1: Track Change (Highest confidence)
        String trackAfter = getNowPlayingTitle();
        if (!trackBefore.isEmpty() && !trackAfter.equals(trackBefore)) {
            Log.i(TAG, "[PLAYLIST][TRACK_CHANGED] Success!");
            return true;
        }

        // Signal 2: UI Indicators
        AccessibilityNodeInfo currentRoot = service.getRootInActiveWindow();
        boolean verified = verifyPlaybackStarted(currentRoot);
        if (currentRoot != null)
            currentRoot.recycle();

        if (verified) {
            Log.i(TAG, "[PLAYLIST][PLAYBACK_VERIFIED] via UI signals");
            return true;
        }

        Log.e(TAG, "[PLAYLIST][PLAYBACK_VERIFY_FAILED]");
        logFallback("FAILED", "PLAYBACK_DID_NOT_START", "Signals timed out");
        return false;
    }

    private void bestRowToRecycle(AccessibilityNodeInfo node) {
        if (node != null)
            node.recycle();
    }

    private boolean performTripleActionClick(AccessibilityNodeInfo candidate, Rect bounds) {
        // Strategy 1: Standard Action Click
        if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return true;

        // Strategy 2: Recursive Parent Click
        AccessibilityNodeInfo parent = candidate.getParent();
        for (int i = 0; i < 3 && parent != null; i++) {
            if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                parent.recycle();
                return true;
            }
            AccessibilityNodeInfo nextParent = parent.getParent();
            parent.recycle();
            parent = nextParent;
        }

        // Strategy 3: Screen-Space Gesture Tap
        return navigator.tap(bounds.centerX(), bounds.centerY());
    }

    private boolean verifyPlaybackStarted(AccessibilityNodeInfo root) {
        if (root == null)
            return false;

        AccessibilityNodeInfo seekbar = navigator.findNodeByResourceId(root, "com.spotify.music:id/seekbar");
        if (seekbar != null) {
            boolean visible = seekbar.isVisibleToUser();
            seekbar.recycle();
            if (visible)
                return true;
        }

        // Broad check for ANY playback signals (Content Descriptions are most reliable
        // in Compose)
        boolean hasPlayback = hasNodeWithContentDescription(root, "Pause") ||
                hasNodeWithContentDescription(root, "Playing") ||
                hasNodeWithContentDescription(root, "Now playing") ||
                hasNodeWithContentDescription(root, "Connect to a device");

        if (hasPlayback)
            return true;

        // Fallback: check for mini-player presence
        return hasNodeByResId(root, "com.spotify.music:id/now_playing_bar")
                || hasNodeByResId(root, "com.spotify.music:id/now_playing_bar_track_title");
    }

    public boolean isPlaybackActive() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;
        boolean active = verifyPlaybackStarted(root);
        root.recycle();
        return active;
    }

    private boolean isValidTrack(AccessibilityNodeInfo row, String title) {
        if (title.isEmpty() || title.contains("find in playlist") ||
                title.contains("recommended") || title.contains("suggested") ||
                title.contains("sponsored") || title.contains("more like this")) {
            return false;
        }
        return row.isClickable() || (row.getParent() != null && row.getParent().isClickable());
    }

    private AccessibilityNodeInfo findFirstTrackHeuristic(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        // A song row is usually a clickable ViewGroup with children
        if (node.isClickable() && node.getChildCount() >= 2) {
            String title = extractNodeText(node, "com.spotify.music:id/title");
            if (!title.isEmpty() && !title.toLowerCase().contains("find in playlist")) {
                return AccessibilityNodeInfo.obtain(node);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findFirstTrackHeuristic(node.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findMainPlayRecursive(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            if ((d.equals("play") || d.contains("shuffle play")) && node.isClickable()) {
                Rect r = new Rect();
                node.getBoundsInScreen(r);
                // Heuristic: Width > 120, Height > 120, Y < 1200
                if (r.width() > 100 && r.height() > 100 && r.top < 1200) {
                    Log.d(TAG, "[PLAYLIST][PLAY_BUTTON_CANDIDATE] bounds=" + r.toShortString());
                    return AccessibilityNodeInfo.obtain(node);
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findMainPlayRecursive(node.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean playFirstTrackInRow(AccessibilityNodeInfo root) {
        java.util.List<AccessibilityNodeInfo> songRows = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
        if (songRows != null) {
            for (AccessibilityNodeInfo songRow : songRows) {
                String t = extractNodeText(songRow, "com.spotify.music:id/title").toLowerCase();
                if (t.contains("recommended") || t.contains("suggested") || t.contains("sponsored"))
                    continue;

                boolean ok = clickNodeOrParent(songRow);
                for (AccessibilityNodeInfo s : songRows)
                    s.recycle();
                return ok;
            }
            for (AccessibilityNodeInfo s : songRows)
                s.recycle();
        }
        return false;
    }

    private String getNowPlayingTitle() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return "";

        // Mini player track title or main player title
        String title = extractNodeText(root, "com.spotify.music:id/now_playing_bar_track_title");
        if (title.isEmpty()) {
            title = extractNodeText(root, "com.spotify.music:id/track_title");
        }

        root.recycle();
        return title;
    }

    public boolean playFirstResult(String query, int autoLikeCount) {
        Log.i(TAG, "[PLAY][BEST_MATCH_SEARCH] query=[" + query + "]");
        navigator.checkAndDismissInterruptions();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        // STEP 1: Find all result rows
        java.util.List<AccessibilityNodeInfo> rows = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
        if (rows == null || rows.isEmpty()) {
            Log.w(TAG, "[PLAY][ROWS_NOT_FOUND] trying DFS search...");
            rows = new java.util.ArrayList<>();
            findNodesByResourceId(root, "com.spotify.music:id/row_root", rows);

            if (rows.isEmpty()) {
                Log.w(TAG, "[PLAY][DFS_FAILED] trying legacy search...");
                AccessibilityNodeInfo legacy = findFirstClickableResult(root);
                if (legacy != null) {
                    boolean ok = clickNodeOrParent(legacy);
                    root.recycle();
                    return ok;
                }
                root.recycle();
                return false;
            }
        }

        Log.i(TAG, "[PLAY][ROWS_FOUND] count=" + rows.size());

        // STEP 2 & 3: Score all candidates
        AccessibilityNodeInfo bestRow = null;
        int bestScore = -1;
        String bestTitle = "";
        String normalizedQuery = query.toLowerCase().trim();

        for (AccessibilityNodeInfo row : rows) {
            String title = extractNodeText(row, "com.spotify.music:id/title");
            String subtitle = extractNodeText(row, "com.spotify.music:id/subtitle");

            int score = calculateRelevanceScore(title, subtitle, normalizedQuery);
            Log.i(TAG, "[PLAY][CANDIDATE] title=" + title + " subtitle=" + subtitle + " score=" + score);

            if (score > bestScore) {
                bestScore = score;
                bestRow = row;
                bestTitle = title;
            }
        }

        // STEP 5: Pick Best Row
        boolean success = false;
        if (bestRow != null) {
            Log.i(TAG, "[PLAY][BEST_MATCH] title=" + bestTitle + " score=" + bestScore);

            success = clickNodeOrParent(bestRow);
            Log.i(TAG, "[PLAY][CLICK_OK]: " + success);
        }

        // STEP 7: Verify Playback
        if (success) {
            try {
                Thread.sleep(2000);
            } catch (Exception ignored) {
            }
            AccessibilityNodeInfo postRoot = service.getRootInActiveWindow();
            if (verifyPlaybackStarted(postRoot)) {
                Log.i(TAG, "[PLAY][VERIFY_SUCCESS]");
            } else {
                Log.w(TAG, "[PLAY][VERIFY_FAILED] but proceeding...");
            }
            if (postRoot != null)
                postRoot.recycle();
        }

        // Optional enhancement: like from the same result row/container.
        if (success && autoLikeCount > 0) {
            boolean liked = likeTrackFromResultContainer(bestTitle, normalizedQuery);
            Log.i(TAG, "[LIKE][RESULT_CONTAINER] " + (liked ? "SUCCESS" : "OPTIONAL_FAILED"));
        }

        // Cleanup
        for (AccessibilityNodeInfo r : rows)
            r.recycle();
        root.recycle();
        return success;
    }

    public boolean addSearchResultToPlaylist(String query, String targetPlaylist) {
        String normalizedQuery = query == null ? "" : query.toLowerCase().trim();
        for (int attempt = 0; attempt < 3; attempt++) {
            service.waitForIdle();
            try {
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }

            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            AccessibilityNodeInfo bestRow = findBestMatchingRow(root, normalizedQuery);
            if (bestRow == null) {
                root.recycle();
                continue;
            }

            AccessibilityNodeInfo menuBtn = findNodeByDescriptionRecursive(bestRow, "Open context menu for");
            if (menuBtn == null)
                menuBtn = findNodeByDescriptionRecursive(bestRow, "More options");
            if (menuBtn == null)
                menuBtn = findNodeByDescriptionRecursive(bestRow, "options");

            boolean added = false;
            if (menuBtn != null) {
                Log.i(TAG, "[PLAYLIST_ADD][MENU_OPENED]");
                added = addTrackToPlaylistFromMenuAtomic(menuBtn, targetPlaylist);
                menuBtn.recycle();
            }
            bestRow.recycle();
            root.recycle();

            if (added)
                return true;
        }
        return false;
    }

    private boolean addTrackToPlaylistFromMenuAtomic(AccessibilityNodeInfo menuBtn, String targetPlaylist) {
        if (!clickNodeOrParent(menuBtn))
            return false;

        try {
            Thread.sleep(1000);
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo menuRoot = service.getRootInActiveWindow();
        if (menuRoot == null)
            return false;

        AccessibilityNodeInfo addOpt = findNodeByText(menuRoot, "Add to playlist");
        if (addOpt == null)
            addOpt = findNodeByText(menuRoot, "Add to a playlist");
        if (addOpt == null)
            addOpt = findNodeByText(menuRoot, "Save to playlist");
        if (addOpt == null)
            addOpt = findNodeByDescriptionRecursive(menuRoot, "add to playlist");

        if (addOpt == null) {
            menuRoot.recycle();
            return false;
        }

        boolean addClicked = clickNodeOrParent(addOpt);
        addOpt.recycle();
        menuRoot.recycle();
        if (!addClicked)
            return false;

        try {
            Thread.sleep(1500);
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo pickerRoot = service.getRootInActiveWindow();
        if (pickerRoot == null)
            return false;
        AccessibilityNodeInfo targetRow = findNormalizedTextRow(pickerRoot, normalize(targetPlaylist));
        if (targetRow == null) {
            pickerRoot.recycle();
            return false;
        }

        boolean selected = clickNodeOrParent(targetRow);
        targetRow.recycle();
        pickerRoot.recycle();
        if (!selected)
            return false;

        try {
            Thread.sleep(1200);
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo verifyRoot = service.getRootInActiveWindow();
        boolean successSignal = hasAddedToPlaylistSignal(verifyRoot);
        if (verifyRoot != null)
            verifyRoot.recycle();
        return successSignal;
    }

    private boolean hasAddedToPlaylistSignal(AccessibilityNodeInfo node) {
        if (node == null)
            return false;
        String text = lower(node.getText());
        String desc = lower(node.getContentDescription());
        if (text.contains("added to playlist") || text.contains("saved to playlist")
                || desc.contains("added to playlist") || desc.contains("saved to playlist"))
            return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasAddedToPlaylistSignal(node.getChild(i)))
                return true;
        }
        return false;
    }

    private boolean likeTrackFromResultContainer(String preferredTitle, String normalizedQuery) {
        for (int attempt = 0; attempt < 2; attempt++) {
            service.waitForIdle();
            try {
                Thread.sleep(attempt == 0 ? 600 : 1000);
            } catch (Exception ignored) {
            }

            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            AccessibilityNodeInfo targetRow = null;
            java.util.List<AccessibilityNodeInfo> rows = root.findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
            if (rows != null) {
                int best = -1;
                for (AccessibilityNodeInfo row : rows) {
                    String title = extractNodeText(row, "com.spotify.music:id/title");
                    int score = calculateRelevanceScore(title, "", normalizedQuery);
                    if (!preferredTitle.isEmpty() && normalize(title).equals(normalize(preferredTitle)))
                        score += 1000;
                    if (score > best) {
                        best = score;
                        if (targetRow != null)
                            targetRow.recycle();
                        targetRow = AccessibilityNodeInfo.obtain(row);
                    }
                }
                for (AccessibilityNodeInfo row : rows)
                    row.recycle();
            }

            if (targetRow == null) {
                targetRow = findBestMatchingRow(root, normalizedQuery);
            }

            boolean liked = false;
            if (targetRow != null) {
                liked = performLikeActionInRow(targetRow);
                targetRow.recycle();
            }
            root.recycle();

            if (liked)
                return true;
        }
        return false;
    }

    private String extractNodeText(AccessibilityNodeInfo row, String resId) {
        java.util.List<AccessibilityNodeInfo> nodes = row.findAccessibilityNodeInfosByViewId(resId);
        if (nodes != null && !nodes.isEmpty()) {
            AccessibilityNodeInfo node = nodes.get(0);
            CharSequence text = node.getText();
            for (AccessibilityNodeInfo n : nodes)
                n.recycle();
            return text != null ? text.toString() : "";
        }
        return "";
    }

    private int calculateRelevanceScore(String title, String subtitle, String query) {
        if (title.isEmpty())
            return 0;

        int score = 0;
        String t = title.toLowerCase();
        String s = subtitle.toLowerCase();

        // Pod/Audiobook Penalty
        if (s.contains("podcast") || s.contains("episode") || s.contains("audiobook")) {
            score -= 500;
        }

        if (t.equals(query))
            score += 200;
        else if (t.contains(query))
            score += 100;

        if (s.contains(query))
            score += 80;

        if (t.startsWith(query))
            score += 40;
        if (s.startsWith(query))
            score += 20;

        // Official Curation Bonuses
        if (t.contains("this is"))
            score += 60;
        if (t.contains("best of"))
            score += 40;
        if (t.contains("official"))
            score += 30;
        if (t.contains("mix"))
            score += 20;

        return score;
    }

    private boolean hasNodeWithContentDescription(AccessibilityNodeInfo node, String desc) {
        if (node == null)
            return false;
        CharSequence nodeDesc = node.getContentDescription();
        if (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(desc.toLowerCase()))
            return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasNodeWithContentDescription(node.getChild(i), desc))
                return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findFirstClickableResult(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        // We are looking for the first actual song row.
        String className = String.valueOf(node.getClassName());
        if (node.isClickable() && (className.contains("ViewGroup") || className.contains("RelativeLayout")
                || className.contains("FrameLayout"))) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);

            // Results are typically below the search/tab area (Y > 600 on modern phones)
            // AND we want to ensure it's not the filter bar itself
            if (bounds.top > 600 && node.getChildCount() > 0) {
                // Heuristic check: does it have a 'Song' or 'Artist' indicator inside?
                if (hasTrackSignals(node)) {
                    return node;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findFirstClickableResult(node.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean hasTrackSignals(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        // Songs usually have a 'Song' text, a duration (e.g. 3:45), or an 'Artist' text
        CharSequence text = node.getText();
        if (text != null) {
            String t = text.toString().toLowerCase();
            if (t.equals("song") || t.contains(":") || t.contains("artist"))
                return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasTrackSignals(node.getChild(i)))
                return true;
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null && !current.isClickable()) {
            current = current.getParent();
        }
        if (current != null) {
            return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    public boolean likeCurrentTrack() {
        Log.i(TAG, "Action: Like");
        navigator.checkAndDismissInterruptions();

        for (int attempt = 0; attempt < 2; attempt++) {
            service.waitForIdle();
            try {
                Thread.sleep(attempt == 0 ? 600 : 1000);
            } catch (Exception ignored) {
            }

            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                Log.w(TAG, "[LIKE][ROOT_UNAVAILABLE] attempt=" + (attempt + 1));
                continue;
            }

            if (isLikedStateVisible(root)) {
                Log.i(TAG, "[LIKE][ALREADY_LIKED]");
                root.recycle();
                return true;
            }

            AccessibilityNodeInfo likeButton = findLikeButtonDynamic(root);
            if (likeButton == null) {
                Log.w(TAG, "[LIKE][BUTTON_NOT_FOUND] attempt=" + (attempt + 1));
                root.recycle();
                continue;
            }

            Log.i(TAG, "[LIKE][BUTTON_FOUND] attempt=" + (attempt + 1));
            boolean clicked = clickNodeWithParentTraversal(likeButton, 4);
            likeButton.recycle();
            root.recycle();

            if (!clicked) {
                Log.w(TAG, "[LIKE][CLICK_FAILED] attempt=" + (attempt + 1));
                continue;
            }

            try {
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }

            AccessibilityNodeInfo verifyRoot = service.getRootInActiveWindow();
            if (verifyRoot != null) {
                boolean verified = isLikedStateVisible(verifyRoot);
                verifyRoot.recycle();
                if (verified) {
                    Log.i(TAG, "[LIKE][VERIFIED]");
                    return true;
                }
            }

            Log.i(TAG, "[LIKE][CLICKED_UNVERIFIED] Treating click as optional success");
            return true;
        }

        Log.w(TAG, "[LIKE][OPTIONAL_FAILED] Like skipped after retries");
        return false;
    }

    private AccessibilityNodeInfo findLikeButtonDynamic(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        String desc = lower(node.getContentDescription());
        String text = lower(node.getText());
        String id = lower(node.getViewIdResourceName());

        if (!containsAny(desc, "remove from liked songs", "remove from your library", "unlike")
                && !containsAny(text, "remove from liked songs", "remove from your library", "unlike")) {
            boolean matches = desc.equals("like")
                    || desc.contains("add to liked songs")
                    || desc.contains("save to your library")
                    || desc.contains("save song")
                    || text.equals("like")
                    || text.contains("add to liked songs")
                    || text.contains("save to your library")
                    || id.contains("add_button")
                    || id.contains("like_button")
                    || id.contains("heart");

            if (matches && node.isVisibleToUser())
                return AccessibilityNodeInfo.obtain(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findLikeButtonDynamic(node.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean isLikedStateVisible(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        String desc = lower(node.getContentDescription());
        String text = lower(node.getText());
        if (containsAny(desc, "added to liked songs", "remove from liked songs", "remove from your library",
                "saved to your library")
                || containsAny(text, "added to liked songs", "remove from liked songs", "remove from your library",
                        "saved to your library")) {
            return true;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (isLikedStateVisible(node.getChild(i)))
                return true;
        }
        return false;
    }

    private String lower(CharSequence value) {
        return value == null ? "" : value.toString().toLowerCase().trim();
    }

    private boolean containsAny(String value, String... needles) {
        if (value == null)
            return false;
        for (String needle : needles) {
            if (value.contains(needle))
                return true;
        }
        return false;
    }

    public boolean skipTrack() {
        Log.i(TAG, "Action: Skip");
        navigator.checkAndDismissInterruptions();
        return findAndClick(service.getRootInActiveWindow(), "Next track") ||
                findAndClick(service.getRootInActiveWindow(), "Skip forward");
    }

    private void ensureSpotifyIsForeground() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root != null) {
            String pkg = String.valueOf(root.getPackageName());

            // Notification Panel Check
            if (pkg.contains("com.android.systemui")) {
                Log.w(TAG, "[SYS][NOTIFICATION_PANEL_DETECTED]");
                service.logEvent("SYS", "NOTIFICATION_PANEL", "DETECTED", "Closing panel...");
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
                service.waitForIdle();
                root.recycle();
                root = service.getRootInActiveWindow();
                if (root != null) {
                    Log.i(TAG, "[SYS][NOTIFICATION_PANEL_CLOSED]");
                    service.logEvent("SYS", "NOTIFICATION_PANEL", "CLOSED", "Panel dismissed");
                }
            }

            if (root != null) {
                boolean alreadyThere = "com.spotify.music".equals(root.getPackageName());
                root.recycle();
                if (alreadyThere)
                    return;
            }
        }

        Log.i(TAG, "Launching Spotify...");
        android.content.Intent intent = service.getPackageManager().getLaunchIntentForPackage("com.spotify.music");
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            service.startActivity(intent);
            try {
                Thread.sleep(2000);
            } catch (Exception ignored) {
            }
            service.waitForIdle();
        }
    }

    private boolean findAndClick(AccessibilityNodeInfo node, String desc) {
        if (node == null)
            return false;
        if (desc.equalsIgnoreCase(String.valueOf(node.getContentDescription())) ||
                desc.equalsIgnoreCase(String.valueOf(node.getText()))) {

            // Try standard click
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }

            // Fallback: Click the center of the node's bounds
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            int x = bounds.centerX();
            int y = bounds.centerY();

            Log.i(TAG, "Fallback click at: " + x + ", " + y);
            return performGestureClick(x, y);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndClick(node.getChild(i), desc))
                return true;
        }
        return false;
    }

    private boolean performGestureClick(int x, int y) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N)
            return false;

        android.accessibilityservice.GestureDescription.Builder builder = new android.accessibilityservice.GestureDescription.Builder();
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x, y);
        builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100));

        return service.dispatchGesture(builder.build(), null, null);
    }

    private boolean clickByText(AccessibilityNodeInfo node, String text) {
        // Implementation for clicking by text
        return findAndClick(node, text);
    }

    public boolean executeLikeFlow(String query) {
        String normalizedQuery = query.toLowerCase().trim();
        Log.i(TAG, "[LIKE][FLOW_STARTED] query=[" + normalizedQuery + "]");

        // STAGE 1: One-Shot Search
        if (!executeSearch(query, "Songs")) {
            Log.e(TAG, "[LIKE][SEARCH_FAILED]");
            return false;
        }

        // STAGE 2: Semantic Row Resolution
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        try {
            AccessibilityNodeInfo bestRow = findBestMatchingRow(root, normalizedQuery);
            if (bestRow != null) {
                Log.i(TAG, "[LIKE][ROW_RESOLVED]");

                // STAGE 3: Scoped Action Execution
                boolean liked = performLikeActionInRow(bestRow);
                bestRow.recycle();
                return liked;
            } else {
                Log.e(TAG, "[LIKE][NO_MATCH_FOUND]");
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private AccessibilityNodeInfo findBestMatchingRow(AccessibilityNodeInfo root, String query) {
        AccessibilityNodeInfo container = findNodeById(root, "com.spotify.music:id/search_content_elements");
        if (container == null)
            container = root;

        java.util.List<AccessibilityNodeInfo> rows = new java.util.ArrayList<>();
        findClickableRows(container, rows);

        AccessibilityNodeInfo bestNode = null;
        int highestScore = 0;

        for (AccessibilityNodeInfo row : rows) {
            int score = calculateRowScore(row, query);
            Log.d(TAG, "[LIKE][MATCH_SCORE] " + score + " for row: " + getRowText(row));

            if (score > highestScore && score >= 70) {
                highestScore = score;
                if (bestNode != null)
                    bestNode.recycle();
                bestNode = AccessibilityNodeInfo.obtain(row);
            }
        }

        for (AccessibilityNodeInfo r : rows)
            r.recycle();
        if (container != root)
            container.recycle();

        return bestNode;
    }

    private int calculateRowScore(AccessibilityNodeInfo row, String query) {
        String title = "";
        String subtitle = "";

        for (int i = 0; i < row.getChildCount(); i++) {
            AccessibilityNodeInfo child = row.getChild(i);
            if (child == null)
                continue;

            CharSequence text = child.getText();
            if (text != null) {
                if (title.isEmpty())
                    title = text.toString().toLowerCase().trim();
                else if (subtitle.isEmpty())
                    subtitle = text.toString().toLowerCase().trim();
            }
            child.recycle();
        }

        int score = 0;
        String[] queryParts = query.split("\\s+");

        // TIER 1: Exact Match (The "Holy Grail")
        if (title.equalsIgnoreCase(query)) {
            score += 150;
        }

        // TIER 2: Title Containment
        if (title.contains(query)) {
            score += 70;
            // Penalty for "extra noise" in the title (length difference)
            int noiseFactor = Math.abs(title.length() - query.length());
            score -= (noiseFactor * 2); // Every extra char in title reduces score slightly
        } else {
            for (String part : queryParts) {
                if (title.contains(part)) {
                    score += 30;
                    break;
                }
            }
        }

        // TIER 3: Metadata (Subtitle)
        if (subtitle.contains(queryParts[queryParts.length - 1])) {
            score += 20;
        }

        // TIER 4: Token Bonus
        if (title.startsWith(queryParts[0])) {
            score += 10;
        }

        return score;
    }

    private boolean performLikeActionInRow(AccessibilityNodeInfo row) {
        AccessibilityNodeInfo likeButton = findLikeButtonInRow(row);
        if (likeButton != null) {
            Log.i(TAG, "[LIKE][SCOPED_BUTTON_FOUND]");
            boolean clicked = clickNodeWithParentTraversal(likeButton, 4);
            likeButton.recycle();
            return clicked;
        }
        return false;
    }

    private AccessibilityNodeInfo findLikeButtonInRow(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        CharSequence desc = node.getContentDescription();
        String id = (node.getViewIdResourceName() != null) ? node.getViewIdResourceName() : "";

        if (desc != null && (desc.toString().contains("Add") || desc.toString().contains("Save"))) {
            return AccessibilityNodeInfo.obtain(node);
        }
        if (id.contains("add_button") || id.contains("heart")) {
            return AccessibilityNodeInfo.obtain(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findLikeButtonInRow(node.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private void findClickableRows(AccessibilityNodeInfo node, java.util.List<AccessibilityNodeInfo> list) {
        if (node == null)
            return;
        if (node.isClickable() && node.getChildCount() >= 2) {
            list.add(AccessibilityNodeInfo.obtain(node));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findClickableRows(node.getChild(i), list);
        }
    }

    private String getRowText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        extractAllText(node, sb);
        return sb.toString();
    }

    private void extractAllText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null)
            return;
        if (node.getText() != null)
            sb.append(node.getText()).append(" ");
        for (int i = 0; i < node.getChildCount(); i++) {
            extractAllText(node.getChild(i), sb);
        }
    }

    public boolean executeAlbumFlow(String query) {
        Log.i(TAG, "[ALBUM][FLOW_STARTED] query=[" + query + "]");

        // STAGE 1: Search Initialized
        if (!executeSearch(query, null)) { // Start search without initial filter
            return false;
        }

        // STAGE 2: Navigate to Albums Tab
        if (!navigateToTab("Albums")) {
            Log.e(TAG, "[ALBUM][TAB_NAV_FAILED]");
            return false;
        }

        // STAGE 3: Select First Album
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        try {
            if (!waitForPlaylistContainer(5000)) {
                Log.e(TAG, "[ALBUM][LIST_NEVER_READY]");
                return false;
            }

            AccessibilityNodeInfo firstRow = selectFirstAlbumRow(root);
            if (firstRow != null) {
                Log.i(TAG, "[ALBUM][FIRST_ROW_LOCKED]");
                boolean clicked = activateNode(firstRow);
                firstRow.recycle();

                if (clicked) {
                    Log.i(TAG, "[ALBUM][ENTRY_OK] Waiting for header/container...");
                    // Give Spotify a moment to hydrate the album page
                    try {
                        Thread.sleep(2000);
                    } catch (Exception ignored) {
                    }

                    // We call startPlaybackFromHeader regardless of strict container check
                    // because the CTA scan has its own wait/retry logic.
                    waitForPlaylistContainer(3);

                    Log.i(TAG, "[ALBUM][PLAYBACK_START_TRIGGERED]");

                    if (targetSongsToAdd > 0 && !targetPlaylistName.isEmpty()) {
                        batchAddPlaylistTracks(targetSongsToAdd);
                    }

                    String trackBefore = getNowPlayingTitle();
                    return startPlaybackFromHeader(trackBefore);
                }
            } else {
                Log.e(TAG, "[ALBUM][NO_ROWS_FOUND]");
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private boolean navigateToTab(String tabName) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        try {
            AccessibilityNodeInfo tab = findNodeByText(root, tabName);
            if (tab != null) {
                Log.i(TAG, "[ALBUM][TAB_FOUND] " + tabName);
                boolean clicked = activateNode(tab);
                tab.recycle();
                if (clicked) {
                    try {
                        Thread.sleep(1000);
                    } catch (Exception ignored) {
                    }
                    return true;
                }
            }
        } finally {
            root.recycle();
        }
        return false;
    }

    private AccessibilityNodeInfo selectFirstAlbumRow(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo container = findNodeById(root, "com.spotify.music:id/search_content_elements");
        if (container == null)
            container = root;

        for (int i = 0; i < container.getChildCount(); i++) {
            AccessibilityNodeInfo child = container.getChild(i);
            if (child == null)
                continue;

            // Check if it's a row_root or has row_root in its ID
            String resId = (child.getViewIdResourceName() != null) ? child.getViewIdResourceName() : "";
            if (resId.contains("row_root") || child.isClickable()) {
                if (container != root)
                    container.recycle();
                return child;
            }

            // Search inside for a row_root
            AccessibilityNodeInfo inner = findNodeById(child, "com.spotify.music:id/row_root");
            if (inner != null) {
                child.recycle();
                if (container != root)
                    container.recycle();
                return inner;
            }

            child.recycle();
        }
        if (container != root)
            container.recycle();
        return null;
    }

    private AccessibilityNodeInfo findNodeById(AccessibilityNodeInfo node, String resId) {
        if (node == null || resId == null)
            return null;
        java.util.List<AccessibilityNodeInfo> list = node.findAccessibilityNodeInfosByViewId(resId);
        if (list != null && !list.isEmpty()) {
            AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(list.get(0));
            for (AccessibilityNodeInfo n : list)
                n.recycle();
            return result;
        }
        return null;
    }

    private boolean hasNodeByResId(AccessibilityNodeInfo node, String resId) {
        if (node == null)
            return false;
        java.util.List<AccessibilityNodeInfo> nodes = node.findAccessibilityNodeInfosByViewId(resId);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo n : nodes)
                n.recycle();
            return true;
        }
        return false;
    }

    public boolean executeFollowArtist(String artistQuery) {
        Log.i(TAG, "[ARTIST][SEARCH_STARTED] " + artistQuery);

        // 1. Execute Search with Artists filter
        if (!executeSearch(artistQuery, "Artists")) {
            Log.e(TAG, "[ARTIST][FAILED] Search/Filter failed");
            return false;
        }
        Log.i(TAG, "[ARTIST][FILTER_SELECTED]");

        // 2. Wait for stabilization
        try {
            Thread.sleep(2000);
        } catch (Exception ignored) {
        }

        // 3. Scan for best match
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        java.util.List<AccessibilityNodeInfo> rows = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
        if (rows == null || rows.isEmpty()) {
            Log.e(TAG, "[ARTIST][FAILED] No artist rows found");
            root.recycle();
            return false;
        }

        AccessibilityNodeInfo bestRow = null;
        AccessibilityNodeInfo bestFollowButton = null;
        int bestScore = -1;
        String normalizedQuery = artistQuery.toLowerCase().trim().replaceAll("[^a-z0-9 ]", "");

        for (AccessibilityNodeInfo row : rows) {
            String title = extractNodeText(row, "com.spotify.music:id/title");
            if (title == null) {
                row.recycle();
                continue;
            }

            String normalizedTitle = title.toLowerCase().trim().replaceAll("[^a-z0-9 ]", "");
            int score = calculateMatchScore(normalizedTitle, normalizedQuery);

            if (score > bestScore && score > 50) { // Minimum confidence threshold
                if (bestRow != null)
                    bestRow.recycle();
                if (bestFollowButton != null)
                    bestFollowButton.recycle();

                bestScore = score;
                bestRow = AccessibilityNodeInfo.obtain(row);
                bestFollowButton = findNodeById(row, "com.spotify.music:id/follow_button");
            }
            row.recycle();
        }

        if (bestRow == null || bestFollowButton == null) {
            Log.e(TAG, "[ARTIST][FAILED] No high-confidence match found");
            if (bestRow != null)
                bestRow.recycle();
            if (bestFollowButton != null)
                bestFollowButton.recycle();
            root.recycle();
            return false;
        }

        String matchedName = extractNodeText(bestRow, "com.spotify.music:id/title");
        Log.i(TAG, "[ARTIST][MATCH_FOUND] name=[" + matchedName + "] score=" + bestScore);

        // 4. Follow Action
        if (!bestFollowButton.isVisibleToUser() || !bestFollowButton.isEnabled()) {
            Log.e(TAG, "[ARTIST][FAILED] Follow button not interactable");
            bestRow.recycle();
            bestFollowButton.recycle();
            root.recycle();
            return false;
        }

        // Verify state before click
        CharSequence currentStatus = bestFollowButton.getText();
        if (currentStatus != null && currentStatus.toString().equalsIgnoreCase("Following")) {
            Log.i(TAG, "[ARTIST][ALREADY_FOLLOWING]");
            bestRow.recycle();
            bestFollowButton.recycle();
            root.recycle();
            return true;
        }

        Log.i(TAG, "[ARTIST][FOLLOW_CLICKED]");
        boolean clicked = bestFollowButton.performAction(AccessibilityNodeInfo.ACTION_CLICK);

        if (!clicked) {
            // Fallback: Gesture click on button center
            Rect bounds = new Rect();
            bestFollowButton.getBoundsInScreen(bounds);
            clicked = navigator.tap(bounds.centerX(), bounds.centerY());
        }

        if (clicked) {
            // 5. Verification
            try {
                Thread.sleep(2000);
            } catch (Exception ignored) {
            }
            // Re-fetch node state
            AccessibilityNodeInfo verifyRoot = service.getRootInActiveWindow();
            if (verifyRoot != null) {
                AccessibilityNodeInfo verifyBtn = findNodeById(verifyRoot, "com.spotify.music:id/follow_button");
                if (verifyBtn != null) {
                    CharSequence newStatus = verifyBtn.getText();
                    if (newStatus != null && newStatus.toString().equalsIgnoreCase("Following")) {
                        Log.i(TAG, "[ARTIST][FOLLOW_VERIFIED]");
                        verifyBtn.recycle();
                        verifyRoot.recycle();
                        bestRow.recycle();
                        bestFollowButton.recycle();
                        root.recycle();
                        return true;
                    }
                    verifyBtn.recycle();
                }
                verifyRoot.recycle();
            }
        }

        Log.e(TAG, "[ARTIST][FAILED] Follow verification failed");
        bestRow.recycle();
        bestFollowButton.recycle();
        root.recycle();
        return false;
    }

    public boolean executePlayArtistCatalog(String artistQuery) {
        Log.i(TAG, "[ARTIST][CATALOG_START] " + artistQuery);

        // 1. Search and Filter
        if (!executeSearch(artistQuery, "Artists"))
            return false;
        try {
            Thread.sleep(2000);
        } catch (Exception ignored) {
        }

        // 2. Find and Click Artist Row
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        java.util.List<AccessibilityNodeInfo> rows = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
        AccessibilityNodeInfo bestRow = null;
        int bestScore = -1;
        String normalizedQuery = artistQuery.toLowerCase().trim().replaceAll("[^a-z0-9 ]", "");

        for (AccessibilityNodeInfo row : rows) {
            String title = extractNodeText(row, "com.spotify.music:id/title");
            if (title == null) {
                row.recycle();
                continue;
            }
            int score = calculateMatchScore(title.toLowerCase().trim().replaceAll("[^a-z0-9 ]", ""), normalizedQuery);
            if (score > bestScore && score > 50) {
                if (bestRow != null)
                    bestRow.recycle();
                bestScore = score;
                bestRow = AccessibilityNodeInfo.obtain(row);
            }
            row.recycle();
        }

        if (bestRow == null) {
            Log.e(TAG, "[ARTIST][FAILED] No match found");
            root.recycle();
            return false;
        }

        Log.i(TAG, "[ARTIST][MATCH_FOUND] " + extractNodeText(bestRow, "com.spotify.music:id/title"));
        boolean clicked = clickNodeOrParent(bestRow);
        bestRow.recycle();
        root.recycle();

        if (!clicked)
            return false;

        // 3. Wait for Artist Page
        Log.i(TAG, "[ARTIST][WAITING_FOR_PAGE]");
        try {
            Thread.sleep(2500);
        } catch (Exception ignored) {
        }

        if (!verifyArtistPageLoaded()) {
            Log.e(TAG, "[ARTIST][FAILED] Artist page not verified");
            return false;
        }
        Log.i(TAG, "[ARTIST][ARTIST_PAGE_OPENED]");
        try {
            Thread.sleep(1500);
        } catch (Exception ignored) {
        } // Stabilization wait

        // 4. Find and Click Main Play Button (Region Validated)
        for (int attempt = 0; attempt < 3; attempt++) {
            AccessibilityNodeInfo pageRoot = service.getRootInActiveWindow();
            if (pageRoot == null)
                continue;

            AccessibilityNodeInfo playBtn = findArtistPagePlayButton(pageRoot);
            if (playBtn != null) {
                Log.i(TAG, "[ARTIST][PLAY_BUTTON_FOUND]");
                boolean playClicked = playBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!playClicked) {
                    Rect bounds = new Rect();
                    playBtn.getBoundsInScreen(bounds);
                    playClicked = navigator.tap(bounds.centerX(), bounds.centerY());
                }
                playBtn.recycle();
                pageRoot.recycle();

                if (playClicked) {
                    Log.i(TAG, "[ARTIST][PLAY_CLICK_OK]");
                    try {
                        Thread.sleep(3000);
                    } catch (Exception ignored) {
                    }
                    if (isPlaybackStarted()) {
                        Log.i(TAG, "[ARTIST][PLAYBACK_STARTED]");
                        return true;
                    }
                }
            } else {
                pageRoot.recycle();
                Log.w(TAG, "[ARTIST][PLAY_BUTTON_RETRY] " + (attempt + 1));
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            }
        }

        Log.e(TAG, "[ARTIST][FAILED] PLAYBACK_DID_NOT_START");
        return false;
    }

    private boolean verifyArtistPageLoaded() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;
        // Search for "Shuffle" or "Play" or "Follow" labels that are typical on artist
        // pages
        boolean hasBack = navigator.findNodeByDescription(root, "back") != null
                || navigator.findNodeByDescription(root, "navigate up") != null;
        boolean hasFollow = hasText(root, "Follow") || hasText(root, "Following");
        root.recycle();
        return hasBack && hasFollow;
    }

    private AccessibilityNodeInfo findArtistPagePlayButton(AccessibilityNodeInfo root) {
        Log.i(TAG, "[ARTIST][PLAY_BUTTON_SCAN]");
        if (root == null)
            return null;

        // 1. Semantic Search: Target the specific Play/Pause ID globally
        java.util.List<AccessibilityNodeInfo> candidates = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/button_play_and_pause");

        if (candidates != null) {
            for (AccessibilityNodeInfo node : candidates) {
                // Dynamic Validation: Reject if inside mini-player/now-playing containers
                if (isInsideNowPlayingContainer(node)) {
                    Log.w(TAG, "[ARTIST][PLAY_BUTTON_REJECTED_MINIPLAYER]");
                } else if (node.isVisibleToUser()) {
                    Rect b = new Rect();
                    node.getBoundsInScreen(b);
                    Log.i(TAG, "[ARTIST][PLAY_BUTTON_VALID] bounds=" + b.toShortString());
                    return node;
                }
                node.recycle();
            }
        }

        // 2. Fallback: Scan for "Play" description anywhere in the tree
        logFallback("RETRY", "PLAY_BUTTON", "alt_selector=content-desc");
        return findPlayButtonDynamic(root);
    }

    private AccessibilityNodeInfo findPlayButtonDynamic(AccessibilityNodeInfo node) {
        if (node == null)
            return null;

        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            if (d.equals("play") || d.contains("play artist") || d.contains("shuffle play")) {
                // Structural Validation: Ensure it's not the bottom player
                if (!isInsideNowPlayingContainer(node) && node.isVisibleToUser()) {
                    Log.i(TAG, "[ARTIST][PLAY_BUTTON_VALID_FALLBACK] desc=" + d);
                    return AccessibilityNodeInfo.obtain(node);
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findPlayButtonDynamic(node.getChild(i));
            if (result != null)
                return result;
        }
        return null;
    }

    private boolean isInsideNowPlayingContainer(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        while (current != null) {
            String resId = current.getViewIdResourceName();
            if (resId != null && (resId.contains("now_playing_view_container") || resId.contains("miniplayer"))) {
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        return false;
    }

    private boolean isPlaybackStarted() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;
        // Look for pause button or now playing bar
        boolean playing = navigator.findNodeByDescription(root, "pause") != null ||
                navigator.findNodeByResourceId(root, "com.spotify.music:id/now_playing_bar") != null;
        root.recycle();
        return playing;
    }

    public boolean executePlayThisIsArtist(String artistQuery) {
        Log.i(TAG, "[THIS_IS][SEARCH_STARTED] " + artistQuery);

        // 1. Search and Filter to Artist
        if (!executeSearch(artistQuery, "Artists"))
            return false;
        try {
            Thread.sleep(2000);
        } catch (Exception ignored) {
        }

        // 2. Open Artist Page
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        java.util.List<AccessibilityNodeInfo> rows = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
        AccessibilityNodeInfo bestRow = null;
        int bestScore = -1;
        String normalizedQuery = artistQuery.toLowerCase().trim().replaceAll("[^a-z0-9 ]", "");

        for (AccessibilityNodeInfo row : rows) {
            String title = extractNodeText(row, "com.spotify.music:id/title");
            if (title == null) {
                row.recycle();
                continue;
            }
            int score = calculateMatchScore(title.toLowerCase().trim().replaceAll("[^a-z0-9 ]", ""), normalizedQuery);
            if (score > bestScore && score > 50) {
                if (bestRow != null)
                    bestRow.recycle();
                bestScore = score;
                bestRow = AccessibilityNodeInfo.obtain(row);
            }
            row.recycle();
        }

        if (bestRow == null) {
            Log.e(TAG, "[THIS_IS][FAILED] Artist not found");
            root.recycle();
            return false;
        }

        Log.i(TAG, "[THIS_IS][ARTIST_MATCH_FOUND] " + extractNodeText(bestRow, "com.spotify.music:id/title"));
        boolean clickedArtist = clickNodeOrParent(bestRow);
        bestRow.recycle();
        root.recycle();

        if (!clickedArtist)
            return false;

        // 3. Verify Artist Page and Scroll for Card
        Log.i(TAG, "[THIS_IS][WAITING_FOR_PAGE]");
        try {
            Thread.sleep(2500);
        } catch (Exception ignored) {
        }

        if (!verifyArtistPageLoaded()) {
            Log.e(TAG, "[THIS_IS][FAILED] Artist page failed load");
            return false;
        }
        Log.i(TAG, "[THIS_IS][ARTIST_PAGE_OPENED]");

        AccessibilityNodeInfo thisIsCard = null;
        for (int scroll = 0; scroll < 10; scroll++) {
            Log.i(TAG, "[THIS_IS][SCROLLING] Attempt " + (scroll + 1));

            AccessibilityNodeInfo currentRoot = service.getRootInActiveWindow();
            if (currentRoot == null)
                continue;

            thisIsCard = findThisIsCard(currentRoot, normalizedQuery);
            currentRoot.recycle();

            if (thisIsCard != null) {
                Log.i(TAG, "[THIS_IS][THIS_IS_FOUND]");
                break;
            }

            navigator.swipeUp();
            try {
                Thread.sleep(1500);
            } catch (Exception ignored) {
            }
        }

        if (thisIsCard == null) {
            Log.e(TAG, "[THIS_IS][FAILED] This Is card not found after scrolling");
            logFallback("FAILED", "THIS_IS_CARD_NOT_FOUND", "Card not found after 10 swipes");
            return false;
        }

        // 4. Open Playlist
        boolean cardClicked = clickNodeOrParent(thisIsCard);
        thisIsCard.recycle();
        if (!cardClicked)
            return false;

        Log.i(TAG, "[THIS_IS][PLAYLIST_OPENED]");
        try {
            Thread.sleep(2500);
        } catch (Exception ignored) {
        }

        // 5. Playlist Playback Stage
        Log.i(TAG, "[THIS_IS][PLAY_BUTTON_SEARCH]");
        boolean playStarted = false;

        for (int retry = 0; retry < 2; retry++) {
            AccessibilityNodeInfo playlistRoot = service.getRootInActiveWindow();
            if (playlistRoot == null)
                continue;

            // Search for "Play playlist" without class restriction
            AccessibilityNodeInfo playBtn = findNodeByDescriptionRecursive(playlistRoot, "play playlist");

            if (playBtn != null) {
                Log.i(TAG, "[THIS_IS][PLAY_BUTTON_FOUND]");

                // Parent-Climbing Click Engine (Depth 5)
                boolean clicked = false;
                AccessibilityNodeInfo current = playBtn;
                for (int depth = 0; depth < 5; depth++) {
                    clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (clicked) {
                        Log.i(TAG,
                                depth == 0 ? "[THIS_IS][PLAY_BUTTON_CLICK_OK]" : "[THIS_IS][PLAY_BUTTON_PARENT_CLICK]");
                        break;
                    }
                    AccessibilityNodeInfo parent = current.getParent();
                    if (current != playBtn)
                        current.recycle();
                    current = parent;
                    if (current == null)
                        break;
                }

                if (!clicked) {
                    Log.w(TAG, "[THIS_IS][PLAY_BUTTON_CLICK_FAILED] Trying gesture...");
                    Rect bounds = new Rect();
                    playBtn.getBoundsInScreen(bounds);
                    clicked = navigator.tap(bounds.centerX(), bounds.centerY());
                }

                if (clicked) {
                    Log.i(TAG, "[THIS_IS][PLAYBACK_VERIFY_STARTED]");
                    if (waitForPlaybackConfirmation(10000)) {
                        Log.i(TAG, "[THIS_IS][PLAYBACK_STARTED]");
                        playStarted = true;
                        if (current != null && current != playBtn)
                            current.recycle();
                        playBtn.recycle();
                        playlistRoot.recycle();
                        break;
                    }
                }
                if (current != null && current != playBtn)
                    current.recycle();
                playBtn.recycle();
            }

            playlistRoot.recycle();
            if (retry == 0) {
                Log.w(TAG, "[THIS_IS][PLAY_RETRY] Waiting for sync...");
                try {
                    Thread.sleep(1500);
                } catch (Exception ignored) {
                }
            }
        }

        if (playStarted)
            return true;

        Log.e(TAG, "[THIS_IS][FAILED] PLAYBACK_DID_NOT_START");
        return false;
    }

    private boolean waitForPlaybackConfirmation(int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                boolean playing = navigator.findNodeByDescription(root, "pause") != null ||
                        navigator.findNodeByResourceId(root, "com.spotify.music:id/now_playing_bar") != null ||
                        navigator.findNodeByResourceId(root, "com.spotify.music:id/now_playing_view_container") != null;
                root.recycle();
                if (playing)
                    return true;
            }
            try {
                Thread.sleep(800);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findThisIsCard(AccessibilityNodeInfo root, String normalizedArtist) {
        java.util.List<AccessibilityNodeInfo> cards = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/card_root");
        if (cards == null)
            return null;

        for (AccessibilityNodeInfo card : cards) {
            String title = extractNodeText(card, "com.spotify.music:id/title");
            String subtitle = extractNodeText(card, "com.spotify.music:id/subtitle");
            CharSequence desc = card.getContentDescription();

            String fullText = (title + " " + subtitle + " " + (desc != null ? desc : "")).toLowerCase();

            // Rule: Must contain "this is" AND normalized artist name
            if (fullText.contains("this is") && fullText.contains(normalizedArtist)) {
                Log.i(TAG, "[THIS_IS][CARD_SCAN] Match found: " + fullText);
                return card; // Keep card node, loop handles the rest
            }
            card.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo findPlaylistPlayButton(AccessibilityNodeInfo root) {
        Log.i(TAG, "[THIS_IS][PLAY_BUTTON_SCAN]");
        if (root == null)
            return null;

        // Try the specific play/pause ID first
        java.util.List<AccessibilityNodeInfo> playButtons = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/button_play_and_pause");
        if (playButtons != null) {
            for (AccessibilityNodeInfo btn : playButtons) {
                if (!isInsideNowPlayingContainer(btn) && btn.isVisibleToUser()) {
                    return btn;
                }
                btn.recycle();
            }
        }

        // Fallback to description search globally
        logFallback("RETRY", "PLAY_BUTTON", "alt_selector=content-desc");
        return findNodeByDescriptionRecursive(root, "play playlist");
    }

    public boolean executePlayArtistRadio(String artistQuery) {

        Log.i(TAG, "[RADIO][SEARCH_STARTED] " + artistQuery);

        // 1. Search and Filter to Artist
        if (!executeSearch(artistQuery, "Artists"))
            return false;
        try {
            Thread.sleep(2000);
        } catch (Exception ignored) {
        }

        // 2. Open Artist Page
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        java.util.List<AccessibilityNodeInfo> rows = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/row_root");
        AccessibilityNodeInfo bestRow = null;
        int bestScore = -1;
        String normalizedQuery = artistQuery.toLowerCase().trim().replaceAll("[^a-z0-9 ]", "");

        for (AccessibilityNodeInfo row : rows) {
            String title = extractNodeText(row, "com.spotify.music:id/title");
            if (title == null) {
                row.recycle();
                continue;
            }
            int score = calculateMatchScore(title.toLowerCase().trim().replaceAll("[^a-z0-9 ]", ""), normalizedQuery);
            if (score > bestScore && score > 50) {
                if (bestRow != null)
                    bestRow.recycle();
                bestScore = score;
                bestRow = AccessibilityNodeInfo.obtain(row);
            }
            row.recycle();
        }

        if (bestRow == null) {
            Log.e(TAG, "[RADIO][FAILED] Artist not found");
            root.recycle();
            return false;
        }

        Log.i(TAG, "[RADIO][ARTIST_MATCH_FOUND]");
        boolean clickedArtist = clickNodeOrParent(bestRow);
        bestRow.recycle();
        root.recycle();
        if (!clickedArtist)
            return false;

        // 3. Open More Options
        try {
            Thread.sleep(2500);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "[RADIO][MORE_OPTIONS_SEARCH]");

        boolean moreClicked = false;
        for (int retry = 0; retry < 2; retry++) {
            AccessibilityNodeInfo pRoot = service.getRootInActiveWindow();
            if (pRoot == null)
                continue;

            AccessibilityNodeInfo moreBtn = findNodeByDescriptionRecursive(pRoot, "more options");
            if (moreBtn != null) {
                Log.i(TAG, "[RADIO][MORE_OPTIONS_FOUND]");
                moreClicked = clickNodeWithParentTraversal(moreBtn, 5);
                moreBtn.recycle();
                pRoot.recycle();
                if (moreClicked) {
                    Log.i(TAG, "[RADIO][MORE_OPTIONS_CLICK_OK]");
                    break;
                }
            } else {
                pRoot.recycle();
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            }
        }
        if (!moreClicked) {
            logFallback("FAILED", "MORE_OPTIONS_NOT_FOUND", "Could not open more options menu");
            return false;
        }

        // 4. Click Go to artist radio
        try {
            Thread.sleep(1500);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "[RADIO][RADIO_OPTION_SEARCH]");

        boolean radioClicked = false;
        for (int retry = 0; retry < 2; retry++) {
            AccessibilityNodeInfo mRoot = service.getRootInActiveWindow();
            if (mRoot == null)
                continue;

            AccessibilityNodeInfo radioBtn = findNodeByDescriptionRecursive(mRoot, "go to artist radio");
            if (radioBtn != null) {
                Log.i(TAG, "[RADIO][RADIO_OPTION_FOUND]");
                radioClicked = clickNodeWithParentTraversal(radioBtn, 5);
                radioBtn.recycle();
                mRoot.recycle();
                if (radioClicked) {
                    Log.i(TAG, "[RADIO][RADIO_OPTION_CLICK_OK]");
                    break;
                }
            } else {
                mRoot.recycle();
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            }
        }
        if (!radioClicked) {
            logFallback("RECOVERY", "returning_to_search", "Radio option not found, going back");
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            return false;
        }

        // 5. Wait for Radio Page
        Log.i(TAG, "[RADIO][RADIO_PAGE_WAIT]");
        try {
            Thread.sleep(3000);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "[RADIO][RADIO_PAGE_OPENED]");

        // 6. Click Play playlist
        Log.i(TAG, "[RADIO][PLAY_BUTTON_SEARCH]");
        boolean playClicked = false;
        for (int retry = 0; retry < 2; retry++) {
            AccessibilityNodeInfo rRoot = service.getRootInActiveWindow();
            if (rRoot == null)
                continue;

            AccessibilityNodeInfo playBtn = findNodeByDescriptionRecursive(rRoot, "play playlist");
            if (playBtn != null) {
                Log.i(TAG, "[RADIO][PLAY_BUTTON_FOUND]");
                playClicked = clickNodeWithParentTraversal(playBtn, 5);
                playBtn.recycle();
                rRoot.recycle();
                if (playClicked) {
                    Log.i(TAG, "[RADIO][PLAY_BUTTON_CLICK_OK]");
                    break;
                }
            } else {
                rRoot.recycle();
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            }
        }
        if (!playClicked)
            return false;

        // 7. Verify Playback
        Log.i(TAG, "[RADIO][PLAYBACK_VERIFY_STARTED]");
        if (waitForPlaybackConfirmation(10000)) {
            Log.i(TAG, "[RADIO][PLAYBACK_STARTED]");
            return true;
        } else {
            Log.e(TAG, "[RADIO][PLAYBACK_DID_NOT_START]");
            return false;
        }
    }

    private boolean clickNodeWithParentTraversal(AccessibilityNodeInfo node, int maxDepth) {

        if (node == null)
            return false;

        AccessibilityNodeInfo current = node;
        for (int i = 0; i < maxDepth; i++) {
            if (current.isClickable()) {
                boolean ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (ok)
                    return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (current != node)
                current.recycle();
            current = parent;
            if (current == null)
                break;
        }
        // Final fallback: Safe Gesture Tap if all else fails
        return navigator.tapNodeCenter(node);
    }

    private int calculateMatchScore(String title, String query) {
        if (title.equals(query))
            return 100;
        if (title.startsWith(query))
            return 85;
        if (query.startsWith(title))
            return 80;
        if (title.contains(query))
            return 70;
        return 0;
    }

    public boolean openNowPlayingWithFallback() {
        // === NOW PLAYING FALLBACK CHAIN ===
        // Try multiple strategies to open Now Playing view
        Log.i(TAG, "[SESSION] Attempting to open Now Playing with fallback chain...");

        // Strategy 1: Try standard mini-player click
        if (openNowPlaying()) {
            Log.i(TAG, "[SESSION][FALLBACK_1_SUCCESS] Mini-player click worked");
            return true;
        }

        // Strategy 2: Swipe up on bottom bar (bottom sheet expansion)
        Log.i(TAG, "[SESSION][FALLBACK_2_ATTEMPT] Trying swipe-up gesture...");
        try {
            if (navigator.swipeUp()) {
                Thread.sleep(1500);
                // Verify it worked by checking for seekbar
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) {
                    AccessibilityNodeInfo seekbar = navigator.findNodeByResourceId(root, "com.spotify.music:id/seekbar");
                    if (seekbar != null && seekbar.isVisibleToUser()) {
                        Log.i(TAG, "[SESSION][FALLBACK_2_SUCCESS] Swipe-up worked");
                        seekbar.recycle();
                        root.recycle();
                        return true;
                    }
                    root.recycle();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "[SESSION][FALLBACK_2_FAILED] " + e.getMessage());
        }

        // Strategy 3: Accept graceful degradation - mark as UNAVAILABLE but continue
        Log.w(TAG, "[SESSION][FALLBACK_EXHAUSTED] Now Playing could not be opened. Continuing without session controls.");
        service.logEvent("SESSION", "NOW_PLAYING", "UNAVAILABLE", "Cannot open Now Playing view. Proceeding without session engine.");
        return false;  // Return false to skip session engine, but don't fail the whole command
    }

    public boolean openNowPlaying() {
        Log.i(TAG, "[SESSION] Opening Now Playing view...");
        for (int i = 0; i < 3; i++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            // Check if already open (if seekbar is visible)
            AccessibilityNodeInfo seekbar = navigator.findNodeByResourceId(root, "com.spotify.music:id/seekbar");
            if (seekbar != null && seekbar.isVisibleToUser()) {
                Log.i(TAG, "[SESSION] Now Playing already open.");
                seekbar.recycle();
                root.recycle();
                return true;
            }

            // Click mini player
            AccessibilityNodeInfo miniPlayer = navigator.findNodeByResourceId(root,
                    "com.spotify.music:id/now_playing_view_container");
            if (miniPlayer == null)
                miniPlayer = navigator.findNodeByResourceId(root, "com.spotify.music:id/now_playing_bar");

            if (miniPlayer != null) {
                boolean clicked = clickNodeWithParentTraversal(miniPlayer, 3);
                miniPlayer.recycle();
                if (clicked) {
                    try {
                        Thread.sleep(2000);
                    } catch (Exception ignored) {
                    }
                    // Verify it opened
                    AccessibilityNodeInfo newRoot = service.getRootInActiveWindow();
                    if (newRoot != null) {
                        AccessibilityNodeInfo sb = navigator.findNodeByResourceId(newRoot,
                                "com.spotify.music:id/seekbar");
                        if (sb != null) {
                            Log.i(TAG, "[SESSION] Now Playing opened successfully.");
                            sb.recycle();
                            newRoot.recycle();
                            root.recycle();
                            return true;
                        }
                        newRoot.recycle();
                    }
                }
            }
            root.recycle();
            try {
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }
        }
        Log.e(TAG, "[SESSION] Failed to open Now Playing view.");
        return false;
    }

    public long getTrackDurationMs() {
        Log.i(TAG, "[SESSION] Reading track duration...");
        for (int i = 0; i < 5; i++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            // Strategy 1: Seekbar Description
            AccessibilityNodeInfo seekbar = navigator.findNodeByResourceId(root, "com.spotify.music:id/seekbar");
            if (seekbar != null) {
                CharSequence desc = seekbar.getContentDescription();
                if (desc != null) {
                    String descStr = desc.toString().toLowerCase();
                    Log.i(TAG, "[SESSION][TRACK_DURATION_DETECTED] Seekbar: " + descStr);
                    if (descStr.length() > 15) {
                        long maxDuration = parseDurationString(descStr);
                        if (maxDuration > 0) {
                            seekbar.recycle();
                            root.recycle();
                            return maxDuration;
                        }
                    }
                }
                seekbar.recycle();
            }

            // Strategy 2: Look for a total time label (fallback)
            java.util.regex.Pattern timePattern = java.util.regex.Pattern.compile("^\\d+:\\d+$");
            AccessibilityNodeInfo durationNode = findNodeByPattern(root, timePattern, true); // Search for last time
                                                                                             // node
            if (durationNode != null) {
                String timeText = durationNode.getText().toString();
                Log.i(TAG, "[SESSION][TRACK_DURATION_DETECTED] Label Fallback: " + timeText);
                long ms = parseTimeText(timeText);
                durationNode.recycle();
                if (ms > 0) {
                    root.recycle();
                    return ms;
                }
            }

            root.recycle();
            try {
                Thread.sleep(1500);
            } catch (Exception ignored) {
            }
        }
        Log.w(TAG, "[SESSION] Could not read duration, falling back to 180000ms (3 min)");
        return 180000;
    }

    private long parseTimeText(String time) {
        try {
            String[] parts = time.split(":");
            if (parts.length == 2) {
                return (Long.parseLong(parts[0]) * 60000) + (Long.parseLong(parts[1]) * 1000);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private AccessibilityNodeInfo findNodeByPattern(AccessibilityNodeInfo node, java.util.regex.Pattern pattern,
            boolean findLast) {
        if (node == null)
            return null;
        AccessibilityNodeInfo result = null;

        CharSequence text = node.getText();
        if (text != null && pattern.matcher(text.toString()).matches()) {
            result = AccessibilityNodeInfo.obtain(node);
            if (!findLast)
                return result;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo childResult = findNodeByPattern(node.getChild(i), pattern, findLast);
            if (childResult != null) {
                if (result != null)
                    result.recycle();
                result = childResult;
                if (!findLast)
                    return result;
            }
        }
        return result;
    }

    private long parseDurationString(String text) {
        try {
            // Find the "of X" part
            int ofIndex = text.lastIndexOf("of");
            if (ofIndex == -1)
                ofIndex = text.lastIndexOf("/"); // Some versions use /

            if (ofIndex != -1) {
                String maxStr = text.substring(ofIndex).trim();
                long ms = 0;

                // Format: "3 minutes 45 seconds"
                java.util.regex.Matcher mMin = java.util.regex.Pattern.compile("(\\d+)\\s*(min|minute)")
                        .matcher(maxStr);
                if (mMin.find()) {
                    ms += Long.parseLong(mMin.group(1)) * 60000;
                }

                java.util.regex.Matcher mSec = java.util.regex.Pattern.compile("(\\d+)\\s*(sec|second)")
                        .matcher(maxStr);
                if (mSec.find()) {
                    ms += Long.parseLong(mSec.group(1)) * 1000;
                }

                // Fallback Format: "3:45"
                if (ms == 0) {
                    java.util.regex.Matcher mTime = java.util.regex.Pattern.compile("(\\d+):(\\d+)").matcher(maxStr);
                    if (mTime.find()) {
                        ms = (Long.parseLong(mTime.group(1)) * 60000) + (Long.parseLong(mTime.group(2)) * 1000);
                    }
                }

                return ms;
            }
        } catch (Exception e) {
            Log.e(TAG, "[SESSION] Parse duration error: " + e.getMessage());
        }
        return 0;
    }

    public boolean enableRepeat() {
        Log.i(TAG, "[SESSION] Enabling repeat mode...");
        for (int i = 0; i < 5; i++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            AccessibilityNodeInfo repeatBtn = navigator.findNodeByDescription(root, "repeat");
            if (repeatBtn != null) {
                CharSequence desc = repeatBtn.getContentDescription();
                String descStr = desc != null ? desc.toString().toLowerCase() : "";
                Log.i(TAG, "[SESSION][REPEAT_CHECK] Current State: " + descStr);

                // Pattern match for active states: "enabled", "context", "track", "on",
                // "repeat"
                if (descStr.contains("enabled") || descStr.contains("context") || descStr.contains("track")
                        || descStr.contains("on") || descStr.equals("repeat")) {
                    Log.i(TAG, "[SESSION][REPEAT_ALREADY_ENABLED]");
                    repeatBtn.recycle();
                    root.recycle();
                    return true;
                }

                Log.i(TAG, "[SESSION][REPEAT_CLICK] Attempt " + (i + 1));
                boolean clicked = clickNodeWithParentTraversal(repeatBtn, 3);
                repeatBtn.recycle();

                if (clicked) {
                    try {
                        Thread.sleep(1500);
                    } catch (Exception ignored) {
                    }
                    // Verify
                    AccessibilityNodeInfo newRoot = service.getRootInActiveWindow();
                    if (newRoot != null) {
                        AccessibilityNodeInfo verifyBtn = navigator.findNodeByDescription(newRoot, "repeat");
                        if (verifyBtn != null) {
                            CharSequence newDesc = verifyBtn.getContentDescription();
                            String newDescStr = newDesc != null ? newDesc.toString().toLowerCase() : "";
                            Log.i(TAG, "[SESSION][REPEAT_VERIFY] New State: " + newDescStr);
                            if (newDescStr.contains("enabled") || newDescStr.contains("context")
                                    || newDescStr.contains("track") || newDescStr.contains("on")
                                    || newDescStr.equals("repeat")) {
                                Log.i(TAG, "[SESSION][REPEAT_ENABLED]");
                                verifyBtn.recycle();
                                newRoot.recycle();
                                root.recycle();
                                return true;
                            }
                            verifyBtn.recycle();
                        }
                        newRoot.recycle();
                    }
                }
            } else {
                Log.w(TAG, "[SESSION] Repeat button not found on screen. Retry " + (i + 1));
            }
            root.recycle();
            try {
                Thread.sleep(1500);
            } catch (Exception ignored) {
            }
        }
        Log.e(TAG, "[SESSION][REPEAT_FAILED]");
        return false;
    }

    public boolean enableRepeatOneForSession() {
        for (int i = 0; i < 5; i++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;
            AccessibilityNodeInfo repeatBtn = navigator.findNodeByDescription(root, "repeat");
            if (repeatBtn == null) {
                root.recycle();
                continue;
            }

            String desc = lower(repeatBtn.getContentDescription());
            String state = desc.contains("one") || desc.contains("1") ? "ONE"
                    : (desc.contains("all") || desc.contains("context") || desc.contains("on")) ? "ALL" : "OFF";
            Log.i(TAG, "[SESSION][REPEAT_STATE] " + state);

            if ("ONE".equals(state)) {
                repeatBtn.recycle();
                root.recycle();
                return true;
            }

            clickNodeWithParentTraversal(repeatBtn, 3);
            repeatBtn.recycle();
            root.recycle();
            try {
                Thread.sleep(900);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public boolean isRepeatOneActive() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;
        AccessibilityNodeInfo repeatBtn = navigator.findNodeByDescription(root, "repeat");
        boolean active = false;
        if (repeatBtn != null) {
            String desc = lower(repeatBtn.getContentDescription());
            active = desc.contains("one") || desc.contains("1");
            repeatBtn.recycle();
        }
        root.recycle();
        return active;
    }

    public double readSeekbarPosition() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return -1;
        AccessibilityNodeInfo seekbar = navigator.findNodeByResourceId(root, "com.spotify.music:id/seekbar");
        if (seekbar == null) {
            root.recycle();
            return -1;
        }

        double value = -1;
        try {
            CharSequence txt = seekbar.getText();
            if (txt != null) {
                value = Double.parseDouble(txt.toString().trim());
            } else {
                CharSequence desc = seekbar.getContentDescription();
                if (desc != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(desc.toString());
                    if (m.find())
                        value = Double.parseDouble(m.group(1));
                }
            }
        } catch (Exception ignored) {
        }
        seekbar.recycle();
        root.recycle();
        return value;
    }

    public boolean pausePlaybackFromNowPlaying() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;
        AccessibilityNodeInfo pauseBtn = navigator.findNodeByDescription(root, "pause");
        boolean paused = false;
        if (pauseBtn != null) {
            paused = clickNodeWithParentTraversal(pauseBtn, 3);
            pauseBtn.recycle();
        }
        root.recycle();
        return paused;
    }

    public String getCurrentTrackTitle() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return null;

        String[] ids = {
                "com.spotify.music:id/track_title",
                "com.spotify.music:id/now_playing_track_title",
                "com.spotify.music:id/track_name",
                "com.spotify.music:id/now_playing_track_name"
        };

        AccessibilityNodeInfo titleNode = null;
        for (String id : ids) {
            titleNode = navigator.findNodeByResourceId(root, id);
            if (titleNode != null)
                break;
        }

        if (titleNode != null) {
            CharSequence text = titleNode.getText();
            titleNode.recycle();
            root.recycle();
            return text != null ? text.toString() : null;
        }
        root.recycle();
        return null;
    }

    public boolean likeCurrentSong() {
        String title = getCurrentTrackTitle();
        if (title == null) {
            Log.e(TAG, "[LIKE][FAILED] Could not detect track title");
            return false;
        }

        Log.i(TAG, "[LIKE][TRACK_DETECTED] " + title);

        for (int retry = 0; retry < 2; retry++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            // 1. Find and Open Context Menu (3-dots)
            String menuTarget = "Open context menu for " + title;
            AccessibilityNodeInfo menuBtn = navigator.findNodeByDescription(root, menuTarget);

            if (menuBtn != null) {
                Log.i(TAG, "[LIKE][MENU_OPEN] Attempting to open menu for " + title);
                boolean menuClicked = clickNodeWithParentTraversal(menuBtn, 3);
                menuBtn.recycle();
                root.recycle();

                if (menuClicked) {
                    try {
                        Thread.sleep(1500);
                    } catch (Exception ignored) {
                    }

                    // 2. Find "Add to Liked Songs" in the menu
                    AccessibilityNodeInfo menuRoot = service.getRootInActiveWindow();
                    if (menuRoot != null) {
                        AccessibilityNodeInfo likeOption = navigator.findNodeByDescription(menuRoot,
                                "Add to Liked Songs");
                        if (likeOption == null)
                            likeOption = findNodeByText(menuRoot, "Add to Liked Songs");

                        if (likeOption != null) {
                            Log.i(TAG, "[LIKE][LIKE_OPTION_FOUND]");
                            boolean liked = clickNodeWithParentTraversal(likeOption, 3);
                            likeOption.recycle();
                            menuRoot.recycle();

                            if (liked) {
                                Log.i(TAG, "[LIKE][SONG_LIKED]");
                                return true;
                            }
                        } else {
                            // If "Add" is missing, it might be "Remove" (already liked)
                            AccessibilityNodeInfo removeOption = navigator.findNodeByDescription(menuRoot,
                                    "Remove from Liked Songs");
                            if (removeOption == null)
                                removeOption = findNodeByText(menuRoot, "Remove from Liked Songs");

                            if (removeOption != null) {
                                Log.i(TAG, "[LIKE][ALREADY_LIKED]");
                                removeOption.recycle();
                                // Close menu by clicking back
                                service.performGlobalAction(
                                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                                menuRoot.recycle();
                                return true;
                            }

                            Log.w(TAG, "[LIKE][MENU_RETRY] Like option not visible yet");
                            menuRoot.recycle();
                        }
                    }
                }
            } else {
                Log.w(TAG, "[LIKE][BUTTON_NOT_FOUND] Could not find menu button for: " + title);
                root.recycle();
            }
            try {
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }
        }

        Log.e(TAG, "[LIKE][FAILED] Like flow incomplete");
        return false;
    }

    public void batchLikePlaylistTracks(int targetCount) {
        Log.i(TAG, "[PLAYLIST][AUTO_LIKE][START] target=" + targetCount);
        int likedCount = 0;
        java.util.Set<String> processedTrackNames = new java.util.HashSet<>();
        int consecutiveEmptyScans = 0;

        while (likedCount < targetCount && CommandRunner.shouldContinueCurrentTask()) {
            // === COMPLETION LOCK CHECK: Stop if task completed ===
            if (CommandRunner.isCurrentTaskCompleted()) {
                Log.i(TAG, "[PLAYLIST][AUTO_LIKE][TERMINATED] Task marked completed. Exiting batch loop.");
                break;
            }
            
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                break;

            java.util.List<AccessibilityNodeInfo> menuButtons = new java.util.ArrayList<>();
            findNodesByDescriptionStart(root, "Open context menu for", menuButtons);

            if (menuButtons.isEmpty()) {
                Log.w(TAG, "[PLAYLIST][AUTO_LIKE][SCAN] No tracks found. Waiting for UI...");
                try {
                    Thread.sleep(1500);
                } catch (Exception ignored) {
                }
                root.recycle();
                root = service.getRootInActiveWindow();
                if (root == null)
                    break;
                findNodesByDescriptionStart(root, "Open context menu for", menuButtons);
            }

            if (menuButtons.isEmpty()) {
                Log.w(TAG, "[PLAYLIST][AUTO_LIKE][MENU_NOT_FOUND] Page seems empty or non-playlist.");
                consecutiveEmptyScans++;
                if (consecutiveEmptyScans > 2) {
                    root.recycle();
                    break;
                }
            } else {
                consecutiveEmptyScans = 0;
            }

            boolean foundNewInBatch = false;
            for (AccessibilityNodeInfo btn : menuButtons) {
                if (likedCount >= targetCount)
                    break;

                CharSequence desc = btn.getContentDescription();
                String descStr = desc != null ? desc.toString() : "";
                String trackName = descStr.replace("Open context menu for ", "").trim();

                if (processedTrackNames.contains(trackName)) {
                    btn.recycle();
                    continue;
                }

                processedTrackNames.add(trackName);
                foundNewInBatch = true;

                Log.i(TAG, "[PLAYLIST][AUTO_LIKE][ATTEMPT] track=" + trackName);
                boolean clicked = clickNodeWithParentTraversal(btn, 3);
                btn.recycle();

                if (clicked) {
                    try {
                        Thread.sleep(1500);
                    } catch (Exception ignored) {
                    }
                    AccessibilityNodeInfo menuRoot = service.getRootInActiveWindow();
                    if (menuRoot != null) {
                        AccessibilityNodeInfo likeOpt = navigator.findNodeByDescription(menuRoot, "Add to Liked Songs");
                        if (likeOpt == null)
                            likeOpt = findNodeByText(menuRoot, "Add to Liked Songs");

                        if (likeOpt != null) {
                            if (clickNodeWithParentTraversal(likeOpt, 3)) {
                                likedCount++;
                                Log.i(TAG, "[PLAYLIST][AUTO_LIKE][SUCCESS] track=" + trackName + " (" + likedCount + "/"
                                        + targetCount + ")");
                            } else {
                                Log.e(TAG, "[PLAYLIST][AUTO_LIKE][FAILED] Click failed for " + trackName);
                            }
                            likeOpt.recycle();
                        } else {
                            Log.i(TAG, "[PLAYLIST][AUTO_LIKE][TRACK_SKIPPED] Already liked or option missing: "
                                    + trackName);
                            // Close menu
                            service.performGlobalAction(
                                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                        }
                        menuRoot.recycle();
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (Exception ignored) {
                    }
                }
            }

            root.recycle();

            // If we found nothing new on this whole page, we might be at the end
            if (!foundNewInBatch && !menuButtons.isEmpty()) {
                Log.i(TAG, "[PLAYLIST][AUTO_LIKE][END_OF_LIST] No new tracks discovered.");
                break;
            }

            if (likedCount < targetCount) {
                Log.i(TAG, "[PLAYLIST][AUTO_LIKE][NO_SCROLL] Staying on current screen; no further navigation.");
                break;
            }
        }
    }

    public void batchAddPlaylistTracks(int targetCount) {
        if (targetPlaylistName == null || targetPlaylistName.isEmpty())
            return;
        Log.i(TAG, "[PLAYLIST_AUTO][BATCH_ADD][START] target=" + targetCount + " to=" + targetPlaylistName);

        service.waitForIdle();
        try {
            Thread.sleep(2500);
        } catch (Exception ignored) {
        }

        int addedCount = 0;
        java.util.Set<String> processedTracks = new java.util.HashSet<>();

        while (addedCount < targetCount && CommandRunner.shouldContinueCurrentTask()) {
            // === COMPLETION LOCK CHECK: Stop if task completed ===
            if (CommandRunner.isCurrentTaskCompleted()) {
                Log.i(TAG, "[PLAYLIST_AUTO][BATCH_ADD][TERMINATED] Task marked completed. Exiting batch loop.");
                break;
            }
            
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                break;

            // STEP 1: FIND VISIBLE TRACK CONTEXT MENUS
            java.util.List<AccessibilityNodeInfo> menuButtons = new java.util.ArrayList<>();
            findNodesByDescriptionStart(root, "Open context menu for", menuButtons);
            if (menuButtons.isEmpty()) {
                Log.w(TAG, "[PLAYLIST_AUTO][BATCH_ADD] No rows found. Staying on current screen.");
                root.recycle();
                break;
            }

            boolean foundNewInScan = false;
            for (AccessibilityNodeInfo menuBtn : menuButtons) {
                if (addedCount >= targetCount)
                    break;

                CharSequence desc = menuBtn.getContentDescription();
                String trackName = (desc != null)
                        ? desc.toString().replace("Open context menu for ", "")
                                .replace("More options for song ", "").trim()
                        : "Unknown Track";

                if (processedTracks.contains(trackName)) {
                    menuBtn.recycle();
                    continue;
                }

                processedTracks.add(trackName);
                foundNewInScan = true;

                Log.i(TAG, "[PLAYLIST_AUTO][SONG_ROW_FOUND] track=\"" + trackName + "\"");
                Log.i(TAG, "[PLAYLIST_AUTO][SONG_MENU_FOUND]");

                if (addTrackToPlaylistFromMenu(menuBtn, targetPlaylistName)) {
                    addedCount++;
                    Log.i(TAG, "[PLAYLIST_AUTO][ADD_PROGRESS] " + addedCount + "/" + targetCount);
                }
                menuBtn.recycle();
                if (addedCount >= targetCount)
                    break;
                try {
                    Thread.sleep(800);
                } catch (Exception ignored) {
                }
            }

            root.recycle();
            if (addedCount >= targetCount) {
                Log.i(TAG, "[PLAYLIST_AUTO][TARGET_REACHED]");
                break;
            }

            if (!foundNewInScan) {
                Log.i(TAG, "[PLAYLIST_AUTO][BATCH_ADD][NO_SCROLL] Staying on current screen; no further navigation.");
                break;
            }
        }
        Log.i(TAG, "[PLAYLIST_AUTO][BATCH_ADD][COMPLETED] count=" + addedCount);
        service.waitForIdle();
    }

    private boolean addTrackToPlaylistFromMenu(AccessibilityNodeInfo menuBtn, String targetPlaylist) {
        Log.i(TAG, "[PLAYLIST_AUTO][SONG_MENU_CLICK]");
        boolean clicked = clickNodeOrParent(menuBtn);
        if (!clicked)
            return false;

        try {
            Thread.sleep(1200);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "[PLAYLIST_AUTO][SONG_MENU_OPEN]");

        // STEP 3 — FIND "ADD TO PLAYLIST"
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        AccessibilityNodeInfo addOpt = navigator.findNodeByDescription(root, "Add to playlist");
        if (addOpt == null)
            addOpt = findNodeByTextRecursive(root, "Add to playlist");

        if (addOpt != null) {
            Log.i(TAG, "[PLAYLIST_AUTO][ADD_TO_PLAYLIST_FOUND]");
            Log.i(TAG, "[PLAYLIST_AUTO][ADD_TO_PLAYLIST_CLICK]");
            clickNodeOrParent(addOpt);
            addOpt.recycle();
            root.recycle();
            try {
                Thread.sleep(1800);
            } catch (Exception ignored) {
            }

            // STEP 4 — SELECT TARGET PLAYLIST
            AccessibilityNodeInfo pickerRoot = service.getRootInActiveWindow();
            if (pickerRoot != null) {
                String normalizedTarget = normalize(targetPlaylist);
                AccessibilityNodeInfo targetRow = findNormalizedTextRow(pickerRoot, normalizedTarget);

                if (targetRow != null) {
                    Log.i(TAG, "[PLAYLIST_AUTO][TARGET_PLAYLIST_FOUND]");

                    boolean added = clickNodeOrParent(targetRow);
                    if (added) {
                        Log.i(TAG, "[PLAYLIST_AUTO][PLAYLIST_SELECTED]");
                        Log.i(TAG, "[PLAYLIST_AUTO][SONG_ADDED]");
                        targetRow.recycle();
                        pickerRoot.recycle();

                        // STEP 5 — RETURN TO TRACK LIST
                        try {
                            Thread.sleep(1200);
                        } catch (Exception ignored) {
                        }
                        service.performGlobalAction(
                                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                        Log.i(TAG, "[PLAYLIST_AUTO][RETURN_TO_TRACKLIST]");

                        try {
                            Thread.sleep(1800);
                        } catch (Exception ignored) {
                        }
                        return true;
                    }
                    targetRow.recycle();
                }
                pickerRoot.recycle();
            }
        } else {
            root.recycle();
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        }
        return false;
    }

    private AccessibilityNodeInfo findNormalizedTextRow(AccessibilityNodeInfo root, String normalizedTarget) {
        // Try direct titles first
        java.util.List<AccessibilityNodeInfo> titles = root
                .findAccessibilityNodeInfosByViewId("com.spotify.music:id/title");
        if (titles != null) {
            for (AccessibilityNodeInfo title : titles) {
                if (normalize(String.valueOf(title.getText())).equals(normalizedTarget)) {
                    AccessibilityNodeInfo row = title.getParent();
                    title.recycle();
                    return row;
                }
                title.recycle();
            }
        }

        // Fallback: DFS scan for matching text
        return findNormalizedTextRecursive(root, normalizedTarget);
    }

    private AccessibilityNodeInfo findNormalizedTextRecursive(AccessibilityNodeInfo node, String target) {
        if (node == null)
            return null;
        if (normalize(String.valueOf(node.getText())).equals(target)) {
            return node.getParent(); // Return container
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo res = findNormalizedTextRecursive(node.getChild(i), target);
            if (res != null)
                return res;
        }
        return null;
    }

    private void findNodesByResourceId(AccessibilityNodeInfo node, String resourceId,
            java.util.List<AccessibilityNodeInfo> results) {

        if (node == null)
            return;
        String id = node.getViewIdResourceName();
        if (id != null && id.equals(resourceId)) {
            results.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findNodesByResourceId(node.getChild(i), resourceId, results);
        }
    }

    public boolean executeCreateOrAppendPlaylist(String playlistName, int songsToAdd) {
        if (playlistName == null || playlistName.isEmpty())
            return false;
        Log.i(TAG, "[PLAYLIST_AUTO] Start for: " + playlistName);

        // Cache is a hint only. Always verify with a fresh UI scan before deciding.
        if (checkPlaylistCache(playlistName)) {
            Log.i(TAG, "[PLAYLIST_AUTO][CACHE_HINT] Cached match found. Running deterministic verification...");
        }

        // Store for session-based batch adding
        this.targetPlaylistName = playlistName;
        this.targetSongsToAdd = songsToAdd;

        ensureSpotifyIsForeground();

        // STEP 1 — OPEN LIBRARY
        service.logEvent("PLAYLIST_AUTO", "LIBRARY_OPEN", "START", "Opening Library...");
        if (!navigator.navigateTo("Your Library")) {
            Log.e(TAG, "[PLAYLIST_AUTO][LIBRARY_NOT_FOUND]");
            return false;
        }

        service.waitForIdle();
        try {
            Thread.sleep(1800);
        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo libraryRoot = service.getRootInActiveWindow();
        if (libraryRoot == null || !isLibraryScreenReady(libraryRoot)) {
            if (libraryRoot != null)
                libraryRoot.recycle();
            Log.w(TAG, "[PLAYLIST_AUTO][LIBRARY_NOT_READY] Waiting for UI stabilization...");
            boolean stabilized = false;
            for (int retry = 0; retry < 3; retry++) {
                try {
                    Thread.sleep(500);
                } catch (Exception ignored) {
                }
                libraryRoot = service.getRootInActiveWindow();
                if (libraryRoot != null && isLibraryScreenReady(libraryRoot)) {
                    stabilized = true;
                    break;
                }
                if (libraryRoot != null)
                    libraryRoot.recycle();
            }
            if (!stabilized) {
                Log.e(TAG, "[PLAYLIST_AUTO][LIBRARY_NOT_READY] Unable to verify library screen.");
                return false;
            }
        }
        if (libraryRoot != null)
            libraryRoot.recycle();
        Log.i(TAG, "[PLAYLIST_AUTO][LIBRARY_READY]");

        // STEP 2 — VERIFY PLAYLIST EXISTS WITH STRONG MATCHING & BOUNDED DETERMINISTIC FLOW (MAX 3 CYCLES)
        boolean found = false;
        String normalizedTarget = normalize(playlistName);
        java.util.Set<String> previousVisibleTitles = new java.util.LinkedHashSet<>();
        java.util.Set<String> allDiscoveredPlaylists = new java.util.LinkedHashSet<>();

        Log.i(TAG, "[PLAYLIST_AUTO][SCAN_START]");

        for (int i = 0; i < 3; i++) {  // === BOUNDED TO 3 CYCLES FOR DETERMINISTIC FLOW ===
            // === COMPLETION LOCK CHECK: Stop if task already completed ===
            if (!CommandRunner.shouldContinueCurrentTask()) {
                Log.i(TAG, "[PLAYLIST_AUTO][SCAN_ABORTED] Task completion locked");
                AccessibilityNodeInfo abortRoot = service.getRootInActiveWindow();
                if (abortRoot != null)
                    abortRoot.recycle();
                break;
            }
            
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                try {
                    Thread.sleep(500);
                } catch (Exception ignored) {
                }
                continue;
            }

            Log.i(TAG, "[PLAYLIST_AUTO][SCAN_PAGE] page=" + (i + 1) + "/3");
            java.util.LinkedHashSet<String> visibleTitles = new java.util.LinkedHashSet<>();
            if (scanVisiblePlaylistTitles(root, visibleTitles, playlistName)) {
                // === DOUBLE-CHECK BEFORE CONFIRMING (UI STABILIZATION) ===
                Log.i(TAG, "[PLAYLIST_AUTO][MATCH_FOUND_FIRST_SCAN]");
                try {
                    Thread.sleep(1000);  // Wait for UI stabilization
                } catch (Exception ignored) {
                }
                
                // Re-scan to confirm match persists (not a transient UI glitch)
                AccessibilityNodeInfo rootVerify = service.getRootInActiveWindow();
                java.util.LinkedHashSet<String> verifyTitles = new java.util.LinkedHashSet<>();
                boolean stillFound = false;
                if (rootVerify != null) {
                    stillFound = scanVisiblePlaylistTitles(rootVerify, verifyTitles, playlistName);
                    rootVerify.recycle();
                }
                
                if (stillFound) {
                    Log.i(TAG, "[PLAYLIST_AUTO][MATCH_CONFIRMED_DUAL_SCAN]");
                    Log.i(TAG, "[PLAYLIST_AUTO][PLAYLIST_EXISTS] playlist=" + playlistName);
                    service.logEvent("PLAYLIST_AUTO", "FOUND", "SUCCESS", "playlist=" + playlistName);
                    addToPlaylistCache(playlistName);
                    root.recycle();
                    found = true;
                    break;
                } else {
                    Log.w(TAG, "[PLAYLIST_AUTO][MATCH_LOST_ON_RECHECK] False positive in scan. Continuing...");
                }
            }

            // Track all discovered playlists for cache
            allDiscoveredPlaylists.addAll(visibleTitles);

            boolean sawNewContent = false;
            for (String title : visibleTitles) {
                Log.i(TAG, "[PLAYLIST_AUTO][VISIBLE] title=" + title);
                if (!previousVisibleTitles.contains(title)) {
                    sawNewContent = true;
                }
            }

            // End-of-list detection: if same items visible, we've reached the end
            if (i > 0 && visibleTitles.equals(previousVisibleTitles) && !visibleTitles.isEmpty()) {
                Log.i(TAG, "[PLAYLIST_AUTO][END_DETECTED]");
                Log.i(TAG, "[PLAYLIST_AUTO][END_OF_LIBRARY]");
                root.recycle();
                break;
            }

            root.recycle();

            previousVisibleTitles.clear();
            previousVisibleTitles.addAll(visibleTitles);

            if (i >= 2)  // 0, 1, 2 = 3 cycles
                break;

            Log.i(TAG, "[PLAYLIST_AUTO][SCAN_SCROLL] Scrolling to next page...");
            navigator.scrollDown();
            try {
                Thread.sleep(1800);
            } catch (Exception ignored) {
            }
        }

        // Add all discovered playlists to cache for future reference
        for (String discovered : allDiscoveredPlaylists) {
            addToPlaylistCache(discovered);
        }

        // STEP 3 — IF PLAYLIST NOT FOUND (WITH FINAL CONFIRMATION CHECK)
        if (!found) {
            boolean foundOnRecheck = false;
            for (int retry = 0; retry < 2; retry++) {
                try {
                    Thread.sleep(900);
                } catch (Exception ignored) {
                }
                AccessibilityNodeInfo recheckRoot = service.getRootInActiveWindow();
                if (recheckRoot != null) {
                    java.util.LinkedHashSet<String> verifyTitles = new java.util.LinkedHashSet<>();
                    foundOnRecheck = scanVisiblePlaylistTitles(recheckRoot, verifyTitles, playlistName);
                    recheckRoot.recycle();
                }
                if (foundOnRecheck)
                    break;
            }

            if (foundOnRecheck) {
                Log.i(TAG, "[PLAYLIST_AUTO][FOUND_ON_FINAL_RECHECK] playlist=" + playlistName);
                addToPlaylistCache(playlistName);
                return true;
            }

            Log.w(TAG, "[PLAYLIST_AUTO][NOT_FOUND] playlist=" + playlistName + " -> Creating it.");
            service.logEvent("PLAYLIST_AUTO", "NOT_FOUND", "WARNING", "playlist=" + playlistName + " (Will Create)");

            Log.i(TAG, "[PLAYLIST_AUTO][CREATE_FLOW_START]");
            if (!createNewPlaylist(playlistName)) {
                Log.e(TAG, "[PLAYLIST_AUTO][CREATE_FAILED]");
                return false;
            }
        }

        return true;
    }

    private boolean isLibraryScreenReady(AccessibilityNodeInfo root) {
        if (root == null)
            return false;
        if (hasListContainer(root))
            return true;
        return hasAnyTextNode(root);
    }

    private boolean hasAnyTextNode(AccessibilityNodeInfo node) {
        if (node == null)
            return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0))
            return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasAnyTextNode(node.getChild(i)))
                return true;
        }
        return false;
    }

    private boolean scanVisiblePlaylistTitles(AccessibilityNodeInfo node, java.util.Set<String> visibleTitles,
            String targetPlaylistName) {
        if (node == null)
            return false;

        boolean found = false;
        if (node.isVisibleToUser()) {
            found = checkVisibleString(node.getText(), visibleTitles, targetPlaylistName) || found;
            found = checkVisibleString(node.getContentDescription(), visibleTitles, targetPlaylistName) || found;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (scanVisiblePlaylistTitles(node.getChild(i), visibleTitles, targetPlaylistName))
                found = true;
        }
        return found;
    }

    private boolean checkVisibleString(CharSequence value, java.util.Set<String> visibleTitles, String targetPlaylistName) {
        if (value == null)
            return false;

        String rawValue = value.toString();
        String normalizedValue = normalize(rawValue);
        if (normalizedValue.isEmpty())
            return false;

        visibleTitles.add(rawValue.trim());
        
        // Use STRONG matching instead of exact string comparison
        return isPlaylistNameMatch(rawValue, targetPlaylistName);
    }

    private boolean createNewPlaylist(String name) {
        // A) Open Create Tab
        if (!navigator.navigateTo("Create, Tab 4 of 4")) {
            Log.e(TAG, "[PLAYLIST_AUTO][CREATE_TAB_FAILED]");
            return false;
        }
        Log.i(TAG, "[PLAYLIST_AUTO][CREATE_TAB_OPEN]");

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return false;

        // B) Click Playlist
        AccessibilityNodeInfo playlistOpt = findNodeByText(root, "Playlist");
        if (playlistOpt != null) {
            Log.i(TAG, "[PLAYLIST_AUTO][PLAYLIST_OPTION_CLICKED]");
            clickNodeWithParentTraversal(playlistOpt, 3);
            playlistOpt.recycle();
            root.recycle();
            try {
                Thread.sleep(2000);
            } catch (Exception ignored) {
            }

            // C) Name Input
            AccessibilityNodeInfo dialogRoot = service.getRootInActiveWindow();
            if (dialogRoot != null) {
                AccessibilityNodeInfo input = findNodeByClass(dialogRoot, "android.widget.EditText");
                if (input != null) {
                    Log.i(TAG, "[PLAYLIST_AUTO][NAME_INPUT]");
                    setTextOnly(input, name);
                    input.recycle();
                    try {
                        Thread.sleep(800);
                    } catch (Exception ignored) {
                    }

                    Log.i(TAG, "[PLAYLIST_AUTO][NAME_VERIFIED]");

                    // D) Submit
                    for (int retry = 0; retry < 3; retry++) {
                        AccessibilityNodeInfo currentRoot = service.getRootInActiveWindow();
                        if (currentRoot == null)
                            continue;

                        AccessibilityNodeInfo createBtnText = findNodeByText(currentRoot, "Create");
                        if (createBtnText != null) {
                            Log.i(TAG, "[PLAYLIST_AUTO][CREATE_BUTTON_CLICK] attempt=" + (retry + 1));

                            // Find clickable parent container
                            AccessibilityNodeInfo clickable = findClickableParent(createBtnText);
                            if (clickable != null) {
                                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                clickable.recycle();
                            } else {
                                clickNodeWithParentTraversal(createBtnText, 3);
                            }
                            createBtnText.recycle();

                            try {
                                Thread.sleep(2500);
                            } catch (Exception ignored) {
                            }

                            // Verification Loop
                            AccessibilityNodeInfo vRoot = service.getRootInActiveWindow();
                            if (vRoot != null) {
                                if (findNodeByTextRecursive(vRoot, name) != null) {
                                    Log.i(TAG, "[PLAYLIST_AUTO][CREATE_SUCCESS]");
                                    service.logEvent("PLAYLIST_AUTO", "CREATE", "SUCCESS", name);
                                    vRoot.recycle();
                                    currentRoot.recycle();
                                    dialogRoot.recycle();
                                    return true;
                                }
                                vRoot.recycle();
                            }
                        }
                        currentRoot.recycle();
                        try {
                            Thread.sleep(1000);
                        } catch (Exception ignored) {
                        }
                    }
                    Log.e(TAG, "[PLAYLIST_AUTO][CREATE_SUCCESS_VERIFY_FAILED]");
                }
                if (dialogRoot != null)
                    dialogRoot.recycle();
            }
        } else {
            root.recycle();
        }
        return false;
    }

    public boolean addCurrentTrackToPlaylist(String playlistName) {
        String title = getCurrentTrackTitle();
        if (title == null)
            return false;
        Log.i(TAG, "[PLAYLIST_AUTO] Adding track to " + playlistName + ": " + title);

        for (int retry = 0; retry < 3; retry++) {
            service.waitForIdle();
            try {
                Thread.sleep(900);
            } catch (Exception ignored) {
            }

            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null)
                continue;

            String menuTarget = "Open context menu for " + title;
            AccessibilityNodeInfo menuBtn = navigator.findNodeByDescription(root, menuTarget);
            if (menuBtn == null)
                menuBtn = findNodeByDescriptionRecursive(root, "open context menu for");
            if (menuBtn == null)
                menuBtn = findNodeByDescriptionRecursive(root, "more options");
            if (menuBtn == null)
                menuBtn = findNodeByDescriptionRecursive(root, "options");

            if (menuBtn != null) {
                Log.i(TAG, "[AUTO_ADD][MENU_OPEN] attempt=" + (retry + 1));
                boolean menuClicked = clickNodeWithParentTraversal(menuBtn, 3);
                menuBtn.recycle();
                root.recycle();

                if (menuClicked) {
                    try {
                        Thread.sleep(1500);
                    } catch (Exception ignored) {
                    }
                    AccessibilityNodeInfo menuRoot = service.getRootInActiveWindow();
                    if (menuRoot != null) {
                        AccessibilityNodeInfo addOpt = findNodeByText(menuRoot, "Add to playlist");
                        if (addOpt == null)
                            addOpt = findNodeByText(menuRoot, "Add to a playlist");
                        if (addOpt == null)
                            addOpt = findNodeByText(menuRoot, "Save to playlist");
                        if (addOpt == null)
                            addOpt = findNodeByDescriptionRecursive(menuRoot, "add to playlist");
                        if (addOpt == null)
                            addOpt = findNodeByDescriptionRecursive(menuRoot, "save to playlist");

                        if (addOpt != null) {
                            Log.i(TAG, "[AUTO_ADD][ADD_TO_PLAYLIST_CLICK]");
                            clickNodeWithParentTraversal(addOpt, 3);
                            addOpt.recycle();
                            menuRoot.recycle();
                            try {
                                Thread.sleep(2000);
                            } catch (Exception ignored) {
                            }

                            // Find and click the target playlist row's PLUS button
                            AccessibilityNodeInfo pickerRoot = service.getRootInActiveWindow();
                            if (pickerRoot != null) {
                                AccessibilityNodeInfo targetRowText = findNormalizedTextRow(pickerRoot, normalize(playlistName));
                                if (targetRowText != null) {
                                    Log.i(TAG, "[AUTO_ADD][TARGET_FOUND]");

                                    // Scope search inside matching playlist row
                                    AccessibilityNodeInfo rowContainer = AccessibilityNodeInfo.obtain(targetRowText);
                                    if (rowContainer != null) {
                                        AccessibilityNodeInfo plusBtn = findNodeByDescriptionRecursive(rowContainer,
                                                "Add to playlist");
                                        if (plusBtn == null)
                                            plusBtn = findNodeByDescriptionRecursive(rowContainer, "Add");

                                        if (plusBtn != null) {
                                            Log.i(TAG, "[AUTO_ADD][PLUS_CLICKED]");
                                            plusBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                            plusBtn.recycle();
                                            Log.i(TAG, "[AUTO_ADD][SONG_ADDED]");
                                            rowContainer.recycle();
                                            pickerRoot.recycle();
                                            return true;
                                        }
                                        rowContainer.recycle();
                                    }

                                    // Fallback: click the row text if no plus found
                                    clickNodeWithParentTraversal(targetRowText, 3);
                                    targetRowText.recycle();
                                    pickerRoot.recycle();
                                    return true;
                                }
                                pickerRoot.recycle();
                            }
                        } else {
                            Log.w(TAG, "[AUTO_ADD][ADD_OPTION_NOT_FOUND] attempt=" + (retry + 1));
                            menuRoot.recycle();
                        }
                    }
                }
            } else {
                Log.w(TAG, "[AUTO_ADD][MENU_NOT_FOUND] attempt=" + (retry + 1));
                root.recycle();
            }
            try {
                Thread.sleep(1000);
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
