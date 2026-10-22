//? source if >=1.21.1
package buildcraft.robotics;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public final class BCRoboticsEntities {
    public static final BCDeferredRegister<EntityType<?>> ENTITIES = BCDeferredRegister.create("minecraft:entity_type", BCRobotics.MODID);

    public static final BCRegistryEntry<EntityType<EntityRobot>> ROBOT = ENTITIES.register("robot", () ->
            EntityType.Builder.<EntityRobot>of(EntityRobot::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(BCRobotics.MODID, "robot"))));

    private BCRoboticsEntities() {
    }

    public static void registry(BCRegistryBinder bus) {
        ENTITIES.register(bus);
    }
}
