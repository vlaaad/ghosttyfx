# Ghostty Update Notes

After updating the pinned Ghostty/libghostty-vt version, consider using:

- `ghostty_terminal_selection_format_*` for copy text instead of formatter setup.
- `GHOSTTY_TERMINAL_OPT_SELECTION` to store active selection in Ghostty.
- Render row/cell selection data for selected-cell painting.
- `ghostty_terminal_select_word`, `select_line`, `select_all`, `select_output`.
- `ghostty_terminal_selection_adjust/order/contains/equal` for keyboard extension.
- `ghostty_selection_gesture_*` for press/drag/release/autoscroll selection.
- Tracked grid refs for anchors that must survive scrollback mutations.
- `GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE` for pinned-to-bottom state.
- Default cursor style/blink options instead of setting mode 12 directly.
- `GHOSTTY_TERMINAL_OPT_PWD_CHANGED` for OSC 7/9/1337 current directory updates.
- `GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8` for simpler text extraction.
- `GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING` to skip unnecessary style fetches.
- `ghostty_unicode_codepoint_width` where Java width guesses are too weak.
