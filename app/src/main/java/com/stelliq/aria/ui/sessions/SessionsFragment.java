/**
 * SessionsFragment.java
 *
 * Sessions list screen with search functionality.
 * Shows all recorded sessions sorted by date.
 *
 * @author STELLiQ Engineering
 * @version 1.0.0
 * @since ARIA Commercial Build — 2026-03-04
 */
package com.stelliq.aria.ui.sessions;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
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

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.stelliq.aria.R;
import com.stelliq.aria.db.AARDatabase;
import com.stelliq.aria.db.dao.AARSessionDao;
import com.stelliq.aria.db.entity.AARSession;
import com.stelliq.aria.util.Constants;

public class SessionsFragment extends Fragment {

    private static final String TAG = Constants.LOG_TAG_UI;

    private TextView mTextSessionCount;
    private TextInputEditText mEditSearch;
    private RecyclerView mRecyclerSessions;
    private LinearLayout mEmptyState;
    private CircularProgressIndicator mProgressBar;

    private SessionAdapter mAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_sessions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        mTextSessionCount = view.findViewById(R.id.textSessionCount);
        mEditSearch = view.findViewById(R.id.editSearch);
        mRecyclerSessions = view.findViewById(R.id.recyclerSessions);
        mEmptyState = view.findViewById(R.id.emptyState);
        mProgressBar = view.findViewById(R.id.progressBar);

        // Setup RecyclerView with adapter
        mAdapter = new SessionAdapter(this::onSessionClick);
        if (mRecyclerSessions != null) {
            mRecyclerSessions.setLayoutManager(new LinearLayoutManager(requireContext()));
            mRecyclerSessions.setAdapter(mAdapter);
        }

        // Setup search
        setupSearch();

        // Load sessions from Room database
        loadSessions();
    }

    private void setupSearch() {
        if (mEditSearch != null) {
            mEditSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (mAdapter != null) {
                        mAdapter.filter(s.toString());
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
    }

    private void loadSessions() {
        Log.d(TAG, "[SessionsFragment] Loading sessions from database");

        if (mProgressBar != null) {
            mProgressBar.setVisibility(View.VISIBLE);
        }

        // Observe LiveData from Room — auto-updates when sessions are saved
        AARSessionDao dao = AARDatabase.getInstance(requireContext()).aarSessionDao();
        dao.getAllSessionsLive().observe(getViewLifecycleOwner(), sessions -> {
            Log.d(TAG, "[SessionsFragment] Received " + (sessions != null ? sessions.size() : 0) + " sessions");

            if (mProgressBar != null) mProgressBar.setVisibility(View.GONE);

            if (sessions == null || sessions.isEmpty()) {
                showEmptyState(true);
                updateSessionCount(0);
            } else {
                showEmptyState(false);
                updateSessionCount(sessions.size());
                mAdapter.setSessions(sessions);
            }
        });
    }

    private void onSessionClick(@NonNull AARSession session) {
        try {
            Bundle args = new Bundle();
            args.putString("sessionId", session.sessionUuid);
            NavController navController = Navigation.findNavController(requireView());
            navController.navigate(R.id.action_sessions_to_detail, args);
        } catch (Exception e) {
            Log.e(TAG, "[SessionsFragment] Navigation to detail failed: " + e.getMessage());
        }
    }

    private void showEmptyState(boolean show) {
        if (mEmptyState != null) {
            mEmptyState.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (mRecyclerSessions != null) {
            mRecyclerSessions.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    private void updateSessionCount(int count) {
        if (mTextSessionCount != null) {
            String text = count == 1 ? "1 recording" : count + " recordings";
            mTextSessionCount.setText(text);
        }
    }
}
