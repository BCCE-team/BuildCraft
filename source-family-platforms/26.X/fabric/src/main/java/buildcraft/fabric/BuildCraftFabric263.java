package buildcraft.fabric;

import buildcraft.lib.BCLib;
import buildcraft.core.BCCore;
import buildcraft.energy.BCEnergy;
import buildcraft.factory.BCFactory;
import buildcraft.builders.BCBuilders;
import buildcraft.robotics.BCRobotics;
import buildcraft.silicon.BCSilicon;
import net.fabricmc.api.ModInitializer;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.FabricServerState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerPickItemEvents;
import net.minecraft.world.InteractionResult;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.net.MessagePipeAttachmentAttack;

/**
 * Fabric 26.3 module bootstrap. Registration is in the native Fabric
 * registries; modules are bootstrapped in dependency order before finalization.
 */
public final class BuildCraftFabric263 implements ModInitializer {
    public void onInitialize() {
        MessageManager.registerPayloads();
        BCLib.bootstrap();
        BCCore.bootstrap();
        BCEnergy.bootstrap();
        buildcraft.transport.BCTransport.bootstrap();
        BCSilicon.bootstrap();
        BCFactory.bootstrap();
        BCBuilders.bootstrap();
        BCRobotics.bootstrap();
        // A vanilla START_DESTROY_BLOCK packet must never be sent for an attachment.
        // In Creative it can remove the client-side pipe before the server has only
        // detached the pluggable, leaving an invisible, partially collidable ghost.
        // Send an explicit, server-validated attachment request instead. The FAIL
        // result cancels *both* vanilla mining and the vanilla destruction packet.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, face) -> {
            if (player.isSpectator()) return InteractionResult.PASS;
            if (!(world.getBlockState(pos).getBlock() instanceof BlockPipeHolder pipe)
                || !pipe.targetsAttachment(world, pos, player)) return InteractionResult.PASS;
            if (world.isClientSide()) {
                MessageManager.sendToServer(new MessagePipeAttachmentAttack(pos));
                player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
            }
            return InteractionResult.FAIL;
        });
        // Pick block must resolve its sub-part on the server rather than always cloning the pipe.
        PlayerPickItemEvents.BLOCK.register((player, pos, state, includeData) -> {
            if (state.getBlock() instanceof BlockPipeHolder pipe) {
                return pipe.getCloneItemStack(player.level(), pos, state, includeData, player);
            }
            return null;
        });

        // Module-dependent registries and marker caches must be finalized only
        // after the last installed BCCE gameplay module has bootstrapped.
        BCLib.finishModuleRegistration();
        // Fabric calls addons' ModInitializer instances after their BCCE dependency.
        // Seal extension registries at server startup, not in our initializer.
        // Do not scan facade materials during SERVER_STARTING: saved facades
        // retain their BlockState even before BCSilicon's SERVER_STARTED scan.
        ServerLifecycleEvents.SERVER_STARTING.register(server -> BCLib.finishApiRegistration());
        ServerLifecycleEvents.SERVER_STARTED.register(FabricServerState::started);
        ServerLifecycleEvents.SERVER_STOPPED.register(FabricServerState::stopped);
    }
}
