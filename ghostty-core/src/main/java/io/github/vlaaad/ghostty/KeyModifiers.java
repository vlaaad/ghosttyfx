package io.github.vlaaad.ghostty;

/**
 * Key modifiers record.
 * 
 * @param shift whether shift is pressed
 * @param ctrl whether control is pressed
 * @param alt whether alt is pressed
 * @param superKey whether super key is pressed
 * @param capsLock whether caps lock is active
 * @param numLock whether num lock is active
 * @param shiftSide which shift key is pressed
 * @param ctrlSide which control key is pressed
 * @param altSide which alt key is pressed
 * @param superSide which super key is pressed
 */
public record KeyModifiers(
    boolean shift,
    boolean ctrl,
    boolean alt,
    boolean superKey,
    boolean capsLock,
    boolean numLock,
    ModifierSide shiftSide,
    ModifierSide ctrlSide,
    ModifierSide altSide,
    ModifierSide superSide
) {}
