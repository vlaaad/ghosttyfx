package io.github.vlaaad.ghostty;

/**
 * Terminal scrollbar information.
 * 
 * @param total total scrollable content size
 * @param offset current scroll offset
 * @param length visible viewport length
 */
public record TerminalScrollbar(
    long total,
    long offset,
    long length
) {}
