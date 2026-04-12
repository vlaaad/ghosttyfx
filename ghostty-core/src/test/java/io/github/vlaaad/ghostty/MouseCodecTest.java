package io.github.vlaaad.ghostty;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class MouseCodecTest {
    private static final KeyModifiers NO_MODS = new KeyModifiers(false, false, false, false, false, false, null, null, null, null);
    private static final MouseEncoderSize SIMPLE_SIZE = new MouseEncoderSize(1_000, 1_000, 1, 1, 0, 0, 0, 0);
    private static final MouseEncoderSize GRID_SIZE = new MouseEncoderSize(800, 600, 10, 20, 0, 0, 0, 0);

    @Test
    void encodesSgrPressUsingRendererGeometry() {
        var codec = Ghostty.mouseCodec(new MouseCodecConfig());

        assertArrayEquals(
            bytes("\u001B[<0;6;3M"),
            codec.encode(
                new MouseEvent(MouseAction.PRESS, MouseButton.LEFT, NO_MODS, new MousePosition(50.0f, 40.0f)),
                new MouseEncodeContext(MouseFormat.SGR, MouseTrackingMode.NORMAL, GRID_SIZE, true, false)
            )
        );
    }

    @Test
    void encodesMotionWithoutButton() {
        var codec = Ghostty.mouseCodec(new MouseCodecConfig());

        assertArrayEquals(
            bytes("\u001B[<35;2;3M"),
            codec.encode(
                new MouseEvent(MouseAction.MOTION, null, NO_MODS, new MousePosition(1.0f, 2.0f)),
                new MouseEncodeContext(MouseFormat.SGR, MouseTrackingMode.ANY, SIMPLE_SIZE, false, false)
            )
        );
    }

    @Test
    void onlyReportsOutsideViewportMotionWhenAButtonIsPressed() {
        var codec = Ghostty.mouseCodec(new MouseCodecConfig());
        var event = new MouseEvent(MouseAction.MOTION, MouseButton.LEFT, NO_MODS, new MousePosition(-1.0f, -1.0f));
        var baseContext = new MouseEncodeContext(MouseFormat.SGR, MouseTrackingMode.ANY, SIMPLE_SIZE, false, false);

        assertArrayEquals(new byte[0], codec.encode(event, baseContext));
        assertArrayEquals(
            bytes("\u001B[<32;1;1M"),
            codec.encode(
                event,
                new MouseEncodeContext(
                    baseContext.format(),
                    baseContext.trackingMode(),
                    baseContext.size(),
                    true,
                    baseContext.trackLastCell()
                )
            )
        );
    }

    @Test
    void dedupesMotionUntilReset() {
        var codec = Ghostty.mouseCodec(new MouseCodecConfig());
        var event = new MouseEvent(MouseAction.MOTION, MouseButton.LEFT, NO_MODS, new MousePosition(5.0f, 6.0f));
        var context = new MouseEncodeContext(MouseFormat.SGR, MouseTrackingMode.ANY, SIMPLE_SIZE, true, true);
        var expected = bytes("\u001B[<32;6;7M");

        assertArrayEquals(expected, codec.encode(event, context));
        assertArrayEquals(new byte[0], codec.encode(event, context));

        codec.reset();

        assertArrayEquals(expected, codec.encode(event, context));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
