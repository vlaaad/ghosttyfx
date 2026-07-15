package io.github.vlaaad.ghosttyfx.perfapp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;

/// PING/PONG echo process for latency benchmarking.
///
/// Reads lines from stdin. Lines starting with "PING " are echoed
/// back as "PONG " with the same payload. All other lines are ignored.
///
/// Usage: java LatencyProbe
public final class LatencyProbe {

    private LatencyProbe() {}

    public static void main(String[] args) throws Exception {
        var reader = new BufferedReader(new InputStreamReader(System.in));
        var writer = new PrintWriter(System.out, true);
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("PING ")) {
                writer.println("PONG " + line.substring(5));
            }
        }
    }
}
