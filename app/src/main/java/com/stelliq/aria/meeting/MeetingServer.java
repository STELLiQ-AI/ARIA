/**
 * MeetingServer.java
 *
 * TCP server running on the meeting owner's device.
 * Accepts connections from participant devices and receives
 * speaker-attributed transcript segments.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-08
 */
package com.stelliq.aria.meeting;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeetingServer {

    private static final String TAG = "ARIA_MEETING";

    /**
     * Callback for server events.
     */
    public interface ServerCallback {
        void onParticipantJoined(@NonNull String speakerName);
        void onParticipantLeft(@NonNull String speakerName);
        void onSegmentReceived(@NonNull String speakerName, @NonNull String text,
                               int segmentIndex, long timestamp);
        void onError(@NonNull String error);
    }

    private ServerSocket mServerSocket;
    private final List<ClientConnection> mConnections = new CopyOnWriteArrayList<>();
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    @Nullable
    private ServerCallback mCallback;
    private volatile boolean mRunning = false;
    private int mPort = 0;

    // WHY: Owner's session start time, sent to participants on JOIN for clock alignment.
    // Participants compute offset = their_clock - t0 to align segment timestamps.
    private long mT0EpochMs = 0;

    public void setCallback(@Nullable ServerCallback callback) {
        mCallback = callback;
    }

    /**
     * Sets the owner's session start time for T0 clock sync.
     * Must be called before start() so joining participants receive it.
     *
     * @param t0EpochMs Owner's System.currentTimeMillis() at session creation
     */
    public void setT0EpochMs(long t0EpochMs) {
        mT0EpochMs = t0EpochMs;
    }

    /**
     * Starts the server on an available port.
     *
     * @return The port number, or -1 on failure
     */
    public int start() {
        try {
            mServerSocket = new ServerSocket(0); // System-assigned port
            mPort = mServerSocket.getLocalPort();
            mRunning = true;

            Log.i(TAG, "[MeetingServer] Started on port " + mPort);

            // Accept connections in background
            mExecutor.execute(this::acceptLoop);

            return mPort;
        } catch (IOException e) {
            Log.e(TAG, "[MeetingServer] Failed to start: " + e.getMessage());
            if (mCallback != null) mCallback.onError("Failed to start meeting server");
            return -1;
        }
    }

    /**
     * Stops the server and disconnects all clients.
     */
    public void stop() {
        mRunning = false;

        // Send END to all connected clients
        broadcast(MeetingProtocol.createEndMessage());

        // Close all connections
        for (ClientConnection conn : mConnections) {
            conn.close();
        }
        mConnections.clear();

        // Close server socket
        try {
            if (mServerSocket != null && !mServerSocket.isClosed()) {
                mServerSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "[MeetingServer] Error closing server: " + e.getMessage());
        }

        // WHY: Shutdown thread pool to prevent threads outliving the server
        mExecutor.shutdownNow();

        Log.i(TAG, "[MeetingServer] Stopped");
    }

    /**
     * Broadcasts a message to all connected clients.
     */
    public void broadcast(@NonNull String message) {
        for (ClientConnection conn : mConnections) {
            conn.send(message);
        }
    }

    /**
     * Returns the server port.
     */
    public int getPort() {
        return mPort;
    }

    /**
     * Returns count of connected participants.
     */
    public int getParticipantCount() {
        return mConnections.size();
    }

    /**
     * Returns list of connected speaker names.
     */
    @NonNull
    public List<String> getParticipantNames() {
        List<String> names = new java.util.ArrayList<>();
        for (ClientConnection conn : mConnections) {
            if (conn.speakerName != null) {
                names.add(conn.speakerName);
            }
        }
        return names;
    }

    public boolean isRunning() {
        return mRunning;
    }

    private void acceptLoop() {
        while (mRunning) {
            try {
                Socket clientSocket = mServerSocket.accept();
                Log.i(TAG, "[MeetingServer] New connection from " + clientSocket.getInetAddress());

                ClientConnection conn = new ClientConnection(clientSocket);
                mConnections.add(conn);
                mExecutor.execute(conn::readLoop);

            } catch (IOException e) {
                if (mRunning) {
                    Log.e(TAG, "[MeetingServer] Accept error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Represents a connected participant.
     */
    private class ClientConnection {
        final Socket socket;
        @Nullable PrintWriter writer;
        @Nullable String speakerName;

        ClientConnection(@NonNull Socket socket) {
            this.socket = socket;
        }

        void readLoop() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "UTF-8"));
                writer = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while (mRunning && (line = reader.readLine()) != null) {
                    handleMessage(line);
                }
            } catch (IOException e) {
                if (mRunning) {
                    Log.d(TAG, "[MeetingServer] Connection lost: " + e.getMessage());
                }
            } finally {
                handleDisconnect();
            }
        }

        void handleMessage(@NonNull String jsonStr) {
            MeetingProtocol.Message msg = MeetingProtocol.parse(jsonStr);
            if (msg == null) return;

            switch (msg.type) {
                case MeetingProtocol.TYPE_JOIN:
                    speakerName = msg.speaker;
                    Log.i(TAG, "[MeetingServer] Participant joined: " + speakerName);

                    // WHY: Send SYNC with owner's T0 so participant can align its clock.
                    // Must happen before any segments are exchanged.
                    if (mT0EpochMs > 0) {
                        send(MeetingProtocol.createSyncMessage(mT0EpochMs));
                        Log.d(TAG, "[MeetingServer] Sent T0 sync to " + speakerName
                                + " (t0=" + mT0EpochMs + ")");
                    }

                    if (mCallback != null) {
                        mCallback.onParticipantJoined(speakerName);
                    }
                    break;

                case MeetingProtocol.TYPE_SEGMENT:
                    if (msg.text != null && mCallback != null) {
                        // WHY: Pass the wire timestamp (already adjusted to owner clock
                        // by participant) instead of using owner's System.currentTimeMillis()
                        mCallback.onSegmentReceived(msg.speaker, msg.text,
                                msg.segmentIndex, msg.timestamp);
                    }
                    break;

                case MeetingProtocol.TYPE_LEAVE:
                    handleDisconnect();
                    break;
            }
        }

        void handleDisconnect() {
            mConnections.remove(this);
            close();
            if (speakerName != null && mCallback != null) {
                Log.i(TAG, "[MeetingServer] Participant left: " + speakerName);
                mCallback.onParticipantLeft(speakerName);
            }
        }

        void send(@NonNull String message) {
            if (writer != null) {
                try {
                    writer.println(message);
                } catch (Exception e) {
                    Log.e(TAG, "[MeetingServer] Send failed: " + e.getMessage());
                }
            }
        }

        void close() {
            try {
                if (!socket.isClosed()) socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
