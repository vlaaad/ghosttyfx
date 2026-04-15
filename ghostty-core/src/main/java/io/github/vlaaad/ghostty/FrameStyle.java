package io.github.vlaaad.ghostty;

import java.util.Objects;

/// Resolved cell style packed for rendering.
///
/// Colors are packed as `0xRRGGBB`.
public record FrameStyle(
    int foreground,
    int background,
    int underlineColor,
    UnderlineStyle underlineStyle,
    int flags
) {
    private static final int FLAG_BOLD = 1;
    private static final int FLAG_FAINT = 1 << 1;
    private static final int FLAG_ITALIC = 1 << 2;
    private static final int FLAG_UNDERLINE = 1 << 3;
    private static final int FLAG_BLINK = 1 << 4;
    private static final int FLAG_INVERSE = 1 << 5;
    private static final int FLAG_INVISIBLE = 1 << 6;
    private static final int FLAG_STRIKETHROUGH = 1 << 7;
    private static final int FLAG_OVERLINE = 1 << 8;

    public FrameStyle {
        Objects.requireNonNull(underlineStyle, "underlineStyle");
    }

    public FrameStyle(
        int foreground,
        int background,
        int underlineColor,
        UnderlineStyle underlineStyle,
        boolean bold,
        boolean faint,
        boolean italic,
        boolean underlined,
        boolean blinking,
        boolean inverse,
        boolean invisible,
        boolean strikethrough,
        boolean overlined
    ) {
        this(
            foreground,
            background,
            underlineColor,
            underlineStyle,
            packFlags(bold, faint, italic, underlined, blinking, inverse, invisible, strikethrough, overlined)
        );
    }

    public boolean isBold() {
        return hasFlag(FLAG_BOLD);
    }

    public boolean isFaint() {
        return hasFlag(FLAG_FAINT);
    }

    public boolean isItalic() {
        return hasFlag(FLAG_ITALIC);
    }

    public boolean hasUnderline() {
        return hasFlag(FLAG_UNDERLINE);
    }

    public boolean isBlinking() {
        return hasFlag(FLAG_BLINK);
    }

    public boolean usesInverseVideo() {
        return hasFlag(FLAG_INVERSE);
    }

    public boolean isInvisible() {
        return hasFlag(FLAG_INVISIBLE);
    }

    public boolean hasStrikethrough() {
        return hasFlag(FLAG_STRIKETHROUGH);
    }

    public boolean hasOverline() {
        return hasFlag(FLAG_OVERLINE);
    }

    private boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    private static int packFlags(
        boolean bold,
        boolean faint,
        boolean italic,
        boolean underlined,
        boolean blinking,
        boolean inverse,
        boolean invisible,
        boolean strikethrough,
        boolean overlined
    ) {
        var flags = 0;
        if (bold) {
            flags |= FLAG_BOLD;
        }
        if (faint) {
            flags |= FLAG_FAINT;
        }
        if (italic) {
            flags |= FLAG_ITALIC;
        }
        if (underlined) {
            flags |= FLAG_UNDERLINE;
        }
        if (blinking) {
            flags |= FLAG_BLINK;
        }
        if (inverse) {
            flags |= FLAG_INVERSE;
        }
        if (invisible) {
            flags |= FLAG_INVISIBLE;
        }
        if (strikethrough) {
            flags |= FLAG_STRIKETHROUGH;
        }
        if (overlined) {
            flags |= FLAG_OVERLINE;
        }
        return flags;
    }
}
