package io.github.vlaaad.ghostty;

/// Immutable configuration used when opening a terminal session.
///
/// @param columns initial terminal column count
/// @param rows initial terminal row count
/// @param maxScrollback maximum number of scrollback rows the session should retain
public record TerminalConfig(
    int columns,
    int rows,
    long maxScrollback
) {}
