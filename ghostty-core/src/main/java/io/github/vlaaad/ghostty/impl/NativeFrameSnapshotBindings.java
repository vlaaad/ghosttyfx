package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

final class NativeFrameSnapshotBindings {
    static final MemoryLayout SNAPSHOT_VIEW_LAYOUT = MemoryLayout.structLayout(
        NativeRuntime.SIZE_T_LAYOUT.withName("size"),
        NativeTerminalBindings.C_POINTER.withName("data"),
        NativeRuntime.SIZE_T_LAYOUT.withName("len")
    );
    static final long SNAPSHOT_VIEW_SIZE_OFFSET = SNAPSHOT_VIEW_LAYOUT.byteOffset(groupElement("size"));
    static final long SNAPSHOT_VIEW_DATA_OFFSET = SNAPSHOT_VIEW_LAYOUT.byteOffset(groupElement("data"));
    static final long SNAPSHOT_VIEW_LEN_OFFSET = SNAPSHOT_VIEW_LAYOUT.byteOffset(groupElement("len"));

    final MethodHandle ghosttyfxFrameSnapshotNew;
    final MethodHandle ghosttyfxFrameSnapshotFree;
    final MethodHandle ghosttyfxFrameSnapshotCapture;

    NativeFrameSnapshotBindings(SymbolLookup lookup) {
        ghosttyfxFrameSnapshotNew = NativeRuntime.bind(
            lookup,
            "ghosttyfx_frame_snapshot_new",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER,
                NativeTerminalBindings.C_POINTER
            )
        );
        ghosttyfxFrameSnapshotFree = NativeRuntime.bind(
            lookup,
            "ghosttyfx_frame_snapshot_free",
            FunctionDescriptor.ofVoid(NativeTerminalBindings.C_POINTER)
        );
        ghosttyfxFrameSnapshotCapture = NativeRuntime.bind(
            lookup,
            "ghosttyfx_frame_snapshot_capture",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, NativeTerminalBindings.C_POINTER, NativeTerminalBindings.C_POINTER, NativeTerminalBindings.C_POINTER)
        );
    }
}
