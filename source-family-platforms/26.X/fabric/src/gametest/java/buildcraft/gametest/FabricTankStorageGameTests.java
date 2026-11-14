/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.gametest;

import buildcraft.lib.fluid.FabricTankManager;
import buildcraft.lib.fluid.FabricTankManagerStorage;
import buildcraft.lib.fluid.FabricTankState;
import buildcraft.lib.fluid.FabricTankStorage;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluids;

/**
 * Native Fabric fluid transfer contracts need the real Fabric Loader runtime.
 * Fabric API adds FluidVariantCache to Minecraft Fluid through a mixin;
 * vanilla JUnit does not transform these classes and cannot exercise
 * FluidVariant.of / SingleFluidStorage without ClassCastException.
 *
 * These are the same regression assertions previously executed in plain
 * FabricTankStateTest, now run on the dedicated GameTest server with mixins.
 */
public final class FabricTankStorageGameTests implements CustomTestMethodInvoker {
    private static FluidVolume water(long amount) {
        return FluidVolume.of(
            FluidVariant.of(Identifier.fromNamespaceAndPath("minecraft", "water")),
            FluidAmount.of(amount)
        );
    }

    @GameTest
    public void nativeSingleTankStorageUsesTheCallerTransactionAndDropletUnits(GameTestHelper helper) {
        AtomicInteger notifications = new AtomicInteger();
        FabricTankState state = new FabricTankState("native", 1000, null, notifications::incrementAndGet);
        FabricTankStorage storage = new FabricTankStorage(state, ItemStackUtil.requireActiveRegistryProvider());
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant waterVariant =
            net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(Fluids.WATER);
        long droplets = 250L * FluidConstants.BUCKET / 1000L;

        try (Transaction aborted = Transaction.openOuter()) {
            assertEquals(droplets, storage.insert(waterVariant, droplets, aborted));
            assertEquals(250, state.getFluidAmount());
        }
        assertEquals(0, state.getFluidAmount());
        assertEquals(0, notifications.get());

        try (Transaction committed = Transaction.openOuter()) {
            assertEquals(droplets, storage.insert(waterVariant, droplets, committed));
            committed.commit();
        }
        assertEquals(250, state.getFluidAmount());
        assertEquals(droplets, storage.getAmount());
        assertEquals(waterVariant, storage.getResource());
        assertEquals(1, notifications.get());

        try (Transaction aborted = Transaction.openOuter()) {
            assertEquals(100L * FluidConstants.BUCKET / 1000L,
                storage.extract(waterVariant, 100L * FluidConstants.BUCKET / 1000L, aborted));
        }
        assertEquals(250, state.getFluidAmount());
        assertEquals(1, notifications.get());
        helper.succeed();
    }

    @GameTest
    public void nativeManagerStorageKeepsDistinctFillAndDrainPriorities(GameTestHelper helper) {
        FabricTankState dual = new FabricTankState("dual", 1000, null, () -> {});
        FabricTankState fillOnly = new FabricTankState("fill", 1000, null, () -> {});
        fillOnly.setCanDrain(false);
        FabricTankState drainOnly = new FabricTankState("drain", 1000, null, () -> {});
        drainOnly.setCanFill(false);
        drainOnly.setFluid(water(200));
        FabricTankManager manager = new FabricTankManager(dual, fillOnly, drainOnly);
        FabricTankManagerStorage storage = manager.asFabricStorage(
            ItemStackUtil.requireActiveRegistryProvider());
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant waterVariant =
            net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(Fluids.WATER);
        long amount = 1500L * FluidConstants.BUCKET / 1000L;

        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(amount, storage.insert(waterVariant, amount, transaction));
            transaction.commit();
        }
        assertEquals(1000, fillOnly.getFluidAmount());
        assertEquals(500, dual.getFluidAmount());
        assertEquals(200, drainOnly.getFluidAmount());
        assertEquals(3, storage.getSlotCount());

        long requested = 1000L * FluidConstants.BUCKET / 1000L;
        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(700L * FluidConstants.BUCKET / 1000L,
                storage.extract(waterVariant, requested, transaction));
            transaction.commit();
        }
        assertEquals(1000, fillOnly.getFluidAmount());
        assertEquals(0, dual.getFluidAmount());
        assertEquals(0, drainOnly.getFluidAmount());
        helper.succeed();
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        method.invoke(this, helper);
    }
}
