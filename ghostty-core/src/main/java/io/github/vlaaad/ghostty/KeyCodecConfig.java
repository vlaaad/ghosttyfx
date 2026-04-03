package io.github.vlaaad.ghostty;

/**
 * Key codec configuration record.
 * 
 * @param cursorKeyApplication cursor key application mode
 * @param keypadKeyApplication keypad key application mode
 * @param ignoreKeypadWithNumLock ignore keypad with num lock
 * @param altEscPrefix alt sends ESC prefix
 * @param modifyOtherKeysState2 modify other keys state 2
 * @param kittyFlags kitty keyboard flags
 * @param optionAsAlt option as alt setting
 */
public record KeyCodecConfig(
    boolean cursorKeyApplication,
    boolean keypadKeyApplication,
    boolean ignoreKeypadWithNumLock,
    boolean altEscPrefix,
    boolean modifyOtherKeysState2,
    KittyKeyboardFlags kittyFlags,
    OptionAsAlt optionAsAlt
) {}
