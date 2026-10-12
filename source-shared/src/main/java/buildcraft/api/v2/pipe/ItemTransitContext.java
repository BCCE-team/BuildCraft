package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemPort;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Valid only during {@link ItemTransitComponent#onTransit(ItemTransitContext)}.
 * Mutations are accepted at CENTER, ROUTING or EXIT on the server thread. Inspectors always
 * receive copies. A failed transfer leaves the source item owned by its current pipe.
 */
public interface ItemTransitContext {
    PipeView pipe();
    ItemTransitPhase phase();
    ItemStack stack();
    ItemTransitData transit();
    Optional<Direction> input();
    Optional<Direction> output();
    UUID transitId();

    /** Replace an item with an equally sized stack. Cannot manufacture or silently discard items. */
    boolean replace(ItemStack stack, ItemTransitData transit);

    /** Select an actually connected output; effective at CENTER or ROUTING. */
    boolean redirect(Direction side);

    /** Retry the item from the pipe centre after 1-1200 ticks, retaining ownership. */
    boolean delay(int ticks);

    /** Insert part or all of this item into a public port and claim only what it accepted. */
    ItemTransitTransfer claim(ItemPort port, OperationMode mode);

    /** Teleport to an already loaded item pipe. Unloaded destinations are never force-loaded. */
    ItemTransitTransfer transfer(Level targetWorld, BlockPos targetPos, Direction enteringFrom, OperationMode mode);

    /** Retain the item for another routing attempt instead of using its original route. */
    boolean reject();

    /** Spawn the remaining stack at this pipe's face; cannot be used after a successful full claim. */
    boolean eject(Direction side);
}
