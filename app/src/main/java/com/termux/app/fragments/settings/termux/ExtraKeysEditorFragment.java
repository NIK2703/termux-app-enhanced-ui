package com.termux.app.fragments.settings.termux;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.BindingTokenizer;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.theme.ThemeUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

@Keep
public class ExtraKeysEditorFragment extends TermuxPreferenceFragmentBase {

    private static final int DIR_TAP = 0;
    private static final int DIR_UP = 1;
    private static final int DIR_DOWN = 2;
    private static final int DIR_LEFT = 3;
    private static final int DIR_RIGHT = 4;
    private static final int MAX_ROWS = 4;
    private static final int MAX_COLS = 10;
    private static final String TAG = "ExtraKeysEditor";

    private static class KeyCell {
        String tap = "";
        String swipeUp = "";
        String swipeDown = "";
        String swipeLeft = "";
        String swipeRight = "";
    }

    private TermuxAppSharedPreferences mPrefs;
    private ExtraKeysView mPreviewView;
    private KeyCell[][] mGrid;
    private int mRows = 2;
    private int mCols = 5;
    private ExtraKeysConstants.ExtraKeyDisplayMap mDisplayMap;

    private int visibleRowStart() { return MAX_ROWS - mRows; }
    private int visibleColStart() { return MAX_COLS - mCols; }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.extra_keys_editor_preferences, rootKey);

        mPrefs = TermuxAppSharedPreferences.build(requireContext());
        loadCurrentExtraKeys();

        SeekBarPreference colsPref = findPreference("extra_keys_editor_columns");
        if (colsPref != null) {
            colsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int cols = (Integer) newValue;
                if (cols < 1) cols = 1;
                mCols = cols;
                rebuildPreview();
                save();
                return true;
            });
        }

        SeekBarPreference rowsPref = findPreference("extra_keys_editor_rows");
        if (rowsPref != null) {
            rowsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int rows = (Integer) newValue;
                if (rows < 1) rows = 1;
                mRows = rows;
                rebuildPreview();
                save();
                return true;
            });
        }

        SeekBarPreference heightPref = findPreference("terminal-toolbar-height");
        if (heightPref != null) {
            // Sync to actual value from TermuxAppSharedPreferences
            float scaleFactor = mPrefs.getTerminalToolbarHeightScaleFactor();
            heightPref.setValue(Math.round(scaleFactor * 100f));
            heightPref.setPersistent(false); // we handle persistence ourselves

            heightPref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setTerminalToolbarHeightScaleFactor(((Integer) newValue) / 100f);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true; // true = accept the change in SeekBarPreference (persistent=false prevents disk save, mPrefs call above writes the actual value)
            });
        }

        SeekBarPreference cornerPref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_CORNER_RADIUS);
        if (cornerPref != null) {
            cornerPref.setPersistent(false);
            cornerPref.setValue(mPrefs.getExtraKeysCornerRadius());
            cornerPref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setExtraKeysCornerRadius((Integer) newValue);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), false);
                return true;
            });
        }

        SeekBarPreference marginPref = findPreference("extra-keys-button-margin");
        if (marginPref != null) {
            marginPref.setPersistent(false);
            marginPref.setValue(Math.round(mPrefs.getExtraKeysButtonMargin() * 10f));
            marginPref.setOnPreferenceChangeListener((preference, newValue) -> {
                float margin = ((Integer) newValue) / 10f;
                mPrefs.setExtraKeysButtonMargin(margin);
                if (mPreviewView != null) mPreviewView.setButtonMargins(margin);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        ListPreference stylePref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE);
        if (stylePref != null) {
            stylePref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setExtraKeysStyle((String) newValue);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        SwitchPreferenceCompat capsPref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS);
        if (capsPref != null) {
            capsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setExtraKeysTextAllCaps((Boolean) newValue);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        SwitchPreferenceCompat dynFontPref = findPreference("extra-keys-dynamic-font-size");
        if (dynFontPref != null) {
            dynFontPref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setExtraKeysDynamicFontSize((Boolean) newValue);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        SeekBarPreference fontSizePref = findPreference("extra-keys-font-size");
        if (fontSizePref != null) {
            fontSizePref.setPersistent(false);
            fontSizePref.setValue(mPrefs.getExtraKeysFontSize());
            fontSizePref.setOnPreferenceChangeListener((preference, newValue) -> {
                int size = (Integer) newValue;
                mPrefs.setExtraKeysFontSize(size);
                if (mPreviewView != null) mPreviewView.setBaseFontSizeSp(size);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getListView() != null) {
            getListView().post(() -> {
                if (mPreviewView == null) initPreview();
            });
        }

        getChildFragmentManager().setFragmentResultListener(
            SignalPickerDialogFragment.REQUEST_KEY,
            getViewLifecycleOwner(),
            (requestKey, result) -> handleSignalPickerResult(result)
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPreviewView == null) initPreview();
    }

    private void initPreview() {
        View root = getView();
        if (root == null) return;
        mPreviewView = root.findViewById(R.id.extra_keys_editor_preview);
        if (mPreviewView == null) return;
        mPreviewView.setEditorGestureListener(mEditorGestureListener);
        rebuildPreview();
    }

    private final ExtraKeysView.EditorGestureListener mEditorGestureListener =
        new ExtraKeysView.EditorGestureListener() {
            @Override
            public void onKeyTap(View button, int row, int col) {
                openSignalPicker(row, col, DIR_TAP);
            }

            @Override
            public void onKeySwipe(View button, int row, int col,
                                   ExtraKeysView.SwipeDirection direction) {
                int dir;
                switch (direction) {
                    case UP: dir = DIR_UP; break;
                    case DOWN: dir = DIR_DOWN; break;
                    case LEFT: dir = DIR_LEFT; break;
                    case RIGHT: dir = DIR_RIGHT; break;
                    default: return;
                }
                openSignalPicker(row, col, dir);
            }
        };

    private void rebuildPreview() {
        if (mPreviewView == null) return;

        String style = mPrefs.getExtraKeysStyle();
        if (style == null) style = "default";
        mDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(style);

        String json;
        try {
            json = buildJsonMatrix();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build extra-keys JSON matrix", e);
            return;
        }

        ExtraKeysInfo info;
        try {
            info = new ExtraKeysInfo(json, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse extra-keys JSON", e);
            return;
        }

        mPreviewView.setButtonTextAllCaps(mPrefs.shouldExtraKeysTextBeAllCaps());
        mPreviewView.setDynamicFontSize(mPrefs.isExtraKeysDynamicFontSizeEnabled(requireContext()));
        mPreviewView.setBaseFontSizeSp(mPrefs.getExtraKeysFontSize());
        mPreviewView.setButtonMargins(mPrefs.getExtraKeysButtonMargin());
        mPreviewView.setSpecialButtonMode("hold".equals(mPrefs.getExtraKeysSpecialButtonMode())
            ? ExtraKeysView.SpecialButtonMode.HOLD
            : ExtraKeysView.SpecialButtonMode.STICKY);

        // Reload the terminal color scheme for the current night mode.
        // This static singleton is otherwise only updated by TermuxActivity, so
        // we must set it here to get correct preview colors after a theme switch.
        boolean isNight = ThemeUtils.isNightModeEnabled(requireContext());
        Properties lightScheme = null;
        if (!isNight) {
            lightScheme = new Properties();
            String[] keys = getResources().getStringArray(R.array.light_terminal_color_scheme_keys);
            String[] values = getResources().getStringArray(R.array.light_terminal_color_scheme_values);
            int len = Math.min(keys.length, values.length);
            for (int i = 0; i < len; i++) {
                lightScheme.setProperty(keys[i], values[i]);
            }
        }
        ColorSchemeUtils.ensureColorSchemeForTheme(isNight, lightScheme);

        TermuxColorSchemeManager cm = new TermuxColorSchemeManager();
        cm.recompute(mPrefs);

        int edgeGray = cm.isSchemeLight() ? 0xFF555555 : 0xFFAAAAAA;
        mPreviewView.setEditorEdgeColor(edgeGray);

        mPreviewView.setBackgroundColor(cm.getSchemeBackground());

        mPreviewView.setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS);

        float scale = 1.0f;
        try { scale = mPrefs.getTerminalToolbarHeightScaleFactor(); } catch (Exception e) { Log.e(TAG, "Failed to get toolbar height scale", e); }
        float rowHeightPx = 37.5f * getResources().getDisplayMetrics().density * scale;
        mPreviewView.reload(info, rowHeightPx);

        int schemeBg = cm.getSchemeBackground();
        int buttonBg = TermuxColorSchemeManager.compositeColors(schemeBg, cm.getButtonBg());
        int buttonActiveBg = TermuxColorSchemeManager.compositeColors(schemeBg, cm.getButtonActiveBg());
        mPreviewView.setButtonColors(cm.getButtonText(), cm.getButtonText(),
            buttonBg, buttonActiveBg);

        int childCount = mPreviewView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mPreviewView.getChildAt(i);
            if (child instanceof com.google.android.material.button.MaterialButton) {
                int row = i / mCols;
                int col = i % mCols;
                KeyCell cell = mGrid[visibleRowStart() + row][visibleColStart() + col];
                int flags = (cell.swipeUp.isEmpty() ? 0 : 1)
                          | (cell.swipeDown.isEmpty() ? 0 : 2)
                          | (cell.swipeLeft.isEmpty() ? 0 : 4)
                          | (cell.swipeRight.isEmpty() ? 0 : 8);
                child.setTag(new int[]{row, col, flags});
            }
        }

        ViewGroup.LayoutParams lp = mPreviewView.getLayoutParams();
        if (lp != null) {
            lp.height = Math.round(rowHeightPx * mRows);
            mPreviewView.setLayoutParams(lp);
        }
        mPreviewView.requestLayout();
    }

    private void loadCurrentExtraKeys() {
        String style = mPrefs.getExtraKeysStyle();
        if (style == null) style = "default";
        mDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(style);

        // Always allocate full-size grid so data is never lost on resize
        mGrid = new KeyCell[MAX_ROWS][MAX_COLS];
        for (int r = 0; r < MAX_ROWS; r++)
            for (int c = 0; c < MAX_COLS; c++)
                mGrid[r][c] = new KeyCell();

        String current = mPrefs.getExtraKeys();
        if (current == null || current.isEmpty()) {
            current = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
        }

        ExtraKeysInfo info;
        try {
            info = new ExtraKeysInfo(current, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse extra-keys JSON", e);
            mRows = 2; mCols = 5;
            SeekBarPreference colsPref = findPreference("extra_keys_editor_columns");
            if (colsPref != null) colsPref.setValue(mCols);
            SeekBarPreference rowsPref = findPreference("extra_keys_editor_rows");
            if (rowsPref != null) rowsPref.setValue(mRows);
            return;
        }

        ExtraKeyButton[][] matrix = info.getMatrix();

        int loadedRows = Math.max(1, Math.min(matrix.length, 4));
        int loadedCols = 1;
        for (ExtraKeyButton[] rowArr : matrix) {
            if (rowArr != null && rowArr.length > loadedCols) loadedCols = rowArr.length;
        }
        loadedCols = Math.max(1, Math.min(loadedCols, 10));

        // Load data into bottom-right corner of the fixed grid
        int gridStartRow = MAX_ROWS - loadedRows;
        int gridStartCol = MAX_COLS - loadedCols;
        for (int r = 0; r < loadedRows; r++) {
            for (int c = 0; c < loadedCols; c++) {
                KeyCell cell = mGrid[gridStartRow + r][gridStartCol + c];
                if (r < matrix.length && matrix[r] != null && c < matrix[r].length && matrix[r][c] != null) {
                    ExtraKeyButton btn = matrix[r][c];
                    cell.tap = btn.getKey() != null ? btn.getKey() : "";
                    cell.swipeUp = btn.getSwipeUp() != null ? btn.getSwipeUp().getKey() : "";
                    cell.swipeDown = btn.getSwipeDown() != null ? btn.getSwipeDown().getKey() : "";
                    cell.swipeLeft = btn.getSwipeLeft() != null ? btn.getSwipeLeft().getKey() : "";
                    cell.swipeRight = btn.getSwipeRight() != null ? btn.getSwipeRight().getKey() : "";
                }
            }
        }

        mRows = loadedRows;
        mCols = loadedCols;

        SeekBarPreference colsPref = findPreference("extra_keys_editor_columns");
        if (colsPref != null) colsPref.setValue(mCols);
        SeekBarPreference rowsPref = findPreference("extra_keys_editor_rows");
        if (rowsPref != null) rowsPref.setValue(mRows);
    }

    private String buildJsonMatrix() throws JSONException {
        JSONArray matrix = new JSONArray();
        int vr = visibleRowStart();
        int vc = visibleColStart();
        for (int r = 0; r < mRows; r++) {
            JSONArray row = new JSONArray();
            for (int c = 0; c < mCols; c++) {
                KeyCell cell = mGrid[vr + r][vc + c];
                if (cell.tap.isEmpty() && cell.swipeUp.isEmpty() && cell.swipeDown.isEmpty()
                    && cell.swipeLeft.isEmpty() && cell.swipeRight.isEmpty()) {
                    row.put("");
                    continue;
                }

                JSONObject obj = new JSONObject();
                if (!cell.tap.isEmpty()) {
                    putSignal(obj, ExtraKeyButton.KEY_KEY_NAME, cell.tap);
                }
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_UP, cell.swipeUp);
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_DOWN, cell.swipeDown);
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_LEFT, cell.swipeLeft);
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_RIGHT, cell.swipeRight);

                if (!cell.swipeUp.isEmpty()) {
                    putSwipe(obj, ExtraKeyButton.KEY_POPUP, cell.swipeUp);
                }

                row.put(obj);
            }
            matrix.put(row);
        }
        return matrix.toString();
    }

    private void putSignal(JSONObject obj, String key, String value) throws JSONException {
        if (value == null || value.isEmpty()) return;
        if (value.contains(" ")) {
            obj.put(ExtraKeyButton.KEY_MACRO, value);
            obj.put(ExtraKeyButton.KEY_DISPLAY_NAME, computeDisplay(value));
        } else {
            obj.put(key, value);
        }
    }

    private void putSwipe(JSONObject obj, String jsonKey, String value) throws JSONException {
        if (value == null || value.isEmpty()) return;
        if (value.contains(" ")) {
            JSONObject swipeObj = new JSONObject();
            swipeObj.put(ExtraKeyButton.KEY_MACRO, value);
            swipeObj.put(ExtraKeyButton.KEY_DISPLAY_NAME, computeDisplay(value));
            obj.put(jsonKey, swipeObj);
        } else {
            obj.put(jsonKey, value);
        }
    }

    private String computeDisplay(String macroValue) {
        if (macroValue == null || macroValue.isEmpty()) return "";
        return Arrays.stream(macroValue.split(" "))
            .map(key -> {
                if (BindingTokenizer.isDelay(key)) {
                    return "⏱" + BindingTokenizer.parseDelayMs(key) + "ms";
                }
                if (mDisplayMap == null) return key;
                String d = mDisplayMap.get(key);
                return d != null ? d : key;
            })
            .collect(Collectors.joining("+"));
    }

    private void openSignalPicker(int row, int col, int direction) {
        KeyCell cell = mGrid[visibleRowStart() + row][visibleColStart() + col];
        String currentValue;
        SignalPickerDialogFragment.BindTarget target;

        switch (direction) {
            case DIR_TAP:
                currentValue = cell.tap;
                target = SignalPickerDialogFragment.BindTarget.TAP;
                break;
            case DIR_UP:
                currentValue = cell.swipeUp;
                target = SignalPickerDialogFragment.BindTarget.SWIPE_UP;
                break;
            case DIR_DOWN:
                currentValue = cell.swipeDown;
                target = SignalPickerDialogFragment.BindTarget.SWIPE_DOWN;
                break;
            case DIR_LEFT:
                currentValue = cell.swipeLeft;
                target = SignalPickerDialogFragment.BindTarget.SWIPE_LEFT;
                break;
            case DIR_RIGHT:
                currentValue = cell.swipeRight;
                target = SignalPickerDialogFragment.BindTarget.SWIPE_RIGHT;
                break;
            default:
                return;
        }

        ArrayList<String> currentSignals = new ArrayList<>();
        if (currentValue != null && !currentValue.isEmpty()) {
            currentSignals.addAll(Arrays.asList(currentValue.split(" ")));
            currentSignals.removeIf(String::isEmpty);
        }

        SignalPickerDialogFragment fragment = SignalPickerDialogFragment.newInstance(row, col, target, currentSignals);
        fragment.show(getChildFragmentManager(), "signal_picker");
    }

    private void handleSignalPickerResult(@NonNull Bundle result) {
        int row = result.getInt(SignalPickerDialogFragment.RESULT_ROW, -1);
        int col = result.getInt(SignalPickerDialogFragment.RESULT_COL, -1);
        String targetStr = result.getString(SignalPickerDialogFragment.RESULT_TARGET, null);
        ArrayList<String> signals = result.getStringArrayList(SignalPickerDialogFragment.RESULT_SIGNALS);

        if (row < 0 || col < 0 || row >= mRows || col >= mCols) return;
        if (targetStr == null || signals == null) return;

        SignalPickerDialogFragment.BindTarget target;
        try {
            target = SignalPickerDialogFragment.BindTarget.valueOf(targetStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        String value;
        if (signals.isEmpty()) {
            value = "";
        } else if (signals.size() == 1) {
            value = signals.get(0);
        } else {
            value = TextUtils.join(" ", signals);
        }

        KeyCell cell = mGrid[visibleRowStart() + row][visibleColStart() + col];
        switch (target) {
            case TAP: cell.tap = value; break;
            case SWIPE_UP: cell.swipeUp = value; break;
            case SWIPE_DOWN: cell.swipeDown = value; break;
            case SWIPE_LEFT: cell.swipeLeft = value; break;
            case SWIPE_RIGHT: cell.swipeRight = value; break;
        }

        rebuildPreview();
        save();
    }

    private void save() {
        String json;
        try {
            json = buildJsonMatrix();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build extra-keys JSON matrix in save", e);
            return;
        }
        try {
            new JSONArray(json);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to validate extra-keys JSON in save", e);
            return;
        }
        mPrefs.setExtraKeys(json);
        TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
    }

}
