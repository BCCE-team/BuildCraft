package buildcraft.compat.physics;

/** Internal physics balance. Units match Sable's kpg; the VS profiles are independent. */
public final class PhysicalMassBalance {
    public static final int MAX_STEP = 20;
    public static final int DEFAULT_PIPE_STEP = 2; // 0.20 kpg, also the data-only fallback.
    public static final double PIPE_MIN = 0.10, PIPE_STEP = 0.05;
    public static final double TANK_EMPTY = 0.50, TANK_STEP = 0.10;
    private PhysicalMassBalance() {}

    /** Dry mass of a straight built-in pipe. Addons receive a neutral fallback. */
    public static double dryPipeMass(String namespace, String path) {
        if (!"buildcrafttransport".equals(namespace) || path == null) return 0.20;
        if (path.equals("structure")) return 0.10;
        if (path.startsWith("diamond_wood_")) return 0.15;
        if (path.startsWith("wood_")) return 0.10;
        if (path.startsWith("gold_")) return 0.30;
        if (path.startsWith("iron_")) return 0.25;
        if (path.startsWith("obsidian_")) return 0.35;
        if (path.startsWith("diamond_")) return 0.25;
        if (path.startsWith("quartz_") || path.startsWith("sandstone_") || path.startsWith("clay_")) return 0.20;
        return 0.20;
    }

    /** Weight of contents, normalised to the vessel's actual geometric volume.
     * A Minecraft 16-bucket tank occupies less than one cubic metre; do not
     * treat its gameplay capacity as 16 cubic metres. Negative density means
     * a gas in NeoForge, not negative gravitational mass.
     */
    public static double contentsMass(long amount, long capacity, int density, double vesselVolume) {
        if (amount <= 0 || capacity <= 0 || !(vesselVolume > 0) || !Double.isFinite(vesselVolume)) return 0;
        double fill = Math.min(1.0, (double) amount / capacity);
        double relativeDensity = density <= 0 ? 0.02 : Math.min(8.0, density / 1000.0);
        return fill * vesselVolume * relativeDensity;
    }

    public static double pipeMass(double dryMass, int connections, int attachments, int facades, int wires, double contents) {
        int arms = Math.max(0, Math.min(6, connections));
        int plugs = Math.max(0, Math.min(6, attachments));
        int covers = Math.max(0, Math.min(plugs, facades));
        // Central body is half the material of a 2-arm straight segment.
        double geometryFactor = 0.5 + 0.25 * arms;
        return Math.max(PIPE_MIN, dryMass * geometryFactor + 0.05 * (plugs - covers)
            + 0.10 * covers + 0.01 * Math.max(0, Math.min(8, wires)) + Math.max(0, contents));
    }

    public static int pipeStep(double mass, int previous) {
        return quantize(mass - PIPE_MIN, PIPE_STEP, previous);
    }

    public static int tankStep(long amount, long capacity, int density, int previous) {
        if (amount <= 0) return 0; // Empty tanks must shed their complete contents mass.
        return quantize(contentsMass(amount, capacity, density, 0.45), TANK_STEP, previous);
    }

    // 20% step hysteresis prevents a fluctuating fluid boundary waking a ship
    // and sending block-state updates each time it crosses a rounding midpoint.
    private static int quantize(double load, double step, int previous) {
        if (!Double.isFinite(load) || load <= 0) return 0;
        double steps = Math.min(MAX_STEP, load / step);
        if (previous >= 0 && previous <= MAX_STEP && Math.abs(steps - previous) < 0.70) return previous;
        return Math.max(0, Math.min(MAX_STEP, (int) Math.round(steps)));
    }

    public static double pipeMassForStep(int step) {
        return PIPE_MIN + PIPE_STEP * Math.max(0, Math.min(MAX_STEP, step));
    }

    public static double tankMassForStep(int step) {
        return TANK_EMPTY + TANK_STEP * Math.max(0, Math.min(MAX_STEP, step));
    }
}
