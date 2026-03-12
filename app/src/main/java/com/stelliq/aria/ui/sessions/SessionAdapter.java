/**
 * SessionAdapter.java
 *
 * RecyclerView adapter for displaying AARSession items in SessionsFragment.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-08
 */
package com.stelliq.aria.ui.sessions;

import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.stelliq.aria.R;
import com.stelliq.aria.db.entity.AARSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.ViewHolder> {

    public interface OnSessionClickListener {
        void onSessionClick(@NonNull AARSession session);
    }

    private List<AARSession> mSessions = new ArrayList<>();
    private List<AARSession> mFilteredSessions = new ArrayList<>();
    @Nullable
    private OnSessionClickListener mListener;

    public SessionAdapter(@Nullable OnSessionClickListener listener) {
        mListener = listener;
    }

    public void setSessions(@NonNull List<AARSession> sessions) {
        mSessions = sessions;
        mFilteredSessions = new ArrayList<>(sessions);
        notifyDataSetChanged();
    }

    public void filter(@NonNull String query) {
        if (query.isEmpty()) {
            mFilteredSessions = new ArrayList<>(mSessions);
        } else {
            String lower = query.toLowerCase(Locale.US);
            mFilteredSessions = new ArrayList<>();
            for (AARSession s : mSessions) {
                if (matches(s, lower)) {
                    mFilteredSessions.add(s);
                }
            }
        }
        notifyDataSetChanged();
    }

    private boolean matches(@NonNull AARSession s, @NonNull String query) {
        if (s.sessionTitle != null && s.sessionTitle.toLowerCase(Locale.US).contains(query)) return true;
        if (s.transcriptFull != null && s.transcriptFull.toLowerCase(Locale.US).contains(query)) return true;
        if (s.whatWasPlanned != null && s.whatWasPlanned.toLowerCase(Locale.US).contains(query)) return true;
        if (s.whatHappened != null && s.whatHappened.toLowerCase(Locale.US).contains(query)) return true;
        if (s.howToImprove != null && s.howToImprove.toLowerCase(Locale.US).contains(query)) return true;
        return false;
    }

    @Override
    public int getItemCount() {
        return mFilteredSessions.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AARSession session = mFilteredSessions.get(position);
        holder.bind(session);
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textTitle;
        final TextView textDate;
        final TextView textDuration;
        final TextView textWordCount;
        final TextView textSummaryPreview;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textTitle = itemView.findViewById(R.id.textTitle);
            textDate = itemView.findViewById(R.id.textDate);
            textDuration = itemView.findViewById(R.id.textDuration);
            textWordCount = itemView.findViewById(R.id.textWordCount);
            textSummaryPreview = itemView.findViewById(R.id.textSummaryPreview);
        }

        void bind(@NonNull AARSession session) {
            // Title: prefer LLM-generated title, then fallback to transcript snippet
            String title = "Recording";
            if (session.sessionTitle != null && !session.sessionTitle.isEmpty()) {
                title = session.sessionTitle;
            } else if (session.transcriptFull != null && !session.transcriptFull.isEmpty()) {
                title = session.transcriptFull.substring(0, Math.min(50, session.transcriptFull.length()));
                if (session.transcriptFull.length() > 50) title += "...";
            }
            textTitle.setText(title);

            // Date
            textDate.setText(DateFormat.format("MMM d, yyyy", session.startedAtEpochMs));

            // Duration
            textDuration.setText(formatDuration(session.durationMs));

            // Word count
            int wordCount = 0;
            if (session.transcriptFull != null && !session.transcriptFull.isEmpty()) {
                wordCount = session.transcriptFull.trim().split("\\s+").length;
            }
            textWordCount.setText(String.format(Locale.US, "%,d words", wordCount));

            // Summary preview
            String preview = "";
            if (session.whatHappened != null && !session.whatHappened.isEmpty()) {
                preview = session.whatHappened;
            } else if (session.transcriptFull != null && !session.transcriptFull.isEmpty()) {
                preview = session.transcriptFull.substring(0, Math.min(150, session.transcriptFull.length()));
            }
            if (preview.isEmpty()) {
                textSummaryPreview.setVisibility(View.GONE);
            } else {
                textSummaryPreview.setVisibility(View.VISIBLE);
                textSummaryPreview.setText(preview);
            }

            // Click handler
            itemView.setOnClickListener(v -> {
                if (mListener != null) mListener.onSessionClick(session);
            });
        }
    }

    @NonNull
    public static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
