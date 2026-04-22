package io.github.vlaaad.ghosttyfx;

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

final class GhosttyInputModel {
    private GhosttyInputModel() {}

    static InputState initialState() {
        return new InputState(Set.of(), Set.of(), List.of(), false, Preedit.empty(), Selection.empty(), MouseState.initial());
    }

    static InputState select(InputState state, Selection selection) {
        return state.selection().equals(selection) ? state : state.withSelection(selection);
    }

    static InputState clearSelection(InputState state) {
        return state.selection().isEmpty() ? state : state.withSelection(Selection.empty());
    }

    static Transition onKeyPressed(InputState state, Platform platform, boolean macOptionAsAlt, KeySnapshot event) {
        var classification = classify(event.code());
        var pressed = copyPressedKeys(state.pressedKeys());
        var repeat = !pressed.add(event.code());
        var nextState = state.withPressedKeys(pressed)
                .withAltGraphDown(state.altGraphDown() || event.code() == KeyCode.ALT_GRAPH);

        if (event.code() == KeyCode.ALT_GRAPH) {
            return new Transition(nextState, List.of(), false);
        }

        var action = repeat ? ghostty_vt_h.GHOSTTY_KEY_ACTION_REPEAT() : ghostty_vt_h.GHOSTTY_KEY_ACTION_PRESS();
        var mods = modifiers(event);
        if (classification.modifierOnly()) {
            return classification.bucket() != Bucket.PHYSICAL
                    ? new Transition(nextState, List.of(), false)
                    : emitImmediate(nextState, event.code(), action, classification.ghosttyKey(), mods, (short) 0, 0, "", false, false);
        }

        if (classification.textKind() == TextKind.NONE) {
            return classification.bucket() != Bucket.PHYSICAL
                    ? new Transition(nextState, List.of(), false)
                    : emitImmediate(nextState, event.code(), action, classification.ghosttyKey(), mods, (short) 0, 0, "", false, true);
        }

        var altGrText = state.altGraphDown();
        var macOptionText = platform == Platform.MACOS
                && !macOptionAsAlt
                && event.altDown()
                && !event.controlDown()
                && !event.metaDown();
        var windowsAltNumpad = platform == Platform.WINDOWS
                && event.altDown()
                && !event.controlDown()
                && !event.metaDown()
                && classification.keypad();
        var consumedMods = consumedModifiers(mods, altGrText, macOptionText);
        if (classification.bucket() == Bucket.PHYSICAL
                && shouldEncodeImmediately(mods, altGrText, macOptionText, windowsAltNumpad)) {
            return emitImmediate(
                    nextState.withSelection(Selection.empty()),
                    event.code(),
                    action,
                    classification.ghosttyKey(),
                    mods,
                    (short) 0,
                    classification.unshiftedCodepoint(),
                    "",
                    false,
                    true);
        }

        var pending = new PendingPress(
                event.code(),
                action,
                classification.bucket() == Bucket.PHYSICAL && !windowsAltNumpad
                        ? classification.ghosttyKey()
                        : ghostty_vt_h.GHOSTTY_KEY_UNIDENTIFIED(),
                mods,
                consumedMods,
                classification.bucket() == Bucket.PHYSICAL && !windowsAltNumpad
                        ? classification.unshiftedCodepoint()
                        : 0,
                false,
                false);
        var deferred = new ArrayList<>(nextState.deferredPresses());
        deferred.add(pending);
        return new Transition(
                nextState.withDeferredPresses(List.copyOf(deferred)).withSelection(Selection.empty()),
                List.of(),
                false);
    }

    static Transition onKeyReleased(InputState state, KeySnapshot event) {
        var pressed = copyPressedKeys(state.pressedKeys());
        pressed.remove(event.code());
        var deferred = markDeferredReleased(state.deferredPresses(), event.code());
        var nextState = state.withPressedKeys(pressed)
                .withDeferredPresses(deferred)
                .withAltGraphDown(event.code() == KeyCode.ALT_GRAPH ? false : state.altGraphDown());
        if (event.code() == KeyCode.ALT_GRAPH) {
            return new Transition(nextState, List.of(), false);
        }

        if (!state.emittedKeys().contains(event.code())) {
            return new Transition(nextState, List.of(), false);
        }

        var classification = classify(event.code());
        if (classification.bucket() != Bucket.PHYSICAL) {
            return new Transition(nextState.withEmittedKeys(removeEmitted(state.emittedKeys(), event.code())), List.of(), false);
        }

        var outputs = List.<Output>of(new EncodeOutput(
                event.code(),
                ghostty_vt_h.GHOSTTY_KEY_ACTION_RELEASE(),
                classification.ghosttyKey(),
                modifiers(event),
                (short) 0,
                0,
                "",
                false));
        return new Transition(
                nextState.withEmittedKeys(removeEmitted(state.emittedKeys(), event.code())),
                outputs,
                false);
    }

    static Transition onKeyTyped(InputState state, String text) {
        if (shouldIgnoreTypedText(text)) {
            return new Transition(state, List.of(), false);
        }

        if (!state.deferredPresses().isEmpty()) {
            var pending = state.deferredPresses().getFirst();
            if (!pending.composingEmitted() && pending.ghosttyKey() != ghostty_vt_h.GHOSTTY_KEY_UNIDENTIFIED()) {
                var outputs = new ArrayList<Output>(2);
                outputs.add(new EncodeOutput(
                        pending.code(),
                        pending.action(),
                        pending.ghosttyKey(),
                        pending.mods(),
                        pending.consumedMods(),
                        pending.unshiftedCodepoint(),
                        text,
                        false));
                var nextState = state.withDeferredPresses(dropFirstDeferred(state.deferredPresses()));
                if (pending.released()) {
                    outputs.add(new EncodeOutput(
                            pending.code(),
                            ghostty_vt_h.GHOSTTY_KEY_ACTION_RELEASE(),
                            pending.ghosttyKey(),
                            pending.mods(),
                            (short) 0,
                            0,
                            "",
                            false));
                    return new Transition(nextState, List.copyOf(outputs), false);
                }

                return new Transition(
                        nextState.withEmittedKeys(addEmitted(state.emittedKeys(), pending.code())),
                        List.copyOf(outputs),
                        false);
            }
        }

        return new Transition(
                state.withDeferredPresses(List.of()),
                List.of(new RawTextOutput(text)),
                false);
    }

    static Transition onInputMethodTextChanged(InputState state, String composedText, int caretPosition, String committedText) {
        var outputs = new ArrayList<Output>(2);
        var nextState = state;
        if (!composedText.isEmpty() && !state.deferredPresses().isEmpty()) {
            var pending = state.deferredPresses().getFirst();
            if (!pending.released()
                    && !pending.composingEmitted()
                    && pending.ghosttyKey() != ghostty_vt_h.GHOSTTY_KEY_UNIDENTIFIED()) {
                outputs.add(new EncodeOutput(
                        pending.code(),
                        pending.action(),
                        pending.ghosttyKey(),
                        pending.mods(),
                        pending.consumedMods(),
                        pending.unshiftedCodepoint(),
                        "",
                        true));
                nextState = nextState.withDeferredPresses(List.of(pending.withComposingEmitted(true)))
                        .withEmittedKeys(addEmitted(nextState.emittedKeys(), pending.code()));
            }
        }

        if (!committedText.isEmpty()) {
            outputs.add(new RawTextOutput(committedText));
            nextState = nextState.withDeferredPresses(List.of()).withSelection(Selection.empty());
        }

        var preedit = composedText.isEmpty() ? Preedit.empty() : new Preedit(composedText, caretPosition);
        var redraw = !nextState.preedit().equals(preedit);
        return new Transition(nextState.withPreedit(preedit), List.copyOf(outputs), redraw);
    }

    static InputState onFocusLost(InputState state) {
        return new InputState(Set.of(), Set.of(), List.of(), false, Preedit.empty(), state.selection(), MouseState.initial());
    }

    static InputState startScrollGesture(InputState state) {
        return state.mouseState().scrollGestureActive()
                ? state
                : state.withMouseState(state.mouseState().withScrollGestureActive(true));
    }

    static InputState stopScrollGesture(InputState state) {
        return state.mouseState().scrollGestureActive()
                ? state.withMouseState(state.mouseState().withScrollGestureActive(false))
                : state;
    }

    static InputState startScrollbarDrag(InputState state, double thumbGrabRatio) {
        var nextMouseState = state.mouseState()
                .withScrollbarThumbGrabRatio(Math.clamp(thumbGrabRatio, 0.0, 1.0))
                .withScrollbarDragging(true);
        return state.mouseState().equals(nextMouseState)
                ? state
                : state.withMouseState(nextMouseState);
    }

    static InputState stopScrollbarDrag(InputState state) {
        return state.mouseState().scrollbarDragging()
                ? state.withMouseState(state.mouseState()
                        .withScrollbarDragging(false)
                        .withScrollbarThumbGrabRatio(0))
                : state;
    }

    static ScrollUpdate accumulateDiscreteScroll(InputState state, double deltaTicks) {
        if (deltaTicks == 0 || !Double.isFinite(deltaTicks)) {
            return new ScrollUpdate(state, 0);
        }

        var totalTicks = state.mouseState().discreteScrollRemainder() + deltaTicks;
        var wholeTicks = (int) totalTicks;
        var remainderTicks = totalTicks - wholeTicks;
        return new ScrollUpdate(
                state.withMouseState(state.mouseState().withDiscreteScrollRemainder(remainderTicks)),
                wholeTicks);
    }

    static ScrollUpdate accumulateSmoothScroll(InputState state, double deltaRows) {
        if (deltaRows == 0 || !Double.isFinite(deltaRows)) {
            return new ScrollUpdate(state, 0);
        }

        var totalRows = state.mouseState().smoothScrollRemainderRows() + deltaRows;
        var wholeRows = (int) totalRows;
        var remainderRows = totalRows - wholeRows;
        return new ScrollUpdate(
                state.withMouseState(state.mouseState().withSmoothScrollRemainderRows(remainderRows)),
                wholeRows);
    }

    static InputState acknowledgeEncode(InputState state, KeyCode code, int action, boolean producedBytes) {
        if (action == ghostty_vt_h.GHOSTTY_KEY_ACTION_RELEASE() || producedBytes) {
            return state;
        }
        return state.withEmittedKeys(removeEmitted(state.emittedKeys(), code));
    }

    static KeyClassification classify(KeyCode code) {
        return switch (code) {
            case A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z ->
                    directText(ghostty_vt_h.GHOSTTY_KEY_A() + (code.ordinal() - KeyCode.A.ordinal()), 'a' + (code.ordinal() - KeyCode.A.ordinal()), false);
            case DIGIT0, DIGIT1, DIGIT2, DIGIT3, DIGIT4, DIGIT5, DIGIT6, DIGIT7, DIGIT8, DIGIT9 ->
                    directText(ghostty_vt_h.GHOSTTY_KEY_DIGIT_0() + (code.ordinal() - KeyCode.DIGIT0.ordinal()), '0' + (code.ordinal() - KeyCode.DIGIT0.ordinal()), false);
            case NUMPAD0, NUMPAD1, NUMPAD2, NUMPAD3, NUMPAD4, NUMPAD5, NUMPAD6, NUMPAD7, NUMPAD8, NUMPAD9 ->
                    directText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_0() + (code.ordinal() - KeyCode.NUMPAD0.ordinal()), '0' + (code.ordinal() - KeyCode.NUMPAD0.ordinal()), true);
            case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16, F17, F18, F19, F20, F21, F22, F23, F24 ->
                    directNonText(ghostty_vt_h.GHOSTTY_KEY_F1() + (code.ordinal() - KeyCode.F1.ordinal()), false, false);
            case COMMA -> directText(ghostty_vt_h.GHOSTTY_KEY_COMMA(), ',', false);
            case MINUS -> directText(ghostty_vt_h.GHOSTTY_KEY_MINUS(), '-', false);
            case PERIOD -> directText(ghostty_vt_h.GHOSTTY_KEY_PERIOD(), '.', false);
            case SLASH -> directText(ghostty_vt_h.GHOSTTY_KEY_SLASH(), '/', false);
            case SEMICOLON -> directText(ghostty_vt_h.GHOSTTY_KEY_SEMICOLON(), ';', false);
            case EQUALS -> directText(ghostty_vt_h.GHOSTTY_KEY_EQUAL(), '=', false);
            case OPEN_BRACKET -> directText(ghostty_vt_h.GHOSTTY_KEY_BRACKET_LEFT(), '[', false);
            case BACK_SLASH -> directText(ghostty_vt_h.GHOSTTY_KEY_BACKSLASH(), '\\', false);
            case CLOSE_BRACKET -> directText(ghostty_vt_h.GHOSTTY_KEY_BRACKET_RIGHT(), ']', false);
            case BACK_QUOTE -> directText(ghostty_vt_h.GHOSTTY_KEY_BACKQUOTE(), '`', false);
            case QUOTE -> directText(ghostty_vt_h.GHOSTTY_KEY_QUOTE(), '\'', false);
            case SPACE -> directText(ghostty_vt_h.GHOSTTY_KEY_SPACE(), ' ', false);
            case MULTIPLY -> directText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_MULTIPLY(), '*', true);
            case ADD -> directText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_ADD(), '+', true);
            case SEPARATOR -> directText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_SEPARATOR(), 0, true);
            case SUBTRACT -> directText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_SUBTRACT(), '-', true);
            case DECIMAL -> directText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_DECIMAL(), 0, true);
            case DIVIDE -> directText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_DIVIDE(), '/', true);
            case ENTER -> directNonText(ghostty_vt_h.GHOSTTY_KEY_ENTER(), false, false);
            case BACK_SPACE -> directNonText(ghostty_vt_h.GHOSTTY_KEY_BACKSPACE(), false, false);
            case TAB -> directNonText(ghostty_vt_h.GHOSTTY_KEY_TAB(), false, false);
            case ESCAPE -> directNonText(ghostty_vt_h.GHOSTTY_KEY_ESCAPE(), false, false);
            case DELETE -> directNonText(ghostty_vt_h.GHOSTTY_KEY_DELETE(), false, false);
            case CLEAR -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_CLEAR(), false, true);
            case PAGE_UP -> directNonText(ghostty_vt_h.GHOSTTY_KEY_PAGE_UP(), false, false);
            case PAGE_DOWN -> directNonText(ghostty_vt_h.GHOSTTY_KEY_PAGE_DOWN(), false, false);
            case END -> directNonText(ghostty_vt_h.GHOSTTY_KEY_END(), false, false);
            case HOME -> directNonText(ghostty_vt_h.GHOSTTY_KEY_HOME(), false, false);
            case LEFT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_ARROW_LEFT(), false, false);
            case UP -> directNonText(ghostty_vt_h.GHOSTTY_KEY_ARROW_UP(), false, false);
            case RIGHT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_ARROW_RIGHT(), false, false);
            case DOWN -> directNonText(ghostty_vt_h.GHOSTTY_KEY_ARROW_DOWN(), false, false);
            case INSERT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_INSERT(), false, false);
            case HELP -> directNonText(ghostty_vt_h.GHOSTTY_KEY_HELP(), false, false);
            case PRINTSCREEN -> directNonText(ghostty_vt_h.GHOSTTY_KEY_PRINT_SCREEN(), false, false);
            case PAUSE -> directNonText(ghostty_vt_h.GHOSTTY_KEY_PAUSE(), false, false);
            case CAPS -> directNonText(ghostty_vt_h.GHOSTTY_KEY_CAPS_LOCK(), false, false);
            case NUM_LOCK -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NUM_LOCK(), false, false);
            case SCROLL_LOCK -> directNonText(ghostty_vt_h.GHOSTTY_KEY_SCROLL_LOCK(), false, false);
            case META, WINDOWS, COMMAND -> directModifier(ghostty_vt_h.GHOSTTY_KEY_META_LEFT());
            case SHIFT -> directModifier(ghostty_vt_h.GHOSTTY_KEY_SHIFT_LEFT());
            case CONTROL -> directModifier(ghostty_vt_h.GHOSTTY_KEY_CONTROL_LEFT());
            case ALT -> directModifier(ghostty_vt_h.GHOSTTY_KEY_ALT_LEFT());
            case CONTEXT_MENU -> directNonText(ghostty_vt_h.GHOSTTY_KEY_CONTEXT_MENU(), false, false);
            case CONVERT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_CONVERT(), false, false);
            case NONCONVERT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NON_CONVERT(), false, false);
            case KANA -> directNonText(ghostty_vt_h.GHOSTTY_KEY_KANA_MODE(), false, false);
            case COPY -> directNonText(ghostty_vt_h.GHOSTTY_KEY_COPY(), false, false);
            case CUT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_CUT(), false, false);
            case PASTE -> directNonText(ghostty_vt_h.GHOSTTY_KEY_PASTE(), false, false);
            case KP_UP -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_UP(), false, true);
            case KP_DOWN -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_DOWN(), false, true);
            case KP_LEFT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_LEFT(), false, true);
            case KP_RIGHT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_RIGHT(), false, true);
            case BEGIN -> directNonText(ghostty_vt_h.GHOSTTY_KEY_NUMPAD_BEGIN(), false, true);
            case EJECT_TOGGLE -> directNonText(ghostty_vt_h.GHOSTTY_KEY_EJECT(), false, false);
            case PLAY -> directNonText(ghostty_vt_h.GHOSTTY_KEY_MEDIA_PLAY_PAUSE(), false, false);
            case STOP -> directNonText(ghostty_vt_h.GHOSTTY_KEY_MEDIA_STOP(), false, false);
            case TRACK_PREV -> directNonText(ghostty_vt_h.GHOSTTY_KEY_MEDIA_TRACK_PREVIOUS(), false, false);
            case TRACK_NEXT -> directNonText(ghostty_vt_h.GHOSTTY_KEY_MEDIA_TRACK_NEXT(), false, false);
            case VOLUME_UP -> directNonText(ghostty_vt_h.GHOSTTY_KEY_AUDIO_VOLUME_UP(), false, false);
            case VOLUME_DOWN -> directNonText(ghostty_vt_h.GHOSTTY_KEY_AUDIO_VOLUME_DOWN(), false, false);
            case MUTE -> directNonText(ghostty_vt_h.GHOSTTY_KEY_AUDIO_VOLUME_MUTE(), false, false);
            case POWER -> directNonText(ghostty_vt_h.GHOSTTY_KEY_POWER(), false, false);
            case DEAD_GRAVE, DEAD_ACUTE, DEAD_CIRCUMFLEX, DEAD_TILDE, DEAD_MACRON, DEAD_BREVE, DEAD_ABOVEDOT,
                    DEAD_DIAERESIS, DEAD_ABOVERING, DEAD_DOUBLEACUTE, DEAD_CARON, DEAD_CEDILLA, DEAD_OGONEK,
                    DEAD_IOTA, DEAD_VOICED_SOUND, DEAD_SEMIVOICED_SOUND, AMPERSAND, ASTERISK, QUOTEDBL, LESS,
                    GREATER, BRACELEFT, BRACERIGHT, AT, COLON, CIRCUMFLEX, DOLLAR, EURO_SIGN, EXCLAMATION_MARK,
                    INVERTED_EXCLAMATION_MARK, LEFT_PARENTHESIS, NUMBER_SIGN, PLUS, RIGHT_PARENTHESIS, UNDERSCORE,
                    COMPOSE -> semanticText();
            case STAR, POUND -> unsupportedText();
            case ALT_GRAPH, SHORTCUT, CANCEL, FINAL, ACCEPT, MODECHANGE, KANJI, ALPHANUMERIC, KATAKANA, HIRAGANA,
                    FULL_WIDTH, HALF_WIDTH, ROMAN_CHARACTERS, ALL_CANDIDATES, PREVIOUS_CANDIDATE, CODE_INPUT,
                    JAPANESE_KATAKANA, JAPANESE_HIRAGANA, JAPANESE_ROMAN, KANA_LOCK, INPUT_METHOD_ON_OFF,
                    UNDEFINED, UNDO, AGAIN, FIND, PROPS, SOFTKEY_0, SOFTKEY_1, SOFTKEY_2, SOFTKEY_3, SOFTKEY_4,
                    SOFTKEY_5, SOFTKEY_6, SOFTKEY_7, SOFTKEY_8, SOFTKEY_9, GAME_A, GAME_B, GAME_C, GAME_D,
                    INFO, COLORED_KEY_0, COLORED_KEY_1, COLORED_KEY_2, COLORED_KEY_3, RECORD, FAST_FWD, REWIND,
                    CHANNEL_UP, CHANNEL_DOWN -> semanticNonText();
        };
    }

    enum Platform {
        WINDOWS,
        MACOS,
        LINUX;

        static Platform current() {
            var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return WINDOWS;
            }
            if (os.contains("mac") || os.contains("darwin")) {
                return MACOS;
            }
            return LINUX;
        }
    }

    enum Bucket {
        PHYSICAL,
        SEMANTIC,
        UNSUPPORTED
    }

    enum TextKind {
        NONE,
        DIRECT,
        COMMIT_ONLY
    }

    record ScreenPoint(int x, int y) {
        ScreenPoint {
            if (x < 0 || x > 0xFFFF || y < 0) {
                throw new IllegalArgumentException("screen coordinates out of range");
            }
        }
    }

    record Selection(ScreenPoint from, ScreenPoint to, boolean rectangle) {
        Selection {
            if ((from == null) != (to == null)) {
                throw new IllegalArgumentException("selection endpoints must both be null or both be present");
            }
            if (from == null && rectangle) {
                throw new IllegalArgumentException("empty selection cannot be rectangular");
            }
        }

        static Selection empty() {
            return new Selection(null, null, false);
        }

        static Selection linear(ScreenPoint from, ScreenPoint to) {
            return new Selection(
                    java.util.Objects.requireNonNull(from, "from"),
                    java.util.Objects.requireNonNull(to, "to"),
                    false);
        }

        boolean isEmpty() {
            return from == null;
        }

        Selection normalized() {
            if (isEmpty()) {
                return this;
            }
            if (rectangle) {
                return new Selection(
                        new ScreenPoint(Math.min(from.x(), to.x()), Math.min(from.y(), to.y())),
                        new ScreenPoint(Math.max(from.x(), to.x()), Math.max(from.y(), to.y())),
                        true);
            }
            return compare(from, to) <= 0 ? this : new Selection(to, from, false);
        }

        private static int compare(ScreenPoint left, ScreenPoint right) {
            var byY = Integer.compare(left.y(), right.y());
            return byY != 0 ? byY : Integer.compare(left.x(), right.x());
        }
    }

    record KeySnapshot(KeyCode code, boolean shiftDown, boolean controlDown, boolean altDown, boolean metaDown) {
    }

    record KeyClassification(
            Bucket bucket,
            int ghosttyKey,
            int unshiftedCodepoint,
            TextKind textKind,
            boolean modifierOnly,
            boolean keypad) {
    }

    record Preedit(String text, int caretPosition) {
        static Preedit empty() {
            return new Preedit("", -1);
        }
    }

    record PendingPress(
            KeyCode code,
            int action,
            short mods,
            short consumedMods,
            int ghosttyKey,
            int unshiftedCodepoint,
            boolean released,
            boolean composingEmitted) {

        PendingPress(
                KeyCode code,
                int action,
                int ghosttyKey,
                short mods,
                short consumedMods,
                int unshiftedCodepoint,
                boolean released,
                boolean composingEmitted) {
            this(code, action, mods, consumedMods, ghosttyKey, unshiftedCodepoint, released, composingEmitted);
        }

        PendingPress withReleased(boolean released) {
            return new PendingPress(code, action, mods, consumedMods, ghosttyKey, unshiftedCodepoint, released, composingEmitted);
        }

        PendingPress withComposingEmitted(boolean composingEmitted) {
            return new PendingPress(code, action, mods, consumedMods, ghosttyKey, unshiftedCodepoint, released, composingEmitted);
        }
    }

    record InputState(
            Set<KeyCode> pressedKeys,
            Set<KeyCode> emittedKeys,
            List<PendingPress> deferredPresses,
            boolean altGraphDown,
            Preedit preedit,
            Selection selection,
            MouseState mouseState) {

        InputState withPressedKeys(Set<KeyCode> pressedKeys) {
            return new InputState(pressedKeys, emittedKeys, deferredPresses, altGraphDown, preedit, selection, mouseState);
        }

        InputState withEmittedKeys(Set<KeyCode> emittedKeys) {
            return new InputState(pressedKeys, emittedKeys, deferredPresses, altGraphDown, preedit, selection, mouseState);
        }

        InputState withDeferredPresses(List<PendingPress> deferredPresses) {
            return new InputState(pressedKeys, emittedKeys, deferredPresses, altGraphDown, preedit, selection, mouseState);
        }

        InputState withAltGraphDown(boolean altGraphDown) {
            return new InputState(pressedKeys, emittedKeys, deferredPresses, altGraphDown, preedit, selection, mouseState);
        }

        InputState withPreedit(Preedit preedit) {
            return new InputState(pressedKeys, emittedKeys, deferredPresses, altGraphDown, preedit, selection, mouseState);
        }

        InputState withSelection(Selection selection) {
            return new InputState(pressedKeys, emittedKeys, deferredPresses, altGraphDown, preedit, selection, mouseState);
        }

        InputState withMouseState(MouseState mouseState) {
            return new InputState(pressedKeys, emittedKeys, deferredPresses, altGraphDown, preedit, selection, mouseState);
        }
    }

    record MouseState(
            double discreteScrollRemainder,
            double smoothScrollRemainderRows,
            boolean scrollGestureActive,
            boolean scrollbarDragging,
            double scrollbarThumbGrabRatio) {
        static MouseState initial() {
            return new MouseState(0, 0, false, false, 0);
        }

        MouseState withDiscreteScrollRemainder(double discreteScrollRemainder) {
            return new MouseState(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        MouseState withSmoothScrollRemainderRows(double smoothScrollRemainderRows) {
            return new MouseState(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        MouseState withScrollGestureActive(boolean scrollGestureActive) {
            return new MouseState(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        MouseState withScrollbarDragging(boolean scrollbarDragging) {
            return new MouseState(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        MouseState withScrollbarThumbGrabRatio(double scrollbarThumbGrabRatio) {
            return new MouseState(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }
    }

    sealed interface Output permits EncodeOutput, RawTextOutput {
    }

    record EncodeOutput(
            KeyCode code,
            int action,
            int ghosttyKey,
            short mods,
            short consumedMods,
            int unshiftedCodepoint,
            String utf8,
            boolean composing) implements Output {
    }

    record RawTextOutput(String text) implements Output {
    }

    record Transition(InputState state, List<Output> outputs, boolean redraw) {
    }

    record ScrollUpdate(InputState state, int lineDelta) {
    }

    private static Transition emitImmediate(
            InputState state,
            KeyCode code,
            int action,
            int ghosttyKey,
            short mods,
            short consumedMods,
            int unshiftedCodepoint,
            String utf8,
            boolean composing,
            boolean clearSelection) {
        var nextState = state.withEmittedKeys(addEmitted(state.emittedKeys(), code));
        if (clearSelection) {
            nextState = nextState.withSelection(Selection.empty());
        }
        return new Transition(
                nextState,
                List.of(new EncodeOutput(code, action, ghosttyKey, mods, consumedMods, unshiftedCodepoint, utf8, composing)),
                false);
    }

    private static List<PendingPress> dropFirstDeferred(List<PendingPress> deferredPresses) {
        return deferredPresses.size() == 1
                ? List.of()
                : List.copyOf(deferredPresses.subList(1, deferredPresses.size()));
    }

    private static boolean shouldEncodeImmediately(short mods, boolean altGrText, boolean macOptionText, boolean windowsAltNumpad) {
        var hasNonShiftModifier = (mods & (ghostty_vt_h.GHOSTTY_MODS_CTRL()
                | ghostty_vt_h.GHOSTTY_MODS_ALT()
                | ghostty_vt_h.GHOSTTY_MODS_SUPER())) != 0;
        return hasNonShiftModifier && !altGrText && !macOptionText && !windowsAltNumpad;
    }

    private static short modifiers(KeySnapshot event) {
        var mods = 0;
        if (event.shiftDown()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_SHIFT();
        }
        if (event.controlDown()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_CTRL();
        }
        if (event.altDown()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_ALT();
        }
        if (event.metaDown()) {
            mods |= ghostty_vt_h.GHOSTTY_MODS_SUPER();
        }
        return (short) mods;
    }

    private static short consumedModifiers(short mods, boolean altGrText, boolean macOptionText) {
        var consumed = 0;
        if ((mods & ghostty_vt_h.GHOSTTY_MODS_SHIFT()) != 0) {
            consumed |= ghostty_vt_h.GHOSTTY_MODS_SHIFT();
        }
        if (altGrText) {
            consumed |= ghostty_vt_h.GHOSTTY_MODS_CTRL() | ghostty_vt_h.GHOSTTY_MODS_ALT();
        } else if (macOptionText && (mods & ghostty_vt_h.GHOSTTY_MODS_ALT()) != 0) {
            consumed |= ghostty_vt_h.GHOSTTY_MODS_ALT();
        }
        return (short) consumed;
    }

    private static boolean shouldIgnoreTypedText(String text) {
        return text == null
                || text.isEmpty()
                || KeyEvent.CHAR_UNDEFINED.equals(text)
                || text.codePoints().allMatch(Character::isISOControl);
    }

    private static List<PendingPress> markDeferredReleased(List<PendingPress> deferredPresses, KeyCode code) {
        if (deferredPresses.isEmpty()) {
            return deferredPresses;
        }
        var updated = new ArrayList<PendingPress>(deferredPresses.size());
        var changed = false;
        for (var pending : deferredPresses) {
            if (pending.code() == code && !pending.released()) {
                pending = pending.withReleased(true);
                changed = true;
            }
            updated.add(pending);
        }
        return changed ? List.copyOf(updated) : deferredPresses;
    }

    private static Set<KeyCode> addEmitted(Set<KeyCode> emittedKeys, KeyCode code) {
        var emitted = copyPressedKeys(emittedKeys);
        emitted.add(code);
        return Set.copyOf(emitted);
    }

    private static Set<KeyCode> removeEmitted(Set<KeyCode> emittedKeys, KeyCode code) {
        if (!emittedKeys.contains(code)) {
            return emittedKeys;
        }
        var emitted = copyPressedKeys(emittedKeys);
        emitted.remove(code);
        return Set.copyOf(emitted);
    }

    private static EnumSet<KeyCode> copyPressedKeys(Set<KeyCode> keys) {
        var result = EnumSet.noneOf(KeyCode.class);
        result.addAll(keys);
        return result;
    }

    private static KeyClassification directText(int ghosttyKey, int unshiftedCodepoint, boolean keypad) {
        return new KeyClassification(Bucket.PHYSICAL, ghosttyKey, unshiftedCodepoint, TextKind.DIRECT, false, keypad);
    }

    private static KeyClassification directNonText(int ghosttyKey, boolean modifierOnly, boolean keypad) {
        return new KeyClassification(Bucket.PHYSICAL, ghosttyKey, 0, TextKind.NONE, modifierOnly, keypad);
    }

    private static KeyClassification directModifier(int ghosttyKey) {
        return directNonText(ghosttyKey, true, false);
    }

    private static KeyClassification semanticText() {
        return new KeyClassification(Bucket.SEMANTIC, ghostty_vt_h.GHOSTTY_KEY_UNIDENTIFIED(), 0, TextKind.COMMIT_ONLY, false, false);
    }

    private static KeyClassification semanticNonText() {
        return new KeyClassification(Bucket.SEMANTIC, ghostty_vt_h.GHOSTTY_KEY_UNIDENTIFIED(), 0, TextKind.NONE, false, false);
    }

    private static KeyClassification unsupportedText() {
        return new KeyClassification(Bucket.UNSUPPORTED, ghostty_vt_h.GHOSTTY_KEY_UNIDENTIFIED(), 0, TextKind.COMMIT_ONLY, false, false);
    }
}
