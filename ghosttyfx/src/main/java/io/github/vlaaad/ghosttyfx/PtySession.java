package io.github.vlaaad.ghosttyfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class PtySession implements AutoCloseable {

    private static final ExecutorService IO = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().daemon().name("ghosttyfx-pty-io-", 0).factory());
    private static final ExecutorService COMMAND_DRAIN = Executors.newVirtualThreadPerTaskExecutor();

    private final BlockingQueue<Command> commands = new ArrayBlockingQueue<>(16_384);
    private final BlockingQueue<ProcessOutput> processOutputs = new ArrayBlockingQueue<>(256);
    private final Future<?> ioTask;

    PtySession(TerminalFactory terminalFactory, int initialColumns, int initialRows) {
        ioTask = IO.submit(() -> runProcess(terminalFactory, initialColumns, initialRows));
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

    private Void runProcess(TerminalFactory terminalFactory, int initialColumns, int initialRows) throws Exception {
        try {
            try (var terminal = terminalFactory.open(initialColumns, initialRows)) {
                // output task, no cleanup since we want to emit closed event when the process exits
                var outputTask = IO.submit(() -> {
                    try (var input = terminal.output()) {
                        var buffer = new byte[8 * 1024];
                        var read = input.read(buffer);
                        while (read >= 0) {
                            processOutputs.put(new Chunk(Arrays.copyOf(buffer, read)));
                            read = input.read(buffer);
                        }
                    }
                    return null;
                });
                // input task, no cleanup since we want to consume the proc commands even after the process exits
                IO.submit(() -> {
                    try (var output = terminal.input()) {
                        while (true) {
                            switch (commands.take()) {
                                case WriteInput(var bytes) ->
                                    output.write(bytes);
                                case ResizePty(var columns, var rows) ->
                                    terminal.resize(columns, rows);
                            }
                        }
                    } catch (Exception _) {
                        COMMAND_DRAIN.submit(() -> {
                            while (true) {
                                commands.take();
                            }
                        });
                    }
                });
                try {
                    outputTask.get();
                } catch (InterruptedException _) {
                    // proceed to closing the terminal
                }
            }
            processOutputs.put(new Closed(new TerminalState.Closed()));
        } catch (Exception e) {
            processOutputs.put(new Closed(new TerminalState.Failed(e)));
        }
        return null;
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

    record Closed(TerminalState state) implements ProcessOutput {
    }
}
