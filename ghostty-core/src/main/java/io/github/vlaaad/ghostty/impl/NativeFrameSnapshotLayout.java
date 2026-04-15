package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import io.github.vlaaad.ghostty.FrameStyle;

final class NativeFrameSnapshotLayout {
    static final int ROW_ENTRY_SIZE = 16;
    static final int STYLE_ENTRY_SIZE = 20;
    static final int RUN_ENTRY_SIZE = 16;

    static final long DIRTY_OFFSET = 0;
    static final long ACTIVE_SCREEN_OFFSET = 4;
    static final long MOUSE_TRACKING_OFFSET = 8;
    static final long KITTY_FLAGS_OFFSET = 12;

    static final long COLUMNS_OFFSET = 16;
    static final long ROWS_OFFSET = 20;
    static final long CELL_WIDTH_PX_OFFSET = 24;
    static final long CELL_HEIGHT_PX_OFFSET = 28;

    static final long CURSOR_VISIBLE_OFFSET = 32;
    static final long CURSOR_BLINKING_OFFSET = 36;
    static final long CURSOR_PASSWORD_INPUT_OFFSET = 40;
    static final long CURSOR_IN_VIEWPORT_OFFSET = 44;
    static final long CURSOR_X_OFFSET = 48;
    static final long CURSOR_Y_OFFSET = 52;
    static final long CURSOR_WIDE_TAIL_OFFSET = 56;
    static final long CURSOR_STYLE_OFFSET = 60;

    static final long COLORS_FOREGROUND_OFFSET = 64;
    static final long COLORS_BACKGROUND_OFFSET = 68;
    static final long COLORS_CURSOR_OFFSET = 72;
    static final long COLORS_CURSOR_EXPLICIT_OFFSET = 76;

    static final long SCROLLBAR_TOTAL_OFFSET = 80;
    static final long SCROLLBAR_OFFSET_OFFSET = 88;
    static final long SCROLLBAR_LENGTH_OFFSET = 96;

    static final long STYLE_COUNT_OFFSET = 104;

    static final long ROWS_OFFSET_OFFSET = 108;
    static final long STYLES_OFFSET_OFFSET = 116;
    static final long RUNS_OFFSET_OFFSET = 124;
    static final long TEXT_BYTES_OFFSET_OFFSET = 132;
    static final long TITLE_OFFSET_OFFSET = 140;
    static final long TITLE_LENGTH_OFFSET = 148;
    static final long PWD_OFFSET_OFFSET = 152;
    static final long PWD_LENGTH_OFFSET = 160;

    static final long ROW_FLAGS_OFFSET = 0;
    static final long ROW_RUN_START_OFFSET = 4;
    static final long ROW_RUN_COUNT_OFFSET = 8;

    static final long STYLE_FOREGROUND_OFFSET = 0;
    static final long STYLE_BACKGROUND_OFFSET = 4;
    static final long STYLE_UNDERLINE_COLOR_OFFSET = 8;
    static final long STYLE_UNDERLINE_STYLE_OFFSET = 12;
    static final long STYLE_FLAGS_OFFSET = 16;

    static final long RUN_STYLE_INDEX_OFFSET = 0;
    static final long RUN_TEXT_START_OFFSET = 4;
    static final long RUN_TEXT_LENGTH_OFFSET = 8;
    static final long RUN_COLUMNS_OFFSET = 12;

    static final int ROW_FLAG_DIRTY = 1 << 6;

    private NativeFrameSnapshotLayout() {}

    static long u64(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
    }

    static int u32(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
    }

    static FrameStyle style(MemorySegment segment, long offset) {
        return new FrameStyle(
            u32(segment, offset + STYLE_FOREGROUND_OFFSET),
            u32(segment, offset + STYLE_BACKGROUND_OFFSET),
            u32(segment, offset + STYLE_UNDERLINE_COLOR_OFFSET),
            io.github.vlaaad.ghostty.UnderlineStyle.values()[u32(segment, offset + STYLE_UNDERLINE_STYLE_OFFSET)],
            u32(segment, offset + STYLE_FLAGS_OFFSET)
        );
    }

    static String utf8(MemorySegment segment, long offset, int len) {
        if (len == 0) {
            return "";
        }
        return new String(segment.asSlice(offset, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

}
