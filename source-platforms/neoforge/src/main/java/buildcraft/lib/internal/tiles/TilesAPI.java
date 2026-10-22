package buildcraft.lib.internal.tiles;

import javax.annotation.Nonnull;

import net.minecraft.core.Direction;
//? if mc_26_x {
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.neoforged.neoforge.capabilities.BlockCapability;

public class TilesAPI {
    @Nonnull
    public static final BlockCapability<IControllable, Direction> CAP_CONTROLLABLE =
        BlockCapability.createSided(id("controllable"), IControllable.class);

    @Nonnull
    public static final BlockCapability<IHasWork, Direction> CAP_HAS_WORK =
        BlockCapability.createSided(id("has_work"), IHasWork.class);

    @Nonnull
    public static final BlockCapability<IHeatable, Direction> CAP_HEATABLE =
        BlockCapability.createSided(id("heatable"), IHeatable.class);

    @Nonnull
    public static final BlockCapability<ITileAreaProvider, Direction> CAP_TILE_AREA_PROVIDER =
        BlockCapability.createSided(id("tile_area_provider"), ITileAreaProvider.class);

//? if mc_26_x {
    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("buildcraftlib", path);
//? } else {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("buildcraftlib", path);
//?}
    }

    private TilesAPI() {
    }
}
