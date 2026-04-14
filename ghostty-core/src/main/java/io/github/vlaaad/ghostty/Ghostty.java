package io.github.vlaaad.ghostty;

import io.github.vlaaad.ghostty.impl.NativeRuntime;

import java.util.Objects;

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
        return NativeRuntime.instance().nativeTerminal.open(
            Objects.requireNonNull(config, "config"),
            Objects.requireNonNull(ptyWriter, "ptyWriter"),
            Objects.requireNonNull(queries, "queries"),
            Objects.requireNonNull(events, "events")
        );
    }
    
    /// Creates a standalone key codec for translating host key events into VT sequences.
    ///
    /// @param config key codec configuration
    /// @return key codec instance
    public static KeyCodec keyCodec(KeyCodecConfig config) {
        return NativeRuntime.instance().nativeKeyCodec.keyCodec(Objects.requireNonNull(config, "config"));
    }
    
    /// Creates a standalone mouse codec for translating host mouse events into VT sequences.
    ///
    /// @param config mouse codec configuration
    /// @return mouse codec instance
    public static MouseCodec mouseCodec(MouseCodecConfig config) {
        return NativeRuntime.instance().nativeMouseCodec.mouseCodec(Objects.requireNonNull(config, "config"));
    }
    
    /// Creates a standalone paste codec.
    ///
    /// @return paste codec instance
    public static PasteCodec pasteCodec() {
        return NativeRuntime.instance().nativePasteCodec;
    }
    
    /// Creates a standalone focus codec.
    ///
    /// @return focus codec instance
    public static FocusCodec focusCodec() {
        return NativeRuntime.instance().nativeFocusCodec;
    }
    
    /// Creates a standalone size-report codec.
    ///
    /// @return size report codec instance
    public static SizeReportCodec sizeReportCodec() {
        return NativeRuntime.instance().nativeSizeReportCodec;
    }

    /// Creates a standalone mode-report codec.
    ///
    /// @return mode report codec instance
    public static ModeReportCodec modeReportCodec() {
        return NativeRuntime.instance().nativeModeReportCodec;
    }
    
    /// Gets build information.
    ///
    /// @return build information
    public static BuildInfo buildInfo() {
        return NativeRuntime.instance().metadata.buildInfo();
    }
    
    /// Gets type schema.
    ///
    /// @return type schema
    public static TypeSchema typeSchema() {
        return NativeRuntime.instance().metadata.typeSchema();
    }
}
