package buildcraft.energy.fluid;

public final class BCFluidFog {
    private BCFluidFog() {}

    public static int color(int light, int dark, int heat) {
        int red = blend((light >>> 16) & 0xFF, (dark >>> 16) & 0xFF, heat);
        int green = blend((light >>> 8) & 0xFF, (dark >>> 8) & 0xFF, heat);
        int blue = blend(light & 0xFF, dark & 0xFF, heat);
        return (red << 16) | (green << 8) | blue;
    }

    private static int blend(int light, int dark, int heat) {
        return Math.min(0xFF, (light + dark) / 2 + heat * 0x10);
    }

    public static float distance(int density) {
        return density < 0 ? 5.0F : 3.0F;
    }

    public static float channel(int fogColor, int shift) {
        return ((fogColor >>> shift) & 0xFF) / 255.0F;
    }
}
