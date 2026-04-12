package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.SizeReportCodec;
import io.github.vlaaad.ghostty.SizeReportStyle;
import io.github.vlaaad.ghostty.TerminalSize;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

public final class NativeSizeReportCodec implements SizeReportCodec {
    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;
    private static final MemoryLayout SIZE_REPORT_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_SHORT.withName("rows"),
        ValueLayout.JAVA_SHORT.withName("columns"),
        ValueLayout.JAVA_INT.withName("cell_width"),
        ValueLayout.JAVA_INT.withName("cell_height")
    );

    private final MethodHandle ghosttySizeReportEncode;

    NativeSizeReportCodec(SymbolLookup lookup) {
        ghosttySizeReportEncode = NativeRuntime.bind(lookup, "ghostty_size_report_encode", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            SIZE_REPORT_LAYOUT,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT,
            C_POINTER
        ));
    }

    @Override
    public byte[] encode(SizeReportStyle style, TerminalSize size) {
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(size, "size");

        try (var arena = Arena.ofConfined()) {
            var nativeSize = arena.allocate(SIZE_REPORT_LAYOUT);
            nativeSize.set(
                ValueLayout.JAVA_SHORT,
                SIZE_REPORT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("rows")),
                (short) size.rows()
            );
            nativeSize.set(
                ValueLayout.JAVA_SHORT,
                SIZE_REPORT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("columns")),
                (short) size.columns()
            );
            nativeSize.set(
                ValueLayout.JAVA_INT,
                SIZE_REPORT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cell_width")),
                size.cellWidthPx()
            );
            nativeSize.set(
                ValueLayout.JAVA_INT,
                SIZE_REPORT_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cell_height")),
                size.cellHeightPx()
            );

            var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
            try {
                NativeRuntime.invokeStatus(
                    ghosttySizeReportEncode,
                    "ghostty_size_report_encode",
                    style.ordinal(),
                    nativeSize,
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
                ghosttySizeReportEncode,
                "ghostty_size_report_encode",
                style.ordinal(),
                nativeSize,
                out,
                required,
                outLen
            );
            return out.asSlice(0, outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE);
        }
    }
}
