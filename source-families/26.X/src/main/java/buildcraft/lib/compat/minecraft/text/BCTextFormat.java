//? source if >=26.2
/* Copyright (c) 2026 the BuildCraft team. MPL-2.0. */
package buildcraft.lib.compat.minecraft.text;

import java.util.Locale;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Style;

/** BuildCraft's legacy text tokens, applied to native immutable component styles. */
public enum BCTextFormat implements UnaryOperator<Style> {
    BLACK('0', 0x000000), DARK_BLUE('1', 0x0000AA), DARK_GREEN('2', 0x00AA00),
    DARK_AQUA('3', 0x00AAAA), DARK_RED('4', 0xAA0000), DARK_PURPLE('5', 0xAA00AA),
    GOLD('6', 0xFFAA00), GRAY('7', 0xAAAAAA), DARK_GRAY('8', 0x555555),
    BLUE('9', 0x5555FF), GREEN('a', 0x55FF55), AQUA('b', 0x55FFFF),
    RED('c', 0xFF5555), LIGHT_PURPLE('d', 0xFF55FF), YELLOW('e', 0xFFFF55), WHITE('f', 0xFFFFFF),
    OBFUSCATED('k'), BOLD('l'), STRIKETHROUGH('m'), UNDERLINE('n'), ITALIC('o'), RESET('r');

    private final char code;
    @Nullable private final Integer colour;

    BCTextFormat(char code) { this.code = code; this.colour = null; }
    BCTextFormat(char code, int colour) { this.code = code; this.colour = colour; }

    public char getChar() { return code; }
    public boolean isColor() { return colour != null; }
    public boolean isFormat() { return colour == null && this != RESET; }
    public int getId() { return isColor() ? ordinal() : -1; }
    @Nullable public Integer getColor() { return colour; }
    public String getName() { return name().toLowerCase(Locale.ROOT); }
    @Override public String toString() { return "\u00a7" + code; }

    @Nullable
    public static BCTextFormat getById(int id) {
        return id < 0 ? RESET : id < 16 ? values()[id] : null;
    }

    @Nullable
    public static BCTextFormat getByCode(char code) {
        char normalized = Character.toLowerCase(code);
        for (BCTextFormat value : values()) if (value.code == normalized) return value;
        return null;
    }

    @Nullable
    public static BCTextFormat getByName(String name) {
        if (name == null) return null;
        for (BCTextFormat value : values()) if (value.getName().equalsIgnoreCase(name)) return value;
        return null;
    }

    /** Formatting is additive: a nested colour must not erase the parent's bold/link style. */
    @Override
    public Style apply(Style style) {
        if (colour != null) return style.withColor(colour);
        return switch (this) {
            case OBFUSCATED -> style.withObfuscated(true);
            case BOLD -> style.withBold(true);
            case STRIKETHROUGH -> style.withStrikethrough(true);
            case UNDERLINE -> style.withUnderlined(true);
            case ITALIC -> style.withItalic(true);
            case RESET -> Style.EMPTY;
            default -> throw new AssertionError(this);
        };
    }
}
