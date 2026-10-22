package buildcraft.api.v2.pipe;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.api.v2.signal.SignalPort;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Side-mounted extension implemented independently of BuildCraft's internal pluggable types. */
public interface PipeAttachment {
    ResourceLocation typeId();
    Direction side();

    default boolean blocksConnection() { return false; }

    /** Whether the attachment contributes a solid side face or accepts a redstone connection. */
    default boolean solidFace() { return false; }
    default boolean canConnectRedstone(Direction from) { return false; }

    /** Sided connection veto/allowance. DENY always wins over a requested connection. */
    default PipeConnectionDecision connection(PipeConnectionContext context) {
        return PipeConnectionDecision.PASS;
    }

    /** Optional ports exposed by this attachment on its mounted side. */
    default Optional<ItemPort> itemPort() { return Optional.empty(); }
    default Optional<ItemPipePort> itemPipePort() { return Optional.empty(); }
    default Optional<FluidPort> fluidPort() { return Optional.empty(); }
    default Optional<MjPort> mjPort() { return Optional.empty(); }
    default Optional<ExternalEnergyPort> externalEnergyPort() { return Optional.empty(); }
    default Optional<? extends SignalPort<?>> signalPort(ResourceLocation channelId) {
        return Optional.empty();
    }

    default VoxelShape boundingBox() { return Shapes.empty(); }

    default ItemStack pickStack() { return ItemStack.EMPTY; }

    /** Called for loot evaluation; may be invoked more than once, so must not consume mutable resources. */
    default List<ItemStack> drops(int fortune) {
        ItemStack item = pickStack();
        return item.isEmpty() ? List.of() : List.of(item.copy());
    }

    default void placed(PipeExecutionContext pipe) { }

    default void restored(PipeExecutionContext pipe) { }

    default void tick(PipeExecutionContext pipe) { }

    default PipeActivationResult activate(PipeActivationContext context) { return PipeActivationResult.PASS; }

    default void removed() { }
}
