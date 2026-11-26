package buildcraft.robotics.gui;

/** Pixel geometry of the processor's 512px atlas. The recessed rail is
 * already part of the background; draw only the red energy sprite. */
final class RobotProcessorPowerGauge {
    static final int ATLAS_SIZE = 512;
    static final int X = 320, Y = 177, WIDTH = 6, HEIGHT = 88;
    static final int SPRITE_X = 476, SPRITE_Y = 0, SPRITE_WIDTH = 16, SPRITE_HEIGHT = 60;

    @FunctionalInterface interface Draw {
        void draw(int x, int y, int u, int v, int width, int height);
    }

    private RobotProcessorPowerGauge() { }

    static boolean contains(double x, double y) {
        return x >= X - 1 && x < X + WIDTH + 1 && y >= Y - 1 && y < Y + HEIGHT + 1;
    }

    static int filledHeight(long stored, long capacity) {
        if (stored <= 0 || capacity <= 0) return 0;
        if (stored >= capacity) return HEIGHT;
        return Math.max(1, Math.min(HEIGHT - 1, (int) Math.round(HEIGHT * (stored / (double) capacity))));
    }

    static void draw(long stored, long capacity, Draw draw) {
        int remaining = filledHeight(stored, capacity);
        int bottom = Y + HEIGHT;
        // The source sprite is 60px tall, while the rail is 88px tall.
        // Tile/crop at native scale instead of stretching or sampling outside the atlas.
        while (remaining > 0) {
            int height = Math.min(SPRITE_HEIGHT, remaining);
            bottom -= height;
            // Anchor the UVs at the bottom too, so filling never scrolls the texture.
            draw.draw(X, bottom, SPRITE_X, SPRITE_Y + SPRITE_HEIGHT - height, WIDTH, height);
            remaining -= height;
        }
    }
}
