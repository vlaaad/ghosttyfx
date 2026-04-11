package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.KeyEvent;
import io.github.vlaaad.ghostty.KeyModifiers;
import io.github.vlaaad.ghostty.KittyKeyboardFlags;
import io.github.vlaaad.ghostty.ModifierSide;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class NativeKeyCodec {
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

    NativeKeyCodec(SymbolLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        ghosttyKeyEncoderNew = NativeRuntime.bind(lookup, "ghostty_key_encoder_new", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER
        ));
        ghosttyKeyEncoderFree = NativeRuntime.bind(
            lookup,
            "ghostty_key_encoder_free",
            FunctionDescriptor.ofVoid(C_POINTER)
        );
        ghosttyKeyEncoderSetopt = NativeRuntime.bind(lookup, "ghostty_key_encoder_setopt", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_INT,
            C_POINTER
        ));
        ghosttyKeyEncoderEncode = NativeRuntime.bind(lookup, "ghostty_key_encoder_encode", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT,
            C_POINTER
        ));
        ghosttyKeyEventNew = NativeRuntime.bind(lookup, "ghostty_key_event_new", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER
        ));
        ghosttyKeyEventFree = NativeRuntime.bind(lookup, "ghostty_key_event_free", FunctionDescriptor.ofVoid(C_POINTER));
        ghosttyKeyEventSetAction = NativeRuntime.bind(lookup, "ghostty_key_event_set_action", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_INT
        ));
        ghosttyKeyEventSetKey = NativeRuntime.bind(lookup, "ghostty_key_event_set_key", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_INT
        ));
        ghosttyKeyEventSetMods = NativeRuntime.bind(lookup, "ghostty_key_event_set_mods", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_SHORT
        ));
        ghosttyKeyEventSetConsumedMods = NativeRuntime.bind(
            lookup,
            "ghostty_key_event_set_consumed_mods",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_SHORT)
        );
        ghosttyKeyEventSetComposing = NativeRuntime.bind(
            lookup,
            "ghostty_key_event_set_composing",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_BOOLEAN)
        );
        ghosttyKeyEventSetUtf8 = NativeRuntime.bind(lookup, "ghostty_key_event_set_utf8", FunctionDescriptor.ofVoid(
            C_POINTER,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT
        ));
        ghosttyKeyEventSetUnshiftedCodepoint = NativeRuntime.bind(
            lookup,
            "ghostty_key_event_set_unshifted_codepoint",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_INT)
        );
    }

    public KeyCodec keyCodec(KeyCodecConfig config) {
        Objects.requireNonNull(config, "config");
        try (var arena = Arena.ofConfined()) {
            var encoderOut = arena.allocate(ValueLayout.ADDRESS);
            NativeRuntime.invokeStatus(
                ghosttyKeyEncoderNew,
                "ghostty_key_encoder_new",
                MemorySegment.NULL,
                encoderOut
            );
            var handleAddress = encoderOut.get(ValueLayout.ADDRESS, 0).address();
            try {
                configureEncoder(arena, MemorySegment.ofAddress(handleAddress), config);
                KeyCodec keyCodec = event -> encode(MemorySegment.ofAddress(handleAddress), event);
                NativeRuntime.CLEANER.register(keyCodec, () -> freeEncoder(handleAddress));
                return keyCodec;
            } catch (RuntimeException exception) {
                freeEncoder(handleAddress);
                throw exception;
            }
        }
    }

    private void configureEncoder(Arena arena, MemorySegment encoder, KeyCodecConfig config) {
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
        NativeRuntime.invoke(
            ghosttyKeyEncoderSetopt,
            encoder,
            GHOSTTY_KEY_ENCODER_OPT_KITTY_FLAGS,
            kittyFlags
        );

        if (config.optionAsAlt() != null) {
            var optionAsAlt = arena.allocate(ValueLayout.JAVA_INT);
            optionAsAlt.set(ValueLayout.JAVA_INT, 0, config.optionAsAlt().ordinal());
            NativeRuntime.invoke(
                ghosttyKeyEncoderSetopt,
                encoder,
                GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT,
                optionAsAlt
            );
        }
    }

    private void setKeyEncoderBooleanOption(Arena arena, MemorySegment encoder, int option, boolean value) {
        var segment = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        segment.set(ValueLayout.JAVA_BOOLEAN, 0, value);
        NativeRuntime.invoke(ghosttyKeyEncoderSetopt, encoder, option, segment);
    }

    private byte[] encode(MemorySegment encoder, KeyEvent event) {
        Objects.requireNonNull(event, "event");
        try (var arena = Arena.ofConfined();
             var keyEvent = NativeKeyEvent.create(this, arena)) {
            // Populate the transient native key event from the immutable Java record.
            NativeRuntime.invoke(ghosttyKeyEventSetAction, keyEvent.handle(), event.action().ordinal());
            NativeRuntime.invoke(ghosttyKeyEventSetKey, keyEvent.handle(), event.key().ordinal());
            NativeRuntime.invoke(ghosttyKeyEventSetMods, keyEvent.handle(), toGhosttyMods(event.modifiers()));
            NativeRuntime.invoke(
                ghosttyKeyEventSetConsumedMods,
                keyEvent.handle(),
                toGhosttyMods(event.consumedModifiers())
            );
            NativeRuntime.invoke(ghosttyKeyEventSetComposing, keyEvent.handle(), event.composing());
            var utf8 = event.utf8();
            if (utf8 == null || utf8.isEmpty()) {
                NativeRuntime.invoke(ghosttyKeyEventSetUtf8, keyEvent.handle(), MemorySegment.NULL, 0L);
            } else {
                var bytes = utf8.getBytes(StandardCharsets.UTF_8);
                var segment = arena.allocate(bytes.length);
                segment.asByteBuffer().put(bytes);
                NativeRuntime.invoke(ghosttyKeyEventSetUtf8, keyEvent.handle(), segment, (long) bytes.length);
            }
            NativeRuntime.invoke(ghosttyKeyEventSetUnshiftedCodepoint, keyEvent.handle(), event.unshiftedCodePoint());

            // Probe the required output size, then encode into an exact-sized buffer.
            var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            try {
                NativeRuntime.invokeStatus(
                    ghosttyKeyEncoderEncode,
                    "ghostty_key_encoder_encode",
                    encoder,
                    keyEvent.handle(),
                    MemorySegment.NULL,
                    0L,
                    outLen
                );
            } catch (ResultException exception) {
                if (exception.result != NativeRuntime.GHOSTTY_OUT_OF_SPACE) {
                    throw exception;
                }
            }

            var required = outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0);
            if (required == 0) {
                return new byte[0];
            }

            var out = arena.allocate(required);
            NativeRuntime.invokeStatus(
                ghosttyKeyEncoderEncode,
                "ghostty_key_encoder_encode",
                encoder,
                keyEvent.handle(),
                out,
                required,
                outLen
            );
            return out.asSlice(0, outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private void freeEncoder(long handleAddress) {
        if (handleAddress != 0L) {
            NativeRuntime.invoke(ghosttyKeyEncoderFree, MemorySegment.ofAddress(handleAddress));
        }
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

    private static byte toKittyFlags(KittyKeyboardFlags flags) {
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

    private record NativeKeyEvent(NativeKeyCodec codec, MemorySegment handle) implements AutoCloseable {
        static NativeKeyEvent create(NativeKeyCodec codec, Arena arena) {
            var eventOut = arena.allocate(ValueLayout.ADDRESS);
            NativeRuntime.invokeStatus(codec.ghosttyKeyEventNew, "ghostty_key_event_new", MemorySegment.NULL, eventOut);
            return new NativeKeyEvent(codec, eventOut.get(ValueLayout.ADDRESS, 0));
        }

        @Override
        public void close() {
            NativeRuntime.invoke(codec.ghosttyKeyEventFree, handle);
        }
    }
}
