package io.github.vlaaad.ghostty;

/**
 * Main entry point for Ghostty terminal emulator.
 * This class provides factory methods for creating terminal sessions and codecs.
 */
public final class Ghostty {
    
    private Ghostty() {
        // Private constructor to prevent instantiation
    }
    
    /**
     * Creates a new terminal session.
     * 
     * @param config terminal configuration
     * @param ptyWriter PTY writer for terminal output
     * @param queries terminal queries handler
     * @param events terminal events listener
     * @return new terminal session
     */
    public static TerminalSession open(
        TerminalConfig config,
        PtyWriter ptyWriter,
        TerminalQueries queries,
        TerminalEvents events
    ) {
        // Platform-specific implementation will be loaded here
        throw new UnsupportedOperationException("Not yet implemented");
    }
    
    /**
     * Creates a key codec for encoding key events.
     * 
     * @param config key codec configuration
     * @return key codec instance
     */
    public static KeyCodec keyCodec(KeyCodecConfig config) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
    
    /**
     * Creates a mouse codec for encoding mouse events.
     * 
     * @param config mouse codec configuration
     * @return mouse codec instance
     */
    public static MouseCodec mouseCodec(MouseCodecConfig config) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
    
    /**
     * Creates a paste codec.
     * 
     * @return paste codec instance
     */
    public static PasteCodec pasteCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
    
    /**
     * Creates a focus codec.
     * 
     * @return focus codec instance
     */
    public static FocusCodec focusCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
    
    /**
     * Creates a size report codec.
     * 
     * @return size report codec instance
     */
    public static SizeReportCodec sizeReportCodec() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
    
    /**
     * Gets build information.
     * 
     * @return build information
     */
    public static BuildInfo buildInfo() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
    
    /**
     * Gets type schema.
     * 
     * @return type schema
     */
    public static TypeSchema typeSchema() {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}