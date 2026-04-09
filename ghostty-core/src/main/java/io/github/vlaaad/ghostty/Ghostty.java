package io.github.vlaaad.ghostty;

import io.github.vlaaad.ghostty.impl.Providers;

/// Public bootstrap entry point for the Ghostty core API.
///
/// This facade is responsible for locating the platform-specific native adapter internally.
/// Callers interact only with Java records, enums, and interfaces exposed from the core module;
/// native handles, memory segments, and generated binding types stay hidden behind this class.
///
/// The API surface is intentionally split into two families:
/// terminal sessions, which own mutable terminal state, and standalone codecs, which encode host
/// input/output sequences without depending on any UI toolkit.
public final class Ghostty {
    
    private Ghostty() {
        // Private constructor to prevent instantiation
    }
    
    /// Creates a new terminal session.
    ///
    /// The returned session is thread-safe. Implementations confine mutable native state to an
    /// internal single-threaded actor and publish only detached immutable snapshots to callers.
    ///
    /// @param config terminal configuration
    /// @param ptyWriter PTY writer for terminal output
    /// @param queries synchronous query handler invoked when the terminal needs an immediate host reply
    /// @param events asynchronous notification listener for terminal-originated events
    /// @return new terminal session
    public static TerminalSession open(
        TerminalConfig config,
        PtyWriter ptyWriter,
        TerminalQueries queries,
        TerminalEvents events
    ) {
        return Providers.provider().open(config, ptyWriter, queries, events);
    }
    
    /// Creates a standalone key codec for translating host key events into VT sequences.
    ///
    /// @param config key codec configuration
    /// @return key codec instance
    public static KeyCodec keyCodec(KeyCodecConfig config) {
        return Providers.provider().keyCodec(config);
    }
    
    /// Creates a standalone mouse codec for translating host mouse events into VT sequences.
    ///
    /// @param config mouse codec configuration
    /// @return mouse codec instance
    public static MouseCodec mouseCodec(MouseCodecConfig config) {
        return Providers.provider().mouseCodec(config);
    }
    
    /// Creates a standalone paste codec.
    ///
    /// @return paste codec instance
    public static PasteCodec pasteCodec() {
        return Providers.provider().pasteCodec();
    }
    
    /// Creates a standalone focus codec.
    ///
    /// @return focus codec instance
    public static FocusCodec focusCodec() {
        return Providers.provider().focusCodec();
    }
    
    /// Creates a standalone size-report codec.
    ///
    /// @return size report codec instance
    public static SizeReportCodec sizeReportCodec() {
        return Providers.provider().sizeReportCodec();
    }
    
    /// Gets build information.
    ///
    /// @return build information
    public static BuildInfo buildInfo() {
        return Providers.provider().buildInfo();
    }
    
    /// Gets type schema.
    ///
    /// @return type schema
    public static TypeSchema typeSchema() {
        return Providers.provider().typeSchema();
    }
}
