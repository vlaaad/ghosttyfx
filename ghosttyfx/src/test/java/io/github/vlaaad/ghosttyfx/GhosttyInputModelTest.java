package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

final class GhosttyInputModelTest {
    @Test
    void classifiesEveryKeyCode() {
        for (var keyCode : KeyCode.values()) {
            var classification = GhosttyInputModel.classify(keyCode);
            assertTrue(classification != null);
        }
    }

    @Test
    void encodesPrintableInput() {
        var state = GhosttyInputModel.initialState();
        var pressed = GhosttyInputModel.onKeyPressed(state, GhosttyInputModel.Platform.LINUX, false, snapshot(KeyCode.A));
        assertTrue(pressed.outputs().isEmpty());
        assertEquals(1, pressed.state().deferredPresses().size());

        var typed = GhosttyInputModel.onKeyTyped(pressed.state(), "a");
        assertEquals(1, typed.outputs().size());
        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.A,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_A(),
                        (short) 0,
                        (short) 0,
                        'a',
                        "a",
                        false),
                typed.outputs().getFirst());
    }

    @Test
    void encodesShiftedPrintableInputWithConsumedShift() {
        var state = GhosttyInputModel.initialState();
        var pressed = GhosttyInputModel.onKeyPressed(
                state,
                GhosttyInputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.A, true, false, false, false));

        var typed = GhosttyInputModel.onKeyTyped(pressed.state(), "A");
        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.A,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_A(),
                        mods(ghostty_vt_h.GHOSTTY_MODS_SHIFT()),
                        mods(ghostty_vt_h.GHOSTTY_MODS_SHIFT()),
                        'a',
                        "A",
                        false),
                typed.outputs().getFirst());
    }

    @Test
    void encodesCtrlComboImmediately() {
        var transition = GhosttyInputModel.onKeyPressed(
                GhosttyInputModel.initialState(),
                GhosttyInputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.C, false, true, false, false));

        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.C,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_C(),
                        mods(ghostty_vt_h.GHOSTTY_MODS_CTRL()),
                        (short) 0,
                        'c',
                        "",
                        false),
                transition.outputs().getFirst());
    }

    @Test
    void defersAltGrText() {
        var state = GhosttyInputModel.onKeyPressed(
                        GhosttyInputModel.initialState(),
                        GhosttyInputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.ALT_GRAPH))
                .state();

        var pressed = GhosttyInputModel.onKeyPressed(
                state,
                GhosttyInputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.Q, false, true, true, false));
        assertTrue(pressed.outputs().isEmpty());

        var typed = GhosttyInputModel.onKeyTyped(pressed.state(), "@");
        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.Q,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_Q(),
                        (short) (ghostty_vt_h.GHOSTTY_MODS_CTRL() | ghostty_vt_h.GHOSTTY_MODS_ALT()),
                        (short) (ghostty_vt_h.GHOSTTY_MODS_CTRL() | ghostty_vt_h.GHOSTTY_MODS_ALT()),
                        'q',
                        "@",
                        false),
                typed.outputs().getFirst());
    }

    @Test
    void defersMacOptionTextWhenOptionIsNotAlt() {
        var pressed = GhosttyInputModel.onKeyPressed(
                GhosttyInputModel.initialState(),
                GhosttyInputModel.Platform.MACOS,
                false,
                snapshot(KeyCode.E, false, false, true, false));
        assertTrue(pressed.outputs().isEmpty());

        var typed = GhosttyInputModel.onKeyTyped(pressed.state(), "€");
        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.E,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_E(),
                        mods(ghostty_vt_h.GHOSTTY_MODS_ALT()),
                        mods(ghostty_vt_h.GHOSTTY_MODS_ALT()),
                        'e',
                        "€",
                        false),
                typed.outputs().getFirst());
    }

    @Test
    void treatsMacOptionAsAltWhenEnabled() {
        var transition = GhosttyInputModel.onKeyPressed(
                GhosttyInputModel.initialState(),
                GhosttyInputModel.Platform.MACOS,
                true,
                snapshot(KeyCode.E, false, false, true, false));

        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.E,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_E(),
                        mods(ghostty_vt_h.GHOSTTY_MODS_ALT()),
                        (short) 0,
                        'e',
                        "",
                        false),
                transition.outputs().getFirst());
    }

    @Test
    void fallsBackToRawTextForWindowsAltNumpadCommit() {
        var state = GhosttyInputModel.initialState();
        state = GhosttyInputModel.onKeyPressed(
                        state,
                        GhosttyInputModel.Platform.WINDOWS,
                        false,
                        snapshot(KeyCode.NUMPAD1, false, false, true, false))
                .state();
        state = GhosttyInputModel.onKeyPressed(
                        state,
                        GhosttyInputModel.Platform.WINDOWS,
                        false,
                        snapshot(KeyCode.NUMPAD6, false, false, true, false))
                .state();

        var typed = GhosttyInputModel.onKeyTyped(state, "A");
        assertEquals(1, typed.outputs().size());
        assertEquals(new GhosttyInputModel.RawTextOutput("A"), typed.outputs().getFirst());
        assertTrue(typed.state().deferredPresses().isEmpty());
    }

    @Test
    void fallsBackToRawTextForDeadKeyComposition() {
        var state = GhosttyInputModel.initialState();
        state = GhosttyInputModel.onKeyPressed(
                        state,
                        GhosttyInputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.DEAD_ACUTE))
                .state();
        state = GhosttyInputModel.onKeyPressed(
                        state,
                        GhosttyInputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.A))
                .state();

        var typed = GhosttyInputModel.onKeyTyped(state, "á");
        assertEquals(new GhosttyInputModel.RawTextOutput("á"), typed.outputs().getFirst());
        assertTrue(typed.state().deferredPresses().isEmpty());
    }

    @Test
    void handlesImePreeditAndCommit() {
        var pressed = GhosttyInputModel.onKeyPressed(
                GhosttyInputModel.initialState(),
                GhosttyInputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.A));
        var preedit = GhosttyInputModel.onInputMethodTextChanged(pressed.state(), "あ", 1, "");
        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.A,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_A(),
                        (short) 0,
                        (short) 0,
                        'a',
                        "",
                        true),
                preedit.outputs().getFirst());
        assertEquals(new GhosttyInputModel.Preedit("あ", 1), preedit.state().preedit());
        assertTrue(preedit.redraw());

        var committed = GhosttyInputModel.onInputMethodTextChanged(preedit.state(), "", 0, "あ");
        assertEquals(new GhosttyInputModel.RawTextOutput("あ"), committed.outputs().getFirst());
        assertEquals(GhosttyInputModel.Preedit.empty(), committed.state().preedit());
        assertTrue(committed.state().deferredPresses().isEmpty());
    }

    @Test
    void supportsLateTypedAfterRelease() {
        var pressed = GhosttyInputModel.onKeyPressed(
                GhosttyInputModel.initialState(),
                GhosttyInputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.A));
        var released = GhosttyInputModel.onKeyReleased(pressed.state(), snapshot(KeyCode.A));
        assertTrue(released.outputs().isEmpty());

        var typed = GhosttyInputModel.onKeyTyped(released.state(), "a");
        assertEquals(2, typed.outputs().size());
        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.A,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_A(),
                        (short) 0,
                        (short) 0,
                        'a',
                        "a",
                        false),
                typed.outputs().get(0));
        assertEquals(
                new GhosttyInputModel.EncodeOutput(
                        KeyCode.A,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_RELEASE(),
                        ghostty_vt_h.GHOSTTY_KEY_A(),
                        (short) 0,
                        (short) 0,
                        0,
                        "",
                        false),
                typed.outputs().get(1));
    }

    private static GhosttyInputModel.KeySnapshot snapshot(KeyCode code) {
        return snapshot(code, false, false, false, false);
    }

    private static GhosttyInputModel.KeySnapshot snapshot(
            KeyCode code,
            boolean shiftDown,
            boolean controlDown,
            boolean altDown,
            boolean metaDown) {
        return new GhosttyInputModel.KeySnapshot(code, shiftDown, controlDown, altDown, metaDown);
    }

    private static short mods(int value) {
        return (short) value;
    }
}
