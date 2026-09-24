package buildcraft.energy;

import net.minecraft.resources.ResourceLocation;

/** Energy-module sprites are declared in assets/minecraft/atlases/blocks.json on 1.20.1. */
public final class BCEnergySprites {
    public static final ResourceLocation IRON_BACK_R = new ResourceLocation(BCEnergy.MODID, "blocks/engine/iron/back");
    public static final ResourceLocation IRON_SIDE_R = new ResourceLocation(BCEnergy.MODID, "blocks/engine/iron/side");
    public static final ResourceLocation STONE_BACK_R = new ResourceLocation(BCEnergy.MODID, "blocks/engine/stone/back");
    public static final ResourceLocation STONE_SIDE_R = new ResourceLocation(BCEnergy.MODID, "blocks/engine/stone/side");
    public static final ResourceLocation FE_BACK_R = new ResourceLocation(BCEnergy.MODID, "blocks/engine/fe/back");
    public static final ResourceLocation FE_SIDE_R = new ResourceLocation(BCEnergy.MODID, "blocks/engine/fe/side");

    public static final ResourceLocation ENGINE_IRON_GUI = new ResourceLocation(BCEnergy.MODID, "textures/gui/combustion_engine_gui.png");
    public static final ResourceLocation ENGINE_STONE_GUI = new ResourceLocation(BCEnergy.MODID, "textures/gui/steam_engine_gui.png");
    public static final ResourceLocation ENGINE_FE_GUI = new ResourceLocation(BCEnergy.MODID, "textures/gui/fe_engine_gui.png");
    public static final ResourceLocation DYNAMO_MJ_GUI = new ResourceLocation(BCEnergy.MODID, "textures/gui/mj_dynamo_gui.png");

    private BCEnergySprites() {
    }

    public static void init() {
        // Sprite loading is driven by the block-atlas JSON in 1.20.1.
    }
}
