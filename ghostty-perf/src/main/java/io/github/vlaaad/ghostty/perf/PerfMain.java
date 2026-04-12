package io.github.vlaaad.ghostty.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.openjdk.jmh.runner.options.VerboseMode;

public final class PerfMain {
    private static final Path OUTPUT_DIR = Path.of("target", "jmh");
    private static final Path REPORT_FILE = OUTPUT_DIR.resolve("report.md");
    private static final Path BASELINE_RESULTS = OUTPUT_DIR.resolve("baseline.json");
    private static final Path GC_RESULTS = OUTPUT_DIR.resolve("dirty-gc.json");
    private static final Path JFR_RESULTS = OUTPUT_DIR.resolve("dirty-jfr.json");
    private static final Path JFR_DIR = OUTPUT_DIR.resolve("jfr");

    private PerfMain() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        recreateDirectory(OUTPUT_DIR);
        Files.createDirectories(JFR_DIR);

        var baselineOptions = new OptionsBuilder();
        baselineOptions
            .include(FrameBench.class.getSimpleName())
            .mode(Mode.AverageTime)
            .timeUnit(TimeUnit.MICROSECONDS)
            .warmupIterations(2)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(3)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)
            .shouldFailOnError(true);
        var baseline = runSuite(
            "baseline benchmarks",
            BASELINE_RESULTS,
            baselineOptions
        );

        var gcOptions = new OptionsBuilder();
        gcOptions
            .include(FrameBench.class.getSimpleName() + "\\.frameFullViewUpdate")
            .param("viewport", "120x40")
            .mode(Mode.AverageTime)
            .timeUnit(TimeUnit.MICROSECONDS)
            .warmupIterations(1)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(2)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)
            .shouldFailOnError(true)
            .addProfiler("gc");
        var dirtyGc = runSuite(
            "dirty viewport gc profile",
            GC_RESULTS,
            gcOptions
        );

        var jfrOptions = new OptionsBuilder();
        jfrOptions
            .include(FrameBench.class.getSimpleName() + "\\.frameFullViewUpdate")
            .param("viewport", "120x40")
            .mode(Mode.AverageTime)
            .timeUnit(TimeUnit.MICROSECONDS)
            .warmupIterations(1)
            .warmupTime(TimeValue.seconds(1))
            .measurementIterations(2)
            .measurementTime(TimeValue.seconds(1))
            .forks(1)
            .shouldFailOnError(true)
            .addProfiler("jfr", "dir=" + JFR_DIR.toAbsolutePath());
        var dirtyJfr = runSuite(
            "dirty viewport jfr profile",
            JFR_RESULTS,
            jfrOptions
        );

        var report = buildReport(baseline, dirtyGc, dirtyJfr, listJfrFiles());
        Files.writeString(
            REPORT_FILE,
            report,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        System.out.println(report);
        System.out.println("Report written to " + REPORT_FILE.toAbsolutePath());
    }

    private static List<RunResult> runSuite(String name, Path resultFile, OptionsBuilder optionsBuilder) throws RunnerException, IOException {
        System.out.println("Running " + name + "...");
        var options = optionsBuilder
            .verbosity(VerboseMode.SILENT)
            .build();
        var runner = new Runner(options, OutputFormatFactory.createFormatInstance(System.out, VerboseMode.SILENT));
        var results = new ArrayList<>(runner.run());
        results.sort(RunResult.DEFAULT_SORT_COMPARATOR);
        ResultFormatFactory.getInstance(ResultFormatType.JSON, resultFile.toAbsolutePath().toString()).writeOut(results);
        return results;
    }

    private static String buildReport(
        List<RunResult> baseline,
        List<RunResult> dirtyGc,
        List<RunResult> dirtyJfr,
        List<Path> jfrFiles
    ) {
        var builder = new StringBuilder();
        builder.append("# Ghostty Perf Report\n\n");
        builder.append("## Baseline\n\n");
        builder.append("| benchmark | viewport | score |\n");
        builder.append("|---|---|---:|\n");
        for (var result : baseline) {
            builder.append("| ")
                .append(shortBenchmark(result))
                .append(" | ")
                .append(result.getParams().getParam("viewport"))
                .append(" | ")
                .append(formatScore(result.getPrimaryResult()))
                .append(" |\n");
        }

        var gcRun = dirtyGc.getFirst();
        builder.append("\n## Dirty Viewport GC Profile\n\n");
        builder.append("- benchmark: `").append(shortBenchmark(gcRun)).append("`\n");
        builder.append("- viewport: `").append(gcRun.getParams().getParam("viewport")).append("`\n");
        builder.append("- score: ").append(formatScore(gcRun.getPrimaryResult())).append('\n');
        appendSecondary(builder, gcRun.getSecondaryResults(), "gc.alloc.rate.norm");
        appendSecondary(builder, gcRun.getSecondaryResults(), "gc.alloc.rate");
        appendSecondary(builder, gcRun.getSecondaryResults(), "gc.count");
        appendSecondary(builder, gcRun.getSecondaryResults(), "gc.time");

        var jfrRun = dirtyJfr.getFirst();
        builder.append("\n## Dirty Viewport JFR Profile\n\n");
        builder.append("- benchmark: `").append(shortBenchmark(jfrRun)).append("`\n");
        builder.append("- viewport: `").append(jfrRun.getParams().getParam("viewport")).append("`\n");
        builder.append("- score: ").append(formatScore(jfrRun.getPrimaryResult())).append('\n');
        if (jfrFiles.isEmpty()) {
            builder.append("- artifact: none\n");
        } else {
            builder.append("- artifacts:\n");
            for (var file : jfrFiles) {
                builder.append("  - `").append(file.toString().replace('\\', '/')).append("`\n");
            }
        }

        builder.append("\n## Files\n\n");
        builder.append("- `").append(BASELINE_RESULTS.toString().replace('\\', '/')).append("`\n");
        builder.append("- `").append(GC_RESULTS.toString().replace('\\', '/')).append("`\n");
        builder.append("- `").append(JFR_RESULTS.toString().replace('\\', '/')).append("`\n");
        builder.append("- `").append(REPORT_FILE.toString().replace('\\', '/')).append("`\n");
        return builder.toString();
    }

    private static void appendSecondary(StringBuilder builder, Map<String, Result> secondaryResults, String key) {
        var result = secondaryResults.get(key);
        if (result == null) {
            return;
        }
        builder.append("- ")
            .append(key)
            .append(": ")
            .append(formatScore(result))
            .append('\n');
    }

    private static String formatScore(Result result) {
        return String.format(Locale.US, "%.3f %s", result.getScore(), result.getScoreUnit());
    }

    private static String shortBenchmark(RunResult result) {
        var benchmark = result.getParams().getBenchmark();
        var index = benchmark.lastIndexOf('.');
        return index >= 0 ? benchmark.substring(index + 1) : benchmark;
    }

    private static List<Path> listJfrFiles() throws IOException {
        try (var files = Files.walk(JFR_DIR)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jfr"))
                .sorted(Comparator.naturalOrder())
                .map(path -> OUTPUT_DIR.getParent().resolve(OUTPUT_DIR.getFileName()).resolve(OUTPUT_DIR.relativize(path)))
                .collect(Collectors.toList());
        }
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                var toDelete = paths.sorted(Comparator.reverseOrder()).toList();
                for (var path : toDelete) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }
}
