package buildcraft.api.v2.machine;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Platform boundary implemented by an addon-owned block entity; no BCCE implementation imports required.
 *
 * <p>Create a host with MachineService.createHost after the owner knows its logical side.
 * On NBT load, call restoreState before onLoad. Store saveState alongside the owner's regular
 * inventories and tanks. The owner's ticker calls host.tick; unload calls onChunkUnloaded,
 * and final removal calls addDrops, removed and invalidate. machineChanged must mark the
 * chunk dirty and schedule the owner's normal tracking packet; that packet can carry
 * host.changedSync(). A new tracking client must receive host.fullSync().
 *
 * <p>Menus/screens are registered by the addon through normal Minecraft APIs. Their
 * client model reads workStatus/presentation and their server menu handlers call
 * performAction after verifying interaction permissions with canPlayerUse.
 */
public interface MachineHostOwner {
    BlockPos position();
    /** May be null before the block entity has been added to a world. */
    Level level();
    /** Fixed logical side of the owner, including while level() is null during deserialization. */
    boolean clientSide();
    /** Mark NBT dirty and arrange normal block-entity sync for observing clients. */
    void machineChanged();
    default Optional<ItemPort> itemPort(Direction side) { return Optional.empty(); }
    default Optional<FluidPort> fluidPort(Direction side) { return Optional.empty(); }
    default Optional<MjPort> mjPort(Direction side) { return Optional.empty(); }
    default Optional<ExternalEnergyPort> externalEnergyPort(Direction side) { return Optional.empty(); }
    default Optional<HeatPort> heatPort() { return Optional.empty(); }
    default Optional<MachineAreaView> area() { return Optional.empty(); }
    default Optional<MachineWorldAccess> worldAccess() { return Optional.empty(); }
    /** Called before dispatching an action from a GUI; deny by default. */
    default boolean canPlayerUse(Player actor) { return false; }
}
