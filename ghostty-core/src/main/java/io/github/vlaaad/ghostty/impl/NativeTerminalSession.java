package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.Cell;
import io.github.vlaaad.ghostty.CellContentTag;
import io.github.vlaaad.ghostty.CellSemantic;
import io.github.vlaaad.ghostty.CellWidth;
import io.github.vlaaad.ghostty.ColorPalette;
import io.github.vlaaad.ghostty.ColorScheme;
import io.github.vlaaad.ghostty.ColorValue;
import io.github.vlaaad.ghostty.DeviceAttributes;
import io.github.vlaaad.ghostty.Frame;
import io.github.vlaaad.ghostty.FrameColors;
import io.github.vlaaad.ghostty.FrameCursor;
import io.github.vlaaad.ghostty.FrameCursorStyle;
import io.github.vlaaad.ghostty.FrameDirty;
import io.github.vlaaad.ghostty.FrameRun;
import io.github.vlaaad.ghostty.FrameRow;
import io.github.vlaaad.ghostty.FrameStyle;
import io.github.vlaaad.ghostty.Hyperlink;
import io.github.vlaaad.ghostty.KittyKeyboardFlags;
import io.github.vlaaad.ghostty.MouseTrackingMode;
import io.github.vlaaad.ghostty.Point;
import io.github.vlaaad.ghostty.PtyWriter;
import io.github.vlaaad.ghostty.Row;
import io.github.vlaaad.ghostty.RowCoordinateSpace;
import io.github.vlaaad.ghostty.RowFlags;
import io.github.vlaaad.ghostty.RowSemanticPrompt;
import io.github.vlaaad.ghostty.Screen;
import io.github.vlaaad.ghostty.ScreenKind;
import io.github.vlaaad.ghostty.Style;
import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalEvents;
import io.github.vlaaad.ghostty.TerminalMode;
import io.github.vlaaad.ghostty.TerminalQueries;
import io.github.vlaaad.ghostty.TerminalScrollViewport;
import io.github.vlaaad.ghostty.TerminalScrollbar;
import io.github.vlaaad.ghostty.TerminalSession;
import io.github.vlaaad.ghostty.TerminalSize;
import io.github.vlaaad.ghostty.UnderlineStyle;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import java.util.concurrent.ExecutionException;

final class NativeTerminalSession implements TerminalSession {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Linker LINKER = Linker.nativeLinker();
    private static final String EMPTY_TEXT = "";

    private static final MemoryLayout DEVICE_ATTRIBUTES_PRIMARY_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("conformance_level"),
        MemoryLayout.sequenceLayout(64, ValueLayout.JAVA_SHORT).withName("features"),
        ValueLayout.JAVA_SHORT.withName("_pad0"),
        ValueLayout.JAVA_SHORT.withName("_pad1"),
        ValueLayout.JAVA_SHORT.withName("_pad2"),
        NativeRuntime.SIZE_T_LAYOUT.withName("num_features")
    );
    private static final MemoryLayout DEVICE_ATTRIBUTES_SECONDARY_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("device_type"),
        ValueLayout.JAVA_SHORT.withName("firmware_version"),
        ValueLayout.JAVA_SHORT.withName("rom_cartridge")
    );
    private static final MemoryLayout DEVICE_ATTRIBUTES_TERTIARY_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("unit_id")
    );
    private static final MemoryLayout DEVICE_ATTRIBUTES_LAYOUT = MemoryLayout.structLayout(
        DEVICE_ATTRIBUTES_PRIMARY_LAYOUT.withName("primary"),
        DEVICE_ATTRIBUTES_SECONDARY_LAYOUT.withName("secondary"),
        ValueLayout.JAVA_SHORT.withName("_pad0"),
        DEVICE_ATTRIBUTES_TERTIARY_LAYOUT.withName("tertiary"),
        ValueLayout.JAVA_INT.withName("_tail_pad")
    );
    private static final long DEVICE_ATTRIBUTES_PRIMARY_CONFORMANCE_OFFSET = DEVICE_ATTRIBUTES_LAYOUT.byteOffset(
        groupElement("primary"),
        groupElement("conformance_level")
    );
    private static final long DEVICE_ATTRIBUTES_PRIMARY_FEATURES_OFFSET = DEVICE_ATTRIBUTES_LAYOUT.byteOffset(
        groupElement("primary"),
        groupElement("features")
    );
    private static final long DEVICE_ATTRIBUTES_PRIMARY_NUM_FEATURES_OFFSET = DEVICE_ATTRIBUTES_LAYOUT.byteOffset(
        groupElement("primary"),
        groupElement("num_features")
    );
    private static final long DEVICE_ATTRIBUTES_SECONDARY_DEVICE_TYPE_OFFSET = DEVICE_ATTRIBUTES_LAYOUT.byteOffset(
        groupElement("secondary"),
        groupElement("device_type")
    );
    private static final long DEVICE_ATTRIBUTES_SECONDARY_FIRMWARE_OFFSET = DEVICE_ATTRIBUTES_LAYOUT.byteOffset(
        groupElement("secondary"),
        groupElement("firmware_version")
    );
    private static final long DEVICE_ATTRIBUTES_SECONDARY_ROM_OFFSET = DEVICE_ATTRIBUTES_LAYOUT.byteOffset(
        groupElement("secondary"),
        groupElement("rom_cartridge")
    );
    private static final long DEVICE_ATTRIBUTES_TERTIARY_UNIT_ID_OFFSET = DEVICE_ATTRIBUTES_LAYOUT.byteOffset(
        groupElement("tertiary"),
        groupElement("unit_id")
    );

    private final NativeTerminalBindings bindings;
    private final TerminalConfig config;
    private final PtyWriter ptyWriter;
    private final TerminalQueries queries;
    private final TerminalEvents events;
    private final ExecutorService actor;
    private final ExecutorService eventExecutor;
    private final AtomicReference<Thread> actorThread = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Arena callbackArena = Arena.ofShared();
    private final MemorySegment emptyNativeString = NativeString.allocate(callbackArena);
    private final MemorySegment writePtyCallback;
    private final MemorySegment bellCallback;
    private final MemorySegment enquiryCallback;
    private final MemorySegment xtversionCallback;
    private final MemorySegment titleChangedCallback;
    private final MemorySegment sizeCallback;
    private final MemorySegment colorSchemeCallback;
    private final MemorySegment deviceAttributesCallback;
    private final NativeFrameSnapshotBindings frameSnapshotBindings;
    private final MemorySegment frameSnapshotLibraryPath;
    private final MemorySegment frameSnapshotView = callbackArena.allocate(NativeFrameSnapshotBindings.SNAPSHOT_VIEW_LAYOUT);

    private volatile MemorySegment terminal = MemorySegment.NULL;
    private volatile MemorySegment frameSnapshot = MemorySegment.NULL;
    private volatile MemorySegment renderState = MemorySegment.NULL;
    private volatile MemorySegment renderRowIterator = MemorySegment.NULL;
    private volatile MemorySegment renderRowCells = MemorySegment.NULL;
    private volatile Throwable callbackFailure;
    private volatile ColorScheme colorSchemeOverride;
    private int pendingBells;
    private boolean pendingTitleChanged;
    private long frameRevision;
    private Frame cachedFrame;

    NativeTerminalSession(
        NativeTerminalBindings bindings,
        NativeFrameSnapshotBindings frameSnapshotBindings,
        String ghosttyVtLibraryPath,
        TerminalConfig config,
        PtyWriter ptyWriter,
        TerminalQueries queries,
        TerminalEvents events
    ) {
        this.bindings = bindings;
        this.frameSnapshotBindings = frameSnapshotBindings;
        this.frameSnapshotLibraryPath = nativeUtf8(ghosttyVtLibraryPath);
        this.config = config;
        this.ptyWriter = ptyWriter;
        this.queries = queries;
        this.events = events;
        emptyNativeString.set(NativeTerminalBindings.C_POINTER, 0, MemorySegment.NULL);
        emptyNativeString.set(NativeRuntime.SIZE_T_LAYOUT, ValueLayout.ADDRESS.byteSize(), 0L);
        actor = Executors.newSingleThreadExecutor(threadFactory("ghostty-terminal-actor", actorThread));
        eventExecutor = Executors.newSingleThreadExecutor(threadFactory("ghostty-terminal-events", null));
        writePtyCallback = upcall(
            "writePtyCallback",
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, long.class),
            FunctionDescriptor.ofVoid(
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER,
                NativeRuntime.SIZE_T_LAYOUT
            )
        );
        bellCallback = upcall(
            "bellCallback",
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.ofVoid(NativeTerminalBindings.C_POINTER, NativeTerminalBindings.C_POINTER)
        );
        enquiryCallback = upcall(
            "enquiryCallback",
            MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.of(NativeString.LAYOUT, NativeTerminalBindings.C_POINTER, NativeTerminalBindings.C_POINTER)
        );
        xtversionCallback = upcall(
            "xtversionCallback",
            MethodType.methodType(MemorySegment.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.of(NativeString.LAYOUT, NativeTerminalBindings.C_POINTER, NativeTerminalBindings.C_POINTER)
        );
        titleChangedCallback = upcall(
            "titleChangedCallback",
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.ofVoid(NativeTerminalBindings.C_POINTER, NativeTerminalBindings.C_POINTER)
        );
        sizeCallback = upcall(
            "sizeCallback",
            MethodType.methodType(boolean.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER
            )
        );
        colorSchemeCallback = upcall(
            "colorSchemeCallback",
            MethodType.methodType(boolean.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER
            )
        );
        deviceAttributesCallback = upcall(
            "deviceAttributesCallback",
            MethodType.methodType(boolean.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
            FunctionDescriptor.of(
                ValueLayout.JAVA_BOOLEAN,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER
            )
        );
        terminal = callActor(this::openTerminal);
    }

    private MemorySegment upcall(String methodName, MethodType methodType, FunctionDescriptor descriptor) {
        try {
            return LINKER.upcallStub(LOOKUP.findVirtual(NativeTerminalSession.class, methodName, methodType).bindTo(this), descriptor, callbackArena);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Unable to create upcall stub for " + methodName, exception);
        }
    }

    private ThreadFactory threadFactory(String name, AtomicReference<Thread> ref) {
        return runnable -> {
            var thread = Thread.ofPlatform().name(name).unstarted(runnable);
            if (ref != null) {
                ref.set(thread);
            }
            return thread;
        };
    }

    private MemorySegment openTerminal() {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(NativeTerminalBindings.C_POINTER);
            var options = arena.allocate(NativeTerminalBindings.TERMINAL_OPTIONS_LAYOUT);
            options.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.TERMINAL_OPTIONS_COLS_OFFSET, (short) config.columns());
            options.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.TERMINAL_OPTIONS_ROWS_OFFSET, (short) config.rows());
            options.set(NativeRuntime.SIZE_T_LAYOUT, NativeTerminalBindings.TERMINAL_OPTIONS_SCROLLBACK_OFFSET, config.maxScrollback());
            NativeRuntime.invokeStatus(bindings.ghosttyTerminalNew, "ghostty_terminal_new", MemorySegment.NULL, out, options);
            var handle = out.get(NativeTerminalBindings.C_POINTER, 0);
            setCallback(handle, NativeTerminalBindings.OPT_WRITE_PTY, writePtyCallback);
            setCallback(handle, NativeTerminalBindings.OPT_BELL, bellCallback);
            setCallback(handle, NativeTerminalBindings.OPT_ENQUIRY, enquiryCallback);
            setCallback(handle, NativeTerminalBindings.OPT_XTVERSION, xtversionCallback);
            setCallback(handle, NativeTerminalBindings.OPT_TITLE_CHANGED, titleChangedCallback);
            setCallback(handle, NativeTerminalBindings.OPT_SIZE, sizeCallback);
            setCallback(handle, NativeTerminalBindings.OPT_COLOR_SCHEME, colorSchemeCallback);
            setCallback(handle, NativeTerminalBindings.OPT_DEVICE_ATTRIBUTES, deviceAttributesCallback);
            renderState = newHandle(bindings.ghosttyRenderStateNew, "ghostty_render_state_new");
            renderRowIterator = newHandle(bindings.ghosttyRenderStateRowIteratorNew, "ghostty_render_state_row_iterator_new");
            renderRowCells = newHandle(bindings.ghosttyRenderStateRowCellsNew, "ghostty_render_state_row_cells_new");
            frameSnapshot = newHandle(
                frameSnapshotBindings.ghosttyfxFrameSnapshotNew,
                "ghosttyfx_frame_snapshot_new",
                frameSnapshotLibraryPath
            );
            return handle;
        }
    }

    private MemorySegment newHandle(MethodHandle constructor, String action, Object... arguments) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(NativeTerminalBindings.C_POINTER);
            var args = new Object[arguments.length + 2];
            args[0] = MemorySegment.NULL;
            System.arraycopy(arguments, 0, args, 1, arguments.length);
            args[args.length - 1] = out;
            NativeRuntime.invokeStatus(constructor, action, args);
            return out.get(NativeTerminalBindings.C_POINTER, 0);
        }
    }

    private MemorySegment nativeUtf8(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var data = callbackArena.allocate(bytes.length + 1L);
        data.asSlice(0, bytes.length).copyFrom(MemorySegment.ofArray(bytes));
        data.set(ValueLayout.JAVA_BYTE, bytes.length, (byte) 0);
        return data;
    }

    private void setCallback(MemorySegment handle, int option, MemorySegment callback) {
        NativeRuntime.invokeStatus(bindings.ghosttyTerminalSet, "ghostty_terminal_set", handle, option, callback);
    }

    private void checkClosed() {
        if (closed.get()) {
            throw new IllegalStateException("Terminal session is closed");
        }
    }

    private void checkNotActorThread() {
        var thread = actorThread.get();
        if (thread != null && Thread.currentThread() == thread) {
            throw new IllegalStateException("Reentrant TerminalSession access from the actor thread is not allowed");
        }
    }

    private void recordCallbackFailure(Throwable throwable) {
        if (callbackFailure == null) {
            callbackFailure = throwable;
        }
    }

    private void throwIfCallbackFailed() {
        var failure = callbackFailure;
        if (failure != null) {
            callbackFailure = null;
            throw NativeRuntime.sneakyThrow(failure);
        }
    }

    private <T> T callActor(ThrowingSupplier<T> supplier) {
        checkClosed();
        checkNotActorThread();
        Future<T> future = actor.submit(supplier::get);
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw NativeRuntime.sneakyThrow(exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            throw NativeRuntime.sneakyThrow(cause == null ? exception : cause);
        }
    }

    private void dispatchEvents(boolean stateChanged) {
        var bells = pendingBells;
        pendingBells = 0;
        var titleChanged = pendingTitleChanged;
        pendingTitleChanged = false;
        var title = titleChanged ? currentTitle() : null;
        if (bells == 0 && !titleChanged && !stateChanged) {
            return;
        }
        eventExecutor.execute(() -> {
            for (var i = 0; i < bells; i++) {
                events.bell(this);
            }
            if (titleChanged) {
                events.titleChanged(this, title);
            }
            if (stateChanged) {
                events.stateChanged(this);
            }
        });
    }

    private void mutate(Runnable runnable, boolean stateChanged) {
        callActor(() -> {
            runnable.run();
            throwIfCallbackFailed();
            dispatchEvents(stateChanged);
            return null;
        });
    }

    private String currentTitle() {
        return borrowedString(NativeTerminalBindings.DATA_TITLE);
    }

    private String currentPwd() {
        return borrowedString(NativeTerminalBindings.DATA_PWD);
    }

    private String borrowedString(int data) {
        try (var arena = Arena.ofConfined()) {
            var out = NativeString.allocate(arena);
            NativeRuntime.invokeStatus(bindings.ghosttyTerminalGet, "ghostty_terminal_get", terminal, data, out);
            return NativeString.readUtf8(out);
        }
    }

    private int getU16(int data) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(ValueLayout.JAVA_SHORT);
            NativeRuntime.invokeStatus(bindings.ghosttyTerminalGet, "ghostty_terminal_get", terminal, data, out);
            return Short.toUnsignedInt(out.get(ValueLayout.JAVA_SHORT, 0));
        }
    }

    private int getInt(int data) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(ValueLayout.JAVA_INT);
            NativeRuntime.invokeStatus(bindings.ghosttyTerminalGet, "ghostty_terminal_get", terminal, data, out);
            return out.get(ValueLayout.JAVA_INT, 0);
        }
    }

    private ColorPalette palette(int data) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(NativeTerminalBindings.PALETTE_LAYOUT);
            NativeRuntime.invokeStatus(bindings.ghosttyTerminalGet, "ghostty_terminal_get", terminal, data, out);
            var colors = new ColorValue[256];
            var stride = NativeTerminalBindings.RGB_LAYOUT.byteSize();
            for (var i = 0; i < colors.length; i++) {
                colors[i] = rgbColor(out.asSlice(i * stride, stride));
            }
            return new ColorPalette(colors);
        }
    }

    private TerminalSize currentSize() {
        var columns = getU16(NativeTerminalBindings.DATA_COLS);
        var rows = getU16(NativeTerminalBindings.DATA_ROWS);
        var widthPx = getInt(NativeTerminalBindings.DATA_WIDTH_PX);
        var heightPx = getInt(NativeTerminalBindings.DATA_HEIGHT_PX);
        return new TerminalSize(
            columns,
            rows,
            columns == 0 ? 0 : widthPx / columns,
            rows == 0 ? 0 : heightPx / rows
        );
    }

    private ColorValue.RgbColor rgbColor(MemorySegment segment) {
        return new ColorValue.RgbColor(
            Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_R_OFFSET)),
            Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_G_OFFSET)),
            Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_B_OFFSET))
        );
    }

    private int packedRgb(MemorySegment segment) {
        return (Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_R_OFFSET)) << 16)
            | (Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_G_OFFSET)) << 8)
            | Byte.toUnsignedInt(segment.get(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_B_OFFSET));
    }

    private ColorValue styleColor(MemorySegment segment, long offset) {
        var tag = segment.get(ValueLayout.JAVA_INT, offset + NativeTerminalBindings.STYLE_COLOR_TAG_OFFSET);
        return switch (tag) {
            case NativeTerminalBindings.STYLE_COLOR_NONE -> new ColorValue.DefaultColor();
            case NativeTerminalBindings.STYLE_COLOR_PALETTE -> new ColorValue.PaletteColor(Byte.toUnsignedInt(
                segment.get(ValueLayout.JAVA_BYTE, offset + NativeTerminalBindings.STYLE_COLOR_PALETTE_OFFSET)
            ));
            case NativeTerminalBindings.STYLE_COLOR_RGB -> rgbColor(segment.asSlice(offset + NativeTerminalBindings.STYLE_COLOR_RGB_OFFSET, NativeTerminalBindings.RGB_LAYOUT.byteSize()));
            default -> throw new IllegalStateException("Unknown style color tag: " + tag);
        };
    }

    private int resolveStyleColor(MemorySegment segment, long offset, int[] palette, int fallback) {
        var tag = segment.get(ValueLayout.JAVA_INT, offset + NativeTerminalBindings.STYLE_COLOR_TAG_OFFSET);
        return switch (tag) {
            case NativeTerminalBindings.STYLE_COLOR_NONE -> fallback;
            case NativeTerminalBindings.STYLE_COLOR_PALETTE -> palette[Byte.toUnsignedInt(
                segment.get(ValueLayout.JAVA_BYTE, offset + NativeTerminalBindings.STYLE_COLOR_PALETTE_OFFSET)
            )];
            case NativeTerminalBindings.STYLE_COLOR_RGB -> packedRgb(segment.asSlice(
                offset + NativeTerminalBindings.STYLE_COLOR_RGB_OFFSET,
                NativeTerminalBindings.RGB_LAYOUT.byteSize()
            ));
            default -> throw new IllegalStateException("Unknown style color tag: " + tag);
        };
    }

    private Style style(MemorySegment segment) {
        var underlineStyle = UnderlineStyle.values()[segment.get(ValueLayout.JAVA_INT, NativeTerminalBindings.STYLE_UNDERLINE_OFFSET)];
        return new Style(
            styleColor(segment, NativeTerminalBindings.STYLE_FG_COLOR_OFFSET),
            styleColor(segment, NativeTerminalBindings.STYLE_BG_COLOR_OFFSET),
            styleColor(segment, NativeTerminalBindings.STYLE_UNDERLINE_COLOR_OFFSET),
            underlineStyle,
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_BOLD_OFFSET),
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_FAINT_OFFSET),
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_ITALIC_OFFSET),
            underlineStyle != UnderlineStyle.NONE,
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_BLINK_OFFSET),
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_INVERSE_OFFSET),
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_INVISIBLE_OFFSET),
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_STRIKETHROUGH_OFFSET),
            segment.get(ValueLayout.JAVA_BOOLEAN, NativeTerminalBindings.STYLE_OVERLINE_OFFSET)
        );
    }

    private MouseTrackingMode mouseTrackingMode() {
        if (modeValue(TerminalMode.ANY_MOUSE).orElse(false)) {
            return MouseTrackingMode.ANY;
        }
        if (modeValue(TerminalMode.BUTTON_MOUSE).orElse(false)) {
            return MouseTrackingMode.BUTTON;
        }
        if (modeValue(TerminalMode.NORMAL_MOUSE).orElse(false)) {
            return MouseTrackingMode.NORMAL;
        }
        if (modeValue(TerminalMode.X10_MOUSE).orElse(false)) {
            return MouseTrackingMode.X10;
        }
        return MouseTrackingMode.NONE;
    }

    private KittyKeyboardFlags kittyKeyboardFlags() {
        var bits = getInt(NativeTerminalBindings.DATA_KITTY_KEYBOARD_FLAGS);
        return new KittyKeyboardFlags(
            (bits & NativeTerminalBindings.KITTY_DISAMBIGUATE) != 0,
            (bits & NativeTerminalBindings.KITTY_REPORT_EVENTS) != 0,
            (bits & NativeTerminalBindings.KITTY_REPORT_ALTERNATES) != 0,
            (bits & NativeTerminalBindings.KITTY_REPORT_ALL) != 0,
            (bits & NativeTerminalBindings.KITTY_REPORT_ASSOCIATED) != 0
        );
    }

    private TerminalScrollbar scrollbar() {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(NativeTerminalBindings.TERMINAL_SCROLLBAR_LAYOUT);
            NativeRuntime.invokeStatus(bindings.ghosttyTerminalGet, "ghostty_terminal_get", terminal, NativeTerminalBindings.DATA_SCROLLBAR, out);
            return new TerminalScrollbar(
                out.get(ValueLayout.JAVA_LONG, NativeTerminalBindings.TERMINAL_SCROLLBAR_TOTAL_OFFSET),
                out.get(ValueLayout.JAVA_LONG, NativeTerminalBindings.TERMINAL_SCROLLBAR_OFFSET_OFFSET),
                out.get(ValueLayout.JAVA_LONG, NativeTerminalBindings.TERMINAL_SCROLLBAR_LEN_OFFSET)
            );
        }
    }

    private MemorySegment point(Arena arena, Point point) {
        var segment = arena.allocate(NativeTerminalBindings.POINT_LAYOUT);
        switch (point) {
            case Point.ActivePoint active -> {
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_TAG_OFFSET, NativeTerminalBindings.POINT_ACTIVE);
                segment.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.POINT_X_OFFSET, (short) active.column());
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_Y_OFFSET, active.row());
            }
            case Point.ViewportPoint viewport -> {
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_TAG_OFFSET, NativeTerminalBindings.POINT_VIEWPORT);
                segment.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.POINT_X_OFFSET, (short) viewport.column());
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_Y_OFFSET, viewport.row());
            }
            case Point.ScreenPoint screen -> {
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_TAG_OFFSET, NativeTerminalBindings.POINT_SCREEN);
                segment.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.POINT_X_OFFSET, (short) screen.column());
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_Y_OFFSET, screen.row());
            }
            case Point.HistoryPoint history -> {
                if (history.row() > 0xFFFF_FFFFL) {
                    throw new IllegalArgumentException("history row out of range: " + history.row());
                }
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_TAG_OFFSET, NativeTerminalBindings.POINT_HISTORY);
                segment.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.POINT_X_OFFSET, (short) history.column());
                segment.set(ValueLayout.JAVA_INT, NativeTerminalBindings.POINT_Y_OFFSET, (int) history.row());
            }
            default -> throw new IllegalArgumentException("Unknown point type: " + point.getClass().getName());
        }
        return segment;
    }

    private Point pointForRow(long rowIndex, RowCoordinateSpace space) {
        if (rowIndex < 0) {
            throw new IllegalArgumentException("rowIndex must be non-negative");
        }
        return switch (space) {
            case ACTIVE -> new Point.ActivePoint(0, Math.toIntExact(rowIndex));
            case VIEWPORT -> new Point.ViewportPoint(0, Math.toIntExact(rowIndex));
            case SCREEN -> new Point.ScreenPoint(0, Math.toIntExact(rowIndex));
        };
    }

    private MemorySegment gridRef(Arena arena, Point point) {
        var ref = arena.allocate(NativeTerminalBindings.GRID_REF_LAYOUT);
        ref.set(NativeRuntime.SIZE_T_LAYOUT, NativeTerminalBindings.GRID_REF_SIZE_OFFSET, NativeTerminalBindings.GRID_REF_LAYOUT.byteSize());
        try {
            NativeRuntime.invokeStatus(bindings.ghosttyTerminalGridRef, "ghostty_terminal_grid_ref", terminal, point(arena, point), ref);
            return ref;
        } catch (ResultException exception) {
            if (exception.result == NativeRuntime.GHOSTTY_INVALID_VALUE || exception.result == NativeRuntime.GHOSTTY_NO_VALUE) {
                return MemorySegment.NULL;
            }
            throw exception;
        }
    }

    private long gridRefCell(Arena arena, MemorySegment ref) {
        var out = arena.allocate(ValueLayout.JAVA_LONG);
        NativeRuntime.invokeStatus(bindings.ghosttyGridRefCell, "ghostty_grid_ref_cell", ref, out);
        return out.get(ValueLayout.JAVA_LONG, 0);
    }

    private long gridRefRow(Arena arena, MemorySegment ref) {
        var out = arena.allocate(ValueLayout.JAVA_LONG);
        NativeRuntime.invokeStatus(bindings.ghosttyGridRefRow, "ghostty_grid_ref_row", ref, out);
        return out.get(ValueLayout.JAVA_LONG, 0);
    }

    private String graphemes(Arena arena, MemorySegment ref) {
        var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
        try {
            NativeRuntime.invokeStatus(bindings.ghosttyGridRefGraphemes, "ghostty_grid_ref_graphemes", ref, MemorySegment.NULL, 0L, outLen);
            return "";
        } catch (ResultException exception) {
            if (exception.result != NativeRuntime.GHOSTTY_OUT_OF_SPACE) {
                throw exception;
            }
        }
        var required = outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0);
        if (required == 0) {
            return "";
        }
        var codepoints = arena.allocate(ValueLayout.JAVA_INT, required);
        NativeRuntime.invokeStatus(bindings.ghosttyGridRefGraphemes, "ghostty_grid_ref_graphemes", ref, codepoints, required, outLen);
        var count = Math.toIntExact(outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0));
        var builder = new StringBuilder();
        for (var i = 0; i < count; i++) {
            builder.appendCodePoint(codepoints.getAtIndex(ValueLayout.JAVA_INT, i));
        }
        return builder.toString();
    }

    private Hyperlink hyperlink(Arena arena, MemorySegment ref) {
        var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
        try {
            NativeRuntime.invokeStatus(bindings.ghosttyGridRefHyperlinkUri, "ghostty_grid_ref_hyperlink_uri", ref, MemorySegment.NULL, 0L, outLen);
            return null;
        } catch (ResultException exception) {
            if (exception.result != NativeRuntime.GHOSTTY_OUT_OF_SPACE) {
                throw exception;
            }
        }
        var required = outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0);
        if (required == 0) {
            return null;
        }
        var out = arena.allocate(required);
        NativeRuntime.invokeStatus(bindings.ghosttyGridRefHyperlinkUri, "ghostty_grid_ref_hyperlink_uri", ref, out, required, outLen);
        return new Hyperlink(new String(out.reinterpret(outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8));
    }

    private Style cellStyle(Arena arena, MemorySegment ref) {
        var out = arena.allocate(NativeTerminalBindings.STYLE_LAYOUT);
        out.set(NativeRuntime.SIZE_T_LAYOUT, NativeTerminalBindings.STYLE_SIZE_OFFSET, NativeTerminalBindings.STYLE_LAYOUT.byteSize());
        NativeRuntime.invokeStatus(bindings.ghosttyGridRefStyle, "ghostty_grid_ref_style", ref, out);
        return style(out);
    }

    private int cellInt(Arena arena, long cell, int data) {
        var out = arena.allocate(ValueLayout.JAVA_INT);
        NativeRuntime.invokeStatus(bindings.ghosttyCellGet, "ghostty_cell_get", cell, data, out);
        return out.get(ValueLayout.JAVA_INT, 0);
    }

    private boolean cellBoolean(Arena arena, long cell, int data) {
        var out = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        NativeRuntime.invokeStatus(bindings.ghosttyCellGet, "ghostty_cell_get", cell, data, out);
        return out.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    private int cellUnsignedByte(Arena arena, long cell, int data) {
        var out = arena.allocate(ValueLayout.JAVA_BYTE);
        NativeRuntime.invokeStatus(bindings.ghosttyCellGet, "ghostty_cell_get", cell, data, out);
        return Byte.toUnsignedInt(out.get(ValueLayout.JAVA_BYTE, 0));
    }

    private boolean rowBoolean(Arena arena, long row, int data) {
        var out = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        NativeRuntime.invokeStatus(bindings.ghosttyRowGet, "ghostty_row_get", row, data, out);
        return out.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    private int rowInt(Arena arena, long row, int data) {
        var out = arena.allocate(ValueLayout.JAVA_INT);
        NativeRuntime.invokeStatus(bindings.ghosttyRowGet, "ghostty_row_get", row, data, out);
        return out.get(ValueLayout.JAVA_INT, 0);
    }

    private ColorValue backgroundFill(Arena arena, long cell, CellContentTag tag) {
        return switch (tag) {
            case BG_COLOR_PALETTE -> new ColorValue.PaletteColor(cellUnsignedByte(arena, cell, NativeTerminalBindings.CELL_DATA_COLOR_PALETTE));
            case BG_COLOR_RGB -> {
                var out = arena.allocate(NativeTerminalBindings.RGB_LAYOUT);
                NativeRuntime.invokeStatus(bindings.ghosttyCellGet, "ghostty_cell_get", cell, NativeTerminalBindings.CELL_DATA_COLOR_RGB, out);
                yield rgbColor(out);
            }
            default -> new ColorValue.DefaultColor();
        };
    }

    private Cell cell(Arena arena, long rawCell, MemorySegment ref, int column) {
        var tag = CellContentTag.values()[cellInt(arena, rawCell, NativeTerminalBindings.CELL_DATA_CONTENT_TAG)];
        return new Cell(
            column,
            graphemes(arena, ref),
            cellInt(arena, rawCell, NativeTerminalBindings.CELL_DATA_CODEPOINT),
            tag,
            CellWidth.values()[cellInt(arena, rawCell, NativeTerminalBindings.CELL_DATA_WIDE)],
            backgroundFill(arena, rawCell, tag),
            cellStyle(arena, ref),
            hyperlink(arena, ref),
            CellSemantic.values()[cellInt(arena, rawCell, NativeTerminalBindings.CELL_DATA_SEMANTIC_CONTENT)],
            cellBoolean(arena, rawCell, NativeTerminalBindings.CELL_DATA_PROTECTED)
        );
    }

    private Optional<Cell> cellInternal(Point point) {
        try (var arena = Arena.ofConfined()) {
            var ref = gridRef(arena, point);
            if (ref == MemorySegment.NULL) {
                return Optional.empty();
            }
            var column = switch (point) {
                case Point.ActivePoint active -> active.column();
                case Point.ViewportPoint viewport -> viewport.column();
                case Point.ScreenPoint screen -> screen.column();
                case Point.HistoryPoint history -> history.column();
            };
            return Optional.of(cell(arena, gridRefCell(arena, ref), ref, column));
        }
    }

    private Optional<Row> rowInternal(long rowIndex, RowCoordinateSpace space) {
        try (var arena = Arena.ofConfined()) {
            var ref = gridRef(arena, pointForRow(rowIndex, space));
            if (ref == MemorySegment.NULL) {
                return Optional.empty();
            }
            var rawRow = gridRefRow(arena, ref);
            var size = currentSize();
            var cells = new ArrayList<Cell>(size.columns());
            for (var column = 0; column < size.columns(); column++) {
                var point = switch (space) {
                    case ACTIVE -> new Point.ActivePoint(column, Math.toIntExact(rowIndex));
                    case VIEWPORT -> new Point.ViewportPoint(column, Math.toIntExact(rowIndex));
                    case SCREEN -> new Point.ScreenPoint(column, Math.toIntExact(rowIndex));
                };
                var cellRef = gridRef(arena, point);
                if (cellRef == MemorySegment.NULL) {
                    break;
                }
                cells.add(cell(arena, gridRefCell(arena, cellRef), cellRef, column));
            }
            return Optional.of(new Row(
                rowIndex,
                new RowFlags(
                    rowBoolean(arena, rawRow, NativeTerminalBindings.ROW_DATA_WRAP),
                    rowBoolean(arena, rawRow, NativeTerminalBindings.ROW_DATA_WRAP_CONTINUATION),
                    rowBoolean(arena, rawRow, NativeTerminalBindings.ROW_DATA_GRAPHEME),
                    rowBoolean(arena, rawRow, NativeTerminalBindings.ROW_DATA_STYLED),
                    rowBoolean(arena, rawRow, NativeTerminalBindings.ROW_DATA_HYPERLINK),
                    rowBoolean(arena, rawRow, NativeTerminalBindings.ROW_DATA_KITTY_VIRTUAL_PLACEHOLDER),
                    rowBoolean(arena, rawRow, NativeTerminalBindings.ROW_DATA_DIRTY)
                ),
                RowSemanticPrompt.values()[rowInt(arena, rawRow, NativeTerminalBindings.ROW_DATA_SEMANTIC_PROMPT)],
                List.copyOf(cells)
            ));
        }
    }

    private Optional<Boolean> modeValue(TerminalMode mode) {
        try (var arena = Arena.ofConfined()) {
            var out = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            try {
                NativeRuntime.invokeStatus(bindings.ghosttyTerminalModeGet, "ghostty_terminal_mode_get", terminal, mode.packedValue(), out);
                return Optional.of(out.get(ValueLayout.JAVA_BOOLEAN, 0));
            } catch (ResultException exception) {
                if (exception.result == NativeRuntime.GHOSTTY_INVALID_VALUE || exception.result == NativeRuntime.GHOSTTY_NO_VALUE) {
                    return Optional.empty();
                }
                throw exception;
            }
        }
    }

    private Screen screenInternal(ScreenKind kind) {
        var size = currentSize();
        if (kind != ScreenKind.values()[getInt(NativeTerminalBindings.DATA_ACTIVE_SCREEN)]) {
            return new Screen(kind, size.columns(), size.rows(), List.of());
        }
        var rows = new ArrayList<Row>(size.rows());
        for (var row = 0; row < size.rows(); row++) {
            rowInternal(row, RowCoordinateSpace.VIEWPORT).ifPresent(rows::add);
        }
        return new Screen(kind, size.columns(), size.rows(), List.copyOf(rows));
    }

    private int renderStateU16(Arena arena, int data) {
        var out = arena.allocate(ValueLayout.JAVA_SHORT);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateGet, "ghostty_render_state_get", renderState, data, out);
        return Short.toUnsignedInt(out.get(ValueLayout.JAVA_SHORT, 0));
    }

    private int renderStateInt(Arena arena, int data) {
        var out = arena.allocate(ValueLayout.JAVA_INT);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateGet, "ghostty_render_state_get", renderState, data, out);
        return out.get(ValueLayout.JAVA_INT, 0);
    }

    private boolean renderStateBoolean(Arena arena, int data) {
        var out = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateGet, "ghostty_render_state_get", renderState, data, out);
        return out.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    private boolean renderRowBoolean(Arena arena, int data) {
        var out = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateRowGet, "ghostty_render_state_row_get", renderRowIterator, data, out);
        return out.get(ValueLayout.JAVA_BOOLEAN, 0);
    }

    private long renderRowRaw(Arena arena) {
        var out = arena.allocate(ValueLayout.JAVA_LONG);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateRowGet, "ghostty_render_state_row_get", renderRowIterator, NativeTerminalBindings.RENDER_STATE_ROW_DATA_RAW, out);
        return out.get(ValueLayout.JAVA_LONG, 0);
    }

    private void populateRenderRowIterator(Arena arena) {
        var out = arena.allocate(NativeTerminalBindings.C_POINTER);
        out.set(NativeTerminalBindings.C_POINTER, 0, renderRowIterator);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateGet, "ghostty_render_state_get", renderState, NativeTerminalBindings.RENDER_STATE_DATA_ROW_ITERATOR, out);
        renderRowIterator = out.get(NativeTerminalBindings.C_POINTER, 0);
    }

    private void populateRenderRowCells(Arena arena) {
        var out = arena.allocate(NativeTerminalBindings.C_POINTER);
        out.set(NativeTerminalBindings.C_POINTER, 0, renderRowCells);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateRowGet, "ghostty_render_state_row_get", renderRowIterator, NativeTerminalBindings.RENDER_STATE_ROW_DATA_CELLS, out);
        renderRowCells = out.get(NativeTerminalBindings.C_POINTER, 0);
    }

    private long renderRowCellRaw(Arena arena) {
        var out = arena.allocate(ValueLayout.JAVA_LONG);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateRowCellsGet, "ghostty_render_state_row_cells_get", renderRowCells, NativeTerminalBindings.RENDER_STATE_ROW_CELLS_DATA_RAW, out);
        return out.get(ValueLayout.JAVA_LONG, 0);
    }

    private int renderRowCellInt(Arena arena, int data) {
        var out = arena.allocate(ValueLayout.JAVA_INT);
        NativeRuntime.invokeStatus(bindings.ghosttyRenderStateRowCellsGet, "ghostty_render_state_row_cells_get", renderRowCells, data, out);
        return out.get(ValueLayout.JAVA_INT, 0);
    }

    private int renderRowCellColor(Arena arena, int data, int fallback) {
        var out = arena.allocate(NativeTerminalBindings.RGB_LAYOUT);
        try {
            NativeRuntime.invokeStatus(bindings.ghosttyRenderStateRowCellsGet, "ghostty_render_state_row_cells_get", renderRowCells, data, out);
            return packedRgb(out);
        } catch (ResultException exception) {
            if (exception.result == NativeRuntime.GHOSTTY_INVALID_VALUE || exception.result == NativeRuntime.GHOSTTY_NO_VALUE) {
                return fallback;
            }
            throw exception;
        }
    }

    private int frameStyleId(List<FrameStyle> styles, HashMap<FrameStyle, Integer> styleIds, FrameStyle style) {
        var existing = styleIds.get(style);
        if (existing != null) {
            return existing;
        }
        var index = styles.size();
        styles.add(style);
        styleIds.put(style, index);
        return index;
    }

    private List<FrameRow> cleanFrameRows(List<FrameRow> rows) {
        var cleaned = new ArrayList<FrameRow>(rows.size());
        var changed = false;
        for (var row : rows) {
            var clean = row.withDirty(false);
            cleaned.add(clean);
            changed |= clean != row;
        }
        return changed ? List.copyOf(cleaned) : rows;
    }

    private boolean sameFrameScalars(
        Frame frame,
        TerminalSize size,
        ScreenKind activeScreen,
        FrameCursor cursor,
        FrameColors colors,
        MouseTrackingMode mouseTracking,
        KittyKeyboardFlags kittyKeyboardFlags,
        TerminalScrollbar scrollbar,
        String title,
        String pwd
    ) {
        return frame.size().equals(size)
            && frame.activeScreen() == activeScreen
            && frame.cursor().equals(cursor)
            && frame.colors().equals(colors)
            && frame.mouseTracking() == mouseTracking
            && frame.kittyKeyboardFlags().equals(kittyKeyboardFlags)
            && frame.scrollbar().equals(scrollbar)
            && frame.title().equals(title)
            && frame.workingDirectory().equals(pwd);
    }

    private MemorySegment captureFrameSnapshot() {
        frameSnapshotView.set(
            NativeRuntime.SIZE_T_LAYOUT,
            NativeFrameSnapshotBindings.SNAPSHOT_VIEW_SIZE_OFFSET,
            NativeFrameSnapshotBindings.SNAPSHOT_VIEW_LAYOUT.byteSize()
        );
        NativeRuntime.invokeStatus(
            frameSnapshotBindings.ghosttyfxFrameSnapshotCapture,
            "ghosttyfx_frame_snapshot_capture",
            frameSnapshot,
            terminal,
            frameSnapshotView
        );
        var data = frameSnapshotView.get(NativeTerminalBindings.C_POINTER, NativeFrameSnapshotBindings.SNAPSHOT_VIEW_DATA_OFFSET);
        var len = frameSnapshotView.get(NativeRuntime.SIZE_T_LAYOUT, NativeFrameSnapshotBindings.SNAPSHOT_VIEW_LEN_OFFSET);
        return data.reinterpret(len);
    }

    private RowFlags rowFlags(int flags) {
        return new RowFlags(
            (flags & NativeFrameSnapshotLayout.ROW_FLAG_WRAPPED) != 0,
            (flags & NativeFrameSnapshotLayout.ROW_FLAG_WRAP_CONTINUATION) != 0,
            (flags & NativeFrameSnapshotLayout.ROW_FLAG_GRAPHEME) != 0,
            (flags & NativeFrameSnapshotLayout.ROW_FLAG_STYLED) != 0,
            (flags & NativeFrameSnapshotLayout.ROW_FLAG_HYPERLINK) != 0,
            (flags & NativeFrameSnapshotLayout.ROW_FLAG_KITTY_VIRTUAL_PLACEHOLDER) != 0,
            (flags & NativeFrameSnapshotLayout.ROW_FLAG_DIRTY) != 0
        );
    }

    private List<FrameRow> frameRows(
        MemorySegment snapshot,
        TerminalSize size,
        List<FrameStyle> styles,
        HashMap<FrameStyle, Integer> styleIds
    ) {
        var rowCount = NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.ROW_COUNT_OFFSET);
        var styleCount = NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.STYLE_COUNT_OFFSET);
        var runCount = NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.RUN_COUNT_OFFSET);
        var textByteCount = NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.TEXT_BYTE_COUNT_OFFSET);
        var rowsOffset = NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.ROWS_OFFSET_OFFSET);
        var stylesOffset = NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.STYLES_OFFSET_OFFSET);
        var runsOffset = NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.RUNS_OFFSET_OFFSET);
        var textBytesOffset = NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.TEXT_BYTES_OFFSET_OFFSET);

        var resolvedStyles = new FrameStyle[styleCount];
        for (var styleIndex = 0; styleIndex < styleCount; styleIndex++) {
            var style = NativeFrameSnapshotLayout.style(
                snapshot,
                stylesOffset + (long) styleIndex * NativeFrameSnapshotLayout.STYLE_ENTRY_SIZE
            );
            resolvedStyles[styleIndex] = styles.get(frameStyleId(styles, styleIds, style));
        }

        var previousRows = cachedFrame == null ? List.<FrameRow>of() : cachedFrame.rows();
        var rows = new ArrayList<FrameRow>(size.rows());
        for (var rowIndex = 0; rowIndex < size.rows(); rowIndex++) {
            var rowOffset = rowsOffset + (long) rowIndex * NativeFrameSnapshotLayout.ROW_ENTRY_SIZE;
            var rowFlags = NativeFrameSnapshotLayout.u32(snapshot, rowOffset + NativeFrameSnapshotLayout.ROW_FLAGS_OFFSET);
            var rowDirty = (rowFlags & NativeFrameSnapshotLayout.ROW_FLAG_DIRTY) != 0;
            if (!rowDirty && rowIndex < previousRows.size()) {
                rows.add(previousRows.get(rowIndex).withDirty(false));
                continue;
            }

            var rowRunStart = NativeFrameSnapshotLayout.u32(snapshot, rowOffset + NativeFrameSnapshotLayout.ROW_RUN_START_OFFSET);
            var rowRunCount = NativeFrameSnapshotLayout.u32(snapshot, rowOffset + NativeFrameSnapshotLayout.ROW_RUN_COUNT_OFFSET);
            var runs = new ArrayList<FrameRun>(rowRunCount);
            for (var runIndex = 0; runIndex < rowRunCount; runIndex++) {
                var runOffset = runsOffset + (long) (rowRunStart + runIndex) * NativeFrameSnapshotLayout.RUN_ENTRY_SIZE;
                var styleIndex = NativeFrameSnapshotLayout.u32(snapshot, runOffset + NativeFrameSnapshotLayout.RUN_STYLE_INDEX_OFFSET);
                var textStart = NativeFrameSnapshotLayout.u32(snapshot, runOffset + NativeFrameSnapshotLayout.RUN_TEXT_START_OFFSET);
                var textLength = NativeFrameSnapshotLayout.u32(snapshot, runOffset + NativeFrameSnapshotLayout.RUN_TEXT_LENGTH_OFFSET);
                runs.add(new FrameRun(
                    NativeFrameSnapshotLayout.utf8(snapshot, textBytesOffset + textStart, textLength),
                    resolvedStyles[styleIndex],
                    NativeFrameSnapshotLayout.u32(snapshot, runOffset + NativeFrameSnapshotLayout.RUN_COLUMNS_OFFSET)
                ));
            }

            rows.add(new FrameRow(rowIndex, rowDirty, rowFlags(rowFlags), runs));
        }
        return rows;
    }

    private Frame frameInternal() {
        var snapshot = captureFrameSnapshot();

        var size = new TerminalSize(
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.COLUMNS_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.ROWS_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CELL_WIDTH_PX_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CELL_HEIGHT_PX_OFFSET)
        );
        var activeScreen = ScreenKind.values()[NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.ACTIVE_SCREEN_OFFSET)];
        var cursor = new FrameCursor(
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CURSOR_VISIBLE_OFFSET) != 0,
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CURSOR_BLINKING_OFFSET) != 0,
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CURSOR_PASSWORD_INPUT_OFFSET) != 0,
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CURSOR_IN_VIEWPORT_OFFSET) != 0,
            snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, NativeFrameSnapshotLayout.CURSOR_X_OFFSET),
            snapshot.get(ValueLayout.JAVA_INT_UNALIGNED, NativeFrameSnapshotLayout.CURSOR_Y_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CURSOR_WIDE_TAIL_OFFSET) != 0,
            FrameCursorStyle.values()[NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.CURSOR_STYLE_OFFSET)]
        );
        var colors = new FrameColors(
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.COLORS_FOREGROUND_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.COLORS_BACKGROUND_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.COLORS_CURSOR_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.COLORS_CURSOR_EXPLICIT_OFFSET) != 0
        );
        var dirty = FrameDirty.values()[NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.DIRTY_OFFSET)];
        var mouseTracking = MouseTrackingMode.values()[NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.MOUSE_TRACKING_OFFSET)];
        var kittyFlags = NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.KITTY_FLAGS_OFFSET);
        var kittyKeyboardFlags = new KittyKeyboardFlags(
            (kittyFlags & NativeTerminalBindings.KITTY_DISAMBIGUATE) != 0,
            (kittyFlags & NativeTerminalBindings.KITTY_REPORT_EVENTS) != 0,
            (kittyFlags & NativeTerminalBindings.KITTY_REPORT_ALTERNATES) != 0,
            (kittyFlags & NativeTerminalBindings.KITTY_REPORT_ALL) != 0,
            (kittyFlags & NativeTerminalBindings.KITTY_REPORT_ASSOCIATED) != 0
        );
        var scrollbar = new TerminalScrollbar(
            NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.SCROLLBAR_TOTAL_OFFSET),
            NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.SCROLLBAR_OFFSET_OFFSET),
            NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.SCROLLBAR_LENGTH_OFFSET)
        );
        var title = NativeFrameSnapshotLayout.utf8(
            snapshot,
            NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.TITLE_OFFSET_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.TITLE_LENGTH_OFFSET)
        );
        var pwd = NativeFrameSnapshotLayout.utf8(
            snapshot,
            NativeFrameSnapshotLayout.u64(snapshot, NativeFrameSnapshotLayout.PWD_OFFSET_OFFSET),
            NativeFrameSnapshotLayout.u32(snapshot, NativeFrameSnapshotLayout.PWD_LENGTH_OFFSET)
        );

        if (dirty == FrameDirty.CLEAN && cachedFrame != null && cachedFrame.dirty() == FrameDirty.CLEAN
            && sameFrameScalars(cachedFrame, size, activeScreen, cursor, colors, mouseTracking, kittyKeyboardFlags, scrollbar, title, pwd)) {
            return cachedFrame;
        }

        var frameStyles = dirty == FrameDirty.FULL || cachedFrame == null
            ? new ArrayList<FrameStyle>()
            : new ArrayList<>(cachedFrame.styles());
        var styleIds = new HashMap<FrameStyle, Integer>(Math.max(16, frameStyles.size() * 2 + 1));
        for (var i = 0; i < frameStyles.size(); i++) {
            styleIds.put(frameStyles.get(i), i);
        }
        var rows = switch (dirty) {
            case CLEAN -> cachedFrame == null ? List.<FrameRow>of() : cleanFrameRows(cachedFrame.rows());
            case PARTIAL, FULL -> frameRows(snapshot, size, frameStyles, styleIds);
        };
        var frame = new Frame(
            ++frameRevision,
            dirty,
            size,
            activeScreen,
            cursor,
            colors,
            mouseTracking,
            kittyKeyboardFlags,
            scrollbar,
            title,
            pwd,
            frameStyles,
            rows
        );
        cachedFrame = frame;
        return frame;
    }

    private MemorySegment encodeString(Arena arena, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var string = NativeString.allocate(arena);
        if (bytes.length == 0) {
            string.set(NativeTerminalBindings.C_POINTER, 0, MemorySegment.NULL);
            string.set(NativeRuntime.SIZE_T_LAYOUT, ValueLayout.ADDRESS.byteSize(), 0L);
            return string;
        }
        var data = arena.allocate(bytes.length);
        data.copyFrom(MemorySegment.ofArray(bytes));
        string.set(NativeTerminalBindings.C_POINTER, 0, data);
        string.set(NativeRuntime.SIZE_T_LAYOUT, ValueLayout.ADDRESS.byteSize(), bytes.length);
        return string;
    }

    private MemorySegment encodeRgbColor(Arena arena, ColorValue color) {
        if (color instanceof ColorValue.DefaultColor) {
            return MemorySegment.NULL;
        }
        if (!(color instanceof ColorValue.RgbColor rgb)) {
            throw new IllegalArgumentException("Only default or RGB colors are supported here");
        }
        var segment = arena.allocate(NativeTerminalBindings.RGB_LAYOUT);
        segment.set(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_R_OFFSET, (byte) rgb.red());
        segment.set(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_G_OFFSET, (byte) rgb.green());
        segment.set(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_B_OFFSET, (byte) rgb.blue());
        return segment;
    }

    private MemorySegment encodePalette(Arena arena, ColorPalette palette) {
        var segment = arena.allocate(NativeTerminalBindings.PALETTE_LAYOUT);
        var stride = NativeTerminalBindings.RGB_LAYOUT.byteSize();
        var colors = palette.colors();
        for (var i = 0; i < colors.length; i++) {
            if (!(colors[i] instanceof ColorValue.RgbColor rgb)) {
                throw new IllegalArgumentException("Palette entries must be RGB colors");
            }
            var rgbSegment = segment.asSlice(i * stride, stride);
            rgbSegment.set(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_R_OFFSET, (byte) rgb.red());
            rgbSegment.set(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_G_OFFSET, (byte) rgb.green());
            rgbSegment.set(ValueLayout.JAVA_BYTE, NativeTerminalBindings.RGB_B_OFFSET, (byte) rgb.blue());
        }
        return segment;
    }

    private int colorSchemeCode(ColorScheme scheme) {
        return switch (scheme) {
            case LIGHT -> NativeTerminalBindings.COLOR_SCHEME_LIGHT;
            case DARK -> NativeTerminalBindings.COLOR_SCHEME_DARK;
        };
    }

    private void setStringOption(int option, String value) {
        mutate(() -> {
            try (var arena = Arena.ofConfined()) {
                NativeRuntime.invokeStatus(bindings.ghosttyTerminalSet, "ghostty_terminal_set", terminal, option, encodeString(arena, value));
            }
        }, true);
    }

    private void setColorOption(int option, ColorValue value) {
        mutate(() -> {
            try (var arena = Arena.ofConfined()) {
                NativeRuntime.invokeStatus(bindings.ghosttyTerminalSet, "ghostty_terminal_set", terminal, option, encodeRgbColor(arena, value));
            }
        }, true);
    }

    @Override
    public TerminalConfig config() {
        return config;
    }

    @Override
    public Frame frame() {
        return callActor(this::frameInternal);
    }

    @Override
    public void resize(TerminalSize size) {
        Objects.requireNonNull(size, "size");
        mutate(() -> NativeRuntime.invokeStatus(
            bindings.ghosttyTerminalResize,
            "ghostty_terminal_resize",
            terminal,
            (short) size.columns(),
            (short) size.rows(),
            size.cellWidthPx(),
            size.cellHeightPx()
        ), true);
    }

    @Override
    public void write(byte[] vt) {
        Objects.requireNonNull(vt, "vt");
        write(vt, 0, vt.length);
    }

    @Override
    public void write(byte[] vt, int offset, int length) {
        Objects.requireNonNull(vt, "vt");
        Objects.checkFromIndexSize(offset, length, vt.length);
        mutate(() -> {
            try (var arena = Arena.ofConfined()) {
                var input = arena.allocate(length);
                input.copyFrom(MemorySegment.ofArray(vt).asSlice(offset, length));
                NativeRuntime.invoke(bindings.ghosttyTerminalVtWrite, terminal, input, (long) length);
            }
        }, true);
    }

    @Override
    public void reset() {
        mutate(() -> NativeRuntime.invoke(bindings.ghosttyTerminalReset, terminal), true);
    }

    @Override
    public void setMode(TerminalMode mode, boolean enabled) {
        Objects.requireNonNull(mode, "mode");
        mutate(() -> NativeRuntime.invokeStatus(bindings.ghosttyTerminalModeSet, "ghostty_terminal_mode_set", terminal, mode.packedValue(), enabled), true);
    }

    @Override
    public Optional<Boolean> mode(TerminalMode mode) {
        Objects.requireNonNull(mode, "mode");
        return callActor(() -> modeValue(mode));
    }

    @Override
    public void setColorScheme(ColorScheme scheme) {
        Objects.requireNonNull(scheme, "scheme");
        mutate(() -> colorSchemeOverride = scheme, true);
    }

    @Override
    public void setWindowTitle(String title) {
        setStringOption(NativeTerminalBindings.OPT_TITLE, Objects.requireNonNull(title, "title"));
    }

    @Override
    public void setWorkingDirectory(String pwd) {
        setStringOption(NativeTerminalBindings.OPT_PWD, Objects.requireNonNull(pwd, "pwd"));
    }

    @Override
    public void setForeground(ColorValue color) {
        setColorOption(NativeTerminalBindings.OPT_COLOR_FOREGROUND, Objects.requireNonNull(color, "color"));
    }

    @Override
    public void setBackground(ColorValue color) {
        setColorOption(NativeTerminalBindings.OPT_COLOR_BACKGROUND, Objects.requireNonNull(color, "color"));
    }

    @Override
    public void setCursorColor(ColorValue color) {
        setColorOption(NativeTerminalBindings.OPT_COLOR_CURSOR, Objects.requireNonNull(color, "color"));
    }

    @Override
    public void setPalette(ColorPalette palette) {
        Objects.requireNonNull(palette, "palette");
        mutate(() -> {
            try (var arena = Arena.ofConfined()) {
                NativeRuntime.invokeStatus(
                    bindings.ghosttyTerminalSet,
                    "ghostty_terminal_set",
                    terminal,
                    NativeTerminalBindings.OPT_COLOR_PALETTE,
                    encodePalette(arena, palette)
                );
            }
        }, true);
    }

    @Override
    public void scrollToTop() {
        scrollViewport(new TerminalScrollViewport.ScrollViewportTop());
    }

    @Override
    public void scrollToBottom() {
        scrollViewport(new TerminalScrollViewport.ScrollViewportBottom());
    }

    @Override
    public void scrollBy(long delta) {
        scrollViewport(new TerminalScrollViewport.ScrollViewportDelta(delta));
    }

    @Override
    public void scrollViewport(TerminalScrollViewport behavior) {
        Objects.requireNonNull(behavior, "behavior");
        mutate(() -> {
            try (var arena = Arena.ofConfined()) {
                var scroll = arena.allocate(NativeTerminalBindings.SCROLL_VIEWPORT_LAYOUT);
                if (behavior instanceof TerminalScrollViewport.ScrollViewportTop) {
                    scroll.set(ValueLayout.JAVA_INT, NativeTerminalBindings.SCROLL_VIEWPORT_TAG_OFFSET, NativeTerminalBindings.SCROLL_TOP);
                } else if (behavior instanceof TerminalScrollViewport.ScrollViewportBottom) {
                    scroll.set(ValueLayout.JAVA_INT, NativeTerminalBindings.SCROLL_VIEWPORT_TAG_OFFSET, NativeTerminalBindings.SCROLL_BOTTOM);
                } else if (behavior instanceof TerminalScrollViewport.ScrollViewportDelta delta) {
                    scroll.set(ValueLayout.JAVA_INT, NativeTerminalBindings.SCROLL_VIEWPORT_TAG_OFFSET, NativeTerminalBindings.SCROLL_DELTA);
                    scroll.set(ValueLayout.JAVA_LONG, NativeTerminalBindings.SCROLL_VIEWPORT_DELTA_OFFSET, delta.delta());
                } else {
                    throw new IllegalArgumentException("Unknown scroll behavior: " + behavior.getClass().getName());
                }
                NativeRuntime.invoke(bindings.ghosttyTerminalScrollViewport, terminal, scroll);
            }
        }, true);
    }

    @Override
    public Optional<Cell> cell(Point point) {
        Objects.requireNonNull(point, "point");
        return callActor(() -> cellInternal(point));
    }

    @Override
    public Optional<Row> row(long rowIndex, RowCoordinateSpace space) {
        Objects.requireNonNull(space, "space");
        return callActor(() -> rowInternal(rowIndex, space));
    }

    @Override
    public Screen screen(ScreenKind screen) {
        Objects.requireNonNull(screen, "screen");
        return callActor(() -> screenInternal(screen));
    }

    @Override
    public Optional<DeviceAttributes> deviceAttributes() {
        return callActor(() -> Objects.requireNonNull(queries.deviceAttributesValue(), "deviceAttributesValue() returned null"));
    }

    private MemorySegment callbackString(String value) {
        var text = value == null ? "" : value;
        if (text.isEmpty()) {
            return emptyNativeString;
        }
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        var data = callbackArena.allocate(bytes.length);
        data.copyFrom(MemorySegment.ofArray(bytes));
        var string = NativeString.allocate(callbackArena);
        string.set(NativeTerminalBindings.C_POINTER, 0, data);
        string.set(NativeRuntime.SIZE_T_LAYOUT, ValueLayout.ADDRESS.byteSize(), bytes.length);
        return string;
    }

    private void writePtyCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata, MemorySegment data, long len) {
        try {
            ptyWriter.writePty(data.reinterpret(len).toArray(ValueLayout.JAVA_BYTE));
        } catch (Throwable throwable) {
            recordCallbackFailure(throwable);
        }
    }

    private void bellCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata) {
        pendingBells++;
    }

    private MemorySegment enquiryCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata) {
        try {
            return callbackString(queries.enquiryReply());
        } catch (Throwable throwable) {
            recordCallbackFailure(throwable);
            return emptyNativeString;
        }
    }

    private MemorySegment xtversionCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata) {
        try {
            return callbackString(queries.xtversionReply());
        } catch (Throwable throwable) {
            recordCallbackFailure(throwable);
            return emptyNativeString;
        }
    }

    private void titleChangedCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata) {
        pendingTitleChanged = true;
    }

    private boolean sizeCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata, MemorySegment outSize) {
        try {
            var size = Objects.requireNonNull(queries.sizeReportValue(), "sizeReportValue() returned null");
            if (size.isEmpty()) {
                return false;
            }
            var value = size.orElseThrow();
            var sizeOut = outSize.reinterpret(NativeTerminalBindings.SIZE_REPORT_LAYOUT.byteSize());
            sizeOut.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.SIZE_REPORT_ROWS_OFFSET, (short) value.rows());
            sizeOut.set(ValueLayout.JAVA_SHORT, NativeTerminalBindings.SIZE_REPORT_COLUMNS_OFFSET, (short) value.columns());
            sizeOut.set(ValueLayout.JAVA_INT, NativeTerminalBindings.SIZE_REPORT_CELL_WIDTH_OFFSET, value.cellWidthPx());
            sizeOut.set(ValueLayout.JAVA_INT, NativeTerminalBindings.SIZE_REPORT_CELL_HEIGHT_OFFSET, value.cellHeightPx());
            return true;
        } catch (Throwable throwable) {
            recordCallbackFailure(throwable);
            return false;
        }
    }

    private boolean colorSchemeCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata, MemorySegment outScheme) {
        try {
            var scheme = Optional.ofNullable(colorSchemeOverride).or(() -> Objects.requireNonNull(queries.colorSchemeValue(), "colorSchemeValue() returned null"));
            if (scheme.isEmpty()) {
                return false;
            }
            outScheme.reinterpret(ValueLayout.JAVA_INT.byteSize()).set(ValueLayout.JAVA_INT, 0, colorSchemeCode(scheme.orElseThrow()));
            return true;
        } catch (Throwable throwable) {
            recordCallbackFailure(throwable);
            return false;
        }
    }

    private boolean deviceAttributesCallback(MemorySegment ignoredTerminal, MemorySegment ignoredUserdata, MemorySegment outAttributes) {
        try {
            var attributes = Objects.requireNonNull(queries.deviceAttributesValue(), "deviceAttributesValue() returned null");
            if (attributes.isEmpty()) {
                return false;
            }
            var value = attributes.orElseThrow();
            var attrsOut = outAttributes.reinterpret(DEVICE_ATTRIBUTES_LAYOUT.byteSize());
            attrsOut.fill((byte) 0);
            attrsOut.set(ValueLayout.JAVA_SHORT, DEVICE_ATTRIBUTES_PRIMARY_CONFORMANCE_OFFSET, (short) value.primary().conformanceLevel().code);
            var index = 0;
            for (var feature : value.primary().features()) {
                if (index == 64) {
                    break;
                }
                attrsOut.set(
                    ValueLayout.JAVA_SHORT,
                    DEVICE_ATTRIBUTES_PRIMARY_FEATURES_OFFSET + index * ValueLayout.JAVA_SHORT.byteSize(),
                    (short) feature.code
                );
                index++;
            }
            attrsOut.set(NativeRuntime.SIZE_T_LAYOUT, DEVICE_ATTRIBUTES_PRIMARY_NUM_FEATURES_OFFSET, index);
            attrsOut.set(ValueLayout.JAVA_SHORT, DEVICE_ATTRIBUTES_SECONDARY_DEVICE_TYPE_OFFSET, (short) value.secondary().deviceType().code);
            attrsOut.set(ValueLayout.JAVA_SHORT, DEVICE_ATTRIBUTES_SECONDARY_FIRMWARE_OFFSET, (short) value.secondary().firmwareVersion());
            attrsOut.set(ValueLayout.JAVA_SHORT, DEVICE_ATTRIBUTES_SECONDARY_ROM_OFFSET, (short) value.secondary().romCartridge());
            attrsOut.set(ValueLayout.JAVA_INT, DEVICE_ATTRIBUTES_TERTIARY_UNIT_ID_OFFSET, (int) value.tertiary().unitId());
            return true;
        } catch (Throwable throwable) {
            recordCallbackFailure(throwable);
            return false;
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            Future<?> future = actor.submit(() -> {
                if (frameSnapshot != MemorySegment.NULL) {
                    NativeRuntime.invoke(frameSnapshotBindings.ghosttyfxFrameSnapshotFree, frameSnapshot);
                    frameSnapshot = MemorySegment.NULL;
                }
                if (renderRowCells != MemorySegment.NULL) {
                    NativeRuntime.invoke(bindings.ghosttyRenderStateRowCellsFree, renderRowCells);
                    renderRowCells = MemorySegment.NULL;
                }
                if (renderRowIterator != MemorySegment.NULL) {
                    NativeRuntime.invoke(bindings.ghosttyRenderStateRowIteratorFree, renderRowIterator);
                    renderRowIterator = MemorySegment.NULL;
                }
                if (renderState != MemorySegment.NULL) {
                    NativeRuntime.invoke(bindings.ghosttyRenderStateFree, renderState);
                    renderState = MemorySegment.NULL;
                }
                if (terminal != MemorySegment.NULL) {
                    NativeRuntime.invoke(bindings.ghosttyTerminalFree, terminal);
                    terminal = MemorySegment.NULL;
                }
                callbackArena.close();
            });
            future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw NativeRuntime.sneakyThrow(exception);
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            throw NativeRuntime.sneakyThrow(cause == null ? exception : cause);
        } finally {
            actor.shutdownNow();
            eventExecutor.shutdown();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
