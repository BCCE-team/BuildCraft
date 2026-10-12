package buildcraft.transport.api2;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.pipe.ItemPipePort;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.api.v2.pipe.PipeComponent;
import buildcraft.api.v2.pipe.PipeEndpointKind;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

/** Prevents a neighbouring pipe lookup from exposing the host's mutation handle. */
public final class ReadOnlyPipeView implements PipeView {
    private final PipeView pipe;

    private ReadOnlyPipeView(PipeView pipe) { this.pipe = pipe; }

    public static PipeView of(PipeView pipe) {
        Objects.requireNonNull(pipe, "pipe");
        return pipe instanceof ReadOnlyPipeView ? pipe : new ReadOnlyPipeView(pipe);
    }

    @Override public ResourceLocation typeId() { return pipe.typeId(); }
    @Override public BlockPos position() { return pipe.position(); }
    @Override public boolean isClientSide() { return pipe.isClientSide(); }
    @Override public <T> Optional<T> presentation(ResourceLocation id, Class<T> type) { return pipe.presentation(id, type); }
    @Override public Set<Direction> connectedSides() { return Set.copyOf(pipe.connectedSides()); }
    @Override public PipeEndpointKind endpoint(Direction side) { return pipe.endpoint(side); }
    @Override public Optional<DyeColor> color() { return pipe.color(); }
    @Override public boolean colorable() { return pipe.colorable(); }
    @Override public Map<Direction, PipeAttachment> attachments() { return Map.copyOf(pipe.attachments()); }
    @Override public Collection<PipeComponent> components() { return List.copyOf(pipe.components()); }
    @Override public Optional<PipeComponent> component(ResourceLocation id) { return pipe.component(id); }
    @Override public Optional<ItemPort> itemPort(Direction side) { return pipe.itemPort(side); }
    @Override public Optional<ItemPipePort> itemPipePort(Direction side) { return pipe.itemPipePort(side); }
    @Override public Optional<FluidPort> fluidPort(Direction side) { return pipe.fluidPort(side); }
    @Override public Optional<MjPort> mjPort(Direction side) { return pipe.mjPort(side); }
    @Override public Optional<ExternalEnergyPort> externalEnergyPort(Direction side) { return pipe.externalEnergyPort(side); }
}
