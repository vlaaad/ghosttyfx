package io.github.vlaaad.ghosttyfx.nordapp;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.pty4j.unix.UnixPtyProcess;
import io.github.vlaaad.ghosttyfx.Terminal;
import io.github.vlaaad.ghosttyfx.UnixPtyFileDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class PtyTerminal implements Terminal {
    private final PtyProcess process;

    PtyTerminal(List<String> command, Path cwd, Map<String, String> environment, int columns, int rows) throws IOException {
        process = (PtyProcess) new PtyProcessBuilder()
                .setCommand(command.toArray(String[]::new))
                .setConsole(false)
                .setRedirectErrorStream(true)
                .setDirectory(cwd.toString())
                .setEnvironment(environment)
                .setInitialColumns(columns)
                .setInitialRows(rows)
                .setUseWinConPty(true)
                .start();
    }

    @Override
    public InputStream output() {
        return process.getInputStream();
    }

    @Override
    public OutputStream input() {
        return process.getOutputStream();
    }

    @Override
    public void resize(int columns, int rows, int widthPx, int heightPx) {
        if (process instanceof UnixPtyProcess unix) {
            UnixPtyFileDescriptor.resize(unix.getPty().getMasterFD(), columns, rows, widthPx, heightPx);
        } else {
            process.setWinSize(new WinSize(columns, rows));
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

}
