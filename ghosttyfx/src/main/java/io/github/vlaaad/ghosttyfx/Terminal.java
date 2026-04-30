package io.github.vlaaad.ghosttyfx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface Terminal extends AutoCloseable {

    InputStream output() throws IOException;

    OutputStream input() throws IOException;

    void resize(int columns, int rows) throws IOException;

    @Override
    void close() throws IOException;
}
