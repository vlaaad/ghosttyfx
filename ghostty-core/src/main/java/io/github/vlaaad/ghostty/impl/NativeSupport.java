package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public final class NativeSupport {
    private NativeSupport() {}

    public static void checkResult(
        String functionName,
        int result,
        int field,
        int successCode,
        int outOfMemoryCode,
        int invalidValueCode,
        int outOfSpaceCode,
        int noValueCode
    ) {
        if (result == successCode) {
            return;
        }
        if (result == outOfMemoryCode) {
            throw new IllegalStateException(functionName + "(" + field + ") failed: out of memory");
        }
        if (result == invalidValueCode) {
            throw new IllegalArgumentException(functionName + "(" + field + ") failed: invalid value");
        }
        if (result == outOfSpaceCode) {
            throw new IllegalStateException(functionName + "(" + field + ") failed: out of space");
        }
        if (result == noValueCode) {
            throw new IllegalStateException(functionName + "(" + field + ") failed: no value");
        }

        throw new IllegalStateException(
            functionName + "(" + field + ") failed with unexpected result code " + result
        );
    }

    public static String readUtf8String(MemorySegment pointer, long length) {
        if (length == 0) {
            return "";
        }

        byte[] bytes = pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
