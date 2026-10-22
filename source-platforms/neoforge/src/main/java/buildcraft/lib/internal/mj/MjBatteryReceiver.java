/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.internal.mj;

import javax.annotation.Nonnull;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjTransferResult;

import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjReadable;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.internal.mj.MjBattery;

import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public class MjBatteryReceiver implements IMjReceiver, IMjReadable, MjPort {
    private final MjBattery battery;

    public MjBatteryReceiver(MjBattery battery) {
        this.battery = battery;
    }

    public MjBattery battery() { return battery; }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public boolean canConnect(@Nonnull IMjConnector other) {
        return true;
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public long getPowerRequested() {
        return battery.getCapacity() - battery.getStored();
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public long receivePower(long microJoules, FluidAction simulate) {
        return battery.addPowerChecking(microJoules, simulate);
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public long getStored() {
        return battery.getStored();
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public long getCapacity() {
        return battery.getCapacity();
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public MjTransferResult insert(MjAmount offered, OperationMode mode) {
        long remainder = receivePower(offered.microMj(), mode == OperationMode.EXECUTE ? FluidAction.EXECUTE : FluidAction.SIMULATE);
        remainder = Math.max(0L, Math.min(offered.microMj(), remainder));
        return MjTransferResult.of(offered, MjAmount.ofMicro(offered.microMj() - remainder));
    }

//? if mc_26_x {
//? } else {
    @Override
//?}
    public MjTransferResult extract(MjAmount requested, OperationMode mode) {
        return MjTransferResult.none(requested);
    }

//? if mc_26_x {
    public MjAmount stored() { return MjAmount.ofMicro(Math.max(0L, getStored())); }
    public MjAmount capacity() { return MjAmount.ofMicro(Math.max(0L, getCapacity())); }
    public boolean canExtract() { return false; }
//? } else {
    @Override public MjAmount stored() { return MjAmount.ofMicro(Math.max(0L, getStored())); }
    @Override public MjAmount capacity() { return MjAmount.ofMicro(Math.max(0L, getCapacity())); }
    @Override public boolean canExtract() { return false; }
//?}
}
