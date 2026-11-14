/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.robotics.BCRobotics;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsClientGuis;
import buildcraft.robotics.BCRoboticsClientRenderers;
import buildcraft.robotics.BCRoboticsModels;
import buildcraft.robotics.BCRoboticsSprites;
import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.ItemOwner;

/** Registrations must remain in the physical client entrypoint only. */
public final class FabricRoboticsClient {
    private FabricRoboticsClient() {}
    private static boolean initialized;
    public static synchronized void bootstrap() {
        if (initialized) return;
        initialized = true;
        BCRobotics.bootstrapClientNetwork();
        BCRoboticsSprites.preInit();
        BCRoboticsModels.init();
        BCRoboticsClientGuis.clientInit(PlatformClientRegistration.screens());
        BCRoboticsClientRenderers.register(PlatformClientRegistration.renderers());
        PlatformClientRegistration.rangeProperties().register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("buildcraftrobotics", "robot"),
            RobotModelProperty.MAP_CODEC);
        PlatformClientRegistration.rangeProperties().register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("buildcraftrobotics", "board"),
            BoardModelProperty.MAP_CODEC);
    }
    public record RobotModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<RobotModelProperty> MAP_CODEC = MapCodec.unit(new RobotModelProperty());
        public float get(net.minecraft.world.item.ItemStack stack, @Nullable ClientLevel level,
            @Nullable ItemOwner owner, int seed) {
            return BCRoboticsBoards.getRobotModelValue(stack);
        }
        public MapCodec<RobotModelProperty> type() { return MAP_CODEC; }
    }
    public record BoardModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<BoardModelProperty> MAP_CODEC = MapCodec.unit(new BoardModelProperty());
        public float get(net.minecraft.world.item.ItemStack stack, @Nullable ClientLevel level,
            @Nullable ItemOwner owner, int seed) {
            return BCRoboticsBoards.getBoardModelValue(stack);
        }
        public MapCodec<BoardModelProperty> type() { return MAP_CODEC; }
    }
}
