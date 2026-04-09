package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildFeature;
import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.BuildOptimize;
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
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

abstract class AbstractNativeProvider implements Provider {
    private final SupportedPlatform platform;

    AbstractNativeProvider(SupportedPlatform platform) {
        this.platform = platform;
    }

    @Override
    public final String id() {
        return platform.id();
    }

    @Override
    public final KeyCodec keyCodec(KeyCodecConfig config) {
        Objects.requireNonNull(config, "config");
        return event -> encodeKey(config, event);
    }

    @Override
    public final TerminalSession open(TerminalConfig config, PtyWriter ptyWriter, TerminalQueries queries, TerminalEvents events) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public final MouseCodec mouseCodec(MouseCodecConfig config) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public final PasteCodec pasteCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public final FocusCodec focusCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public final SizeReportCodec sizeReportCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public final BuildInfo buildInfo() {
        GhosttyBindings bindings = bindings();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment versionOut = bindings.allocateGhosttyString(arena);
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_STRING,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_STRING, versionOut)
            );

            MemorySegment majorOut = arena.allocate(bindings.sizeTLayout());
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_MAJOR,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_MAJOR, majorOut)
            );

            MemorySegment minorOut = arena.allocate(bindings.sizeTLayout());
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_MINOR,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_MINOR, minorOut)
            );

            MemorySegment patchOut = arena.allocate(bindings.sizeTLayout());
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_PATCH,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_PATCH, patchOut)
            );

            MemorySegment buildOut = bindings.allocateGhosttyString(arena);
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_BUILD,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_VERSION_BUILD, buildOut)
            );

            MemorySegment optimizeOut = arena.allocate(ValueLayout.JAVA_INT);
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_OPTIMIZE,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_OPTIMIZE, optimizeOut)
            );

            MemorySegment simdOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_SIMD,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_SIMD, simdOut)
            );

            MemorySegment kittyGraphicsOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS, kittyGraphicsOut)
            );

            MemorySegment tmuxControlModeOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                GhosttyBindings.GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE,
                bindings.ghosttyBuildInfo(GhosttyBindings.GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE, tmuxControlModeOut)
            );

            EnumSet<BuildFeature> features = EnumSet.noneOf(BuildFeature.class);
            if (simdOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.SIMD);
            }
            if (kittyGraphicsOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.KITTY_GRAPHICS);
            }
            if (tmuxControlModeOut.get(ValueLayout.JAVA_BOOLEAN, 0)) {
                features.add(BuildFeature.TMUX_CONTROL_MODE);
            }

            int optimize = optimizeOut.get(ValueLayout.JAVA_INT, 0);
            return new BuildInfo(
                NativeSupport.readUtf8String(bindings.ghosttyStringPtr(versionOut), bindings.ghosttyStringLen(versionOut)),
                majorOut.get(bindings.sizeTLayout(), 0),
                minorOut.get(bindings.sizeTLayout(), 0),
                patchOut.get(bindings.sizeTLayout(), 0),
                NativeSupport.readUtf8String(bindings.ghosttyStringPtr(buildOut), bindings.ghosttyStringLen(buildOut)),
                optimize == GhosttyBindings.GHOSTTY_OPTIMIZE_DEBUG
                    ? BuildOptimize.DEBUG
                    : optimize == GhosttyBindings.GHOSTTY_OPTIMIZE_RELEASE_SAFE
                        ? BuildOptimize.RELEASE_SAFE
                        : optimize == GhosttyBindings.GHOSTTY_OPTIMIZE_RELEASE_SMALL
                            ? BuildOptimize.RELEASE_SMALL
                            : optimize == GhosttyBindings.GHOSTTY_OPTIMIZE_RELEASE_FAST
                                ? BuildOptimize.RELEASE_FAST
                                : throwUnknownOptimize(optimize),
                Set.copyOf(features)
            );
        }
    }

    @Override
    public final TypeSchema typeSchema() {
        MemorySegment jsonPointer = bindings().ghosttyTypeJson();
        if (jsonPointer.address() == 0L) {
            throw new IllegalStateException("ghostty_type_json() returned a null pointer");
        }
        return new TypeSchema(jsonPointer.reinterpret(Long.MAX_VALUE).getString(0));
    }

    private GhosttyBindings bindings() {
        return GhosttyBindings.get(platform);
    }

    private byte[] encodeKey(KeyCodecConfig config, io.github.vlaaad.ghostty.KeyEvent event) {
        Objects.requireNonNull(event, "event");

        GhosttyBindings bindings = bindings();
        try (Arena arena = Arena.ofConfined()) {
            try (NativeKeyEncoder encoder = NativeKeyEncoder.create(bindings, arena);
                 NativeKeyEvent keyEvent = NativeKeyEvent.create(bindings, arena)) {
                configureEncoder(bindings, arena, encoder.handle(), config);
                populateKeyEvent(bindings, arena, keyEvent.handle(), event);

                MemorySegment outLen = arena.allocate(bindings.sizeTLayout());
                int probe = bindings.ghosttyKeyEncoderEncode(
                    encoder.handle(),
                    keyEvent.handle(),
                    MemorySegment.NULL,
                    0,
                    outLen
                );
                long required = outLen.get(bindings.sizeTLayout(), 0);
                if (probe != GhosttyBindings.GHOSTTY_OUT_OF_SPACE) {
                    checkKeyResult("ghostty_key_encoder_encode", probe);
                }
                if (required == 0) {
                    return new byte[0];
                }

                MemorySegment out = arena.allocate(required);
                checkKeyResult(
                    "ghostty_key_encoder_encode",
                    bindings.ghosttyKeyEncoderEncode(
                        encoder.handle(),
                        keyEvent.handle(),
                        out,
                        required,
                        outLen
                    )
                );
                return out.asSlice(0, outLen.get(bindings.sizeTLayout(), 0)).toArray(ValueLayout.JAVA_BYTE);
            }
        }
    }

    private static void configureEncoder(
        GhosttyBindings bindings,
        Arena arena,
        MemorySegment encoder,
        KeyCodecConfig config
    ) {
        setKeyEncoderBooleanOption(
            bindings,
            arena,
            encoder,
            GhosttyBindings.GHOSTTY_KEY_ENCODER_OPT_CURSOR_KEY_APPLICATION,
            config.cursorKeyApplication()
        );
        setKeyEncoderBooleanOption(
            bindings,
            arena,
            encoder,
            GhosttyBindings.GHOSTTY_KEY_ENCODER_OPT_KEYPAD_KEY_APPLICATION,
            config.keypadKeyApplication()
        );
        setKeyEncoderBooleanOption(
            bindings,
            arena,
            encoder,
            GhosttyBindings.GHOSTTY_KEY_ENCODER_OPT_IGNORE_KEYPAD_WITH_NUMLOCK,
            config.ignoreKeypadWithNumLock()
        );
        setKeyEncoderBooleanOption(
            bindings,
            arena,
            encoder,
            GhosttyBindings.GHOSTTY_KEY_ENCODER_OPT_ALT_ESC_PREFIX,
            config.altEscPrefix()
        );
        setKeyEncoderBooleanOption(
            bindings,
            arena,
            encoder,
            GhosttyBindings.GHOSTTY_KEY_ENCODER_OPT_MODIFY_OTHER_KEYS_STATE_2,
            config.modifyOtherKeysState2()
        );

        MemorySegment kittyFlags = arena.allocate(ValueLayout.JAVA_BYTE);
        kittyFlags.set(ValueLayout.JAVA_BYTE, 0, toKittyFlags(config.kittyFlags()));
        bindings.ghosttyKeyEncoderSetopt(
            encoder,
            GhosttyBindings.GHOSTTY_KEY_ENCODER_OPT_KITTY_FLAGS,
            kittyFlags
        );

        if (config.optionAsAlt() != null) {
            MemorySegment optionAsAlt = arena.allocate(ValueLayout.JAVA_INT);
            optionAsAlt.set(ValueLayout.JAVA_INT, 0, config.optionAsAlt().ordinal());
            bindings.ghosttyKeyEncoderSetopt(
                encoder,
                GhosttyBindings.GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT,
                optionAsAlt
            );
        }
    }

    private static void setKeyEncoderBooleanOption(
        GhosttyBindings bindings,
        Arena arena,
        MemorySegment encoder,
        int option,
        boolean value
    ) {
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        segment.set(ValueLayout.JAVA_BOOLEAN, 0, value);
        bindings.ghosttyKeyEncoderSetopt(encoder, option, segment);
    }

    private static void populateKeyEvent(
        GhosttyBindings bindings,
        Arena arena,
        MemorySegment keyEvent,
        io.github.vlaaad.ghostty.KeyEvent event
    ) {
        bindings.ghosttyKeyEventSetAction(keyEvent, event.action().ordinal());
        bindings.ghosttyKeyEventSetKey(keyEvent, event.key().ordinal());
        bindings.ghosttyKeyEventSetMods(keyEvent, toGhosttyMods(event.modifiers()));
        bindings.ghosttyKeyEventSetConsumedMods(keyEvent, toGhosttyMods(event.consumedModifiers()));
        bindings.ghosttyKeyEventSetComposing(keyEvent, event.composing());
        setUtf8(bindings, arena, keyEvent, event.utf8());
        bindings.ghosttyKeyEventSetUnshiftedCodepoint(keyEvent, event.unshiftedCodePoint());
    }

    private static void setUtf8(GhosttyBindings bindings, Arena arena, MemorySegment keyEvent, String utf8) {
        if (utf8 == null || utf8.isEmpty()) {
            bindings.ghosttyKeyEventSetUtf8(keyEvent, MemorySegment.NULL, 0);
            return;
        }

        byte[] bytes = utf8.getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = arena.allocate(bytes.length);
        segment.asByteBuffer().put(bytes);
        bindings.ghosttyKeyEventSetUtf8(keyEvent, segment, bytes.length);
    }

    private static short toGhosttyMods(KeyModifiers modifiers) {
        if (modifiers == null) {
            return 0;
        }

        int mods = 0;
        if (modifiers.shift()) {
            mods |= GhosttyBindings.GHOSTTY_MODS_SHIFT;
            if (modifiers.shiftSide() == ModifierSide.RIGHT) {
                mods |= GhosttyBindings.GHOSTTY_MODS_SHIFT_SIDE;
            }
        }
        if (modifiers.ctrl()) {
            mods |= GhosttyBindings.GHOSTTY_MODS_CTRL;
            if (modifiers.ctrlSide() == ModifierSide.RIGHT) {
                mods |= GhosttyBindings.GHOSTTY_MODS_CTRL_SIDE;
            }
        }
        if (modifiers.alt()) {
            mods |= GhosttyBindings.GHOSTTY_MODS_ALT;
            if (modifiers.altSide() == ModifierSide.RIGHT) {
                mods |= GhosttyBindings.GHOSTTY_MODS_ALT_SIDE;
            }
        }
        if (modifiers.superKey()) {
            mods |= GhosttyBindings.GHOSTTY_MODS_SUPER;
            if (modifiers.superSide() == ModifierSide.RIGHT) {
                mods |= GhosttyBindings.GHOSTTY_MODS_SUPER_SIDE;
            }
        }
        if (modifiers.capsLock()) {
            mods |= GhosttyBindings.GHOSTTY_MODS_CAPS_LOCK;
        }
        if (modifiers.numLock()) {
            mods |= GhosttyBindings.GHOSTTY_MODS_NUM_LOCK;
        }
        return (short) mods;
    }

    private static byte toKittyFlags(io.github.vlaaad.ghostty.KittyKeyboardFlags flags) {
        if (flags == null) {
            return 0;
        }

        int result = 0;
        if (flags.disambiguate()) {
            result |= GhosttyBindings.GHOSTTY_KITTY_KEY_DISAMBIGUATE;
        }
        if (flags.reportEvents()) {
            result |= GhosttyBindings.GHOSTTY_KITTY_KEY_REPORT_EVENTS;
        }
        if (flags.reportAlternates()) {
            result |= GhosttyBindings.GHOSTTY_KITTY_KEY_REPORT_ALTERNATES;
        }
        if (flags.reportAll()) {
            result |= GhosttyBindings.GHOSTTY_KITTY_KEY_REPORT_ALL;
        }
        if (flags.reportAssociated()) {
            result |= GhosttyBindings.GHOSTTY_KITTY_KEY_REPORT_ASSOCIATED;
        }
        return (byte) result;
    }

    private static void checkKeyResult(String functionName, int result) {
        NativeSupport.checkResult(
            functionName,
            result,
            0,
            GhosttyBindings.GHOSTTY_SUCCESS,
            GhosttyBindings.GHOSTTY_OUT_OF_MEMORY,
            GhosttyBindings.GHOSTTY_INVALID_VALUE,
            GhosttyBindings.GHOSTTY_OUT_OF_SPACE,
            Integer.MIN_VALUE
        );
    }

    private static void checkBuildInfoResult(int field, int result) {
        NativeSupport.checkResult(
            "ghostty_build_info",
            result,
            field,
            GhosttyBindings.GHOSTTY_SUCCESS,
            GhosttyBindings.GHOSTTY_OUT_OF_MEMORY,
            GhosttyBindings.GHOSTTY_INVALID_VALUE,
            GhosttyBindings.GHOSTTY_OUT_OF_SPACE,
            GhosttyBindings.GHOSTTY_NO_VALUE
        );
    }

    private static BuildOptimize throwUnknownOptimize(int optimize) {
        throw new IllegalStateException("Unknown optimize mode " + optimize);
    }

    private record NativeKeyEncoder(GhosttyBindings bindings, MemorySegment handle) implements AutoCloseable {
        static NativeKeyEncoder create(GhosttyBindings bindings, Arena arena) {
            MemorySegment encoderOut = arena.allocate(ValueLayout.ADDRESS);
            checkKeyResult("ghostty_key_encoder_new", bindings.ghosttyKeyEncoderNew(MemorySegment.NULL, encoderOut));
            return new NativeKeyEncoder(bindings, encoderOut.get(ValueLayout.ADDRESS, 0));
        }

        @Override
        public void close() {
            bindings.ghosttyKeyEncoderFree(handle);
        }
    }

    private record NativeKeyEvent(GhosttyBindings bindings, MemorySegment handle) implements AutoCloseable {
        static NativeKeyEvent create(GhosttyBindings bindings, Arena arena) {
            MemorySegment eventOut = arena.allocate(ValueLayout.ADDRESS);
            checkKeyResult("ghostty_key_event_new", bindings.ghosttyKeyEventNew(MemorySegment.NULL, eventOut));
            return new NativeKeyEvent(bindings, eventOut.get(ValueLayout.ADDRESS, 0));
        }

        @Override
        public void close() {
            bindings.ghosttyKeyEventFree(handle);
        }
    }
}
