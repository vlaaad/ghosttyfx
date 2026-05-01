package io.github.vlaaad.ghosttyfx;

@FunctionalInterface
public interface TerminalFactory {

    Terminal open(int columns, int rows) throws Exception;
}
