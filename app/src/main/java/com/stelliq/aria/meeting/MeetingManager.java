/**
 * MeetingManager.java
 *
 * Coordinates multi-device meeting lifecycle including NSD discovery,
 * server/client management, and transcript aggregation.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-08
 */
package com.stelliq.aria.meeting;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class MeetingManager {

    private static final String TAG = "ARIA_MEETING";

    /**
     * Callback for meeting events delivered on the main thread.
     */
    public interface MeetingCallback {
        void onParticipantCountChanged(int count, @NonNull List<String> names);
        void onRemoteSegmentReceived(@NonNull String speaker, @NonNull String text);
        void onMeetingEnded();
        void onError(@NonNull String error);
    }

    /**
     * Callback for meeting discovery.
     */
    public interface DiscoveryCallback {
        void onMeetingFound(@NonNull String meetingName, @NonNull NsdServiceInfo serviceInfo);
        void onMeetingLost(@NonNull String meetingName);
    }

    public enum Role { OWNER, PARTICIPANT, NONE }

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final String mSpeakerName;

    private Role mRole = Role.NONE;
    @Nullable private MeetingCallback mCallback;
    @Nullable private DiscoveryCallback mDiscoveryCallback;

    // Owner-side
    @Nullable private MeetingServer mServer;
    @Nullable private NsdManager mNsdManager;
    @Nullable private NsdManager.RegistrationListener mRegistrationListener;
    private boolean mRegistered = false;

    // Participant-side
    @Nullable private MeetingClient mClient;
    @Nullable private NsdManager.DiscoveryListener mDiscoveryListener;

    // Aggregated transcript (owner only) — ordered by timestamp
    private final TreeMap<Long, SpeakerSegment> mAggregatedSegments = new TreeMap<>();
    private int mOwnerSegmentIndex = 0;

    // WHY: Session start time used as T0 reference for cross-device clock alignment.
    // Sent to participants on JOIN so they can compute clock offset.
    private long mT0EpochMs = 0;

    public static class SpeakerSegment {
        @NonNull public final String speaker;
        @NonNull public final String text;
        public final long timestamp;

        SpeakerSegment(@NonNull String speaker, @NonNull String text, long timestamp) {
            this.speaker = speaker;
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    public MeetingManager(@NonNull Context context, @NonNull String speakerName) {
        mContext = context.getApplicationContext();
        mSpeakerName = speakerName;
        mNsdManager = (NsdManager) mContext.getSystemService(Context.NSD_SERVICE);
    }

    public void setCallback(@Nullable MeetingCallback callback) {
        mCallback = callback;
    }

    public void setDiscoveryCallback(@Nullable DiscoveryCallback callback) {
        mDiscoveryCallback = callback;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OWNER OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates a meeting room as the owner.
     * Starts TCP server and registers NSD service for discovery.
     *
     * @return true if meeting was created successfully
     */
    public boolean createMeeting() {
        if (mRole != Role.NONE) {
            Log.w(TAG, "[MeetingManager] Already in a meeting");
            return false;
        }

        mT0EpochMs = System.currentTimeMillis();

        mServer = new MeetingServer();
        mServer.setT0EpochMs(mT0EpochMs);
        mServer.setCallback(new MeetingServer.ServerCallback() {
            @Override
            public void onParticipantJoined(@NonNull String speakerName) {
                mMainHandler.post(() -> notifyParticipantChange());
            }

            @Override
            public void onParticipantLeft(@NonNull String speakerName) {
                mMainHandler.post(() -> notifyParticipantChange());
            }

            @Override
            public void onSegmentReceived(@NonNull String speakerName, @NonNull String text,
                                          int segmentIndex, long timestamp) {
                // WHY: Use the wire timestamp (already adjusted to owner clock by
                // participant's T0 offset) instead of owner's System.currentTimeMillis().
                // This gives correct chronological ordering across devices.
                synchronized (mAggregatedSegments) {
                    // Use micros to avoid key collision
                    long key = timestamp * 1000 + segmentIndex;
                    mAggregatedSegments.put(key, new SpeakerSegment(speakerName, text, timestamp));
                }

                mMainHandler.post(() -> {
                    if (mCallback != null) {
                        mCallback.onRemoteSegmentReceived(speakerName, text);
                    }
                });

                // Broadcast updated transcript to all participants
                broadcastFullTranscript();
            }

            @Override
            public void onError(@NonNull String error) {
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onError(error);
                });
            }
        });

        int port = mServer.start();
        if (port < 0) return false;

        mRole = Role.OWNER;

        // Register NSD service so others can discover this meeting
        registerNsd(port);

        Log.i(TAG, "[MeetingManager] Meeting created by " + mSpeakerName + " on port " + port);
        return true;
    }

    /**
     * Adds a local transcript segment (from the owner's microphone).
     */
    public void addLocalSegment(@NonNull String text) {
        long timestamp = System.currentTimeMillis();
        synchronized (mAggregatedSegments) {
            long key = timestamp * 1000 + (mOwnerSegmentIndex++);
            mAggregatedSegments.put(key, new SpeakerSegment(mSpeakerName, text, timestamp));
        }

        // Broadcast to participants
        if (mServer != null && mServer.getParticipantCount() > 0) {
            broadcastFullTranscript();
        }
    }

    /**
     * Ends the meeting and disconnects all participants.
     */
    public void endMeeting() {
        if (mRole == Role.OWNER && mServer != null) {
            mServer.stop();
            mServer = null;
        }
        if (mRole == Role.PARTICIPANT && mClient != null) {
            mClient.disconnect();
            mClient = null;
        }

        unregisterNsd();
        stopDiscovery();
        mRole = Role.NONE;

        Log.i(TAG, "[MeetingManager] Meeting ended");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PARTICIPANT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Starts discovering nearby meetings via NSD.
     */
    public void startDiscovery() {
        if (mNsdManager == null) return;

        mDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "[MeetingManager] Discovery start failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "[MeetingManager] Discovery stop failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.i(TAG, "[MeetingManager] Discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "[MeetingManager] Discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.i(TAG, "[MeetingManager] Found: " + serviceInfo.getServiceName());
                // Resolve to get host/port
                mNsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        Log.e(TAG, "[MeetingManager] Resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        mMainHandler.post(() -> {
                            if (mDiscoveryCallback != null) {
                                mDiscoveryCallback.onMeetingFound(info.getServiceName(), info);
                            }
                        });
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                mMainHandler.post(() -> {
                    if (mDiscoveryCallback != null) {
                        mDiscoveryCallback.onMeetingLost(serviceInfo.getServiceName());
                    }
                });
            }
        };

        mNsdManager.discoverServices(
                MeetingProtocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
    }

    /**
     * Stops discovering meetings.
     */
    public void stopDiscovery() {
        if (mNsdManager != null && mDiscoveryListener != null) {
            try {
                mNsdManager.stopServiceDiscovery(mDiscoveryListener);
            } catch (Exception e) {
                // Ignore if already stopped
            }
            mDiscoveryListener = null;
        }
    }

    /**
     * Joins an existing meeting.
     */
    public void joinMeeting(@NonNull NsdServiceInfo serviceInfo) {
        if (mRole != Role.NONE) {
            Log.w(TAG, "[MeetingManager] Already in a meeting");
            return;
        }

        InetAddress host = serviceInfo.getHost();
        int port = serviceInfo.getPort();

        mClient = new MeetingClient(mSpeakerName);
        mClient.setCallback(new MeetingClient.ClientCallback() {
            @Override
            public void onConnected() {
                mRole = Role.PARTICIPANT;
                Log.i(TAG, "[MeetingManager] Joined meeting as " + mSpeakerName);
            }

            @Override
            public void onDisconnected() {
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onMeetingEnded();
                });
            }

            @Override
            public void onTranscriptUpdate(@NonNull String fullTranscript) {
                // Participant receives full transcript from owner
                // This will be used to display the combined transcript
            }

            @Override
            public void onMeetingEnded() {
                mRole = Role.NONE;
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onMeetingEnded();
                });
            }

            @Override
            public void onError(@NonNull String error) {
                mMainHandler.post(() -> {
                    if (mCallback != null) mCallback.onError(error);
                });
            }
        });

        stopDiscovery();
        mClient.connect(host, port);
    }

    /**
     * Sends a transcript segment to the meeting owner (participant mode).
     */
    public void sendSegment(@NonNull String text, int segmentIndex) {
        if (mRole == Role.PARTICIPANT && mClient != null) {
            mClient.sendSegment(text, segmentIndex);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSCRIPT AGGREGATION (owner only)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the full aggregated multi-speaker transcript.
     * Format: "Speaker Name: text\nAnother Speaker: text\n..."
     */
    @NonNull
    public String getAggregatedTranscript() {
        StringBuilder sb = new StringBuilder();
        synchronized (mAggregatedSegments) {
            String lastSpeaker = null;
            for (SpeakerSegment seg : mAggregatedSegments.values()) {
                if (!seg.speaker.equals(lastSpeaker)) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(seg.speaker).append(": ");
                    lastSpeaker = seg.speaker;
                } else {
                    sb.append(" ");
                }
                sb.append(seg.text);
            }
        }
        return sb.toString();
    }

    /**
     * Returns the total participant count (including owner).
     */
    public int getTotalParticipantCount() {
        int count = 1; // Owner
        if (mServer != null) {
            count += mServer.getParticipantCount();
        }
        return count;
    }

    /**
     * Returns all participant names (including owner).
     */
    @NonNull
    public List<String> getAllParticipantNames() {
        List<String> names = new ArrayList<>();
        names.add(mSpeakerName + " (You)");
        if (mServer != null) {
            names.addAll(mServer.getParticipantNames());
        }
        return names;
    }

    public Role getRole() {
        return mRole;
    }

    @NonNull
    public String getSpeakerName() {
        return mSpeakerName;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NSD REGISTRATION (owner only)
    // ═══════════════════════════════════════════════════════════════════════════

    private void registerNsd(int port) {
        if (mNsdManager == null) return;

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName("ARIA-" + mSpeakerName);
        serviceInfo.setServiceType(MeetingProtocol.NSD_SERVICE_TYPE);
        serviceInfo.setPort(port);

        mRegistrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "[MeetingManager] NSD registration failed: " + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "[MeetingManager] NSD unregistration failed: " + errorCode);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                mRegistered = true;
                Log.i(TAG, "[MeetingManager] NSD registered: " + info.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                mRegistered = false;
                Log.i(TAG, "[MeetingManager] NSD unregistered");
            }
        };

        mNsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, mRegistrationListener);
    }

    private void unregisterNsd() {
        if (mNsdManager != null && mRegistrationListener != null && mRegistered) {
            try {
                mNsdManager.unregisterService(mRegistrationListener);
            } catch (Exception e) {
                // Ignore
            }
            mRegistrationListener = null;
            mRegistered = false;
        }
    }

    private void notifyParticipantChange() {
        if (mCallback != null) {
            mCallback.onParticipantCountChanged(
                    getTotalParticipantCount(), getAllParticipantNames());
        }
    }

    private void broadcastFullTranscript() {
        if (mServer != null) {
            String transcript = getAggregatedTranscript();
            mServer.broadcast(MeetingProtocol.createTranscriptBroadcast(transcript));
        }
    }
}
