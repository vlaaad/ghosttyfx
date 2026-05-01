package io.github.vlaaad.ghosttyfx;

import java.io.InputStream;
import java.io.OutputStream;

public interface Terminal extends AutoCloseable {

    InputStream output() throws Exception;

    OutputStream input() throws Exception;

    void resize(int columns, int rows) throws Exception;

    @Override
    void close() throws Exception;
}
