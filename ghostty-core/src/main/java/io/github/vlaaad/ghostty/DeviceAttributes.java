package io.github.vlaaad.ghostty;

/// Device attributes record.
/// This is a simplified placeholder - full implementation would include all device attributes.
///
/// @param primary primary device attributes
/// @param secondary secondary device attributes
/// @param tertiary tertiary device attributes
public record DeviceAttributes(
    DeviceAttributesPrimary primary,
    DeviceAttributesSecondary secondary,
    DeviceAttributesTertiary tertiary
) {}

/// Primary device attributes.
record DeviceAttributesPrimary(
    String version,
    int pngLevel,
    boolean truecolor,
    boolean sixel,
    boolean iterm,
    boolean kittyGraphics,
    boolean tmuxControlMode,
    boolean titleStack,
    boolean colorStack,
    boolean graphemeCluster,
    boolean syncOutput,
    boolean dynamicColors,
    boolean colorPalette,
    boolean ansiColor,
    boolean columnRegions,
    boolean ansiTextLocator,
    boolean hexdumpLocator,
    boolean rectangleLocator,
    boolean horizontalPositioning,
    boolean verticalPositioning,
    boolean unitTerminal,
    boolean geometricShapes,
    boolean nationalReplacementCharacterSets,
    boolean technicalCharacters,
    boolean userDefinedCharacters,
    boolean activePositionReporting,
    boolean printer,
    boolean userWindows,
    boolean nationalReplacementCharacterSets2,
    boolean technicalCharacters2,
    boolean g0G1CharacterSets,
    boolean g2G3CharacterSets,
    boolean additionalCharacterSets,
    boolean macroDefinitions,
    boolean terminalStateInterrogation,
    boolean multipleSessionManagement,
    boolean defaultFontSelection
) {}

/// Secondary device attributes.
record DeviceAttributesSecondary(
    boolean utf8,
    boolean sixelGraphics,
    boolean softTerminalReset,
    boolean selectiveErase,
    boolean urxvtMouse,
    boolean sgrMouse,
    boolean alternateScroll,
    boolean proportionalSpacing
) {}

/// Tertiary device attributes.
record DeviceAttributesTertiary(
    boolean transparency,
    boolean italics,
    boolean blinkingText,
    boolean coloredUnderline,
    boolean ideogramSupport,
    boolean ideogramUnderline,
    boolean ideogramDoubleUnderline,
    boolean ideogramOverline
) {}
