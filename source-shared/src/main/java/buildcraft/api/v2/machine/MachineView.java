package buildcraft.api.v2.machine;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

public interface MachineView {
    ResourceLocation typeId();
    BlockPos position();
    WorkStatus workStatus();
    /** Whether there is work available, even if the machine is currently awaiting energy. */
    default boolean hasWork() {
        return switch (workStatus().state()) {
            case RUNNING, BLOCKED -> true;
            default -> false;
        };
    }
    Collection<MachineComponent> machineComponents();
    Optional<MachineControl> control();
    default Optional<ItemPort> itemPort(Direction side) { return Optional.empty(); }
    default Optional<FluidPort> fluidPort(Direction side) { return Optional.empty(); }
    default Optional<MjPort> mjPort(Direction side) { return Optional.empty(); }
    default Optional<ExternalEnergyPort> externalEnergyPort(Direction side) { return Optional.empty(); }
    default Optional<HeatPort> heatPort() { return Optional.empty(); }
    default Optional<MachineAreaView> area() { return Optional.empty(); }
}
