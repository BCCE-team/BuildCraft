//? source if >=1.21.1
package buildcraft.builders.platform;

import java.util.UUID;

import buildcraft.builders.menu.ContainerFillerPlanner;
import buildcraft.core.marker.volume.EnumAddonSlot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/** NeoForge-specific menu-opening entrypoint; keeps loader APIs out of shared add-on code. */
public final class PlannerMenuOpening {
    private PlannerMenuOpening() {}

    public static void open(ServerPlayer player, UUID id, EnumAddonSlot slot) {
        var provider = new SimpleMenuProvider(
            (window, inventory, viewer) -> new ContainerFillerPlanner(window, inventory, id, slot),
            Component.translatable("item.buildcraftbuilders.filler_planner")
        );
        player.openMenu(provider, buffer -> {
            buffer.writeUUID(id);
            buffer.writeEnum(slot);
        });
    }
}
