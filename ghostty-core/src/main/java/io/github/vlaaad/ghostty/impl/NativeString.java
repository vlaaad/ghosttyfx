package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

final class NativeString {
    private static final AddressLayout POINTER_LAYOUT = ValueLayout.ADDRESS;
    static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        POINTER_LAYOUT.withName("ptr"),
        NativeRuntime.SIZE_T_LAYOUT.withName("len")
    );
    private static final long PTR_OFFSET = LAYOUT.byteOffset(groupElement("ptr"));
    private static final long LEN_OFFSET = LAYOUT.byteOffset(groupElement("len"));

    private NativeString() {
    }

    static MemorySegment allocate(SegmentAllocator allocator) {
        return allocator.allocate(LAYOUT);
    }

    static MemorySegment ptr(MemorySegment struct) {
        return struct.get(POINTER_LAYOUT, PTR_OFFSET);
    }

    static long len(MemorySegment struct) {
        return struct.get(NativeRuntime.SIZE_T_LAYOUT, LEN_OFFSET);
    }

    static String readUtf8(MemorySegment struct) {
        var length = len(struct);
        if (length == 0) {
            return "";
        }

        var bytes = ptr(struct).reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
