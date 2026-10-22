package buildcraft.robotics;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class BCRoboticsEntities {
    public static final BCDeferredRegister<EntityType<?>> ENTITIES = BCDeferredRegister.create("minecraft:entity_type", BCRobotics.MODID);

    public static final BCRegistryEntry<EntityType<EntityRobot>> ROBOT = ENTITIES.register("robot", () ->
            EntityType.Builder.<EntityRobot>of(EntityRobot::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(BCRobotics.MODID + ":robot"));

    private BCRoboticsEntities() {
    }

    public static void registry(BCRegistryBinder bus) {
        ENTITIES.register(bus);
    }
}
