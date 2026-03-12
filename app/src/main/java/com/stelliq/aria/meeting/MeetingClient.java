/**
 * MeetingClient.java
 *
 * TCP client running on participant devices.
 * Connects to the meeting owner's server and sends
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
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MeetingClient {

    private static final String TAG = "ARIA_MEETING";
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * Callback for client events.
     */
    public interface ClientCallback {
        void onConnected();
        void onDisconnected();
        void onTranscriptUpdate(@NonNull String fullTranscript);
        void onMeetingEnded();
        void onError(@NonNull String error);
    }

    private Socket mSocket;
    @Nullable
    private PrintWriter mWriter;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    @Nullable
    private ClientCallback mCallback;
    private volatile boolean mConnected = false;
    @NonNull
    private final String mSpeakerName;

    // WHY: Clock offset between this device and the owner, computed from T0 SYNC.
    // offset = localTimeAtSync - ownerT0. Subtract from local timestamps to get
    // owner-aligned time. Zero until SYNC received (falls back to local clock).
    private volatile long mClockOffsetMs = 0;
    private volatile boolean mClockSynced = false;

    public MeetingClient(@NonNull String speakerName) {
        mSpeakerName = speakerName;
    }

    public void setCallback(@Nullable ClientCallback callback) {
        mCallback = callback;
    }

    /**
     * Connects to a meeting server.
     *
     * @param host Server IP address
     * @param port Server port
     */
    public void connect(@NonNull InetAddress host, int port) {
        mExecutor.execute(() -> {
            try {
                Log.i(TAG, "[MeetingClient] Connecting to " + host + ":" + port);
                mSocket = new Socket();
                mSocket.connect(new java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);

                mWriter = new PrintWriter(mSocket.getOutputStream(), true);
                mConnected = true;

                // Send join message
                send(MeetingProtocol.createJoinMessage(mSpeakerName));

                Log.i(TAG, "[MeetingClient] Connected as " + mSpeakerName);
                if (mCallback != null) mCallback.onConnected();

                // Read messages from server
                readLoop();

            } catch (IOException e) {
                Log.e(TAG, "[MeetingClient] Connection failed: " + e.getMessage());
                if (mCallback != null) mCallback.onError("Failed to join meeting: " + e.getMessage());
            }
        });
    }

    /**
     * Sends a transcript segment to the meeting owner.
     * Timestamp is adjusted by the T0 clock offset so segments
     * from all devices are aligned to the owner's clock.
     */
    public void sendSegment(@NonNull String text, int segmentIndex) {
        if (!mConnected) return;
        // WHY: Subtract clock offset to convert local time → owner clock space.
        // If SYNC hasn't arrived yet (offset=0), local time is used as fallback.
        long adjustedTimestamp = System.currentTimeMillis() - mClockOffsetMs;
        send(MeetingProtocol.createSegmentMessage(mSpeakerName, text, segmentIndex, adjustedTimestamp));
    }

    /**
     * Disconnects from the meeting.
     */
    public void disconnect() {
        if (mConnected) {
            send(MeetingProtocol.createLeaveMessage(mSpeakerName));
        }
        mConnected = false;
        close();
        Log.i(TAG, "[MeetingClient] Disconnected");
    }

    public boolean isConnected() {
        return mConnected;
    }

    private void send(@NonNull String message) {
        if (mWriter != null) {
            try {
                mWriter.println(message);
            } catch (Exception e) {
                Log.e(TAG, "[MeetingClient] Send failed: " + e.getMessage());
            }
        }
    }

    private void readLoop() {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(mSocket.getInputStream(), "UTF-8"));

            String line;
            while (mConnected && (line = reader.readLine()) != null) {
                MeetingProtocol.Message msg = MeetingProtocol.parse(line);
                if (msg == null) continue;

                switch (msg.type) {
                    case MeetingProtocol.TYPE_SYNC:
                        // WHY: Owner sent its T0 epoch. Compute clock offset.
                        // offset = our_clock_now - owner_t0.
                        // To convert our future timestamps to owner's clock:
                        //   adjustedTime = ourTime - offset
                        mClockOffsetMs = System.currentTimeMillis() - msg.t0EpochMs;
                        mClockSynced = true;
                        Log.i(TAG, "[MeetingClient] T0 clock synced. Offset="
                                + mClockOffsetMs + "ms");
                        break;

                    case MeetingProtocol.TYPE_END:
                        Log.i(TAG, "[MeetingClient] Meeting ended by owner");
                        if (mCallback != null) mCallback.onMeetingEnded();
                        disconnect();
                        break;

                    case MeetingProtocol.TYPE_TRANSCRIPT:
                        // Owner broadcasting full transcript
                        if (msg.text != null && mCallback != null) {
                            mCallback.onTranscriptUpdate(msg.text);
                        }
                        break;
                }
            }
        } catch (IOException e) {
            if (mConnected) {
                Log.d(TAG, "[MeetingClient] Connection lost: " + e.getMessage());
                mConnected = false;
                if (mCallback != null) mCallback.onDisconnected();
            }
        }
    }

    private void close() {
        try {
            if (mSocket != null && !mSocket.isClosed()) {
                mSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }
    }
}
