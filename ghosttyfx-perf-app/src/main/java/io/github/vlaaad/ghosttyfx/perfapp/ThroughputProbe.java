package io.github.vlaaad.ghosttyfx.perfapp;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/// Writes a configurable amount of terminal output to stdout.
///
/// Usage: java ThroughputProbe [bytes]
///   Writes approximately [bytes] bytes (default 1000000) then exits.
public final class ThroughputProbe {

    private ThroughputProbe() {}

    public static void main(String[] args) throws Exception {
        var targetBytes = args.length > 0 ? Long.parseLong(args[0]) : 1_000_000;
        var line = "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789 !@#$%^&*()_+-=\n";
        var lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
        var writer = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 64 * 1024);
        var written = 0L;
        while (written < targetBytes) {
            writer.write(line);
            written += lineBytes;
        }
        writer.flush();
    }
}
