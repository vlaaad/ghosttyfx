package io.github.vlaaad.ghostty.impl;

final class ResultException extends RuntimeException {
    public final int result;

    ResultException(String message, int result) {
        super(message);
        this.result = result;
    }
}
