package io.github.vlaaad.ghosttyfx;

record ActiveLink(Target target, Selection selection, Runnable action) {
    boolean sameTarget(ActiveLink other) {
        return other != null && target.equals(other.target) && selection.equals(other.selection);
    }

    sealed interface Target permits Osc8, Regex {
    }

    record Osc8(String uri) implements Target {
    }

    record Regex(int index, String text) implements Target {
    }
}
