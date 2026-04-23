package io.github.vlaaad.ghosttyfx;

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

    record ScreenPoint(int x, int y) {
        ScreenPoint {
            if (x < 0 || x > 0xFFFF || y < 0) {
                throw new IllegalArgumentException("screen coordinates out of range");
            }
        }
    }
}
