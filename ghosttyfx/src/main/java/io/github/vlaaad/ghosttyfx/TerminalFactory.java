package io.github.vlaaad.ghosttyfx;

import java.io.IOException;

@FunctionalInterface
public interface TerminalFactory {

    Terminal open(int columns, int rows) throws IOException;
}
