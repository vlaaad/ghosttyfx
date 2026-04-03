package io.github.vlaaad.ghostty;

import java.util.Optional;

/**
 * Terminal queries interface for handling terminal queries.
 */
public interface TerminalQueries {
    default String enquiryReply() { return ""; }
    default String xtversionReply() { return ""; }
    default Optional<TerminalSize> sizeReportValue() { return Optional.empty(); }
    default Optional<ColorScheme> colorSchemeValue() { return Optional.empty(); }
    default Optional<DeviceAttributes> deviceAttributesValue() { return Optional.empty(); }
}