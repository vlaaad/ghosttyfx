package io.github.vlaaad.ghostty.macos.aarch64;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.BuildFeature;
import io.github.vlaaad.ghostty.BuildOptimize;
import io.github.vlaaad.ghostty.FocusCodec;
import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.MouseCodec;
import io.github.vlaaad.ghostty.MouseCodecConfig;
import io.github.vlaaad.ghostty.PasteCodec;
import io.github.vlaaad.ghostty.PtyWriter;
import io.github.vlaaad.ghostty.SizeReportCodec;
import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalEvents;
import io.github.vlaaad.ghostty.TerminalQueries;
import io.github.vlaaad.ghostty.TerminalSession;
import io.github.vlaaad.ghostty.TypeSchema;
import io.github.vlaaad.ghostty.impl.NativeLibraries;
import io.github.vlaaad.ghostty.impl.NativeSupport;
import io.github.vlaaad.macos.aarch64.GhosttyString;
import io.github.vlaaad.macos.aarch64.ghostty_vt_h;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class Provider implements io.github.vlaaad.ghostty.impl.Provider {
    private static final String ID = "macos-aarch64";
    private static final String LIBRARY_EXTENSION = "dylib";

    public String id() {
        return ID;
    }

    public TerminalSession open(TerminalConfig config, PtyWriter ptyWriter, TerminalQueries queries, TerminalEvents events) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public KeyCodec keyCodec(KeyCodecConfig config) {
        ensureLoaded();
        Objects.requireNonNull(config, "config");
        return event -> encodeKey(config, event);
    }

    public MouseCodec mouseCodec(MouseCodecConfig config) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public PasteCodec pasteCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public FocusCodec focusCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public SizeReportCodec sizeReportCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    public BuildInfo buildInfo() {
        ensureLoaded();

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment versionOut = GhosttyString.allocate(arena);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_STRING(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_STRING(), versionOut)
            );
            MemorySegment majorOut = arena.allocate(ghostty_vt_h.size_t);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MAJOR(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MAJOR(), majorOut)
            );
            MemorySegment minorOut = arena.allocate(ghostty_vt_h.size_t);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MINOR(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_MINOR(), minorOut)
            );
            MemorySegment patchOut = arena.allocate(ghostty_vt_h.size_t);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_PATCH(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_PATCH(), patchOut)
            );
            MemorySegment buildOut = GhosttyString.allocate(arena);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_BUILD(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_VERSION_BUILD(), buildOut)
            );
            MemorySegment optimizeOut = arena.allocate(ValueLayout.JAVA_INT);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_OPTIMIZE(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_OPTIMIZE(), optimizeOut)
            );
            MemorySegment simdOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_SIMD(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_SIMD(), simdOut)
            );
            MemorySegment kittyGraphicsOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_KITTY_GRAPHICS(), kittyGraphicsOut)
            );
            MemorySegment tmuxControlModeOut = arena.allocate(ValueLayout.JAVA_BOOLEAN);
            checkBuildInfoResult(
                ghostty_vt_h.GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE(),
                ghostty_vt_h.ghostty_build_info(ghostty_vt_h.GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE(), tmuxControlModeOut)
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
                NativeSupport.readUtf8String(GhosttyString.ptr(versionOut), GhosttyString.len(versionOut)),
                majorOut.get(ghostty_vt_h.size_t, 0),
                minorOut.get(ghostty_vt_h.size_t, 0),
                patchOut.get(ghostty_vt_h.size_t, 0),
                NativeSupport.readUtf8String(GhosttyString.ptr(buildOut), GhosttyString.len(buildOut)),
                optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_DEBUG()
                    ? BuildOptimize.DEBUG
                    : optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_RELEASE_SAFE()
                        ? BuildOptimize.RELEASE_SAFE
                        : optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_RELEASE_SMALL()
                            ? BuildOptimize.RELEASE_SMALL
                            : optimize == ghostty_vt_h.GHOSTTY_OPTIMIZE_RELEASE_FAST()
                                ? BuildOptimize.RELEASE_FAST
                                : throwUnknownOptimize(optimize),
                Set.copyOf(features)
            );
        }
    }

    public TypeSchema typeSchema() {
        ensureLoaded();

        MemorySegment jsonPointer = ghostty_vt_h.ghostty_type_json();
        if (jsonPointer.address() == 0L) {
            throw new IllegalStateException("ghostty_type_json() returned a null pointer");
        }

        return new TypeSchema(jsonPointer.reinterpret(Long.MAX_VALUE).getString(0));
    }

    private static void ensureLoaded() {
        String libraryFileName = "libghostty-vt-" + ID + "." + LIBRARY_EXTENSION;
        NativeLibraries.ensureLoaded(ID, "native/" + ID + "/" + libraryFileName, libraryFileName, Provider.class);
    }

    private static byte[] encodeKey(KeyCodecConfig config, io.github.vlaaad.ghostty.KeyEvent event) {
        Objects.requireNonNull(event, "event");

        try (Arena arena = Arena.ofConfined()) {
            try (NativeKeyEncoder encoder = NativeKeyEncoder.create(arena);
                 NativeKeyEvent keyEvent = NativeKeyEvent.create(arena)) {
                configureEncoder(arena, encoder.handle(), config);
                populateKeyEvent(arena, keyEvent.handle(), event);

                MemorySegment outLen = arena.allocate(ghostty_vt_h.size_t);
                int probe = ghostty_vt_h.ghostty_key_encoder_encode(
                    encoder.handle(),
                    keyEvent.handle(),
                    MemorySegment.NULL,
                    0,
                    outLen
                );
                long required = outLen.get(ghostty_vt_h.size_t, 0);
                if (probe != ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                    checkKeyResult("ghostty_key_encoder_encode", probe);
                }
                if (required == 0) {
                    return new byte[0];
                }

                MemorySegment out = arena.allocate(required);
                checkKeyResult(
                    "ghostty_key_encoder_encode",
                    ghostty_vt_h.ghostty_key_encoder_encode(
                        encoder.handle(),
                        keyEvent.handle(),
                        out,
                        required,
                        outLen
                    )
                );
                return out.asSlice(0, outLen.get(ghostty_vt_h.size_t, 0)).toArray(ValueLayout.JAVA_BYTE);
            }
        }
    }

    private static void configureEncoder(Arena arena, MemorySegment encoder, KeyCodecConfig config) {
        setKeyEncoderBooleanOption(arena, encoder, ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_CURSOR_KEY_APPLICATION(), config.cursorKeyApplication());
        setKeyEncoderBooleanOption(arena, encoder, ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_KEYPAD_KEY_APPLICATION(), config.keypadKeyApplication());
        setKeyEncoderBooleanOption(arena, encoder, ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_IGNORE_KEYPAD_WITH_NUMLOCK(), config.ignoreKeypadWithNumLock());
        setKeyEncoderBooleanOption(arena, encoder, ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_ALT_ESC_PREFIX(), config.altEscPrefix());
        setKeyEncoderBooleanOption(arena, encoder, ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_MODIFY_OTHER_KEYS_STATE_2(), config.modifyOtherKeysState2());

        MemorySegment kittyFlags = arena.allocate(ValueLayout.JAVA_BYTE);
        kittyFlags.set(ValueLayout.JAVA_BYTE, 0, toKittyFlags(config.kittyFlags()));
        ghostty_vt_h.ghostty_key_encoder_setopt(encoder, ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_KITTY_FLAGS(), kittyFlags);

        if (config.optionAsAlt() != null) {
            MemorySegment optionAsAlt = arena.allocate(ValueLayout.JAVA_INT);
            optionAsAlt.set(ValueLayout.JAVA_INT, 0, config.optionAsAlt().ordinal());
            ghostty_vt_h.ghostty_key_encoder_setopt(
                encoder,
                ghostty_vt_h.GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT(),
                optionAsAlt
            );
        }
    }

    private static void setKeyEncoderBooleanOption(Arena arena, MemorySegment encoder, int option, boolean value) {
        MemorySegment segment = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        segment.set(ValueLayout.JAVA_BOOLEAN, 0, value);
        ghostty_vt_h.ghostty_key_encoder_setopt(encoder, option, segment);
    }

    private static void populateKeyEvent(Arena arena, MemorySegment keyEvent, io.github.vlaaad.ghostty.KeyEvent event) {
        ghostty_vt_h.ghostty_key_event_set_action(keyEvent, event.action().ordinal());
        ghostty_vt_h.ghostty_key_event_set_key(keyEvent, event.key().ordinal());
        ghostty_vt_h.ghostty_key_event_set_mods(keyEvent, toGhosttyMods(event.modifiers()));
        ghostty_vt_h.ghostty_key_event_set_consumed_mods(keyEvent, toGhosttyMods(event.consumedModifiers()));
        ghostty_vt_h.ghostty_key_event_set_composing(keyEvent, event.composing());
        setUtf8(arena, keyEvent, event.utf8());
        ghostty_vt_h.ghostty_key_event_set_unshifted_codepoint(keyEvent, event.unshiftedCodePoint());
    }

    private static void setUtf8(Arena arena, MemorySegment keyEvent, String utf8) {
        if (utf8 == null || utf8.isEmpty()) {
            ghostty_vt_h.ghostty_key_event_set_utf8(keyEvent, MemorySegment.NULL, 0);
            return;
        }

        byte[] bytes = utf8.getBytes(StandardCharsets.UTF_8);
        MemorySegment segment = arena.allocate(bytes.length);
        segment.asByteBuffer().put(bytes);
        ghostty_vt_h.ghostty_key_event_set_utf8(keyEvent, segment, bytes.length);
    }

    private static short toGhosttyMods(io.github.vlaaad.ghostty.KeyModifiers modifiers) {
        if (modifiers == null) {
            return 0;
        }

        int mods = 0;
        if (modifiers.shift()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_SHIFT();
            if (modifiers.shiftSide() == io.github.vlaaad.ghostty.ModifierSide.RIGHT) {
                mods |= ghostty_vt_h.GHOSTTY_MODS_SHIFT_SIDE();
            }
        }
        if (modifiers.ctrl()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_CTRL();
            if (modifiers.ctrlSide() == io.github.vlaaad.ghostty.ModifierSide.RIGHT) {
                mods |= ghostty_vt_h.GHOSTTY_MODS_CTRL_SIDE();
            }
        }
        if (modifiers.alt()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_ALT();
            if (modifiers.altSide() == io.github.vlaaad.ghostty.ModifierSide.RIGHT) {
                mods |= ghostty_vt_h.GHOSTTY_MODS_ALT_SIDE();
            }
        }
        if (modifiers.superKey()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_SUPER();
            if (modifiers.superSide() == io.github.vlaaad.ghostty.ModifierSide.RIGHT) {
                mods |= ghostty_vt_h.GHOSTTY_MODS_SUPER_SIDE();
            }
        }
        if (modifiers.capsLock()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_CAPS_LOCK();
        }
        if (modifiers.numLock()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_NUM_LOCK();
        }
        return (short) mods;
    }

    private static byte toKittyFlags(io.github.vlaaad.ghostty.KittyKeyboardFlags flags) {
        if (flags == null) {
            return 0;
        }

        int result = 0;
        if (flags.disambiguate()) {
            result |= ghostty_vt_h.GHOSTTY_KITTY_KEY_DISAMBIGUATE();
        }
        if (flags.reportEvents()) {
            result |= ghostty_vt_h.GHOSTTY_KITTY_KEY_REPORT_EVENTS();
        }
        if (flags.reportAlternates()) {
            result |= ghostty_vt_h.GHOSTTY_KITTY_KEY_REPORT_ALTERNATES();
        }
        if (flags.reportAll()) {
            result |= ghostty_vt_h.GHOSTTY_KITTY_KEY_REPORT_ALL();
        }
        if (flags.reportAssociated()) {
            result |= ghostty_vt_h.GHOSTTY_KITTY_KEY_REPORT_ASSOCIATED();
        }
        return (byte) result;
    }

    private static void checkKeyResult(String functionName, int result) {
        NativeSupport.checkResult(
            functionName,
            result,
            0,
            ghostty_vt_h.GHOSTTY_SUCCESS(),
            ghostty_vt_h.GHOSTTY_OUT_OF_MEMORY(),
            ghostty_vt_h.GHOSTTY_INVALID_VALUE(),
            ghostty_vt_h.GHOSTTY_OUT_OF_SPACE(),
            Integer.MIN_VALUE
        );
    }

    private record NativeKeyEncoder(MemorySegment handle) implements AutoCloseable {
        private static NativeKeyEncoder create(Arena arena) {
            MemorySegment encoderOut = arena.allocate(ghostty_vt_h.C_POINTER);
            checkKeyResult("ghostty_key_encoder_new", ghostty_vt_h.ghostty_key_encoder_new(MemorySegment.NULL, encoderOut));
            return new NativeKeyEncoder(encoderOut.get(ghostty_vt_h.C_POINTER, 0));
        }

        @Override
        public void close() {
            ghostty_vt_h.ghostty_key_encoder_free(handle);
        }
    }

    private record NativeKeyEvent(MemorySegment handle) implements AutoCloseable {
        private static NativeKeyEvent create(Arena arena) {
            MemorySegment keyEventOut = arena.allocate(ghostty_vt_h.C_POINTER);
            checkKeyResult("ghostty_key_event_new", ghostty_vt_h.ghostty_key_event_new(MemorySegment.NULL, keyEventOut));
            return new NativeKeyEvent(keyEventOut.get(ghostty_vt_h.C_POINTER, 0));
        }

        @Override
        public void close() {
            ghostty_vt_h.ghostty_key_event_free(handle);
        }
    }

    private static void checkBuildInfoResult(int field, int result) {
        NativeSupport.checkResult(
            "ghostty_build_info",
            result,
            field,
            ghostty_vt_h.GHOSTTY_SUCCESS(),
            ghostty_vt_h.GHOSTTY_OUT_OF_MEMORY(),
            ghostty_vt_h.GHOSTTY_INVALID_VALUE(),
            ghostty_vt_h.GHOSTTY_OUT_OF_SPACE(),
            ghostty_vt_h.GHOSTTY_NO_VALUE()
        );
    }

    private static BuildOptimize throwUnknownOptimize(int optimize) {
        throw new IllegalStateException("Unknown optimize mode " + optimize);
    }
}
