/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * Licensed under the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.fluid;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;

/**
 * Fabric replacement for the legacy BCCE fluid-stack value, in millibuckets.
 *
 * <p>Unlike a Fabric Transfer storage view, this is an owned, mutable value.
 * Components belong to the fluid identity: copies, comparisons, transfers,
 * and network/NBT encoding must retain the entire {@link DataComponentPatch}.
 * A single shared EMPTY instance is never mutable.</p>
 */
public final class FluidStack {
    public static final FluidStack EMPTY = new FluidStack(Fluids.EMPTY, 0, DataComponentPatch.EMPTY);

    private final Fluid fluid;
    private final DataComponentPatch components;
    private int amount;

    public FluidStack(Fluid fluid, int amount) {
        this(fluid, amount, DataComponentPatch.EMPTY);
    }

    public FluidStack(Holder<Fluid> fluid, int amount, DataComponentPatch components) {
        this(fluid.value(), amount, components);
    }

    public FluidStack(Fluid fluid, int amount, DataComponentPatch components) {
        this.fluid = Objects.requireNonNull(fluid, "fluid");
        this.components = Objects.requireNonNull(components, "components");
        this.amount = Math.max(0, amount);
    }

    public Fluid getFluid() { return fluid; }
    public int getAmount() { return isEmpty() ? 0 : amount; }
    public boolean isEmpty() { return fluid == Fluids.EMPTY || amount <= 0; }
    public DataComponentPatch getComponentsPatch() { return components; }
    public DataComponentPatch getComponents() { return components; }

    @Nullable
    public <T> T get(DataComponentType<T> type) {
        return components.get(net.minecraft.core.component.DataComponentMap.EMPTY, type);
    }

    public void setAmount(int amount) {
        if (this == EMPTY) throw new IllegalStateException("Cannot modify the shared empty fluid stack");
        this.amount = Math.max(0, amount);
    }
    public void grow(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative fluid growth");
        setAmount(Math.addExact(this.amount, amount));
    }
    public void shrink(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative fluid shrink");
        setAmount(Math.max(0, this.amount - amount));
    }

    public FluidStack copy() {
        return isEmpty() ? EMPTY : new FluidStack(fluid, amount, components);
    }
    public FluidStack copyWithAmount(int newAmount) {
        return isEmpty() || newAmount <= 0 ? EMPTY : new FluidStack(fluid, newAmount, components);
    }

    /** A localized name, using Fabric's component-aware fluid display service. */
    public Component getHoverName() {
        if (isEmpty()) return Component.empty();
        return FluidVariantAttributes.getName(
            net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid, components));
    }

    public String getDescriptionId() {
        Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
        return id == null ? "fluid.minecraft.empty" : "fluid." + id.getNamespace() + "." + id.getPath();
    }

    public static boolean isSameFluidSameComponents(FluidStack left, FluidStack right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return left.fluid == right.fluid && left.components.equals(right.components);
    }
    public static int hashFluidAndComponents(FluidStack stack) {
        return stack == null ? 0 : Objects.hash(stack.fluid, stack.components);
    }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof FluidStack stack
            && amount == stack.amount && isSameFluidSameComponents(this, stack);
    }
    @Override public int hashCode() { return Objects.hash(fluid, amount, components); }
    @Override public String toString() {
        return "FluidStack[" + BuiltInRegistries.FLUID.getKey(fluid) + "," + getAmount() + "mB," + components + "]";
    }
}
