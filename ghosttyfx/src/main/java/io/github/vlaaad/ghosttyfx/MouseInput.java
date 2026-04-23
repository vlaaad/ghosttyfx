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
            double scrollbarThumbGrabRatio) {
        static State initial() {
            return new State(0, 0, false, false, 0);
        }

        State withDiscreteScrollRemainder(double discreteScrollRemainder) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        State withSmoothScrollRemainderRows(double smoothScrollRemainderRows) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        State withScrollGestureActive(boolean scrollGestureActive) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        State withScrollbarDragging(boolean scrollbarDragging) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }

        State withScrollbarThumbGrabRatio(double scrollbarThumbGrabRatio) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio);
        }
    }

    record ScrollUpdate(State state, int lineDelta) {
    }
}
