//? source if >=1.21.11
package buildcraft.lib.internal.capabilities;

import buildcraft.lib.internal.mj.MjCapabilities;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import buildcraft.lib.misc.CapUtil;

/** Helpers for exposing BuildCraft block-entity capabilities through NeoForge. */
public final class BCCapabilityRegistration {
    private BCCapabilityRegistration() {
    }

    public static <T, BE extends BlockEntity & IBCCapabilityProvider> void registerBlockEntity(
        RegisterCapabilitiesEvent event,
        BlockCapability<T, Direction> capability,
        BlockEntityType<BE> blockEntityType
    ) {
        event.registerBlockEntity(
            capability,
            blockEntityType,
            (blockEntity, side) -> blockEntity.getCapability(capability, side)
        );
        // Keep the BC-internal capabilities for addons and publish standard NeoForge capabilities
        // from the SAME sided storage. Re-resolve the provider on use (rotation/config/removal).
        if (capability == CapUtil.CAP_ITEMS) {
            event.registerBlockEntity(Capabilities.Item.BLOCK, blockEntityType, (be, side) ->
                be.getCapability(CapUtil.CAP_ITEMS, side) == null ? null :
                    buildcraft.lib.compat.transfer.TransferInterop.exportItems(() ->
                        be.isRemoved() ? null : be.getCapability(CapUtil.CAP_ITEMS, side), be::setChanged));
        }
        if (capability == CapUtil.CAP_FLUIDS) {
            event.registerBlockEntity(Capabilities.Fluid.BLOCK, blockEntityType, (be, side) ->
                be.getCapability(CapUtil.CAP_FLUIDS, side) == null ? null :
                    buildcraft.lib.compat.transfer.TransferInterop.exportFluids(() ->
                        be.isRemoved() ? null : be.getCapability(CapUtil.CAP_FLUIDS, side), be::setChanged));
        }
        // MjCapabilityHelper applies automatic conversion policy; FE Engine/Dynamo retain
        // their dedicated input/output ports and Dynamo's output-face restriction.
        if (capability == MjCapabilities.CAP_RECEIVER) {
            event.registerBlockEntity(CapUtil.CAP_FE, blockEntityType,
                (be, side) -> be.getCapability(CapUtil.CAP_FE, side));
        }
        if (capability == MjCapabilities.CAP_RECEIVER || capability == CapUtil.CAP_FE) {
            event.registerBlockEntity(Capabilities.Energy.BLOCK, blockEntityType, (be, side) ->
                be.getCapability(CapUtil.CAP_FE, side) == null ? null :
                    buildcraft.lib.compat.transfer.TransferInterop.exportEnergy(() ->
                        be.isRemoved() ? null : be.getCapability(CapUtil.CAP_FE, side), be::setChanged));
        }
    }
}
