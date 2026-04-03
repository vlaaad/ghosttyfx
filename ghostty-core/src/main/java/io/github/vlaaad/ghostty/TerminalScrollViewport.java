package io.github.vlaaad.ghostty;

/**
 * Terminal scroll viewport - sealed interface for different scroll behaviors.
 */
public sealed interface TerminalScrollViewport permits 
    ScrollViewportTop, 
    ScrollViewportBottom, 
    ScrollViewportDelta {}

/**
 * Scroll to top.
 */
record ScrollViewportTop() implements TerminalScrollViewport {}

/**
 * Scroll to bottom.
 */
record ScrollViewportBottom() implements TerminalScrollViewport {}

/**
 * Scroll by delta.
 */
record ScrollViewportDelta(long delta) implements TerminalScrollViewport {}
