package buildcraft.api.v2.machine;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Standalone machine bridge for addon block entities. Host methods run on the owning level thread. */
public interface MachineHost extends MachineExecutionContext, MachineControl {
    <C extends MachineComponent> Optional<C> component(ResourceLocation id, Class<C> type);
    /** Restore bytes before the first onLoad/tick. A missing key is an empty byte array. */
    void restoreState(byte[] saved);
    /** Store the result as a byte-array entry in the owner's regular block-entity NBT. */
    byte[] saveState();
    /** Write on initial tracking and on reconnect; contains no server-side component objects. */
    byte[] fullSync();
    /** Empty array indicates no change; owner transmits nonempty payloads via its standard BE packet. */
    byte[] changedSync();
    /** Client-side; malformed or stale packets do not partially update presentation. */
    void applySync(byte[] packet) throws IOException;
    void onLoad();
    void tick();
    void onChunkUnloaded();
    /** Call before invalidation when dropping the actual block. */
    void addDrops(List<ItemStack> drops);
    void removed();
    void invalidate();
    /** Server-only; requires owner.canPlayerUse(actor) and at most 4 KiB of action data. */
    boolean performAction(Player actor, ResourceLocation action, byte[] payload);
}
