package io.github.vlaaad.ghosttyfx;

final class MouseInput {
    private static final double CELL_SELECTION_THRESHOLD = 0.6;

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

    static Selection selectionForDrag(
            Selection.ScreenPoint click,
            Selection.ScreenPoint drag,
            double clickX,
            double dragX,
            boolean rectangle,
            int columns,
            int cellWidthPx) {
        if (columns <= 0 || cellWidthPx <= 0) {
            throw new IllegalArgumentException("columns and cell width must be positive");
        }

        var thresholdPoint = (int) Math.round(cellWidthPx * CELL_SELECTION_THRESHOLD);
        var maxX = columns * cellWidthPx - 1;
        var dragXFraction = Math.min(maxX, Math.max(0, (int) dragX)) % cellWidthPx;
        var clickXFraction = Math.min(maxX, Math.max(0, (int) clickX)) % cellWidthPx;
        var samePoint = click.equals(drag);
        var endBeforeStart = rectangle
                ? drag.x() == click.x()
                        ? dragXFraction < clickXFraction
                        : drag.x() < click.x()
                : compare(drag, click) < 0 || samePoint && dragXFraction < clickXFraction;
        var includeClickCell = endBeforeStart
                ? clickXFraction >= thresholdPoint
                : clickXFraction < thresholdPoint;
        var includeDragCell = endBeforeStart
                ? dragXFraction < thresholdPoint
                : dragXFraction >= thresholdPoint;

        var start = includeClickCell
                ? click
                : endBeforeStart
                        ? rectangle
                                ? leftClamp(click)
                                : leftWrap(click, columns)
                        : rectangle
                                ? rightClamp(click, columns)
                                : rightWrap(click, columns);
        var end = includeDragCell
                ? drag
                : endBeforeStart
                        ? rectangle
                                ? rightClamp(drag, columns)
                                : rightWrap(drag, columns)
                        : rectangle
                                ? leftClamp(drag)
                                : leftWrap(drag, columns);

        if ((!includeClickCell && samePoint)
                || (!includeClickCell && rectangle && click.x() == drag.x())
                || (!includeClickCell && end.equals(click))
                || (!includeClickCell && rectangle && end.x() == click.x())
                || (!includeDragCell && start.equals(drag))
                || (!includeDragCell && rectangle && start.x() == drag.x())) {
            return Selection.empty();
        }

        return new Selection(start, end, rectangle);
    }

    static int normalizeClickCount(int clickCount) {
        return Math.clamp(clickCount, 1, 3);
    }

    private static int compare(Selection.ScreenPoint left, Selection.ScreenPoint right) {
        var byY = Integer.compare(left.y(), right.y());
        return byY != 0 ? byY : Integer.compare(left.x(), right.x());
    }

    private static Selection.ScreenPoint leftClamp(Selection.ScreenPoint point) {
        return new Selection.ScreenPoint(Math.max(0, point.x() - 1), point.y());
    }

    private static Selection.ScreenPoint rightClamp(Selection.ScreenPoint point, int columns) {
        return new Selection.ScreenPoint(Math.min(columns - 1, point.x() + 1), point.y());
    }

    private static Selection.ScreenPoint leftWrap(Selection.ScreenPoint point, int columns) {
        return point.x() > 0
                ? new Selection.ScreenPoint(point.x() - 1, point.y())
                : point.y() > 0
                        ? new Selection.ScreenPoint(columns - 1, point.y() - 1)
                        : point;
    }

    private static Selection.ScreenPoint rightWrap(Selection.ScreenPoint point, int columns) {
        return point.x() + 1 < columns
                ? new Selection.ScreenPoint(point.x() + 1, point.y())
                : new Selection.ScreenPoint(0, point.y() + 1);
    }

    record State(
            double discreteScrollRemainder,
            double smoothScrollRemainderRows,
            boolean scrollGestureActive,
            boolean scrollbarDragging,
            double scrollbarThumbGrabRatio,
            PressGesture pressGesture) {
        static State initial() {
            return new State(0, 0, false, false, 0, null);
        }

        State withDiscreteScrollRemainder(double discreteScrollRemainder) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio, pressGesture);
        }

        State withSmoothScrollRemainderRows(double smoothScrollRemainderRows) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio, pressGesture);
        }

        State withScrollGestureActive(boolean scrollGestureActive) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio, pressGesture);
        }

        State withScrollbarDragging(boolean scrollbarDragging) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio, pressGesture);
        }

        State withScrollbarThumbGrabRatio(double scrollbarThumbGrabRatio) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio, pressGesture);
        }

        State withPressGesture(PressGesture pressGesture) {
            return new State(discreteScrollRemainder, smoothScrollRemainderRows, scrollGestureActive, scrollbarDragging, scrollbarThumbGrabRatio, pressGesture);
        }
    }

    record ScrollUpdate(State state, int lineDelta) {
    }

    record PressGesture(
            TerminalSession.MouseButton button,
            Selection.ScreenPoint anchor,
            double anchorCellOffsetX,
            int clickCount,
            boolean rectangleSelection,
            boolean dragged,
            String hyperlinkUri) {
        PressGesture withDrag(boolean dragged) {
            return new PressGesture(button, anchor, anchorCellOffsetX, clickCount, rectangleSelection, dragged, hyperlinkUri);
        }
    }
}
