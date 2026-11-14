package buildcraft.lib.internal.tiles;

import buildcraft.lib.platform.capability.BlockCapability;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * Stable BCCE tile capability identities. Internal lookups use the loader-neutral
 * BlockCapability token; {@link #fabricLookup(BlockCapability)} exposes the same
 * endpoint to Fabric addons through its native BlockApiLookup registry.
 */
public final class TilesAPI {
    public static final BlockCapability<IControllable, Direction> CAP_CONTROLLABLE =
        token("controllable", IControllable.class);
    public static final BlockCapability<IHasWork, Direction> CAP_HAS_WORK =
        token("has_work", IHasWork.class);
    public static final BlockCapability<IHeatable, Direction> CAP_HEATABLE =
        token("heatable", IHeatable.class);
    public static final BlockCapability<ITileAreaProvider, Direction> CAP_TILE_AREA_PROVIDER =
        token("tile_area_provider", ITileAreaProvider.class);

    private TilesAPI() {}

    private static <T> BlockCapability<T, Direction> token(String path, Class<T> type) {
        return BlockCapability.createSided(
            Identifier.fromNamespaceAndPath("buildcraftlib", path), type);
    }

    public static <T> BlockApiLookup<T, Direction> fabricLookup(BlockCapability<T, Direction> capability) {
        return BlockApiLookup.get(capability.id(), capability.type(), Direction.class);
    }
}
