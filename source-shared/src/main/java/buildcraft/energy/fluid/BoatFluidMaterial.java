/* SPDX-License-Identifier: MPL-2.0 */
package buildcraft.energy.fluid;

import java.util.Map;

/**
 * Physical properties of BuildCraft world fluids, including source/flowing and heated forms.
 *
 * <p>Values mirror BCEnergyFluids.DATA across Forge, NeoForge and Fabric. Kept free of
 * Minecraft classes so the model can be tested without starting a game client.
 */
public record BoatFluidMaterial(String family, int heatTier, int density, int viscosity, int temperature) {
    // density, viscosity, first gaseous heat tier (3 = never gaseous)
    private static final Map<String, int[]> BASE = Map.of(
        "oil",                 new int[] {900, 2000, 3},
        "oil_residue",         new int[] {1200, 4000, 3},
        "oil_heavy",           new int[] {850, 1800, 3},
        "oil_dense",           new int[] {950, 1600, 3},
        "oil_distilled",       new int[] {750, 1400, 2},
        "fuel_dense",          new int[] {600, 800, 2},
        "fuel_mixed_heavy",    new int[] {700, 1000, 2},
        "fuel_light",          new int[] {400, 600, 1},
        "fuel_mixed_light",    new int[] {650, 900, 1},
        "fuel_gaseous",        new int[] {300, 500, 0}
    );

    public static BoatFluidMaterial fromId(String namespace, String id) {
        if (!"buildcraftenergy".equals(namespace) || id == null) return null;
        String name = id.endsWith("_flowing") ? id.substring(0, id.length() - "_flowing".length()) : id;
        if ("spout_oil".equals(name)) name = "oil";
        int heat = 0;
        if (name.endsWith("_heat_1") || name.endsWith("_heat_2")) {
            heat = name.charAt(name.length() - 1) - '0';
            name = name.substring(0, name.length() - "_heat_1".length());
        }
        int[] data = BASE.get(name);
        if (data == null) return null;
        int density = heat >= data[2] ? -data[0] : data[0];
        int viscosity = data[1] * (4 - heat) / 4;
        // The registered FluidType temperature is currently 300 + 50 * heat.
        // The nominal names COOL/HOT/SEARING are distinct from these registered values.
        return new BoatFluidMaterial(name, heat, density, viscosity, 300 + 50 * heat);
    }

    public boolean isGas() {
        return density < 0;
    }

    /**
     * Additional horizontal damping, applied after vanilla boat rowing physics.
     * Water-like fuels stay fast; crude oil becomes slow and residue nearly impassable.
     * Hotter *liquids* thin out through the registered viscosity reduction.
     */
    public double horizontalDrag() {
        if (isGas()) return 1.0;
        double excess = Math.max(0.0, viscosity / 1000.0 - 1.0);
        return clamp(1.0 / (1.0 + 0.55 * excess), 0.34, 0.99);
    }

    public double floatStrength() {
        return isGas() ? 0.0 : clamp(density / 950.0, 0.65, 1.2);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
