package io.github.vlaaad.ghostty;

/// Renderer geometry used for mouse encoding.
///
/// @param screenWidth full surface width in pixels
/// @param screenHeight full surface height in pixels
/// @param cellWidth cell width in pixels
/// @param cellHeight cell height in pixels
/// @param paddingTop top padding in pixels
/// @param paddingBottom bottom padding in pixels
/// @param paddingRight right padding in pixels
/// @param paddingLeft left padding in pixels
public record MouseEncoderSize(
    int screenWidth,
    int screenHeight,
    int cellWidth,
    int cellHeight,
    int paddingTop,
    int paddingBottom,
    int paddingRight,
    int paddingLeft
) {
    public MouseEncoderSize {
        requireNonNegative(screenWidth, "screenWidth");
        requireNonNegative(screenHeight, "screenHeight");
        requirePositive(cellWidth, "cellWidth");
        requirePositive(cellHeight, "cellHeight");
        requireNonNegative(paddingTop, "paddingTop");
        requireNonNegative(paddingBottom, "paddingBottom");
        requireNonNegative(paddingRight, "paddingRight");
        requireNonNegative(paddingLeft, "paddingLeft");
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
