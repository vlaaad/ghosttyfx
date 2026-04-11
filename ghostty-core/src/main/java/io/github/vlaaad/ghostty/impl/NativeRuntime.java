package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.FocusCodec;
import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.KeyModifiers;
import io.github.vlaaad.ghostty.MouseCodec;
import io.github.vlaaad.ghostty.MouseCodecConfig;
import io.github.vlaaad.ghostty.ModifierSide;
import io.github.vlaaad.ghostty.PasteCodec;
import io.github.vlaaad.ghostty.PtyWriter;
import io.github.vlaaad.ghostty.SizeReportCodec;
import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalEvents;
import io.github.vlaaad.ghostty.TerminalQueries;
import io.github.vlaaad.ghostty.TerminalSession;
import io.github.vlaaad.ghostty.TypeSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;

public final class NativeRuntime {
    private static final int GHOSTTY_SUCCESS = 0;
    private static final int GHOSTTY_OUT_OF_MEMORY = -1;
    private static final int GHOSTTY_INVALID_VALUE = -2;
    private static final int GHOSTTY_OUT_OF_SPACE = -3;
    private static final int GHOSTTY_NO_VALUE = -4;

    private static final int GHOSTTY_KEY_ENCODER_OPT_CURSOR_KEY_APPLICATION = 0;
    private static final int GHOSTTY_KEY_ENCODER_OPT_KEYPAD_KEY_APPLICATION = 1;
    private static final int GHOSTTY_KEY_ENCODER_OPT_IGNORE_KEYPAD_WITH_NUMLOCK = 2;
    private static final int GHOSTTY_KEY_ENCODER_OPT_ALT_ESC_PREFIX = 3;
    private static final int GHOSTTY_KEY_ENCODER_OPT_MODIFY_OTHER_KEYS_STATE_2 = 4;
    private static final int GHOSTTY_KEY_ENCODER_OPT_KITTY_FLAGS = 5;
    private static final int GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT = 6;

    private static final int GHOSTTY_MODS_SHIFT = 1;
    private static final int GHOSTTY_MODS_CTRL = 2;
    private static final int GHOSTTY_MODS_ALT = 4;
    private static final int GHOSTTY_MODS_SUPER = 8;
    private static final int GHOSTTY_MODS_CAPS_LOCK = 16;
    private static final int GHOSTTY_MODS_NUM_LOCK = 32;
    private static final int GHOSTTY_MODS_SHIFT_SIDE = 64;
    private static final int GHOSTTY_MODS_CTRL_SIDE = 128;
    private static final int GHOSTTY_MODS_ALT_SIDE = 256;
    private static final int GHOSTTY_MODS_SUPER_SIDE = 512;

    private static final int GHOSTTY_KITTY_KEY_DISAMBIGUATE = 1;
    private static final int GHOSTTY_KITTY_KEY_REPORT_EVENTS = 2;
    private static final int GHOSTTY_KITTY_KEY_REPORT_ALTERNATES = 4;
    private static final int GHOSTTY_KITTY_KEY_REPORT_ALL = 8;
    private static final int GHOSTTY_KITTY_KEY_REPORT_ASSOCIATED = 16;

    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;
    static final ValueLayout.OfLong SIZE_T_LAYOUT = ValueLayout.JAVA_LONG;

    private final NativeMetadata metadata;
    private final MethodHandle ghosttyKeyEncoderNew;
    private final MethodHandle ghosttyKeyEncoderFree;
    private final MethodHandle ghosttyKeyEncoderSetopt;
    private final MethodHandle ghosttyKeyEncoderEncode;
    private final MethodHandle ghosttyKeyEventNew;
    private final MethodHandle ghosttyKeyEventFree;
    private final MethodHandle ghosttyKeyEventSetAction;
    private final MethodHandle ghosttyKeyEventSetKey;
    private final MethodHandle ghosttyKeyEventSetMods;
    private final MethodHandle ghosttyKeyEventSetConsumedMods;
    private final MethodHandle ghosttyKeyEventSetComposing;
    private final MethodHandle ghosttyKeyEventSetUtf8;
    private final MethodHandle ghosttyKeyEventSetUnshiftedCodepoint;

    private NativeRuntime() {
        loadCurrentLibrary();
        metadata = new NativeMetadata();

        var lookup = SymbolLookup.loaderLookup();
        ghosttyKeyEncoderNew = bind(lookup, "ghostty_key_encoder_new", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER
        ));
        ghosttyKeyEncoderFree = bind(lookup, "ghostty_key_encoder_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyKeyEncoderSetopt = bind(lookup, "ghostty_key_encoder_setopt", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_INT,
            C_POINTER
        ));
        ghosttyKeyEncoderEncode = bind(lookup, "ghostty_key_encoder_encode", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER,
            C_POINTER,
            SIZE_T_LAYOUT,
            C_POINTER
        ));
        ghosttyKeyEventNew = bind(lookup, "ghostty_key_event_new", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER
        ));
        ghosttyKeyEventFree = bind(lookup, "ghostty_key_event_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyKeyEventSetAction = bind(lookup, "ghostty_key_event_set_action", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_INT
        ));
        ghosttyKeyEventSetKey = bind(lookup, "ghostty_key_event_set_key", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_INT
        ));
        ghosttyKeyEventSetMods = bind(lookup, "ghostty_key_event_set_mods", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_SHORT
        ));
        ghosttyKeyEventSetConsumedMods = bind(lookup, "ghostty_key_event_set_consumed_mods", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_SHORT
        ));
        ghosttyKeyEventSetComposing = bind(lookup, "ghostty_key_event_set_composing", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_BOOLEAN
        ));
        ghosttyKeyEventSetUtf8 = bind(lookup, "ghostty_key_event_set_utf8", FunctionDescriptor.ofVoid(
            C_POINTER,
            C_POINTER,
            SIZE_T_LAYOUT
        ));
        ghosttyKeyEventSetUnshiftedCodepoint = bind(
            lookup,
            "ghostty_key_event_set_unshifted_codepoint",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_INT)
        );
    }

    public final KeyCodec keyCodec(KeyCodecConfig config) {
        Objects.requireNonNull(config, "config");
        return event -> encodeKey(config, event);
    }

    public final TerminalSession open(TerminalConfig config, PtyWriter ptyWriter, TerminalQueries queries, TerminalEvents events) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public final MouseCodec mouseCodec(MouseCodecConfig config) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public final PasteCodec pasteCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public final FocusCodec focusCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public final SizeReportCodec sizeReportCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public final BuildInfo buildInfo() {
        return metadata.buildInfo();
    }

    public final TypeSchema typeSchema() {
        return metadata.typeSchema();
    }

    public static NativeRuntime instance() {
        return Holder.INSTANCE;
    }

    private byte[] encodeKey(KeyCodecConfig config, io.github.vlaaad.ghostty.KeyEvent event) {
        Objects.requireNonNull(event, "event");

        try (var arena = Arena.ofConfined()) {
            try (var encoder = NativeKeyEncoder.create(this, arena);
                 var keyEvent = NativeKeyEvent.create(this, arena)) {
                configureEncoder(arena, encoder.handle(), config);
                populateKeyEvent(arena, keyEvent.handle(), event);

                var outLen = arena.allocate(SIZE_T_LAYOUT);
                try {
                    ghosttyKeyEncoderEncode(
                        encoder.handle(),
                        keyEvent.handle(),
                        MemorySegment.NULL,
                        0,
                        outLen
                    );
                } catch (ResultException exception) {
                    if (exception.result != GHOSTTY_OUT_OF_SPACE) {
                        throw exception;
                    }
                }
                var required = outLen.get(SIZE_T_LAYOUT, 0);
                if (required == 0) {
                    return new byte[0];
                }

                var out = arena.allocate(required);
                ghosttyKeyEncoderEncode(
                    encoder.handle(),
                    keyEvent.handle(),
                    out,
                    required,
                    outLen
                );
                return out.asSlice(0, outLen.get(SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE);
            }
        }
    }

    private void configureEncoder(
        Arena arena,
        MemorySegment encoder,
        KeyCodecConfig config
    ) {
        setKeyEncoderBooleanOption(
            arena,
            encoder,
            GHOSTTY_KEY_ENCODER_OPT_CURSOR_KEY_APPLICATION,
            config.cursorKeyApplication()
        );
        setKeyEncoderBooleanOption(
            arena,
            encoder,
            GHOSTTY_KEY_ENCODER_OPT_KEYPAD_KEY_APPLICATION,
            config.keypadKeyApplication()
        );
        setKeyEncoderBooleanOption(
            arena,
            encoder,
            GHOSTTY_KEY_ENCODER_OPT_IGNORE_KEYPAD_WITH_NUMLOCK,
            config.ignoreKeypadWithNumLock()
        );
        setKeyEncoderBooleanOption(
            arena,
            encoder,
            GHOSTTY_KEY_ENCODER_OPT_ALT_ESC_PREFIX,
            config.altEscPrefix()
        );
        setKeyEncoderBooleanOption(
            arena,
            encoder,
            GHOSTTY_KEY_ENCODER_OPT_MODIFY_OTHER_KEYS_STATE_2,
            config.modifyOtherKeysState2()
        );

        var kittyFlags = arena.allocate(ValueLayout.JAVA_BYTE);
        kittyFlags.set(ValueLayout.JAVA_BYTE, 0, toKittyFlags(config.kittyFlags()));
        ghosttyKeyEncoderSetopt(
            encoder,
            GHOSTTY_KEY_ENCODER_OPT_KITTY_FLAGS,
            kittyFlags
        );

        if (config.optionAsAlt() != null) {
            var optionAsAlt = arena.allocate(ValueLayout.JAVA_INT);
            optionAsAlt.set(ValueLayout.JAVA_INT, 0, config.optionAsAlt().ordinal());
            ghosttyKeyEncoderSetopt(
                encoder,
                GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT,
                optionAsAlt
            );
        }
    }

    private void setKeyEncoderBooleanOption(
        Arena arena,
        MemorySegment encoder,
        int option,
        boolean value
    ) {
        var segment = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        segment.set(ValueLayout.JAVA_BOOLEAN, 0, value);
        ghosttyKeyEncoderSetopt(encoder, option, segment);
    }

    private void populateKeyEvent(
        Arena arena,
        MemorySegment keyEvent,
        io.github.vlaaad.ghostty.KeyEvent event
    ) {
        ghosttyKeyEventSetAction(keyEvent, event.action().ordinal());
        ghosttyKeyEventSetKey(keyEvent, event.key().ordinal());
        ghosttyKeyEventSetMods(keyEvent, toGhosttyMods(event.modifiers()));
        ghosttyKeyEventSetConsumedMods(keyEvent, toGhosttyMods(event.consumedModifiers()));
        ghosttyKeyEventSetComposing(keyEvent, event.composing());
        setUtf8(arena, keyEvent, event.utf8());
        ghosttyKeyEventSetUnshiftedCodepoint(keyEvent, event.unshiftedCodePoint());
    }

    private void setUtf8(Arena arena, MemorySegment keyEvent, String utf8) {
        if (utf8 == null || utf8.isEmpty()) {
            ghosttyKeyEventSetUtf8(keyEvent, MemorySegment.NULL, 0);
            return;
        }

        var bytes = utf8.getBytes(StandardCharsets.UTF_8);
        var segment = arena.allocate(bytes.length);
        segment.asByteBuffer().put(bytes);
        ghosttyKeyEventSetUtf8(keyEvent, segment, bytes.length);
    }

    private void ghosttyKeyEncoderNew(MemorySegment allocator, MemorySegment encoder) {
        callStatus(ghosttyKeyEncoderNew, "ghostty_key_encoder_new", allocator, encoder);
    }

    private void ghosttyKeyEncoderFree(MemorySegment encoder) {
        invokeVoid(ghosttyKeyEncoderFree, "ghostty_key_encoder_free", encoder);
    }

    private void ghosttyKeyEncoderSetopt(MemorySegment encoder, int option, MemorySegment value) {
        invokeVoid(ghosttyKeyEncoderSetopt, "ghostty_key_encoder_setopt", encoder, option, value);
    }

    private void ghosttyKeyEncoderEncode(
        MemorySegment encoder,
        MemorySegment event,
        MemorySegment outBuf,
        long outBufSize,
        MemorySegment outLen
    ) {
        callStatus(
            ghosttyKeyEncoderEncode,
            "ghostty_key_encoder_encode",
            encoder,
            event,
            outBuf,
            outBufSize,
            outLen
        );
    }

    private void ghosttyKeyEventNew(MemorySegment allocator, MemorySegment event) {
        callStatus(ghosttyKeyEventNew, "ghostty_key_event_new", allocator, event);
    }

    private void ghosttyKeyEventFree(MemorySegment event) {
        invokeVoid(ghosttyKeyEventFree, "ghostty_key_event_free", event);
    }

    private void ghosttyKeyEventSetAction(MemorySegment event, int action) {
        invokeVoid(ghosttyKeyEventSetAction, "ghostty_key_event_set_action", event, action);
    }

    private void ghosttyKeyEventSetKey(MemorySegment event, int key) {
        invokeVoid(ghosttyKeyEventSetKey, "ghostty_key_event_set_key", event, key);
    }

    private void ghosttyKeyEventSetMods(MemorySegment event, short mods) {
        invokeVoid(ghosttyKeyEventSetMods, "ghostty_key_event_set_mods", event, mods);
    }

    private void ghosttyKeyEventSetConsumedMods(MemorySegment event, short consumedMods) {
        invokeVoid(ghosttyKeyEventSetConsumedMods, "ghostty_key_event_set_consumed_mods", event, consumedMods);
    }

    private void ghosttyKeyEventSetComposing(MemorySegment event, boolean composing) {
        invokeVoid(ghosttyKeyEventSetComposing, "ghostty_key_event_set_composing", event, composing);
    }

    private void ghosttyKeyEventSetUtf8(MemorySegment event, MemorySegment utf8, long len) {
        invokeVoid(ghosttyKeyEventSetUtf8, "ghostty_key_event_set_utf8", event, utf8, len);
    }

    private void ghosttyKeyEventSetUnshiftedCodepoint(MemorySegment event, int codepoint) {
        invokeVoid(
            ghosttyKeyEventSetUnshiftedCodepoint,
            "ghostty_key_event_set_unshifted_codepoint",
            event,
            codepoint
        );
    }

    private static short toGhosttyMods(KeyModifiers modifiers) {
        if (modifiers == null) {
            return 0;
        }

        var mods = 0;
        if (modifiers.shift()) {
            mods |= GHOSTTY_MODS_SHIFT;
            if (modifiers.shiftSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_SHIFT_SIDE;
            }
        }
        if (modifiers.ctrl()) {
            mods |= GHOSTTY_MODS_CTRL;
            if (modifiers.ctrlSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_CTRL_SIDE;
            }
        }
        if (modifiers.alt()) {
            mods |= GHOSTTY_MODS_ALT;
            if (modifiers.altSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_ALT_SIDE;
            }
        }
        if (modifiers.superKey()) {
            mods |= GHOSTTY_MODS_SUPER;
            if (modifiers.superSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_SUPER_SIDE;
            }
        }
        if (modifiers.capsLock()) {
            mods |= GHOSTTY_MODS_CAPS_LOCK;
        }
        if (modifiers.numLock()) {
            mods |= GHOSTTY_MODS_NUM_LOCK;
        }
        return (short) mods;
    }

    private static byte toKittyFlags(io.github.vlaaad.ghostty.KittyKeyboardFlags flags) {
        if (flags == null) {
            return 0;
        }

        var result = 0;
        if (flags.disambiguate()) {
            result |= GHOSTTY_KITTY_KEY_DISAMBIGUATE;
        }
        if (flags.reportEvents()) {
            result |= GHOSTTY_KITTY_KEY_REPORT_EVENTS;
        }
        if (flags.reportAlternates()) {
            result |= GHOSTTY_KITTY_KEY_REPORT_ALTERNATES;
        }
        if (flags.reportAll()) {
            result |= GHOSTTY_KITTY_KEY_REPORT_ALL;
        }
        if (flags.reportAssociated()) {
            result |= GHOSTTY_KITTY_KEY_REPORT_ASSOCIATED;
        }
        return (byte) result;
    }

    private static void loadCurrentLibrary() {
        var osName = System.getProperty("os.name", "");
        var archName = System.getProperty("os.arch", "");

        var os = osName.toLowerCase(Locale.ROOT);
        String extension;
        if (os.contains("win")) {
            os = "windows";
            extension = ".dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            os = "macos";
            extension = ".dylib";
        } else if (os.contains("linux")) {
            os = "linux";
            extension = ".so";
        } else {
            throw new UnsupportedOperationException(
                "Native runtime is not available for os '" + osName + "' and arch '" + archName + "'"
            );
        }

        var arch = archName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "");
        arch = switch (arch) {
            case "x8664", "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };

        var platform = os + "-" + arch;
        var libraryFileName = "libghostty-vt-" + platform + extension;
        var libraryResource = "native/" + platform + "/" + libraryFileName;

        try (var input = NativeRuntime.class.getClassLoader().getResourceAsStream(libraryResource)) {
            if (input == null) {
                throw new UnsupportedOperationException(
                    "Native runtime is not available for os '" + osName + "' and arch '" + archName
                        + "': missing '" + libraryResource + "'"
                );
            }

            var directory = Files.createTempDirectory("ghosttyfx-" + platform + "-");
            directory.toFile().deleteOnExit();

            var extracted = directory.resolve(libraryFileName);
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            System.load(extracted.toAbsolutePath().toString());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Failed to extract native library '" + libraryResource + "'",
                exception
            );
        }
    }

    static MethodHandle bind(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        var address = lookup.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Native symbol '" + symbol + "' is unavailable"));
        return Linker.nativeLinker().downcallHandle(address, descriptor);
    }

    static MemorySegment invokeAddress(MethodHandle handle, String symbol) {
        try {
            return (MemorySegment) handle.invokeExact();
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    static void callStatus(MethodHandle handle, String errorMessage, int arg0, MemorySegment arg1) {
        throwIfFailed(errorMessage, invokeExactInt(handle, errorMessage, arg0, arg1));
    }

    private static int invokeExactInt(MethodHandle handle, String symbol, int arg0, MemorySegment arg1) {
        try {
            return (int) handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void callStatus(MethodHandle handle, String errorMessage, MemorySegment arg0, MemorySegment arg1) {
        throwIfFailed(errorMessage, invokeExactInt(handle, errorMessage, arg0, arg1));
    }

    private static int invokeExactInt(MethodHandle handle, String symbol, MemorySegment arg0, MemorySegment arg1) {
        try {
            return (int) handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void callStatus(
        MethodHandle handle,
        String errorMessage,
        MemorySegment arg0,
        MemorySegment arg1,
        MemorySegment arg2,
        long arg3,
        MemorySegment arg4
    ) {
        throwIfFailed(errorMessage, invokeExactInt(handle, errorMessage, arg0, arg1, arg2, arg3, arg4));
    }

    private static int invokeExactInt(
        MethodHandle handle,
        String symbol,
        MemorySegment arg0,
        MemorySegment arg1,
        MemorySegment arg2,
        long arg3,
        MemorySegment arg4
    ) {
        try {
            return (int) handle.invokeExact(arg0, arg1, arg2, arg3, arg4);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void throwIfFailed(String errorMessage, int result) {
        if (result == GHOSTTY_SUCCESS) {
            return;
        }
        if (result == GHOSTTY_OUT_OF_MEMORY) {
            throw new ResultException(errorMessage + " failed: out of memory", result);
        }
        if (result == GHOSTTY_INVALID_VALUE) {
            throw new ResultException(errorMessage + " failed: invalid value", result);
        }
        if (result == GHOSTTY_OUT_OF_SPACE) {
            throw new ResultException(errorMessage + " failed: out of space", result);
        }
        if (result == GHOSTTY_NO_VALUE) {
            throw new ResultException(errorMessage + " failed: no value", result);
        }

        throw new ResultException(
            errorMessage + " failed with unexpected result code " + result,
            result
        );
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0) {
        try {
            handle.invokeExact(arg0);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, int arg1) {
        try {
            handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, short arg1) {
        try {
            handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, boolean arg1) {
        try {
            handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, int arg1, MemorySegment arg2) {
        try {
            handle.invokeExact(arg0, arg1, arg2);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private static void invokeVoid(
        MethodHandle handle,
        String symbol,
        MemorySegment arg0,
        MemorySegment arg1,
        long arg2
    ) {
        try {
            handle.invokeExact(arg0, arg1, arg2);
        } catch (Error | RuntimeException exception) {
            throw exception;
        } catch (Throwable exception) {
            throw new AssertionError("Failed to invoke " + symbol, exception);
        }
    }

    private record NativeKeyEncoder(NativeRuntime runtime, MemorySegment handle) implements AutoCloseable {
        static NativeKeyEncoder create(NativeRuntime runtime, Arena arena) {
            var encoderOut = arena.allocate(ValueLayout.ADDRESS);
            runtime.ghosttyKeyEncoderNew(MemorySegment.NULL, encoderOut);
            return new NativeKeyEncoder(runtime, encoderOut.get(ValueLayout.ADDRESS, 0));
        }

        @Override
        public void close() {
            runtime.ghosttyKeyEncoderFree(handle);
        }
    }

    private record NativeKeyEvent(NativeRuntime runtime, MemorySegment handle) implements AutoCloseable {
        static NativeKeyEvent create(NativeRuntime runtime, Arena arena) {
            var eventOut = arena.allocate(ValueLayout.ADDRESS);
            runtime.ghosttyKeyEventNew(MemorySegment.NULL, eventOut);
            return new NativeKeyEvent(runtime, eventOut.get(ValueLayout.ADDRESS, 0));
        }

        @Override
        public void close() {
            runtime.ghosttyKeyEventFree(handle);
        }
    }

    private static final class Holder {
        private static final NativeRuntime INSTANCE = new NativeRuntime();
    }
}
