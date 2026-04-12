package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

import io.github.vlaaad.ghostty.FocusCodec;
import io.github.vlaaad.ghostty.FocusEvent;

public final class NativeFocusCodec implements FocusCodec {
    private static final long INITIAL_OUTPUT_CAPACITY = 3;
    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;

    private final MethodHandle ghosttyFocusEncode;

    NativeFocusCodec(SymbolLookup lookup) {
        ghosttyFocusEncode = NativeRuntime.bind(lookup, "ghostty_focus_encode", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT,
            C_POINTER
        ));
    }

    @Override
    public byte[] encode(FocusEvent event) {
        Objects.requireNonNull(event, "event");
        try (var arena = Arena.ofConfined()) {
            var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            var out = arena.allocate(INITIAL_OUTPUT_CAPACITY);
            try {
                NativeRuntime.invokeStatus(
                    ghosttyFocusEncode,
                    "ghostty_focus_encode",
                    event.action().ordinal(),
                    out,
                    INITIAL_OUTPUT_CAPACITY,
                    outLen
                );
                var written = outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0);
                return written == 0
                    ? new byte[0]
                    : out.asSlice(0, written).toArray(ValueLayout.JAVA_BYTE);
            } catch (ResultException exception) {
                if (exception.result != NativeRuntime.GHOSTTY_OUT_OF_SPACE) {
                    throw exception;
                }
            }

            var required = outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0);
            if (required == 0) {
                return new byte[0];
            }

            out = arena.allocate(required);
            NativeRuntime.invokeStatus(
                ghosttyFocusEncode,
                "ghostty_focus_encode",
                event.action().ordinal(),
                out,
                required,
                outLen
            );
            return out.asSlice(0, outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
