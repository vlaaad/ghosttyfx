package io.github.vlaaad.ghosttyfx;

import java.util.Objects;

/// A notification requested by the application running in the terminal.
///
/// Empty title and body values are valid.
///
/// @param title the notification title
/// @param body the notification body
public record Notification(String title, String body) {

    public Notification {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
    }
}
