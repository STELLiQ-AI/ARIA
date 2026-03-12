/**
 * SessionDetailFragment.java
 *
 * Individual session detail view with summary and transcript tabs.
 * Shows AAR summary sections and full transcript.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-04
 */
package com.stelliq.aria.ui.session;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.stelliq.aria.R;
import com.stelliq.aria.db.AARDatabase;
import com.stelliq.aria.db.entity.AARSession;
import com.stelliq.aria.service.ARIASessionService;
import com.stelliq.aria.ui.sessions.SessionAdapter;
import com.stelliq.aria.util.Constants;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionDetailFragment extends Fragment {

    private static final String TAG = Constants.LOG_TAG_UI;
    private static final String ARG_SESSION_ID = "sessionId";

    private String mSessionId;
    private AARSession mSession;

    private TextView mTextTitle;
    private TextView mTextDate;
    private TextView mTextDuration;
    private TextView mTextWordCount;
    private TabLayout mTabLayout;
    private ViewPager2 mViewPager;
    private CircularProgressIndicator mProgressBar;
    private ImageButton mBtnBack;
    private ImageButton mBtnDelete;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() != null) {
            mSessionId = getArguments().getString(ARG_SESSION_ID);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_session_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Log.d(TAG, "[SessionDetailFragment] Showing session: " + mSessionId);

        // Find views
        mTextTitle = view.findViewById(R.id.textTitle);
        mTextDate = view.findViewById(R.id.textDate);
        mTextDuration = view.findViewById(R.id.textDuration);
        mTextWordCount = view.findViewById(R.id.textWordCount);
        mTabLayout = view.findViewById(R.id.tabLayout);
        mViewPager = view.findViewById(R.id.viewPager);
        mProgressBar = view.findViewById(R.id.progressBar);
        mBtnBack = view.findViewById(R.id.btnBack);
        mBtnDelete = view.findViewById(R.id.btnDelete);

        // Back button
        if (mBtnBack != null) {
            mBtnBack.setOnClickListener(v -> {
                try {
                    Navigation.findNavController(requireView()).popBackStack();
                } catch (Exception e) {
                    Log.e(TAG, "[SessionDetailFragment] Back navigation failed");
                }
            });
        }

        // Delete button
        if (mBtnDelete != null) {
            mBtnDelete.setOnClickListener(v -> confirmDeleteSession());
        }

        // Show loading, then load from DB on background thread
        if (mProgressBar != null) mProgressBar.setVisibility(View.VISIBLE);
        loadSession();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mExecutor.shutdownNow();
    }

    private void loadSession() {
        if (mSessionId == null) {
            Log.e(TAG, "[SessionDetailFragment] No session ID provided");
            return;
        }

        // WHY: Capture context on main thread to avoid IllegalStateException
        // if fragment detaches before executor runs.
        Context context = requireContext();
        mExecutor.execute(() -> {
            try {
                mSession = AARDatabase.getInstance(context)
                        .aarSessionDao().getByUuid(mSessionId);

                if (mSession != null) {
                    Activity activity = getActivity();
                    if (activity != null) {
                        activity.runOnUiThread(this::displaySession);
                    }
                } else {
                    Log.e(TAG, "[SessionDetailFragment] Session not found: " + mSessionId);
                    Activity activity = getActivity();
                    if (activity != null) {
                        activity.runOnUiThread(() -> {
                            if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
                            if (mTextTitle != null) mTextTitle.setText("Session not found");
                        });
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[SessionDetailFragment] Failed to load: " + e.getMessage(), e);
            }
        });
    }

    private void displaySession() {
        if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);
        if (mSession == null) return;

        // Title: prefer LLM-generated title, then fallback
        String title = "Recording";
        if (mSession.sessionTitle != null && !mSession.sessionTitle.isEmpty()) {
            title = mSession.sessionTitle;
        } else if (mSession.transcriptFull != null && !mSession.transcriptFull.isEmpty()) {
            title = mSession.transcriptFull.substring(0, Math.min(50, mSession.transcriptFull.length()));
            if (mSession.transcriptFull.length() > 50) title += "...";
        }
        if (mTextTitle != null) mTextTitle.setText(title);

        // Date
        if (mTextDate != null) {
            mTextDate.setText(DateFormat.format("MMMM d, yyyy 'at' h:mm a", mSession.startedAtEpochMs));
        }

        // Duration
        if (mTextDuration != null) {
            mTextDuration.setText(SessionAdapter.formatDuration(mSession.durationMs));
        }

        // Word count
        if (mTextWordCount != null) {
            int wordCount = 0;
            if (mSession.transcriptFull != null && !mSession.transcriptFull.isEmpty()) {
                wordCount = mSession.transcriptFull.trim().split("\\s+").length;
            }
            mTextWordCount.setText(String.format(Locale.US, "%,d words", wordCount));
        }

        // Setup tabs with ViewPager2
        setupTabs();
    }

    private void setupTabs() {
        if (mViewPager == null || mTabLayout == null) return;

        mViewPager.setAdapter(new DetailPagerAdapter(this));

        new TabLayoutMediator(mTabLayout, mViewPager, (tab, position) -> {
            tab.setText(position == 0 ? "Summary" : "Transcript");
        }).attach();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DELETE SESSION
    // ═══════════════════════════════════════════════════════════════════════════

    private void confirmDeleteSession() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_message)
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> deleteSession())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteSession() {
        if (mSession == null || mSessionId == null) return;

        // WHY: Capture context on main thread before background work
        Context context = requireContext();
        mExecutor.execute(() -> {
            try {
                AARDatabase db = AARDatabase.getInstance(context);

                // WHY: Delete related entities first, then the session itself.
                // TranscriptSegments and PipelineMetrics reference session_uuid.
                db.transcriptSegmentDao().deleteForSession(mSessionId);
                db.pipelineMetricDao().deleteForSession(mSessionId);
                db.aarSessionDao().delete(mSession);

                Log.i(TAG, "[SessionDetailFragment] Deleted session: " + mSessionId);

                Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            View view = getView();
                            if (view != null) {
                                Navigation.findNavController(view).popBackStack();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "[SessionDetailFragment] Navigation after delete failed");
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "[SessionDetailFragment] Delete failed: " + e.getMessage(), e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TAB FRAGMENTS
    // ═══════════════════════════════════════════════════════════════════════════

    private class DetailPagerAdapter extends FragmentStateAdapter {
        DetailPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == 0) {
                return SummaryTabFragment.newInstance(mSession);
            } else {
                return TranscriptTabFragment.newInstance(mSession);
            }
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }

    /**
     * Summary tab showing AAR fields.
     */
    public static class SummaryTabFragment extends Fragment {
        private static final String KEY_PLANNED = "planned";
        private static final String KEY_HAPPENED = "happened";
        private static final String KEY_WHY = "why";
        private static final String KEY_IMPROVE = "improve";
        private static final String KEY_SUCCESS = "success";
        private static final String KEY_SESSION_UUID = "session_uuid";

        static SummaryTabFragment newInstance(@Nullable AARSession session) {
            SummaryTabFragment f = new SummaryTabFragment();
            Bundle args = new Bundle();
            if (session != null) {
                args.putString(KEY_PLANNED, session.whatWasPlanned);
                args.putString(KEY_HAPPENED, session.whatHappened);
                args.putString(KEY_WHY, session.whyItHappened);
                args.putString(KEY_IMPROVE, session.howToImprove);
                args.putBoolean(KEY_SUCCESS, session.llmParseSuccess);
                args.putString(KEY_SESSION_UUID, session.sessionUuid);
            }
            f.setArguments(args);
            return f;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            // Simple scrollable text layout
            android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
            scroll.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scroll.setClipToPadding(false);
            scroll.setPadding(dp(20), dp(16), dp(20), dp(100));

            android.widget.LinearLayout layout = new android.widget.LinearLayout(requireContext());
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            Bundle args = getArguments();
            if (args == null || !args.getBoolean(KEY_SUCCESS, false)) {
                // No summary available
                TextView noSummary = createLabel(getString(R.string.no_summary));
                noSummary.setTextColor(getResources().getColor(R.color.text_tertiary, null));
                noSummary.setPadding(0, dp(40), 0, 0);
                noSummary.setGravity(android.view.Gravity.CENTER);
                layout.addView(noSummary);
            } else {
                addSection(layout, "What Was Planned", args.getString(KEY_PLANNED));
                addSection(layout, "What Happened", args.getString(KEY_HAPPENED));
                addSection(layout, "Why It Happened", args.getString(KEY_WHY));
                addSection(layout, "How to Improve", args.getString(KEY_IMPROVE));
            }

            // WHY: Regenerate button is always visible — useful both when summary failed
            // (primary use case) and when user wants to re-run with a different template.
            addRegenerateButton(layout, args);

            scroll.addView(layout);
            return scroll;
        }

        private void addRegenerateButton(@NonNull android.widget.LinearLayout parent,
                                          @Nullable Bundle args) {
            String sessionUuid = args != null ? args.getString(KEY_SESSION_UUID) : null;
            if (sessionUuid == null) return;

            MaterialButton btnRegenerate = new MaterialButton(
                    requireContext(), null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnRegenerate.setText(R.string.regenerate_summary);
            btnRegenerate.setIconResource(R.drawable.ic_refresh);
            btnRegenerate.setIconGravity(MaterialButton.ICON_GRAVITY_START);

            android.widget.LinearLayout.LayoutParams params =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(24);
            btnRegenerate.setLayoutParams(params);

            btnRegenerate.setOnClickListener(v -> confirmRegenerate(sessionUuid));
            parent.addView(btnRegenerate);
        }

        private void confirmRegenerate(@NonNull String sessionUuid) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.regenerate_confirm_title)
                    .setMessage(R.string.regenerate_confirm_message)
                    .setPositiveButton(R.string.regenerate_summary, (dialog, which) -> {
                        startRegeneration(sessionUuid);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }

        private void startRegeneration(@NonNull String sessionUuid) {
            Context context = requireContext();

            // WHY: Send intent to ARIASessionService which owns the LLM engine.
            // Service handles foreground promotion, model loading, and DB update.
            Intent intent = new Intent(context, ARIASessionService.class);
            intent.setAction(Constants.ACTION_REGENERATE_SUMMARY);
            intent.putExtra(Constants.EXTRA_SESSION_UUID, sessionUuid);
            ContextCompat.startForegroundService(context, intent);

            Toast.makeText(context, R.string.regenerate_started, Toast.LENGTH_SHORT).show();

            // Navigate back — user will get a notification when summary is ready.
            // WHY: Use parent fragment's view for NavController lookup since this
            // fragment is inside a ViewPager2 and doesn't have direct nav access.
            try {
                Fragment parent = getParentFragment();
                if (parent != null && parent.getView() != null) {
                    Navigation.findNavController(parent.requireView()).popBackStack();
                }
            } catch (Exception e) {
                Log.e(Constants.LOG_TAG_UI, "[SummaryTabFragment] Navigation after regenerate failed");
            }
        }

        private void addSection(@NonNull android.widget.LinearLayout parent,
                                @NonNull String title, @Nullable String content) {
            if (content == null || content.isEmpty()) return;

            // Section title
            TextView titleView = createLabel(title);
            titleView.setTextSize(14);
            titleView.setTextColor(getResources().getColor(R.color.primary, null));
            titleView.setTypeface(null, android.graphics.Typeface.BOLD);
            android.widget.LinearLayout.LayoutParams titleParams = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.topMargin = dp(16);
            titleView.setLayoutParams(titleParams);
            parent.addView(titleView);

            // Section content
            TextView contentView = createLabel(content);
            contentView.setTextSize(14);
            contentView.setTextColor(getResources().getColor(R.color.text_secondary, null));
            contentView.setLineSpacing(0, 1.4f);
            android.widget.LinearLayout.LayoutParams contentParams = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            contentParams.topMargin = dp(6);
            contentView.setLayoutParams(contentParams);
            parent.addView(contentView);
        }

        private TextView createLabel(String text) {
            TextView tv = new TextView(requireContext());
            tv.setText(text);
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return tv;
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density);
        }
    }

    /**
     * Transcript tab showing full text.
     */
    public static class TranscriptTabFragment extends Fragment {
        private static final String KEY_TRANSCRIPT = "transcript";

        static TranscriptTabFragment newInstance(@Nullable AARSession session) {
            TranscriptTabFragment f = new TranscriptTabFragment();
            Bundle args = new Bundle();
            if (session != null) {
                args.putString(KEY_TRANSCRIPT, session.transcriptFull);
            }
            f.setArguments(args);
            return f;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                 @Nullable Bundle savedInstanceState) {
            android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
            scroll.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scroll.setClipToPadding(false);
            scroll.setPadding(dp(20), dp(16), dp(20), dp(100));

            String transcript = getArguments() != null ? getArguments().getString(KEY_TRANSCRIPT) : null;

            TextView tv = new TextView(requireContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setTextSize(14);
            tv.setLineSpacing(0, 1.5f);

            if (transcript != null && !transcript.isEmpty()) {
                tv.setText(styleSpeakerName(transcript));
                tv.setTextColor(getResources().getColor(R.color.text_secondary, null));
            } else {
                tv.setText("No transcript available");
                tv.setTextColor(getResources().getColor(R.color.text_tertiary, null));
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding(0, dp(40), 0, 0);
            }

            scroll.addView(tv);
            return scroll;
        }

        private CharSequence styleSpeakerName(@NonNull String transcript) {
            SpannableString spannable = new SpannableString(transcript);
            int primaryColor = getResources().getColor(R.color.primary, null);

            // Style speaker name ("Stephen:") if present at the start
            int colonNewline = transcript.indexOf(":\n");
            if (colonNewline > 0 && colonNewline <= 30) {
                int nameEnd = colonNewline + 1;
                spannable.setSpan(
                        new ForegroundColorSpan(primaryColor),
                        0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(
                        new StyleSpan(Typeface.BOLD),
                        0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(
                        new RelativeSizeSpan(1.15f),
                        0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            // Style all [M:SS] timestamps in gold and bold
            int searchFrom = 0;
            while (searchFrom < transcript.length()) {
                int bracketOpen = transcript.indexOf('[', searchFrom);
                if (bracketOpen < 0) break;
                int bracketClose = transcript.indexOf(']', bracketOpen);
                if (bracketClose < 0) break;
                String inside = transcript.substring(bracketOpen + 1, bracketClose);
                if (inside.contains(":") && inside.length() <= 7) {
                    spannable.setSpan(
                            new ForegroundColorSpan(primaryColor),
                            bracketOpen, bracketClose + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannable.setSpan(
                            new StyleSpan(Typeface.BOLD),
                            bracketOpen, bracketClose + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                searchFrom = bracketClose + 1;
            }

            return spannable;
        }

        private int dp(int value) {
            return (int) (value * getResources().getDisplayMetrics().density);
        }
    }
}
