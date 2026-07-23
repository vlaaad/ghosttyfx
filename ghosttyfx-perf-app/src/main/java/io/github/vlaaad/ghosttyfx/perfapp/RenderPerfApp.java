package io.github.vlaaad.ghosttyfx.perfapp;

import com.sun.management.ThreadMXBean;
import io.github.vlaaad.ghosttyfx.Terminal;
import io.github.vlaaad.ghosttyfx.TerminalView;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import javax.imageio.ImageIO;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class RenderPerfApp {
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final long FRAME_16_NS = 16_666_667L;
    private static final long FRAME_33_NS = 33_333_333L;
    private static final long FRAME_50_NS = 50_000_000L;
    private static final ThreadMXBean ALLOCATION = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    static {
        if (ALLOCATION.isThreadAllocatedMemorySupported() && !ALLOCATION.isThreadAllocatedMemoryEnabled()) {
            ALLOCATION.setThreadAllocatedMemoryEnabled(true);
        }
    }

    private RenderPerfApp() {}

    public static void main(String[] args) throws InterruptedException {
        var completion = new CountDownLatch(1);
        var failure = new Throwable[1];
        Platform.startup(() -> {
            try {
                start(completion, failure);
            } catch (Throwable t) {
                failure[0] = t;
                completion.countDown();
            }
        });
        completion.await();
        if (failure[0] != null) {
            if (failure[0] instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure[0] instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(failure[0]);
        }
    }

    private static void start(CountDownLatch completion, Throwable[] failure) {
        var config = Config.load();
        var terminal = new ControlledTerminal();
        var view = new TerminalView((_, _) -> terminal);
        view.setCursorBlinking(false);
        var stage = new Stage();
        stage.setTitle("GhosttyFX render benchmark");
        stage.setScene(new Scene(view, config.width(), config.height()));
        stage.setX(100);
        stage.setY(100);
        stage.show();
        view.requestFocus();

        Platform.runLater(() -> {
            try {
                var access = Access.create(view);
                access.write(config.scenario().preload());
                access.redraw();
                new Runner(stage, view, terminal, access, config, completion, failure).start();
            } catch (Throwable t) {
                failure[0] = t;
                close(stage, view, terminal);
                completion.countDown();
            }
        });
    }

    private static void close(Stage stage, TerminalView view, ControlledTerminal terminal) {
        try {
            view.close();
        } catch (RuntimeException _) {
        }
        terminal.close();
        stage.close();
    }

    private static long allocatedBytes() {
        return ALLOCATION.isThreadAllocatedMemoryEnabled()
                ? ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().threadId())
                : -1;
    }

    private static byte[] emptyScreen() {
        return "\u001B[?25l\u001B[2J\u001B[H".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] denseScreen() {
        var output = new StringBuilder("\u001B[?25l\u001B[2J\u001B[H");
        var pattern = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+-";
        for (var row = 0; row < 80; row++) {
            for (var column = 0; column < 160; column++) {
                output.append(pattern.charAt((row + column) % pattern.length()));
            }
            output.append("\r\n");
        }
        output.append("\u001B[H");
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] linkScreen() {
        var output = new StringBuilder("\u001B[?25l\u001B[2J\u001B[H");
        for (var row = 0; row < 80; row++) {
            switch (row % 6) {
                case 0 -> output.append("plain output row ").append(row).append(" without links");
                case 1 -> output.append("https://x.test/").append(row);
                case 2 -> output
                        .append("prefix https://example.test/docs/")
                        .append(row)
                        .append("/getting-started suffix");
                case 3 -> {
                    for (var link = 0; link < 4; link++) {
                        output
                                .append("https://link")
                                .append(link)
                                .append(".test/")
                                .append(row)
                                .append('/')
                                .append(link)
                                .append(' ');
                    }
                }
                case 4 -> output
                        .append("https://example.test/")
                        .append(row)
                        .append('/')
                        .append("long-path-segment/".repeat(12));
                case 5 -> {
                    for (var link = 0; link < 12; link++) {
                        output
                                .append("https://many")
                                .append(link)
                                .append(".test/")
                                .append(row)
                                .append("/path/")
                                .append(link)
                                .append(' ');
                    }
                }
                default -> throw new AssertionError();
            }
            output.append("\r\n");
        }
        output.append("\u001B[H");
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withImage(int z) {
        return concat(denseScreen(), kittyTransmission(true, 24, rgb(), z));
    }

    private static byte[] textUpdate() {
        return ("\u001B[H" + "text-update-".repeat(12)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] rgb() {
        var data = new byte[64 * 64 * 3];
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 64; x++) {
                var offset = (y * 64 + x) * 3;
                data[offset] = (byte) (x * 4);
                data[offset + 1] = (byte) (y * 4);
                data[offset + 2] = (byte) ((x * 17 + y * 31) & 0xFF);
            }
        }
        return data;
    }

    private static byte[] rgba() {
        var data = new byte[64 * 64 * 4];
        for (var y = 0; y < 64; y++) {
            for (var x = 0; x < 64; x++) {
                var offset = (y * 64 + x) * 4;
                data[offset] = (byte) (x * 4);
                data[offset + 1] = (byte) (y * 4);
                data[offset + 2] = (byte) ((x * 17 + y * 31) & 0xFF);
                data[offset + 3] = (byte) (64 + ((x + y) & 0xBF));
            }
        }
        return data;
    }

    private static byte[] png() {
        try {
            var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            for (var y = 0; y < image.getHeight(); y++) {
                for (var x = 0; x < image.getWidth(); x++) {
                    var alpha = 64 + ((x + y) & 0xBF);
                    var red = x * 2;
                    var green = y * 2;
                    var blue = (x * 17 + y * 31) & 0xFF;
                    image.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
                }
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", output)) {
                throw new IllegalStateException("No PNG writer");
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create PNG fixture", e);
        }
    }

    private static byte[] kittyTransmission(boolean display, int format, byte[] data, int z) {
        var payload = Base64.getEncoder().encodeToString(data);
        var output = new StringBuilder(payload.length() + 512);
        var chunks = Math.max(1, (payload.length() + 4095) / 4096);
        for (var chunk = 0; chunk < chunks; chunk++) {
            var start = chunk * 4096;
            var end = Math.min(payload.length(), start + 4096);
            output.append("\u001B_G");
            if (chunk == 0) {
                output.append("a=").append(display ? 'T' : 't')
                        .append(",t=d,f=").append(format)
                        .append(",i=1,q=2");
                if (format != 100) {
                    output.append(",s=64,v=64");
                }
                if (display) {
                    output.append(",p=1,c=80,r=40,C=1,z=").append(z);
                }
                output.append(",m=").append(chunk + 1 < chunks ? 1 : 0);
            } else {
                output.append("m=").append(chunk + 1 < chunks ? 1 : 0);
            }
            output.append(';').append(payload, start, end).append("\u001B\\");
        }
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] left, byte[] right) {
        var result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private enum Scenario {
        EMPTY_REDRAW,
        DENSE_REDRAW,
        LINK_REDRAW,
        TEXT_UPDATE,
        KITTY_ABOVE,
        KITTY_MIDDLE,
        RGB_RETRANSMIT,
        RGBA_RETRANSMIT,
        PNG_RETRANSMIT;

        byte[] preload() {
            return switch (this) {
                case EMPTY_REDRAW -> emptyScreen();
                case DENSE_REDRAW, TEXT_UPDATE -> denseScreen();
                case LINK_REDRAW -> linkScreen();
                case KITTY_ABOVE -> withImage(0);
                case KITTY_MIDDLE, RGB_RETRANSMIT, RGBA_RETRANSMIT, PNG_RETRANSMIT -> withImage(-1);
            };
        }

        Operation operation(Access access) {
            return switch (this) {
                case EMPTY_REDRAW, DENSE_REDRAW, LINK_REDRAW, KITTY_ABOVE, KITTY_MIDDLE -> access::redraw;
                case TEXT_UPDATE -> updateOperation(access, textUpdate());
                case RGB_RETRANSMIT -> updateOperation(access, kittyTransmission(false, 24, rgb(), 0));
                case RGBA_RETRANSMIT -> updateOperation(access, kittyTransmission(false, 32, rgba(), 0));
                case PNG_RETRANSMIT -> updateOperation(access, kittyTransmission(false, 100, png(), 0));
            };
        }

        private static Operation updateOperation(Access access, byte[] bytes) {
            return () -> {
                access.write(bytes);
                access.redraw();
            };
        }
    }

    @FunctionalInterface
    private interface Operation {
        void run();
    }

    private record Config(
            Scenario scenario,
            String version,
            Path outputDirectory,
            int width,
            int height,
            int warmupCount,
            int sampleCount,
            int batchSize,
            Duration settleDuration) {

        static Config load() {
            var scenario = Scenario.valueOf(System.getProperty("ghosttyfx.renderPerf.scenario", "DENSE_REDRAW")
                    .toUpperCase(Locale.ROOT));
            var version = System.getProperty("ghosttyfx.renderPerf.version", "unknown");
            var outputDirectory = Path.of(System.getProperty(
                            "ghosttyfx.renderPerf.outputDir",
                            "ghosttyfx-perf-app/target/render-perf-results"))
                    .toAbsolutePath()
                    .normalize();
            return new Config(
                    scenario,
                    version,
                    outputDirectory,
                    intProperty("ghosttyfx.renderPerf.width", 1200, 200),
                    intProperty("ghosttyfx.renderPerf.height", 800, 200),
                    intProperty("ghosttyfx.renderPerf.warmupCount", 200, 0),
                    intProperty("ghosttyfx.renderPerf.sampleCount", 600, 1),
                    intProperty("ghosttyfx.renderPerf.batchSize", 2, 1),
                    Duration.ofMillis(intProperty("ghosttyfx.renderPerf.settleMillis", 750, 0)));
        }

        private static int intProperty(String name, int defaultValue, int minimum) {
            var value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
            if (value < minimum) {
                throw new IllegalArgumentException(name + " must be >= " + minimum + ", got " + value);
            }
            return value;
        }
    }

    private record Access(TerminalView view, Object session, MethodHandle redrawHandle, MethodHandle writeHandle) {

        static Access create(TerminalView view) throws ReflectiveOperationException {
            var lookup = MethodHandles.lookup();
            var viewLookup = MethodHandles.privateLookupIn(TerminalView.class, lookup);
            var redraw = viewLookup.findVirtual(TerminalView.class, "redraw", MethodType.methodType(void.class));
            var sessionField = TerminalView.class.getDeclaredField("terminalSession");
            sessionField.setAccessible(true);
            var session = sessionField.get(view);
            var sessionType = sessionField.getType();
            var sessionLookup = MethodHandles.privateLookupIn(sessionType, lookup);
            var write = sessionLookup.findVirtual(
                    sessionType,
                    "writeToTerminal",
                    MethodType.methodType(void.class, byte[].class));
            return new Access(view, session, redraw, write);
        }

        void redraw() {
            try {
                redrawHandle.invoke(view);
            } catch (Throwable t) {
                throw unchecked(t);
            }
        }

        void write(byte[] bytes) {
            try {
                writeHandle.invoke(session, bytes);
            } catch (Throwable t) {
                throw unchecked(t);
            }
        }

        private static RuntimeException unchecked(Throwable t) {
            if (t instanceof RuntimeException runtimeException) {
                return runtimeException;
            }
            if (t instanceof Error error) {
                throw error;
            }
            return new RuntimeException(t);
        }
    }

    private enum Phase {
        SETTLE,
        WARMUP,
        PRE_MEASURE,
        MEASURE
    }

    private static final class Runner extends AnimationTimer {
        private final Stage stage;
        private final TerminalView view;
        private final ControlledTerminal terminal;
        private final Config config;
        private final CountDownLatch completion;
        private final Throwable[] failure;
        private final Operation operation;
        private final long[] durations;
        private final long[] allocations;
        private final ArrayList<Long> pulseIntervals = new ArrayList<>();
        private Phase phase = Phase.SETTLE;
        private long phaseStarted;
        private long previousPulse;
        private long measureStarted;
        private long measureFinished;
        private int warmed;
        private int measured;

        private Runner(
                Stage stage,
                TerminalView view,
                ControlledTerminal terminal,
                Access access,
                Config config,
                CountDownLatch completion,
                Throwable[] failure) {
            this.stage = stage;
            this.view = view;
            this.terminal = terminal;
            this.config = config;
            this.completion = completion;
            this.failure = failure;
            operation = config.scenario().operation(access);
            durations = new long[config.sampleCount()];
            allocations = new long[config.sampleCount()];
        }

        @Override
        public void handle(long now) {
            try {
                if (phaseStarted == 0) {
                    phaseStarted = now;
                }
                if (phase == Phase.MEASURE && previousPulse != 0) {
                    pulseIntervals.add(now - previousPulse);
                }
                previousPulse = now;

                switch (phase) {
                    case SETTLE -> {
                        if (now - phaseStarted >= config.settleDuration().toNanos()) {
                            switchPhase(Phase.WARMUP, now);
                        }
                    }
                    case WARMUP -> warmup(now);
                    case PRE_MEASURE -> {
                        if (now - phaseStarted >= config.settleDuration().toNanos()) {
                            measureStarted = System.nanoTime();
                            switchPhase(Phase.MEASURE, now);
                        }
                    }
                    case MEASURE -> measure();
                }
            } catch (Throwable t) {
                fail(t);
            }
        }

        private void warmup(long now) {
            var batch = Math.min(config.batchSize(), config.warmupCount() - warmed);
            for (var i = 0; i < batch; i++) {
                operation.run();
                warmed++;
            }
            if (warmed == config.warmupCount()) {
                System.gc();
                switchPhase(Phase.PRE_MEASURE, now);
            }
        }

        private void measure() {
            var batch = Math.min(config.batchSize(), config.sampleCount() - measured);
            for (var i = 0; i < batch; i++) {
                var allocatedBefore = allocatedBytes();
                var started = System.nanoTime();
                operation.run();
                durations[measured] = System.nanoTime() - started;
                var allocatedAfter = allocatedBytes();
                allocations[measured] = allocatedBefore < 0 || allocatedAfter < 0 ? -1 : allocatedAfter - allocatedBefore;
                measured++;
            }
            if (measured == config.sampleCount()) {
                measureFinished = System.nanoTime();
                succeed();
            }
        }

        private void switchPhase(Phase next, long now) {
            phase = next;
            phaseStarted = now;
            previousPulse = 0;
        }

        private void succeed() {
            stop();
            try {
                writeReport(
                        config,
                        durations,
                        allocations,
                        pulseIntervals,
                        measureFinished - measureStarted,
                        stage.getOutputScaleX(),
                        stage.getOutputScaleY());
                close(stage, view, terminal);
                completion.countDown();
            } catch (Throwable t) {
                fail(t);
            }
        }

        private void fail(Throwable t) {
            stop();
            failure[0] = t;
            close(stage, view, terminal);
            completion.countDown();
        }
    }

    private static void writeReport(
            Config config,
            long[] durations,
            long[] allocations,
            List<Long> pulses,
            long elapsedNs,
            double scaleX,
            double scaleY) throws IOException {
        Files.createDirectories(config.outputDirectory());
        var duration = summarize(durations);
        var allocation = summarize(Arrays.stream(allocations).filter(value -> value >= 0).toArray());
        var pulseValues = pulses.stream().mapToLong(Long::longValue).toArray();
        var pulse = summarize(pulseValues);
        var over16 = Arrays.stream(pulseValues).filter(value -> value > FRAME_16_NS).count();
        var over33 = Arrays.stream(pulseValues).filter(value -> value > FRAME_33_NS).count();
        var over50 = Arrays.stream(pulseValues).filter(value -> value > FRAME_50_NS).count();
        var report = List.of(
                "# GhosttyFX Render Perf Report",
                "",
                "- Generated: " + Instant.now(),
                "- Version: " + config.version(),
                "- Scenario: " + config.scenario(),
                "- Java: " + System.getProperty("java.runtime.version"),
                "- OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"),
                "- View: " + config.width() + "x" + config.height(),
                "- Output scale: " + DECIMAL.format(scaleX) + "x" + DECIMAL.format(scaleY),
                "- Warmup operations: " + config.warmupCount(),
                "- Measured operations: " + config.sampleCount(),
                "- Batch size: " + config.batchSize(),
                "",
                "## Operation duration",
                "",
                "- Average: " + millis(duration.average()) + " ms",
                "- P50: " + millis(duration.p50()) + " ms",
                "- P95: " + millis(duration.p95()) + " ms",
                "- P99: " + millis(duration.p99()) + " ms",
                "- Maximum: " + millis(duration.maximum()) + " ms",
                "- Synchronous capacity: " + DECIMAL.format(1_000_000_000d / duration.average()) + " ops/s",
                "- Wall rate: " + DECIMAL.format(config.sampleCount() * 1_000_000_000d / elapsedNs) + " ops/s",
                "",
                "## JavaFX-thread allocation",
                "",
                "- Average: " + DECIMAL.format(allocation.average()) + " bytes/op",
                "- P50: " + allocation.p50() + " bytes/op",
                "- P95: " + allocation.p95() + " bytes/op",
                "- P99: " + allocation.p99() + " bytes/op",
                "- Maximum: " + allocation.maximum() + " bytes/op",
                "",
                "## Pulse intervals",
                "",
                "- Count: " + pulseValues.length,
                "- P50: " + millis(pulse.p50()) + " ms",
                "- P95: " + millis(pulse.p95()) + " ms",
                "- P99: " + millis(pulse.p99()) + " ms",
                "- Maximum: " + millis(pulse.maximum()) + " ms",
                "- >16.7 ms: " + over16,
                "- >33.3 ms: " + over33,
                "- >50 ms: " + over50);
        Files.writeString(config.outputDirectory().resolve("summary.md"), String.join(System.lineSeparator(), report));

        var operationCsv = new ArrayList<String>(durations.length + 1);
        operationCsv.add("duration_ns,allocated_bytes");
        for (var i = 0; i < durations.length; i++) {
            operationCsv.add(durations[i] + "," + allocations[i]);
        }
        Files.write(config.outputDirectory().resolve("operation-samples.csv"), operationCsv);

        var pulseCsv = new ArrayList<String>(pulses.size() + 1);
        pulseCsv.add("interval_ns");
        pulses.forEach(value -> pulseCsv.add(Long.toString(value)));
        Files.write(config.outputDirectory().resolve("pulse-samples.csv"), pulseCsv);

        System.out.println(config.version() + " " + config.scenario()
                + " p50=" + millis(duration.p50()) + "ms"
                + " p95=" + millis(duration.p95()) + "ms"
                + " alloc=" + DECIMAL.format(allocation.average()) + "B/op");
    }

    private static Stats summarize(long[] values) {
        if (values.length == 0) {
            return new Stats(0, 0, 0, 0, 0);
        }
        var sorted = values.clone();
        Arrays.sort(sorted);
        return new Stats(
                Arrays.stream(values).average().orElse(0),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                sorted[sorted.length - 1]);
    }

    private static long percentile(long[] sorted, double percentile) {
        return sorted[Math.clamp((int) Math.ceil(sorted.length * percentile) - 1, 0, sorted.length - 1)];
    }

    private static String millis(double nanos) {
        return DECIMAL.format(nanos / 1_000_000d);
    }

    private record Stats(double average, long p50, long p95, long p99, long maximum) {
    }

    private static final class ControlledTerminal implements Terminal {
        private final PipedInputStream output;
        private final PipedOutputStream outputWriter;

        private ControlledTerminal() {
            try {
                output = new PipedInputStream(8 * 1024);
                outputWriter = new PipedOutputStream(output);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create controlled terminal", e);
            }
        }

        @Override
        public InputStream output() {
            return output;
        }

        @Override
        public OutputStream input() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void resize(int columns, int rows) {
        }

        @Override
        public void close() {
            try {
                outputWriter.close();
                output.close();
            } catch (IOException _) {
            }
        }
    }
}
