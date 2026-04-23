package io.github.vlaaad.ghosttyfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import javafx.scene.input.KeyCode;

final class InputModelTest {
    @Test
    void classifiesEveryKeyCode() {
        for (var keyCode : KeyCode.values()) {
            var classification = KeyInput.classify(keyCode);
            assertTrue(classification != null);
        }
    }

    @Test
    void encodesPrintableInput() {
        var state = KeyInput.initialState();
        var pressed = KeyInput.onKeyPressed(state, KeyInput.Platform.LINUX, false, snapshot(KeyCode.A));
        assertTrue(pressed.outputs().isEmpty());
        assertEquals(1, pressed.state().deferredPresses().size());

        var typed = KeyInput.onKeyTyped(pressed.state(), KeyInput.Platform.LINUX, false, "a");
        assertEquals(1, typed.outputs().size());
        assertEquals(
                new KeyInput.EncodeOutput(
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
        var state = KeyInput.initialState();
        var pressed = KeyInput.onKeyPressed(
                state,
                KeyInput.Platform.LINUX,
                false,
                snapshot(KeyCode.A, true, false, false, false));

        var typed = KeyInput.onKeyTyped(pressed.state(), KeyInput.Platform.LINUX, false, "A");
        assertEquals(
                new KeyInput.EncodeOutput(
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
        var transition = KeyInput.onKeyPressed(
                KeyInput.initialState(),
                KeyInput.Platform.LINUX,
                false,
                snapshot(KeyCode.C, false, true, false, false));

        assertEquals(
                new KeyInput.EncodeOutput(
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
        var state = KeyInput.onKeyPressed(
                        KeyInput.initialState(),
                        KeyInput.Platform.LINUX,
                        false,
                        snapshot(KeyCode.ALT_GRAPH))
                .state();

        var pressed = KeyInput.onKeyPressed(
                state,
                KeyInput.Platform.LINUX,
                false,
                snapshot(KeyCode.Q, false, true, true, false));
        assertTrue(pressed.outputs().isEmpty());

        var typed = KeyInput.onKeyTyped(pressed.state(), KeyInput.Platform.LINUX, false, "@");
        assertEquals(
                new KeyInput.EncodeOutput(
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
        var pressed = KeyInput.onKeyPressed(
                KeyInput.initialState(),
                KeyInput.Platform.MACOS,
                false,
                snapshot(KeyCode.E, false, false, true, false));
        assertTrue(pressed.outputs().isEmpty());

        var typed = KeyInput.onKeyTyped(pressed.state(), KeyInput.Platform.MACOS, false, "€");
        assertEquals(
                new KeyInput.EncodeOutput(
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
        var transition = KeyInput.onKeyPressed(
                KeyInput.initialState(),
                KeyInput.Platform.MACOS,
                true,
                snapshot(KeyCode.E, false, false, true, false));

        assertEquals(
                new KeyInput.EncodeOutput(
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
        var state = KeyInput.initialState();
        state = KeyInput.onKeyPressed(
                        state,
                        KeyInput.Platform.WINDOWS,
                        false,
                        snapshot(KeyCode.NUMPAD1, false, false, true, false))
                .state();
        state = KeyInput.onKeyPressed(
                        state,
                        KeyInput.Platform.WINDOWS,
                        false,
                        snapshot(KeyCode.NUMPAD6, false, false, true, false))
                .state();

        var typed = KeyInput.onKeyTyped(state, KeyInput.Platform.WINDOWS, false, "A");
        assertEquals(1, typed.outputs().size());
        assertEquals(new KeyInput.RawTextOutput("A"), typed.outputs().getFirst());
        assertTrue(typed.state().deferredPresses().isEmpty());
    }

    @Test
    void fallsBackToRawTextForDeadKeyComposition() {
        var state = KeyInput.initialState();
        state = KeyInput.onKeyPressed(
                        state,
                        KeyInput.Platform.LINUX,
                        false,
                        snapshot(KeyCode.DEAD_ACUTE))
                .state();
        state = KeyInput.onKeyPressed(
                        state,
                        KeyInput.Platform.LINUX,
                        false,
                        snapshot(KeyCode.A))
                .state();

        var typed = KeyInput.onKeyTyped(state, KeyInput.Platform.LINUX, false, "á");
        assertEquals(new KeyInput.RawTextOutput("á"), typed.outputs().getFirst());
        assertTrue(typed.state().deferredPresses().isEmpty());
    }

    @Test
    void handlesImePreeditAndCommit() {
        var pressed = KeyInput.onKeyPressed(
                KeyInput.initialState(),
                KeyInput.Platform.LINUX,
                false,
                snapshot(KeyCode.A));
        var preedit = KeyInput.onInputMethodTextChanged(pressed.state(), "あ", 1, "");
        assertEquals(
                new KeyInput.EncodeOutput(
                        KeyCode.A,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_A(),
                        (short) 0,
                        (short) 0,
                        'a',
                        "",
                        true),
                preedit.outputs().getFirst());
        assertEquals(new KeyInput.Preedit("あ", 1), preedit.state().preedit());
        assertTrue(preedit.redraw());

        var committed = KeyInput.onInputMethodTextChanged(preedit.state(), "", 0, "あ");
        assertEquals(new KeyInput.RawTextOutput("あ"), committed.outputs().getFirst());
        assertEquals(KeyInput.Preedit.empty(), committed.state().preedit());
        assertTrue(committed.state().deferredPresses().isEmpty());
    }

    @Test
    void clearsPreeditWhenInputMethodEventCommitsAndStillReportsComposition() {
        var state = KeyInput.initialState();
        state = KeyInput.onKeyPressed(
                        state,
                        KeyInput.Platform.LINUX,
                        false,
                        snapshot(KeyCode.DEAD_ACUTE))
                .state();
        state = KeyInput.onInputMethodTextChanged(state, "´", 1, "´").state();
        state = KeyInput.onKeyPressed(
                        state,
                        KeyInput.Platform.LINUX,
                        false,
                        snapshot(KeyCode.A))
                .state();

        var transition = KeyInput.onInputMethodTextChanged(state, "´", 1, "á");
        assertEquals(new KeyInput.RawTextOutput("á"), transition.outputs().getLast());
        assertEquals(KeyInput.Preedit.empty(), transition.state().preedit());
        assertTrue(transition.state().deferredPresses().isEmpty());
    }

    @Test
    void showsImeOnlyPreeditWithoutDeferredPresses() {
        var transition = KeyInput.onInputMethodTextChanged(KeyInput.initialState(), "´", 1, "");
        assertTrue(transition.outputs().isEmpty());
        assertEquals(new KeyInput.Preedit("´", 1), transition.state().preedit());
    }

    @Test
    void supportsLateTypedAfterRelease() {
        var pressed = KeyInput.onKeyPressed(
                KeyInput.initialState(),
                KeyInput.Platform.LINUX,
                false,
                snapshot(KeyCode.A));
        var released = KeyInput.onKeyReleased(pressed.state(), snapshot(KeyCode.A));
        assertTrue(released.outputs().isEmpty());

        var typed = KeyInput.onKeyTyped(released.state(), KeyInput.Platform.LINUX, false, "a");
        assertEquals(2, typed.outputs().size());
        assertEquals(
                new KeyInput.EncodeOutput(
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
                new KeyInput.EncodeOutput(
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

    @Test
    void ignoresMacCommandTypedText() {
        var typed = KeyInput.onKeyTyped(
                KeyInput.initialState(),
                KeyInput.Platform.MACOS,
                true,
                "a");

        assertTrue(typed.outputs().isEmpty());
        assertEquals(KeyInput.initialState(), typed.state());
    }

    @Test
    void tracksScrollGestureInsideInputState() {
        var state = MouseInput.startScrollGesture(MouseInput.initialState());
        assertTrue(state.scrollGestureActive());

        state = MouseInput.stopScrollGesture(state);
        assertFalse(state.scrollGestureActive());
    }

    private static KeyInput.KeySnapshot snapshot(KeyCode code) {
        return snapshot(code, false, false, false, false);
    }

    private static KeyInput.KeySnapshot snapshot(
            KeyCode code,
            boolean shiftDown,
            boolean controlDown,
            boolean altDown,
            boolean metaDown) {
        return new KeyInput.KeySnapshot(code, shiftDown, controlDown, altDown, metaDown);
    }

    private static short mods(int value) {
        return (short) value;
    }
}
