package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributes;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesPrimary;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesSecondary;
import io.github.vlaaad.ghostty.bindings.GhosttyDeviceAttributesTertiary;
import io.github.vlaaad.ghostty.bindings.GhosttySizeReportSize;
import io.github.vlaaad.ghostty.bindings.GhosttyString;
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TerminalSessionEffectsTest {

    @Test
    void deliversBellAndTitleEffectsFromTerminalInput() throws Exception {
        var bells = new AtomicInteger();
        var title = new AtomicReference<String>();
        try (var session = newSession(_ -> {}, title::set, bells::incrementAndGet)) {
            session.writeToTerminal("\u0007".getBytes(StandardCharsets.UTF_8));
            session.writeToTerminal("\u001B]2;ghosttyfx title\u001B\\".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(1, bells.get());
        assertEquals("ghosttyfx title", title.get());
    }

    @Test
    void reportsCurrentSizeEffectData() throws Exception {
        try (var session = newSession(_ -> {}, _ -> {}, () -> {});
                var arena = Arena.ofConfined()) {
            var outSize = GhosttySizeReportSize.allocate(arena);
            var result = invoke(session, "reportSize", MemorySegment.NULL, MemorySegment.NULL, outSize);

            assertEquals(Boolean.TRUE, result);
            assertEquals(80, GhosttySizeReportSize.columns(outSize));
            assertEquals(24, GhosttySizeReportSize.rows(outSize));
            assertEquals(9, GhosttySizeReportSize.cell_width(outSize));
            assertEquals(18, GhosttySizeReportSize.cell_height(outSize));
        }
    }

    @Test
    void reportsDeviceAttributesEffectData() throws Exception {
        try (var session = newSession(_ -> {}, _ -> {}, () -> {});
                var arena = Arena.ofConfined()) {
            var attributes = GhosttyDeviceAttributes.allocate(arena);
            var result = invoke(session, "reportDeviceAttributes", MemorySegment.NULL, MemorySegment.NULL, attributes);
            var primary = GhosttyDeviceAttributes.primary(attributes);
            var secondary = GhosttyDeviceAttributes.secondary(attributes);
            var tertiary = GhosttyDeviceAttributes.tertiary(attributes);

            assertEquals(Boolean.TRUE, result);
            assertEquals(ghostty_vt_h.GHOSTTY_DA_CONFORMANCE_VT220(), GhosttyDeviceAttributesPrimary.conformance_level(primary));
            assertEquals(3, GhosttyDeviceAttributesPrimary.num_features(primary));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_FEATURE_COLUMNS_132(), GhosttyDeviceAttributesPrimary.features(primary, 0));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_FEATURE_SELECTIVE_ERASE(), GhosttyDeviceAttributesPrimary.features(primary, 1));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_FEATURE_ANSI_COLOR(), GhosttyDeviceAttributesPrimary.features(primary, 2));
            assertEquals(ghostty_vt_h.GHOSTTY_DA_DEVICE_TYPE_VT220(), GhosttyDeviceAttributesSecondary.device_type(secondary));
            assertEquals(1, GhosttyDeviceAttributesSecondary.firmware_version(secondary));
            assertEquals(0, GhosttyDeviceAttributesSecondary.rom_cartridge(secondary));
            assertEquals(0, GhosttyDeviceAttributesTertiary.unit_id(tertiary));
        }
    }

    @Test
    void reportsXtversionEffectData() throws Exception {
        try (var session = newSession(_ -> {}, _ -> {}, () -> {})) {
            var value = (MemorySegment) invoke(session, "reportXtversion", MemorySegment.NULL, MemorySegment.NULL);
            assertEquals("ghosttyfx", ghosttyString(value));
        }
    }

    private static TerminalSession newSession(
            java.util.function.Consumer<byte[]> terminalInput,
            java.util.function.Consumer<String> titleChanged,
            Runnable bell) {
        return new TerminalSession(80, 24, new TerminalView.CellMetrics(9, 18, 13), terminalInput, titleChanged, bell);
    }

    private static Object invoke(TerminalSession session, String methodName, Object... arguments) throws Exception {
        var method = method(methodName, arguments);
        method.setAccessible(true);
        return method.invoke(session, arguments);
    }

    private static Method method(String methodName, Object[] arguments) throws NoSuchMethodException {
        var types = new Class<?>[arguments.length];
        for (var i = 0; i < arguments.length; i++) {
            types[i] = switch (arguments[i]) {
                case MemorySegment _ -> MemorySegment.class;
                default -> arguments[i].getClass();
            };
        }
        return TerminalSession.class.getDeclaredMethod(methodName, types);
    }

    private static String ghosttyString(MemorySegment value) {
        var length = GhosttyString.len(value);
        var pointer = GhosttyString.ptr(value);
        return new String(pointer.reinterpret(length).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

}
