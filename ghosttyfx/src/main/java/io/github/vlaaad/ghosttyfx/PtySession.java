package io.github.vlaaad.ghosttyfx;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class PtySession implements AutoCloseable {

    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();

    private final BlockingQueue<Command> commands = new ArrayBlockingQueue<>(16_384);
    private final BlockingQueue<ProcessOutput> processOutputs = new ArrayBlockingQueue<>(256);
    private final Future<?> ioTask;

    PtySession(List<String> command, Path cwd, Map<String, String> environment, int initialColumns, int initialRows) {
        ioTask = IO.submit(() -> runProcess(command, cwd, environment, initialColumns, initialRows));
    }

    @Override
    public void close() {
        ioTask.cancel(true);
    }

    void putCommand(Command command) throws InterruptedException {
        commands.put(command);
    }

    List<ProcessOutput> pollProcessOutputs() {
        var firstOutput = processOutputs.poll();
        if (firstOutput == null) {
            return List.of();
        }

        var outputs = new ArrayList<ProcessOutput>(1 + processOutputs.size());
        outputs.add(firstOutput);
        processOutputs.drainTo(outputs);
        return outputs;
    }

    private int runProcess(
            List<String> command,
            Path cwd,
            Map<String, String> environment,
            int initialColumns,
            int initialRows) throws Exception {
        if (!Files.isDirectory(cwd)) {
            throw new IllegalArgumentException("cwd must be an existing directory: " + cwd);
        }

        var process = (PtyProcess) new PtyProcessBuilder()
                .setCommand(command.toArray(String[]::new))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .setDirectory(cwd.toString())
                .setEnvironment(environment)
                .setInitialColumns(initialColumns)
                .setInitialRows(initialRows)
                .setUseWinConPty(true)
                .start();
        try {
            var outputTask = IO.submit(() -> {
                try (var input = process.getInputStream()) {
                    var buffer = new byte[8 * 1024];
                    while (true) {
                        var read = input.read(buffer);
                        if (read < 0) {
                            return null;
                        }
                        processOutputs.put(new Chunk(Arrays.copyOf(buffer, read)));
                    }
                } finally {
                    processOutputs.put(new Closed());
                }
            });
            try {
                // input task, no cleanup since we want to consume the proc commands even after the process exits
                IO.submit(() -> {
                    try (var output = process.getOutputStream()) {
                        while (true) {
                            switch (commands.take()) {
                                case WriteInput(var bytes) ->
                                    output.write(bytes);
                                case ResizePty(var columns, var rows) ->
                                    process.setWinSize(new WinSize(columns, rows));
                            }
                        }
                    } catch (Exception _) {
                        while (true) {
                            commands.take();
                        }
                    }
                });
                return process.waitFor();
            } catch (InterruptedException _) {
                outputTask.cancel(true);
                return destroyProcess(process);
            } finally {
                // outputTask cleanup
                outputTask.cancel(true);
            }
        } finally {
            // process cleanup
            if (process.isAlive()) {
                destroyProcess(process);
            }
        }
    }

    private static int destroyProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return process.waitFor();
        }
        return process.exitValue();
    }

    sealed interface Command permits WriteInput, ResizePty {
    }

    record WriteInput(byte[] bytes) implements Command {
    }

    record ResizePty(int columns, int rows) implements Command {
    }

    sealed interface ProcessOutput permits Chunk, Closed {
    }

    record Chunk(byte[] bytes) implements ProcessOutput {
    }

    record Closed() implements ProcessOutput {
    }
}
