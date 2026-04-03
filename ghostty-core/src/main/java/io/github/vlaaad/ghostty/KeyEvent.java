package io.github.vlaaad.ghostty;

/// Key event record.
///
/// @param action key action (press, release, repeat)
/// @param key key that was pressed/released
/// @param modifiers key modifiers
/// @param consumedModifiers consumed modifiers
/// @param composing whether key is part of composing sequence
/// @param utf8 UTF-8 representation
/// @param unshiftedCodePoint unshifted code point
public record KeyEvent(
    KeyAction action,
    Key key,
    KeyModifiers modifiers,
    KeyModifiers consumedModifiers,
    boolean composing,
    String utf8,
    int unshiftedCodePoint
) {}