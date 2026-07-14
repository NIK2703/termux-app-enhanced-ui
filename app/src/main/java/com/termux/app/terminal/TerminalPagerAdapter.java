package com.termux.app.terminal;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * RecyclerView adapter backing the horizontal session pager (ViewPager2).
 *
 * <p>Each page is its own {@link TerminalView} bound to exactly one {@link TerminalSession}.
 * Because every session gets a dedicated view, a horizontal swipe reveals the neighbouring
 * session live (the ViewPager2 keeps both pages attached during the drag), which is what gives
 * the smooth "intermediate" paging feel between adjacent terminals.
 *
 * <p>All pages share the single {@link TermuxTerminalViewClient} instance owned by the activity,
 * so input, IME, gestures and theming behave identically to the previous single-view setup. The
 * activity keeps its {@code mTerminalView} field pointed at the <em>currently selected</em> page's
 * TerminalView (updated in {@code onPageSelected}), so the rest of the codebase that calls
 * {@link TermuxActivity#getTerminalView()} keeps working unchanged.
 */
public final class TerminalPagerAdapter extends RecyclerView.Adapter<TerminalPagerAdapter.TerminalPageViewHolder> {

    private final TermuxActivity mActivity;
    private final TermuxTerminalViewClient mViewClient;
    private List<TermuxSession> mSessions;

    /** Maps a page position to its currently-bound TerminalView.
     *  Kept in sync in onBindViewHolder / onViewRecycled so the activity can resolve the
     *  active page's view reliably even when RecyclerView.findViewHolderForAdapterPosition()
     *  returns null mid-swipe (ViewHolder not yet laid out). This is what makes the
     *  mTerminalView pointer and extra-keys target track the visible page instead of lagging
     *  a frame behind and routing input to the wrong session. */
    private final Map<Integer, TerminalView> mAttachedViews = new HashMap<>();

    public TerminalPagerAdapter(@NonNull TermuxActivity activity,
                                @NonNull TermuxTerminalViewClient viewClient,
                                @NonNull List<TermuxSession> sessions) {
        this.mActivity = activity;
        this.mViewClient = viewClient;
        this.mSessions = sessions;
        setHasStableIds(true);
    }

    /**
     * Sync the adapter with the live service session list, using incremental
     * notifications ({@link #notifyItemRangeInserted}/{@link #notifyItemRangeRemoved})
     * instead of {@link #notifyDataSetChanged()} so that RecyclerView's internals
     * (GapWorker prefetch, ViewFlinger) never see stale positions after a remove
     * operation.  A full {@link #notifyDataSetChanged()} invalidates EVERY pending
     * ViewHolder and any GapWorker Handler callback that fires after that will crash
     * with {@code IndexOutOfBoundsException} ("Invalid item position" / "Inconsistency detected").
     * Incremental notifications properly update the state and GapWorker's cached tasks.
     */
    public void syncWithServiceList(@NonNull List<TermuxSession> serviceSessions) {
        int oldSize = mSessions.size();
        int newSize = serviceSessions.size();

        if (newSize > oldSize) {
            // One or more items appended — new tab.
            mSessions = new java.util.ArrayList<>(serviceSessions);
            notifyItemRangeInserted(oldSize, newSize - oldSize);
        } else if (newSize < oldSize) {
            // One or more items removed — tab closed.
            // Find the first mismatch by reference equality to determine the removed index.
            int removedCount = oldSize - newSize;
            for (int i = 0; i < newSize; i++) {
                if (mSessions.get(i) != serviceSessions.get(i)) {
                    mSessions = new java.util.ArrayList<>(serviceSessions);
                    notifyItemRangeRemoved(i, removedCount);
                    return;
                }
            }
            // No mismatch found in the shared portion → last item(s) were removed.
            mSessions = new java.util.ArrayList<>(serviceSessions);
            notifyItemRangeRemoved(oldSize - removedCount, removedCount);
        }
        // Same size — no structural change; tab titles etc. handled by updateTabs().
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= mSessions.size()) return RecyclerView.NO_ID;
        TerminalSession session = mSessions.get(position).getTerminalSession();
        return session == null ? RecyclerView.NO_ID : (long) session.mHandle.hashCode();
    }

    @NonNull
    @Override
    public TerminalPageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View page = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_terminal_page, parent, false);
        return new TerminalPageViewHolder(page);
    }

    @Override
    public void onBindViewHolder(@NonNull TerminalPageViewHolder holder, int position) {
        TermuxSession termuxSession = mSessions.get(position);
        TerminalSession session = termuxSession.getTerminalSession();
        if (session == null) return;

        TerminalView terminalView = holder.mTerminalView;
        // Bind the shared client so input/IME/gestures route here.
        terminalView.setTerminalViewClient(mViewClient);

        // Attach the focus-change listener that drives the soft keyboard to THIS page's view.
        // Done here (not in TermuxTerminalViewClient.setSoftKeyboardState) because the shared
        // active view may be null during early lifecycle, while the page view is always non-null here.
        mViewClient.registerTerminalViewFocusListener(terminalView);

        // Register the terminal context menu on THIS page's TerminalView (not the activity root).
        // If it were on the root, a long-press on the sibling text-input panel would bubble up to
        // the root's context menu and show the terminal menu over the input field instead of
        // letting the EditText select a word (regression). With it here, only presses on an actual
        // terminal surface open the terminal menu; the input panel keeps its normal word-select.
        mActivity.registerForContextMenu(terminalView);

        // Mirror the global terminal view configuration onto this page.
        terminalView.setTextSize(mActivity.getPreferences().getFontSize());
        terminalView.setKeepScreenOn(mActivity.getPreferences().shouldKeepScreenOn());

        // Attach the session. attachSession() reuses the session's existing TerminalEmulator
        // (history/scrollback live inside the session, not the view), so switching pages never
        // loses transcript and re-binding is cheap.
        terminalView.attachSession(session);

        // Re-apply the active color scheme + typeface so the freshly bound page matches the
        // current theme (covers both initial bind and re-bind after the adapter was rebuilt).
        mActivity.getTermuxTerminalSessionClient().checkForFontAndColorsForView(terminalView);

        // Remember this position->view mapping so the activity can resolve the active page's
        // view even when RecyclerView.findViewHolderForAdapterPosition() is still null mid-swipe.
        mAttachedViews.put(position, terminalView);
    }

    /** @return the TerminalView currently bound to {@code position}, or null if not bound. */
    @androidx.annotation.Nullable
    public TerminalView getAttachedView(int position) {
        return mAttachedViews.get(position);
    }

    @Override
    public void onViewRecycled(@NonNull TerminalPageViewHolder holder) {
        super.onViewRecycled(holder);
        // Detach the emulator but keep the session alive. The view may be reused for a different
        // position; re-attaching on the next bind restores the correct emulator from the session.
        holder.mTerminalView.attachSession(null);
        // Drop the per-page context menu registration so a recycled page leaves no dangling
        // listener and only the active page serves the terminal menu.
        mActivity.unregisterForContextMenu(holder.mTerminalView);
        // Drop any stale position->view entry so getAttachedView() never returns a
        // recycled (detached) view for a position that has moved on.
        Integer pos = null;
        for (Map.Entry<Integer, TerminalView> e : mAttachedViews.entrySet()) {
            if (e.getValue() == holder.mTerminalView) { pos = e.getKey(); break; }
        }
        if (pos != null) mAttachedViews.remove(pos);
    }

    @Override
    public int getItemCount() {
        return mSessions.size();
    }

    public static final class TerminalPageViewHolder extends RecyclerView.ViewHolder {
        public final TerminalView mTerminalView;

        TerminalPageViewHolder(@NonNull View itemView) {
            super(itemView);
            mTerminalView = itemView.findViewById(R.id.terminal_view_page);
        }
    }
}
