# Termux App — Inventory of All Settings

This document is a structured inventory of **every setting** in the Termux app (fork `termux-app-ui-improve`), organized by **screen** (top-level section) and **subsection** (categories / headers within a screen). It is a reference only — no changes are applied to the project.

Settings are entered via **SettingsActivity → RootPreferencesFragment** (`res/xml/root_preferences.xml`). Each top-level row opens a preferences fragment. The four plugin rows (Termux:API, Float, Tasker, Widget) are visible **only if the plugin app is installed**; `donate` is hidden on Google Play builds.

---

## 1. Root / Settings Home
*File: `root_preferences.xml` · Fragment: `RootPreferencesFragment`*

No categories. Each item is a navigation entry (opens a fragment) or an action (opens an activity/URL).

- **Appearance** → `AppearancePreferencesFragment`
- **Termux** → `TermuxPreferencesFragment`
- **Termux:API** → `TermuxAPIPreferencesFragment` *(hidden unless Termux:API installed)*
- **Termux:Float** → `TermuxFloatPreferencesFragment` *(hidden unless installed)*
- **Termux:Tasker** → `TermuxTaskerPreferencesFragment` *(hidden unless installed)*
- **Termux:Widget** → `TermuxWidgetPreferencesFragment` *(hidden unless installed)*
- **About** → opens `ReportActivity` (app + device info markdown report)
- **Donate** → opens external donate URL *(hidden on Play builds)*

---

## 2. Appearance
*File: `termux_appearance_preferences.xml` · Fragment: `AppearancePreferencesFragment`*

### 2.1 Theme
- `theme_mode` — **App Theme** — `ListPreference` — Dark / Light / System default (default: System). Persisted to `termux.properties` (`night_mode`); recreates Settings activity.
- `locale_override` — **App language** — `ListPreference` — System / English (default: System). Applied via `AppCompatDelegate.setApplicationLocales()`.
- `hide_extra_keys_with_keyboard` — **Hide extra keys with keyboard** — `CheckBoxPreference` — Auto-hide extra-key bar with soft keyboard (default: on).
- `color_scheme_light` — **Terminal Color Scheme (Light Theme)** — `Preference` (dialog) — Opens color-scheme picker for light mode; applies live.
- `color_scheme_dark` — **Terminal Color Scheme (Dark Theme)** — `Preference` (dialog) — Opens color-scheme picker for dark mode; applies live.

### 2.2 Panel Transparency
- `button_bg_inactive_alpha` — **Inactive element transparency** — `SeekBarPreference` — Range 0–10, default 5.
- `button_bg_active_alpha` — **Active element transparency** — `SeekBarPreference` — Range 10–20, default 12.

### 2.3 Text Input
- `suggestions_max_count` — **Max suggestions** — `SeekBarPreference` — Auto-complete suggestions from history, range 1–10, default 4.

---

## 3. Termux (main)
*File: `termux_preferences.xml` · Fragment: `TermuxPreferencesFragment`*

### 3.1 Sub-screen Links (no category)
- → **Debugging** → `termux.DebuggingPreferencesFragment`
- → **Terminal I/O** → `termux.TerminalIOPreferencesFragment`
- → **Extra keys** → `termux.ExtraKeysPreferencesFragment`
- → **Terminal View** → `termux.TerminalViewPreferencesFragment`

### 3.2 Message History
- `message_history_max` — **Max remembered messages** — `SeekBarPreference` — Range 10–100, default 20.
- `per_directory_message_history` — **Per-directory message history** — `SwitchPreferenceCompat` — Separate history per working dir (default: off).

### 3.3 Text Input
- `text_input_append_enter` — **Append Enter on send** — `SwitchPreferenceCompat` — Press Enter after sending (default: on).
- `text_input_hide_on_send` — **Hide input panel after send** — `SwitchPreferenceCompat` — Close panel after send (default: on).

### 3.4 Directory History
- `directory_history_max` — **Max remembered directories** — `SeekBarPreference` — Range 10–100, default 20.
- `restore_sessions` — **Restore tabs on launch** — `SwitchPreferenceCompat` — Reopen tabs + dirs at start (default: on).

### 3.5 Session Tabs
- `swipe_rightmost_new_tab` — **Swipe rightmost tab for new session** — `SwitchPreferenceCompat` — Swipe last tab to open new session (default: off).

### 3.6 Screen Orientation
- `screen_orientation` — **Screen orientation** — `ListPreference` — Follow sensor / Portrait (follow sensor) / Landscape (follow sensor) / Portrait / Landscape (default: sensor on tablet, portrait on phone).

### 3.7 Termux Data Backup / Restore
- `backup_container` — **Backup Termux Data** — `Preference` (action) — SAF create `.tar.gz` backup.
- `restore_container` — **Restore Termux Data** — `Preference` (action) — SAF open `.tar.gz` to restore.

---

## 4. Termux → Debugging
*File: `termux_debugging_preferences.xml` · Fragment: `termux.DebuggingPreferencesFragment`*

### 4.1 Logging
- `log_level` — **Log Level** — `ListPreference` — Off / Normal / Debug / Verbose (default: Normal "1").
- `terminal_view_key_logging_enabled` — **Terminal View Key Logging** — `SwitchPreferenceCompat` — Log each key press (default: off).
- `plugin_error_notifications_enabled` — **Plugin Error Notifications** — `SwitchPreferenceCompat` — Flash/notify on plugin errors (default: on).
- `crash_report_notifications_enabled` — **Crash Report Notifications** — `SwitchPreferenceCompat` — Notify on crash report (default: on).

---

## 5. Termux → Terminal I/O
*File: `termux_terminal_io_preferences.xml` · Fragment: `termux.TerminalIOPreferencesFragment`*

### 5.1 Keyboard
- `soft_keyboard_enabled` — **Soft Keyboard Enabled** — `SwitchPreferenceCompat` — Master toggle (default: on).
- `soft_keyboard_enabled_only_if_no_hardware` — **Soft Keyboard Only If No Hardware** — `SwitchPreferenceCompat` — Hide soft keyboard when hardware present (default: off).
- `text_input_enabled` — **Show text input field** — `SwitchPreferenceCompat` — Show input field above extra keys (default: on).
- `tab_panel_position` — **Tab panel position** — `ListPreference` — Top / Bottom (default: top).
- `tab_height_mode` — **Tab height** — `ListPreference` — Single line / Two lines (default: single).

---

## 6. Termux → Extra Keys
*File: `termux_extra_keys_preferences.xml` · Fragment: `termux.ExtraKeysPreferencesFragment`*

### 6.1 Extra keys
- `extra-keys` — **Keys layout** — `EditTextPreference` — JSON matrix of keys/popups (default: 2-row ESC/CTRL/ALT/etc. matrix).
- `extra-keys-style` — **Keys style** — `ListPreference` — Default / Arrows only / Arrows all / All / None (default: default).
- `extra-keys-text-all-caps` — **All caps text** — `SwitchPreferenceCompat` — Uppercase labels (default: on).
- `extra-keys-special-button-mode` — **Special keys behaviour** — `ListPreference` — Sticky (toggle/lock) / Hold (while pressed) (default: sticky).

---

## 7. Termux → Terminal View
*File: `termux_terminal_view_preferences.xml` · Fragment: `termux.TerminalViewPreferencesFragment`*

### 7.1 View
- `terminal_margin_adjustment` — **Terminal Margin Adjustment** — `SwitchPreferenceCompat` — Recalc margin to avoid keyboard covering view (default: on).

---

## 8. Termux:API
*File: `termux_api_preferences.xml` · Fragment: `TermuxAPIPreferencesFragment`*

### 8.1 Sub-screen Link
- → **Debugging** → `termux_api.DebuggingPreferencesFragment`

### 8.2 Debugging → Logging
- `log_level` — **Log Level** — `ListPreference` — Off / Normal / Debug / Verbose (default: Normal "1").

---

## 9. Termux:Float
*File: `termux_float_preferences.xml` · Fragment: `TermuxFloatPreferencesFragment`*

### 9.1 Sub-screen Link
- → **Debugging** → `termux_float.DebuggingPreferencesFragment`

### 9.2 Debugging → Logging
- `log_level` — **Log Level** — `ListPreference` — Off / Normal / Debug / Verbose (default: Normal "1").
- `terminal_view_key_logging_enabled` — **Terminal View Key Logging** — `SwitchPreferenceCompat` — Log each key press (default: off).

---

## 10. Termux:Tasker
*File: `termux_tasker_preferences.xml` · Fragment: `TermuxTaskerPreferencesFragment`*

### 10.1 Sub-screen Link
- → **Debugging** → `termux_tasker.DebuggingPreferencesFragment`

### 10.2 Debugging → Logging
- `log_level` — **Log Level** — `ListPreference` — Off / Normal / Debug / Verbose (default: Normal "1").

---

## 11. Termux:Widget
*File: `termux_widget_preferences.xml` · Fragment: `TermuxWidgetPreferencesFragment`*

### 11.1 Sub-screen Link
- → **Debugging** → `termux_widget.DebuggingPreferencesFragment`

### 11.2 Debugging → Logging
- `log_level` — **Log Level** — `ListPreference` — Off / Normal / Debug / Verbose (default: Normal "1").

---

## Summary

| # | Screen | Subsections (categories) | Setting count |
|---|--------|--------------------------|---------------|
| 1 | Root / Settings Home | — (nav entries) | 8 links |
| 2 | Appearance | Theme · Panel Transparency · Text Input | 8 |
| 3 | Termux (main) | Sub-links · Message History · Text Input · Directory History · Session Tabs · Screen Orientation · Backup/Restore | 14 |
| 4 | Termux → Debugging | Logging | 4 |
| 5 | Termux → Terminal I/O | Keyboard | 5 |
| 6 | Termux → Extra Keys | Extra keys | 4 |
| 7 | Termux → Terminal View | View | 1 |
| 8 | Termux:API | Debugging → Logging | 1 |
| 9 | Termux:Float | Debugging → Logging | 2 |
| 10 | Termux:Tasker | Debugging → Logging | 1 |
| 11 | Termux:Widget | Debugging → Logging | 1 |

**Totals:** 11 screens · 18 subsections · 41 stored settings (plus 8 navigation entries and 2 backup/restore action buttons).

### Persistence notes
- **Termux main** in-category settings (message/directory history, text input, tabs, orientation) are mirrored into `termux_prefs` SharedPreferences for live application.
- **Appearance / Extra Keys** persist to `termux.properties`; theme mode writes `night_mode`.
- **Debugging, Terminal I/O, Terminal View, Extra Keys** and all **plugin** screens use custom `PreferenceDataStore` subclasses backed by `Termux*AppSharedPreferences`.

### Non-XML settings destinations
- **About** → `ReportActivity` (markdown device/app report).
- **Backup / Restore** → SAF file chooser driving `TermuxBackupService`.
- **Donate** → external URL.
- **Help** (`HelpActivity`, WebView) is reached from the terminal activity menu, not from the Settings tree.
