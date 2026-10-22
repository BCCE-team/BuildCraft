package buildcraft.factory;

import buildcraft.lib.client.sprite.SpriteHolderRegistry;
import buildcraft.lib.client.sprite.SpriteHolderRegistry.SpriteHolder;
import net.minecraft.resources.ResourceLocation;

public final class BCFactorySprites {
    public static final SpriteHolder pump_tube =
        SpriteHolderRegistry.getHolder("buildcraftfactory:blocks/pump/tube");
    public static final SpriteHolder mining_tube =
        SpriteHolderRegistry.getHolder("buildcraftfactory:blocks/mining_well/tube");

    public static final ResourceLocation DISTILLER_POWER_A =
        ResourceLocation.parse("buildcraftfactory:blocks/distiller/power_sprite_a");
    public static final ResourceLocation DISTILLER_POWER_B =
        ResourceLocation.parse("buildcraftfactory:blocks/distiller/power_sprite_b");
    public static final ResourceLocation DISTILLER_POWER_C =
        ResourceLocation.parse("buildcraftfactory:blocks/distiller/power_sprite_c");
    public static final ResourceLocation DISTILLER_POWER_D =
        ResourceLocation.parse("buildcraftfactory:blocks/distiller/power_sprite_d");

    public static final ResourceLocation AUTO_BENCH_GUI =
        ResourceLocation.parse("buildcraftfactory:textures/gui/autobench_item.png");
    public static final ResourceLocation HEAT_EXCHANGE =
        ResourceLocation.parse("buildcraftfactory:textures/gui/heat_exchanger.png");

    private BCFactorySprites() {
    }

    /** Forces holder registration before the first resource reload. */
    public static void init() {
    }
}
