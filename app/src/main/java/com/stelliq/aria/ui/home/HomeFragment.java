/**
 * HomeFragment.java
 *
 * Home screen with "Start Recording" CTA and recent sessions list.
 * Entry point for the main user flow.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-04
 */
package com.stelliq.aria.ui.home;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.stelliq.aria.R;
import com.stelliq.aria.db.AARDatabase;
import com.stelliq.aria.db.dao.AARSessionDao;
import com.stelliq.aria.db.entity.AARSession;
import com.stelliq.aria.service.ARIASessionService;
import com.stelliq.aria.ui.MainActivity;
import com.stelliq.aria.ui.sessions.SessionAdapter;
import com.stelliq.aria.util.Constants;

import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {

    private static final String TAG = Constants.LOG_TAG_UI;
    private static final int MAX_RECENT_SESSIONS = 5;

    private MaterialButton mBtnStartRecording;
    private MaterialButton mBtnViewAll;
    private RecyclerView mRecyclerRecentSessions;
    private LinearLayout mEmptyState;
    private CircularProgressIndicator mProgressBar;

    private SessionAdapter mAdapter;
    private boolean mIsObservingModelStatus = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        mBtnStartRecording = view.findViewById(R.id.btn_start_recording);
        mBtnViewAll = view.findViewById(R.id.btn_view_all_sessions);
        mRecyclerRecentSessions = view.findViewById(R.id.recycler_recent_sessions);
        mEmptyState = view.findViewById(R.id.empty_state);
        mProgressBar = view.findViewById(R.id.progress_bar);

        // Setup RecyclerView
        mAdapter = new SessionAdapter(this::onSessionClick);
        if (mRecyclerRecentSessions != null) {
            mRecyclerRecentSessions.setLayoutManager(new LinearLayoutManager(requireContext()));
            mRecyclerRecentSessions.setAdapter(mAdapter);
        }

        // Setup click listeners
        setupClickListeners();

        // Reset observer flag for new view lifecycle
        mIsObservingModelStatus = false;

        // Observe model status reactively
        observeModelStatus();

        // Load recent sessions
        loadRecentSessions();
    }

    @Override
    public void onResume() {
        super.onResume();
        // WHY: Service binding is async — may not be ready in onViewCreated().
        // Retry observation on each resume to catch late-binding scenarios.
        observeModelStatus();
    }

    private void setupClickListeners() {
        if (mBtnStartRecording != null) {
            mBtnStartRecording.setOnClickListener(v -> navigateToRecording());
        }

        if (mBtnViewAll != null) {
            mBtnViewAll.setOnClickListener(v -> navigateToSessions());
        }
    }

    /**
     * Observes SessionController.mModelsReadyLiveData reactively.
     * Button updates automatically when model state changes (e.g., after
     * whisper re-initialization following summarization).
     */
    private void observeModelStatus() {
        if (mIsObservingModelStatus) return;

        MainActivity activity = (MainActivity) getActivity();
        if (activity == null || !activity.isServiceBound()) return;

        ARIASessionService service = activity.getService();
        if (service == null || service.getSessionController() == null) return;

        service.getSessionController().getModelsReadyLiveData()
                .observe(getViewLifecycleOwner(), isReady -> {
                    if (mBtnStartRecording != null) {
                        boolean ready = isReady != null && isReady;
                        mBtnStartRecording.setEnabled(ready);
                        mBtnStartRecording.setText(ready
                                ? getString(R.string.btn_start_recording)
                                : "Loading...");
                    }
                });
        mIsObservingModelStatus = true;
    }

    private void loadRecentSessions() {
        AARSessionDao dao = AARDatabase.getInstance(requireContext()).aarSessionDao();
        dao.getAllSessionsLive().observe(getViewLifecycleOwner(), sessions -> {
            if (sessions == null || sessions.isEmpty()) {
                if (mEmptyState != null) mEmptyState.setVisibility(View.VISIBLE);
                if (mRecyclerRecentSessions != null) mRecyclerRecentSessions.setVisibility(View.GONE);
                if (mBtnViewAll != null) mBtnViewAll.setVisibility(View.GONE);
            } else {
                if (mEmptyState != null) mEmptyState.setVisibility(View.GONE);
                if (mRecyclerRecentSessions != null) mRecyclerRecentSessions.setVisibility(View.VISIBLE);
                if (mBtnViewAll != null) mBtnViewAll.setVisibility(View.VISIBLE);

                // Show only the most recent sessions
                List<AARSession> recent = sessions.size() > MAX_RECENT_SESSIONS
                        ? sessions.subList(0, MAX_RECENT_SESSIONS)
                        : sessions;
                mAdapter.setSessions(new ArrayList<>(recent));
            }
        });
    }

    private void onSessionClick(@NonNull AARSession session) {
        try {
            Bundle args = new Bundle();
            args.putString("sessionId", session.sessionUuid);
            NavController navController = Navigation.findNavController(requireView());
            navController.navigate(R.id.action_home_to_session_detail, args);
        } catch (Exception e) {
            Log.e(TAG, "[HomeFragment] Navigation to session detail failed: " + e.getMessage());
        }
    }

    private void navigateToRecording() {
        try {
            NavController navController = Navigation.findNavController(requireView());
            navController.navigate(R.id.action_home_to_recording);
        } catch (Exception e) {
            Log.e(TAG, "[HomeFragment] Navigation failed: " + e.getMessage());
        }
    }

    private void navigateToSessions() {
        try {
            NavController navController = Navigation.findNavController(requireView());
            navController.navigate(R.id.sessionsFragment);
        } catch (Exception e) {
            Log.e(TAG, "[HomeFragment] Navigation to sessions failed: " + e.getMessage());
        }
    }

    private void navigateToSettings() {
        try {
            NavController navController = Navigation.findNavController(requireView());
            navController.navigate(R.id.settingsFragment);
        } catch (Exception e) {
            Log.e(TAG, "[HomeFragment] Navigation to settings failed: " + e.getMessage());
        }
    }
}
