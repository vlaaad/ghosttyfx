package io.github.vlaaad.ghosttyfx;

final class MouseInput {
    private MouseInput() {}

    static State initialState() {
        return State.initial();
    }

    static State onFocusLost(State state) {
        return State.initial();
    }

    static State startScrollGesture(State state) {
        return state.scrollGestureActive()
                ? state
                : state.withScrollGestureActive(true);
    }

    static State stopScrollGesture(State state) {
        return state.scrollGestureActive()
                ? state.withScrollGestureActive(false)
                : state;
    }

    static State startScrollbarDrag(State state, double thumbGrabRatio) {
        var nextState = state
                .withScrollbarThumbGrabRatio(Math.clamp(thumbGrabRatio, 0.0, 1.0))
                .withScrollbarDragging(true);
        return state.equals(nextState) ? state : nextState;
    }

    static State stopScrollbarDrag(State state) {
        return state.scrollbarDragging()
                ? state.withScrollbarDragging(false).withScrollbarThumbGrabRatio(0)
                : state;
    }

    static State setScrollbarHovered(State state, boolean scrollbarHovered) {
        return state.scrollbarHovered() == scrollbarHovered
                ? state
                : state.withScrollbarHovered(scrollbarHovered);
    }

    static ScrollUpdate accumulateDiscreteScroll(State state, double deltaTicks) {
        if (deltaTicks == 0 || !Double.isFinite(deltaTicks)) {
            return new ScrollUpdate(state, 0);
        }

        var totalTicks = state.discreteScrollRemainder() + deltaTicks;
        var wholeTicks = (int) totalTicks;
        var remainderTicks = totalTicks - wholeTicks;
        return new ScrollUpdate(state.withDiscreteScrollRemainder(remainderTicks), wholeTicks);
    }

    static ScrollUpdate accumulateSmoothScroll(State state, double deltaRows) {
        if (deltaRows == 0 || !Double.isFinite(deltaRows)) {
            return new ScrollUpdate(state, 0);
        }

        var totalRows = state.smoothScrollRemainderRows() + deltaRows;
        var wholeRows = (int) totalRows;
        var remainderRows = totalRows - wholeRows;
        return new ScrollUpdate(state.withSmoothScrollRemainderRows(remainderRows), wholeRows);
    }

    record State(
            double discreteScrollRemainder,
            double smoothScrollRemainderRows,
            boolean scrollGestureActive,
            boolean scrollbarDragging,
            boolean scrollbarHovered,
            double scrollbarThumbGrabRatio,
            PressGesture pressGesture) {
        static State initial() {
            return new State(0, 0, false, false, false, 0, null);
        }

        State withDiscreteScrollRemainder(double discreteScrollRemainder) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarHovered, scrollbarThumbGrabRatio, pressGesture);
        }

        State withSmoothScrollRemainderRows(double smoothScrollRemainderRows) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarHovered, scrollbarThumbGrabRatio, pressGesture);
        }

        State withScrollGestureActive(boolean scrollGestureActive) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarHovered, scrollbarThumbGrabRatio, pressGesture);
        }

        State withScrollbarDragging(boolean scrollbarDragging) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarHovered, scrollbarThumbGrabRatio, pressGesture);
        }

        State withScrollbarHovered(boolean scrollbarHovered) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarHovered, scrollbarThumbGrabRatio, pressGesture);
        }

        State withScrollbarThumbGrabRatio(double scrollbarThumbGrabRatio) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarHovered, scrollbarThumbGrabRatio, pressGesture);
        }

        State withPressGesture(PressGesture pressGesture) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarHovered, scrollbarThumbGrabRatio, pressGesture);
        }
    }

    record ScrollUpdate(State state, int lineDelta) {
    }

    record PressGesture(
            TerminalSession.MouseButton button,
            Selection.ScreenPoint anchor,
            ActiveLink link) {
    }
}
