package io.github.vlaaad.ghosttyfx.perfapp;

import io.github.vlaaad.ghosttyfx.GhosttyCanvas;
import io.github.vlaaad.ghosttyfx.GhosttyFx;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public final class GhosttyFxPerfApp {
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final long SLOW_PULSE_16_NS = 16_666_667L;
    private static final long SLOW_PULSE_33_NS = 33_333_333L;
    private static final long SLOW_PULSE_50_NS = 50_000_000L;

    private GhosttyFxPerfApp() {}

    public static void main(String[] args) throws InterruptedException {
        var completion = new Completion();
        Platform.startup(() -> {
            try {
                start(completion);
            } catch (Throwable t) {
                completion.fail(t);
            }
        });
        completion.await();
    }

    private static void start(Completion completion) {
        var config = PerfConfig.load();
        try {
            Files.createDirectories(config.outputDirectory());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create perf output directory", e);
        }
        var statusLog = new StatusLog(config.outputDirectory().resolve("status.log"));
        statusLog.write("startup");
        var terminal = detectTerminal();
        statusLog.write("terminal=" + terminal.label());
        var canvas = GhosttyFx.create(terminal.command(), config.cwd(), System.getenv());
        var root = new BorderPane(canvas);
        var scene = new Scene(root, config.width(), config.height());
        var stage = new Stage();
        stage.setTitle("GhosttyFX Perf App");
        stage.setScene(scene);
        stage.setOnCloseRequest(_ -> closeCanvas(canvas));
        stage.show();
        statusLog.write("stage-shown");
        canvas.requestFocus();

        var recorder = new Recorder();
        var controller = new Controller(stage, canvas, config, terminal, recorder, completion, statusLog);
        statusLog.write("controller-start");
        controller.start();
    }

    private static void closeCanvas(GhosttyCanvas canvas) {
        try {
            canvas.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static TerminalOption detectTerminal() {
        var terminals = detectTerminals();
        if (terminals.isEmpty()) {
            throw new IllegalStateException("No suitable terminal found");
        }
        return terminals.getFirst();
    }

    private static List<TerminalOption> detectTerminals() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? detectWindowsTerminals()
                : detectUnixTerminals();
    }

    private static List<TerminalOption> detectWindowsTerminals() {
        var result = new ArrayList<TerminalOption>();
        var seen = new LinkedHashSet<Path>();
        addTerminal(result, seen, "PowerShell", resolveExecutable("pwsh.exe"));
        addTerminal(result, seen, "Windows PowerShell", resolveExecutable("powershell.exe"));
        addTerminal(result, seen, "Command Prompt", resolveExecutable("cmd.exe"));

        var comspec = System.getenv("COMSPEC");
        if (comspec != null && !comspec.isBlank()) {
            addTerminal(result, seen, "COMSPEC", resolveExecutable(comspec));
        }
        return List.copyOf(result);
    }

    private static List<TerminalOption> detectUnixTerminals() {
        var result = new ArrayList<TerminalOption>();
        var seen = new LinkedHashSet<Path>();

        var shell = System.getenv("SHELL");
        if (shell != null && !shell.isBlank()) {
            var resolved = resolveExecutable(shell);
            addTerminal(result, seen, Path.of(shell).getFileName().toString(), resolved);
        }

        addTerminal(result, seen, "bash", resolveExecutable("bash"));
        addTerminal(result, seen, "zsh", resolveExecutable("zsh"));
        addTerminal(result, seen, "fish", resolveExecutable("fish"));
        addTerminal(result, seen, "sh", resolveExecutable("sh"));
        return List.copyOf(result);
    }

    private static void addTerminal(
            List<TerminalOption> terminals,
            LinkedHashSet<Path> seen,
            String label,
            Path executable) {
        if (executable == null || !seen.add(executable)) {
            return;
        }
        terminals.add(new TerminalOption(label, List.of(executable.toString())));
    }

    private static Path resolveExecutable(String candidate) {
        var path = Path.of(candidate);
        if (path.isAbsolute()) {
            return Files.isRegularFile(path) ? path : null;
        }
        if (candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0) {
            var absolute = path.toAbsolutePath().normalize();
            return Files.isRegularFile(absolute) ? absolute : null;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                var process = new ProcessBuilder("where.exe", candidate).redirectErrorStream(true).start();
                try (var reader = process.inputReader()) {
                    var line = reader.readLine();
                    if (process.waitFor() == 0 && line != null && !line.isBlank()) {
                        var resolved = Path.of(line.trim()).toAbsolutePath().normalize();
                        if (Files.isRegularFile(resolved)) {
                            return resolved;
                        }
                    }
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return searchPath(candidate);
    }

    private static Path searchPath(String candidate) {
        var pathEntries = System.getenv().getOrDefault("PATH", "").split(File.pathSeparator);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            var pathext = System.getenv().getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD")
                    .toLowerCase(Locale.ROOT)
                    .split(";");
            var hasExtension = candidate.contains(".");
            for (var entry : pathEntries) {
                if (entry.isBlank()) {
                    continue;
                }
                var directory = Path.of(entry);
                if (hasExtension) {
                    var resolved = directory.resolve(candidate);
                    if (Files.isRegularFile(resolved)) {
                        return resolved.toAbsolutePath().normalize();
                    }
                    continue;
                }
                for (var extension : pathext) {
                    var resolved = directory.resolve(candidate + extension);
                    if (Files.isRegularFile(resolved)) {
                        return resolved.toAbsolutePath().normalize();
                    }
                }
            }
            return null;
        }
        for (var entry : pathEntries) {
            if (entry.isBlank()) {
                continue;
            }
            var resolved = Path.of(entry).resolve(candidate);
            if (Files.isExecutable(resolved)) {
                return resolved.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static KeyEvent keyPressed(PerfConfig config) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", config.repeatKeyCode(), false, false, false, false);
    }

    private static KeyEvent keyTyped(String text) {
        return new KeyEvent(
                KeyEvent.KEY_TYPED,
                text,
                text,
                KeyCode.UNDEFINED,
                false,
                false,
                false,
                false);
    }

    private static KeyEvent keyReleased(PerfConfig config) {
        return new KeyEvent(KeyEvent.KEY_RELEASED, "", "", config.repeatKeyCode(), false, false, false, false);
    }

    private static long dispatch(GhosttyCanvas canvas, KeyEvent event) {
        var start = System.nanoTime();
        canvas.fireEvent(event);
        return System.nanoTime() - start;
    }

    private static void dispatchText(GhosttyCanvas canvas, Recorder recorder, String text) {
        text.codePoints()
                .mapToObj(codePoint -> new String(Character.toChars(codePoint)))
                .forEach(grapheme -> recorder.recordDispatch(DispatchKind.TEXT, dispatch(canvas, keyTyped(grapheme))));
    }

    private static void writeReport(
            PerfConfig config,
            TerminalOption terminal,
            Recorder recorder,
            double canvasWidth,
            double canvasHeight) {
        try {
            Files.createDirectories(config.outputDirectory());
            writeSummary(config, terminal, recorder, canvasWidth, canvasHeight);
            writeSamples(config.outputDirectory().resolve("dispatch-samples.csv"), recorder.dispatchSamples());
            writeSamples(config.outputDirectory().resolve("pulse-samples.csv"), recorder.pulseSamples());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write perf report", e);
        }
    }

    private static void writeSummary(
            PerfConfig config,
            TerminalOption terminal,
            Recorder recorder,
            double canvasWidth,
            double canvasHeight) throws IOException {
        var report = new ArrayList<String>();
        var runSeconds = recorder.runDurationNs() / 1_000_000_000d;
        var repeatThroughput = runSeconds == 0 ? 0 : config.repeatCount() / runSeconds;
        var runDispatchCount = recorder.repeatDispatchNs().size() + recorder.releaseDispatchNs().size();
        var eventThroughput = runSeconds == 0 ? 0 : runDispatchCount / runSeconds;
        var baseline = summarizePhase(recorder.baselinePulseNs());
        var run = summarizePhase(recorder.runPulseNs());
        var cooldown = summarizePhase(recorder.cooldownPulseNs());

        report.add("# GhosttyFX Perf Report");
        report.add("");
        report.add("- Generated: " + Instant.now());
        report.add("- Terminal: " + terminal.label());
        report.add("- Command: `" + terminal.command().stream().map(GhosttyFxPerfApp::escapeBackticks).collect(Collectors.joining(" ")) + "`");
        report.add("- Working directory: `" + escapeBackticks(config.cwd().toString()) + "`");
        report.add("- Canvas size: " + Math.round(canvasWidth) + "x" + Math.round(canvasHeight));
        report.add("- Scenario: type `" + escapeBackticks(config.preludeText()) + "` then hold `" + config.repeatKeyCode() + "`");
        report.add("- Repeats: " + config.repeatCount());
        report.add("- Batch size per pulse: " + config.batchSize());
        report.add("- Prelude settle: " + config.preludeSettleDuration().toMillis() + " ms");
        report.add("");
        report.add("## Summary");
        report.add("");
        report.add("- Run duration: " + DECIMAL.format(runSeconds) + " s");
        report.add("- Repeat throughput: " + DECIMAL.format(repeatThroughput) + " repeats/s");
        report.add("- Dispatch throughput: " + DECIMAL.format(eventThroughput) + " events/s");
        report.add("- Dispatch samples: " + recorder.dispatchSampleCount());
        report.add("- Prelude dispatch samples: " + recorder.textDispatchNs().size());
        report.add("- Run dispatch samples: " + runDispatchCount);
        report.add("- Pulse samples during run: " + recorder.runPulseNs().size());
        report.add("");
        report.add("## Dispatch");
        report.add("");
        report.add("| Event | Count | Avg ms | P50 ms | P95 ms | P99 ms | Max ms |");
        report.add("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
        report.add(summaryRow("prelude-text", summarize(recorder.textDispatchNs())));
        report.add(summaryRow("repeat-press", summarize(recorder.repeatDispatchNs())));
        report.add(summaryRow("release", summarize(recorder.releaseDispatchNs())));
        report.add("");
        report.add("## Pulses");
        report.add("");
        report.add("| Phase | Count | Avg ms | P50 ms | P95 ms | P99 ms | Max ms | >16.7ms | >33.3ms | >50ms |");
        report.add("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        report.add(phaseRow("baseline", baseline));
        report.add(phaseRow("run", run));
        report.add(phaseRow("cooldown", cooldown));
        report.add("");
        report.add("## Files");
        report.add("");
        report.add("- `dispatch-samples.csv`");
        report.add("- `pulse-samples.csv`");

        Files.writeString(config.outputDirectory().resolve("summary.md"), String.join(System.lineSeparator(), report));
        System.out.println("GhosttyFX perf report written to " + config.outputDirectory().resolve("summary.md"));
        System.out.println("Run duration: " + DECIMAL.format(runSeconds) + " s");
        System.out.println("Repeat throughput: " + DECIMAL.format(repeatThroughput) + " repeats/s");
        System.out.println("Run pulse P95: " + DECIMAL.format(run.summary().p95Ms()) + " ms");
        System.out.println("Run pulse max: " + DECIMAL.format(run.summary().maxMs()) + " ms");
    }

    private static void writeSamples(Path path, List<String> samples) throws IOException {
        Files.write(path, samples);
    }

    private static String summaryRow(String label, Summary summary) {
        return "| " + label
                + " | " + summary.count()
                + " | " + DECIMAL.format(summary.averageMs())
                + " | " + DECIMAL.format(summary.p50Ms())
                + " | " + DECIMAL.format(summary.p95Ms())
                + " | " + DECIMAL.format(summary.p99Ms())
                + " | " + DECIMAL.format(summary.maxMs())
                + " |";
    }

    private static String phaseRow(String label, PhaseSummary summary) {
        return "| " + label
                + " | " + summary.summary().count()
                + " | " + DECIMAL.format(summary.summary().averageMs())
                + " | " + DECIMAL.format(summary.summary().p50Ms())
                + " | " + DECIMAL.format(summary.summary().p95Ms())
                + " | " + DECIMAL.format(summary.summary().p99Ms())
                + " | " + DECIMAL.format(summary.summary().maxMs())
                + " | " + summary.over16Ms()
                + " | " + summary.over33Ms()
                + " | " + summary.over50Ms()
                + " |";
    }

    private static PhaseSummary summarizePhase(List<Long> values) {
        var summary = summarize(values);
        var over16 = values.stream().filter(value -> value > SLOW_PULSE_16_NS).count();
        var over33 = values.stream().filter(value -> value > SLOW_PULSE_33_NS).count();
        var over50 = values.stream().filter(value -> value > SLOW_PULSE_50_NS).count();
        return new PhaseSummary(summary, over16, over33, over50);
    }

    private static Summary summarize(List<Long> values) {
        if (values.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, 0);
        }

        var sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        var sum = values.stream().mapToLong(Long::longValue).sum();
        return new Summary(
                values.size(),
                nanosToMillis(sum / (double) values.size()),
                nanosToMillis(percentile(sorted, 0.50)),
                nanosToMillis(percentile(sorted, 0.95)),
                nanosToMillis(percentile(sorted, 0.99)),
                nanosToMillis(sorted[sorted.length - 1]));
    }

    private static long percentile(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        var index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.clamp(index, 0, sorted.length - 1)];
    }

    private static double nanosToMillis(double value) {
        return value / 1_000_000d;
    }

    private static String escapeBackticks(String value) {
        return value.replace("`", "\\`");
    }

    private enum Phase {
        STARTUP,
        BASELINE,
        PRELUDE,
        SETTLE,
        RUN,
        COOLDOWN
    }

    private enum DispatchKind {
        TEXT,
        REPEAT,
        RELEASE
    }

    private record TerminalOption(String label, List<String> command) {
    }

    private record PerfConfig(
            Path cwd,
            Path outputDirectory,
            int width,
            int height,
            int repeatCount,
            int batchSize,
            Duration startupDelay,
            Duration baselineDuration,
            Duration preludeSettleDuration,
            Duration cooldownDuration,
            KeyCode repeatKeyCode,
            String preludeText) {

        static PerfConfig load() {
            var cwd = Path.of(System.getProperty("ghosttyfx.perf.cwd", System.getProperty("user.dir", ".")))
                    .toAbsolutePath()
                    .normalize();
            var outputDirectory = Path.of(System.getProperty(
                            "ghosttyfx.perf.outputDir",
                            cwd.resolve("ghosttyfx-perf-app").resolve("target").resolve("perf-results").toString()))
                    .toAbsolutePath()
                    .normalize();
            var width = intProperty("ghosttyfx.perf.width", 1200, 200);
            var height = intProperty("ghosttyfx.perf.height", 800, 200);
            var repeatCount = intProperty("ghosttyfx.perf.repeatCount", 2_000, 1);
            var batchSize = intProperty("ghosttyfx.perf.batchSize", 8, 1);
            var startupDelay = durationProperty("ghosttyfx.perf.startupMillis", 1_500, 0);
            var baselineDuration = durationProperty("ghosttyfx.perf.baselineMillis", 1_000, 1);
            var preludeSettleDuration = durationProperty("ghosttyfx.perf.preludeSettleMillis", 500, 0);
            var cooldownDuration = durationProperty("ghosttyfx.perf.cooldownMillis", 1_000, 0);
            var repeatKeyCode = KeyCode.valueOf(System.getProperty("ghosttyfx.perf.repeatKeyCode", "TAB").toUpperCase(Locale.ROOT));
            var preludeText = System.getProperty("ghosttyfx.perf.preludeText", "cd ");
            if (preludeText.isEmpty()) {
                throw new IllegalArgumentException("ghosttyfx.perf.preludeText must not be empty");
            }
            return new PerfConfig(
                    cwd,
                    outputDirectory,
                    width,
                    height,
                    repeatCount,
                    batchSize,
                    startupDelay,
                    baselineDuration,
                    preludeSettleDuration,
                    cooldownDuration,
                    repeatKeyCode,
                    preludeText);
        }

        private static int intProperty(String name, int defaultValue, int minimum) {
            var value = Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
            if (value < minimum) {
                throw new IllegalArgumentException(name + " must be >= " + minimum + ", got " + value);
            }
            return value;
        }

        private static Duration durationProperty(String name, int defaultMillis, int minimumMillis) {
            var millis = intProperty(name, defaultMillis, minimumMillis);
            return Duration.ofMillis(millis);
        }
    }

    private record Summary(int count, double averageMs, double p50Ms, double p95Ms, double p99Ms, double maxMs) {
    }

    private record PhaseSummary(Summary summary, long over16Ms, long over33Ms, long over50Ms) {
    }

    private static final class Recorder {
        private final List<Long> textDispatchNs = new ArrayList<>();
        private final List<Long> repeatDispatchNs = new ArrayList<>();
        private final List<Long> releaseDispatchNs = new ArrayList<>();
        private final List<Long> baselinePulseNs = new ArrayList<>();
        private final List<Long> runPulseNs = new ArrayList<>();
        private final List<Long> cooldownPulseNs = new ArrayList<>();
        private final List<String> dispatchSamples = new ArrayList<>(List.of("kind,duration_ns"));
        private final List<String> pulseSamples = new ArrayList<>(List.of("phase,interval_ns"));
        private long runStartNs;
        private long runEndNs;

        void recordDispatch(DispatchKind kind, long durationNs) {
            switch (kind) {
                case TEXT -> textDispatchNs.add(durationNs);
                case REPEAT -> repeatDispatchNs.add(durationNs);
                case RELEASE -> releaseDispatchNs.add(durationNs);
            }
            dispatchSamples.add(kind.name().toLowerCase(Locale.ROOT) + "," + durationNs);
        }

        void recordPulse(Phase phase, long intervalNs) {
            switch (phase) {
                case BASELINE -> baselinePulseNs.add(intervalNs);
                case RUN -> runPulseNs.add(intervalNs);
                case COOLDOWN -> cooldownPulseNs.add(intervalNs);
                case STARTUP, PRELUDE, SETTLE -> {
                    return;
                }
            }
            pulseSamples.add(phase.name().toLowerCase(Locale.ROOT) + "," + intervalNs);
        }

        void markRunStart() {
            if (runStartNs == 0) {
                runStartNs = System.nanoTime();
            }
        }

        void markRunEnd() {
            runEndNs = System.nanoTime();
        }

        long runDurationNs() {
            return runEndNs > runStartNs ? runEndNs - runStartNs : 0;
        }

        int dispatchSampleCount() {
            return dispatchSamples.size() - 1;
        }

        List<Long> textDispatchNs() {
            return textDispatchNs;
        }

        List<Long> repeatDispatchNs() {
            return repeatDispatchNs;
        }

        List<Long> releaseDispatchNs() {
            return releaseDispatchNs;
        }

        List<Long> baselinePulseNs() {
            return baselinePulseNs;
        }

        List<Long> runPulseNs() {
            return runPulseNs;
        }

        List<Long> cooldownPulseNs() {
            return cooldownPulseNs;
        }

        List<String> dispatchSamples() {
            return dispatchSamples;
        }

        List<String> pulseSamples() {
            return pulseSamples;
        }
    }

    private static final class Controller extends AnimationTimer {
        private final Stage stage;
        private final GhosttyCanvas canvas;
        private final PerfConfig config;
        private final TerminalOption terminal;
        private final Recorder recorder;
        private final Completion completion;
        private final StatusLog statusLog;
        private long lastPulseNs;
        private long phaseStartedNs;
        private int repeatsSent;
        private Phase phase = Phase.STARTUP;
        private boolean completed;

        private Controller(
                Stage stage,
                GhosttyCanvas canvas,
                PerfConfig config,
                TerminalOption terminal,
                Recorder recorder,
                Completion completion,
                StatusLog statusLog) {
            this.stage = stage;
            this.canvas = canvas;
            this.config = config;
            this.terminal = terminal;
            this.recorder = recorder;
            this.completion = completion;
            this.statusLog = statusLog;
        }

        @Override
        public void handle(long now) {
            try {
                if (lastPulseNs != 0) {
                    recorder.recordPulse(phase, now - lastPulseNs);
                }
                lastPulseNs = now;
                if (phaseStartedNs == 0) {
                    phaseStartedNs = now;
                }

                switch (phase) {
                    case STARTUP -> handleStartup(now);
                    case BASELINE -> handleBaseline(now);
                    case PRELUDE -> handlePrelude(now);
                    case SETTLE -> handleSettle(now);
                    case RUN -> handleRun();
                    case COOLDOWN -> handleCooldown(now);
                }
            } catch (Throwable t) {
                completeExceptionally(t);
            }
        }

        private void handleStartup(long now) {
            canvas.requestFocus();
            if (now - phaseStartedNs >= config.startupDelay().toNanos()) {
                switchPhase(Phase.BASELINE, now);
            }
        }

        private void handleBaseline(long now) {
            if (now - phaseStartedNs >= config.baselineDuration().toNanos()) {
                switchPhase(Phase.PRELUDE, now);
            }
        }

        private void handlePrelude(long now) {
            statusLog.write("prelude=" + config.preludeText());
            dispatchText(canvas, recorder, config.preludeText());
            switchPhase(Phase.SETTLE, now);
        }

        private void handleSettle(long now) {
            if (now - phaseStartedNs >= config.preludeSettleDuration().toNanos()) {
                switchPhase(Phase.RUN, now);
            }
        }

        private void handleRun() {
            if (repeatsSent == 0) {
                recorder.markRunStart();
                statusLog.write("run-start");
            }

            var batch = Math.min(config.batchSize(), config.repeatCount() - repeatsSent);
            for (var i = 0; i < batch; i++) {
                recorder.recordDispatch(DispatchKind.REPEAT, dispatch(canvas, keyPressed(config)));
                repeatsSent++;
                if (repeatsSent == 1 || repeatsSent == config.repeatCount() || repeatsSent % 100 == 0) {
                    statusLog.write("repeats=" + repeatsSent);
                }
            }

            if (repeatsSent == config.repeatCount()) {
                recorder.recordDispatch(DispatchKind.RELEASE, dispatch(canvas, keyReleased(config)));
                recorder.markRunEnd();
                switchPhase(Phase.COOLDOWN, lastPulseNs);
            }
        }

        private void handleCooldown(long now) {
            if (now - phaseStartedNs >= config.cooldownDuration().toNanos()) {
                completeSuccessfully();
            }
        }

        private void switchPhase(Phase nextPhase, long now) {
            phase = nextPhase;
            phaseStartedNs = now;
            statusLog.write("phase=" + nextPhase.name().toLowerCase(Locale.ROOT));
        }

        private void completeSuccessfully() {
            if (completed) {
                return;
            }
            completed = true;
            stop();
            try {
                statusLog.write("writing-report");
                writeReport(config, terminal, recorder, canvas.getWidth(), canvas.getHeight());
                closeCanvas(canvas);
                stage.close();
                statusLog.write("success");
                completion.succeed();
            } catch (Throwable t) {
                completeExceptionally(t);
                return;
            }
            Platform.exit();
        }

        private void completeExceptionally(Throwable t) {
            if (completed) {
                return;
            }
            completed = true;
            stop();
            statusLog.write("failure=" + t.getClass().getName() + ":" + t.getMessage());
            closeCanvas(canvas);
            stage.close();
            completion.fail(t);
            Platform.exit();
        }
    }

    private static final class StatusLog {
        private final Path path;

        private StatusLog(Path path) {
            this.path = path;
        }

        void write(String message) {
            try {
                Files.writeString(
                        path,
                        Instant.now() + " " + message + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to append status log", e);
            }
        }
    }

    private static final class Completion {
        private final Object monitor = new Object();
        private Throwable failure;
        private boolean complete;

        void succeed() {
            synchronized (monitor) {
                if (complete) {
                    return;
                }
                complete = true;
                monitor.notifyAll();
            }
        }

        void fail(Throwable t) {
            synchronized (monitor) {
                if (complete) {
                    return;
                }
                failure = t;
                complete = true;
                monitor.notifyAll();
            }
        }

        void await() throws InterruptedException {
            synchronized (monitor) {
                while (!complete) {
                    monitor.wait();
                }
            }
            if (failure != null) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(failure);
            }
        }
    }
}
