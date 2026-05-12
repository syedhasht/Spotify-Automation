# 🎧 Spotify Automation Hub (v1.6.0-PROD)

A professional-grade, tri-tier agentic automation system designed for large-scale Spotify account management. The system leverages Android's Accessibility Layer to perform high-fidelity, resolution-independent UI interactions through a real-time WebSocket orchestration pipeline.

---

## 1. PROJECT OVERVIEW

### **System Objective**
The Spotify Automation Hub provides a unified interface for controlling a fleet of Android devices running the official Spotify app. It bypasses traditional API limitations by acting as a "virtual user," executing human-like gestures and navigation flows.

### **Architecture Philosophy**
*   **Decoupled Orchestration**: The backend (FastAPI) acts as a stateless router, while state is maintained in a persistent SQLite database.
*   **Real-time Feedback**: Every UI interaction on the Android device is streamed back to the React dashboard via WebSockets with <100ms latency.
*   **Resolution Independence**: The automation engine does not use absolute coordinates. It traverses the Android `AccessibilityNodeInfo` tree to find elements dynamically based on semantics (ID, text, description).
*   **Linear Execution**: Batch operations (Album/Playlist) follow a strict row-based traversal to prevent UI state drift and redundant scrolling.

---

## 2. FULL SYSTEM ARCHITECTURE

### **The Tri-Tier Model**
1.  **Dashboard (Frontend)**: A React-based glassmorphic UI that allows operatives to create tasks and monitor device health in real-time.
2.  **Orchestrator (Backend)**: A FastAPI server managing user authentication, task persistence, and WebSocket routing between the dashboard and devices.
3.  **Agent (Android APK)**: A native AccessibilityService that implements a recursive UI-tree matching engine and execution router.

### **Lifecycle & Logic**
*   **Command Routing**: Commands flow from Frontend → REST API → WebSocket Dispatch → Android Agent.
*   **Heartbeat Lifecycle**: Devices emit a heartbeat every 15s (Battery, Network, Status). If no heartbeat is received for 120s, the "Sentinel" background task marks the device as offline.
*   **Auth Lifecycle**: Uses JWT with JTI (JSON Token Identifier) linkage. Every JWT is tied to a database `user_session`, allowing for immediate global revocation.

---

## 3. DIRECTORY STRUCTURE

### **`/server` (Backend)**
*   `main.py`: The central orchestrator. Handles FastAPI routes, WebSocket handlers, and the background "Sentinel" cleanup task.
*   `logger.py`: Implements a structured logging system with specialized prefixes (WS, DEV, DB, AUTH).
*   `automation.db`: SQLite database storing the full state of the system.
*   `automation.logs`: Real-time log file containing detailed execution traces.

### **`/frontend` (Dashboard)**
*   `AuthContext.jsx`: Manages JWT storage, session restoration, and auto-logout on token expiry.
*   `api.js`: Axios instance with request/reponse interceptors for transparent JWT injection.
*   `Dashboard.jsx`: The primary control plane. Implements the "Radar" device monitoring and the hierarchical command builder.

### **`/app` (Android Agent)**
*   `MyAccessibilityService.java`: The entry point. Connects to the WebSocket and listens for `AccessibilityEvent` updates.
*   `CommandRunner.java`: The execution engine. Manages a `BlockingQueue` and implements the task execution lifecycle (VALIDATE → EXECUTE → VERIFY).
*   `SpotifyActions.java`: The automation library. Contains all high-level Spotify navigation flows (Search, Like, Radio, Artist Catalog).
*   `SpotifyNavigator.java`: Low-level navigation utilities for tab switching and handling UI interruptions.

---

## 4. HARDENED AUTOMATION FEATURES (NEW)

### **💿 Album & Playlist Traversal**
*   **Row-Scoped Menu Access**: Instead of searching globally, the bot identifies track rows within the `RecyclerView` and performs scoped searches for the "3-dots" menu (`entity_action_short_row_end_action`).
*   **Linear Batching**: Processes songs sequentially. It only scrolls when the current visible set is exhausted, preventing infinite scroll loops.
*   **Verification Latency**: Implements polling-based verification after menu clicks to ensure the UI has settled before attempting next-step interactions.

### **🎵 Song Mode - Direct Action**
*   **Strict Like Flow**: Song mode now bypasses menus entirely for "Like" actions. It targets the direct `+` (`add_button`) within the search result row, significantly increasing speed and reliability.
*   **Fail-Fast Detection**: If the direct button is not found, the bot logs a diagnostic and proceeds rather than entering complex fallback loops that could break the UI.

### **📋 Playlist Normalization**
*   **Case-Insensitive Matching**: Implemented `normalizePlaylistName` utility to handle "Intoxicated" vs "intoxicated" vs " INTOXICATED " logic.
*   **Space Collapsing**: Automatically trims and collapses multiple spaces to match Spotify's internal UI naming conventions.

---

## 5. DATABASE DOCUMENTATION

| Table | Purpose | Key Columns |
| :--- | :--- | :--- |
| `users` | Operative credentials | `id`, `username`, `password` (Plain), `is_active` |
| `user_sessions` | JWT Lifecycle tracking | `session_id`, `jwt_jti`, `expires_at`, `revoked_at` |
| `devices` | Remote agent registry | `device_id`, `status`, `last_seen`, `battery`, `network_type` |
| `tasks` | Intent storage | `id`, `task_name`, `action_type`, `search_query` |
| `runs` | Execution instances | `id`, `task_id`, `device_id`, `status` (running/success/failed) |

---

## 6. STABILITY & SECURITY FIXES

*   **Android 10+ Thread Safety**: Clipboard operations (`setPrimaryClip`) are now safely marshaled to the UI thread via `Handler` + `CountDownLatch` to prevent silent crashes on modern Android versions.
*   **Infinite Loop Protection**: All recursive parent-climbing searches now have a strict depth cap (15 levels) to prevent freezes on corrupted UI trees.
*   **Memory Management**: Implemented exhaustive `AccessibilityNodeInfo` recycling in batch loops, fixing a major memory leak during long-running album scans.

---

## 7. RUNNING THE SYSTEM

### **1. Backend (FastAPI)**
```bash
cd server
pip install -r requirements.txt
python main.py
```

### **2. Frontend (React)**
```bash
cd frontend
npm install
npm run dev
```

### **3. Android Agent**
1. Install `app-debug.apk` on the target device.
2. Enable "Spotify Automation Service" in Accessibility Settings.
3. Ensure the app shows "CONNECTED" status.

---
*© 2026 Spotify Automation Hub - Enterprise Grade Control.*
