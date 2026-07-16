package io.github.vlaaad.ghosttyfx;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class PtySession implements AutoCloseable {

    private static final ExecutorService IO = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().daemon().name("ghosttyfx-pty-io-", 0).factory());
    private static final ExecutorService COMMAND_DRAIN = Executors.newVirtualThreadPerTaskExecutor();

    private final BlockingQueue<Command> commands = new ArrayBlockingQueue<>(16_384);
    // Process exit state, set by the IO thread when the terminal process exits.
    // VT output is now fed directly to the terminal core on the IO thread.
    private volatile TerminalState closedState;
    private final Future<?> ioTask;

    PtySession(TerminalFactory terminalFactory, int initialColumns, int initialRows, Consumer<byte[]> vtWriter) {
        ioTask = IO.submit(() -> runProcess(terminalFactory, initialColumns, initialRows, vtWriter));
    }

    @Override
    public void close() {
        ioTask.cancel(true);
    }

    void putCommand(Command command) throws InterruptedException {
        commands.put(command);
    }

    /**
     * Returns the terminal closed state if the process has exited, null otherwise.
     * Called from the FX-thread AnimationTimer.
     */
    TerminalState pollClosed() {
        return closedState;
    }

    private Void runProcess(TerminalFactory terminalFactory, int initialColumns, int initialRows, Consumer<byte[]> vtWriter) throws Exception {
        try {
            try (var terminal = terminalFactory.open(initialColumns, initialRows)) {
                // output task — feeds PTY output directly to the Ghostty terminal core on the IO thread
                var outputTask = IO.submit(() -> {
                    try (var input = terminal.output()) {
                        var buffer = new byte[8 * 1024];
                        var read = input.read(buffer);
                        while (read >= 0) {
                            vtWriter.accept(Arrays.copyOf(buffer, read));
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
            closedState = new TerminalState.Closed();
        } catch (Exception e) {
            closedState = new TerminalState.Failed(e);
        }
        return null;
    }

    sealed interface Command permits WriteInput, ResizePty {
    }

    record WriteInput(byte[] bytes) implements Command {
    }

    record ResizePty(int columns, int rows) implements Command {
    }
}
