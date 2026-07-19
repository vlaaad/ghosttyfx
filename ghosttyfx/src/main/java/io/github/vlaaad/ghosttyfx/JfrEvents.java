package io.github.vlaaad.ghosttyfx;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

final class JfrEvents {
    private JfrEvents() {}

    private static final EventType REDRAW = EventType.getEventType(RedrawEvent.class);
    private static final EventType RENDER = EventType.getEventType(RenderEvent.class);
    private static final EventType PTY_DRAIN = EventType.getEventType(PtyDrainEvent.class);
    private static final EventType WRITE_TO_TERMINAL = EventType.getEventType(WriteToTerminalEvent.class);
    private static final EventType PTY_WRITE = EventType.getEventType(PtyWriteEvent.class);
    private static final EventType KEY_INPUT = EventType.getEventType(KeyInputEvent.class);

    static RedrawEvent redraw() {
        return REDRAW.isEnabled() ? new RedrawEvent() : null;
    }

    static RenderEvent render() {
        return RENDER.isEnabled() ? new RenderEvent() : null;
    }

    static PtyDrainEvent ptyDrain() {
        return PTY_DRAIN.isEnabled() ? new PtyDrainEvent() : null;
    }

    static WriteToTerminalEvent writeToTerminal() {
        return WRITE_TO_TERMINAL.isEnabled() ? new WriteToTerminalEvent() : null;
    }

    static PtyWriteEvent ptyWrite() {
        return PTY_WRITE.isEnabled() ? new PtyWriteEvent() : null;
    }

    static KeyInputEvent keyInput() {
        return KEY_INPUT.isEnabled() ? new KeyInputEvent() : null;
    }

    @Name("ghosttyfx.Redraw")
    @Label("Terminal redraw")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class RedrawEvent extends Event {
        public int width;
        public int height;
    }

    @Name("ghosttyfx.Render")
    @Label("Terminal render")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class RenderEvent extends Event {
        public int columns;
        public int rows;
        public boolean searchVisible;
        public boolean focused;
    }

    @Name("ghosttyfx.PtyDrain")
    @Label("PTY output drain")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class PtyDrainEvent extends Event {
        @DataAmount(DataAmount.BYTES)
        public long bytes;
        public int chunks;
    }

    @Name("ghosttyfx.WriteToTerminal")
    @Label("VT write")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class WriteToTerminalEvent extends Event {
        @DataAmount(DataAmount.BYTES)
        public long bytes;
    }

    @Name("ghosttyfx.PtyWrite")
    @Label("PTY input write")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class PtyWriteEvent extends Event {
        @DataAmount(DataAmount.BYTES)
        public long bytes;
    }

    @Name("ghosttyfx.KeyInput")
    @Label("Key input")
    @Category("GhosttyFX")
    @StackTrace(false)
    static final class KeyInputEvent extends Event {
        public String action;
    }
}
