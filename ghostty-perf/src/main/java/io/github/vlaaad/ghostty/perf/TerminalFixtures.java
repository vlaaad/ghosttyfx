package io.github.vlaaad.ghostty.perf;

import io.github.vlaaad.ghostty.Ghostty;
import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalEvents;
import io.github.vlaaad.ghostty.TerminalQueries;
import io.github.vlaaad.ghostty.TerminalSession;

import java.nio.charset.StandardCharsets;

final class TerminalFixtures {
    private static final TerminalQueries QUERIES = new TerminalQueries() {};
    private static final TerminalEvents EVENTS = new TerminalEvents() {};

    private TerminalFixtures() {
    }

    static TerminalSession openSession(ViewportSize size) {
        return Ghostty.open(new TerminalConfig(size.columns(), size.rows(), size.scrollback()), bytes -> {}, QUERIES, EVENTS);
    }

    static byte[] page(ViewportSize size, int seed, boolean clear) {
        var builder = new StringBuilder(size.rows() * (size.columns() + 2) + 8);
        if (clear) {
            builder.append("\u001b[2J");
        }
        builder.append("\u001b[H");
        for (var row = 0; row < size.rows(); row++) {
            for (var column = 0; column < size.columns(); column++) {
                var value = Math.floorMod(seed + (row * 17) + (column * 31), 26);
                builder.append((char) ('a' + value));
            }
            if (row + 1 < size.rows()) {
                builder.append("\r\n");
            }
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    record ViewportSize(
        int columns,
        int rows,
        long scrollback
    ) {
        static ViewportSize parse(String value) {
            var parts = value.split("x", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Viewport must be <columns>x<rows>: " + value);
            }
            var columns = Integer.parseInt(parts[0]);
            var rows = Integer.parseInt(parts[1]);
            var scrollback = Math.max(1_024L, rows * 4L);
            return new ViewportSize(columns, rows, scrollback);
        }
    }
}
