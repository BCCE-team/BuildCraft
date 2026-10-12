package buildcraft.api.v2.machine;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjTransferResult;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** Server-thread-confined engine controls; MJ is always stored in the owner-provided port. */
public interface EngineExecutionContext extends MachineExecutionContext, EngineView {
    EngineType engineType();
    EngineHeatSettings heatSettings();
    MjPort outputBuffer();
    /** Generate up to the engine profile's per-tick rate and the output buffer's free capacity. */
    MjTransferResult generate(MjAmount requested, OperationMode mode);
    /** Transfer buffered MJ to an adjacent loaded endpoint through the standard EnergyService. */
    MjTransferResult emit(Direction facing, MjAmount maximum, OperationMode mode);
    /** Set the authoritative engine heat; overheating stops further generation. */
    void setTemperature(double value);
    default void addHeat(double delta) { setTemperature(visualState().heat().temperature() + delta); }
    void setStage(EngineStage stage);
    /** Animate the piston; a pulsed receiver can consume at most once per upstroke. */
    void setAnimation(double phase, ResourceLocation soundId);
    EngineVisualState visualState();
}
