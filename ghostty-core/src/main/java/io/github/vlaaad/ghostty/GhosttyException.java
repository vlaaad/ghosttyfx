package io.github.vlaaad.ghostty;

/// Base exception for Ghostty errors.
public sealed class GhosttyException extends RuntimeException 
    permits OutOfMemoryException, InvalidValueException, OutOfSpaceException, NoValueException {
    
    public GhosttyException(String message) {
        super(message);
    }
    
    public GhosttyException(String message, Throwable cause) {
        super(message, cause);
    }
}

/// Out of memory exception.
final class OutOfMemoryException extends GhosttyException {
    public OutOfMemoryException(String message) {
        super(message);
    }
}

/// Invalid value exception.
final class InvalidValueException extends GhosttyException {
    public InvalidValueException(String message) {
        super(message);
    }
}

/// Out of space exception.
final class OutOfSpaceException extends GhosttyException {
    public OutOfSpaceException(String message) {
        super(message);
    }
}

/// No value exception.
final class NoValueException extends GhosttyException {
    public NoValueException(String message) {
        super(message);
    }
}