package io.github.vlaaad.ghosttyfx.perfapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ThroughputProbe {
    private ThroughputProbe() {}

    public static void main(String[] args) throws IOException {
        var bytes = args.length == 0 ? 1_000_000L : Long.parseLong(args[0]);
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (reader.readLine() == null) {
                // wait for the harness to start the measured interval
            }
        }
        var pattern = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ https://example.test/perf\r\n"
                .getBytes(StandardCharsets.UTF_8);
        var out = System.out;
        var written = 0L;
        while (written < bytes) {
            var length = (int) Math.min(pattern.length, bytes - written);
            out.write(pattern, 0, length);
            written += length;
        }
        out.flush();
    }
}
