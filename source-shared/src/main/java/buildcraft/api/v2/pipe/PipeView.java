package buildcraft.api.v2.pipe;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

public interface PipeView {
    ResourceLocation typeId();
    BlockPos position();
    default boolean isClientSide() { return false; }
    /** Client-only, last decoded value from a registered PipeSyncChannel. */
    default <T> Optional<T> presentation(ResourceLocation channelId, Class<T> type) { return Optional.empty(); }
    Set<Direction> connectedSides();
    default PipeEndpointKind endpoint(Direction side) { return connectedSides().contains(side) ? PipeEndpointKind.BLOCK : PipeEndpointKind.NONE; }
    default Optional<DyeColor> color() { return Optional.empty(); }
    default boolean colorable() { return false; }
    default Map<Direction, PipeAttachment> attachments() { return Map.of(); }
    default Optional<PipeAttachment> attachment(Direction side) { return Optional.ofNullable(attachments().get(side)); }
    Collection<PipeComponent> components();
    Optional<PipeComponent> component(ResourceLocation typeId);
    default Optional<ItemPort> itemPort(Direction side) { return Optional.empty(); }
    default Optional<ItemPipePort> itemPipePort(Direction side) { return Optional.empty(); }
    default Optional<FluidPort> fluidPort(Direction side) { return Optional.empty(); }
    default Optional<MjPort> mjPort(Direction side) { return Optional.empty(); }
    default Optional<ExternalEnergyPort> externalEnergyPort(Direction side) { return Optional.empty(); }
}
