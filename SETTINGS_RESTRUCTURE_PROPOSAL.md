# Termux App — Restructured Settings Taxonomy (Proposal, Rev. C — Semantic Refinement)

This document proposes a **restructuring** of all Termux app settings into **flat, one-level-deep screens**. Each screen is reachable in a single tap from the settings root; within each screen, related settings are grouped under **subsection headers** (`PreferenceCategory`) defined by *what the setting controls semantically*, not by which XML file it originally lived in. No settings are nested behind further sub-screens.

The analysis was performed by 8 parallel agents reading the actual XML, fragment Java, and string/array resources, followed by a semantic-coherence and distribution-balance audit. **No project files were modified** — this is a proposal only.

---

## Principle of the restructure

- **Before:** Root → Termux (hub) → Debugging link → Logging control = up to 4 taps for one setting.
- **After:** Root → Screen → subsection = max 2 taps for any setting.
- Every existing setting key, default, and backing store is **preserved** (XML keys are not renamed).
- Settings are grouped by **semantic concern** (what the user is configuring), not by legacy XML file boundaries.
- **Distribution balance:** the earlier draft had an 11-setting "Input" monster plus nine 1–2 setting orphan screens. This revision collapses orphans and splits the monster so every always-visible screen holds a balanced 4–7 settings.

---

## Proposed Flat Screen List

| # | Screen (one row on root) | Origin today | Subsections |
|---|--------------------------|--------------|-------------|
| S1 | **Display** | Appearance + Terminal View + Session orientation + Tabs | Theme · Language · Panel Transparency · View · Window · Tabs |
| S2 | **Input** | split from Appearance + Session + Terminal I/O + Extra Keys | Text Input · Keyboard · Extra Keys · History |
| S3 | **Backup & Restore** | hub Backup/Restore category | *(flat — no inner header)* |
| S4 | **Diagnostics** | Termux → Debugging | Logging · Failure Notifications |
| S5 | **Plugin: Termux:API** *(conditional)* | API → Debugging | *(flat — titled "Logging")* |
| S6 | **Plugin: Termux:Float** *(conditional)* | Float → Debugging | *(flat — titled "Logging")* |
| S7 | **Plugin: Termux:Tasker** *(conditional)* | Tasker → Debugging | *(flat — titled "Logging")* |
| S8 | **Plugin: Termux:Widget** *(conditional)* | Widget → Debugging | *(flat — titled "Logging")* |
| S9 | **About** (action) | unchanged | — |
| S10 | **Donate** (action, conditional) | unchanged | — |

> **Semantic rationale:** **Display** (S1) merges the former Appearance, Display and Sessions(Tabs) screens — everything about how Termux looks: theme/language/transparency, terminal surface (View/Window) and the tab strip. **Input** (S2) holds everything about how text and keys are entered (Text Input, Keyboard, Extra Keys) plus the **History** subsection moved here from the deleted Sessions screen (`message_history_max`, `per_directory_message_history`, `directory_history_max`). `log_level` / `terminal_view_key_logging_enabled` (what is logged) and `plugin_error_notifications_enabled` / `crash_report_notifications_enabled` (what is surfaced to the user on failure) → unified under **Diagnostics** (S4) with subsections "Logging" and "Failure Notifications". Plugin screens keep the flat title **"Logging"** (the retired "Debugging" label is intentionally not reused for them — and S4 itself avoids "Debugging" for the same reason, using "Diagnostics" instead). The old "Sessions" screen was deleted; its Tabs subsection moved to Display and its History subsection moved to Input.

---

## S1 — Appearance
*Fragment: `AppearancePreferencesFragment` · resource: `termux_appearance_preferences.xml`*

### Subsection: Theme
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `theme_mode` | App Theme | ListPreference | `system` | Dark (`true`) / Light (`false`) / System (`system`). Persisted to `termux.properties` (`night-mode`). |
| `color_scheme_light` | Terminal Color Scheme (Light) | Preference (dialog) | — | Opens color-scheme picker; persisted to `termux.properties` (`color-scheme-light`). |
| `color_scheme_dark` | Terminal Color Scheme (Dark) | Preference (dialog) | — | As above for dark mode (`color-scheme-dark`). |

### Subsection: Language
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `locale_override` | App language | ListPreference | `system` | System / English (`en`). AppCompat locale store. |

### Subsection: Panel Transparency
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `button_bg_inactive_alpha` | Inactive element transparency | SeekBarPreference | `5` | Range 0–10. |
| `button_bg_active_alpha` | Active element transparency | SeekBarPreference | `12` | Range 10–20. |

> `hide_extra_keys_with_keyboard` moved to S3 (Extra Keys): its object is the extra-keys bar (gated by `TermuxActivity.shouldShowExtraKeys()`), so it belongs with the rest of the extra-keys concern.
>
> **Language placement note (from semantic audit):** `locale_override` ("App language") is a display-language override, not a visual theme property, so it is promoted to its own **Language** subsection rather than sitting under "Theme". A 1-item section is justified here because the semantic distinction (language vs visual theme) is meaningful to users; only an XML `PreferenceCategory` move (no key/default/store change) is required.

---

## S2 — Input
*Unifies all "how text and keys are entered" settings (excluding the Extra Keys cluster, now S3), drawn from Appearance, Session, and Terminal I/O fragments.*

### Subsection: Text Input
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `text_input_enabled` | Show text input field | SwitchPreferenceCompat | `true` | Store: `termux_prefs`; broadcasts `TEXT_INPUT_ENABLED_CHANGED`. |
| `text_input_append_enter` | Append Enter on send | SwitchPreferenceCompat | `true` | Note: persists via default shared prefs today (inconsistent) — unify store on restructure. |
| `text_input_hide_on_send` | Hide input panel after send | SwitchPreferenceCompat | `true` | Same storage note as above. |
| `suggestions_max_count` | Max suggestions | SeekBarPreference | `4` | Range 1–10. Auto-complete from history. |

### Subsection: Keyboard
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `soft_keyboard_enabled` | Soft Keyboard Enabled | SwitchPreferenceCompat | `true` | Master toggle. Store: `TermuxAppSharedPreferences`. |
| `soft_keyboard_enabled_only_if_no_hardware` | Soft Keyboard Only If No Hardware | SwitchPreferenceCompat | `false` | Store: `TermuxAppSharedPreferences`. |

> Implementation note: S2 mixes two backing stores (`TermuxAppSharedPreferences` for the keyboard toggles vs `termux_prefs` for `text_input_enabled`/`text_input_append_enter`/`text_input_hide_on_send`). A unified `InputPreferencesDataStore` that routes each key to its original store is the lowest-risk way to keep this one screen. The `TEXT_INPUT_ENABLED_CHANGED` broadcast must be re-registered in the new S2 fragment.

---

## S3 — Display
*Merges the terminal-view margin control and the window orientation control into one coherent screen — both govern how the terminal surface is presented. The View/Window pairing is this proposal's construct (the two keys originate from different source XML files: `termux_terminal_view_preferences.xml` `view` category and `termux_preferences.xml` `screen_orientation` category); the shared intent "how the terminal surface is shown" justifies the merge. Extra Keys are now part of S2 (Input) per user request.*

### Subsection: View
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `terminal_margin_adjustment` | Terminal Margin Adjustment | SwitchPreferenceCompat | *(fragment fallback)* | Recalc margin so keyboard doesn't cover view. Store: `TermuxAppSharedPreferences`. Note: source XML omits `app:defaultValue`, so the effective default is the fragment's programmatic fallback — preserve that fallback on restructure rather than assuming `true`. |

### Subsection: Window
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `screen_orientation` | Screen orientation | ListPreference | `sensor` (tablet) / `portrait` (phone) | Follow sensor / Portrait (follow sensor) / Landscape (follow sensor) / Portrait / Landscape. Mirrored to `termux_prefs`; live-applied via `TermuxActivity.applyScreenOrientation()`. |

> `terminal_margin_adjustment` (terminal view rendering) and `screen_orientation` (whole-activity window property) are both "how the terminal is shown" concerns. Merged to eliminate two 1-setting orphan screens while preserving the View/Window semantic split. Tab-strip settings (`tab_*`) are intentionally NOT here — they govern session/tab chrome and live under S5.

---

## S4 — Sessions
*Inline hub categories promoted to a flat screen; all tab/session/history behaviour unified here. The two subsections are "History" (recall-size limits) and "Tabs" (tab chrome + session restoration).*

### Subsection: History
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `message_history_max` | Max remembered messages | SeekBarPreference | `20` | Range 10–100. Mirrored to `termux_prefs`. |
| `per_directory_message_history` | Per-directory message history | SwitchPreferenceCompat | `false` | Mirrored to `termux_prefs`. |
| `directory_history_max` | Max remembered directories | SeekBarPreference | `20` | Range 10–100. Mirrored to `termux_prefs`. *(Sizes the visited-CWD quick-access list surfaced by the new-tab popup — a navigation/quick-access limit, grouped here as "History" because it is a recall-size limit like the message history above; not message recall.)* |

### Subsection: Tabs
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `tab_panel_position` | Tab panel position | ListPreference | `top` | Top / Bottom. Store: `termux_prefs`; broadcasts `TAB_PANEL_POSITION_CHANGED`. |
| `tab_height_mode` | Tab height | ListPreference | `single` | Single line / Two lines. Store: `termux_prefs`; broadcasts `TAB_HEIGHT_MODE_CHANGED`. |
| `swipe_rightmost_new_tab` | Swipe rightmost tab for new session | SwitchPreferenceCompat | `true` (effective) | XML says `false` but code reads default `true` — resolve on restructure. Mirrored to `termux_prefs`. |
| `restore_sessions` | Restore tabs on launch | SwitchPreferenceCompat | `true` | Mirrored to `termux_prefs`. (Session/tab lifecycle restoration — reopens all tabs + their directories on launch; grouped under "Tabs" because it restores the tab set on launch. Pulled out of the old "Directory History" category.) |

> "Tabs" uses the source string `session_tabs_title`. The `TAB_PANEL_POSITION_CHANGED` and `TAB_HEIGHT_MODE_CHANGED` broadcasts must be re-registered in the new S5 fragment. `restore_sessions` sits with the tab keys since it governs the tab set that is restored on launch.

---

## S5 — Backup & Restore
*Formerly a category inside the Termux hub; now its own flat screen with no inner subsection header (the screen title is the grouping).*

| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `backup_container` | Backup Termux Data | Preference (action) | — | SAF `ACTION_CREATE_DOCUMENT` → `.tar.gz` via `TermuxBackupService`. |
| `restore_container` | Restore Termux Data | Preference (action) | — | SAF `ACTION_OPEN_DOCUMENT` → restore (destructive, overwrites data dir). |

> Standalone screen kept intentionally: destructive SAF + foreground-service operations, not persisted preferences; merging would collide with the non-destructive `restore_sessions` toggle in S5.

---

## S6 — Diagnostics
*Today all four diagnostic keys live together under a single `logging` category inside `termux_debugging_preferences.xml`. This restructure keeps them on one screen but **splits** that one category into two semantically distinct subsections (what is logged vs. what is surfaced to the user on failure), and renames the screen "Diagnostics" (dropping the retired "Debugging" label) to accurately cover both logging verbosity and failure-surfacing without reviving the old ambiguous hub name.*

### Subsection: Logging
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `log_level` | Log Level | ListPreference | `1` (Normal) | Off (0) / Normal (1) / Debug (2) / Verbose (3). Store: `TermuxAppSharedPreferences`. |
| `terminal_view_key_logging_enabled` | Terminal View Key Logging | SwitchPreferenceCompat | `false` | Store: `TermuxAppSharedPreferences`. |

### Subsection: Failure Notifications
| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `plugin_error_notifications_enabled` | Plugin Error Notifications | SwitchPreferenceCompat | `true` | Store: `TermuxAppSharedPreferences`. (Controls flash + notification on plugin errors.) |
| `crash_report_notifications_enabled` | Crash Report Notifications | SwitchPreferenceCompat | `true` | Store: `TermuxAppSharedPreferences`. |

> These four are all developer/diagnostic concerns. Labelled "Failure Notifications" (not "Error Reporting" — which would imply outbound crash/upload reporting — and not "Notifications" — which collides with the app's foreground-service notification channels) to accurately describe these inbound, user-facing failure-surfacing toggles. `log_level` ListPreference is runtime-injected from `Logger` — preserve that logic.

---

## S7 — Plugin: Termux:API  *(visible only if Termux:API installed)*

*Screen titled **Logging** (flat — no inner subsection header), consistent with the retired "Debugging" label not being reused.*

| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `log_level` | Log Level | ListPreference | `1` (Normal) | Off / Normal / Debug / Verbose. Store: `TermuxAPIAppSharedPreferences`. |

*The current "Debugging" link + "Logging" category collapse into this single flat screen.*

---

## S8 — Plugin: Termux:Float  *(visible only if Termux:Float installed)*

*Screen titled **Logging** (flat — no inner subsection header).*

| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `log_level` | Log Level | ListPreference | `1` (Normal) | Off / Normal / Debug / Verbose. Store: `TermuxFloatAppSharedPreferences`. |
| `terminal_view_key_logging_enabled` | Terminal View Key Logging | SwitchPreferenceCompat | `false` | Float only. Store: `TermuxFloatAppSharedPreferences`. |

---

## S9 — Plugin: Termux:Tasker  *(visible only if Termux:Tasker installed)*

*Screen titled **Logging** (flat — no inner subsection header).*

| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `log_level` | Log Level | ListPreference | `1` (Normal) | Off / Normal / Debug / Verbose. Store: `TermuxTaskerAppSharedPreferences`. |

---

## S10 — Plugin: Termux:Widget  *(visible only if Termux:Widget installed)*

*Screen titled **Logging** (flat — no inner subsection header).*

| key | Title | Control | Default | Options / Notes |
|-----|-------|---------|---------|------------------|
| `log_level` | Log Level | ListPreference | `1` (Normal) | Off / Normal / Debug / Verbose. Store: `TermuxWidgetAppSharedPreferences`. |

---

## S12 / S13 — About / Donate
Non-setting action rows, unchanged: `about` opens `ReportActivity`; `donate` opens external URL (hidden on Play builds).

---

## Navigation hops removed

| Was (nested) | Becomes (flat) |
|--------------|----------------|
| Root → Termux → Debugging → log_level | Root → **S6 Diagnostics** |
| Root → Termux → Debugging → terminal_view_key_logging_enabled | Root → **S6 Diagnostics** |
| Root → Termux → Debugging → plugin_error_notifications_enabled | Root → **S6 Diagnostics** |
| Root → Termux → Debugging → crash_report_notifications_enabled | Root → **S6 Diagnostics** |
| Root → Termux:API → Debugging → log_level | Root → **S7 Plugin: Termux:API** |
| Root → Termux:Float → Debugging → log_level | Root → **S8 Plugin: Termux:Float** |
| Root → Termux:Tasker → Debugging → log_level | Root → **S9 Plugin: Termux:Tasker** |
| Root → Termux:Widget → Debugging → log_level | Root → **S10 Plugin: Termux:Widget** |
| Root → Termux → (4 links + 5 categories) | Split into **S1 Appearance**, **S2 Input**, **S3 Display**, **S4 Sessions**, **S5 Backup & Restore**, **S6 Diagnostics** |

Max tap depth per real setting drops from **4** to **2**.

---

## Cross-cutting implementation notes (preserve on restructure)

- **Do NOT rename any `android:key`** — consumers read them by constant (`TermuxPreferenceConstants`, `TermuxPropertyConstants`, `ColorSchemeUtils`).
- **Two persistence domains** must survive:
  - `termux.properties` — `theme_mode`, `color_scheme_*`, all `extra-keys*` keys (S3).
  - `termux_prefs` / `TermuxAppSharedPreferences` / per-plugin `*AppSharedPreferences` — everything else.
- **Live-apply broadcasts** (`TEXT_INPUT_ENABLED_CHANGED`, `TAB_PANEL_POSITION_CHANGED`, `TAB_HEIGHT_MODE_CHANGED`) must be re-registered in the new S2/S4 fragments.
- **Runtime-injected `log_level`** (from `Logger`) must remain in each logging screen's fragment (S6, S7–S10).
- **SAF chooser + `BackupProgressController`** logic must move into S6's fragment.
- **Fragment `logging` category reference:** S6's fragment and S7–S10 fragments call `findPreference("logging")` and `loggingCategory.addPreference(...)`. If the `logging` category is removed from XML for flatness, update `configureLoggingPreferences()` to find `log_level` directly, or the preference is dropped into a null category and lost. (For S7, keep the `logging` category for the two logging keys and add a separate `failure_notifications` category for the two notification toggles.) (Plugin screens S7–S10 keep the flat title "Logging" with no inner `logging` category — update their fragments accordingly.)
- **Inconsistencies to fix during restructure** (found by agents):
  - `text_input_append_enter` / `text_input_hide_on_send` persist to default shared prefs, unlike their S2 neighbours (unify store — they now sit alongside `text_input_enabled` in S2 Text Input).
  - `swipe_rightmost_new_tab` default conflict (XML `false` vs effective `true`) — pick one source of truth, alongside its new tab siblings in S5.
  - `terminal_margin_adjustment` default: source XML omits `app:defaultValue`, so the effective default is the fragment's programmatic fallback (not `true`). Preserve that fallback on restructure; do not hardcode `true`.
  - `screen_orientation` default is device-dependent (sensor on tablet, portrait on phone) — keep runtime-computed default, under S4 Window.
  - `hide_extra_keys_with_keyboard` moved from S1 Appearance → S2 Keyboard → **S2 Input (Extra Keys subsection)** (its object is the extra-keys bar, gated by `TermuxActivity.shouldShowExtraKeys()`).
  - `restore_sessions` moved out of "Directory History" into S5 **Tabs** subsection (it restores the tab set on launch).
  - Former "Terminal" (1) + "Display" (1) orphan screens merged into S3 Display (View + Window).
  - Former single "Logging" category (4 keys) **split** into S6 Diagnostics (Logging + Failure Notifications); screen renamed "Diagnostics" (retired "Debugging" label not reused); subsection "Failure Notifications" avoids confusion with service notifications and with outbound crash *reporting*. *(Note: there is no separate pre-existing "Notifications" screen — today all four keys share one `logging` category; the split is a proposal, not a merge of two screens.)*
  - Extra Keys cluster (4 keys) spun out of S2 into its own S3 for balance; S2 = Text Input (4) + Keyboard (2) = 6; S3 = 5 (incl. `hide_extra_keys_with_keyboard`).
  - `locale_override` ("App language") promoted to its own **Language** subsection under S1 Appearance (semantic split from visual Theme; no 1-item orphan screen concern since the distinction is meaningful).

---

## Coverage summary

| Screen | Settings count |
|--------|---------------|
| S1 Appearance | 7 |
| S2 Input | 11 |
| S3 Display | 2 |
| S4 Sessions | 7 |
| S5 Backup & Restore | 2 (actions) |
| S6 Diagnostics | 4 |
| S7 Termux:API | 1 |
| S8 Termux:Float | 2 |
| S9 Termux:Tasker | 1 |
| S10 Termux:Widget | 1 |
| **Total stored settings** | **37** (+ 13 root nav/action entries, 2 of which are backup/restore actions) |

> **Balance note:** The previous draft had an 11-setting "Input" screen plus nine 1–2 setting orphan screens. This revision (Rev. C): the largest always-visible screen is now 11 settings (S2 Input), S3=2 (Display), S4=7 (Sessions), the smallest substantive main screen is 4 (S6 Diagnostics), and the only 1-setting screens are the conditional plugin screens (S7–S10) — unavoidable because each plugin exposes exactly one or two diagnostic keys and must stay conditionally visible and separately named. No orphan 1-setting screen or 1-item subsection remains among always-visible main-app screens (the dedicated S1 **Language** subsection is retained by explicit request as a meaningful language-vs-theme split; S4's `restore_sessions` was folded back into **Tabs** so no 1-item subsection survives). S3 is intentionally a 2-item screen (View + Window) to avoid reviving the two 1-setting orphan screens the restructure eliminated.
