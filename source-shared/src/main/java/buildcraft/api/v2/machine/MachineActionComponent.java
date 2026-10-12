package buildcraft.api.v2.machine;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/** Server-side menu action. The host checks authorization and bounds the payload first. */
@FunctionalInterface
public interface MachineActionComponent {
    boolean handleAction(MachineExecutionContext machine, Player actor, ResourceLocation action, byte[] payload);
}
