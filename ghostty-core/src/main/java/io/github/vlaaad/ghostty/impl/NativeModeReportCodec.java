package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.ModeReportCodec;
import io.github.vlaaad.ghostty.ModeReportState;
import io.github.vlaaad.ghostty.TerminalMode;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

public final class NativeModeReportCodec implements ModeReportCodec {
    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;

    private final MethodHandle ghosttyModeReportEncode;

    NativeModeReportCodec(SymbolLookup lookup) {
        ghosttyModeReportEncode = NativeRuntime.bind(lookup, "ghostty_mode_report_encode", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_INT,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT,
            C_POINTER
        ));
    }

    @Override
    public byte[] encode(TerminalMode mode, ModeReportState state) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(state, "state");

        try (var arena = Arena.ofConfined()) {
            var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            try {
                NativeRuntime.invokeStatus(
                    ghosttyModeReportEncode,
                    "ghostty_mode_report_encode",
                    mode.packedValue(),
                    state.ordinal(),
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
                ghosttyModeReportEncode,
                "ghostty_mode_report_encode",
                mode.packedValue(),
                state.ordinal(),
                out,
                required,
                outLen
            );
            return out.asSlice(0, outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
