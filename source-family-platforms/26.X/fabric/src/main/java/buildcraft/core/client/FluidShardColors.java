/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.core.client;

import java.awt.image.BufferedImage;

/** Color math for the shard's white liquid mask, independent of Minecraft's renderer. */
final class FluidShardColors {
    private FluidShardColors() {}

    /** Average all animation frames, ignoring transparent pixels. */
    static int averageTexture(BufferedImage image) {
        long red = 0, green = 0, blue = 0, weight = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                int alpha = pixel >>> 24;
                red += ((pixel >>> 16) & 255) * alpha;
                green += ((pixel >>> 8) & 255) * alpha;
                blue += (pixel & 255) * alpha;
                weight += alpha;
            }
        }
        if (weight == 0) return 0xFFFFFFFF;
        return 0xFF000000 | (rounded(red, weight) << 16)
            | (rounded(green, weight) << 8) | rounded(blue, weight);
    }

    /** Same RGB multiplication as a tinted fluid texture; a filled shard is opaque. */
    static int multiplyRgb(int texture, int tint) {
        return 0xFF000000
            | (multiply(texture >>> 16, tint >>> 16) << 16)
            | (multiply(texture >>> 8, tint >>> 8) << 8)
            | multiply(texture, tint);
    }

    private static int rounded(long sum, long weight) {
        return (int) ((sum + weight / 2) / weight);
    }

    private static int multiply(int a, int b) {
        return ((a & 255) * (b & 255) + 127) / 255;
    }
}
