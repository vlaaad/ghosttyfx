package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.ColorPalette;
import io.github.vlaaad.ghostty.ColorScheme;
import io.github.vlaaad.ghostty.ColorValue;
import io.github.vlaaad.ghostty.Point;
import io.github.vlaaad.ghostty.RowCoordinateSpace;
import io.github.vlaaad.ghostty.Style;
import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalMode;
import io.github.vlaaad.ghostty.TerminalScrollViewport;
import io.github.vlaaad.ghostty.TerminalScrollbar;
import io.github.vlaaad.ghostty.TerminalSession;
import io.github.vlaaad.ghostty.TerminalSize;
import io.github.vlaaad.ghostty.UnderlineStyle;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

public final class NativeTerminalBindings {
    static final AddressLayout C_POINTER = ValueLayout.ADDRESS;

    static final int OPT_WRITE_PTY = 1;
    static final int OPT_BELL = 2;
    static final int OPT_ENQUIRY = 3;
    static final int OPT_XTVERSION = 4;
    static final int OPT_TITLE_CHANGED = 5;
    static final int OPT_SIZE = 6;
    static final int OPT_COLOR_SCHEME = 7;
    static final int OPT_DEVICE_ATTRIBUTES = 8;
    static final int OPT_TITLE = 9;
    static final int OPT_PWD = 10;
    static final int OPT_COLOR_FOREGROUND = 11;
    static final int OPT_COLOR_BACKGROUND = 12;
    static final int OPT_COLOR_CURSOR = 13;
    static final int OPT_COLOR_PALETTE = 14;

    static final int DATA_COLS = 1;
    static final int DATA_ROWS = 2;
    static final int DATA_CURSOR_X = 3;
    static final int DATA_CURSOR_Y = 4;
    static final int DATA_CURSOR_PENDING_WRAP = 5;
    static final int DATA_ACTIVE_SCREEN = 6;
    static final int DATA_CURSOR_VISIBLE = 7;
    static final int DATA_KITTY_KEYBOARD_FLAGS = 8;
    static final int DATA_SCROLLBAR = 9;
    static final int DATA_CURSOR_STYLE = 10;
    static final int DATA_TITLE = 12;
    static final int DATA_PWD = 13;
    static final int DATA_TOTAL_ROWS = 14;
    static final int DATA_SCROLLBACK_ROWS = 15;
    static final int DATA_WIDTH_PX = 16;
    static final int DATA_HEIGHT_PX = 17;
    static final int DATA_COLOR_FOREGROUND = 18;
    static final int DATA_COLOR_BACKGROUND = 19;
    static final int DATA_COLOR_CURSOR = 20;
    static final int DATA_COLOR_PALETTE = 21;
    static final int DATA_COLOR_FOREGROUND_DEFAULT = 22;
    static final int DATA_COLOR_BACKGROUND_DEFAULT = 23;
    static final int DATA_COLOR_CURSOR_DEFAULT = 24;
    static final int DATA_COLOR_PALETTE_DEFAULT = 25;

    static final int CELL_DATA_CODEPOINT = 1;
    static final int CELL_DATA_CONTENT_TAG = 2;
    static final int CELL_DATA_WIDE = 3;
    static final int CELL_DATA_PROTECTED = 8;
    static final int CELL_DATA_SEMANTIC_CONTENT = 9;
    static final int CELL_DATA_COLOR_PALETTE = 10;
    static final int CELL_DATA_COLOR_RGB = 11;

    static final int ROW_DATA_WRAP = 1;
    static final int ROW_DATA_WRAP_CONTINUATION = 2;
    static final int ROW_DATA_GRAPHEME = 3;
    static final int ROW_DATA_STYLED = 4;
    static final int ROW_DATA_HYPERLINK = 5;
    static final int ROW_DATA_SEMANTIC_PROMPT = 6;
    static final int ROW_DATA_KITTY_VIRTUAL_PLACEHOLDER = 7;
    static final int ROW_DATA_DIRTY = 8;

    static final int RENDER_STATE_DIRTY_FALSE = 0;
    static final int RENDER_STATE_DIRTY_PARTIAL = 1;
    static final int RENDER_STATE_DIRTY_FULL = 2;
    static final int RENDER_STATE_CURSOR_VISUAL_STYLE_BAR = 0;
    static final int RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK = 1;
    static final int RENDER_STATE_CURSOR_VISUAL_STYLE_UNDERLINE = 2;
    static final int RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK_HOLLOW = 3;
    static final int RENDER_STATE_DATA_COLS = 1;
    static final int RENDER_STATE_DATA_ROWS = 2;
    static final int RENDER_STATE_DATA_DIRTY = 3;
    static final int RENDER_STATE_DATA_ROW_ITERATOR = 4;
    static final int RENDER_STATE_DATA_COLOR_BACKGROUND = 5;
    static final int RENDER_STATE_DATA_COLOR_FOREGROUND = 6;
    static final int RENDER_STATE_DATA_COLOR_CURSOR = 7;
    static final int RENDER_STATE_DATA_COLOR_CURSOR_HAS_VALUE = 8;
    static final int RENDER_STATE_DATA_COLOR_PALETTE = 9;
    static final int RENDER_STATE_DATA_CURSOR_VISUAL_STYLE = 10;
    static final int RENDER_STATE_DATA_CURSOR_VISIBLE = 11;
    static final int RENDER_STATE_DATA_CURSOR_BLINKING = 12;
    static final int RENDER_STATE_DATA_CURSOR_PASSWORD_INPUT = 13;
    static final int RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE = 14;
    static final int RENDER_STATE_DATA_CURSOR_VIEWPORT_X = 15;
    static final int RENDER_STATE_DATA_CURSOR_VIEWPORT_Y = 16;
    static final int RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL = 17;
    static final int RENDER_STATE_OPTION_DIRTY = 0;
    static final int RENDER_STATE_ROW_DATA_DIRTY = 1;
    static final int RENDER_STATE_ROW_DATA_RAW = 2;
    static final int RENDER_STATE_ROW_DATA_CELLS = 3;
    static final int RENDER_STATE_ROW_OPTION_DIRTY = 0;
    static final int RENDER_STATE_ROW_CELLS_DATA_RAW = 1;
    static final int RENDER_STATE_ROW_CELLS_DATA_STYLE = 2;
    static final int RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN = 3;
    static final int RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF = 4;
    static final int RENDER_STATE_ROW_CELLS_DATA_BG_COLOR = 5;
    static final int RENDER_STATE_ROW_CELLS_DATA_FG_COLOR = 6;

    static final int STYLE_COLOR_NONE = 0;
    static final int STYLE_COLOR_PALETTE = 1;
    static final int STYLE_COLOR_RGB = 2;

    static final int COLOR_SCHEME_LIGHT = 0;
    static final int COLOR_SCHEME_DARK = 1;
    static final int SCREEN_PRIMARY = 0;
    static final int SCREEN_ALTERNATE = 1;
    static final int POINT_ACTIVE = 0;
    static final int POINT_VIEWPORT = 1;
    static final int POINT_SCREEN = 2;
    static final int POINT_HISTORY = 3;
    static final int SCROLL_TOP = 0;
    static final int SCROLL_BOTTOM = 1;
    static final int SCROLL_DELTA = 2;
    static final int KITTY_DISAMBIGUATE = 1;
    static final int KITTY_REPORT_EVENTS = 2;
    static final int KITTY_REPORT_ALTERNATES = 4;
    static final int KITTY_REPORT_ALL = 8;
    static final int KITTY_REPORT_ASSOCIATED = 16;

    static final MemoryLayout TERMINAL_OPTIONS_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("cols"),
        ValueLayout.JAVA_SHORT.withName("rows"),
        ValueLayout.JAVA_INT.withName("_pad0"),
        NativeRuntime.SIZE_T_LAYOUT.withName("max_scrollback")
    );
    static final long TERMINAL_OPTIONS_COLS_OFFSET = TERMINAL_OPTIONS_LAYOUT.byteOffset(groupElement("cols"));
    static final long TERMINAL_OPTIONS_ROWS_OFFSET = TERMINAL_OPTIONS_LAYOUT.byteOffset(groupElement("rows"));
    static final long TERMINAL_OPTIONS_SCROLLBACK_OFFSET = TERMINAL_OPTIONS_LAYOUT.byteOffset(groupElement("max_scrollback"));

    static final MemoryLayout SCROLL_VIEWPORT_VALUE_LAYOUT = MemoryLayout.unionLayout(
        ValueLayout.JAVA_LONG.withName("delta"),
        MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_LONG).withName("_padding")
    );
    static final MemoryLayout SCROLL_VIEWPORT_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("tag"),
        ValueLayout.JAVA_INT.withName("_pad0"),
        SCROLL_VIEWPORT_VALUE_LAYOUT.withName("value")
    );
    static final long SCROLL_VIEWPORT_TAG_OFFSET = SCROLL_VIEWPORT_LAYOUT.byteOffset(groupElement("tag"));
    static final long SCROLL_VIEWPORT_DELTA_OFFSET = SCROLL_VIEWPORT_LAYOUT.byteOffset(
        groupElement("value"),
        groupElement("delta")
    );

    static final MemoryLayout RGB_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("r"),
        ValueLayout.JAVA_BYTE.withName("g"),
        ValueLayout.JAVA_BYTE.withName("b")
    );
    static final long RGB_R_OFFSET = RGB_LAYOUT.byteOffset(groupElement("r"));
    static final long RGB_G_OFFSET = RGB_LAYOUT.byteOffset(groupElement("g"));
    static final long RGB_B_OFFSET = RGB_LAYOUT.byteOffset(groupElement("b"));
    static final MemoryLayout PALETTE_LAYOUT = MemoryLayout.sequenceLayout(256, RGB_LAYOUT);

    static final MemoryLayout RENDER_STATE_COLORS_LAYOUT = MemoryLayout.structLayout(
        NativeRuntime.SIZE_T_LAYOUT.withName("size"),
        RGB_LAYOUT.withName("background"),
        RGB_LAYOUT.withName("foreground"),
        RGB_LAYOUT.withName("cursor"),
        ValueLayout.JAVA_BOOLEAN.withName("cursor_has_value"),
        PALETTE_LAYOUT.withName("palette"),
        ValueLayout.JAVA_SHORT.withName("_pad0"),
        ValueLayout.JAVA_INT.withName("_pad1")
    );
    static final long RENDER_STATE_COLORS_SIZE_OFFSET = RENDER_STATE_COLORS_LAYOUT.byteOffset(groupElement("size"));
    static final long RENDER_STATE_COLORS_BACKGROUND_OFFSET = RENDER_STATE_COLORS_LAYOUT.byteOffset(groupElement("background"));
    static final long RENDER_STATE_COLORS_FOREGROUND_OFFSET = RENDER_STATE_COLORS_LAYOUT.byteOffset(groupElement("foreground"));
    static final long RENDER_STATE_COLORS_CURSOR_OFFSET = RENDER_STATE_COLORS_LAYOUT.byteOffset(groupElement("cursor"));
    static final long RENDER_STATE_COLORS_CURSOR_HAS_VALUE_OFFSET = RENDER_STATE_COLORS_LAYOUT.byteOffset(groupElement("cursor_has_value"));
    static final long RENDER_STATE_COLORS_PALETTE_OFFSET = RENDER_STATE_COLORS_LAYOUT.byteOffset(groupElement("palette"));

    static final MemoryLayout SIZE_REPORT_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("rows"),
        ValueLayout.JAVA_SHORT.withName("columns"),
        ValueLayout.JAVA_INT.withName("cell_width"),
        ValueLayout.JAVA_INT.withName("cell_height")
    );
    static final long SIZE_REPORT_ROWS_OFFSET = SIZE_REPORT_LAYOUT.byteOffset(groupElement("rows"));
    static final long SIZE_REPORT_COLUMNS_OFFSET = SIZE_REPORT_LAYOUT.byteOffset(groupElement("columns"));
    static final long SIZE_REPORT_CELL_WIDTH_OFFSET = SIZE_REPORT_LAYOUT.byteOffset(groupElement("cell_width"));
    static final long SIZE_REPORT_CELL_HEIGHT_OFFSET = SIZE_REPORT_LAYOUT.byteOffset(groupElement("cell_height"));

    static final MemoryLayout TERMINAL_SCROLLBAR_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("total"),
        ValueLayout.JAVA_LONG.withName("offset"),
        ValueLayout.JAVA_LONG.withName("len")
    );
    static final long TERMINAL_SCROLLBAR_TOTAL_OFFSET = TERMINAL_SCROLLBAR_LAYOUT.byteOffset(groupElement("total"));
    static final long TERMINAL_SCROLLBAR_OFFSET_OFFSET = TERMINAL_SCROLLBAR_LAYOUT.byteOffset(groupElement("offset"));
    static final long TERMINAL_SCROLLBAR_LEN_OFFSET = TERMINAL_SCROLLBAR_LAYOUT.byteOffset(groupElement("len"));

    static final MemoryLayout STYLE_COLOR_VALUE_LAYOUT = MemoryLayout.unionLayout(
        ValueLayout.JAVA_LONG.withName("_padding"),
        RGB_LAYOUT.withName("rgb"),
        ValueLayout.JAVA_BYTE.withName("palette")
    );
    static final MemoryLayout STYLE_COLOR_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("tag"),
        ValueLayout.JAVA_INT.withName("_pad0"),
        STYLE_COLOR_VALUE_LAYOUT.withName("value")
    );
    static final long STYLE_COLOR_TAG_OFFSET = STYLE_COLOR_LAYOUT.byteOffset(groupElement("tag"));
    static final long STYLE_COLOR_PALETTE_OFFSET = STYLE_COLOR_LAYOUT.byteOffset(groupElement("value"), groupElement("palette"));
    static final long STYLE_COLOR_RGB_OFFSET = STYLE_COLOR_LAYOUT.byteOffset(groupElement("value"), groupElement("rgb"));

    static final MemoryLayout STYLE_LAYOUT = MemoryLayout.structLayout(
        NativeRuntime.SIZE_T_LAYOUT.withName("size"),
        STYLE_COLOR_LAYOUT.withName("fg_color"),
        STYLE_COLOR_LAYOUT.withName("bg_color"),
        STYLE_COLOR_LAYOUT.withName("underline_color"),
        ValueLayout.JAVA_BOOLEAN.withName("bold"),
        ValueLayout.JAVA_BOOLEAN.withName("italic"),
        ValueLayout.JAVA_BOOLEAN.withName("faint"),
        ValueLayout.JAVA_BOOLEAN.withName("blink"),
        ValueLayout.JAVA_BOOLEAN.withName("inverse"),
        ValueLayout.JAVA_BOOLEAN.withName("invisible"),
        ValueLayout.JAVA_BOOLEAN.withName("strikethrough"),
        ValueLayout.JAVA_BOOLEAN.withName("overline"),
        ValueLayout.JAVA_INT.withName("underline"),
        ValueLayout.JAVA_INT.withName("_tail_pad")
    );
    static final long STYLE_SIZE_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("size"));
    static final long STYLE_FG_COLOR_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("fg_color"));
    static final long STYLE_BG_COLOR_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("bg_color"));
    static final long STYLE_UNDERLINE_COLOR_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("underline_color"));
    static final long STYLE_BOLD_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("bold"));
    static final long STYLE_ITALIC_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("italic"));
    static final long STYLE_FAINT_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("faint"));
    static final long STYLE_BLINK_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("blink"));
    static final long STYLE_INVERSE_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("inverse"));
    static final long STYLE_INVISIBLE_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("invisible"));
    static final long STYLE_STRIKETHROUGH_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("strikethrough"));
    static final long STYLE_OVERLINE_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("overline"));
    static final long STYLE_UNDERLINE_OFFSET = STYLE_LAYOUT.byteOffset(groupElement("underline"));

    static final MemoryLayout POINT_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("tag"),
        ValueLayout.JAVA_INT.withName("_pad0"),
        MemoryLayout.unionLayout(
            MemoryLayout.sequenceLayout(2, ValueLayout.JAVA_LONG).withName("_padding"),
            MemoryLayout.structLayout(
                ValueLayout.JAVA_SHORT.withName("x"),
                ValueLayout.JAVA_SHORT.withName("_pad0"),
                ValueLayout.JAVA_INT.withName("y")
            ).withName("coordinate")
        ).withName("value")
    );
    static final long POINT_TAG_OFFSET = POINT_LAYOUT.byteOffset(groupElement("tag"));
    static final long POINT_X_OFFSET = POINT_LAYOUT.byteOffset(groupElement("value"), groupElement("coordinate"), groupElement("x"));
    static final long POINT_Y_OFFSET = POINT_LAYOUT.byteOffset(groupElement("value"), groupElement("coordinate"), groupElement("y"));

    static final MemoryLayout GRID_REF_LAYOUT = MemoryLayout.structLayout(
        NativeRuntime.SIZE_T_LAYOUT.withName("size"),
        C_POINTER.withName("node"),
        ValueLayout.JAVA_SHORT.withName("x"),
        ValueLayout.JAVA_SHORT.withName("y"),
        ValueLayout.JAVA_INT.withName("_tail_pad")
    );
    static final long GRID_REF_SIZE_OFFSET = GRID_REF_LAYOUT.byteOffset(groupElement("size"));

    final MethodHandle ghosttyTerminalNew;
    final MethodHandle ghosttyTerminalFree;
    final MethodHandle ghosttyTerminalReset;
    final MethodHandle ghosttyTerminalResize;
    final MethodHandle ghosttyTerminalSet;
    final MethodHandle ghosttyTerminalVtWrite;
    final MethodHandle ghosttyTerminalScrollViewport;
    final MethodHandle ghosttyTerminalModeGet;
    final MethodHandle ghosttyTerminalModeSet;
    final MethodHandle ghosttyTerminalGet;
    final MethodHandle ghosttyTerminalGridRef;
    final MethodHandle ghosttyGridRefCell;
    final MethodHandle ghosttyGridRefRow;
    final MethodHandle ghosttyGridRefGraphemes;
    final MethodHandle ghosttyGridRefHyperlinkUri;
    final MethodHandle ghosttyGridRefStyle;
    final MethodHandle ghosttyCellGet;
    final MethodHandle ghosttyRowGet;
    final MethodHandle ghosttyRenderStateNew;
    final MethodHandle ghosttyRenderStateFree;
    final MethodHandle ghosttyRenderStateUpdate;
    final MethodHandle ghosttyRenderStateGet;
    final MethodHandle ghosttyRenderStateSet;
    final MethodHandle ghosttyRenderStateColorsGet;
    final MethodHandle ghosttyRenderStateRowIteratorNew;
    final MethodHandle ghosttyRenderStateRowIteratorFree;
    final MethodHandle ghosttyRenderStateRowIteratorNext;
    final MethodHandle ghosttyRenderStateRowGet;
    final MethodHandle ghosttyRenderStateRowSet;
    final MethodHandle ghosttyRenderStateRowCellsNew;
    final MethodHandle ghosttyRenderStateRowCellsSelect;
    final MethodHandle ghosttyRenderStateRowCellsNext;
    final MethodHandle ghosttyRenderStateRowCellsGet;
    final MethodHandle ghosttyRenderStateRowCellsFree;

    NativeTerminalBindings(SymbolLookup lookup) {
        ghosttyTerminalNew = NativeRuntime.bind(lookup, "ghostty_terminal_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER, TERMINAL_OPTIONS_LAYOUT));
        ghosttyTerminalFree = NativeRuntime.bind(lookup, "ghostty_terminal_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyTerminalReset = NativeRuntime.bind(lookup, "ghostty_terminal_reset", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyTerminalResize = NativeRuntime.bind(lookup, "ghostty_terminal_resize", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        ghosttyTerminalSet = NativeRuntime.bind(lookup, "ghostty_terminal_set", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyTerminalVtWrite = NativeRuntime.bind(lookup, "ghostty_terminal_vt_write", FunctionDescriptor.ofVoid(C_POINTER, C_POINTER, NativeRuntime.SIZE_T_LAYOUT));
        ghosttyTerminalScrollViewport = NativeRuntime.bind(lookup, "ghostty_terminal_scroll_viewport", FunctionDescriptor.ofVoid(C_POINTER, SCROLL_VIEWPORT_LAYOUT));
        ghosttyTerminalModeGet = NativeRuntime.bind(lookup, "ghostty_terminal_mode_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_SHORT, C_POINTER));
        ghosttyTerminalModeSet = NativeRuntime.bind(lookup, "ghostty_terminal_mode_set", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_SHORT, ValueLayout.JAVA_BOOLEAN));
        ghosttyTerminalGet = NativeRuntime.bind(lookup, "ghostty_terminal_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyTerminalGridRef = NativeRuntime.bind(lookup, "ghostty_terminal_grid_ref", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, POINT_LAYOUT, C_POINTER));
        ghosttyGridRefCell = NativeRuntime.bind(lookup, "ghostty_grid_ref_cell", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyGridRefRow = NativeRuntime.bind(lookup, "ghostty_grid_ref_row", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyGridRefGraphemes = NativeRuntime.bind(lookup, "ghostty_grid_ref_graphemes", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER, NativeRuntime.SIZE_T_LAYOUT, C_POINTER));
        ghosttyGridRefHyperlinkUri = NativeRuntime.bind(lookup, "ghostty_grid_ref_hyperlink_uri", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER, NativeRuntime.SIZE_T_LAYOUT, C_POINTER));
        ghosttyGridRefStyle = NativeRuntime.bind(lookup, "ghostty_grid_ref_style", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyCellGet = NativeRuntime.bind(lookup, "ghostty_cell_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyRowGet = NativeRuntime.bind(lookup, "ghostty_row_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyRenderStateNew = NativeRuntime.bind(lookup, "ghostty_render_state_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateFree = NativeRuntime.bind(lookup, "ghostty_render_state_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyRenderStateUpdate = NativeRuntime.bind(lookup, "ghostty_render_state_update", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateGet = NativeRuntime.bind(lookup, "ghostty_render_state_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyRenderStateSet = NativeRuntime.bind(lookup, "ghostty_render_state_set", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyRenderStateColorsGet = NativeRuntime.bind(lookup, "ghostty_render_state_colors_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateRowIteratorNew = NativeRuntime.bind(lookup, "ghostty_render_state_row_iterator_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateRowIteratorFree = NativeRuntime.bind(lookup, "ghostty_render_state_row_iterator_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyRenderStateRowIteratorNext = NativeRuntime.bind(lookup, "ghostty_render_state_row_iterator_next", FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, C_POINTER));
        ghosttyRenderStateRowGet = NativeRuntime.bind(lookup, "ghostty_render_state_row_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyRenderStateRowSet = NativeRuntime.bind(lookup, "ghostty_render_state_row_set", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyRenderStateRowCellsNew = NativeRuntime.bind(lookup, "ghostty_render_state_row_cells_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateRowCellsSelect = NativeRuntime.bind(lookup, "ghostty_render_state_row_cells_select", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_SHORT));
        ghosttyRenderStateRowCellsNext = NativeRuntime.bind(lookup, "ghostty_render_state_row_cells_next", FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, C_POINTER));
        ghosttyRenderStateRowCellsGet = NativeRuntime.bind(lookup, "ghostty_render_state_row_cells_get", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, ValueLayout.JAVA_INT, C_POINTER));
        ghosttyRenderStateRowCellsFree = NativeRuntime.bind(lookup, "ghostty_render_state_row_cells_free", FunctionDescriptor.ofVoid(C_POINTER));
    }

    public TerminalSession open(
        TerminalConfig config,
        io.github.vlaaad.ghostty.PtyWriter ptyWriter,
        io.github.vlaaad.ghostty.TerminalQueries queries,
        io.github.vlaaad.ghostty.TerminalEvents events
    ) {
        return new NativeTerminalSession(this, config, ptyWriter, queries, events);
    }
}
