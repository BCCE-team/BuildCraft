/* SPDX-License-Identifier: MPL-2.0 */
package buildcraft.energy.fluid;

import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
//? if <1.19.3 {
/*?
import net.minecraft.core.Registry;
?*/
//?} else {
import net.minecraft.core.registries.BuiltInRegistries;
//?}
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
//? if >=1.21.11 {
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
//?} else {
import net.minecraft.world.entity.vehicle.Boat;
//?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Boat-only physics for BuildCraft fluids.
 *
 * <p>Forge and NeoForge use FluidType.supportsBoating for their native surface
 * detection and buoyancy. Fabric has no equivalent in the current BCCE fluid
 * integration, so it receives a small vertical buoyancy correction instead.
 * Drag runs on both sides for locally responsive rowing and server authority.
 */
public final class BoatFluidPhysics {
    private static final Map<Level, List<WeakReference<Entity>>> BOATS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Fluid, BoatFluidMaterial> MATERIALS =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static boolean registered;
    // Set by a platform fluid registry when native boating support is unavailable.
    private static volatile boolean customBuoyancyEnabled;

    private BoatFluidPhysics() {}

    /** Enable hydrostatic lift for platforms without native custom-fluid boat buoyancy. */
    public static void enableCustomBuoyancy() {
        customBuoyancyEnabled = true;
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PlatformEvents.entityJoin(event -> {
            Entity entity = event.getEntity();
            if (!isBoat(entity)) return;
            Level level = entityLevel(entity);
            synchronized (BOATS) {
                BOATS.computeIfAbsent(level, key -> new ArrayList<>()).add(new WeakReference<>(entity));
            }
        });
        PlatformEvents.levelTick(BCEvents.Phase.END, event -> tick(event.getLevel()));
        PlatformEvents.levelUnload(event -> {
            if (event.getLevel() instanceof Level level) {
                synchronized (BOATS) {
                    BOATS.remove(level);
                }
            }
        });
    }

    private static Level entityLevel(Entity entity) {
//? if <1.20 {
        return entity.level;
//?} else {
        return entity.level();
//?}
    }

    private static boolean isBoat(Entity entity) {
//? if >=1.21.11 {
        return entity instanceof AbstractBoat;
//?} else {
        return entity instanceof Boat;
//?}
    }

    /** Ground contact was renamed between Minecraft 1.19.2 and 1.20.1. */
    private static boolean boatOnGround(Entity boat) {
//? if <1.20 {
        return boat.isOnGround();
//?} else {
        return boat.onGround();
//?}
    }

    private static void tick(Level level) {
        if (level == null) return;
        List<WeakReference<Entity>> references;
        synchronized (BOATS) {
            List<WeakReference<Entity>> tracked = BOATS.get(level);
            if (tracked == null || tracked.isEmpty()) return;
            references = new ArrayList<>(tracked);
        }
        for (WeakReference<Entity> reference : references) {
            Entity boat = reference.get();
            if (boat != null && !boat.isRemoved() && entityLevel(boat) == level) {
                updateBoat(boat, level);
            }
        }
        synchronized (BOATS) {
            List<WeakReference<Entity>> tracked = BOATS.get(level);
            if (tracked != null) {
                tracked.removeIf(ref -> {
                    Entity entity = ref.get();
                    return entity == null || entity.isRemoved() || entityLevel(entity) != level;
                });
                if (tracked.isEmpty()) BOATS.remove(level);
            }
        }
    }

    private static BoatFluidMaterial material(Fluid fluid) {
        if (fluid == Fluids.EMPTY) return null;
        BoatFluidMaterial value = MATERIALS.get(fluid);
        if (value != null) return value;
//? if <1.19.3 {
/*?
        ResourceLocation id = Registry.FLUID.getKey(fluid);
?*/
//?} else {
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
//?}
        if (id == null || !"buildcraftenergy".equals(id.getNamespace())) return null;
        value = BoatFluidMaterial.fromId(id.getNamespace(), id.getPath());
        if (value != null) MATERIALS.put(fluid, value);
        return value;
    }

    private static void updateBoat(Entity boat, Level level) {
        AABB bounds = boat.getBoundingBox();
        double x = (bounds.minX + bounds.maxX) * 0.5;
        double z = (bounds.minZ + bounds.maxZ) * 0.5;
        double[] xs = {x, bounds.minX + 0.15, bounds.maxX - 0.15,
            bounds.minX + 0.15, bounds.maxX - 0.15};
        double[] zs = {z, bounds.minZ + 0.15, bounds.minZ + 0.15,
            bounds.maxZ - 0.15, bounds.maxZ - 0.15};
        int baseY = Mth.floor(bounds.minY - 0.30);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int liquidSamples = 0;
        double totalDrag = 0.0;
        double totalFloatStrength = 0.0;
        double surface = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < xs.length; i++) {
            int blockX = Mth.floor(xs[i]);
            int blockZ = Mth.floor(zs[i]);
            pos.set(blockX, baseY, blockZ);
            if (!level.isLoaded(pos)) continue;
            FluidState state = level.getFluidState(pos);
            BoatFluidMaterial data = material(state.getType());
            if (data == null || data.isGas()) continue;
            double localSurface = baseY + state.getHeight(level, pos);
            // Ignore liquid underneath a solid floor or well below an airborne boat.
            if (localSurface < bounds.minY - 0.35) continue;
            liquidSamples++;
            totalDrag += data.horizontalDrag();
            totalFloatStrength += data.floatStrength();
            // A boat can be immersed in a deeper column; find the actual upper
            // surface rather than treating every full liquid block as the surface.
            double highest = localSurface;
            for (int offset = 1; offset <= 5; offset++) {
                pos.set(blockX, baseY + offset, blockZ);
                if (!level.isLoaded(pos)) break;
                FluidState above = level.getFluidState(pos);
                BoatFluidMaterial aboveMaterial = material(above.getType());
                if (aboveMaterial == null || aboveMaterial.isGas()) break;
                highest = baseY + offset + above.getHeight(level, pos);
            }
            surface = Math.max(surface, highest);
        }
        if (liquidSamples == 0) return;

        double coverage = (double) liquidSamples / xs.length;
        double drag = 1.0 - coverage * (1.0 - totalDrag / liquidSamples);
        Vec3 velocity = boat.getDeltaMovement();
        double newY = velocity.y;
        // Platforms without native custom-fluid boating support enable extra lift.
        // Approximate hydrostatic lift near the surface and recover submerged boats.
        if (customBuoyancyEnabled && Double.isFinite(surface) && !boatOnGround(boat)) {
            double error = Mth.clamp(surface - 0.18 - bounds.minY, -0.35, 2.0);
            double strength = totalFloatStrength / liquidSamples;
            double acceleration = coverage * strength * (0.046 + error * 0.12)
                - 0.26 * velocity.y;
            newY = Mth.clamp(velocity.y + acceleration, -0.36, 0.13);
        }
        boat.setDeltaMovement(velocity.x * drag, newY, velocity.z * drag);
    }
}
