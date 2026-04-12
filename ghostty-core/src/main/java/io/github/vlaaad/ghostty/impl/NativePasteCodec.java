package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.PasteCodec;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class NativePasteCodec implements PasteCodec {
    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;

    private final MethodHandle ghosttyPasteIsSafe;
    private final MethodHandle ghosttyPasteEncode;

    NativePasteCodec(SymbolLookup lookup) {
        ghosttyPasteIsSafe = NativeRuntime.bind(lookup, "ghostty_paste_is_safe", FunctionDescriptor.of(
            ValueLayout.JAVA_BOOLEAN,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT
        ));
        ghosttyPasteEncode = NativeRuntime.bind(lookup, "ghostty_paste_encode", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT,
            ValueLayout.JAVA_BOOLEAN,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT,
            C_POINTER
        ));
    }

    @Override
    public boolean isSafe(String data) {
        Objects.requireNonNull(data, "data");
        var bytes = data.getBytes(StandardCharsets.UTF_8);
        try (var arena = Arena.ofConfined()) {
            var input = copyBytes(arena, bytes);
            return NativeRuntime.invoke(ghosttyPasteIsSafe, input, (long) bytes.length);
        }
    }

    @Override
    public byte[] encode(String data, boolean bracketed) {
        Objects.requireNonNull(data, "data");
        var bytes = data.getBytes(StandardCharsets.UTF_8);
        try (var arena = Arena.ofConfined()) {
            var mutableInput = copyBytes(arena, bytes);
            var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            try {
                NativeRuntime.invokeStatus(
                    ghosttyPasteEncode,
                    "ghostty_paste_encode",
                    mutableInput,
                    (long) bytes.length,
                    bracketed,
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
                ghosttyPasteEncode,
                "ghostty_paste_encode",
                mutableInput,
                (long) bytes.length,
                bracketed,
                out,
                required,
                outLen
            );
            return out.asSlice(0, outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    private static MemorySegment copyBytes(Arena arena, byte[] data) {
        if (data.length == 0) {
            return MemorySegment.NULL;
        }

        var segment = arena.allocate(data.length);
        segment.asByteBuffer().put(data);
        return segment;
    }
}
