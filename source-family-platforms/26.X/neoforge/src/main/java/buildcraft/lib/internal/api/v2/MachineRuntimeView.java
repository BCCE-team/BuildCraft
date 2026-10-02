package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.machine.MachineComponent;
import buildcraft.api.v2.machine.MachineControl;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.machine.MachineView;
import buildcraft.api.v2.machine.WorkState;
import buildcraft.api.v2.machine.WorkStatus;
import buildcraft.lib.internal.api.v2.energy.MjRuntimeLookup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;

/**
 * Internal adapter for existing BCCE machine block entities while their implementation remains module-owned.
 * Addons see only the public {@link MachineView} contract.
 */
public interface MachineRuntimeView extends MachineView {
    Identifier api2MachineTypeId();

    default BlockPos api2MachinePosition() {
        if (this instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            return blockEntity.getBlockPos();
        }
        throw new IllegalStateException("MachineRuntimeView must be implemented by a block entity or override api2MachinePosition()");
    }

    /** Existing machines may override this as richer state becomes available. */
    default WorkStatus api2WorkStatus() {
        return new WorkStatus(WorkState.IDLE, 0.0, "");
    }

    default Identifier typeId() {
        return api2MachineTypeId();
    }

    default BlockPos position() {
        return api2MachinePosition();
    }

    default WorkStatus workStatus() {
        return api2WorkStatus();
    }

    default Collection<MachineComponent> machineComponents() {
        MachineType type = BuildCraftApi.registry(BuildCraftRegistries.MACHINE_TYPES).get(typeId());
        if (type == null) return List.of();
        List<MachineComponent> result = new ArrayList<>();
        for (Identifier componentId : type.components()) {
            result.add(() -> componentId);
        }
        return List.copyOf(result);
    }

    default Optional<MachineControl> control() {
        return Optional.empty();
    }

    default Optional<MjPort> mjPort(Direction side) {
        if (this instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity
            && blockEntity.getLevel() != null) {
            return MjRuntimeLookup.port(blockEntity.getLevel(), blockEntity.getBlockPos(), side);
        }
        return Optional.empty();
    }
}
