/**
 * MeetingProtocol.java
 *
 * Defines the wire protocol for multi-device meeting communication.
 * Uses newline-delimited JSON messages over TCP.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-08
 */
package com.stelliq.aria.meeting;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

public class MeetingProtocol {

    private static final String TAG = "ARIA_MEETING";

    public static final String TYPE_JOIN = "join";
    public static final String TYPE_SYNC = "sync";
    public static final String TYPE_SEGMENT = "segment";
    public static final String TYPE_LEAVE = "leave";
    public static final String TYPE_END = "end";
    public static final String TYPE_TRANSCRIPT = "transcript";

    // NSD service type for meeting discovery
    public static final String NSD_SERVICE_TYPE = "_ariameeting._tcp.";
    public static final int DEFAULT_PORT = 0; // System-assigned

    /**
     * Represents a protocol message.
     */
    public static class Message {
        @NonNull public final String type;
        @NonNull public final String speaker;
        @Nullable public final String text;
        public final long timestamp;
        public final int segmentIndex;
        /** Owner's session start time, present only in SYNC messages. */
        public final long t0EpochMs;

        public Message(@NonNull String type, @NonNull String speaker,
                       @Nullable String text, long timestamp, int segmentIndex,
                       long t0EpochMs) {
            this.type = type;
            this.speaker = speaker;
            this.text = text;
            this.timestamp = timestamp;
            this.segmentIndex = segmentIndex;
            this.t0EpochMs = t0EpochMs;
        }
    }

    /**
     * Creates a JOIN message.
     */
    @NonNull
    public static String createJoinMessage(@NonNull String speakerName) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_JOIN);
            json.put("speaker", speakerName);
            json.put("timestamp", System.currentTimeMillis());
            return json.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Creates a SYNC message sent from owner to participant after JOIN.
     * Carries the owner's session start time so the participant can
     * compute its clock offset for proper segment ordering.
     *
     * @param t0EpochMs Owner's session start time (System.currentTimeMillis at session creation)
     */
    @NonNull
    public static String createSyncMessage(long t0EpochMs) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_SYNC);
            json.put("t0_epoch_ms", t0EpochMs);
            json.put("timestamp", System.currentTimeMillis());
            return json.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Creates a SEGMENT message with an explicit timestamp.
     * Used by participants after clock sync to send owner-aligned timestamps.
     */
    @NonNull
    public static String createSegmentMessage(@NonNull String speakerName,
                                               @NonNull String text,
                                               int segmentIndex,
                                               long timestamp) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_SEGMENT);
            json.put("speaker", speakerName);
            json.put("text", text);
            json.put("timestamp", timestamp);
            json.put("index", segmentIndex);
            return json.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Creates a SEGMENT message (transcript segment from a speaker).
     * Uses current system time as timestamp.
     */
    @NonNull
    public static String createSegmentMessage(@NonNull String speakerName,
                                               @NonNull String text,
                                               int segmentIndex) {
        return createSegmentMessage(speakerName, text, segmentIndex, System.currentTimeMillis());
    }

    /**
     * Creates a LEAVE message.
     */
    @NonNull
    public static String createLeaveMessage(@NonNull String speakerName) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_LEAVE);
            json.put("speaker", speakerName);
            json.put("timestamp", System.currentTimeMillis());
            return json.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Creates an END message (owner ends the meeting).
     */
    @NonNull
    public static String createEndMessage() {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_END);
            json.put("timestamp", System.currentTimeMillis());
            return json.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Creates a TRANSCRIPT broadcast (owner sends full transcript to all).
     */
    @NonNull
    public static String createTranscriptBroadcast(@NonNull String fullTranscript) {
        try {
            JSONObject json = new JSONObject();
            json.put("type", TYPE_TRANSCRIPT);
            json.put("text", fullTranscript);
            json.put("timestamp", System.currentTimeMillis());
            return json.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Parses a protocol message from JSON string.
     */
    @Nullable
    public static Message parse(@NonNull String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            String type = json.optString("type", "");
            String speaker = json.optString("speaker", "");
            String text = json.optString("text", null);
            long timestamp = json.optLong("timestamp", 0);
            int index = json.optInt("index", 0);
            long t0EpochMs = json.optLong("t0_epoch_ms", 0);
            return new Message(type, speaker, text, timestamp, index, t0EpochMs);
        } catch (JSONException e) {
            Log.e(TAG, "[MeetingProtocol.parse] Invalid message: " + e.getMessage());
            return null;
        }
    }
}
