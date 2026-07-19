import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

public final class PerfCompare {
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.000", DecimalFormatSymbols.getInstance(Locale.ROOT));

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: java scripts/PerfCompare.java baseline-summary.md candidate-summary.md [output.md]");
        }
        var baseline = read(Path.of(args[0]));
        var candidate = read(Path.of(args[1]));
        var report = compare(args[0], baseline, args[1], candidate);
        if (args.length >= 3) {
            Files.writeString(Path.of(args[2]), report);
        } else {
            System.out.println(report);
        }
    }

    private static String compare(String baselineName, Metrics baseline, String candidateName, Metrics candidate) {
        var out = new ArrayList<String>();
        out.add("# GhosttyFX Perf Comparison");
        out.add("");
        out.add("- Baseline: `" + baselineName + "`");
        out.add("- Candidate: `" + candidateName + "`");
        out.add("");
        out.add("| Metric | Baseline | Candidate | Delta | Delta % | Better |");
        out.add("| --- | ---: | ---: | ---: | ---: | --- |");
        row(out, "Run duration s", baseline.runDurationS, candidate.runDurationS, false);
        row(out, "Input throughput inputs/s", baseline.inputThroughput, candidate.inputThroughput, true);
        row(out, "Dispatch throughput events/s", baseline.dispatchThroughput, candidate.dispatchThroughput, true);
        row(out, "Run pulse p95 ms", baseline.runPulseP95Ms, candidate.runPulseP95Ms, false);
        row(out, "Run pulse max ms", baseline.runPulseMaxMs, candidate.runPulseMaxMs, false);
        for (var event : candidate.jfr.keySet()) {
            var base = baseline.jfr.get(event);
            var cand = candidate.jfr.get(event);
            if (base == null || cand == null) {
                continue;
            }
            row(out, "JFR " + event + " p95 ms", base.p95Ms, cand.p95Ms, false);
            if (base.mbPerSecond > 0 || cand.mbPerSecond > 0) {
                row(out, "JFR " + event + " MB/s", base.mbPerSecond, cand.mbPerSecond, true);
            }
        }
        return String.join(System.lineSeparator(), out);
    }

    private static void row(ArrayList<String> out, String label, double baseline, double candidate, boolean higherIsBetter) {
        var delta = candidate - baseline;
        var pct = baseline == 0 ? 0 : delta / baseline * 100;
        var better = delta == 0 ? "same" : higherIsBetter == delta > 0 ? "candidate" : "baseline";
        out.add("| " + label
                + " | " + DECIMAL.format(baseline)
                + " | " + DECIMAL.format(candidate)
                + " | " + DECIMAL.format(delta)
                + " | " + DECIMAL.format(pct)
                + " | " + better
                + " |");
    }

    private static Metrics read(Path summary) throws IOException {
        var result = new Metrics();
        var lines = Files.readAllLines(summary);
        for (var line : lines) {
            if (line.startsWith("- Run duration: ")) {
                result.runDurationS = number(line);
            } else if (line.startsWith("- Input throughput: ")) {
                result.inputThroughput = number(line);
            } else if (line.startsWith("- Dispatch throughput: ")) {
                result.dispatchThroughput = number(line);
            } else if (line.startsWith("| run |")) {
                var cells = cells(line);
                result.runPulseP95Ms = Double.parseDouble(cells[4]);
                result.runPulseMaxMs = Double.parseDouble(cells[6]);
            } else if (line.startsWith("| ") && !line.startsWith("| ---") && line.split("\\|").length >= 11) {
                var cells = cells(line);
                if (cells.length >= 10 && !cells[0].equals("Event")) {
                    result.jfr.put(cells[0], new Jfr(cells[4], cells[8]));
                }
            }
        }
        return result;
    }

    private static String[] cells(String markdownRow) {
        var raw = markdownRow.substring(1, markdownRow.length() - 1).split("\\|");
        for (var i = 0; i < raw.length; i++) {
            raw[i] = raw[i].trim();
        }
        return raw;
    }

    private static double number(String line) {
        var start = line.indexOf(':') + 1;
        var end = line.indexOf(' ', start + 1);
        return Double.parseDouble(line.substring(start, end).trim());
    }

    private static final class Metrics {
        double runDurationS;
        double inputThroughput;
        double dispatchThroughput;
        double runPulseP95Ms;
        double runPulseMaxMs;
        final LinkedHashMap<String, Jfr> jfr = new LinkedHashMap<>();
    }

    private record Jfr(double p95Ms, double mbPerSecond) {
        Jfr(String p95Ms, String mbPerSecond) {
            this(Double.parseDouble(p95Ms), Double.parseDouble(mbPerSecond));
        }
    }
}
