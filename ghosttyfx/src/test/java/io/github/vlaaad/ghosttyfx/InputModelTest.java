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
            var classification = InputModel.classify(keyCode);
            assertTrue(classification != null);
        }
    }

    @Test
    void encodesPrintableInput() {
        var state = InputModel.initialState();
        var pressed = InputModel.onKeyPressed(state, InputModel.Platform.LINUX, false, snapshot(KeyCode.A));
        assertTrue(pressed.outputs().isEmpty());
        assertEquals(1, pressed.state().deferredPresses().size());

        var typed = InputModel.onKeyTyped(pressed.state(), InputModel.Platform.LINUX, false, "a");
        assertEquals(1, typed.outputs().size());
        assertEquals(
                new InputModel.EncodeOutput(
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
        var state = InputModel.initialState();
        var pressed = InputModel.onKeyPressed(
                state,
                InputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.A, true, false, false, false));

        var typed = InputModel.onKeyTyped(pressed.state(), InputModel.Platform.LINUX, false, "A");
        assertEquals(
                new InputModel.EncodeOutput(
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
        var transition = InputModel.onKeyPressed(
                InputModel.initialState(),
                InputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.C, false, true, false, false));

        assertEquals(
                new InputModel.EncodeOutput(
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
        var state = InputModel.onKeyPressed(
                        InputModel.initialState(),
                        InputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.ALT_GRAPH))
                .state();

        var pressed = InputModel.onKeyPressed(
                state,
                InputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.Q, false, true, true, false));
        assertTrue(pressed.outputs().isEmpty());

        var typed = InputModel.onKeyTyped(pressed.state(), InputModel.Platform.LINUX, false, "@");
        assertEquals(
                new InputModel.EncodeOutput(
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
        var pressed = InputModel.onKeyPressed(
                InputModel.initialState(),
                InputModel.Platform.MACOS,
                false,
                snapshot(KeyCode.E, false, false, true, false));
        assertTrue(pressed.outputs().isEmpty());

        var typed = InputModel.onKeyTyped(pressed.state(), InputModel.Platform.MACOS, false, "€");
        assertEquals(
                new InputModel.EncodeOutput(
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
        var transition = InputModel.onKeyPressed(
                InputModel.initialState(),
                InputModel.Platform.MACOS,
                true,
                snapshot(KeyCode.E, false, false, true, false));

        assertEquals(
                new InputModel.EncodeOutput(
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
        var state = InputModel.initialState();
        state = InputModel.onKeyPressed(
                        state,
                        InputModel.Platform.WINDOWS,
                        false,
                        snapshot(KeyCode.NUMPAD1, false, false, true, false))
                .state();
        state = InputModel.onKeyPressed(
                        state,
                        InputModel.Platform.WINDOWS,
                        false,
                        snapshot(KeyCode.NUMPAD6, false, false, true, false))
                .state();

        var typed = InputModel.onKeyTyped(state, InputModel.Platform.WINDOWS, false, "A");
        assertEquals(1, typed.outputs().size());
        assertEquals(new InputModel.RawTextOutput("A"), typed.outputs().getFirst());
        assertTrue(typed.state().deferredPresses().isEmpty());
    }

    @Test
    void fallsBackToRawTextForDeadKeyComposition() {
        var state = InputModel.initialState();
        state = InputModel.onKeyPressed(
                        state,
                        InputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.DEAD_ACUTE))
                .state();
        state = InputModel.onKeyPressed(
                        state,
                        InputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.A))
                .state();

        var typed = InputModel.onKeyTyped(state, InputModel.Platform.LINUX, false, "á");
        assertEquals(new InputModel.RawTextOutput("á"), typed.outputs().getFirst());
        assertTrue(typed.state().deferredPresses().isEmpty());
    }

    @Test
    void handlesImePreeditAndCommit() {
        var pressed = InputModel.onKeyPressed(
                InputModel.initialState(),
                InputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.A));
        var preedit = InputModel.onInputMethodTextChanged(pressed.state(), "あ", 1, "");
        assertEquals(
                new InputModel.EncodeOutput(
                        KeyCode.A,
                        ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS(),
                        ghostty_vt_h.GHOSTTY_KEY_A(),
                        (short) 0,
                        (short) 0,
                        'a',
                        "",
                        true),
                preedit.outputs().getFirst());
        assertEquals(new InputModel.Preedit("あ", 1), preedit.state().preedit());
        assertTrue(preedit.redraw());

        var committed = InputModel.onInputMethodTextChanged(preedit.state(), "", 0, "あ");
        assertEquals(new InputModel.RawTextOutput("あ"), committed.outputs().getFirst());
        assertEquals(InputModel.Preedit.empty(), committed.state().preedit());
        assertTrue(committed.state().deferredPresses().isEmpty());
    }

    @Test
    void clearsPreeditWhenInputMethodEventCommitsAndStillReportsComposition() {
        var state = InputModel.initialState();
        state = InputModel.onKeyPressed(
                        state,
                        InputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.DEAD_ACUTE))
                .state();
        state = InputModel.onInputMethodTextChanged(state, "´", 1, "´").state();
        state = InputModel.onKeyPressed(
                        state,
                        InputModel.Platform.LINUX,
                        false,
                        snapshot(KeyCode.A))
                .state();

        var transition = InputModel.onInputMethodTextChanged(state, "´", 1, "á");
        assertEquals(new InputModel.RawTextOutput("á"), transition.outputs().getLast());
        assertEquals(InputModel.Preedit.empty(), transition.state().preedit());
        assertTrue(transition.state().deferredPresses().isEmpty());
    }

    @Test
    void showsImeOnlyPreeditWithoutDeferredPresses() {
        var transition = InputModel.onInputMethodTextChanged(InputModel.initialState(), "´", 1, "");
        assertTrue(transition.outputs().isEmpty());
        assertEquals(new InputModel.Preedit("´", 1), transition.state().preedit());
    }

    @Test
    void supportsLateTypedAfterRelease() {
        var pressed = InputModel.onKeyPressed(
                InputModel.initialState(),
                InputModel.Platform.LINUX,
                false,
                snapshot(KeyCode.A));
        var released = InputModel.onKeyReleased(pressed.state(), snapshot(KeyCode.A));
        assertTrue(released.outputs().isEmpty());

        var typed = InputModel.onKeyTyped(released.state(), InputModel.Platform.LINUX, false, "a");
        assertEquals(2, typed.outputs().size());
        assertEquals(
                new InputModel.EncodeOutput(
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
                new InputModel.EncodeOutput(
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
        var typed = InputModel.onKeyTyped(
                InputModel.initialState(),
                InputModel.Platform.MACOS,
                true,
                "a");

        assertTrue(typed.outputs().isEmpty());
        assertEquals(InputModel.initialState(), typed.state());
    }

    @Test
    void tracksScrollGestureInsideInputState() {
        var state = InputModel.startScrollGesture(InputModel.initialState());
        assertTrue(state.mouseState().scrollGestureActive());

        state = InputModel.stopScrollGesture(state);
        assertFalse(state.mouseState().scrollGestureActive());
    }

    private static InputModel.KeySnapshot snapshot(KeyCode code) {
        return snapshot(code, false, false, false, false);
    }

    private static InputModel.KeySnapshot snapshot(
            KeyCode code,
            boolean shiftDown,
            boolean controlDown,
            boolean altDown,
            boolean metaDown) {
        return new InputModel.KeySnapshot(code, shiftDown, controlDown, altDown, metaDown);
    }

    private static short mods(int value) {
        return (short) value;
    }
}
