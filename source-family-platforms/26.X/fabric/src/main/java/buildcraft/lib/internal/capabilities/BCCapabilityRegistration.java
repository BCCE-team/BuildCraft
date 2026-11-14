package buildcraft.lib.internal.capabilities;

import buildcraft.lib.internal.tiles.TilesAPI;
import buildcraft.lib.platform.capability.BlockCapability;
import buildcraft.lib.platform.storage.FabricStorageLookups;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Fabric equivalent of NeoForge's capability registration callback.
 * A BCCE tile implements {@link IBCCapabilityProvider} for internal calls; the
 * exact same scoped value is also exported into Fabric's native BlockApiLookup.
 */
public final class BCCapabilityRegistration {
    private BCCapabilityRegistration() {}

    public static <T, BE extends BlockEntity & IBCCapabilityProvider> void registerBlockEntity(
        BlockCapability<T, Direction> capability, BlockEntityType<BE> type) {
        if (capability == null || type == null) return;
        FabricStorageLookups.registerAddonType(type);
        var lookup = TilesAPI.fabricLookup(capability);
        // Fabric indexes providers by block, not block-entity type. Multiple
        // engine BE types share buildcraftcore:engine; registering each type
        // overwrote the same block entry and produced duplicate warnings.
        // A block-wide provider dispatches to the *actual* BE instance, so all
        // variants keep their individual sided capabilities.
        for (Block block : FabricStorageLookups.validBlocksFor(type)) {
            if (lookup.getProvider(block) != null) continue;
            lookup.registerForBlocks((level, pos, state, blockEntity, side) ->
                blockEntity instanceof IBCCapabilityProvider provider
                    ? provider.getCapability(capability, side) : null, block);
        }
    }
}
