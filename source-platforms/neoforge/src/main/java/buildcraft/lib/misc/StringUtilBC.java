/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import java.text.DecimalFormat;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.base.Splitter;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

public final class StringUtilBC {

    public static final Splitter newLineSplitter = Splitter.on('\n');

    private static final DecimalFormat displayDecimalFormat = new DecimalFormat("#####0.00");

    /**
     * Remembers the unmodified source for recently converted strings. This makes the common tooltip -> book
     * conversion reversible even where multiple original colours map to the same high-contrast colour. The cache is
     * deliberately small: it is only provenance for live UI strings, not persistent application state.
     */
    private static final Map<String, String> FORMAT_SOURCES = new LinkedHashMap<>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 512;
        }
    };

    /** Deactivate constructor */
    private StringUtilBC() {}

    public static List<String> splitIntoLines(String string) {
        // Legacy .lang files store line breaks as the two characters "\\n",
        // while JSON translations decode "\n" into an actual newline. Support
        // both forms so legacy and current translations behave identically.
        String normalized = string.replace("\\n", "\n").replace("\r\n", "\n").replace('\r', '\n');
        return newLineSplitter.splitToList(normalized);
    }

    /** Formats a string to be displayed on a white background (for example a book background), replacing any
     * close-to-white colours with darker variants. Replaces instances of {@link ChatFormatting} values. */
    public static String formatStringForWhite(String string) {
        return formatStringImpl(string, ColourUtil.getTextFormatForWhite);
    }

    /** Formats a string to be displayed on a black background (for example an item tooltip), replacing any
     * close-to-white colours with darker variants. Replaces instances of {@link ChatFormatting} values. */
    public static String formatStringForBlack(String string) {
        return formatStringImpl(string, ColourUtil.getTextFormatForBlack);
    }

    private static String formatStringImpl(String string, Function<ChatFormatting, ChatFormatting> fn) {
        String source;
        synchronized (FORMAT_SOURCES) {
            source = FORMAT_SOURCES.getOrDefault(string, string);
        }
        StringBuilder out = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\u00a7' && source.length() > i + 1) {
                i++;
                char after = source.charAt(i);
                ChatFormatting colour = null;
                if (after >= '0' && after <= '9') {
                    colour = ChatFormatting.getById(after - '0');
                } else if (after >= 'a' && after <= 'f') {
                    colour = ChatFormatting.getById(after - 'a' + 10);
                } else if (after >= 'A' && after <= 'F') {
                    colour = ChatFormatting.getById(after - 'A' + 10);
                }
                if (colour == null) {
                    out.append(c).append(after);
                } else {
                    out.append(fn.apply(colour));
                }
            } else {
                out.append(c);
            }
        }
        String formatted = out.toString();
        synchronized (FORMAT_SOURCES) {
            FORMAT_SOURCES.put(formatted, source);
        }
        return formatted;
    }

    public static String blockPosToString(BlockPos pos) {
        if (pos == null) {
            return "null";
        }
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static String blockPosAsSizeToString(BlockPos pos) {
        if (pos == null) {
            return "null";
        }
        return pos.getX() + "x" + pos.getY() + "x" + pos.getZ();
    }

    public static String fluidToString(FluidStack fluid) {
        if (fluid == null) {
            return "null";
        }
        return fluid.getAmount() + "mb " + fluid.getFluid().getFluidType().getDescriptionId();
    }

    // Displaying objects
    public static String vec3ToDispString(Vec3 vec) {
        if (vec == null) {
            return "null";
        }
        return displayDecimalFormat.format(vec.x) + ", " + displayDecimalFormat.format(vec.y) + ", "
            + displayDecimalFormat.format(vec.z);
    }

    public static String vec3ToDispString(Vec3i vec) {
        if (vec == null) {
            return "null";
        }
        return vec.getX() + ", " + vec.getY() + ", " + vec.getZ();
    }

    /** @param keyExtractor An extractor that will map an object to a string.
     * @return A form of {@link #compareBasicReadable()} that operates on any object that can provide a string. */
    public static <T> Comparator<T> compareBasicReadable(Function<T, String> keyExtractor) {
        return Comparator.comparing(keyExtractor, compareBasicReadable());
    }

    /** @return A comparator that only compares the text that we can see - so this will remove any format codes, and
     *         ignore case when comparing. */
    public static Comparator<String> compareBasicReadable() {
        return BasicReadableStringComparator.INSTANCE;
    }

    enum BasicReadableStringComparator implements Comparator<String> {
        INSTANCE;

        @Override
        public int compare(String o1, String o2) {
            String __o1 = ColourUtil.stripAllFormatCodes(o1);
            String __o2 = ColourUtil.stripAllFormatCodes(o2);
            return __o1.compareToIgnoreCase(__o2);
        }
    }
}
