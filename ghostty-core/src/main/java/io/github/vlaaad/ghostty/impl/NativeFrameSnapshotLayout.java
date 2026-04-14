package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.FrameStyle;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

final class NativeFrameSnapshotLayout {
    static final int MAGIC = 0x58465447;
    static final int HEADER_SIZE = 208;
    static final int ROW_ENTRY_SIZE = 16;
    static final int STYLE_ENTRY_SIZE = 20;
    static final int CELL_ENTRY_SIZE = 16;
    static final int RUN_ENTRY_SIZE = 16;

    static final long MAGIC_OFFSET = 0;
    static final long VERSION_OFFSET = 4;
    static final long HEADER_SIZE_OFFSET = 6;
    static final long ROW_ENTRY_SIZE_OFFSET = 8;
    static final long STYLE_ENTRY_SIZE_OFFSET = 10;
    static final long RUN_ENTRY_SIZE_OFFSET = 12;
    static final long TOTAL_SIZE_OFFSET = 16;

    static final long DIRTY_OFFSET = 24;
    static final long ACTIVE_SCREEN_OFFSET = 28;
    static final long MOUSE_TRACKING_OFFSET = 32;
    static final long KITTY_FLAGS_OFFSET = 36;

    static final long COLUMNS_OFFSET = 40;
    static final long ROWS_OFFSET = 44;
    static final long CELL_WIDTH_PX_OFFSET = 48;
    static final long CELL_HEIGHT_PX_OFFSET = 52;

    static final long CURSOR_VISIBLE_OFFSET = 56;
    static final long CURSOR_BLINKING_OFFSET = 60;
    static final long CURSOR_PASSWORD_INPUT_OFFSET = 64;
    static final long CURSOR_IN_VIEWPORT_OFFSET = 68;
    static final long CURSOR_X_OFFSET = 72;
    static final long CURSOR_Y_OFFSET = 76;
    static final long CURSOR_WIDE_TAIL_OFFSET = 80;
    static final long CURSOR_STYLE_OFFSET = 84;

    static final long COLORS_FOREGROUND_OFFSET = 88;
    static final long COLORS_BACKGROUND_OFFSET = 92;
    static final long COLORS_CURSOR_OFFSET = 96;
    static final long COLORS_CURSOR_EXPLICIT_OFFSET = 100;

    static final long SCROLLBAR_TOTAL_OFFSET = 104;
    static final long SCROLLBAR_OFFSET_OFFSET = 112;
    static final long SCROLLBAR_LENGTH_OFFSET = 120;

    static final long ROW_COUNT_OFFSET = 128;
    static final long STYLE_COUNT_OFFSET = 132;
    static final long RUN_COUNT_OFFSET = 136;
    static final long TEXT_BYTE_COUNT_OFFSET = 140;

    static final long ROWS_OFFSET_OFFSET = 144;
    static final long STYLES_OFFSET_OFFSET = 152;
    static final long RUNS_OFFSET_OFFSET = 160;
    static final long TEXT_BYTES_OFFSET_OFFSET = 168;
    static final long TITLE_OFFSET_OFFSET = 176;
    static final long TITLE_LENGTH_OFFSET = 184;
    static final long PWD_OFFSET_OFFSET = 192;
    static final long PWD_LENGTH_OFFSET = 200;

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

    static final long CELL_STYLE_INDEX_OFFSET = 0;
    static final long CELL_CODEPOINT_START_OFFSET = 4;
    static final long CELL_CODEPOINT_LENGTH_OFFSET = 8;
    static final long CELL_WIDTH_OFFSET = 12;

    static final int ROW_FLAG_WRAPPED = 1 << 0;
    static final int ROW_FLAG_WRAP_CONTINUATION = 1 << 1;
    static final int ROW_FLAG_GRAPHEME = 1 << 2;
    static final int ROW_FLAG_STYLED = 1 << 3;
    static final int ROW_FLAG_HYPERLINK = 1 << 4;
    static final int ROW_FLAG_KITTY_VIRTUAL_PLACEHOLDER = 1 << 5;
    static final int ROW_FLAG_DIRTY = 1 << 6;

    static final int STYLE_FLAG_BOLD = 1 << 0;
    static final int STYLE_FLAG_FAINT = 1 << 1;
    static final int STYLE_FLAG_ITALIC = 1 << 2;
    static final int STYLE_FLAG_UNDERLINE = 1 << 3;
    static final int STYLE_FLAG_BLINK = 1 << 4;
    static final int STYLE_FLAG_INVERSE = 1 << 5;
    static final int STYLE_FLAG_INVISIBLE = 1 << 6;
    static final int STYLE_FLAG_STRIKETHROUGH = 1 << 7;
    static final int STYLE_FLAG_OVERLINE = 1 << 8;

    static final String EMPTY_TEXT = "";
    static final String[] ASCII = ascii();

    private NativeFrameSnapshotLayout() {}

    static long u64(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED, offset);
    }

    static int u32(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED, offset);
    }

    static short u16(MemorySegment segment, long offset) {
        return segment.get(ValueLayout.JAVA_SHORT_UNALIGNED, offset);
    }

    static FrameStyle style(MemorySegment segment, long offset) {
        var flags = u32(segment, offset + STYLE_FLAGS_OFFSET);
        return new FrameStyle(
            u32(segment, offset + STYLE_FOREGROUND_OFFSET),
            u32(segment, offset + STYLE_BACKGROUND_OFFSET),
            u32(segment, offset + STYLE_UNDERLINE_COLOR_OFFSET),
            io.github.vlaaad.ghostty.UnderlineStyle.values()[u32(segment, offset + STYLE_UNDERLINE_STYLE_OFFSET)],
            (flags & STYLE_FLAG_BOLD) != 0,
            (flags & STYLE_FLAG_FAINT) != 0,
            (flags & STYLE_FLAG_ITALIC) != 0,
            (flags & STYLE_FLAG_UNDERLINE) != 0,
            (flags & STYLE_FLAG_BLINK) != 0,
            (flags & STYLE_FLAG_INVERSE) != 0,
            (flags & STYLE_FLAG_INVISIBLE) != 0,
            (flags & STYLE_FLAG_STRIKETHROUGH) != 0,
            (flags & STYLE_FLAG_OVERLINE) != 0
        );
    }

    static String utf8(MemorySegment segment, long offset, int len) {
        if (len == 0) {
            return EMPTY_TEXT;
        }
        return new String(segment.asSlice(offset, len).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    static String cellText(MemorySegment segment, long codepointsOffset, int codepointStart, int codepointLength) {
        if (codepointLength == 0) {
            return EMPTY_TEXT;
        }
        var offset = codepointsOffset + (long) codepointStart * Integer.BYTES;
        if (codepointLength == 1) {
            var codepoint = u32(segment, offset);
            if (codepoint >= 0 && codepoint < ASCII.length) {
                return ASCII[codepoint];
            }
            return new String(Character.toChars(codepoint));
        }
        var codepoints = new int[codepointLength];
        for (var i = 0; i < codepointLength; i++) {
            codepoints[i] = u32(segment, offset + (long) i * Integer.BYTES);
        }
        return new String(codepoints, 0, codepointLength);
    }

    private static String[] ascii() {
        var values = new String[128];
        for (var i = 0; i < values.length; i++) {
            values[i] = new String(new char[] {(char) i});
        }
        return values;
    }
}
