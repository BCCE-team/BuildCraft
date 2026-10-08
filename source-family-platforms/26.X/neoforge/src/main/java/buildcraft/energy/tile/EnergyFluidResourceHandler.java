//? source if >=26.3
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.energy.tile;

import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.lib.fluid.Tank;
import java.util.Objects;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Native NeoForge fluid transfer view of the three BuildCraft combustion-engine tanks. */
public final class EnergyFluidResourceHandler implements ResourceHandler<FluidResource> {
    private final TileEngineIron_BC8 engine;
    private final Tank fuel;
    private final Tank coolant;
    private final Tank residue;

    public EnergyFluidResourceHandler(TileEngineIron_BC8 engine, Tank fuel, Tank coolant, Tank residue) {
        this.engine = Objects.requireNonNull(engine);
        this.fuel = Objects.requireNonNull(fuel);
        this.coolant = Objects.requireNonNull(coolant);
        this.residue = Objects.requireNonNull(residue);
    }

    private Tank tank(int index) {
        return switch (index) {
            case 0 -> fuel;
            case 1 -> coolant;
            case 2 -> residue;
            default -> throw new IndexOutOfBoundsException(index);
        };
    }

    public int size() { return 3; }

    public FluidResource getResource(int index) {
        return FluidResource.of(tank(index).getFluid());
    }

    public long getAmountAsLong(int index) {
        return tank(index).getFluidAmount();
    }

    public long getCapacityAsLong(int index, FluidResource resource) {
        Tank target = tank(index);
        return resource.isEmpty() || isValid(index, resource) ? target.getCapacity() : 0;
    }

    public boolean isValid(int index, FluidResource resource) {
        Tank target = tank(index);
        if (resource.isEmpty()) return false;
        return index == 2 ? resource.equals(getResource(2)) : target.isFluidValid(resource.toStack(1));
    }

    public int insert(int index, FluidResource resource, int amount, TransactionContext context) {
        Tank target = tank(index);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0 || index == 2) return 0;
        FluidStack input = resource.toStack(amount);
        if (!target.isFluidValid(input)) return 0;
        try (Transaction transaction = Transaction.open(context)) {
            int inserted = insertInternal(target, input, engine);
            if (inserted > 0) transaction.commit();
            return inserted;
        }
    }

    public int extract(int index, FluidResource resource, int amount, TransactionContext context) {
        Tank target = tank(index);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0 || index != 2 || !resource.equals(FluidResource.of(target.getFluid()))) return 0;
        try (Transaction transaction = Transaction.open(context)) {
            int extracted = drainInternal(target, amount, engine).getAmount();
            if (extracted > 0) transaction.commit();
            return extracted;
        }
    }

    /** Internal insertion used by the engine's residue production as well as external transactions. */
    static int insertInternal(Tank tank, FluidStack offered, TileEngineIron_BC8 engine) {
        if (offered == null || offered.isEmpty() || !tank.isFluidValid(offered)) return 0;
        FluidStack existing = tank.getFluid();
        int stored = existing.isEmpty() ? 0 : existing.getAmount();
        int accepted = Math.min(offered.getAmount(), Math.max(0, tank.getCapacity() - stored));
        if (accepted <= 0) return 0;
        FluidStack updated = existing.isEmpty()
            ? offered.copyWithAmount(accepted)
            : existing.copyWithAmount(stored + accepted);
        tank.setFluid(updated);
        TransferJournal.notifyAfterCommit(engine::markChunkDirty);
        return accepted;
    }

    /** The engine consumes coolant internally; external automation can only extract residue. */
    static FluidStack drainInternal(Tank tank, int requested, TileEngineIron_BC8 engine) {
        FluidStack existing = tank.getFluid();
        if (requested <= 0 || existing == null || existing.isEmpty()) return FluidStack.EMPTY;
        int extracted = Math.min(requested, existing.getAmount());
        if (extracted <= 0) return FluidStack.EMPTY;
        FluidStack result = existing.copyWithAmount(extracted);
        int remaining = existing.getAmount() - extracted;
        tank.setFluid(remaining == 0 ? FluidStack.EMPTY : existing.copyWithAmount(remaining));
        TransferJournal.notifyAfterCommit(engine::markChunkDirty);
        return result;
    }
}
