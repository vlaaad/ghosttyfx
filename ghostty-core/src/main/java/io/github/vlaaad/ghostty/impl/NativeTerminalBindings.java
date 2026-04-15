package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalSession;

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

    static final int DATA_TITLE = 12;

    static final int COLOR_SCHEME_LIGHT = 0;
    static final int COLOR_SCHEME_DARK = 1;
    static final int SCROLL_TOP = 0;
    static final int SCROLL_BOTTOM = 1;
    static final int SCROLL_DELTA = 2;

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
    final MethodHandle ghosttyRenderStateNew;
    final MethodHandle ghosttyRenderStateFree;
    final MethodHandle ghosttyRenderStateRowIteratorNew;
    final MethodHandle ghosttyRenderStateRowIteratorFree;
    final MethodHandle ghosttyRenderStateRowCellsNew;
    final MethodHandle ghosttyRenderStateRowCellsFree;
    private final NativeFrameSnapshotBindings frameSnapshotBindings;
    private final String ghosttyVtLibraryPath;

    NativeTerminalBindings(
        SymbolLookup lookup,
        NativeFrameSnapshotBindings frameSnapshotBindings,
        String ghosttyVtLibraryPath
    ) {
        this.frameSnapshotBindings = frameSnapshotBindings;
        this.ghosttyVtLibraryPath = ghosttyVtLibraryPath;
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
        ghosttyRenderStateNew = NativeRuntime.bind(lookup, "ghostty_render_state_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateFree = NativeRuntime.bind(lookup, "ghostty_render_state_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyRenderStateRowIteratorNew = NativeRuntime.bind(lookup, "ghostty_render_state_row_iterator_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateRowIteratorFree = NativeRuntime.bind(lookup, "ghostty_render_state_row_iterator_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyRenderStateRowCellsNew = NativeRuntime.bind(lookup, "ghostty_render_state_row_cells_new", FunctionDescriptor.of(ValueLayout.JAVA_INT, C_POINTER, C_POINTER));
        ghosttyRenderStateRowCellsFree = NativeRuntime.bind(lookup, "ghostty_render_state_row_cells_free", FunctionDescriptor.ofVoid(C_POINTER));
    }

    public TerminalSession open(
        TerminalConfig config,
        io.github.vlaaad.ghostty.PtyWriter ptyWriter,
        io.github.vlaaad.ghostty.TerminalQueries queries,
        io.github.vlaaad.ghostty.TerminalEvents events
    ) {
        return new NativeTerminalSession(this, frameSnapshotBindings, ghosttyVtLibraryPath, config, ptyWriter, queries, events);
    }
}
