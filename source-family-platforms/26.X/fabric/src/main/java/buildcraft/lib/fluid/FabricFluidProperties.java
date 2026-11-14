/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.lib.fluid;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.material.FlowingFluid;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Physical properties needed by the BuildCraft lib algorithms, without NeoForge FluidType. */
public final class FabricFluidProperties {
    private static final Map<Fluid, Integer> DENSITIES = new ConcurrentHashMap<>();
    private static final Map<Fluid, Integer> VISCOSITIES = new ConcurrentHashMap<>();
    private FabricFluidProperties() {}

    /** Treat source and flowing variants as one physical fluid family, like NeoForge FluidType. */
    public static Fluid family(Fluid fluid) {
        return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
    }

    /** NeoForge's default water viscosity is 1000; lava is substantially thicker. */
    public static void registerViscosity(Fluid fluid, int viscosity) {
        if (fluid != null && fluid != Fluids.EMPTY) VISCOSITIES.put(family(fluid), Math.max(0, viscosity));
    }

    public static int viscosity(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return 1000;
        Integer registered = VISCOSITIES.get(family(fluid));
        if (registered != null) return registered;
        return fluid.isSame(Fluids.LAVA) ? 6000 : 1000;
    }

    /** Light is a block-state property on native Fabric rather than a NeoForge FluidType field. */
    public static int lightLevel(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return 0;
        return Math.clamp(fluid.defaultFluidState().createLegacyBlock().getLightEmission(), 0, 15);
    }

    /** Register the density used for a custom fluid (kg per cubic metre). */
    public static void registerDensity(Fluid fluid, int density) {
        if (fluid != null && fluid != Fluids.EMPTY) DENSITIES.put(fluid, density);
    }

    /** A negative density denotes a rising gaseous fluid, as on the NeoForge side. */
    public static int density(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return 0;
        Integer supplied = DENSITIES.get(fluid);
        if (supplied != null) return supplied;
        if (fluid.isSame(Fluids.LAVA)) return 3000;
        if (isLighterThanAir(fluid)) return -1000;
        return 1000;
    }

    public static boolean isLighterThanAir(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) return false;
        Integer supplied = DENSITIES.get(fluid);
        if (supplied != null && supplied < 0) return true;
        return FluidVariantAttributes.isLighterThanAir(FluidVariant.of(fluid));
    }
}
