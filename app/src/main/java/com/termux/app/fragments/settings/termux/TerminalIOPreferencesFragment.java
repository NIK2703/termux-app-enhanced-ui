package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.GridView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceDataStore;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.json.JSONArray;
import org.json.JSONException;

/**
 * The single "Input" screen. Handles both the S2 terminal I/O keys (soft keyboard,
 * text input) routed through {@link TerminalIOPreferencesDataStore} and the S3 extra
 * keys keys persisted to {@link TermuxAppSharedPreferences} (plus hide extra keys with
 * keyboard stored there as well).
 */
@Keep
public class TerminalIOPreferencesFragment extends TermuxPreferenceFragmentBase {

    private static final String LOG_TAG = "TerminalIOPrefsFragment";

    private TermuxAppSharedPreferences mPrefs;
    private ExtraKeysView mPreviewView;
    private final TermuxColorSchemeManager mColorSchemeManager = new TermuxColorSchemeManager();

    private String[][] mGrid;
    private int mRows = 2;
    private int mCols = 5;

    private androidx.appcompat.app.AlertDialog mSignalDialog;

    private final ExtraKeysView.IExtraKeysView mEditorClient = new ExtraKeysView.IExtraKeysView() {
        @Override
        public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
            int[] tag = (int[]) button.getTag();
            if (tag == null) return;
            int row = tag[0];
            int col = tag[1];
            if (row < 0 || row >= mRows || col < 0 || col >= mCols) return;
            openSignalPicker(row, col);
        }

        @Override
        public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, com.google.android.material.button.MaterialButton button) {
            return false;
        }
    };

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(new TerminalIOPreferencesDataStore(context));

        setPreferencesFromResource(R.xml.termux_terminal_io_preferences, rootKey);

        // Broadcast so TermuxActivity updates the text-input toggle button immediately.
        SwitchPreferenceCompat textInputPref = findPreference("text_input_enabled");
        if (textInputPref != null) {
            textInputPref.setOnPreferenceChangeListener((preference, newValue) -> {
                android.content.Intent intent = new android.content.Intent("com.termux.TEXT_INPUT_ENABLED_CHANGED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
                return true;
            });
        }

        // --- History (moved here from the deleted Sessions screen) ---
        configureHistorySlider("message_history_max", 10, 100);
        configureHistorySlider("directory_history_max", 10, 100);

        // --- Extra Keys Editor (merged here from the deleted ExtraKeysEditorPreferencesFragment) ---
        mPrefs = TermuxAppSharedPreferences.build(requireContext());

        loadCurrentExtraKeys();

        SeekBarPreference colsPref = findPreference("extra_keys_editor_columns");
        if (colsPref != null) {
            colsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int cols = (Integer) newValue;
                if (cols < 1) cols = 1;
                resizeGrid(mRows, cols);
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
                resizeGrid(rows, mCols);
                rebuildPreview();
                save();
                return true;
            });
        }

        SeekBarPreference heightPref = findPreference("terminal-toolbar-height");
        if (heightPref != null) {
            heightPref.setOnPreferenceChangeListener((preference, newValue) -> {
                rebuildPreview();
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.post(this::initPreview);
    }

    private void initPreview() {
        View root = getView();
        if (root == null) return;
        mPreviewView = root.findViewById(R.id.extra_keys_editor_preview);
        if (mPreviewView == null) return;
        mPreviewView.setExtraKeysViewClient(mEditorClient);
        rebuildPreview();
    }

    private void rebuildPreview() {
        if (mPreviewView == null) return;

        String json = buildJsonMatrix();
        String style = mPrefs.getExtraKeysStyle();
        if (style == null) style = "default";

        ExtraKeysInfo info;
        try {
            info = new ExtraKeysInfo(json, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            return;
        }

        mPreviewView.setButtonTextAllCaps(mPrefs.shouldExtraKeysTextBeAllCaps());
        mPreviewView.setSpecialButtonMode("hold".equals(mPrefs.getExtraKeysSpecialButtonMode())
                ? ExtraKeysView.SpecialButtonMode.HOLD
                : ExtraKeysView.SpecialButtonMode.STICKY);
        mPreviewView.setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS);

        // Use the same scheme-aware button colours the running app uses, so the preview
        // matches the user's light/dark terminal colour scheme instead of dark defaults.
        mColorSchemeManager.recompute();
        int textColor = mColorSchemeManager.getButtonText();
        int activeTextColor = textColor;
        int bgColor = mColorSchemeManager.getButtonBg();
        int activeBgColor = mColorSchemeManager.getButtonActiveBg();
        mPreviewView.setButtonColors(textColor, activeTextColor, bgColor, activeBgColor);

        float scale = 1.0f;
        try { scale = mPrefs.getTerminalToolbarHeightScaleFactor(); } catch (Exception ignored) {}
        float rowHeightPx = 37.5f * getResources().getDisplayMetrics().density * scale;
        mPreviewView.reload(info, rowHeightPx);

        // Tag each rendered button with its (row,col), and intercept taps so they ALWAYS open
        // the signal picker — including modifier keys (CTRL/ALT/SHIFT/FN), whose default behaviour
        // is to toggle an active state instead of firing the click callback. The editor never sends
        // keys, so we disable the built-in touch handling and route every tap to the picker.
        int childCount = mPreviewView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mPreviewView.getChildAt(i);
            if (child instanceof com.google.android.material.button.MaterialButton) {
                final int row = i / mCols;
                final int col = i % mCols;
                child.setTag(new int[]{row, col});
                child.setOnTouchListener(null);
                child.setOnClickListener(v -> openSignalPicker(row, col));
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

        String current = mPrefs.getExtraKeys();
        if (current == null || current.isEmpty()) {
            current = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
        }

        ExtraKeysInfo info;
        try {
            info = new ExtraKeysInfo(current, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            mRows = 2;
            mCols = 5;
            mGrid = new String[mRows][mCols];
            for (int r = 0; r < mRows; r++) {
                for (int c = 0; c < mCols; c++) {
                    mGrid[r][c] = "";
                }
            }
            return;
        }

        ExtraKeyButton[][] matrix = info.getMatrix();

        int loadedRows = matrix.length;
        if (loadedRows < 1) loadedRows = 1;
        if (loadedRows > 4) loadedRows = 4;

        int loadedCols = 0;
        for (ExtraKeyButton[] rowArr : matrix) {
            if (rowArr != null && rowArr.length > loadedCols) {
                loadedCols = rowArr.length;
            }
        }
        if (loadedCols < 1) loadedCols = 1;
        if (loadedCols > 10) loadedCols = 10;

        mGrid = new String[loadedRows][loadedCols];
        for (int r = 0; r < loadedRows; r++) {
            for (int c = 0; c < loadedCols; c++) {
                mGrid[r][c] = "";
            }
        }

        for (int r = 0; r < loadedRows; r++) {
            if (r < matrix.length && matrix[r] != null) {
                for (int c = 0; c < loadedCols; c++) {
                    if (c < matrix[r].length && matrix[r][c] != null) {
                        String key = matrix[r][c].getKey();
                        mGrid[r][c] = key == null ? "" : key;
                    }
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

    private String buildJsonMatrix() {
        JSONArray matrix = new JSONArray();
        for (int r = 0; r < mRows; r++) {
            JSONArray row = new JSONArray();
            for (int c = 0; c < mCols; c++) {
                String value = mGrid[r][c];
                if (value == null) value = "";
                row.put(value);
            }
            matrix.put(row);
        }
        return matrix.toString();
    }

    private void save() {
        String json = buildJsonMatrix();
        try {
            new JSONArray(json);
        } catch (JSONException e) {
            return;
        }
        mPrefs.setExtraKeys(json);
        TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
    }

    private void resizeGrid(int newRows, int newCols) {
        String[][] next = new String[newRows][newCols];
        for (int r = 0; r < newRows; r++) {
            for (int c = 0; c < newCols; c++) {
                if (r < mRows && c < mCols) {
                    next[r][c] = mGrid[r][c];
                } else {
                    next[r][c] = "";
                }
            }
        }
        mGrid = next;
        mRows = newRows;
        mCols = newCols;
    }

    private void openSignalPicker(int row, int col) {
        Context context = requireContext();
        String[] entries = getResources().getStringArray(R.array.extra_keys_editor_signal_entries);
        String[] values = getResources().getStringArray(R.array.extra_keys_editor_signal_values);

        View view = getLayoutInflater().inflate(R.layout.extra_keys_signal_grid, null);
        GridView grid = view.findViewById(R.id.signal_grid);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, entries);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, v, position, id) -> {
            if (position < 0 || position >= values.length) return;
            String value = values[position];
            if ("__CUSTOM__".equals(value)) {
                openCustomTextDialog(row, col);
            } else {
                mGrid[row][col] = value;
                rebuildPreview();
                save();
            }
            if (mSignalDialog != null) mSignalDialog.dismiss();
        });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_TermuxActivity_Dialog);
        builder.setTitle(R.string.extra_keys_editor_signal_dialog_title);
        builder.setView(view);
        builder.setNegativeButton(android.R.string.cancel, null);
        mSignalDialog = builder.show();
    }

    private void openCustomTextDialog(int row, int col) {
        Context context = requireContext();
        EditText editText = new EditText(context);
        editText.setText(mGrid[row][col]);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_TermuxActivity_Dialog);
        builder.setTitle(R.string.extra_keys_editor_custom_text_title);
        builder.setMessage(R.string.extra_keys_editor_custom_text_message);
        builder.setView(editText);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            mGrid[row][col] = editText.getText().toString();
            rebuildPreview();
            save();
        });
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.show();
    }

    private void configureHistorySlider(String key, int min, int max) {
        androidx.preference.SeekBarPreference slider = findPreference(key);
        if (slider == null) return;
        slider.setOnPreferenceChangeListener((preference, newValue) -> {
            int value = (Integer) newValue;
            if (value < min) {
                value = min;
                slider.setValue(value);
            }
            return true;
        });
    }

}

class TerminalIOPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxAppSharedPreferences mPreferences;

    private static TerminalIOPreferencesDataStore mInstance;

    public TerminalIOPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxAppSharedPreferences.build(context, true);
    }

    public static synchronized TerminalIOPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TerminalIOPreferencesDataStore(context);
        }
        return mInstance;
    }

    private android.content.SharedPreferences getTermuxPrefs() {
        return mContext.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (mPreferences == null) return;
        if (key == null) return;

        switch (key) {
            case "soft_keyboard_enabled":
                    mPreferences.setSoftKeyboardEnabled(value);
                break;
            case "soft_keyboard_enabled_only_if_no_hardware":
                mPreferences.setSoftKeyboardEnabledOnlyIfNoHardware(value);
                break;
            case "text_input_enabled":
                // Save to shared preferences (this controls whether text input is enabled in settings)
                getTermuxPrefs().edit().putBoolean("text_input_enabled", value).apply();
                break;
            case "text_input_append_enter":
                if (mPreferences != null) mPreferences.setTextInputAppendEnter(value);
                break;
            case "text_input_hide_on_send":
                if (mPreferences != null) mPreferences.setTextInputHideOnSend(value);
                break;
            case "per_directory_message_history":
                getTermuxPrefs().edit().putBoolean("per_directory_message_history", value).apply();
                break;
            default:
                break;
        }
    }

    @Override
    public void putInt(String key, int value) {
        if (key == null) return;
        switch (key) {
            case "suggestions_max_count":
                getTermuxPrefs().edit().putInt("suggestions_max_count", value).apply();
                break;
            case "message_history_max":
                getTermuxPrefs().edit().putInt("message_history_max", value).apply();
                break;
            case "directory_history_max":
                getTermuxPrefs().edit().putInt("directory_history_max", value).apply();
                break;
            case "terminal-toolbar-height":
                mPreferences.setTerminalToolbarHeightScaleFactor(((Integer) value) / 100f);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        if (mPreferences == null) return false;

        switch (key) {
            case "soft_keyboard_enabled":
                return mPreferences.isSoftKeyboardEnabled();
            case "soft_keyboard_enabled_only_if_no_hardware":
                return mPreferences.isSoftKeyboardEnabledOnlyIfNoHardware();
            case "text_input_enabled":
                return getTermuxPrefs().getBoolean("text_input_enabled", true);
            case "text_input_append_enter":
                return mPreferences != null && mPreferences.shouldTextInputAppendEnter();
            case "text_input_hide_on_send":
                return mPreferences != null && mPreferences.shouldTextInputHideOnSend();
            case "per_directory_message_history":
                return getTermuxPrefs().getBoolean("per_directory_message_history", false);
            default:
                return false;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        if (key == null) return defValue;
        switch (key) {
            case "suggestions_max_count":
                return getTermuxPrefs().getInt("suggestions_max_count", 4);
            case "message_history_max":
                return getTermuxPrefs().getInt("message_history_max", 20);
            case "directory_history_max":
                return getTermuxPrefs().getInt("directory_history_max", 20);
            case "terminal-toolbar-height":
                return mPreferences != null ? Math.round(mPreferences.getTerminalToolbarHeightScaleFactor() * 100f) : 100;
            default:
                return defValue;
        }
    }

    @Override
    public String getString(String key, String defValue) {
        if (key == null || mPreferences == null) return defValue;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                return mPreferences.getExtraKeys();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
                return mPreferences.getExtraKeysStyle();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE:
                return mPreferences.getExtraKeysSpecialButtonMode();
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS:
                return mPreferences.shouldExtraKeysTextBeAllCaps() ? "true" : "false";
            case "extra_keys_visibility":
                if (mPreferences == null) return "keyboard";
                if (!mPreferences.shouldShowTerminalToolbar()) return "never";
                return mPreferences.shouldHideExtraKeysWithKeyboard() ? "keyboard" : "always";
            default:
                return defValue;
        }
    }

    @Override
    public void putString(String key, String value) {
        if (key == null || mPreferences == null) return;
        switch (key) {
            case TermuxPropertyConstants.KEY_EXTRA_KEYS:
                mPreferences.setExtraKeys(value);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE:
                mPreferences.setExtraKeysStyle(value);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS:
                mPreferences.setExtraKeysTextAllCaps("true".equals(value));
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            case TermuxPropertyConstants.KEY_EXTRA_KEYS_SPECIAL_BUTTON_MODE:
                mPreferences.setExtraKeysSpecialButtonMode(value);
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            case "extra_keys_visibility":
                if ("never".equals(value)) {
                    mPreferences.setShowTerminalToolbar(false);
                } else if ("keyboard".equals(value)) {
                    mPreferences.setShowTerminalToolbar(true);
                    mPreferences.setHideExtraKeysWithKeyboard(true);
                } else { // "always"
                    mPreferences.setShowTerminalToolbar(true);
                    mPreferences.setHideExtraKeysWithKeyboard(false);
                }
                TermuxActivity.updateTermuxActivityStyling(mContext, true);
                break;
            default:
                break;
        }
    }

}
