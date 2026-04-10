package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

final class GhosttyBindings {
    static final int GHOSTTY_SUCCESS = 0;
    static final int GHOSTTY_OUT_OF_MEMORY = -1;
    static final int GHOSTTY_INVALID_VALUE = -2;
    static final int GHOSTTY_OUT_OF_SPACE = -3;
    static final int GHOSTTY_NO_VALUE = -4;

    static final int GHOSTTY_OPTIMIZE_DEBUG = 0;
    static final int GHOSTTY_OPTIMIZE_RELEASE_SAFE = 1;
    static final int GHOSTTY_OPTIMIZE_RELEASE_SMALL = 2;
    static final int GHOSTTY_OPTIMIZE_RELEASE_FAST = 3;

    static final int GHOSTTY_BUILD_INFO_SIMD = 1;
    static final int GHOSTTY_BUILD_INFO_KITTY_GRAPHICS = 2;
    static final int GHOSTTY_BUILD_INFO_TMUX_CONTROL_MODE = 3;
    static final int GHOSTTY_BUILD_INFO_OPTIMIZE = 4;
    static final int GHOSTTY_BUILD_INFO_VERSION_STRING = 5;
    static final int GHOSTTY_BUILD_INFO_VERSION_MAJOR = 6;
    static final int GHOSTTY_BUILD_INFO_VERSION_MINOR = 7;
    static final int GHOSTTY_BUILD_INFO_VERSION_PATCH = 8;
    static final int GHOSTTY_BUILD_INFO_VERSION_BUILD = 9;

    static final int GHOSTTY_KEY_ENCODER_OPT_CURSOR_KEY_APPLICATION = 0;
    static final int GHOSTTY_KEY_ENCODER_OPT_KEYPAD_KEY_APPLICATION = 1;
    static final int GHOSTTY_KEY_ENCODER_OPT_IGNORE_KEYPAD_WITH_NUMLOCK = 2;
    static final int GHOSTTY_KEY_ENCODER_OPT_ALT_ESC_PREFIX = 3;
    static final int GHOSTTY_KEY_ENCODER_OPT_MODIFY_OTHER_KEYS_STATE_2 = 4;
    static final int GHOSTTY_KEY_ENCODER_OPT_KITTY_FLAGS = 5;
    static final int GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT = 6;

    static final int GHOSTTY_MODS_SHIFT = 1;
    static final int GHOSTTY_MODS_CTRL = 2;
    static final int GHOSTTY_MODS_ALT = 4;
    static final int GHOSTTY_MODS_SUPER = 8;
    static final int GHOSTTY_MODS_CAPS_LOCK = 16;
    static final int GHOSTTY_MODS_NUM_LOCK = 32;
    static final int GHOSTTY_MODS_SHIFT_SIDE = 64;
    static final int GHOSTTY_MODS_CTRL_SIDE = 128;
    static final int GHOSTTY_MODS_ALT_SIDE = 256;
    static final int GHOSTTY_MODS_SUPER_SIDE = 512;

    static final int GHOSTTY_KITTY_KEY_DISAMBIGUATE = 1;
    static final int GHOSTTY_KITTY_KEY_REPORT_EVENTS = 2;
    static final int GHOSTTY_KITTY_KEY_REPORT_ALTERNATES = 4;
    static final int GHOSTTY_KITTY_KEY_REPORT_ALL = 8;
    static final int GHOSTTY_KITTY_KEY_REPORT_ASSOCIATED = 16;

    private static final AtomicReference<GhosttyBindings> INSTANCE = new AtomicReference<>();
    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;

    private final NativeRuntime runtime;
    private final MemoryLayout ghosttyStringLayout;
    private final long ghosttyStringPtrOffset;
    private final long ghosttyStringLenOffset;

    private final MethodHandle ghosttyTypeJson;
    private final MethodHandle ghosttyBuildInfo;
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

    private GhosttyBindings(NativeRuntime runtime) {
        this.runtime = runtime;

        NativeLibraries.ensureLoaded(
            runtime.id(),
            "native/" + runtime.id() + "/" + runtime.libraryFileName(),
            runtime.libraryFileName(),
            GhosttyBindings.class
        );

        SymbolLookup lookup = SymbolLookup.loaderLookup();
        ghosttyStringLayout = MemoryLayout.structLayout(
            C_POINTER.withName("ptr"),
            runtime.sizeTLayout().withName("len")
        );
        ghosttyStringPtrOffset = ghosttyStringLayout.byteOffset(groupElement("ptr"));
        ghosttyStringLenOffset = ghosttyStringLayout.byteOffset(groupElement("len"));

        ghosttyTypeJson = bind(lookup, "ghostty_type_json", FunctionDescriptor.of(C_POINTER));
        ghosttyBuildInfo = bind(lookup, "ghostty_build_info", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            C_POINTER
        ));
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
            runtime.sizeTLayout(),
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
            runtime.sizeTLayout()
        ));
        ghosttyKeyEventSetUnshiftedCodepoint = bind(
            lookup,
            "ghostty_key_event_set_unshifted_codepoint",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_INT)
        );
    }

    static GhosttyBindings get(NativeRuntime runtime) {
        GhosttyBindings bindings = INSTANCE.get();
        if (bindings != null) {
            return bindings;
        }

        GhosttyBindings created = new GhosttyBindings(runtime);
        if (INSTANCE.compareAndSet(null, created)) {
            return created;
        }
        return INSTANCE.get();
    }

    static void resetForTests() {
        INSTANCE.set(null);
    }

    ValueLayout.OfLong sizeTLayout() {
        return runtime.sizeTLayout();
    }

    MemorySegment allocateGhosttyString(Arena arena) {
        return arena.allocate(ghosttyStringLayout);
    }

    MemorySegment ghosttyStringPtr(MemorySegment struct) {
        return struct.get(C_POINTER, ghosttyStringPtrOffset);
    }

    long ghosttyStringLen(MemorySegment struct) {
        return struct.get(runtime.sizeTLayout(), ghosttyStringLenOffset);
    }

    MemorySegment ghosttyTypeJson() {
        return invokeAddress(ghosttyTypeJson, "ghostty_type_json");
    }

    int ghosttyBuildInfo(int data, MemorySegment out) {
        return invokeInt(ghosttyBuildInfo, "ghostty_build_info", data, out);
    }

    int ghosttyKeyEncoderNew(MemorySegment allocator, MemorySegment encoder) {
        return invokeInt(ghosttyKeyEncoderNew, "ghostty_key_encoder_new", allocator, encoder);
    }

    void ghosttyKeyEncoderFree(MemorySegment encoder) {
        invokeVoid(ghosttyKeyEncoderFree, "ghostty_key_encoder_free", encoder);
    }

    void ghosttyKeyEncoderSetopt(MemorySegment encoder, int option, MemorySegment value) {
        invokeVoid(ghosttyKeyEncoderSetopt, "ghostty_key_encoder_setopt", encoder, option, value);
    }

    int ghosttyKeyEncoderEncode(
        MemorySegment encoder,
        MemorySegment event,
        MemorySegment outBuf,
        long outBufSize,
        MemorySegment outLen
    ) {
        return invokeInt(
            ghosttyKeyEncoderEncode,
            "ghostty_key_encoder_encode",
            encoder,
            event,
            outBuf,
            outBufSize,
            outLen
        );
    }

    int ghosttyKeyEventNew(MemorySegment allocator, MemorySegment event) {
        return invokeInt(ghosttyKeyEventNew, "ghostty_key_event_new", allocator, event);
    }

    void ghosttyKeyEventFree(MemorySegment event) {
        invokeVoid(ghosttyKeyEventFree, "ghostty_key_event_free", event);
    }

    void ghosttyKeyEventSetAction(MemorySegment event, int action) {
        invokeVoid(ghosttyKeyEventSetAction, "ghostty_key_event_set_action", event, action);
    }

    void ghosttyKeyEventSetKey(MemorySegment event, int key) {
        invokeVoid(ghosttyKeyEventSetKey, "ghostty_key_event_set_key", event, key);
    }

    void ghosttyKeyEventSetMods(MemorySegment event, short mods) {
        invokeVoid(ghosttyKeyEventSetMods, "ghostty_key_event_set_mods", event, mods);
    }

    void ghosttyKeyEventSetConsumedMods(MemorySegment event, short consumedMods) {
        invokeVoid(ghosttyKeyEventSetConsumedMods, "ghostty_key_event_set_consumed_mods", event, consumedMods);
    }

    void ghosttyKeyEventSetComposing(MemorySegment event, boolean composing) {
        invokeVoid(ghosttyKeyEventSetComposing, "ghostty_key_event_set_composing", event, composing);
    }

    void ghosttyKeyEventSetUtf8(MemorySegment event, MemorySegment utf8, long len) {
        invokeVoid(ghosttyKeyEventSetUtf8, "ghostty_key_event_set_utf8", event, utf8, len);
    }

    void ghosttyKeyEventSetUnshiftedCodepoint(MemorySegment event, int codepoint) {
        invokeVoid(
            ghosttyKeyEventSetUnshiftedCodepoint,
            "ghostty_key_event_set_unshifted_codepoint",
            event,
            codepoint
        );
    }

    private static MethodHandle bind(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        MemorySegment address = lookup.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Native symbol '" + symbol + "' is unavailable"));
        return Linker.nativeLinker().downcallHandle(address, descriptor);
    }

    private static MemorySegment invokeAddress(MethodHandle handle, String symbol) {
        try {
            return (MemorySegment) handle.invokeExact();
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static int invokeInt(MethodHandle handle, String symbol, int arg0, MemorySegment arg1) {
        try {
            return (int) handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static int invokeInt(MethodHandle handle, String symbol, MemorySegment arg0, MemorySegment arg1) {
        try {
            return (int) handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static int invokeInt(
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
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0) {
        try {
            handle.invokeExact(arg0);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, int arg1) {
        try {
            handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, short arg1) {
        try {
            handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, boolean arg1) {
        try {
            handle.invokeExact(arg0, arg1);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }

    private static void invokeVoid(MethodHandle handle, String symbol, MemorySegment arg0, int arg1, MemorySegment arg2) {
        try {
            handle.invokeExact(arg0, arg1, arg2);
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
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
        } catch (Error | RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError("Failed to invoke " + symbol, e);
        }
    }
}
