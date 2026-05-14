package com.example.project2;

import org.json.JSONObject;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import android.os.PowerManager;
import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import android.util.Log;

/**
 * CORE ENGINE: Manages FIFO command queue and execution lifecycle.
 * Lifecycle: RECEIVE -> VALIDATE -> EXECUTE -> VERIFY -> REPORT
 */
public class CommandRunner {
    private static final String TAG = "SpotifyBot";
    private enum TaskType {
        IDLE,
        SONG_PLAY,
        PLAYLIST,
        SESSION,
        SEARCH_ONLY
    }

    private enum SessionType {
        NORMAL_PLAY,      // No Now Playing, no repeat, just play + verify
        SCHEDULED_PLAY    // Full session: Now Playing, duration extraction, loop control
    }

    /**
     * EXPLICIT SUCCESS CONTRACT: Task result tracking with verification signals
     * Prevents false-negative failures by requiring verified UI state
     */
    private static final class TaskResult {
        enum Status {
            PENDING,      // Task not yet completed
            SUCCESS,      // Execution succeeded and UI verified
            FAILED,       // Task failed or verification failed
            TIMEOUT       // Task timed out
        }
        
        String taskId;
        String action;
        Status status = Status.PENDING;
        String reason = "";
        java.util.List<String> verificationSignals = new java.util.ArrayList<>();
        long completedAt = 0L;
        
        TaskResult(String taskId, String action) {
            this.taskId = taskId;
            this.action = action;
        }
        
        void markSuccess(String reason) {
            this.status = Status.SUCCESS;
            this.reason = reason;
            this.completedAt = System.currentTimeMillis();
            Log.i(TAG, "[RESULT][SUCCESS] taskId=" + taskId + " action=" + action + " reason=" + reason);
        }
        
        void markFailed(String reason) {
            this.status = Status.FAILED;
            this.reason = reason;
            this.completedAt = System.currentTimeMillis();
            Log.w(TAG, "[RESULT][FAILED] taskId=" + taskId + " action=" + action + " reason=" + reason);
        }
        
        void addSignal(String signal) {
            verificationSignals.add(signal);
        }
        
        boolean isSuccess() {
            return status == Status.SUCCESS;
        }
    }

    /**
     * RESULT VALIDATOR: Performs post-execution UI verification
     * Implements soft success detection and 2-phase completion model
     */
    private final class ResultValidator {
        private final MyAccessibilityService service;
        private final SpotifyNavigator navigator;
        private final SpotifyActions actions;
        
        ResultValidator(MyAccessibilityService service, SpotifyNavigator navigator, SpotifyActions actions) {
            this.service = service;
            this.navigator = navigator;
            this.actions = actions;
        }
        
        /**
         * PHASE 2: VERIFICATION - Post-execution UI state validation
         * Re-checks UI after execution to confirm action effects
         */
        TaskResult finalizeTask(String commandId, String action, TaskResult executionResult, boolean executionSucceeded) {
            Log.i(TAG, "[FINALIZE][START] action=" + action + " executionSuccess=" + executionSucceeded);
            
            // Wait for UI stabilization (1-3 second buffer)
            try {
                Thread.sleep(1500);
            } catch (Exception ignored) {
            }
            
            // Capture current UI state
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) {
                executionResult.markFailed("UI_UNAVAILABLE");
                return executionResult;
            }
            
            if (isPlaybackAction(action) && executionSucceeded && taskContext.sessionType == SessionType.SCHEDULED_PLAY
                    && runtimeState != null && runtimeState.taskCompleted) {
                executionResult.addSignal("SESSION_COMPLETED");
                executionResult.markSuccess("SESSION_COMPLETED");
                Log.i(TAG, "[FINALIZE][SUCCESS] Scheduled session completed; playback was intentionally stopped at end");
                root.recycle();
                return executionResult;
            }
            
            if (isPlaybackAction(action) && executionSucceeded && actions.isPlaybackActive()) {
                executionResult.addSignal("PLAYBACK_ACTIVE");
                executionResult.markSuccess("PLAYBACK_VERIFIED");
                Log.i(TAG, "[FINALIZE][SUCCESS] Playback verified; optional UI actions cannot fail play task");
                root.recycle();
                return executionResult;
            }
            
            boolean uiIsStable = checkNavigationConsistency(root) && 
                                 checkExpectedScreenForAction(action, root);
            
            if (uiIsStable) {
                executionResult.addSignal("UI_STABLE");
            }
            
            // === SOFT SUCCESS DETECTION ===
            // Even without explicit success flag, detect from UI state
            boolean softSuccess = detectSoftSuccess(action, root, executionSucceeded);
            
            if (executionSucceeded && uiIsStable) {
                // Execution succeeded AND UI verified
                executionResult.markSuccess("EXECUTION_AND_VERIFICATION_OK");
                Log.i(TAG, "[FINALIZE][SUCCESS] Both execution and verification passed");
            } else if (softSuccess) {
                // Soft success: action effects visible even without explicit signal
                executionResult.markSuccess("SOFT_SUCCESS_DETECTED");
                Log.i(TAG, "[FINALIZE][SUCCESS] Soft success detected from UI state");
            } else if (executionSucceeded && !uiIsStable) {
                // Execution reported success but UI not verified
                Log.w(TAG, "[FINALIZE][WARNING] Execution succeeded but UI verification failed. Checking for action effects...");
                if (checkActionEffectsVisible(action, root)) {
                    executionResult.markSuccess("ACTION_EFFECTS_VISIBLE");
                    Log.i(TAG, "[FINALIZE][SUCCESS] Action effects confirmed in UI");
                } else {
                    executionResult.markFailed("EXECUTION_SUCCESS_BUT_UI_INVALID");
                    Log.e(TAG, "[FINALIZE][FAILED] No action effects visible despite execution success");
                }
            } else {
                // Execution failed
                executionResult.markFailed("EXECUTION_FAILED");
                Log.e(TAG, "[FINALIZE][FAILED] Execution did not complete successfully");
            }
            
            root.recycle();
            return executionResult;
        }
        
        /**
         * Check if mini-player state is correct (presence indicates playback active)
         */
        private boolean checkMiniPlayerState(AccessibilityNodeInfo root) {
            AccessibilityNodeInfo miniPlayer = navigator.findNodeByResourceId(root, 
                "com.spotify.music:id/now_playing_bar");
            if (miniPlayer != null) {
                Log.i(TAG, "[VERIFY][MINI_PLAYER_PRESENT] Playback active");
                miniPlayer.recycle();
                return true;
            }
            Log.w(TAG, "[VERIFY][MINI_PLAYER_ABSENT] No mini-player visible");
            return false;
        }

        private boolean isPlaybackAction(String action) {
            return "play_from_search".equals(action) || "play_album".equals(action)
                    || "play_artist".equals(action) || "play_playlist".equals(action);
        }
        
        /**
         * Check navigation consistency (e.g., not stuck in menu, on expected tab)
         */
        private boolean checkNavigationConsistency(AccessibilityNodeInfo root) {
            // Check for stuck menus
            AccessibilityNodeInfo backBtn = navigator.findNodeByDescription(root, "Back");
            if (backBtn != null) {
                backBtn.recycle();
                Log.w(TAG, "[VERIFY][BACK_BTN_PRESENT] Possible menu state");
                return false;  // Still in menu
            }
            
            Log.i(TAG, "[VERIFY][NAVIGATION_OK] Not in modal menu");
            return true;
        }
        
        /**
         * Check if UI shows expected screen for given action
         */
        private boolean checkExpectedScreenForAction(String action, AccessibilityNodeInfo root) {
            if ("play_from_search".equals(action) || "search".equals(action)) {
                // Should be on search tab
                AccessibilityNodeInfo searchTab = navigator.findNodeByText(root, "Search");
                if (searchTab != null) {
                    searchTab.recycle();
                    Log.i(TAG, "[VERIFY][SCREEN_OK] On search tab");
                    return true;
                }
                Log.w(TAG, "[VERIFY][SCREEN_MISMATCH] Not on search tab");
                return false;
            } else if ("play_playlist".equals(action)) {
                // Should be in library or on playlist
                AccessibilityNodeInfo libraryBtn = navigator.findNodeByText(root, "Your Library");
                if (libraryBtn != null) {
                    libraryBtn.recycle();
                    Log.i(TAG, "[VERIFY][SCREEN_OK] In library");
                    return true;
                }
                Log.w(TAG, "[VERIFY][SCREEN_UNCERTAIN] Unclear if in library");
                return false;  // Conservative: require confirmation
            } else if ("play_album".equals(action)) {
                // Check for album-specific UI elements
                AccessibilityNodeInfo header = navigator.findNodeByResourceId(root, 
                    "com.spotify.music:id/header_image");
                if (header != null) {
                    header.recycle();
                    Log.i(TAG, "[VERIFY][SCREEN_OK] Album header visible");
                    return true;
                }
                // Fallback: check for mini-player
                AccessibilityNodeInfo miniPlayer = navigator.findNodeByResourceId(root, 
                    "com.spotify.music:id/now_playing_bar");
                if (miniPlayer != null) {
                    miniPlayer.recycle();
                    Log.i(TAG, "[VERIFY][SCREEN_OK] Mini-player active (album play confirmed)");
                    return true;
                }
                Log.w(TAG, "[VERIFY][SCREEN_UNCERTAIN] Album screen not clearly detected");
                return false;
            } else if ("play_artist".equals(action)) {
                // Artist page: check for artist name or play button
                AccessibilityNodeInfo artistName = navigator.findNodeByResourceId(root, 
                    "com.spotify.music:id/title");
                if (artistName != null) {
                    CharSequence text = artistName.getText();
                    if (text != null && text.toString().length() > 0) {
                        Log.i(TAG, "[VERIFY][SCREEN_OK] Artist content visible");
                        artistName.recycle();
                        return true;
                    }
                    artistName.recycle();
                }
                // Fallback: mini-player confirmation
                AccessibilityNodeInfo miniPlayer = navigator.findNodeByResourceId(root, 
                    "com.spotify.music:id/now_playing_bar");
                if (miniPlayer != null) {
                    miniPlayer.recycle();
                    Log.i(TAG, "[VERIFY][SCREEN_OK] Mini-player active (artist play confirmed)");
                    return true;
                }
                Log.w(TAG, "[VERIFY][SCREEN_UNCERTAIN] Artist screen not clearly detected");
                return false;
            }
            Log.i(TAG, "[VERIFY][SCREEN_UNSPECIFIED] No specific screen check for action: " + action);
            return true;  // Unknown action, accept if no errors
        }
        
        /**
         * SOFT SUCCESS DETECTION: Infer success from UI state when no explicit signal
         */
        private boolean detectSoftSuccess(String action, AccessibilityNodeInfo root, boolean executionSucceeded) {
            if (!executionSucceeded) {
                return false;  // Execution explicitly failed
            }
            
            // Check for error states first (highest priority negative signal)
            if (hasErrorNodes(root)) {
                Log.w(TAG, "[SOFT_SUCCESS_FAILED] Error nodes detected in UI");
                return false;
            }
            
            // If execution succeeded, check for action effects
            boolean effectsVisible = checkActionEffectsVisible(action, root);
            if (effectsVisible) {
                Log.i(TAG, "[SOFT_SUCCESS] Action effects visible: " + action);
                return true;
            }
            
            // Fallback: check mini-player as generic success indicator for play actions
            boolean miniPlayerPresent = checkMiniPlayerState(root);
            if (miniPlayerPresent && ("play_from_search".equals(action) || "play_album".equals(action) 
                    || "play_artist".equals(action) || "play_playlist".equals(action))) {
                Log.i(TAG, "[SOFT_SUCCESS] Mini-player present for play action: " + action);
                return true;
            }
            
            Log.w(TAG, "[SOFT_SUCCESS_FAILED] Cannot detect success for action: " + action);
            return false;
        }
        
        /**
         * Check for error-related UI nodes (error dialogs, toasts, exception messages)
         */
        private boolean hasErrorNodes(AccessibilityNodeInfo root) {
            // Check for error dialog title
            AccessibilityNodeInfo errorDialog = navigator.findNodeByText(root, "Error");
            if (errorDialog != null) {
                Log.w(TAG, "[ERROR_DETECTED] Error dialog present");
                errorDialog.recycle();
                return true;
            }
            
            // Check for error toast/snackbar content
            AccessibilityNodeInfo errorContent = navigator.findNodeByText(root, "failed");
            if (errorContent != null) {
                errorContent.recycle();
                Log.w(TAG, "[ERROR_DETECTED] Error text found");
                return true;
            }
            
            return false;  // No error nodes detected
        }
        
        /**
         * Check if action effects are visible in current UI
         */
        private boolean checkActionEffectsVisible(String action, AccessibilityNodeInfo root) {
            if ("like".equals(action)) {
                // Check if heart icon is filled/solid
                AccessibilityNodeInfo heart = navigator.findNodeByDescription(root, "Added to Liked Songs");
                if (heart != null) {
                    heart.recycle();
                    Log.i(TAG, "[EFFECTS][LIKE_CONFIRMED] Heart is solid");
                    return true;
                }
                return false;
            } else if ("play_from_search".equals(action) || "play_album".equals(action) 
                    || "play_artist".equals(action) || "play_playlist".equals(action)) {
                // Playback effect: mini-player with pause button
                AccessibilityNodeInfo pauseBtn = navigator.findNodeByDescription(root, "pause");
                if (pauseBtn != null) {
                    pauseBtn.recycle();
                    Log.i(TAG, "[EFFECTS][PLAYBACK_CONFIRMED] Pause button visible");
                    return true;
                }
                return false;
            }
            Log.i(TAG, "[EFFECTS][UNSPECIFIED] Cannot verify action effects for: " + action);
            return true;  // Conservative: accept unknown
        }
    }

    /**
     * GLOBAL TASK MANAGER: Provides strict task lifecycle boundaries
     * Prevents state bleed between commands and enforces termination signals
     * INCLUDES COMPLETION LOCK: Hard barrier after success to prevent post-success crawling
     */
    private static final class TaskManager {
        private static volatile String currentTaskId = "";
        private static volatile boolean isTaskCompleted = false;
        private static volatile boolean isTaskCancelled = false;
        private static volatile boolean isCompletionLocked = false;  // CRITICAL: Hard stop after success
        
        static synchronized void startNewTask(String taskId) {
            if (!currentTaskId.isEmpty() && !isTaskCompleted && !isTaskCancelled) {
                Log.w(TAG, "[TASK_MANAGER] Cancelling previous task: " + currentTaskId);
                isTaskCancelled = true;
            }
            currentTaskId = taskId;
            isTaskCompleted = false;
            isTaskCancelled = false;
            isCompletionLocked = false;  // Reset lock for new task
            Log.i(TAG, "[TASK_MANAGER] Started new task: " + taskId);
        }
        
        static synchronized void completeTask(String taskId) {
            if (taskId.equals(currentTaskId)) {
                isTaskCompleted = true;
                Log.i(TAG, "[TASK_MANAGER] Task completed: " + taskId);
            }
        }
        
        static synchronized void lockCompletion(String taskId) {
            if (taskId.equals(currentTaskId)) {
                isCompletionLocked = true;
                isTaskCompleted = true;
                Log.i(TAG, "[TASK_MANAGER][COMPLETION_LOCK] Task locked. No further actions allowed: " + taskId);
            }
        }
        
        static synchronized void cancelTask(String taskId) {
            if (taskId.equals(currentTaskId)) {
                isTaskCancelled = true;
                Log.i(TAG, "[TASK_MANAGER] Task cancelled: " + taskId);
            }
        }
        
        static synchronized boolean shouldContinueTask(String taskId) {
            if (isCompletionLocked && taskId.equals(currentTaskId)) {
                Log.w(TAG, "[TASK_MANAGER][BLOCKED] Completion locked. Stopping execution.");
                return false;  // Hard stop
            }
            return taskId.equals(currentTaskId) && !isTaskCompleted && !isTaskCancelled;
        }
        
        static synchronized boolean isCurrentTaskCompleted() {
            return isTaskCompleted;
        }
        
        static synchronized boolean isCurrentTaskCancelled() {
            return isTaskCancelled;
        }
        
        static synchronized boolean isCurrentTaskLocked() {
            return isCompletionLocked;
        }
    }

    private static final class TaskContext {
        private String currentTaskId = "";
        private TaskType taskType = TaskType.IDLE;
        private SessionType sessionType = SessionType.NORMAL_PLAY;
        private String expectedAction = "";        // Guard against wrong action in same task
        private boolean isCompleted = false;
        private boolean isCancelled = false;        // NEW: Explicit cancellation flag
        private boolean resetOnCompletion = true;
        private boolean sessionEnabled = false;
        private boolean likeRequested = false;
        private boolean playlistRequested = false;
        private long taskStartTime = 0L;
        private int retryCount = 0;

        private void reset() {
            currentTaskId = "";
            taskType = TaskType.IDLE;
            sessionType = SessionType.NORMAL_PLAY;
            expectedAction = "";
            isCompleted = false;
            isCancelled = false;
            resetOnCompletion = true;
            sessionEnabled = false;
            likeRequested = false;
            playlistRequested = false;
            taskStartTime = System.currentTimeMillis();
            retryCount = 0;
        }

        /**
         * Guard: Check if task has been explicitly cancelled.
         */
        private boolean isCancelled() {
            return isCancelled || !TaskManager.shouldContinueTask(currentTaskId);
        }

        /**
         * Guard: Ensure action matches expected task type.
         * Prevents cross-execution (e.g., playlist logic leaking into song flow).
         */
        private boolean validateActionMatch(String action) {
            if (expectedAction.isEmpty()) {
                expectedAction = action;
                return true;
            }
            boolean matches = expectedAction.equals(action);
            if (!matches) {
                Log.w("SpotifyBot", "[STATE_GUARD] Action mismatch! Expected=" + expectedAction + " Got=" + action);
            }
            return matches;
        }

        /**
         * Guard: Check if task has exceeded max duration or retry limit.
         */
        private boolean isTaskValid() {
            long elapsed = System.currentTimeMillis() - taskStartTime;
            if (elapsed > 600000L) { // 10 minute timeout
                Log.w("SpotifyBot", "[STATE_GUARD] Task exceeded max duration: " + elapsed + "ms");
                return false;
            }
            return true;
        }
    }

    private enum ScreenState {
        SEARCH_SCREEN,
        LIBRARY_SCREEN,
        PLAYLIST_SCREEN,
        NOW_PLAYING_SCREEN,
        CONTEXT_MENU_OPEN,
        UNKNOWN
    }

    private static final class TaskRuntimeState {
        ScreenState currentScreen = ScreenState.UNKNOWN;
        String currentNodeContext = "";
        boolean taskCompleted = false;
        boolean navigationLocked = false;
        int playlistLikesRemaining = 0;
        int playlistLikesDone = 0;
    }

    private final MyAccessibilityService service;
    private final SpotifyNavigator navigator;
    private final SpotifyActions actions;
    private final ResultValidator resultValidator;
    private final BlockingQueue<JSONObject> queue = new LinkedBlockingQueue<>();
    private boolean isRunning = false;
    private volatile boolean forceStopSession = false;
    private volatile String activeSessionId = null;
    private final TaskContext taskContext = new TaskContext();
    private TaskRuntimeState runtimeState = null;
    
    private final java.util.LinkedHashMap<String, Boolean> seenCommandIds = new java.util.LinkedHashMap<String, Boolean>(100, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
            return size() > 100;
        }
    };
    private static final int MAX_RETRIES = 2;

    // === STATIC ACCESSORS FOR TASKMANAGER (used by SpotifyActions) ===
    public static boolean isCurrentTaskCompleted() {
        return TaskManager.isCurrentTaskCompleted();
    }

    public static boolean isCurrentTaskCancelled() {
        return TaskManager.isCurrentTaskCancelled();
    }

    public static void completeCurrentTask() {
        TaskManager.completeTask(TaskManager.currentTaskId);
    }

    public static void lockCompletion() {
        TaskManager.lockCompletion(TaskManager.currentTaskId);
    }

    public static boolean shouldContinueCurrentTask() {
        return TaskManager.shouldContinueTask(TaskManager.currentTaskId);
    }

    public CommandRunner(MyAccessibilityService service) {
        this.service = service;
        this.navigator = new SpotifyNavigator(service);
        this.actions = new SpotifyActions(service, navigator);
        this.resultValidator = new ResultValidator(service, navigator, actions);
        startQueueProcessor();
    }

    public void enqueue(JSONObject command) {
        String type = command.optString("type");
        if ("STOP_SESSION".equals(type)) {
            String sid = command.optString("session_id");
            if (sid.equals(activeSessionId)) {
                forceStopSession = true;
                android.util.Log.i("SpotifyBot", "[SESSION] Received STOP_SESSION for " + sid);
            }
            return; // Don't enqueue stop commands
        }

        String action = command.optString("action");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            android.widget.Toast.makeText(service, "🚀 Command: " + action, android.widget.Toast.LENGTH_SHORT).show();
        });
        queue.add(command);
    }

    private void startQueueProcessor() {
        if (isRunning) return;
        isRunning = true;
        new Thread(() -> {
            while (isRunning) {
                try {
                    JSONObject cmd = queue.take();
                    executeLifecycle(cmd);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private void executeLifecycle(JSONObject cmd) {
        String commandId = cmd.optString("command_id");
        String action = cmd.optString("action");
        JSONObject payload = cmd.optJSONObject("payload");
        long issuedAt = cmd.optLong("issued_at", 0);
        long ttlMs = cmd.optLong("ttl_ms", 0);

        // 1. VALIDATE & DEDUPLICATE
        if (commandId.isEmpty() || action.isEmpty()) {
            report(commandId, "VALIDATE", "FAILED", "Missing fields");
            return;
        }

        if (issuedAt > 0 && ttlMs > 0) {
            long expiration = issuedAt + ttlMs;
            if (System.currentTimeMillis() > expiration) {
                report(commandId, "VALIDATE", "FAILED", "COMMAND_TTL_EXCEEDED");
                return;
            }
        }

        if (seenCommandIds.containsKey(commandId)) {
            android.util.Log.w("SpotifyBot", "[COMMAND][DUPLICATE] Dropping " + commandId);
            return;
        }
        seenCommandIds.put(commandId, true);

        report(commandId, "VALIDATE", "OK", action);

        taskContext.reset();
        taskContext.currentTaskId = java.util.UUID.randomUUID().toString();
        
        // 1.5 START GLOBAL TASK CONTEXT (CRITICAL for state isolation)
        TaskManager.startNewTask(taskContext.currentTaskId);
        Log.i("SpotifyBot", "[TASK_ISOLATION] New task started: id=" + taskContext.currentTaskId + " action=" + action);
        
        taskContext.taskType = resolveTaskType(action, cmd, payload);
        taskContext.sessionEnabled = isExplicitSessionRequest(cmd, payload);
        taskContext.sessionType = determineSessionType(taskContext.sessionEnabled, payload);
        taskContext.expectedAction = action;  // Guard against action switching
        taskContext.likeRequested = payload != null && payload.optInt("auto_like_count", 0) > 0;
        boolean playlistAutomationEnabled = payload != null && payload.optBoolean("playlist_automation_enabled", false);
        taskContext.playlistRequested = playlistAutomationEnabled && payload != null
                && (!payload.optString("playlist_name", "").isEmpty() || payload.optInt("songs_to_add", 0) > 0);

        // === STRICT ROUTING GUARD ===
        if (!enforceStrictRouting(action, taskContext, payload)) {
            report(commandId, "ROUTING", "REJECTED", "Action conflicts with task context. Aborting.");
            TaskManager.cancelTask(taskContext.currentTaskId);
            return;
        }

        actions.resetAutomationState();

        // 1.6 Pre-Flight: Check Screen State & UI Stability
        if (!ensureUIStability(commandId)) {
            report(commandId, "EXECUTE", "FAILED", "UI not stable");
            completeTask(cmd, commandId, action, false, "UI_STABILITY_CHECK_FAILED");
            return;
        }

        boolean scheduledSongMode = isScheduledSongMode(cmd, payload, action);
        boolean isSession = taskContext.sessionEnabled || scheduledSongMode;
        if (scheduledSongMode) {
            taskContext.sessionEnabled = true;
            taskContext.sessionType = SessionType.SCHEDULED_PLAY;
            taskContext.likeRequested = false;
            playlistAutomationEnabled = false;
            taskContext.playlistRequested = false;
        }
        if (isSession && "play_playlist".equals(action)) {
            playlistAutomationEnabled = false;
            taskContext.playlistRequested = false;
        }
        int autoLikeCount = scheduledSongMode ? 0 : (payload != null ? payload.optInt("auto_like_count", 0) : 0);
        String playlistName = playlistAutomationEnabled && payload != null ? payload.optString("playlist_name", "") : "";
        int songsToAdd = playlistAutomationEnabled && payload != null ? payload.optInt("songs_to_add", 0) : 0;

        if (isSession) {
            String sessionId = cmd.optString("session_id", "adhoc_" + System.currentTimeMillis());
            long sessionStartTime = cmd.optLong("session_start_time", System.currentTimeMillis());
            long sessionEndTime = resolveSessionEndTime(cmd, payload, sessionStartTime);
            activeSessionId = sessionId;
            forceStopSession = false;

            // --- PLAYLIST AUTOMATION SETUP (PRE-FLIGHT) ---
            int addedToPlaylistCount = 0;
            java.util.Set<String> addedTracks = new java.util.HashSet<>();
            if (!scheduledSongMode && playlistAutomationEnabled && !playlistName.isEmpty()) {
                report(commandId, "PLAYLIST_AUTO", "START", "Preparing playlist: " + playlistName);
                if (!actions.executeCreateOrAppendPlaylist(playlistName, songsToAdd)) {
                    report(commandId, "PLAYLIST_AUTO", "ABORTED", "playlist_setup_failed");
                    completeTask(cmd, commandId, action, false, "PLAYLIST_SETUP_FAILED");
                    return;
                }
            }
            // ----------------------------------------------

            if (taskContext.taskType == TaskType.PLAYLIST) {
                playlistName = payload != null ? payload.optString("playlist_name", "") : "";
            }
            
            // 1. Initial Playback Start
            boolean success;
            if (scheduledSongMode && "play_from_search".equals(action)) {
                success = runScheduledSongSession(commandId, payload, autoLikeCount, playlistName, songsToAdd,
                        sessionStartTime, sessionEndTime);
            } else {
                success = runActionLoop(commandId, action, payload, autoLikeCount);
            }
            
            if (success) {
                // 2. Setup Session Engine (ONLY for SCHEDULED sessions)
                if (taskContext.sessionType == SessionType.SCHEDULED_PLAY && !(scheduledSongMode && "play_from_search".equals(action))) {
                    boolean nowPlayingOpened = actions.openNowPlayingWithFallback();
                    if (nowPlayingOpened) {
                        long trackDurationMs = actions.getTrackDurationMs();
                        long sessionDuration = sessionEndTime - sessionStartTime;
                        int requiredLoops = (int) Math.ceil((double) sessionDuration / trackDurationMs);
                        
                        report(commandId, "SESSION", "SETUP", "Track Duration: " + (trackDurationMs/1000) + "s. Estimated loops: " + requiredLoops);
                        
                        actions.enableRepeat();
                        
                        // 3. Monitor Loop
                        int loopCount = 0;
                        int likedCount = 0;
                        String currentTrack = "";
                        java.util.Set<String> likedSongs = new java.util.HashSet<>();
                        
                        long lastCheck = System.currentTimeMillis();
                        report(commandId, "SESSION", "ACTIVE", "Monitoring playback session...");
                        
                        while (System.currentTimeMillis() < sessionEndTime && !forceStopSession) {
                            try { Thread.sleep(10000); } catch(Exception ignored){}
                            if (forceStopSession) break;
                        
                        // --- AUTO LIKE LOGIC ---
                        // Only run monitor-based liking for non-playlist actions
                        // (Playlists are handled by batchLikePlaylistTracks)
                        if (autoLikeCount > 0 && likedCount < autoLikeCount && !action.equals("play_playlist")) {
                            String nowPlaying = actions.getCurrentTrackTitle();
                            if (nowPlaying != null && !nowPlaying.equals(currentTrack)) {
                                currentTrack = nowPlaying;
                                android.util.Log.i("SpotifyBot", "[LIKE][TRACK_DETECTED] " + nowPlaying);
                                
                                if (!likedSongs.contains(nowPlaying)) {
                                if (actions.likeCurrentTrack()) {
                                    likedCount++;
                                    likedSongs.add(nowPlaying);
                                    android.util.Log.i("SpotifyBot", "[LIKE][SONG_LIKED] track=\"" + nowPlaying + "\" count=" + likedCount + "/" + autoLikeCount);
                                    report(commandId, "LIKE", "SONG_LIKED", "Liked: " + nowPlaying + " (" + likedCount + "/" + autoLikeCount + ")");
                                }
                                } else {
                                    android.util.Log.i("SpotifyBot", "[LIKE][ALREADY_LIKED] " + nowPlaying);
                                }
                            }
                            
                            if (likedCount >= autoLikeCount && autoLikeCount > 0) {
                                android.util.Log.i("SpotifyBot", "[LIKE][LIKE_LIMIT_REACHED]");
                                // Don't report repeatedly
                            }
                        }

                        // --- PLAYLIST ADDITION LOGIC ---
                        if (!playlistName.isEmpty() && addedToPlaylistCount < songsToAdd) {
                            String nowPlaying = actions.getCurrentTrackTitle();
                            if (nowPlaying != null && !nowPlaying.equals(currentTrack)) {
                                // currentTrack is updated in Auto Like block or here
                                if (!addedTracks.contains(nowPlaying)) {
                                    android.util.Log.i("SpotifyBot", "[PLAYLIST_AUTO][ATTEMPT] Adding " + nowPlaying + " to " + playlistName);
                                    if (actions.addCurrentTrackToPlaylist(playlistName)) {
                                        addedToPlaylistCount++;
                                        addedTracks.add(nowPlaying);
                                        report(commandId, "PLAYLIST_AUTO", "TRACK_ADDED", "Added: " + nowPlaying + " (" + addedToPlaylistCount + "/" + songsToAdd + ")");
                                    }
                                }
                            }
                        }
                        // -------------------------------
                        // -----------------------
                        
                        long now = System.currentTimeMillis();
                        long elapsed = now - sessionStartTime;
                        long remaining = sessionEndTime - now;
                        
                        if (trackDurationMs > 0) {
                            loopCount = (int) (elapsed / trackDurationMs);
                        }
                        
                        // Playback Recovery & Verification
                        String state = "PLAYING";
                        if (now - lastCheck > 30000) {
                            lastCheck = now;
                            android.view.accessibility.AccessibilityNodeInfo root = service.getRootInActiveWindow();
                            if (root != null) {
                                android.view.accessibility.AccessibilityNodeInfo pauseBtn = navigator.findNodeByDescription(root, "pause");
                                if (pauseBtn == null) {
                                    state = "RECOVERING";
                                    android.util.Log.w("SpotifyBot", "[SESSION][RECOVERY_PLAY] Playback paused unexpectedly. Attempting recovery...");
                                    report(commandId, "SESSION", "RECOVERY_PLAY", "Attempting to resume playback...");
                                    
                                    android.view.accessibility.AccessibilityNodeInfo playBtn = navigator.findNodeByDescription(root, "play");
                                    if (playBtn != null) {
                                        playBtn.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
                                        playBtn.recycle();
                                    } else {
                                        // Fallback: Re-open Now Playing
                                        actions.openNowPlaying();
                                    }
                                } else {
                                    pauseBtn.recycle();
                                    android.util.Log.i("SpotifyBot", "[SESSION][PLAYBACK_CONTINUES]");
                                    report(commandId, "SESSION", "PLAYBACK_CONTINUES", "Confirmed playback is active.");
                                }
                                root.recycle();
                            }
                        } else {
                            android.util.Log.i("SpotifyBot", "[SESSION][MONITORING]");
                        }
                        
                        // Telemetry Emission
                        emitSessionStats(sessionId, trackDurationMs, elapsed, remaining, loopCount, state, likedCount);
                        lastCheck = now;
                        }  // End of while loop
                    } else {
                        // Now Playing opening failed, but don't fail the whole command
                        // Graceful degradation: continue with normal playback
                        Log.i("SpotifyBot", "[SESSION] Continuing without full session engine (Now Playing unavailable)");
                        report(commandId, "SESSION", "DEGRADED", "Continuing without session controls");
                    }
                }
            }

            if (taskContext.isCompleted) {
                // already handled by an inner terminal branch
                return;
            }

            completeTask(cmd, commandId, action, success, isSession ? "Session time expired or stopped." : action);

        } else {
            boolean success = runActionLoop(commandId, action, payload, autoLikeCount);

            completeTask(cmd, commandId, action, success, action);
        }
    }

    private boolean runActionLoop(String commandId, String action, JSONObject payload, int autoLikeCount) {
        boolean success = false;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            report(commandId, "EXECUTE", "STARTED", "Attempt " + (attempt + 1));
            
            try {
                success = runAction(commandId, action, payload, autoLikeCount);
            } catch (AutomationAbortException e) {
                android.util.Log.e("SpotifyBot", "Terminal State Reached: " + e.getMessage());
                report(commandId, "EXECUTE", "FAILED", e.getMessage());
                break; // Break retry loop immediately on terminal states
            } catch (Exception e) {
                android.util.Log.e("SpotifyBot", "Action threw exception: " + e.getMessage(), e);
                success = false;
            }
            
            // 3. VERIFY (Internal to runAction)
            if (success) {
                report(commandId, "VERIFY", "OK", "Success");
                break;
            } else if (attempt < MAX_RETRIES) {
                // Exponential Backoff: 500ms, 1s, 2s...
                long backoffMs = (long) (500 * Math.pow(2, attempt));
                report(commandId, "EXECUTE", "RETRYING", "Cooldown " + backoffMs + "ms...");
                try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) {}
            }
        }
        return success;
    }

    private void emitServiceLog(JSONObject cmd, boolean success) {
        try {
            String action = cmd.optString("action");
            String taskId = cmd.optString("task_id", "---");
            String runId = cmd.optString("run_id", "---");
            String deviceId = service.getBotDeviceId();
            String devName = service.getDeviceName();
            
            String last4 = deviceId.length() >= 4 ? deviceId.substring(deviceId.length() - 4) : deviceId;
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
            String timestamp = sdf.format(new Date());
            String tz = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT);
            
            String status = success ? "SUCCESS" : "FAILED";
            String logLine = String.format("[%s %s] [SERVICE] [%s] [%s|%s] %s task=%s run=%s",
                    timestamp, tz, status, last4, devName, action.toUpperCase(), taskId, runId);
            
            android.util.Log.i("spotifybot", logLine);
        } catch (Exception e) {
            android.util.Log.e("spotifybot", "Failed to emit service log", e);
        }
    }

    private boolean runAction(String cmdId, String action, JSONObject payload, int autoLikeCount) {
        String query = (payload != null) ? payload.optString("query") : "";
        boolean playlistAutomationEnabled = payload != null && payload.optBoolean("playlist_automation_enabled", false);
        
        switch (action) {
            case "play_from_search":
                String filter = payload != null ? payload.optString("filter", "Songs") : "Songs";
                return actions.executeSearch(query, filter) && actions.playFirstResult(query, autoLikeCount);
            
            case "play_playlist":
                if (playlistAutomationEnabled && !taskContext.sessionEnabled) {
                    String playlistName = payload != null ? payload.optString("playlist_name", "") : "";
                    int songsToAdd = payload != null ? payload.optInt("songs_to_add", 0) : 0;
                    if (!playlistName.isEmpty() && !actions.executeCreateOrAppendPlaylist(playlistName, Math.max(1, songsToAdd))) {
                        return false;
                    }
                }
                return actions.executeSearch(query, "Playlists") && actions.executePlaylistFlow(query, autoLikeCount);

            case "play_album":
                return actions.executeSearch(query, "Albums") && actions.executeAlbumFlow(query, autoLikeCount);
                
            case "mode_change":
                // Web UI sends mode_change when toggling between SONG and PLAYLIST mode
                // We acknowledge it as success to keep UI in sync
                return true;

            case "like_current":
            case "like_track":
                return actions.likeCurrentTrack();
                
            case "skip_track":
                return actions.skipTrack();

            case "instant_like":
                // Semantic-Like mode: resolve row -> click Add button
                return actions.executeLikeFlow(query);
                
            case "follow_artist":
                return actions.executeFollowArtist(query);
                
            case "play_artist_catalog":
                return actions.executePlayArtistCatalog(query);
                
            case "play_this_is_artist":
                return actions.executePlayThisIsArtist(query);
                
            case "artist":
                String subAction = payload != null ? payload.optString("artist_action") : "";
                switch(subAction) {
                    case "FOLLOW_ARTIST": return actions.executeFollowArtist(query);
                    case "PLAY_ARTIST_CATALOG": return actions.executePlayArtistCatalog(query);
                    case "PLAY_THIS_IS_ARTIST": return actions.executePlayThisIsArtist(query);
                    case "PLAY_ARTIST_RADIO": return actions.executePlayArtistRadio(query);
                    default: 
                        android.util.Log.e("SpotifyBot", "Unknown artist sub-action: " + subAction);
                        return false;
                }
                
            default:
                android.util.Log.e("SpotifyBot", "Unknown action: " + action);
                return false;
        }
    }

    private boolean isScheduledSongMode(JSONObject cmd, JSONObject payload, String action) {
        if (!"play_from_search".equals(action))
            return false;
        if (cmd != null) {
            JSONObject task = cmd.optJSONObject("task");
            if ("scheduled_session".equalsIgnoreCase(cmd.optString("source")))
                return true;
            if (task != null && "scheduled_session".equalsIgnoreCase(task.optString("source")))
                return true;
        }
        return payload != null && payload.optBoolean("session_mode", false);
    }

    private boolean runScheduledSongSession(String commandId, JSONObject payload, int autoLikeCount, String playlistName,
            int songsToAdd, long sessionStartTime, long sessionEndTime) {
        runtimeState = new TaskRuntimeState();
        runtimeState.playlistLikesRemaining = Math.max(0, autoLikeCount);
        runtimeState.playlistLikesDone = 0;

        String query = payload != null ? payload.optString("query", "") : "";
        boolean playlistAutomationEnabled = false;
        boolean playlistResolved = false;
        boolean playlistPipelineSkipped = true;
        report(commandId, "SESSION", "START", "[SESSION][START]");
        if (!waitForScheduledUiBarrier()) {
            report(commandId, "EXECUTE", "FAILED", "UI_READY_BARRIER_FAILED");
            return false;
        }
        runtimeState.currentScreen = detectScreenState();
        if (!ensureScheduledScreenReady())
            return false;

        // Scheduled-only strict order: library check/create happens before track search.
        boolean playlistRequested = false;
        boolean playlistDone = true;
        if (!canNavigate())
            return false;
        if (!waitForScheduledUiBarrier()) {
            report(commandId, "EXECUTE", "FAILED", "UI_READY_BARRIER_FAILED_BEFORE_SEARCH");
            return false;
        }
        runtimeState.currentScreen = detectScreenState();
        if (!ensureScheduledScreenReady())
            return false;
        if (!canNavigate())
            return false;
        if (!actions.executeSearch(query, "Songs")) {
            report(commandId, "EXECUTE", "FAILED", "Scheduled song search failed");
            return false;
        }
        runtimeState.currentScreen = detectScreenState();

        boolean likeRequested = autoLikeCount > 0;
        boolean likeDone = !likeRequested;
        if (likeRequested) {
            runtimeState.currentNodeContext = "LIKE_LOOP";
            while (runtimeState.playlistLikesDone < runtimeState.playlistLikesRemaining) {
                if (!actions.executeLikeFlow(query)) {
                    likeDone = false;
                    break;
                }
                runtimeState.playlistLikesDone++;
                likeDone = true;
                if (runtimeState.playlistLikesDone < runtimeState.playlistLikesRemaining) {
                    actions.skipTrack();
                    try {
                        Thread.sleep(1200);
                    } catch (Exception ignored) {
                    }
                }
            }
            report(commandId, "LIKE", likeDone ? "SONG_LIKED" : "FAILED",
                    likeDone ? "Pre-play like complete" : "Pre-play like failed");
            if (!likeDone)
                return false;
        }

        boolean playbackDone = actions.playFirstResult(query, 0);
        if (!playbackDone || !actions.isPlaybackActive()) {
            report(commandId, "EXECUTE", "FAILED", "Playback verification failed");
            return false;
        }

        if (!playlistPipelineSkipped && !(playlistDone && likeDone && playbackDone)) {
            report(commandId, "FINALIZE", "WAITING_PENDING_ACTIONS", "[FINALIZE][WAITING_PENDING_ACTIONS]");
            return false;
        }

        if (!actions.openNowPlayingWithFallback()) {
            report(commandId, "SESSION", "FAILED", "Now Playing unavailable for scheduled monitor");
            return false;
        }
        runtimeState.currentScreen = ScreenState.NOW_PLAYING_SCREEN;

        report(commandId, "SESSION", "SEEKBAR_DETECTED", "[SESSION][SEEKBAR_DETECTED]");
        long songDurationMs = actions.getTrackDurationMs();
        report(commandId, "SESSION", "SONG_DURATION", "[SESSION][SONG_DURATION] " + (songDurationMs / 1000) + "s");

        long sessionDurationMs = Math.max(0, sessionEndTime - sessionStartTime);
        int repeatCountNeeded = (songDurationMs > 0) ? (int) Math.ceil((double) sessionDurationMs / songDurationMs) : 0;
        int repeatCountCompleted = 0;
        double lastSeek = -1;

        if (!actions.enableRepeatOneForSession()) {
            report(commandId, "SESSION", "FAILED", "Could not enable Repeat One");
            return false;
        }
        report(commandId, "SESSION", "REPEAT_ENABLED", "[SESSION][REPEAT_ENABLED]");

        while (System.currentTimeMillis() < sessionEndTime && !forceStopSession) {
            try {
                Thread.sleep(3000);
            } catch (Exception ignored) {
            }
            runtimeState.currentScreen = detectScreenState();
            if (runtimeState.currentScreen != ScreenState.NOW_PLAYING_SCREEN) {
                if (!actions.openNowPlayingWithFallback()) {
                    report(commandId, "SESSION", "FAILED", "Now Playing lost and could not recover");
                    return false;
                }
                runtimeState.currentScreen = ScreenState.NOW_PLAYING_SCREEN;
            }
            if (!actions.isPlaybackActive()) {
                report(commandId, "SESSION", "PLAYBACK_INACTIVE", "Playback inactive during scheduled loop");
                break;
            }
            if (!actions.isRepeatOneActive()) {
                actions.enableRepeatOneForSession();
            }
            double currentSeek = actions.readSeekbarPosition();
            if (lastSeek >= 0 && currentSeek >= 0 && currentSeek < (lastSeek * 0.2)) {
                repeatCountCompleted++;
                report(commandId, "SESSION", "REPEAT_COUNT",
                        "[SESSION][REPEAT_COUNT] " + repeatCountCompleted + "/" + repeatCountNeeded);
            }
            lastSeek = currentSeek;
            long remainingSec = Math.max(0, (sessionEndTime - System.currentTimeMillis()) / 1000);
            report(commandId, "SESSION", "TIME_REMAINING", "[SESSION][TIME_REMAINING] " + remainingSec + "s");
        }

        actions.openNowPlayingWithFallback();
        boolean paused = actions.pausePlaybackFromNowPlaying();
        report(commandId, "SESSION", "ENDED", "[SESSION][ENDED]");
        report(commandId, "SESSION", paused ? "PLAYBACK_STOPPED" : "STOP_REQUESTED",
                paused ? "[SESSION][PLAYBACK_STOPPED]" : "Pause button not found");
        runtimeState.navigationLocked = true;
        runtimeState.taskCompleted = true;
        return true;
    }

    private boolean waitForScheduledUiBarrier() {
        long[] waits = { 700, 1100, 1600 };
        for (long wait : waits) {
            service.waitForIdle();
            try {
                Thread.sleep(wait);
            } catch (Exception ignored) {
            }
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                boolean ready = root.getChildCount() > 0;
                root.recycle();
                if (ready)
                    return true;
            }
        }
        return false;
    }

    private boolean canNavigate() {
        return runtimeState == null || !runtimeState.navigationLocked;
    }

    private ScreenState detectScreenState() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null)
            return ScreenState.UNKNOWN;
        try {
            if (navigator.findNodeByResourceId(root, "com.spotify.music:id/now_playing_bar_layout") != null
                    || navigator.findNodeByResourceId(root, "com.spotify.music:id/seekbar") != null) {
                return ScreenState.NOW_PLAYING_SCREEN;
            }
            if (navigator.findNodeByDescription(root, "Your Library") != null) {
                return ScreenState.LIBRARY_SCREEN;
            }
            if (navigator.findNodeByDescription(root, "Open context menu for") != null) {
                return ScreenState.PLAYLIST_SCREEN;
            }
            if (navigator.findNodeByText(root, "Add to playlist") != null
                    || navigator.findNodeByText(root, "Add to a playlist") != null) {
                return ScreenState.CONTEXT_MENU_OPEN;
            }
            if (navigator.findNodeByDescription(root, "Search") != null || navigator.findNodeByText(root, "Search") != null) {
                return ScreenState.SEARCH_SCREEN;
            }
            return ScreenState.UNKNOWN;
        } finally {
            root.recycle();
        }
    }

    private boolean ensureScheduledScreenReady() {
        if (runtimeState == null)
            return true;
        if (runtimeState.currentScreen != ScreenState.UNKNOWN)
            return true;
        if (!waitForScheduledUiBarrier()) {
            return false;
        }
        runtimeState.currentScreen = detectScreenState();
        return runtimeState.currentScreen != ScreenState.UNKNOWN;
    }

    private void runPostPlayActions(String commandId, int autoLikeCount, String playlistName, int songsToAdd) {
        boolean needsLike = autoLikeCount > 0;
        boolean needsPlaylistAdd = playlistName != null && !playlistName.isEmpty();
        if (!needsLike && !needsPlaylistAdd) {
            return;
        }

        report(commandId, "POST_PLAY", "START", "Running optional post-play actions");
        report(commandId, "SESSION", "READY", "Opening Now Playing for post-play actions");
        boolean opened = actions.openNowPlayingWithFallback();
        if (!opened) {
            report(commandId, "SESSION", "UNAVAILABLE", "Now Playing unavailable; skipping optional post actions");
            if (needsLike) {
                report(commandId, "LIKE", "SKIPPED", "Could not open Now Playing for optional like");
            }
            if (needsPlaylistAdd) {
                report(commandId, "PLAYLIST_AUTO", "SKIPPED", "Could not open Now Playing for optional playlist add");
            }
            return;
        }

        if (needsLike) {
            boolean liked = actions.likeCurrentTrack();
            report(commandId, "LIKE", liked ? "SONG_LIKED" : "OPTIONAL_FAILED",
                    liked ? "Liked current track" : "Could not like current track; playback remains successful");
        }

        if (needsPlaylistAdd) {
            int attempts = Math.max(1, songsToAdd);
            boolean added = false;
            for (int i = 0; i < attempts; i++) {
                if (actions.addCurrentTrackToPlaylist(playlistName)) {
                    added = true;
                    break;
                }
            }
            report(commandId, "PLAYLIST_AUTO", added ? "TRACK_ADDED" : "OPTIONAL_FAILED",
                    added ? "Added current track to " + playlistName
                            : "Could not add current track to " + playlistName + "; playback remains successful");
        }
    }

    private boolean enforceStrictRouting(String action, TaskContext context, JSONObject payload) {
        // === STRICT ROUTING ENFORCEMENT ===
        // Each action type MUST stick to its declared flow. No cross-execution.
        
        switch (context.taskType) {
            case SONG_PLAY:
                // Song play MUST NOT trigger playlist or session logic
                if ("play_from_search".equals(action) || "instant_like".equals(action))
                    return true;
                Log.e("SpotifyBot", "[ROUTING_GUARD] SONG_PLAY action mismatch: " + action);
                return false;

            case PLAYLIST:
                // Playlist flow MUST NOT be mixed with song play
                if ("play_playlist".equals(action) || "play_album".equals(action))
                    return true;
                Log.e("SpotifyBot", "[ROUTING_GUARD] PLAYLIST action mismatch: " + action);
                return false;

            case SESSION:
                // Session is only valid with explicit session flag
                if (context.sessionEnabled)
                    return true;
                Log.e("SpotifyBot", "[ROUTING_GUARD] SESSION without explicit session flag");
                return false;

            case SEARCH_ONLY:
                // Search-only actions (artist, follow)
                if ("artist".equals(action) || (action != null && action.startsWith("follow_")))
                    return true;
                // Allow some flexibility for search-based actions
                return action != null && !action.contains("session");

            default:
                return true;
        }
    }

    private SessionType determineSessionType(boolean isSession, JSONObject payload) {
        if (!isSession)
            return SessionType.NORMAL_PLAY;

        // If session is explicitly requested with duration/loop params, it's SCHEDULED
        if (payload != null && (payload.optLong("duration_ms", 0) > 0 || payload.optInt("loop_count", 0) > 0)) {
            return SessionType.SCHEDULED_PLAY;
        }

        return SessionType.NORMAL_PLAY;
    }

    private boolean ensureUIStability(String commandId) {
        // === UI STABILITY CHECK ===
        // Wait for UI to be stable before executing any action.
        // Prevents crashes and race conditions from stale node references.
        
        service.waitForIdle();
        
        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                // Check if basic structure exists
                if (root.getChildCount() > 0) {
                    Log.i("SpotifyBot", "[UI_STABILITY] OK after attempt " + (attempt + 1));
                    root.recycle();
                    return true;
                }
                root.recycle();
            }
            
            try {
                Thread.sleep(300);
            } catch (Exception ignored) {
            }
        }
        
        Log.e("SpotifyBot", "[UI_STABILITY] FAILED: Cannot get valid root node");
        report(commandId, "UI_CHECK", "FAILED", "Root node not stable");
        return false;
    }

    private boolean isExplicitSessionRequest(JSONObject cmd, JSONObject payload) {
        if (cmd == null)
            return false;

        if (!cmd.optBoolean("is_session", false))
            return false;

        if (cmd.optLong("session_end_time", 0) > 0 || cmd.optLong("session_start_time", 0) > 0)
            return true;

        if (payload == null)
            return false;

        return payload.optLong("duration_ms", 0) > 0 || payload.optInt("loop_count", 0) > 0
                || payload.optLong("session_end_time", 0) > 0;
    }

    private TaskType resolveTaskType(String action, JSONObject cmd, JSONObject payload) {
        if (isExplicitSessionRequest(cmd, payload))
            return TaskType.SESSION;

        if ("play_playlist".equals(action) || "play_album".equals(action))
            return TaskType.PLAYLIST;

        if ("play_from_search".equals(action) || "instant_like".equals(action))
            return TaskType.SONG_PLAY;

        if ("artist".equals(action) || action != null && action.startsWith("follow_"))
            return TaskType.SEARCH_ONLY;

        return TaskType.SEARCH_ONLY;
    }

    private long resolveSessionEndTime(JSONObject cmd, JSONObject payload, long sessionStartTime) {
        long explicitEnd = cmd.optLong("session_end_time", 0);
        if (explicitEnd > 0)
            return explicitEnd;

        if (payload != null) {
            long payloadEnd = payload.optLong("session_end_time", 0);
            if (payloadEnd > 0)
                return payloadEnd;

            long durationMs = payload.optLong("duration_ms", 0);
            if (durationMs > 0)
                return sessionStartTime + durationMs;

            int loopCount = payload.optInt("loop_count", 0);
            if (loopCount > 0)
                return sessionStartTime + (loopCount * 180000L);
        }

        return System.currentTimeMillis() + 3600000L;
    }

    /**
     * === 2-PHASE COMPLETION ===
     * Phase 1: Execution (already done)
     * Phase 2: Finalization (UI verification + explicit result)
     * 
     * CRITICAL: Do NOT mark task failed without verification.
     * Always run finalizeTask() to confirm success via UI state.
     */
    private void completeTask(JSONObject cmd, String commandId, String action, boolean success, String completionMessage) {
        Log.i(TAG, "[COMPLETE_TASK][START] action=" + action + " executionSuccess=" + success);
        
        // === PHASE 2: FINALIZATION WITH UI VERIFICATION ===
        TaskResult result = new TaskResult(commandId, action);
        
        // Perform final validation (this is the CRITICAL FIX)
        result = resultValidator.finalizeTask(commandId, action, result, success);
        
        // Report using explicit result status (NOT heuristic)
        boolean finalSuccess = result.isSuccess();
        Log.i(TAG, "[COMPLETE_TASK][RESULT] action=" + action + " finalStatus=" + result.status + 
              " reason=" + result.reason + " signals=" + result.verificationSignals.toString());
        
        report(commandId, "FINAL", finalSuccess ? "OK" : "FAILED", result.reason);
        emitServiceLog(cmd, finalSuccess);
        closeSpotifyAfterTask();
        
        taskContext.isCompleted = true;
        TaskManager.completeTask(taskContext.currentTaskId);
        if (runtimeState != null) {
            runtimeState.taskCompleted = true;
            runtimeState.navigationLocked = true;
        }
        
        if (taskContext.resetOnCompletion) {
            resetTaskState();
        }
    }

    private void closeSpotifyAfterTask() {
        Log.i(TAG, "[CLEANUP][SPOTIFY_CLOSE][START]");

        String failureReason = null;
        try {
            Process process = Runtime.getRuntime().exec(new String[] {"am", "force-stop", "com.spotify.music"});
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                Log.i(TAG, "[CLEANUP][SPOTIFY_CLOSE][SUCCESS]");
                return;
            }
            failureReason = "force_stop_exit_code_" + exitCode;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failureReason = "force_stop_interrupted";
        } catch (Exception e) {
            failureReason = "force_stop_exception_" + e.getClass().getSimpleName();
        }

        boolean homeFallback = false;
        try {
            homeFallback = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
        } catch (Exception e) {
            if (failureReason == null || failureReason.isEmpty()) {
                failureReason = "home_fallback_exception_" + e.getClass().getSimpleName();
            } else {
                failureReason += "|home_fallback_exception_" + e.getClass().getSimpleName();
            }
        }

        if (homeFallback) {
            Log.i(TAG, "[CLEANUP][SPOTIFY_CLOSE][SUCCESS]");
            return;
        }

        if (failureReason == null || failureReason.isEmpty()) {
            failureReason = "force_stop_failed_and_home_fallback_failed";
        } else {
            failureReason += "|home_fallback_failed";
        }
        Log.w(TAG, "[CLEANUP][SPOTIFY_CLOSE][FAILED] reason=" + failureReason);
    }

    private void resetTaskState() {
        forceStopSession = false;
        activeSessionId = null;
        taskContext.reset();
        actions.resetAutomationState();
        runtimeState = null;
    }


    private void report(String cmdId, String step, String status, String message) {
        service.logEvent(cmdId, step, status, message);
    }

    private void emitSessionStats(String sessionId, long totalDuration, long elapsed, long remaining, int loops, String state, int likedCount) {
        try {
            org.json.JSONObject msg = new org.json.JSONObject();
            msg.put("type", "SESSION_STATS");
            msg.put("session_id", sessionId);
            msg.put("total_duration", totalDuration);
            msg.put("elapsed_time", elapsed);
            msg.put("remaining_time", remaining);
            msg.put("loop_count", loops);
            msg.put("playback_state", state);
            msg.put("liked_count", likedCount);
            SpotifyWebSocketClient ws = service.getWsClient();
            if (ws != null && ws.isOpen()) ws.send(msg.toString());
        } catch (Exception ignored) {}
    }
}
