package io.github.vlaaad.ghosttyfx;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// JFR events for GhosttyFX performance tracing.
///
/// Usage:
/// ```java
/// var event = new JfrEvents.RedrawEvent();
/// event.begin();
/// // ... do work ...
/// event.commit();
/// ```
final class JfrEvents {

    private JfrEvents() {}

    @Name("ghosttyfx.Redraw")
    @Label("Redraw")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class RedrawEvent extends Event {
    }

    @Name("ghosttyfx.Render")
    @Label("Render")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class RenderEvent extends Event {
        @Label("Rows") int rows;
        @Label("Cols") int cols;
    }

    @Name("ghosttyfx.PtyOutput")
    @Label("PTY Output")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class PtyOutputEvent extends Event {
        @Label("Bytes") int bytes;
    }

    @Name("ghosttyfx.WriteToTerminal")
    @Label("Write To Terminal")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class WriteToTerminalEvent extends Event {
        @Label("Bytes") int bytes;
    }

    @Name("ghosttyfx.KeyInput")
    @Label("Key Input")
    @Category("GhosttyFX")
    static final class KeyInputEvent extends Event {
        @Label("Action") String action;
    }

    @Name("ghosttyfx.PtyWrite")
    @Label("PTY Write")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class PtyWriteEvent extends Event {
        @Label("Bytes") int bytes;
    }

}
