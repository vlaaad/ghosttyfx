package io.github.vlaaad.ghosttyfx;

import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import io.github.vlaaad.ghostty.bindings.GhosttySysImage;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;

final class PngDecoder {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final int MAX_DIMENSION = 10_000;
    private static final int MAX_DECODED_BYTES = 64 * 1024 * 1024;

    private PngDecoder() {}

    static boolean decode(
            MemorySegment userdata,
            MemorySegment allocator,
            MemorySegment data,
            long dataLength,
            MemorySegment outAddress) {
        MemorySegment pixels = MemorySegment.NULL;
        var pixelLength = 0L;
        try {
            if (dataLength < SIGNATURE.length || dataLength > Integer.MAX_VALUE || data.equals(MemorySegment.NULL)) {
                return false;
            }

            var encoded = data.reinterpret(dataLength).toArray(ValueLayout.JAVA_BYTE);
            if (!Arrays.equals(SIGNATURE, 0, SIGNATURE.length, encoded, 0, SIGNATURE.length)) {
                return false;
            }

            var readers = ImageIO.getImageReadersByFormatName("PNG");
            if (!readers.hasNext()) {
                return false;
            }

            var reader = readers.next();
            int width;
            int height;
            byte[] rgba;
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
                reader.setInput(input, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    return false;
                }

                pixelLength = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
                if (pixelLength > MAX_DECODED_BYTES) {
                    return false;
                }

                var image = reader.read(0, reader.getDefaultReadParam());
                if (image.getWidth() != width || image.getHeight() != height) {
                    return false;
                }
                var argb = image.getRGB(0, 0, width, height, null, 0, width);
                rgba = new byte[Math.toIntExact(pixelLength)];
                for (var i = 0; i < argb.length; i++) {
                    var value = argb[i];
                    var offset = i * 4;
                    rgba[offset] = (byte) (value >>> 16);
                    rgba[offset + 1] = (byte) (value >>> 8);
                    rgba[offset + 2] = (byte) value;
                    rgba[offset + 3] = (byte) (value >>> 24);
                }
            } finally {
                reader.dispose();
            }

            pixels = ghostty_vt_h.ghostty_alloc(allocator, pixelLength);
            if (pixels.equals(MemorySegment.NULL)) {
                return false;
            }
            MemorySegment.copy(rgba, 0, pixels.reinterpret(pixelLength), ValueLayout.JAVA_BYTE, 0, rgba.length);

            try (var arena = Arena.ofConfined()) {
                var decoded = GhosttySysImage.allocate(arena);
                GhosttySysImage.width(decoded, width);
                GhosttySysImage.height(decoded, height);
                GhosttySysImage.data(decoded, pixels);
                GhosttySysImage.data_len(decoded, pixelLength);
                MemorySegment.copy(decoded, 0, outAddress.reinterpret(GhosttySysImage.sizeof()), 0, GhosttySysImage.sizeof());
            }
            return true;
        } catch (Throwable _) {
            if (!pixels.equals(MemorySegment.NULL)) {
                try {
                    ghostty_vt_h.ghostty_free(allocator, pixels, pixelLength);
                } catch (Throwable _) {
                    // An exception escaping an FFM upcall terminates the JVM.
                }
            }
            return false;
        }
    }
}
