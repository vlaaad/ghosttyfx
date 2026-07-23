package io.github.vlaaad.ghosttyfx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.MatchResult;

import io.github.vlaaad.ghostty.bindings.GhosttyBuffer;
import io.github.vlaaad.ghostty.bindings.GhosttyColorRgb;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributes;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesPrimary;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesSecondary;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesTertiary;
import io.github.vlaaad.ghostty.bindings.GhosttyGridRef;
import io.github.vlaaad.ghostty.bindings.GhosttyKittyGraphicsPlacementRenderInfo;
import io.github.vlaaad.ghostty.bindings.GhosttyMouseEncoderSize;
import io.github.vlaaad.ghostty.bindings.GhosttyMousePosition;
import io.github.vlaaad.ghostty.bindings.GhosttyPoint;
import io.github.vlaaad.ghostty.bindings.GhosttyPointCoordinate;
import io.github.vlaaad.ghostty.bindings.GhosttyPointValue;
import io.github.vlaaad.ghostty.bindings.GhosttyRenderStateColors;
import io.github.vlaaad.ghostty.bindings.GhosttySelection;
import io.github.vlaaad.ghostty.bindings.GhosttySelectionGestureGeometry;
import io.github.vlaaad.ghostty.bindings.GhosttySizeReportSize;
import io.github.vlaaad.ghostty.bindings.GhosttyString;
import io.github.vlaaad.ghostty.bindings.GhosttyStyle;
import io.github.vlaaad.ghostty.bindings.GhosttyStyleColor;
import io.github.vlaaad.ghostty.bindings.GhosttyStyleColorValue;
import io.github.vlaaad.ghostty.bindings.GhosttySurfacePosition;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalBellFn;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalColorSchemeFn;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalDeviceAttributesFn;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalOptions;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalPwdChangedFn;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollViewport;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollViewportValue;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollbar;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalSelectionFormatOptions;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalSizeFn;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalTitleChangedFn;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalWritePtyFn;
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalXtversionFn;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

final class TerminalSession implements AutoCloseable {

    private static final int GHOSTTY_SUCCESS = 0;
    private static final int MAX_GRAPHEME_CODEPOINTS = 16;
    private static final int KEY_BUFFER_SIZE = 256;
    private static final int CURSOR_STYLE_BAR = 0;
    private static final int CURSOR_STYLE_UNDERLINE = 2;
    private static final int CURSOR_STYLE_BLOCK_HOLLOW = 3;
    private static final int PALETTE_SIZE = 256;
    private static final int MAX_GHOSTTY_DIMENSION = 0xFFFF;
    private static final long INITIAL_MAX_SCROLLBACK = 10_000_000;
    private static final long KITTY_IMAGE_STORAGE_LIMIT = 128L * 1024 * 1024;
    private static final long KITTY_APC_MAX_BYTES = 65L * 1024 * 1024;
    private static final double BLOCK_CURSOR_ALPHA = 0.5;
    private static final int KITTY_BELOW_BACKGROUND_Z = Integer.MIN_VALUE / 2;
    private static final byte[] XTVERSION_BYTES = "ghosttyfx".getBytes(StandardCharsets.UTF_8);
    private static final Comparator<KittyPlacement> KITTY_PLACEMENT_ORDER = (left, right) -> {
        var z = Integer.compare(left.z(), right.z());
        return z != 0 ? z : Integer.compareUnsigned(left.imageId(), right.imageId());
    };
    private static final long SELECTION_REPEAT_INTERVAL_NS = 500_000_000L;
    private static final Allocator RENDER_STATE_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_render_state_new(allocator, out);
        }
    };
    private static final Allocator RENDER_STATE_ROW_ITERATOR_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_render_state_row_iterator_new(allocator, out);
        }
    };
    private static final Allocator RENDER_STATE_ROW_CELLS_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_render_state_row_cells_new(allocator, out);
        }
    };
    private static final Allocator KITTY_PLACEMENT_ITERATOR_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_kitty_graphics_placement_iterator_new(allocator, out);
        }
    };
    private static final Allocator KEY_ENCODER_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_key_encoder_new(allocator, out);
        }
    };
    private static final Allocator KEY_EVENT_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_key_event_new(allocator, out);
        }
    };
    private static final Allocator MOUSE_ENCODER_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_mouse_encoder_new(allocator, out);
        }
    };
    private static final Allocator MOUSE_EVENT_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_mouse_event_new(allocator, out);
        }
    };
    private static final Allocator SELECTION_GESTURE_ALLOCATOR = new Allocator() {
        @Override
        public int allocate(MemorySegment allocator, MemorySegment out) {
            return ghostty_vt_h.ghostty_selection_gesture_new(allocator, out);
        }
    };

    private final AtomicBoolean closed = new AtomicBoolean();
    private final Arena callbackArena = Arena.ofShared();
    private final MemorySegment terminal;
    private final MemorySegment renderState;
    private final MemorySegment rowIterator;
    private final MemorySegment rowCells;
    private final MemorySegment kittyPlacementIterator;
    private final MemorySegment keyEncoder;
    private final MemorySegment keyEvent;
    private final MemorySegment mouseEncoder;
    private final MemorySegment mouseEvent;
    private final MemorySegment selectionGesture;
    private final MemorySegment xtversionString;
    private final ArrayList<Color> builtInPalette = new ArrayList<>(PALETTE_SIZE);
    private final Map<Long, CachedKittyImage> kittyImageCache = new HashMap<>();
    private final Consumer<byte[]> terminalInput;
    private final Consumer<String> titleChanged;
    private final Consumer<String> pwdChanged;
    private final Runnable bell;
    private Size size;
    private int physicalCellWidthPx;
    private int physicalCellHeightPx;
    private long kittyStorageGeneration;
    private CachedLinks cachedLinks;

    TerminalSession(
            int initialColumns,
            int initialRows,
            TerminalView.FontMetrics initialFontMetrics,
            Consumer<byte[]> terminalInput,
            Consumer<String> titleChanged,
            Consumer<String> pwdChanged,
            Runnable bell) {
        this.terminalInput = terminalInput;
        this.titleChanged = titleChanged;
        this.pwdChanged = pwdChanged;
        this.bell = bell;
        size = new Size(
                initialColumns,
                initialRows,
                initialColumns * initialFontMetrics.cellWidthPx(),
                initialRows * initialFontMetrics.cellHeightPx());
        physicalCellWidthPx = initialFontMetrics.cellWidthPx();
        physicalCellHeightPx = initialFontMetrics.cellHeightPx();
        xtversionString = GhosttyString.allocate(callbackArena);
        GhosttyString.ptr(xtversionString, callbackArena.allocateFrom(ValueLayout.JAVA_BYTE, XTVERSION_BYTES));
        GhosttyString.len(xtversionString, XTVERSION_BYTES.length);

        try (var arena = Arena.ofConfined()) {
            var terminalPointer = arena.allocate(ValueLayout.ADDRESS);
            var options = GhosttyTerminalOptions.allocate(arena);
            GhosttyTerminalOptions.cols(options, (short) initialColumns);
            GhosttyTerminalOptions.rows(options, (short) initialRows);
            GhosttyTerminalOptions.max_scrollback(options, INITIAL_MAX_SCROLLBACK);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_new(MemorySegment.NULL, terminalPointer, options),
                    "ghostty_terminal_new");
            terminal = terminalPointer.get(ValueLayout.ADDRESS, 0);

            renderState = newAddress(arena, "ghostty_render_state_new", RENDER_STATE_ALLOCATOR);
            rowIterator = newAddress(arena, "ghostty_render_state_row_iterator_new", RENDER_STATE_ROW_ITERATOR_ALLOCATOR);
            rowCells = newAddress(arena, "ghostty_render_state_row_cells_new", RENDER_STATE_ROW_CELLS_ALLOCATOR);
            kittyPlacementIterator = newAddress(arena, "ghostty_kitty_graphics_placement_iterator_new", KITTY_PLACEMENT_ITERATOR_ALLOCATOR);
            keyEncoder = newAddress(arena, "ghostty_key_encoder_new", KEY_ENCODER_ALLOCATOR);
            keyEvent = newAddress(arena, "ghostty_key_event_new", KEY_EVENT_ALLOCATOR);
            mouseEncoder = newAddress(arena, "ghostty_mouse_encoder_new", MOUSE_ENCODER_ALLOCATOR);
            mouseEvent = newAddress(arena, "ghostty_mouse_event_new", MOUSE_EVENT_ALLOCATOR);
            selectionGesture = newAddress(arena, "ghostty_selection_gesture_new", SELECTION_GESTURE_ALLOCATOR);

            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_resize(
                            terminal,
                            (short) initialColumns,
                            (short) initialRows,
                            initialFontMetrics.cellWidthPx(),
                            initialFontMetrics.cellHeightPx()),
                    "ghostty_terminal_resize");
            configureKittyGraphics(arena);
            setDefaultCursorStyle();
            setCursorBlinking(true);
            var palette = GhosttyColorRgb.allocateArray(PALETTE_SIZE, arena);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_get(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_DATA_COLOR_PALETTE_DEFAULT(),
                            palette),
                    "ghostty_terminal_get(color_palette_default)");
            for (var i = 0; i < PALETTE_SIZE; i++) {
                builtInPalette.add(toFxColor(GhosttyColorRgb.asSlice(palette, i)));
            }
        }
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_WRITE_PTY(),
                        GhosttyTerminalWritePtyFn.allocate(this::writePty, callbackArena)),
                "ghostty_terminal_set");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_SIZE(),
                        GhosttyTerminalSizeFn.allocate(this::reportSize, callbackArena)),
                "ghostty_terminal_set");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES(),
                        GhosttyTerminalDeviceAttributesFn.allocate(this::reportDeviceAttributes, callbackArena)),
                "ghostty_terminal_set");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_COLOR_SCHEME(),
                        GhosttyTerminalColorSchemeFn.allocate(this::reportColorScheme, callbackArena)),
                "ghostty_terminal_set");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_XTVERSION(),
                        GhosttyTerminalXtversionFn.allocate(this::reportXtversion, callbackArena)),
                "ghostty_terminal_set");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_TITLE_CHANGED(),
                        GhosttyTerminalTitleChangedFn.allocate(this::reportTitleChanged, callbackArena)),
                "ghostty_terminal_set");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_PWD_CHANGED(),
                        GhosttyTerminalPwdChangedFn.allocate(this::reportPwdChanged, callbackArena)),
                "ghostty_terminal_set");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_BELL(),
                        GhosttyTerminalBellFn.allocate((_, _) -> this.bell.run(), callbackArena)),
                "ghostty_terminal_set");
        updateRenderState();
    }

    void applyTheme(TerminalTheme theme) {
        try (var arena = Arena.ofConfined()) {
            var background = toNativeColor(theme.background(), arena);
            var foreground = toNativeColor(theme.foreground(), arena);
            var cursor = toNativeColor(theme.cursorColor(), arena);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_set(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND(),
                            background),
                    "ghostty_terminal_set(color_background)");
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_set(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND(),
                            foreground),
                    "ghostty_terminal_set(color_foreground)");
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_set(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_OPT_COLOR_CURSOR(),
                            cursor),
                    "ghostty_terminal_set(color_cursor)");
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_set(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_OPT_COLOR_PALETTE(),
                            theme.palette().isEmpty() ? MemorySegment.NULL : nativePalette(theme, arena)),
                    "ghostty_terminal_set(color_palette)");
        }
        updateRenderState();
    }

    void setCursorBlinking(boolean value) {
        try (var arena = Arena.ofConfined()) {
            var blink = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            blink.set(ValueLayout.JAVA_BOOLEAN, 0, value);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_set(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK(),
                            blink),
                    "ghostty_terminal_set(default_cursor_blink)");
        }
        updateRenderState();
    }

    private void setDefaultCursorStyle() {
        try (var arena = Arena.ofConfined()) {
            var style = arena.allocate(ValueLayout.JAVA_INT);
            style.set(ValueLayout.JAVA_INT, 0, ghostty_vt_h.GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK());
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_set(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE(),
                            style),
                    "ghostty_terminal_set(default_cursor_style)");
        }
    }

    private void configureKittyGraphics(Arena arena) {
        var storageLimit = arena.allocate(ValueLayout.JAVA_LONG);
        storageLimit.set(ValueLayout.JAVA_LONG, 0, KITTY_IMAGE_STORAGE_LIMIT);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT(),
                        storageLimit),
                "ghostty_terminal_set(kitty_image_storage_limit)");

        var disabled = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        disabled.set(ValueLayout.JAVA_BOOLEAN, 0, false);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_FILE(),
                        disabled),
                "ghostty_terminal_set(kitty_image_medium_file)");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_TEMP_FILE(),
                        disabled),
                "ghostty_terminal_set(kitty_image_medium_temp_file)");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_MEDIUM_SHARED_MEM(),
                        disabled),
                "ghostty_terminal_set(kitty_image_medium_shared_mem)");

        var apcLimit = arena.allocate(ValueLayout.JAVA_LONG);
        apcLimit.set(ValueLayout.JAVA_LONG, 0, KITTY_APC_MAX_BYTES);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_APC_MAX_BYTES_KITTY(),
                        apcLimit),
                "ghostty_terminal_set(apc_max_bytes_kitty)");
    }

    private void restoreKittyImageStorageLimit() {
        try (var arena = Arena.ofConfined()) {
            var storageLimit = arena.allocate(ValueLayout.JAVA_LONG);
            storageLimit.set(ValueLayout.JAVA_LONG, 0, KITTY_IMAGE_STORAGE_LIMIT);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_set(
                            terminal,
                            ghostty_vt_h.GHOSTTY_TERMINAL_OPT_KITTY_IMAGE_STORAGE_LIMIT(),
                            storageLimit),
                    "ghostty_terminal_set(kitty_image_storage_limit)");
        }
    }

    private void writePty(MemorySegment terminal, MemorySegment userdata, MemorySegment data, long length) {
        if (length == 0) {
            return;
        }
        terminalInput.accept(data.reinterpret(length).toArray(ValueLayout.JAVA_BYTE));
    }

    private boolean reportSize(MemorySegment terminal, MemorySegment userdata, MemorySegment outSize) {
        var sizeReport = outSize.reinterpret(GhosttySizeReportSize.sizeof());
        var currentSize = size;
        GhosttySizeReportSize.rows(sizeReport, (short) currentSize.rows());
        GhosttySizeReportSize.columns(sizeReport, (short) currentSize.columns());
        GhosttySizeReportSize.cell_width(sizeReport, physicalCellWidthPx);
        GhosttySizeReportSize.cell_height(sizeReport, physicalCellHeightPx);
        return true;
    }

    private boolean reportDeviceAttributes(MemorySegment terminal, MemorySegment userdata, MemorySegment outAttributes) {
        var attributes = outAttributes.reinterpret(GhosttyDeviceAttributes.sizeof());
        var primary = GhosttyDeviceAttributes.primary(attributes);
        GhosttyDeviceAttributesPrimary.conformance_level(primary, (short) ghostty_vt_h.GHOSTTY_DA_CONFORMANCE_VT220());
        GhosttyDeviceAttributesPrimary.features(primary, 0, (short) ghostty_vt_h.GHOSTTY_DA_FEATURE_COLUMNS_132());
        GhosttyDeviceAttributesPrimary.features(primary, 1, (short) ghostty_vt_h.GHOSTTY_DA_FEATURE_SELECTIVE_ERASE());
        GhosttyDeviceAttributesPrimary.features(primary, 2, (short) ghostty_vt_h.GHOSTTY_DA_FEATURE_ANSI_COLOR());
        GhosttyDeviceAttributesPrimary.num_features(primary, 3);

        var secondary = GhosttyDeviceAttributes.secondary(attributes);
        GhosttyDeviceAttributesSecondary.device_type(secondary, (short) ghostty_vt_h.GHOSTTY_DA_DEVICE_TYPE_VT220());
        GhosttyDeviceAttributesSecondary.firmware_version(secondary, (short) 1);
        GhosttyDeviceAttributesSecondary.rom_cartridge(secondary, (short) 0);

        var tertiary = GhosttyDeviceAttributes.tertiary(attributes);
        GhosttyDeviceAttributesTertiary.unit_id(tertiary, 0);
        return true;
    }

    private boolean reportColorScheme(MemorySegment terminal, MemorySegment userdata, MemorySegment outScheme) {
        var scheme = Platform.getPreferences().getColorScheme();
        if (scheme == null) {
            return false;
        }

        outScheme.set(
                ValueLayout.JAVA_INT,
                0,
                scheme == ColorScheme.DARK
                        ? ghostty_vt_h.GHOSTTY_COLOR_SCHEME_DARK()
                        : ghostty_vt_h.GHOSTTY_COLOR_SCHEME_LIGHT());
        return true;
    }

    private MemorySegment reportXtversion(MemorySegment terminal, MemorySegment userdata) {
        return xtversionString;
    }

    private void reportTitleChanged(MemorySegment terminal, MemorySegment userdata) {
        try (var arena = Arena.ofConfined()) {
            var title = GhosttyString.allocate(arena);
            if (ghostty_vt_h.ghostty_terminal_get(
                    this.terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_TITLE(),
                    title) == GHOSTTY_SUCCESS) {
                titleChanged.accept(toJavaString(title));
            }
        }
    }

    private void reportPwdChanged(MemorySegment terminal, MemorySegment userdata) {
        try (var arena = Arena.ofConfined()) {
            var pwd = GhosttyString.allocate(arena);
            if (ghostty_vt_h.ghostty_terminal_get(
                    this.terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_PWD(),
                    pwd) == GHOSTTY_SUCCESS) {
                pwdChanged.accept(toJavaString(pwd));
            }
        }
    }

    private static String toJavaString(MemorySegment value) {
        var length = GhosttyString.len(value);
        if (length == 0) {
            return "";
        }
        var pointer = GhosttyString.ptr(value);
        if (pointer.equals(MemorySegment.NULL)) {
            return "";
        }
        return new String(pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        try (callbackArena) {
            ghostty_vt_h.ghostty_selection_gesture_free(selectionGesture, terminal);
            ghostty_vt_h.ghostty_mouse_event_free(mouseEvent);
            ghostty_vt_h.ghostty_mouse_encoder_free(mouseEncoder);
            ghostty_vt_h.ghostty_key_event_free(keyEvent);
            ghostty_vt_h.ghostty_key_encoder_free(keyEncoder);
            ghostty_vt_h.ghostty_render_state_row_cells_free(rowCells);
            ghostty_vt_h.ghostty_render_state_row_iterator_free(rowIterator);
            ghostty_vt_h.ghostty_kitty_graphics_placement_iterator_free(kittyPlacementIterator);
            ghostty_vt_h.ghostty_render_state_free(renderState);
            ghostty_vt_h.ghostty_terminal_free(terminal);
            kittyImageCache.clear();
        }
    }

    Size resize(
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx,
            double scaleX,
            double scaleY) {
        if (widthPx <= 0 || heightPx <= 0) {
            return null;
        }

        var cellWidthPx = Math.clamp((int) Math.round(metrics.cellWidthPx() * scaleX), 1, MAX_GHOSTTY_DIMENSION);
        var cellHeightPx = Math.clamp((int) Math.round(metrics.cellHeightPx() * scaleY), 1, MAX_GHOSTTY_DIMENSION);
        var columns = Math.clamp(
                (int) Math.floor(Math.max(0, widthPx - scrollbarReservedWidthPx) / metrics.cellWidthPx()),
                1,
                MAX_GHOSTTY_DIMENSION / cellWidthPx);
        var rows = Math.clamp(
                (int) Math.floor(heightPx / metrics.cellHeightPx()),
                1,
                MAX_GHOSTTY_DIMENSION / cellHeightPx);
        clearLinkCache();
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_resize(
                        terminal,
                        (short) columns,
                        (short) rows,
                        cellWidthPx,
                        cellHeightPx),
                "ghostty_terminal_resize");
        size = new Size(columns, rows, columns * cellWidthPx, rows * cellHeightPx);
        physicalCellWidthPx = cellWidthPx;
        physicalCellHeightPx = cellHeightPx;
        updateRenderState();
        return size;
    }

    void writeToTerminal(byte[] bytes) {
        try (var arena = Arena.ofConfined()) {
            var nativeBytes = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
            try {
                ghostty_vt_h.ghostty_terminal_vt_write(terminal, nativeBytes, bytes.length);
            } finally {
                clearLinkCache();
            }
        }
        restoreKittyImageStorageLimit();
        updateRenderState();
    }

    byte[] encodeFocus(boolean focused) {
        try (var arena = Arena.ofConfined()) {
            var buffer = arena.allocate(8);
            var written = arena.allocate(ValueLayout.JAVA_LONG);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_focus_encode(
                            focused ? ghostty_vt_h.GHOSTTY_FOCUS_GAINED() : ghostty_vt_h.GHOSTTY_FOCUS_LOST(),
                            buffer,
                            buffer.byteSize(),
                            written),
                    "ghostty_focus_encode");
            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            return length == 0
                    ? new byte[0]
                    : buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    void scrollViewportBy(long deltaRows) {
        if (deltaRows == 0) {
            return;
        }

        try (var arena = Arena.ofConfined()) {
            var behavior = GhosttyTerminalScrollViewport.allocate(arena);
            GhosttyTerminalScrollViewport.tag(behavior, ghostty_vt_h.GHOSTTY_SCROLL_VIEWPORT_DELTA());
            GhosttyTerminalScrollViewportValue.delta(GhosttyTerminalScrollViewport.value(behavior), deltaRows);
            ghostty_vt_h.ghostty_terminal_scroll_viewport(terminal, behavior);
        }
        updateRenderState();
    }

    void scrollViewportTo(long row) {
        try (var arena = Arena.ofConfined()) {
            var behavior = GhosttyTerminalScrollViewport.allocate(arena);
            GhosttyTerminalScrollViewport.tag(behavior, ghostty_vt_h.GHOSTTY_SCROLL_VIEWPORT_ROW());
            GhosttyTerminalScrollViewportValue.row(GhosttyTerminalScrollViewport.value(behavior), row);
            ghostty_vt_h.ghostty_terminal_scroll_viewport(terminal, behavior);
        }
        updateRenderState();
    }

    void scrollViewportToBottom() {
        try (var arena = Arena.ofConfined()) {
            var behavior = GhosttyTerminalScrollViewport.allocate(arena);
            GhosttyTerminalScrollViewport.tag(behavior, ghostty_vt_h.GHOSTTY_SCROLL_VIEWPORT_BOTTOM());
            ghostty_vt_h.ghostty_terminal_scroll_viewport(terminal, behavior);
        }
        updateRenderState();
    }

    boolean mouseTrackingEnabled() {
        try (var arena = Arena.ofConfined()) {
            var mouseTracking = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            return ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING(),
                    mouseTracking) == GHOSTTY_SUCCESS && mouseTracking.get(ValueLayout.JAVA_BOOLEAN, 0);
        }
    }

    boolean alternateScreenActive() {
        try (var arena = Arena.ofConfined()) {
            var activeScreen = arena.allocate(ValueLayout.JAVA_INT);
            return ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN(),
                    activeScreen) == GHOSTTY_SUCCESS
                    && activeScreen.get(ValueLayout.JAVA_INT, 0) == ghostty_vt_h.GHOSTTY_TERMINAL_SCREEN_ALTERNATE();
        }
    }

    byte[] encodeMousePress(
            MouseButton button,
            double x,
            double y,
            short mods,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx,
            boolean anyButtonPressed) {
        refreshMouseEncoder(anyButtonPressed, widthPx, heightPx, metrics, scrollbarReservedWidthPx);
        return encodeMouseButton(ghostty_vt_h.GHOSTTY_MOUSE_ACTION_PRESS(), button.ghosttyButton(), x, y, mods);
    }

    byte[] encodeMouseRelease(
            MouseButton button,
            double x,
            double y,
            short mods,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx,
            boolean anyButtonPressed) {
        refreshMouseEncoder(anyButtonPressed, widthPx, heightPx, metrics, scrollbarReservedWidthPx);
        return encodeMouseButton(ghostty_vt_h.GHOSTTY_MOUSE_ACTION_RELEASE(), button.ghosttyButton(), x, y, mods);
    }

    byte[] encodeMouseMotion(
            MouseButton button,
            double x,
            double y,
            short mods,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx,
            boolean anyButtonPressed) {
        refreshMouseEncoder(anyButtonPressed, widthPx, heightPx, metrics, scrollbarReservedWidthPx);
        return encodeMouseButton(ghostty_vt_h.GHOSTTY_MOUSE_ACTION_MOTION(), button.ghosttyButton(), x, y, mods);
    }

    byte[] encodeMouseScroll(
            double x,
            double y,
            int lineDelta,
            short mods,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx) {
        var button = lineDelta > 0
                ? ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_FOUR()
                : ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_FIVE();
        var count = Math.abs(lineDelta);
        var chunks = new byte[count * 2][];
        var chunkCount = 0;
        var totalLength = 0;
        refreshMouseEncoder(false, widthPx, heightPx, metrics, scrollbarReservedWidthPx);
        for (var i = 0; i < count; i++) {
            var press = encodeMouseButton(
                    ghostty_vt_h.GHOSTTY_MOUSE_ACTION_PRESS(),
                    button,
                    x,
                    y,
                    mods);
            if (press.length > 0) {
                chunks[chunkCount++] = press;
                totalLength += press.length;
            }

            var release = encodeMouseButton(
                    ghostty_vt_h.GHOSTTY_MOUSE_ACTION_RELEASE(),
                    button,
                    x,
                    y,
                    mods);
            if (release.length > 0) {
                chunks[chunkCount++] = release;
                totalLength += release.length;
            }
        }
        if (totalLength == 0) {
            return new byte[0];
        }

        var bytes = new byte[totalLength];
        var offset = 0;
        for (var i = 0; i < chunkCount; i++) {
            var chunk = chunks[i];
            System.arraycopy(chunk, 0, bytes, offset, chunk.length);
            offset += chunk.length;
        }
        return bytes;
    }

    int viewportRowCount(int fallback, int cellHeightPx, double heightPx) {
        try (var arena = Arena.ofConfined()) {
            var rows = arena.allocate(ValueLayout.JAVA_SHORT);
            if (ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_ROWS(),
                    rows) != GHOSTTY_SUCCESS) {
                return Math.max(1, fallback > 0 ? fallback : (int) Math.floor(heightPx / cellHeightPx));
            }
            return Math.max(1, Short.toUnsignedInt(rows.get(ValueLayout.JAVA_SHORT, 0)));
        }
    }

    ScrollbarInfo scrollbarInfo(double widthPx, double heightPx, double scrollbarReservedWidthPx, double minScrollbarHeightPx) {
        try (var arena = Arena.ofConfined()) {
            var scrollbar = GhosttyTerminalScrollbar.allocate(arena);
            if (ghostty_vt_h.ghostty_terminal_get(
                    terminal,
                    ghostty_vt_h.GHOSTTY_TERMINAL_DATA_SCROLLBAR(),
                    scrollbar) != GHOSTTY_SUCCESS) {
                return null;
            }

            var total = GhosttyTerminalScrollbar.total(scrollbar);
            var visible = GhosttyTerminalScrollbar.len(scrollbar);
            if (visible <= 0) {
                return null;
            }

            var scrollableRows = Math.max(0, total - visible);
            var thumbHeight = scrollableRows == 0
                    ? 0
                    : Math.max(minScrollbarHeightPx, heightPx * ((double) visible / total));
            var thumbY = scrollableRows == 0
                    ? 0
                    : (heightPx - thumbHeight) * ((double) GhosttyTerminalScrollbar.offset(scrollbar) / scrollableRows);
            return new ScrollbarInfo(
                    total,
                    visible,
                    GhosttyTerminalScrollbar.offset(scrollbar),
                    Math.max(0, widthPx - scrollbarReservedWidthPx),
                    Math.max(0, heightPx),
                    thumbY,
                    thumbHeight);
        }
    }

    int promptRowBefore(long row) {
        var rows = totalRowCount();
        if (rows <= 0 || row <= 0) {
            return -1;
        }

        try (var arena = Arena.ofConfined()) {
            for (var currentRow = Math.toIntExact(Math.min(row - 1, rows - 1L)); currentRow >= 0; currentRow--) {
                if (rowSemanticPrompt(currentRow, arena) == ghostty_vt_h.GHOSTTY_ROW_SEMANTIC_PROMPT()) {
                    return currentRow;
                }
            }
            return -1;
        }
    }

    int promptRowAfter(long row) {
        var rows = totalRowCount();
        if (rows <= 0 || row >= rows - 1L) {
            return -1;
        }

        try (var arena = Arena.ofConfined()) {
            for (var currentRow = Math.toIntExact(Math.max(0, row + 1)); currentRow < rows; currentRow++) {
                if (rowSemanticPrompt(currentRow, arena) == ghostty_vt_h.GHOSTTY_ROW_SEMANTIC_PROMPT()) {
                    return currentRow;
                }
            }
            return -1;
        }
    }

    boolean isPromptRow(long row) {
        var rows = totalRowCount();
        if (row < 0 || row >= rows) {
            return false;
        }

        try (var arena = Arena.ofConfined()) {
            return rowSemanticPrompt(Math.toIntExact(row), arena) == ghostty_vt_h.GHOSTTY_ROW_SEMANTIC_PROMPT();
        }
    }

    boolean hasSelection() {
        try (var arena = Arena.ofConfined()) {
            return !activeSelection(arena).equals(MemorySegment.NULL);
        }
    }

    boolean clearSelection() {
        if (!hasSelection()) {
            return false;
        }

        setActiveSelection(MemorySegment.NULL);
        updateRenderState();
        return true;
    }

    boolean selectAll() {
        try (var arena = Arena.ofConfined()) {
            var selection = GhosttySelection.allocate(arena);
            GhosttySelection.size(selection, GhosttySelection.sizeof());
            var result = ghostty_vt_h.ghostty_terminal_select_all(terminal, selection);
            if (result == ghostty_vt_h.GHOSTTY_NO_VALUE()) {
                setActiveSelection(MemorySegment.NULL);
                updateRenderState();
                return false;
            }

            requireGhosttySuccess(result, "ghostty_terminal_select_all");
            setActiveSelection(selection);
        }
        updateRenderState();
        return true;
    }

    boolean adjustSelection(int adjustment) {
        try (var arena = Arena.ofConfined()) {
            var selection = activeSelection(arena);
            if (selection.equals(MemorySegment.NULL)) {
                return false;
            }

            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_terminal_selection_adjust(terminal, selection, adjustment),
                    "ghostty_terminal_selection_adjust");
            setActiveSelection(selection);
        }
        updateRenderState();
        return true;
    }

    Selection.ScreenPoint selectionFocus() {
        try (var arena = Arena.ofConfined()) {
            var selection = activeSelection(arena);
            if (selection.equals(MemorySegment.NULL)) {
                return null;
            }

            return screenPoint(GhosttySelection.end(selection), arena);
        }
    }

    String selectedText() {
        try (var arena = Arena.ofConfined()) {
            var options = GhosttyTerminalSelectionFormatOptions.allocate(arena);
            GhosttyTerminalSelectionFormatOptions.size(options, GhosttyTerminalSelectionFormatOptions.sizeof());
            GhosttyTerminalSelectionFormatOptions.emit(options, ghostty_vt_h.GHOSTTY_FORMATTER_FORMAT_PLAIN());
            GhosttyTerminalSelectionFormatOptions.unwrap(options, true);
            GhosttyTerminalSelectionFormatOptions.trim(options, true);
            GhosttyTerminalSelectionFormatOptions.selection(options, MemorySegment.NULL);

            var outputPointer = arena.allocate(ValueLayout.ADDRESS);
            var outputLength = arena.allocate(ValueLayout.JAVA_LONG);
            var result = ghostty_vt_h.ghostty_terminal_selection_format_alloc(
                    terminal,
                    MemorySegment.NULL,
                    options,
                    outputPointer,
                    outputLength);
            if (result == ghostty_vt_h.GHOSTTY_NO_VALUE()) {
                return "";
            }

            requireGhosttySuccess(result, "ghostty_terminal_selection_format_alloc");
            var length = outputLength.get(ValueLayout.JAVA_LONG, 0);
            if (length == 0) {
                return "";
            }

            var output = outputPointer.get(ValueLayout.ADDRESS, 0);
            try {
                return new String(output.reinterpret(length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
            } finally {
                ghostty_vt_h.ghostty_free(MemorySegment.NULL, output, length);
            }
        }
    }

    boolean selectionGesturePress(
            CellHit hit,
            double x,
            double y,
            TerminalView.FontMetrics metrics) {
        try (var arena = Arena.ofConfined()) {
            var event = selectionGestureEvent(arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS());
            try {
                var ref = gridRef(hit.screenPoint(), arena);
                if (ref == null) {
                    return false;
                }

                setSelectionGestureRef(event, ref);
                setSelectionGesturePosition(event, arena, x, y);
                setSelectionGestureDouble(event, arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_DISTANCE(), metrics.cellWidthPx());
                setSelectionGestureLong(event, arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_TIME_NS(), SelectionGestureClock.now());
                setSelectionGestureLong(event, arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_INTERVAL_NS(), SELECTION_REPEAT_INTERVAL_NS);
                return applySelectionGestureEvent(arena, event, true);
            } finally {
                ghostty_vt_h.ghostty_selection_gesture_event_free(event);
            }
        }
    }

    boolean selectionGestureDrag(
            CellHit hit,
            double x,
            double y,
            boolean rectangle,
            TerminalView.FontMetrics metrics,
            double heightPx) {
        try (var arena = Arena.ofConfined()) {
            var event = selectionGestureEvent(arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG());
            try {
                var ref = gridRef(hit.screenPoint(), arena);
                if (ref == null) {
                    return false;
                }

                setSelectionGestureRef(event, ref);
                setSelectionGesturePosition(event, arena, x, y);
                setSelectionGestureBoolean(event, arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_RECTANGLE(), rectangle);
                setSelectionGestureGeometry(event, arena, metrics, heightPx);
                return applySelectionGestureEvent(arena, event, false);
            } finally {
                ghostty_vt_h.ghostty_selection_gesture_event_free(event);
            }
        }
    }

    boolean selectionGestureAutoscrollTick(
            CellHit hit,
            double x,
            double y,
            boolean rectangle,
            TerminalView.FontMetrics metrics,
            double heightPx) {
        try (var arena = Arena.ofConfined()) {
            var event = selectionGestureEvent(arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_AUTOSCROLL_TICK());
            try {
                var viewport = GhosttyPointCoordinate.allocate(arena);
                GhosttyPointCoordinate.x(viewport, (short) hit.viewportX());
                GhosttyPointCoordinate.y(viewport, hit.viewportY());
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_selection_gesture_event_set(
                                event,
                                ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_VIEWPORT(),
                                viewport),
                        "ghostty_selection_gesture_event_set(viewport)");
                setSelectionGesturePosition(event, arena, x, y);
                setSelectionGestureBoolean(event, arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_RECTANGLE(), rectangle);
                setSelectionGestureGeometry(event, arena, metrics, heightPx);
                return applySelectionGestureEvent(arena, event, false);
            } finally {
                ghostty_vt_h.ghostty_selection_gesture_event_free(event);
            }
        }
    }

    void selectionGestureRelease(CellHit hit) {
        try (var arena = Arena.ofConfined()) {
            var event = selectionGestureEvent(arena, ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE());
            try {
                if (hit != null) {
                    var ref = gridRef(hit.screenPoint(), arena);
                    if (ref != null) {
                        setSelectionGestureRef(event, ref);
                    }
                }
                applySelectionGestureEvent(arena, event, false);
            } finally {
                ghostty_vt_h.ghostty_selection_gesture_event_free(event);
            }
        }
    }

    void resetSelectionGesture() {
        ghostty_vt_h.ghostty_selection_gesture_reset(selectionGesture, terminal);
    }

    boolean selectionGestureDragged() {
        try (var arena = Arena.ofConfined()) {
            var dragged = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_selection_gesture_get(
                            selectionGesture,
                            terminal,
                            ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_DATA_DRAGGED(),
                            dragged),
                    "ghostty_selection_gesture_get(dragged)");
            return dragged.get(ValueLayout.JAVA_BOOLEAN, 0);
        }
    }

    int selectionGestureClickCount() {
        try (var arena = Arena.ofConfined()) {
            var clickCount = arena.allocate(ValueLayout.JAVA_BYTE);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_selection_gesture_get(
                            selectionGesture,
                            terminal,
                            ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_DATA_CLICK_COUNT(),
                            clickCount),
                    "ghostty_selection_gesture_get(click_count)");
            return Byte.toUnsignedInt(clickCount.get(ValueLayout.JAVA_BYTE, 0));
        }
    }

    SelectionAutoscroll selectionGestureAutoscroll() {
        try (var arena = Arena.ofConfined()) {
            var autoscroll = arena.allocate(ValueLayout.JAVA_INT);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_selection_gesture_get(
                            selectionGesture,
                            terminal,
                            ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_DATA_AUTOSCROLL(),
                            autoscroll),
                    "ghostty_selection_gesture_get(autoscroll)");
            var value = autoscroll.get(ValueLayout.JAVA_INT, 0);
            if (value == ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_UP()) {
                return SelectionAutoscroll.UP;
            }
            if (value == ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_DOWN()) {
                return SelectionAutoscroll.DOWN;
            }
            return SelectionAutoscroll.NONE;
        }
    }

    SearchDocumentBuilder newSearchDocument() {
        var columns = columnCount();
        var rows = totalRowCount();
        return new SearchDocumentBuilder(columns, rows);
    }

    boolean appendSearchRows(SearchDocumentBuilder document, long budgetNs) {
        if (document.complete() || document.columns() <= 0 || document.rows() <= 0) {
            document.complete = true;
            return false;
        }

        var changed = false;
        var start = System.nanoTime();
        try (var arena = Arena.ofConfined()) {
            do {
                appendSearchRow(document, document.nextRow, arena);
                document.nextRow++;
                changed = true;
            } while (document.nextRow < document.rows()
                    && (budgetNs == Long.MAX_VALUE || System.nanoTime() - start < budgetNs));
        }
        document.complete = document.nextRow >= document.rows();
        return changed;
    }

    static SearchBatch search(SearchDocumentBuilder index, String query, int searchedUntil) {
        var searchLimit = index.text().length() - query.length() + 1;
        if (searchLimit <= searchedUntil) {
            return new SearchBatch(List.of(), searchedUntil);
        }

        var matches = new ArrayList<Selection>();
        var fromIndex = Math.max(0, searchedUntil - Math.max(0, query.length() - 1));
        while (fromIndex < searchLimit) {
            var matchIndex = regionMatchesIgnoreCase(index.text(), fromIndex, query)
                    ? fromIndex
                    : -1;
            if (matchIndex < 0) {
                fromIndex++;
                continue;
            }

            if (matchIndex >= searchedUntil) {
                var start = nearestMappedPoint(index.points(), matchIndex, 1);
                var end = nearestMappedPoint(index.points(), matchIndex + query.length() - 1, -1);
                if (start != null && end != null) {
                    matches.add(Selection.linear(start, end));
                }
            }
            fromIndex = matchIndex + Math.max(1, query.length());
        }
        return new SearchBatch(matches, searchLimit);
    }

    MatchedLinkMatcher linkMatcherAt(Selection.ScreenPoint point, List<TerminalLinkMatcher> linkMatchers) {
        if (linkMatchers.isEmpty()) {
            return null;
        }

        var line = logicalLine(point);
        if (line.text().isEmpty()) {
            return null;
        }

        for (var i = 0; i < linkMatchers.size(); i++) {
            var linkMatcher = linkMatchers.get(i);
            var matcher = linkMatcher.pattern().matcher(line.text());
            while (matcher.find()) {
                var selection = line.selection(matcher.start(), matcher.end());
                if (!selection.isEmpty() && contains(selection, point)) {
                    return new MatchedLinkMatcher(i, linkMatcher, matcher.toMatchResult(), selection);
                }
            }
        }
        return null;
    }

    List<Selection> linkMatcherSelections(List<TerminalLinkMatcher> linkMatchers, int startRow, int endRow) {
        if (linkMatchers.isEmpty()) {
            return List.of();
        }

        var rows = totalRowCount();
        if (rows <= 0) {
            return List.of();
        }

        startRow = Math.clamp(startRow, 0, rows - 1);
        endRow = Math.clamp(endRow, startRow, rows - 1);
        try (var arena = Arena.ofConfined()) {
            while (startRow > 0 && rowWrapContinuation(new Selection.ScreenPoint(0, startRow), arena)) {
                startRow--;
            }
        }

        var selections = new ArrayList<Selection>();
        for (var row = startRow; row <= endRow; row++) {
            try (var arena = Arena.ofConfined()) {
                if (row > 0 && rowWrapContinuation(new Selection.ScreenPoint(0, row), arena)) {
                    continue;
                }
            }

            var line = logicalLine(new Selection.ScreenPoint(0, row));
            if (line.text().isEmpty()) {
                continue;
            }

            for (var linkMatcher : linkMatchers) {
                var matcher = linkMatcher.pattern().matcher(line.text());
                while (matcher.find()) {
                    var selection = line.selection(matcher.start(), matcher.end());
                    if (!selection.isEmpty()) {
                        selections.add(selection);
                    }
                }
            }
        }
        return selections;
    }

    private SearchResult linkMatcherResult(
            List<TerminalLinkMatcher> linkMatchers,
            int viewportTop,
            int viewportBottom) {
        if (linkMatchers.isEmpty()) {
            clearLinkCache();
            return SearchResult.empty();
        }

        var result = cachedLinks;
        if (result == null
                || result.viewportTop() != viewportTop
                || result.viewportBottom() != viewportBottom) {
            var columns = columnCount();
            var visibleSelections = new ArrayList<Selection>();
            for (var selection : linkMatcherSelections(linkMatchers, viewportTop, viewportBottom)) {
                var normalized = selection.normalized();
                if (normalized.to().y() < viewportTop || normalized.from().y() > viewportBottom) {
                    continue;
                }
                var fromRow = Math.max(viewportTop, normalized.from().y());
                var toRow = Math.min(viewportBottom, normalized.to().y());
                visibleSelections.add(Selection.linear(
                        new Selection.ScreenPoint(
                                fromRow == normalized.from().y() ? normalized.from().x() : 0,
                                fromRow),
                        new Selection.ScreenPoint(
                                toRow == normalized.to().y() ? normalized.to().x() : Math.max(0, columns - 1),
                                toRow)));
            }
            result = new CachedLinks(
                    viewportTop,
                    viewportBottom,
                    SearchResult.append(
                            SearchResult.empty(),
                            visibleSelections,
                            columns));
            cachedLinks = result;
        }
        return result.result();
    }

    void clearLinkCache() {
        cachedLinks = null;
    }

    int columnCount() {
        try (var arena = Arena.ofConfined()) {
            var cols = arena.allocate(ValueLayout.JAVA_SHORT);
            if (ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_COLS(), cols) != GHOSTTY_SUCCESS) {
                return 0;
            }
            return Short.toUnsignedInt(cols.get(ValueLayout.JAVA_SHORT, 0));
        }
    }

    int totalRowCount() {
        try (var arena = Arena.ofConfined()) {
            var rows = arena.allocate(ValueLayout.JAVA_LONG);
            if (ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_TOTAL_ROWS(), rows) != GHOSTTY_SUCCESS) {
                return 0;
            }
            return Math.toIntExact(rows.get(ValueLayout.JAVA_LONG, 0));
        }
    }

    CellHit hitTest(
            double x,
            double y,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx) {
        return hitTest(x, y, widthPx, heightPx, metrics, scrollbarReservedWidthPx, false);
    }

    CellHit clampedHitTest(
            double x,
            double y,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx) {
        return hitTest(x, y, widthPx, heightPx, metrics, scrollbarReservedWidthPx, true);
    }

    private CellHit hitTest(
            double x,
            double y,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx,
            boolean clamp) {
        var contentWidth = Math.max(0, widthPx - scrollbarReservedWidthPx);
        if (contentWidth <= 0 || heightPx <= 0) {
            return null;
        }
        if (!clamp && (x < 0 || y < 0 || x >= contentWidth || y >= heightPx)) {
            return null;
        }

        var hitX = clamp ? Math.clamp(x, 0.0, Math.max(0.0, contentWidth - 1.0)) : x;
        var hitY = clamp ? Math.clamp(y, 0.0, Math.max(0.0, heightPx - 1.0)) : y;
        try (var arena = Arena.ofConfined()) {
            var point = GhosttyPoint.allocate(arena);
            GhosttyPoint.tag(point, ghostty_vt_h.GHOSTTY_POINT_TAG_VIEWPORT());
            var coordinate = GhosttyPointCoordinate.allocate(arena);
            GhosttyPointCoordinate.x(coordinate, (short) Math.clamp((int) Math.floor(hitX / metrics.cellWidthPx()), 0, Math.max(0, columnCount() - 1)));
            GhosttyPointCoordinate.y(coordinate, Math.max(0, (int) Math.floor(hitY / metrics.cellHeightPx())));
            GhosttyPointValue.coordinate(GhosttyPoint.value(point), coordinate);

            var gridRef = GhosttyGridRef.allocate(arena);
            GhosttyGridRef.size(gridRef, GhosttyGridRef.sizeof());
            if (ghostty_vt_h.ghostty_terminal_grid_ref(terminal, point, gridRef) != GHOSTTY_SUCCESS) {
                return null;
            }

            var screenCoordinate = GhosttyPointCoordinate.allocate(arena);
            if (ghostty_vt_h.ghostty_terminal_point_from_grid_ref(
                    terminal,
                    gridRef,
                    ghostty_vt_h.GHOSTTY_POINT_TAG_SCREEN(),
                    screenCoordinate) != GHOSTTY_SUCCESS) {
                return null;
            }

            var cell = arena.allocate(ValueLayout.JAVA_LONG);
            if (ghostty_vt_h.ghostty_grid_ref_cell(gridRef, cell) != GHOSTTY_SUCCESS) {
                return null;
            }

            var hasText = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_cell_get(cell.get(ValueLayout.JAVA_LONG, 0), ghostty_vt_h.GHOSTTY_CELL_DATA_HAS_TEXT(), hasText),
                    "ghostty_cell_get(has_text)");
            var hyperlinkUri = hyperlinkUri(gridRef, arena);
            return new CellHit(
                    new Selection.ScreenPoint(
                            Short.toUnsignedInt(GhosttyPointCoordinate.x(screenCoordinate)),
                            GhosttyPointCoordinate.y(screenCoordinate)),
                    Short.toUnsignedInt(GhosttyPointCoordinate.x(coordinate)),
                    GhosttyPointCoordinate.y(coordinate),
                    hitX % metrics.cellWidthPx(),
                    hasText.get(ValueLayout.JAVA_BOOLEAN, 0),
                    hyperlinkUri);
        }
    }

    LogicalLine logicalLine(Selection.ScreenPoint point) {
        var columns = columnCount();
        var rows = totalRowCount();
        if (columns <= 0 || rows <= 0 || point.y() >= rows) {
            return LogicalLine.empty();
        }

        try (var arena = Arena.ofConfined()) {
            var current = point;
            while (current.y() > 0 && rowWrapContinuation(current, arena)) {
                current = new Selection.ScreenPoint(current.x(), current.y() - 1);
            }

            var startRow = current.y();
            var endRow = current.y();
            while (endRow + 1 < rows && rowWrap(new Selection.ScreenPoint(columns - 1, endRow), arena)) {
                endRow++;
            }

            var text = new StringBuilder();
            var points = new ArrayList<Selection.ScreenPoint>();
            for (var row = startRow; row <= endRow; row++) {
                appendLogicalLineRow(text, points, row, columns, arena);
            }
            return new LogicalLine(text.toString(), List.copyOf(points));
        }
    }

    Selection hyperlinkSelection(Selection.ScreenPoint point, String uri) {
        if (uri.isEmpty()) {
            return Selection.empty();
        }

        var columns = columnCount();
        if (columns <= 0) {
            return Selection.empty();
        }

        try (var arena = Arena.ofConfined()) {
            if (!uri.equals(cellHyperlink(point, arena))) {
                return Selection.empty();
            }

            var start = point;
            while (true) {
                var previous = previous(start, columns);
                if (previous.equals(start) || !isSoftWrappedBoundary(previous, start, columns, arena) || !uri.equals(cellHyperlink(previous, arena))) {
                    break;
                }
                start = previous;
            }

            var end = point;
            while (true) {
                var next = next(end, columns);
                if (next.equals(end) || !isSoftWrappedBoundary(end, next, columns, arena) || !uri.equals(cellHyperlink(next, arena))) {
                    break;
                }
                end = next;
            }

            return Selection.linear(start, end);
        }
    }

    boolean readMode(short mode) {
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            return ghostty_vt_h.ghostty_terminal_mode_get(terminal, mode, value) == GHOSTTY_SUCCESS
                    && value.get(ValueLayout.JAVA_BOOLEAN, 0);
        }
    }

    byte[] encode(KeyInput.EncodeOutput output, boolean macOptionAsAlt) {
        refreshKeyEncoder(macOptionAsAlt);
        try (var arena = Arena.ofConfined()) {
            ghostty_vt_h.ghostty_key_event_set_action(keyEvent, output.action());
            ghostty_vt_h.ghostty_key_event_set_key(keyEvent, output.ghosttyKey());
            ghostty_vt_h.ghostty_key_event_set_mods(keyEvent, output.mods());
            ghostty_vt_h.ghostty_key_event_set_consumed_mods(keyEvent, output.consumedMods());
            ghostty_vt_h.ghostty_key_event_set_unshifted_codepoint(keyEvent, output.unshiftedCodepoint());
            ghostty_vt_h.ghostty_key_event_set_composing(keyEvent, output.composing());

            if (output.utf8().isEmpty()) {
                ghostty_vt_h.ghostty_key_event_set_utf8(keyEvent, MemorySegment.NULL, 0);
            } else {
                var utf8 = output.utf8().getBytes(StandardCharsets.UTF_8);
                ghostty_vt_h.ghostty_key_event_set_utf8(
                        keyEvent,
                        arena.allocateFrom(ValueLayout.JAVA_BYTE, utf8),
                        utf8.length);
            }

            var written = arena.allocate(ValueLayout.JAVA_LONG);
            var buffer = arena.allocate(KEY_BUFFER_SIZE);
            var result = ghostty_vt_h.ghostty_key_encoder_encode(
                    keyEncoder,
                    keyEvent,
                    buffer,
                    buffer.byteSize(),
                    written);
            if (result == ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                var required = written.get(ValueLayout.JAVA_LONG, 0);
                buffer = arena.allocate(required);
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_key_encoder_encode(
                                keyEncoder,
                                keyEvent,
                                buffer,
                                buffer.byteSize(),
                                written),
                        "ghostty_key_encoder_encode");
            } else {
                requireGhosttySuccess(result, "ghostty_key_encoder_encode");
            }

            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            if (length == 0) {
                return new byte[0];
            }
            return buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    byte[] encodePaste(String text, boolean bracketed) {
        var input = text.getBytes(StandardCharsets.UTF_8);
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocateFrom(ValueLayout.JAVA_BYTE, input);
            var written = arena.allocate(ValueLayout.JAVA_LONG);
            var result = ghostty_vt_h.ghostty_paste_encode(
                    data,
                    input.length,
                    bracketed,
                    MemorySegment.NULL,
                    0,
                    written);
            if (result != GHOSTTY_SUCCESS && result != ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                requireGhosttySuccess(result, "ghostty_paste_encode");
            }

            var buffer = arena.allocate(Math.max(1, written.get(ValueLayout.JAVA_LONG, 0)));
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_paste_encode(
                            data,
                            input.length,
                            bracketed,
                            buffer,
                            buffer.byteSize(),
                            written),
                    "ghostty_paste_encode");
            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            return buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private ArrayList<KittyPlacement> kittyPlacements(
            Arena arena,
            double canvasWidth,
            double canvasHeight,
            TerminalView.FontMetrics metrics) {
        var placements = new ArrayList<KittyPlacement>();
        var graphicsPointer = arena.allocate(ValueLayout.ADDRESS);
        if (ghostty_vt_h.ghostty_terminal_get(
                terminal,
                ghostty_vt_h.GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS(),
                graphicsPointer) != GHOSTTY_SUCCESS) {
            kittyImageCache.clear();
            kittyStorageGeneration = 0;
            return placements;
        }
        var graphics = graphicsPointer.get(ValueLayout.ADDRESS, 0);
        if (graphics.equals(MemorySegment.NULL)) {
            kittyImageCache.clear();
            kittyStorageGeneration = 0;
            return placements;
        }

        var storageGenerationValue = arena.allocate(ValueLayout.JAVA_LONG);
        if (ghostty_vt_h.ghostty_kitty_graphics_get(
                graphics,
                ghostty_vt_h.GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION(),
                storageGenerationValue) != GHOSTTY_SUCCESS) {
            return placements;
        }
        var storageGeneration = storageGenerationValue.get(ValueLayout.JAVA_LONG, 0);

        var iteratorPointer = arena.allocate(ValueLayout.ADDRESS);
        iteratorPointer.set(ValueLayout.ADDRESS, 0, kittyPlacementIterator);
        if (ghostty_vt_h.ghostty_kitty_graphics_get(
                graphics,
                ghostty_vt_h.GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR(),
                iteratorPointer) != GHOSTTY_SUCCESS) {
            return placements;
        }

        var placementKeys = arena.allocate(MemoryLayout.sequenceLayout(4, ValueLayout.JAVA_INT));
        placementKeys.setAtIndex(ValueLayout.JAVA_INT, 0, ghostty_vt_h.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID());
        placementKeys.setAtIndex(ValueLayout.JAVA_INT, 1, ghostty_vt_h.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_X_OFFSET());
        placementKeys.setAtIndex(ValueLayout.JAVA_INT, 2, ghostty_vt_h.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Y_OFFSET());
        placementKeys.setAtIndex(ValueLayout.JAVA_INT, 3, ghostty_vt_h.GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z());
        var imageIdValue = arena.allocate(ValueLayout.JAVA_INT);
        var xOffsetValue = arena.allocate(ValueLayout.JAVA_INT);
        var yOffsetValue = arena.allocate(ValueLayout.JAVA_INT);
        var zValue = arena.allocate(ValueLayout.JAVA_INT);
        var placementValues = arena.allocate(MemoryLayout.sequenceLayout(4, ValueLayout.ADDRESS));
        placementValues.setAtIndex(ValueLayout.ADDRESS, 0, imageIdValue);
        placementValues.setAtIndex(ValueLayout.ADDRESS, 1, xOffsetValue);
        placementValues.setAtIndex(ValueLayout.ADDRESS, 2, yOffsetValue);
        placementValues.setAtIndex(ValueLayout.ADDRESS, 3, zValue);

        var imageKeys = arena.allocate(MemoryLayout.sequenceLayout(6, ValueLayout.JAVA_INT));
        imageKeys.setAtIndex(ValueLayout.JAVA_INT, 0, ghostty_vt_h.GHOSTTY_KITTY_IMAGE_DATA_WIDTH());
        imageKeys.setAtIndex(ValueLayout.JAVA_INT, 1, ghostty_vt_h.GHOSTTY_KITTY_IMAGE_DATA_HEIGHT());
        imageKeys.setAtIndex(ValueLayout.JAVA_INT, 2, ghostty_vt_h.GHOSTTY_KITTY_IMAGE_DATA_FORMAT());
        imageKeys.setAtIndex(ValueLayout.JAVA_INT, 3, ghostty_vt_h.GHOSTTY_KITTY_IMAGE_DATA_DATA_PTR());
        imageKeys.setAtIndex(ValueLayout.JAVA_INT, 4, ghostty_vt_h.GHOSTTY_KITTY_IMAGE_DATA_DATA_LEN());
        imageKeys.setAtIndex(ValueLayout.JAVA_INT, 5, ghostty_vt_h.GHOSTTY_KITTY_IMAGE_DATA_GENERATION());
        var imageWidthValue = arena.allocate(ValueLayout.JAVA_INT);
        var imageHeightValue = arena.allocate(ValueLayout.JAVA_INT);
        var imageFormatValue = arena.allocate(ValueLayout.JAVA_INT);
        var imageDataValue = arena.allocate(ValueLayout.ADDRESS);
        var imageDataLengthValue = arena.allocate(ValueLayout.JAVA_LONG);
        var imageGenerationValue = arena.allocate(ValueLayout.JAVA_LONG);
        var imageValues = arena.allocate(MemoryLayout.sequenceLayout(6, ValueLayout.ADDRESS));
        imageValues.setAtIndex(ValueLayout.ADDRESS, 0, imageWidthValue);
        imageValues.setAtIndex(ValueLayout.ADDRESS, 1, imageHeightValue);
        imageValues.setAtIndex(ValueLayout.ADDRESS, 2, imageFormatValue);
        imageValues.setAtIndex(ValueLayout.ADDRESS, 3, imageDataValue);
        imageValues.setAtIndex(ValueLayout.ADDRESS, 4, imageDataLengthValue);
        imageValues.setAtIndex(ValueLayout.ADDRESS, 5, imageGenerationValue);

        var renderInfo = GhosttyKittyGraphicsPlacementRenderInfo.allocate(arena);
        while (ghostty_vt_h.ghostty_kitty_graphics_placement_next(kittyPlacementIterator)) {
            if (ghostty_vt_h.ghostty_kitty_graphics_placement_get_multi(
                    kittyPlacementIterator,
                    4,
                    placementKeys,
                    placementValues,
                    MemorySegment.NULL) != GHOSTTY_SUCCESS) {
                continue;
            }
            var imageId = imageIdValue.get(ValueLayout.JAVA_INT, 0);
            var image = ghostty_vt_h.ghostty_kitty_graphics_image(graphics, imageId);
            if (image.equals(MemorySegment.NULL)) {
                continue;
            }

            GhosttyKittyGraphicsPlacementRenderInfo.size(renderInfo, GhosttyKittyGraphicsPlacementRenderInfo.sizeof());
            if (ghostty_vt_h.ghostty_kitty_graphics_placement_render_info(
                    kittyPlacementIterator,
                    image,
                    terminal,
                    renderInfo) != GHOSTTY_SUCCESS
                    || !GhosttyKittyGraphicsPlacementRenderInfo.viewport_visible(renderInfo)) {
                continue;
            }

            if (ghostty_vt_h.ghostty_kitty_graphics_image_get_multi(
                    image,
                    6,
                    imageKeys,
                    imageValues,
                    MemorySegment.NULL) != GHOSTTY_SUCCESS) {
                continue;
            }
            var imageGeneration = imageGenerationValue.get(ValueLayout.JAVA_LONG, 0);
            var cachedImage = kittyImageCache.get(imageGeneration);
            if (cachedImage == null) {
                var copiedImage = copyKittyImage(
                        imageWidthValue.get(ValueLayout.JAVA_INT, 0),
                        imageHeightValue.get(ValueLayout.JAVA_INT, 0),
                        imageFormatValue.get(ValueLayout.JAVA_INT, 0),
                        imageDataValue.get(ValueLayout.ADDRESS, 0),
                        imageDataLengthValue.get(ValueLayout.JAVA_LONG, 0));
                if (copiedImage == null) {
                    continue;
                }
                cachedImage = new CachedKittyImage(imageId, copiedImage);
                kittyImageCache.put(imageGeneration, cachedImage);
            }

            var sourceX = Integer.toUnsignedLong(GhosttyKittyGraphicsPlacementRenderInfo.source_x(renderInfo));
            var sourceY = Integer.toUnsignedLong(GhosttyKittyGraphicsPlacementRenderInfo.source_y(renderInfo));
            var sourceWidth = Integer.toUnsignedLong(GhosttyKittyGraphicsPlacementRenderInfo.source_width(renderInfo));
            var sourceHeight = Integer.toUnsignedLong(GhosttyKittyGraphicsPlacementRenderInfo.source_height(renderInfo));
            var destinationWidthPx = Integer.toUnsignedLong(GhosttyKittyGraphicsPlacementRenderInfo.pixel_width(renderInfo));
            var destinationHeightPx = Integer.toUnsignedLong(GhosttyKittyGraphicsPlacementRenderInfo.pixel_height(renderInfo));
            if (sourceWidth == 0 || sourceHeight == 0 || destinationWidthPx == 0 || destinationHeightPx == 0) {
                continue;
            }
            var terminalScaleX = physicalCellWidthPx / (double) metrics.cellWidthPx();
            var terminalScaleY = physicalCellHeightPx / (double) metrics.cellHeightPx();
            var destinationX = GhosttyKittyGraphicsPlacementRenderInfo.viewport_col(renderInfo) * metrics.cellWidthPx()
                    + Integer.toUnsignedLong(xOffsetValue.get(ValueLayout.JAVA_INT, 0)) / terminalScaleX;
            var destinationY = GhosttyKittyGraphicsPlacementRenderInfo.viewport_row(renderInfo) * metrics.cellHeightPx()
                    + Integer.toUnsignedLong(yOffsetValue.get(ValueLayout.JAVA_INT, 0)) / terminalScaleY;
            var destinationWidth = destinationWidthPx / terminalScaleX;
            var destinationHeight = destinationHeightPx / terminalScaleY;
            if (destinationX >= canvasWidth
                    || destinationY >= canvasHeight
                    || destinationX + destinationWidth <= 0
                    || destinationY + destinationHeight <= 0) {
                continue;
            }
            placements.add(new KittyPlacement(
                    imageId,
                    zValue.get(ValueLayout.JAVA_INT, 0),
                    sourceX,
                    sourceY,
                    sourceWidth,
                    sourceHeight,
                    destinationX,
                    destinationY,
                    destinationWidth,
                    destinationHeight,
                    cachedImage.image()));
        }

        if (storageGeneration != kittyStorageGeneration) {
            kittyImageCache.entrySet().removeIf(entry -> {
                var image = ghostty_vt_h.ghostty_kitty_graphics_image(graphics, entry.getValue().imageId());
                if (image.equals(MemorySegment.NULL)
                        || ghostty_vt_h.ghostty_kitty_graphics_image_get(
                                image,
                                ghostty_vt_h.GHOSTTY_KITTY_IMAGE_DATA_GENERATION(),
                                imageGenerationValue) != GHOSTTY_SUCCESS) {
                    return true;
                }
                return imageGenerationValue.get(ValueLayout.JAVA_LONG, 0) != entry.getKey();
            });
            kittyStorageGeneration = storageGeneration;
        }
        placements.sort(KITTY_PLACEMENT_ORDER);
        return placements;
    }

    private static Image copyKittyImage(int widthValue, int heightValue, int format, MemorySegment data, long dataLength) {
        var width = Integer.toUnsignedLong(widthValue);
        var height = Integer.toUnsignedLong(heightValue);
        var bytesPerPixel = format == ghostty_vt_h.GHOSTTY_KITTY_IMAGE_FORMAT_RGB()
                ? 3
                : format == ghostty_vt_h.GHOSTTY_KITTY_IMAGE_FORMAT_RGBA() ? 4 : 0;
        if (width == 0
                || height == 0
                || width > Integer.MAX_VALUE
                || height > Integer.MAX_VALUE
                || bytesPerPixel == 0
                || dataLength < 0
                || data.equals(MemorySegment.NULL)) {
            return null;
        }

        final long expectedLength;
        try {
            expectedLength = Math.multiplyExact(Math.multiplyExact(width, height), bytesPerPixel);
        } catch (ArithmeticException _) {
            return null;
        }
        if (dataLength != expectedLength || expectedLength > Integer.MAX_VALUE) {
            return null;
        }

        var pixelWidth = (int) width;
        var pixelHeight = (int) height;
        var source = data.reinterpret(dataLength).toArray(ValueLayout.JAVA_BYTE);
        var image = new WritableImage(pixelWidth, pixelHeight);
        if (bytesPerPixel == 3) {
            image.getPixelWriter().setPixels(
                    0,
                    0,
                    pixelWidth,
                    pixelHeight,
                    PixelFormat.getByteRgbInstance(),
                    source,
                    0,
                    Math.multiplyExact(pixelWidth, 3));
            return image;
        }

        var bgra = new byte[source.length];
        for (var i = 0; i < source.length; i += 4) {
            bgra[i] = source[i + 2];
            bgra[i + 1] = source[i + 1];
            bgra[i + 2] = source[i];
            bgra[i + 3] = source[i + 3];
        }
        image.getPixelWriter().setPixels(
                0,
                0,
                pixelWidth,
                pixelHeight,
                PixelFormat.getByteBgraInstance(),
                bgra,
                0,
                Math.multiplyExact(pixelWidth, 4));
        return image;
    }

    private static void drawKittyPlacements(
            GraphicsContext graphics,
            List<KittyPlacement> placements,
            long minimumZ,
            long maximumZ) {
        for (var placement : placements) {
            if (placement.z() < minimumZ || placement.z() >= maximumZ) {
                continue;
            }
            graphics.drawImage(
                    placement.image(),
                    placement.sourceX(),
                    placement.sourceY(),
                    placement.sourceWidth(),
                    placement.sourceHeight(),
                    placement.destinationX(),
                    placement.destinationY(),
                    placement.destinationWidth(),
                    placement.destinationHeight());
        }
    }

    BlinkState render(
            GraphicsContext graphics,
            double width,
            double height,
            TerminalView.FontMetrics metrics,
            KeyInput.Preedit preedit,
            Selection hoveredLink,
            List<TerminalLinkMatcher> linkMatchers,
            SearchResult searchResult,
            int selectedSearchMatch,
            boolean focused,
            TerminalTheme theme,
            boolean cursorBlinkVisible,
            boolean textBlinkVisible,
            boolean scrollbarActive,
            double scrollbarReservedWidthPx,
            double minScrollbarHeightPx,
            int promptNavigationHighlightRow) {
        graphics.setFont(metrics.regular());

        try (var arena = Arena.ofConfined()) {
            var colors = GhosttyRenderStateColors.allocate(arena);
            GhosttyRenderStateColors.size(colors, GhosttyRenderStateColors.sizeof());
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_colors_get(renderState, colors),
                    "ghostty_render_state_colors_get");

            var defaultBackground = toFxColor(GhosttyRenderStateColors.background(colors));
            graphics.setFill(defaultBackground);
            graphics.fillRect(0, 0, width, height);

            var kittyPlacements = kittyPlacements(arena, width, height, metrics);
            drawKittyPlacements(graphics, kittyPlacements, Long.MIN_VALUE, KITTY_BELOW_BACKGROUND_Z);

            var rowIteratorPointer = arena.allocate(ValueLayout.ADDRESS);
            rowIteratorPointer.set(ValueLayout.ADDRESS, 0, rowIterator);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_render_state_get(
                            renderState,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR(),
                            rowIteratorPointer),
                    "ghostty_render_state_get(row_iterator)");

            var rowCellsPointer = arena.allocate(ValueLayout.ADDRESS);
            rowCellsPointer.set(ValueLayout.ADDRESS, 0, rowCells);
            var style = GhosttyStyle.allocate(arena);
            GhosttyStyle.size(style, GhosttyStyle.sizeof());
            var foreground = GhosttyColorRgb.allocate(arena);
            var background = GhosttyColorRgb.allocate(arena);
            var swappedColor = GhosttyColorRgb.allocate(arena);
            var selectedValue = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            var graphemeBuffer = GhosttyBuffer.allocate(arena);
            var graphemeBytesCapacity = MAX_GRAPHEME_CODEPOINTS * 4;
            var graphemeBytes = arena.allocate(graphemeBytesCapacity);
            var scrollbar = GhosttyTerminalScrollbar.allocate(arena);
            var viewportTop = ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_SCROLLBAR(), scrollbar) == GHOSTTY_SUCCESS
                    ? Math.toIntExact(GhosttyTerminalScrollbar.offset(scrollbar))
                    : 0;
            var visibleRows = Math.max(1, (int) Math.ceil(height / metrics.cellHeightPx()));
            var linkResult = linkMatcherResult(linkMatchers, viewportTop, viewportTop + visibleRows - 1);
            var highlightedViewportRow = promptNavigationHighlightRow >= viewportTop
                    && promptNavigationHighlightRow < viewportTop + visibleRows
                    ? promptNavigationHighlightRow - viewportTop
                    : -1;
            var cursor = cursorInfo(arena);
            var preeditCellCount = preedit.text().codePointCount(0, preedit.text().length());
            var cursorText = "";
            var cursorBold = false;
            var cursorItalic = false;
            var hasCursorBlink = focused && cursor != null && cursor.blinking();
            var hasTextBlink = false;

            var splitCellRendering = false;
            for (var placement : kittyPlacements) {
                if (placement.z() >= KITTY_BELOW_BACKGROUND_Z && placement.z() < 0) {
                    splitCellRendering = true;
                    break;
                }
            }
            for (var pass = 0; pass < (splitCellRendering ? 2 : 1); pass++) {
                var backgroundOnly = splitCellRendering && pass == 0;
                if (pass > 0) {
                    drawKittyPlacements(graphics, kittyPlacements, KITTY_BELOW_BACKGROUND_Z, 0);
                    rowIteratorPointer.set(ValueLayout.ADDRESS, 0, rowIterator);
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_get(
                                    renderState,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR(),
                                    rowIteratorPointer),
                            "ghostty_render_state_get(row_iterator)");
                }

                var y = 0.0;
                var viewportY = 0;
                while (ghostty_vt_h.ghostty_render_state_row_iterator_next(rowIterator)) {
                if ((!splitCellRendering || backgroundOnly) && viewportY == highlightedViewportRow) {
                    graphics.setFill(applyOpacity(theme.foreground(), 0.16));
                    graphics.fillRect(0, y, Math.max(0.0, width - scrollbarReservedWidthPx), metrics.cellHeightPx());
                }

                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_render_state_row_get(
                                rowIterator,
                                ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS(),
                                rowCellsPointer),
                        "ghostty_render_state_row_get(cells)");

                var x = 0.0;
                var viewportX = 0;
                while (ghostty_vt_h.ghostty_render_state_row_cells_next(rowCells)) {
                    var screenPoint = new Selection.ScreenPoint(viewportX, viewportTop + viewportY);
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_row_cells_get(
                                    rowCells,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED(),
                                    selectedValue),
                            "ghostty_render_state_row_cells_get(selected)");
                    var selected = selectedValue.get(ValueLayout.JAVA_BOOLEAN, 0);
                    var hovered = contains(hoveredLink, screenPoint);
                    var matchedLink = linkResult.matchIndex(screenPoint) >= 0;
                    var searchMatch = searchResult.matchIndex(screenPoint);
                    var searchHighlighted = searchMatch >= 0;
                    var currentSearchMatch = searchMatch == selectedSearchMatch;
                    var preeditCell = cursor != null
                            && preeditCellCount > 0
                            && viewportY == cursor.y()
                            && viewportX >= cursor.x()
                            && viewportX < cursor.x() + preeditCellCount;
                    GhosttyBuffer.ptr(graphemeBuffer, graphemeBytes);
                    GhosttyBuffer.cap(graphemeBuffer, graphemeBytesCapacity);
                    GhosttyBuffer.len(graphemeBuffer, 0);
                    var graphemeResult = ghostty_vt_h.ghostty_render_state_row_cells_get(
                            rowCells,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8(),
                            graphemeBuffer);
                    if (graphemeResult == ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                        graphemeBytesCapacity = Math.toIntExact(GhosttyBuffer.len(graphemeBuffer));
                        graphemeBytes = arena.allocate(graphemeBytesCapacity);
                        GhosttyBuffer.ptr(graphemeBuffer, graphemeBytes);
                        GhosttyBuffer.cap(graphemeBuffer, graphemeBytesCapacity);
                        GhosttyBuffer.len(graphemeBuffer, 0);
                        requireGhosttySuccess(
                                ghostty_vt_h.ghostty_render_state_row_cells_get(
                                        rowCells,
                                        ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8(),
                                        graphemeBuffer),
                                "ghostty_render_state_row_cells_get(graphemes_utf8)");
                    } else {
                        requireGhosttySuccess(graphemeResult, "ghostty_render_state_row_cells_get(graphemes_utf8)");
                    }
                    var graphemeByteLength = GhosttyBuffer.len(graphemeBuffer);
                    if (graphemeByteLength == 0) {
                        if (!splitCellRendering || backgroundOnly) {
                            if (selected) {
                                graphics.setFill(theme.selectionColor());
                                graphics.fillRect(x, y, metrics.cellWidthPx(), metrics.cellHeightPx());
                            } else if (ghostty_vt_h.ghostty_render_state_row_cells_get(
                                    rowCells,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR(),
                                    background) == GHOSTTY_SUCCESS) {
                                graphics.setFill(toFxColor(background));
                                graphics.fillRect(x, y, metrics.cellWidthPx(), metrics.cellHeightPx());
                            }
                            if (!selected && searchHighlighted) {
                                drawSearchHighlightBackground(
                                        graphics,
                                        x,
                                        y,
                                        metrics,
                                        theme,
                                        currentSearchMatch);
                            }
                        }
                        x += metrics.cellWidthPx();
                        viewportX++;
                        continue;
                    }

                    GhosttyStyle.size(style, GhosttyStyle.sizeof());
                    requireGhosttySuccess(
                            ghostty_vt_h.ghostty_render_state_row_cells_get(
                                    rowCells,
                                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE(),
                                    style),
                            "ghostty_render_state_row_cells_get(style)");

                    MemorySegment.copy(
                            GhosttyRenderStateColors.foreground(colors),
                            0,
                            foreground,
                            0,
                            GhosttyColorRgb.sizeof());
                    ghostty_vt_h.ghostty_render_state_row_cells_get(
                            rowCells,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR(),
                            foreground);

                    var hasBackground = ghostty_vt_h.ghostty_render_state_row_cells_get(
                            rowCells,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR(),
                            background) == GHOSTTY_SUCCESS;
                    if (!hasBackground) {
                        MemorySegment.copy(
                                GhosttyRenderStateColors.background(colors),
                                0,
                                background,
                                0,
                                GhosttyColorRgb.sizeof());
                    }

                    if (GhosttyStyle.inverse(style)) {
                        MemorySegment.copy(background, 0, swappedColor, 0, GhosttyColorRgb.sizeof());
                        MemorySegment.copy(foreground, 0, background, 0, GhosttyColorRgb.sizeof());
                        MemorySegment.copy(swappedColor, 0, foreground, 0, GhosttyColorRgb.sizeof());
                        hasBackground = true;
                    }

                    if (!splitCellRendering || backgroundOnly) {
                        if (selected || hasBackground) {
                            graphics.setFill(selected ? theme.selectionColor() : toFxColor(background));
                            graphics.fillRect(x, y, metrics.cellWidthPx(), metrics.cellHeightPx());
                        }
                        if (!selected && searchHighlighted) {
                            drawSearchHighlightBackground(
                                    graphics,
                                    x,
                                    y,
                                    metrics,
                                    theme,
                                    currentSearchMatch);
                        }
                        if (backgroundOnly) {
                            x += metrics.cellWidthPx();
                            viewportX++;
                            continue;
                        }
                    }

                    var faint = GhosttyStyle.faint(style);
                    var textBlinking = !GhosttyStyle.invisible(style) && GhosttyStyle.blink(style);
                    hasTextBlink |= textBlinking;
                    var textBlinkHidden = textBlinking && !textBlinkVisible && faint;
                    var faintFactor = faint || (textBlinking && !textBlinkVisible) ? theme.faintOpacity() : 1.0;

                    if (!GhosttyStyle.invisible(style) && !textBlinkHidden) {
                        var renderedText = new String(
                                GhosttyBuffer.ptr(graphemeBuffer)
                                        .reinterpret(graphemeByteLength)
                                        .toArray(ValueLayout.JAVA_BYTE),
                                StandardCharsets.UTF_8);
                        if (renderedText.codePointAt(0) == 0x10EEEE) {
                            x += metrics.cellWidthPx();
                            viewportX++;
                            continue;
                        }
                        if (cursor != null && viewportX == cursor.x() && viewportY == cursor.y()) {
                            cursorText = renderedText;
                            cursorBold = GhosttyStyle.bold(style);
                            cursorItalic = GhosttyStyle.italic(style);
                        }
                        var baseline = y + metrics.baselineOffsetPx();
                        if (!preeditCell) {
                            var baseTextColor = selected ? theme.selectionText() : toFxColor(foreground);
                            var textColor = applyOpacity(baseTextColor, faintFactor);
                            graphics.setFill(textColor);
                            var codePoint = renderedText.codePointAt(0);
                            if (Character.charCount(codePoint) == renderedText.length() && codePoint >= 0x2580 && codePoint <= 0x259F) {
                                drawBlockElement(graphics, codePoint, x, y, metrics, textColor);
                            } else {
                                graphics.setFont(metrics.forStyle(GhosttyStyle.bold(style), GhosttyStyle.italic(style)));
                                graphics.fillText(renderedText, x, baseline);
                            }
                            drawTextDecorations(graphics, x, y, metrics, style, selected, baseTextColor, textColor, theme, faintFactor);
                            if (GhosttyStyle.underline(style) == ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_NONE()
                                    && !GhosttyStyle.strikethrough(style)
                                    && !GhosttyStyle.overline(style)) {
                                var hyperlinkUri = cellHyperlink(screenPoint, arena);
                                var osc8Link = hyperlinkUri != null && !hyperlinkUri.isEmpty();
                                if (matchedLink || osc8Link) {
                                    var linkUnderlineOpacity = hovered
                                            ? theme.hoveredLinkUnderlineOpacity()
                                            : (osc8Link ? theme.osc8LinkUnderlineOpacity() : theme.matchedLinkUnderlineOpacity());
                                    if (linkUnderlineOpacity > 0.0) {
                                        graphics.setStroke(applyOpacity(baseTextColor, faintFactor * linkUnderlineOpacity));
                                        drawUnderline(graphics, x, y + metrics.cellHeightPx() - 2.5, metrics.cellWidthPx(), ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_DOTTED());
                                    }
                                }
                            }
                        }
                    }
                    x += metrics.cellWidthPx();
                    viewportX++;
                }

                if (!backgroundOnly) {
                    drawSearchHighlightBorders(
                            graphics,
                            y,
                            metrics,
                            theme,
                            searchResult,
                            selectedSearchMatch,
                            viewportTop + viewportY);
                }
                y += metrics.cellHeightPx();
                viewportY++;
            }
            }

            renderCursor(graphics, metrics, colors, focused, theme, cursorBlinkVisible, cursor, cursorText, cursorBold, cursorItalic);
            graphics.setFont(metrics.regular());
            renderPreedit(graphics, metrics, preedit, cursor, theme);
            drawKittyPlacements(graphics, kittyPlacements, 0, Long.MAX_VALUE);

            var scrollbarInfo = scrollbarInfo(width, height, scrollbarReservedWidthPx, minScrollbarHeightPx);
            if (scrollbarInfo != null && scrollbarInfo.scrollable()) {
                graphics.setFill(scrollbarActive ? theme.scrollbarActiveColor() : theme.scrollbarColor());
                graphics.fillRoundRect(
                        scrollbarInfo.thumbX(),
                        scrollbarInfo.thumbY(),
                        TerminalView.SCROLLBAR_WIDTH_PX,
                        scrollbarInfo.thumbHeight(),
                        TerminalView.SCROLLBAR_ARC_PX,
                        TerminalView.SCROLLBAR_ARC_PX);
            }
            return new BlinkState(hasCursorBlink, hasTextBlink);
        }
    }

    TerminalView.CursorLocation currentCursorLocation(TerminalView.FontMetrics metrics) {
        try (var arena = Arena.ofConfined()) {
            var cursorVisible = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            if (ghostty_vt_h.ghostty_render_state_get(
                    renderState,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE(),
                    cursorVisible) != GHOSTTY_SUCCESS
                    || !cursorVisible.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                return null;
            }

            var cursorInViewport = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            if (ghostty_vt_h.ghostty_render_state_get(
                    renderState,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE(),
                    cursorInViewport) != GHOSTTY_SUCCESS
                    || !cursorInViewport.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                return null;
            }

            var cursorX = arena.allocate(ValueLayout.JAVA_SHORT);
            var cursorY = arena.allocate(ValueLayout.JAVA_SHORT);
            if (ghostty_vt_h.ghostty_render_state_get(
                    renderState,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X(),
                    cursorX) != GHOSTTY_SUCCESS
                    || ghostty_vt_h.ghostty_render_state_get(
                            renderState,
                            ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y(),
                            cursorY) != GHOSTTY_SUCCESS) {
                return null;
            }

            var cellX = Short.toUnsignedInt(cursorX.get(ValueLayout.JAVA_SHORT, 0));
            var cellY = Short.toUnsignedInt(cursorY.get(ValueLayout.JAVA_SHORT, 0));
            return new TerminalView.CursorLocation(
                    cellX,
                    cellY,
                    cellX * (double) metrics.cellWidthPx(),
                    cellY * (double) metrics.cellHeightPx());
        }
    }

    private byte[] encodeMouseButton(int action, int button, double x, double y, short mods) {
        try (var arena = Arena.ofConfined()) {
            ghostty_vt_h.ghostty_mouse_event_set_action(mouseEvent, action);
            ghostty_vt_h.ghostty_mouse_event_set_button(mouseEvent, button);
            ghostty_vt_h.ghostty_mouse_event_set_mods(mouseEvent, mods);
            var position = GhosttyMousePosition.allocate(arena);
            GhosttyMousePosition.x(position, (float) x);
            GhosttyMousePosition.y(position, (float) y);
            ghostty_vt_h.ghostty_mouse_event_set_position(mouseEvent, position);

            var written = arena.allocate(ValueLayout.JAVA_LONG);
            var buffer = arena.allocate(KEY_BUFFER_SIZE);
            var result = ghostty_vt_h.ghostty_mouse_encoder_encode(
                    mouseEncoder,
                    mouseEvent,
                    buffer,
                    buffer.byteSize(),
                    written);
            if (result == ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                var required = written.get(ValueLayout.JAVA_LONG, 0);
                buffer = arena.allocate(required);
                requireGhosttySuccess(
                        ghostty_vt_h.ghostty_mouse_encoder_encode(
                                mouseEncoder,
                                mouseEvent,
                                buffer,
                                buffer.byteSize(),
                                written),
                        "ghostty_mouse_encoder_encode");
            } else {
                requireGhosttySuccess(result, "ghostty_mouse_encoder_encode");
            }

            var length = Math.toIntExact(written.get(ValueLayout.JAVA_LONG, 0));
            if (length == 0) {
                return new byte[0];
            }
            return buffer.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private void refreshMouseEncoder(
            boolean anyButtonPressed,
            double widthPx,
            double heightPx,
            TerminalView.FontMetrics metrics,
            double scrollbarReservedWidthPx) {
        ghostty_vt_h.ghostty_mouse_encoder_setopt_from_terminal(mouseEncoder, terminal);
        try (var arena = Arena.ofConfined()) {
            var size = GhosttyMouseEncoderSize.allocate(arena);
            GhosttyMouseEncoderSize.size(size, GhosttyMouseEncoderSize.sizeof());
            GhosttyMouseEncoderSize.screen_width(size, Math.max(1, (int) Math.ceil(widthPx)));
            GhosttyMouseEncoderSize.screen_height(size, Math.max(1, (int) Math.ceil(heightPx)));
            GhosttyMouseEncoderSize.cell_width(size, metrics.cellWidthPx());
            GhosttyMouseEncoderSize.cell_height(size, metrics.cellHeightPx());
            GhosttyMouseEncoderSize.padding_top(size, 0);
            GhosttyMouseEncoderSize.padding_bottom(size, 0);
            GhosttyMouseEncoderSize.padding_right(size, (int) Math.ceil(scrollbarReservedWidthPx));
            GhosttyMouseEncoderSize.padding_left(size, 0);
            ghostty_vt_h.ghostty_mouse_encoder_setopt(
                    mouseEncoder,
                    ghostty_vt_h.GHOSTTY_MOUSE_ENCODER_OPT_SIZE(),
                    size);

            var anyPressed = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            anyPressed.set(ValueLayout.JAVA_BOOLEAN, 0, anyButtonPressed);
            ghostty_vt_h.ghostty_mouse_encoder_setopt(
                    mouseEncoder,
                    ghostty_vt_h.GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED(),
                    anyPressed);
        }
    }

    private void refreshKeyEncoder(boolean macOptionAsAlt) {
        ghostty_vt_h.ghostty_key_encoder_setopt_from_terminal(keyEncoder, terminal);
        try (var arena = Arena.ofConfined()) {
            var option = arena.allocate(ValueLayout.JAVA_INT);
            option.set(
                    ValueLayout.JAVA_INT,
                    0,
                    macOptionAsAlt
                            ? ghostty_vt_h.GHOSTTY_OPTION_AS_ALT_TRUE()
                            : ghostty_vt_h.GHOSTTY_OPTION_AS_ALT_FALSE());
            ghostty_vt_h.ghostty_key_encoder_setopt(
                    keyEncoder,
                    ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT(),
                    option);
        }
    }

    private MemorySegment activeSelection(Arena arena) {
        var selection = GhosttySelection.allocate(arena);
        GhosttySelection.size(selection, GhosttySelection.sizeof());
        var result = ghostty_vt_h.ghostty_terminal_get(
                terminal,
                ghostty_vt_h.GHOSTTY_TERMINAL_DATA_SELECTION(),
                selection);
        if (result == ghostty_vt_h.GHOSTTY_NO_VALUE()) {
            return MemorySegment.NULL;
        }

        requireGhosttySuccess(result, "ghostty_terminal_get(selection)");
        return selection;
    }

    private void setActiveSelection(MemorySegment selection) {
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_terminal_set(
                        terminal,
                        ghostty_vt_h.GHOSTTY_TERMINAL_OPT_SELECTION(),
                        selection),
                "ghostty_terminal_set(selection)");
    }

    private MemorySegment selectionGestureEvent(Arena arena, int type) {
        var eventPointer = arena.allocate(ValueLayout.ADDRESS);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_selection_gesture_event_new(MemorySegment.NULL, eventPointer, type),
                "ghostty_selection_gesture_event_new");
        return eventPointer.get(ValueLayout.ADDRESS, 0);
    }

    private boolean applySelectionGestureEvent(Arena arena, MemorySegment event, boolean clearOnNoSelection) {
        var selection = GhosttySelection.allocate(arena);
        GhosttySelection.size(selection, GhosttySelection.sizeof());
        var result = ghostty_vt_h.ghostty_selection_gesture_event(selectionGesture, terminal, event, selection);
        if (result == GHOSTTY_SUCCESS) {
            setActiveSelection(selection);
            updateRenderState();
            return true;
        }

        if (result == ghostty_vt_h.GHOSTTY_NO_VALUE()) {
            if (clearOnNoSelection) {
                setActiveSelection(MemorySegment.NULL);
            }
            updateRenderState();
            return false;
        }

        requireGhosttySuccess(result, "ghostty_selection_gesture_event");
        return false;
    }

    private void setSelectionGestureRef(MemorySegment event, MemorySegment ref) {
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_selection_gesture_event_set(
                        event,
                        ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF(),
                        ref),
                "ghostty_selection_gesture_event_set(ref)");
    }

    private static void setSelectionGesturePosition(MemorySegment event, Arena arena, double x, double y) {
        var position = GhosttySurfacePosition.allocate(arena);
        GhosttySurfacePosition.x(position, x);
        GhosttySurfacePosition.y(position, y);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_selection_gesture_event_set(
                        event,
                        ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION(),
                        position),
                "ghostty_selection_gesture_event_set(position)");
    }

    private static void setSelectionGestureDouble(MemorySegment event, Arena arena, int option, double value) {
        var nativeValue = arena.allocate(ValueLayout.JAVA_DOUBLE);
        nativeValue.set(ValueLayout.JAVA_DOUBLE, 0, value);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_selection_gesture_event_set(event, option, nativeValue),
                "ghostty_selection_gesture_event_set(double)");
    }

    private static void setSelectionGestureLong(MemorySegment event, Arena arena, int option, long value) {
        var nativeValue = arena.allocate(ValueLayout.JAVA_LONG);
        nativeValue.set(ValueLayout.JAVA_LONG, 0, value);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_selection_gesture_event_set(event, option, nativeValue),
                "ghostty_selection_gesture_event_set(long)");
    }

    private static void setSelectionGestureBoolean(MemorySegment event, Arena arena, int option, boolean value) {
        var nativeValue = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        nativeValue.set(ValueLayout.JAVA_BOOLEAN, 0, value);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_selection_gesture_event_set(event, option, nativeValue),
                "ghostty_selection_gesture_event_set(boolean)");
    }

    private void setSelectionGestureGeometry(
            MemorySegment event,
            Arena arena,
            TerminalView.FontMetrics metrics,
            double heightPx) {
        var geometry = GhosttySelectionGestureGeometry.allocate(arena);
        GhosttySelectionGestureGeometry.columns(geometry, Math.max(1, columnCount()));
        GhosttySelectionGestureGeometry.cell_width(geometry, Math.max(1, metrics.cellWidthPx()));
        GhosttySelectionGestureGeometry.padding_left(geometry, 0);
        GhosttySelectionGestureGeometry.screen_height(geometry, Math.max(1, (int) Math.ceil(heightPx)));
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_selection_gesture_event_set(
                        event,
                        ghostty_vt_h.GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY(),
                        geometry),
                "ghostty_selection_gesture_event_set(geometry)");
    }

    private boolean writeGridRef(Arena arena, Selection.ScreenPoint point, MemorySegment outGridRef) {
        var coordinate = GhosttyPointCoordinate.allocate(arena);
        GhosttyPointCoordinate.x(coordinate, (short) point.x());
        GhosttyPointCoordinate.y(coordinate, point.y());
        var ghosttyPoint = GhosttyPoint.allocate(arena);
        GhosttyPoint.tag(ghosttyPoint, ghostty_vt_h.GHOSTTY_POINT_TAG_SCREEN());
        GhosttyPointValue.coordinate(GhosttyPoint.value(ghosttyPoint), coordinate);
        return ghostty_vt_h.ghostty_terminal_grid_ref(terminal, ghosttyPoint, outGridRef) == GHOSTTY_SUCCESS;
    }

    private Selection.ScreenPoint screenPoint(MemorySegment gridRef, Arena arena) {
        var coordinate = GhosttyPointCoordinate.allocate(arena);
        if (ghostty_vt_h.ghostty_terminal_point_from_grid_ref(
                terminal,
                gridRef,
                ghostty_vt_h.GHOSTTY_POINT_TAG_SCREEN(),
                coordinate) != GHOSTTY_SUCCESS) {
            return null;
        }

        return new Selection.ScreenPoint(
                Short.toUnsignedInt(GhosttyPointCoordinate.x(coordinate)),
                GhosttyPointCoordinate.y(coordinate));
    }

    private void appendSearchRow(SearchDocumentBuilder document, int row, Arena arena) {
        var lastTextColumn = lastTextColumn(row, document.columns(), arena);
        for (var column = 0; column <= lastTextColumn; column++) {
            var point = new Selection.ScreenPoint(column, row);
            var grapheme = cellGrapheme(point, arena);
            if (grapheme.spacerCell()) {
                continue;
            }
            if (grapheme.text().isEmpty()) {
                document.text().append(' ');
                document.points().add(point);
            } else {
                var start = document.text().length();
                document.text().append(grapheme.text());
                for (var i = start; i < document.text().length(); i++) {
                    document.points().add(point);
                }
            }
        }
        if (row + 1 < document.rows() && !rowWrap(new Selection.ScreenPoint(Math.max(0, document.columns() - 1), row), arena)) {
            document.text().append('\n');
            document.points().add(null);
        }
    }

    private void appendLogicalLineRow(
            StringBuilder text,
            ArrayList<Selection.ScreenPoint> points,
            int row,
            int columns,
            Arena arena) {
        var lastTextColumn = lastTextColumn(row, columns, arena);
        for (var column = 0; column <= lastTextColumn; column++) {
            var point = new Selection.ScreenPoint(column, row);
            var grapheme = cellGrapheme(point, arena);
            if (grapheme.spacerCell()) {
                continue;
            }
            if (grapheme.text().isEmpty()) {
                text.append(' ');
                points.add(point);
            } else {
                var start = text.length();
                text.append(grapheme.text());
                for (var i = start; i < text.length(); i++) {
                    points.add(point);
                }
            }
        }
    }

    private int lastTextColumn(int row, int columns, Arena arena) {
        for (var column = columns - 1; column >= 0; column--) {
            if (cellCodePoint(new Selection.ScreenPoint(column, row), arena) != null) {
                return column;
            }
        }
        return -1;
    }

    private Integer cellCodePoint(Selection.ScreenPoint point, Arena arena) {
        var gridRef = gridRef(point, arena);
        if (gridRef == null) {
            return null;
        }

        var cell = arena.allocate(ValueLayout.JAVA_LONG);
        if (ghostty_vt_h.ghostty_grid_ref_cell(gridRef, cell) != GHOSTTY_SUCCESS) {
            return null;
        }

        var hasText = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_cell_get(cell.get(ValueLayout.JAVA_LONG, 0), ghostty_vt_h.GHOSTTY_CELL_DATA_HAS_TEXT(), hasText),
                "ghostty_cell_get(has_text)");
        if (!hasText.get(ValueLayout.JAVA_BOOLEAN, 0)) {
            return null;
        }

        var codePoint = arena.allocate(ValueLayout.JAVA_INT);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_cell_get(cell.get(ValueLayout.JAVA_LONG, 0), ghostty_vt_h.GHOSTTY_CELL_DATA_CODEPOINT(), codePoint),
                "ghostty_cell_get(codepoint)");
        return codePoint.get(ValueLayout.JAVA_INT, 0);
    }

    private CellGrapheme cellGrapheme(Selection.ScreenPoint point, Arena arena) {
        var gridRef = gridRef(point, arena);
        if (gridRef == null) {
            return CellGrapheme.empty();
        }

        var cell = arena.allocate(ValueLayout.JAVA_LONG);
        if (ghostty_vt_h.ghostty_grid_ref_cell(gridRef, cell) != GHOSTTY_SUCCESS) {
            return CellGrapheme.empty();
        }

        var hasText = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_cell_get(cell.get(ValueLayout.JAVA_LONG, 0), ghostty_vt_h.GHOSTTY_CELL_DATA_HAS_TEXT(), hasText),
                "ghostty_cell_get(has_text)");
        if (!hasText.get(ValueLayout.JAVA_BOOLEAN, 0)) {
            var wide = arena.allocate(ValueLayout.JAVA_INT);
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_cell_get(cell.get(ValueLayout.JAVA_LONG, 0), ghostty_vt_h.GHOSTTY_CELL_DATA_WIDE(), wide),
                    "ghostty_cell_get(wide)");
            return wide.get(ValueLayout.JAVA_INT, 0) == ghostty_vt_h.GHOSTTY_CELL_WIDE_SPACER_TAIL()
                    ? CellGrapheme.spacer()
                    : CellGrapheme.empty();
        }

        var length = arena.allocate(ValueLayout.JAVA_LONG);
        var buffer = arena.allocate(MemoryLayout.sequenceLayout(MAX_GRAPHEME_CODEPOINTS, ValueLayout.JAVA_INT));
        var result = ghostty_vt_h.ghostty_grid_ref_graphemes(gridRef, buffer, MAX_GRAPHEME_CODEPOINTS, length);
        if (result == ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
            buffer = arena.allocate(MemoryLayout.sequenceLayout(length.get(ValueLayout.JAVA_LONG, 0), ValueLayout.JAVA_INT));
            requireGhosttySuccess(
                    ghostty_vt_h.ghostty_grid_ref_graphemes(gridRef, buffer, length.get(ValueLayout.JAVA_LONG, 0), length),
                    "ghostty_grid_ref_graphemes");
        } else {
            requireGhosttySuccess(result, "ghostty_grid_ref_graphemes");
        }

        var text = new StringBuilder(Math.toIntExact(length.get(ValueLayout.JAVA_LONG, 0)) * 2);
        for (var i = 0L; i < length.get(ValueLayout.JAVA_LONG, 0); i++) {
            var codePoint = buffer.get(ValueLayout.JAVA_INT, i * Integer.BYTES);
            text.appendCodePoint(Character.isValidCodePoint(codePoint) ? codePoint : 0xFFFD);
        }
        return new CellGrapheme(text.toString(), false);
    }

    private String cellHyperlink(Selection.ScreenPoint point, Arena arena) {
        var gridRef = gridRef(point, arena);
        return gridRef == null ? null : hyperlinkUri(gridRef, arena);
    }

    private int rowSemanticPrompt(int row, Arena arena) {
        var gridRef = gridRef(new Selection.ScreenPoint(0, row), arena);
        if (gridRef == null) {
            return ghostty_vt_h.GHOSTTY_ROW_SEMANTIC_NONE();
        }

        var nativeRow = arena.allocate(ValueLayout.JAVA_LONG);
        if (ghostty_vt_h.ghostty_grid_ref_row(gridRef, nativeRow) != GHOSTTY_SUCCESS) {
            return ghostty_vt_h.GHOSTTY_ROW_SEMANTIC_NONE();
        }

        var semanticPrompt = arena.allocate(ValueLayout.JAVA_INT);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_row_get(
                        nativeRow.get(ValueLayout.JAVA_LONG, 0),
                        ghostty_vt_h.GHOSTTY_ROW_DATA_SEMANTIC_PROMPT(),
                        semanticPrompt),
                "ghostty_row_get(semantic_prompt)");
        return semanticPrompt.get(ValueLayout.JAVA_INT, 0);
    }

    private boolean rowWrap(Selection.ScreenPoint point, Arena arena) {
        return rowFlag(point, ghostty_vt_h.GHOSTTY_ROW_DATA_WRAP(), arena);
    }

    private boolean rowWrapContinuation(Selection.ScreenPoint point, Arena arena) {
        return rowFlag(point, ghostty_vt_h.GHOSTTY_ROW_DATA_WRAP_CONTINUATION(), arena);
    }

    private boolean rowFlag(Selection.ScreenPoint point, int flag, Arena arena) {
        var gridRef = gridRef(point, arena);
        if (gridRef == null) {
            return false;
        }

        var row = arena.allocate(ValueLayout.JAVA_LONG);
        if (ghostty_vt_h.ghostty_grid_ref_row(gridRef, row) != GHOSTTY_SUCCESS) {
            return false;
        }

        var out = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        requireGhosttySuccess(ghostty_vt_h.ghostty_row_get(row.get(ValueLayout.JAVA_LONG, 0), flag, out), "ghostty_row_get");
        return out.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    private String hyperlinkUri(MemorySegment gridRef, Arena arena) {
        var length = arena.allocate(ValueLayout.JAVA_LONG);
        var result = ghostty_vt_h.ghostty_grid_ref_hyperlink_uri(gridRef, MemorySegment.NULL, 0, length);
        if (result == GHOSTTY_SUCCESS && length.get(ValueLayout.JAVA_LONG, 0) == 0) {
            return null;
        }
        if (result != GHOSTTY_SUCCESS && result != ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
            requireGhosttySuccess(result, "ghostty_grid_ref_hyperlink_uri");
        }

        var byteLength = Math.toIntExact(length.get(ValueLayout.JAVA_LONG, 0));
        if (byteLength == 0) {
            return null;
        }

        var buffer = arena.allocate(byteLength);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_grid_ref_hyperlink_uri(gridRef, buffer, byteLength, length),
                "ghostty_grid_ref_hyperlink_uri");
        return new String(buffer.asSlice(0, byteLength).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private MemorySegment gridRef(Selection.ScreenPoint point, Arena arena) {
        var gridRef = GhosttyGridRef.allocate(arena);
        GhosttyGridRef.size(gridRef, GhosttyGridRef.sizeof());
        return writeGridRef(arena, point, gridRef) ? gridRef : null;
    }

    private boolean isSoftWrappedBoundary(
            Selection.ScreenPoint left,
            Selection.ScreenPoint right,
            int columns,
            Arena arena) {
        if (left.y() == right.y()) {
            return Math.abs(left.x() - right.x()) == 1;
        }
        if (left.y() + 1 == right.y() && left.x() == columns - 1 && right.x() == 0) {
            return rowWrap(left, arena);
        }
        if (right.y() + 1 == left.y() && right.x() == columns - 1 && left.x() == 0) {
            return rowWrap(right, arena);
        }
        return false;
    }

    private static Selection.ScreenPoint nearestMappedPoint(List<Selection.ScreenPoint> points, int index, int direction) {
        for (var i = Math.clamp(index, 0, Math.max(0, points.size() - 1)); i >= 0 && i < points.size(); i += direction) {
            var point = points.get(i);
            if (point != null) {
                return point;
            }
        }
        return null;
    }

    private static boolean regionMatchesIgnoreCase(CharSequence text, int fromIndex, String query) {
        for (var i = 0; i < query.length(); i++) {
            if (Character.toLowerCase(text.charAt(fromIndex + i)) != Character.toLowerCase(query.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Selection.ScreenPoint next(Selection.ScreenPoint point, int columns) {
        return point.x() + 1 < columns
                ? new Selection.ScreenPoint(point.x() + 1, point.y())
                : new Selection.ScreenPoint(0, point.y() + 1);
    }

    private static Selection.ScreenPoint previous(Selection.ScreenPoint point, int columns) {
        if (point.x() > 0) {
            return new Selection.ScreenPoint(point.x() - 1, point.y());
        }
        return point.y() > 0
                ? new Selection.ScreenPoint(columns - 1, point.y() - 1)
                : point;
    }

    private static boolean contains(Selection selection, Selection.ScreenPoint point) {
        var normalized = selection.normalized();
        if (normalized.isEmpty()) {
            return false;
        }

        var from = normalized.from();
        var to = normalized.to();
        if (normalized.rectangle()) {
            return point.y() >= from.y()
                    && point.y() <= to.y()
                    && point.x() >= from.x()
                    && point.x() <= to.x();
        }
        if (point.y() < from.y() || point.y() > to.y()) {
            return false;
        }
        if (point.y() == from.y() && point.x() < from.x()) {
            return false;
        }
        return point.y() != to.y() || point.x() <= to.x();
    }

    private static void drawSearchHighlightBackground(
            GraphicsContext graphics,
            double x,
            double y,
            TerminalView.FontMetrics metrics,
            TerminalTheme theme,
            boolean selected) {
        var width = metrics.cellWidthPx();
        graphics.setFill(selected ? theme.searchCurrentMatchColor() : theme.searchMatchColor());
        graphics.fillRect(x, y, width, metrics.cellHeightPx());
    }

    private static void drawSearchHighlightBorders(
            GraphicsContext graphics,
            double y,
            TerminalView.FontMetrics metrics,
            TerminalTheme theme,
            SearchResult searchResult,
            int selectedSearchMatch,
            int row) {
        var spans = searchResult.rows().get(row);
        if (spans == null) {
            return;
        }
        graphics.setLineWidth(1.0);
        for (var span : spans) {
            var selected = span.matchIndex() == selectedSearchMatch;
            var x = span.fromX() * metrics.cellWidthPx();
            var width = (span.toX() - span.fromX() + 1) * metrics.cellWidthPx();
            graphics.setStroke(selected ? theme.searchCurrentMatchBorderColor() : theme.searchMatchBorderColor());
            graphics.strokeRect(x + 0.5, y + 0.5, width, metrics.cellHeightPx());
        }
    }

    private static void drawBlockElement(
            GraphicsContext graphics,
            int codePoint,
            double x,
            double y,
            TerminalView.FontMetrics metrics,
            Color color) {
        switch (codePoint) {
            case 0x2580 -> drawUpperBlock(graphics, x, y, metrics, 0.5);
            case 0x2581 -> drawLowerBlock(graphics, x, y, metrics, 0.125);
            case 0x2582 -> drawLowerBlock(graphics, x, y, metrics, 0.25);
            case 0x2583 -> drawLowerBlock(graphics, x, y, metrics, 0.375);
            case 0x2584 -> drawLowerBlock(graphics, x, y, metrics, 0.5);
            case 0x2585 -> drawLowerBlock(graphics, x, y, metrics, 0.625);
            case 0x2586 -> drawLowerBlock(graphics, x, y, metrics, 0.75);
            case 0x2587 -> drawLowerBlock(graphics, x, y, metrics, 0.875);
            case 0x2588 -> drawFullBlock(graphics, x, y, metrics);
            case 0x2589 -> drawLeftBlock(graphics, x, y, metrics, 0.875);
            case 0x258A -> drawLeftBlock(graphics, x, y, metrics, 0.75);
            case 0x258B -> drawLeftBlock(graphics, x, y, metrics, 0.625);
            case 0x258C -> drawLeftBlock(graphics, x, y, metrics, 0.5);
            case 0x258D -> drawLeftBlock(graphics, x, y, metrics, 0.375);
            case 0x258E -> drawLeftBlock(graphics, x, y, metrics, 0.25);
            case 0x258F -> drawLeftBlock(graphics, x, y, metrics, 0.125);
            case 0x2590 -> drawRightBlock(graphics, x, y, metrics, 0.5);
            case 0x2591 -> drawShadeBlock(graphics, x, y, metrics, color, 0.25);
            case 0x2592 -> drawShadeBlock(graphics, x, y, metrics, color, 0.5);
            case 0x2593 -> drawShadeBlock(graphics, x, y, metrics, color, 0.75);
            case 0x2594 -> drawUpperBlock(graphics, x, y, metrics, 0.125);
            case 0x2595 -> drawRightBlock(graphics, x, y, metrics, 0.125);
            case 0x2596 -> drawQuadrants(graphics, x, y, metrics, true, false, false, false);
            case 0x2597 -> drawQuadrants(graphics, x, y, metrics, false, true, false, false);
            case 0x2598 -> drawQuadrants(graphics, x, y, metrics, false, false, true, false);
            case 0x2599 -> drawQuadrants(graphics, x, y, metrics, true, true, true, false);
            case 0x259A -> drawQuadrants(graphics, x, y, metrics, false, true, true, false);
            case 0x259B -> drawQuadrants(graphics, x, y, metrics, true, false, true, true);
            case 0x259C -> drawQuadrants(graphics, x, y, metrics, false, true, true, true);
            case 0x259D -> drawQuadrants(graphics, x, y, metrics, false, false, false, true);
            case 0x259E -> drawQuadrants(graphics, x, y, metrics, true, false, false, true);
            case 0x259F -> drawQuadrants(graphics, x, y, metrics, true, true, false, true);
            default -> throw new IllegalArgumentException("Expected block element codepoint: " + codePoint);
        }
    }

    private static void drawUpperBlock(GraphicsContext graphics, double x, double y, TerminalView.FontMetrics metrics, double fraction) {
        graphics.fillRect(x, y, metrics.cellWidthPx(), blockSize(metrics.cellHeightPx(), fraction));
    }

    private static void drawLowerBlock(GraphicsContext graphics, double x, double y, TerminalView.FontMetrics metrics, double fraction) {
        var height = blockSize(metrics.cellHeightPx(), fraction);
        graphics.fillRect(x, y + metrics.cellHeightPx() - height, metrics.cellWidthPx(), height);
    }

    private static void drawLeftBlock(GraphicsContext graphics, double x, double y, TerminalView.FontMetrics metrics, double fraction) {
        graphics.fillRect(x, y, blockSize(metrics.cellWidthPx(), fraction), metrics.cellHeightPx());
    }

    private static void drawRightBlock(GraphicsContext graphics, double x, double y, TerminalView.FontMetrics metrics, double fraction) {
        var width = blockSize(metrics.cellWidthPx(), fraction);
        graphics.fillRect(x + metrics.cellWidthPx() - width, y, width, metrics.cellHeightPx());
    }

    private static void drawFullBlock(GraphicsContext graphics, double x, double y, TerminalView.FontMetrics metrics) {
        graphics.fillRect(x, y, metrics.cellWidthPx(), metrics.cellHeightPx());
    }

    private static void drawShadeBlock(
            GraphicsContext graphics,
            double x,
            double y,
            TerminalView.FontMetrics metrics,
            Color color,
            double opacity) {
        graphics.setFill(color.deriveColor(0, 1, 1, color.getOpacity() * opacity));
        graphics.fillRect(x, y, metrics.cellWidthPx(), metrics.cellHeightPx());
    }

    private static void drawQuadrants(
            GraphicsContext graphics,
            double x,
            double y,
            TerminalView.FontMetrics metrics,
            boolean bottomLeft,
            boolean bottomRight,
            boolean topLeft,
            boolean topRight) {
        var leftWidth = metrics.cellWidthPx() - blockSize(metrics.cellWidthPx(), 0.5);
        var rightWidth = blockSize(metrics.cellWidthPx(), 0.5);
        var topHeight = metrics.cellHeightPx() - blockSize(metrics.cellHeightPx(), 0.5);
        var bottomHeight = blockSize(metrics.cellHeightPx(), 0.5);
        var rightX = x + leftWidth;
        var bottomY = y + topHeight;
        if (topLeft) {
            graphics.fillRect(x, y, leftWidth, topHeight);
        }
        if (topRight) {
            graphics.fillRect(rightX, y, rightWidth, topHeight);
        }
        if (bottomLeft) {
            graphics.fillRect(x, bottomY, leftWidth, bottomHeight);
        }
        if (bottomRight) {
            graphics.fillRect(rightX, bottomY, rightWidth, bottomHeight);
        }
    }

    private static int blockSize(int cellSize, double fraction) {
        return Math.max(1, (int) Math.round(cellSize * fraction));
    }

    private void drawTextDecorations(
            GraphicsContext graphics,
            double x,
            double y,
            TerminalView.FontMetrics metrics,
            MemorySegment style,
            boolean selected,
            Color baseTextColor,
            Color textColor,
            TerminalTheme theme,
            double faintFactor) {
        var underline = GhosttyStyle.underline(style);
        if (underline == ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_NONE()
                && !GhosttyStyle.strikethrough(style)
                && !GhosttyStyle.overline(style)) {
            return;
        }

        graphics.setLineWidth(1.0);
        graphics.setLineDashes(null);
        if (underline != ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_NONE()) {
            var underlineBaseColor = selected
                    ? theme.selectionText()
                    : decorationColor(GhosttyStyle.underline_color(style), baseTextColor, theme);
            graphics.setStroke(applyOpacity(underlineBaseColor, faintFactor));
            drawUnderline(graphics, x, y + metrics.cellHeightPx() - 2.5, metrics.cellWidthPx(), underline);
        }
        graphics.setStroke(textColor);
        if (GhosttyStyle.strikethrough(style)) {
            var strikeY = y + Math.round(metrics.cellHeightPx() * 0.55);
            graphics.strokeLine(x, strikeY, x + metrics.cellWidthPx(), strikeY);
        }
        if (GhosttyStyle.overline(style)) {
            graphics.strokeLine(x, y + 1.5, x + metrics.cellWidthPx(), y + 1.5);
        }
        graphics.setLineWidth(1.0);
        graphics.setLineDashes(null);
    }

    private static void drawUnderline(GraphicsContext graphics, double x, double y, double width, int underline) {
        if (underline == ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_DOUBLE()) {
            graphics.strokeLine(x, y, x + width, y);
            graphics.strokeLine(x, y + 2, x + width, y + 2);
            return;
        }
        if (underline == ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_CURLY()) {
            var points = Math.max(8, (int) Math.ceil(width) + 1);
            var xs = new double[points];
            var ys = new double[points];
            for (var i = 0; i < points; i++) {
                var progress = points == 1 ? 0.0 : i / (double) (points - 1);
                xs[i] = x + width * progress;
                ys[i] = y + 0.5 + Math.sin(progress * Math.PI * 4.0) * 1.0;
            }
            graphics.strokePolyline(xs, ys, points);
            return;
        }
        if (underline == ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_DOTTED()) {
            graphics.setFill(graphics.getStroke());
            var dotY = Math.round(y);
            var spacing = 3.0;
            var phase = 1.0;
            var endX = x + width - 1;
            for (var dotX = Math.ceil((x - phase) / spacing) * spacing + phase; dotX <= endX; dotX += spacing) {
                graphics.fillRect(dotX, dotY, 1, 1);
            }
            return;
        }
        if (underline == ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_DASHED()) {
            graphics.setLineDashes(4.0, 3.0);
            graphics.strokeLine(x, y, x + width, y);
            graphics.setLineDashes(null);
            return;
        }
        graphics.strokeLine(x, y, x + width, y);
    }

    private static Color applyOpacity(Color color, double factor) {
        if (factor >= 1.0) {
            return color;
        }

        return color.deriveColor(0, 1, 1, color.getOpacity() * factor);
    }

    static final class SearchDocumentBuilder {
        private final int columns;
        private final int rows;
        private final StringBuilder text = new StringBuilder();
        private final ArrayList<Selection.ScreenPoint> points = new ArrayList<>();
        private int nextRow;
        private boolean complete;

        private SearchDocumentBuilder(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
            complete = columns <= 0 || rows <= 0;
        }

        int columns() {
            return columns;
        }

        private int rows() {
            return rows;
        }

        private StringBuilder text() {
            return text;
        }

        private ArrayList<Selection.ScreenPoint> points() {
            return points;
        }

        boolean complete() {
            return complete;
        }

    }

    record SearchBatch(List<Selection> matches, int searchedUntil) {

    }

    record MatchedLinkMatcher(int index, TerminalLinkMatcher link, MatchResult match, Selection selection) {
    }

    record LogicalLine(String text, List<Selection.ScreenPoint> points) {
        private static final LogicalLine EMPTY = new LogicalLine("", List.of());

        static LogicalLine empty() {
            return EMPTY;
        }

        Selection selection(int start, int end) {
            if (start >= end || points.isEmpty()) {
                return Selection.empty();
            }

            var from = nearestMappedPoint(points, start, 1);
            var to = nearestMappedPoint(points, end - 1, -1);
            return from == null || to == null ? Selection.empty() : Selection.linear(from, to);
        }
    }

    record SearchResult(List<Selection> matches, Map<Integer, List<SearchSpan>> rows) {

        private static final SearchResult EMPTY = new SearchResult(List.of(), Map.of());

        static SearchResult empty() {
            return EMPTY;
        }

        static SearchResult append(SearchResult result, List<Selection> matches, int columns) {
            if (matches.isEmpty()) {
                return result;
            }

            var allMatches = result.matches instanceof ArrayList<Selection> mutableMatches
                    ? mutableMatches
                    : new ArrayList<>(result.matches);
            var rows = result.rows instanceof HashMap<Integer, List<SearchSpan>> mutableRows
                    ? mutableRows
                    : new HashMap<>(result.rows);
            var firstMatchIndex = allMatches.size();
            allMatches.addAll(matches);
            appendRows(rows, matches, firstMatchIndex, columns);
            return new SearchResult(allMatches, rows);
        }

        private static void appendRows(
                Map<Integer, List<SearchSpan>> rows,
                List<Selection> matches,
                int firstMatchIndex,
                int columns) {
            for (var i = 0; i < matches.size(); i++) {
                var normalized = matches.get(i).normalized();
                var matchIndex = firstMatchIndex + i;
                for (var y = normalized.from().y(); y <= normalized.to().y(); y++) {
                    var fromX = y == normalized.from().y() ? normalized.from().x() : 0;
                    var toX = y == normalized.to().y() ? normalized.to().x() : Math.max(0, columns - 1);
                    rows.computeIfAbsent(y, _ -> new ArrayList<>()).add(new SearchSpan(fromX, toX, matchIndex));
                }
            }
        }

        private int matchIndex(Selection.ScreenPoint point) {
            var spans = rows.get(point.y());
            if (spans == null) {
                return -1;
            }
            for (var span : spans) {
                if (point.x() >= span.fromX() && point.x() <= span.toX()) {
                    return span.matchIndex();
                }
            }
            return -1;
        }

    }

    private record SearchSpan(int fromX, int toX, int matchIndex) {

    }

    private record CachedLinks(int viewportTop, int viewportBottom, SearchResult result) {

    }

    private record CellGrapheme(String text, boolean spacerCell) {

        private static CellGrapheme empty() {
            return new CellGrapheme("", false);
        }

        private static CellGrapheme spacer() {
            return new CellGrapheme("", true);
        }

    }

    private Color decorationColor(MemorySegment color, Color fallback, TerminalTheme theme) {
        var tag = GhosttyStyleColor.tag(color);
        if (tag == ghostty_vt_h.GHOSTTY_STYLE_COLOR_RGB()) {
            return toFxColor(GhosttyStyleColorValue.rgb(GhosttyStyleColor.value(color)));
        }
        if (tag == ghostty_vt_h.GHOSTTY_STYLE_COLOR_PALETTE()) {
            var index = Byte.toUnsignedInt(GhosttyStyleColorValue.palette(GhosttyStyleColor.value(color)));
            var palette = theme.palette();
            return index < palette.size() ? palette.get(index) : builtInPalette.get(index);
        }
        return fallback;
    }

    private void renderPreedit(
            GraphicsContext graphics,
            TerminalView.FontMetrics metrics,
            KeyInput.Preedit preedit,
            CursorInfo cursor,
            TerminalTheme theme) {
        if (cursor == null || preedit.text().isEmpty()) {
            return;
        }

        var codePointCount = preedit.text().codePointCount(0, preedit.text().length());
        var x = cursor.x() * (double) metrics.cellWidthPx();
        var y = cursor.y() * (double) metrics.cellHeightPx();
        graphics.setFont(metrics.regular());
        graphics.setFill(theme.foreground());
        graphics.fillText(preedit.text(), x, y + metrics.baselineOffsetPx());
        graphics.setStroke(theme.foreground());
        for (var i = 0; i < codePointCount; i++) {
            var cellX = x + i * (double) metrics.cellWidthPx();
            graphics.strokeLine(
                    cellX,
                    y + metrics.cellHeightPx() - 1,
                    cellX + metrics.cellWidthPx(),
                    y + metrics.cellHeightPx() - 1);
        }
        var caret = Math.clamp(preedit.caretPosition(), 0, codePointCount);
        var caretX = x + caret * (double) metrics.cellWidthPx();
        graphics.strokeLine(caretX, y + 2, caretX, y + metrics.cellHeightPx() - 2);
    }

    private void renderCursor(
            GraphicsContext graphics,
            TerminalView.FontMetrics metrics,
            MemorySegment colors,
            boolean focused,
            TerminalTheme theme,
            boolean blinkVisible,
            CursorInfo cursor,
            String cursorText,
            boolean cursorBold,
            boolean cursorItalic) {
        if (cursor == null) {
            return;
        }
        if (focused && cursor.blinking() && !blinkVisible) {
            return;
        }

        var cursorColor = GhosttyRenderStateColors.cursor_has_value(colors)
                ? toFxColor(GhosttyRenderStateColors.cursor(colors))
                : theme.cursorColor();
        var cursorPixelX = cursor.x() * (double) metrics.cellWidthPx();
        var cursorPixelY = cursor.y() * (double) metrics.cellHeightPx();
        var cursorWidth = metrics.cellWidthPx();
        var cursorHeight = metrics.cellHeightPx();
        var cursorStyle = focused ? cursor.style() : CURSOR_STYLE_BLOCK_HOLLOW;

        switch (cursorStyle) {
            case CURSOR_STYLE_BAR -> {
                graphics.setFill(cursorColor);
                graphics.fillRect(cursorPixelX, cursorPixelY, Math.max(1, Math.ceil(cursorWidth / 6.0)), cursorHeight);
            }
            case CURSOR_STYLE_UNDERLINE -> {
                graphics.setFill(cursorColor);
                graphics.fillRect(
                        cursorPixelX,
                        cursorPixelY + cursorHeight - Math.max(1, cursorHeight / 8.0),
                        cursorWidth,
                        Math.max(1, cursorHeight / 8.0));
            }
            case CURSOR_STYLE_BLOCK_HOLLOW -> {
                graphics.setStroke(cursorColor);
                graphics.strokeRect(
                        cursorPixelX + 0.5,
                        cursorPixelY + 0.5,
                        Math.max(0, cursorWidth - 1),
                        Math.max(0, cursorHeight - 1));
            }
            default -> {
                graphics.setFill(cursorColor.deriveColor(0, 1, 1, BLOCK_CURSOR_ALPHA));
                graphics.fillRect(cursorPixelX, cursorPixelY, cursorWidth, cursorHeight);
                if (!cursorText.isEmpty()) {
                    graphics.setFill(theme.cursorText());
                    graphics.setFont(metrics.forStyle(cursorBold, cursorItalic));
                    graphics.fillText(cursorText, cursorPixelX, cursorPixelY + metrics.baselineOffsetPx());
                }
            }
        }
    }

    private void updateRenderState() {
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_update(renderState, terminal),
                "ghostty_render_state_update");
    }

    private CursorInfo cursorInfo(Arena arena) {
        var cursorVisible = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_get(
                        renderState,
                        ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE(),
                        cursorVisible),
                "ghostty_render_state_get(cursor_visible)");
        if (!cursorVisible.get(ValueLayout.JAVA_BOOLEAN, 0)) {
            return null;
        }

        var cursorInViewport = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_get(
                        renderState,
                        ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE(),
                        cursorInViewport),
                "ghostty_render_state_get(cursor_viewport_has_value)");
        if (!cursorInViewport.get(ValueLayout.JAVA_BOOLEAN, 0)) {
            return null;
        }

        var cursorX = arena.allocate(ValueLayout.JAVA_SHORT);
        var cursorY = arena.allocate(ValueLayout.JAVA_SHORT);
        var cursorStyle = arena.allocate(ValueLayout.JAVA_INT);
        var cursorBlinking = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_get(
                        renderState,
                        ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X(),
                        cursorX),
                "ghostty_render_state_get(cursor_viewport_x)");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_get(
                        renderState,
                        ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y(),
                        cursorY),
                "ghostty_render_state_get(cursor_viewport_y)");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_get(
                        renderState,
                        ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE(),
                        cursorStyle),
                "ghostty_render_state_get(cursor_visual_style)");
        requireGhosttySuccess(
                ghostty_vt_h.ghostty_render_state_get(
                        renderState,
                        ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR_BLINKING(),
                        cursorBlinking),
                "ghostty_render_state_get(cursor_blinking)");
        return new CursorInfo(
                Short.toUnsignedInt(cursorX.get(ValueLayout.JAVA_SHORT, 0)),
                Short.toUnsignedInt(cursorY.get(ValueLayout.JAVA_SHORT, 0)),
                cursorStyle.get(ValueLayout.JAVA_INT, 0),
                cursorBlinking.get(ValueLayout.JAVA_BOOLEAN, 0));
    }

    private static MemorySegment newAddress(Arena arena, String operation, Allocator allocator) {
        var pointer = arena.allocate(ValueLayout.ADDRESS);
        requireGhosttySuccess(allocator.allocate(MemorySegment.NULL, pointer), operation);
        return pointer.get(ValueLayout.ADDRESS, 0);
    }

    private static Color toFxColor(MemorySegment color) {
        return Color.rgb(
                Byte.toUnsignedInt(GhosttyColorRgb.r(color)),
                Byte.toUnsignedInt(GhosttyColorRgb.g(color)),
                Byte.toUnsignedInt(GhosttyColorRgb.b(color)));
    }

    private static MemorySegment toNativeColor(Color color, SegmentAllocator allocator) {
        var result = GhosttyColorRgb.allocate(allocator);
        GhosttyColorRgb.r(result, toNativeColorChannel(color.getRed()));
        GhosttyColorRgb.g(result, toNativeColorChannel(color.getGreen()));
        GhosttyColorRgb.b(result, toNativeColorChannel(color.getBlue()));
        return result;
    }

    private MemorySegment nativePalette(TerminalTheme theme, Arena arena) {
        var result = GhosttyColorRgb.allocateArray(PALETTE_SIZE, arena);
        var palette = theme.palette();
        for (var i = 0; i < PALETTE_SIZE; i++) {
            var color = i < palette.size() ? palette.get(i) : builtInPalette.get(i);
            MemorySegment.copy(
                    toNativeColor(color, arena),
                    0,
                    GhosttyColorRgb.asSlice(result, i),
                    0,
                    GhosttyColorRgb.sizeof());
        }
        return result;
    }

    private static byte toNativeColorChannel(double value) {
        return (byte) Math.clamp((int) Math.round(value * 255), 0, 255);
    }

    private static void requireGhosttySuccess(int result, String operation) {
        if (result != GHOSTTY_SUCCESS) {
            throw new IllegalStateException(operation + " failed with result=" + result);
        }
    }

    private static final class SelectionGestureClock {
        private SelectionGestureClock() {
        }

        private static long now() {
            return HostPlatform.CURRENT.os() == HostPlatform.OS.WINDOWS
                    ? WindowsPerformanceCounter.now()
                    : System.nanoTime();
        }
    }

    private static final class WindowsPerformanceCounter {
        private static final MethodHandle QUERY_PERFORMANCE_COUNTER = Linker.nativeLinker().downcallHandle(
                SymbolLookup.libraryLookup("kernel32", Arena.global()).findOrThrow("QueryPerformanceCounter"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        private WindowsPerformanceCounter() {
        }

        private static long now() {
            try (var arena = Arena.ofConfined()) {
                var counter = arena.allocate(ValueLayout.JAVA_LONG);
                var result = (int) QUERY_PERFORMANCE_COUNTER.invokeExact(counter);
                if (result == 0) {
                    throw new IllegalStateException("QueryPerformanceCounter failed");
                }
                return counter.get(ValueLayout.JAVA_LONG, 0);
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Throwable throwable) {
                throw new IllegalStateException("QueryPerformanceCounter failed", throwable);
            }
        }
    }

    @FunctionalInterface
    private interface Allocator {

        int allocate(MemorySegment allocator, MemorySegment out);
    }

    record ScrollbarInfo(
            long total,
            long visible,
            long offset,
            double gutterLeft,
            double height,
            double thumbY,
            double thumbHeight) {

        boolean scrollable() {
            return total > visible && visible > 0 && height > 0;
        }

        long scrollableRows() {
            return Math.max(0, total - visible);
        }

        double movableHeight() {
            return Math.max(0, height - thumbHeight);
        }

        boolean containsThumb(double y) {
            return thumbHeight > 0 && y >= thumbY && y <= thumbY + thumbHeight;
        }

        double thumbGrabRatio(double y) {
            if (thumbHeight <= 0) {
                return 0;
            }
            return Math.clamp((y - thumbY) / thumbHeight, 0.0, 1.0);
        }

        long targetOffsetForTrackPress(double y) {
            if (!scrollable()) {
                return offset;
            }

            var movableHeight = movableHeight();
            if (movableHeight == 0) {
                return 0;
            }

            var thumbTop = Math.clamp(y - thumbHeight / 2.0, 0.0, movableHeight);
            return (long) ((thumbTop / movableHeight) * scrollableRows());
        }

        long targetOffsetForDrag(double y, double thumbGrabRatio) {
            if (!scrollable()) {
                return offset;
            }

            var movableHeight = movableHeight();
            if (movableHeight == 0) {
                return 0;
            }

            var grabOffset = Math.clamp(thumbGrabRatio, 0.0, 1.0) * thumbHeight;
            var thumbTop = Math.clamp(y - grabOffset, 0.0, movableHeight);
            return (long) ((thumbTop / movableHeight) * scrollableRows());
        }

        double thumbX() {
            return gutterLeft + TerminalView.SCROLLBAR_MARGIN_PX;
        }
    }

    enum MouseButton {
        UNKNOWN(ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_UNKNOWN()),
        LEFT(ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_LEFT()),
        RIGHT(ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_RIGHT()),
        MIDDLE(ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_MIDDLE());

        private final int ghosttyButton;

        MouseButton(int ghosttyButton) {
            this.ghosttyButton = ghosttyButton;
        }

        int ghosttyButton() {
            return ghosttyButton;
        }
    }

    enum SelectionAutoscroll {
        NONE,
        UP,
        DOWN
    }

    record BlinkState(boolean cursor, boolean text) {

        static BlinkState none() {
            return new BlinkState(false, false);
        }

    }

    private record CursorInfo(int x, int y, int style, boolean blinking) {

    }

    private record CachedKittyImage(int imageId, Image image) {

    }

    private record KittyPlacement(
            int imageId,
            int z,
            double sourceX,
            double sourceY,
            double sourceWidth,
            double sourceHeight,
            double destinationX,
            double destinationY,
            double destinationWidth,
            double destinationHeight,
            Image image) {

    }

    record CellHit(
            Selection.ScreenPoint screenPoint,
            int viewportX,
            int viewportY,
            double cellOffsetX,
            boolean hasText,
            String hyperlinkUri) {

    }

    record Size(int columns, int rows, int widthPx, int heightPx) {

    }
}
