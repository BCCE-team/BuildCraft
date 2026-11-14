/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.energy.tile;

import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.compat.transfer.TransferJournal;

/**
 * Internal combustion-engine tank operations. External Fabric transfer views
 * use the TankManager's IndexedFluidHandler contract and its transaction
 * journal; these helpers deliberately bypass the external I/O face policy.
 */
public final class EnergyFluidResourceHandler {
    private EnergyFluidResourceHandler() {}

    /** Insert residue produced by combustion, even though residue is output-only. */
    static int insertInternal(Tank tank, FluidStack offered, TileEngineIron_BC8 engine) {
        if (offered == null || offered.isEmpty() || !tank.isFluidValid(offered)) return 0;
        FluidStack existing = tank.getFluid();
        if (!existing.isEmpty() && !FluidStack.isSameFluidSameComponents(existing, offered)) return 0;
        int allowed = Math.min(offered.getAmount(), Math.max(0, tank.getCapacity() - tank.getFluidAmount()));
        if (allowed <= 0) return 0;
        int inserted = tank.fillInternal(offered.copyWithAmount(allowed), FluidAction.EXECUTE);
        if (inserted > 0) TransferJournal.notifyAfterCommit(engine::markChunkDirty);
        return inserted;
    }

    /** Internal engine consumption bypasses the external fill-only restriction. */
    static FluidStack drainInternal(Tank tank, int requested, TileEngineIron_BC8 engine) {
        if (requested <= 0) return FluidStack.EMPTY;
        FluidStack drained = tank.drainInternal(requested, FluidAction.EXECUTE);
        if (!drained.isEmpty()) TransferJournal.notifyAfterCommit(engine::markChunkDirty);
        return drained;
    }
}
