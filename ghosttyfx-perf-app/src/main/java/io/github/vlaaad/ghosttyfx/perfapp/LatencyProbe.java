package io.github.vlaaad.ghosttyfx.perfapp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class LatencyProbe {
    private LatencyProbe() {}

    public static void main(String[] args) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            var line = reader.readLine();
            while (line != null) {
                if (line.startsWith("PING ")) {
                    System.out.println("PONG " + line.substring("PING ".length()));
                    System.out.flush();
                }
                line = reader.readLine();
            }
        }
    }
}
