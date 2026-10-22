/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net.cache;

import buildcraft.lib.misc.FluidStackUtil;
import java.io.IOException;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

public class NetworkedFluidStackCache extends NetworkedObjectCache<FluidStack> {
    private static final int FLUID_AMOUNT = 1;

    public NetworkedFluidStackCache() {
        super(() -> new FluidStack(Fluids.WATER, FLUID_AMOUNT));
    }

    protected Object2IntMap<FluidStack> createObject2IntMap() {
        return new Object2IntOpenCustomHashMap<>(new Hash.Strategy<FluidStack>() {
            public int hashCode(FluidStack o) {
                if (o == null) {
                    return 0;
                }
                return FluidStack.hashFluidAndComponents(o);
            }

            public boolean equals(FluidStack a, FluidStack b) {
                if (a == null || b == null) {
                    return a == b;
                }
                return FluidStack.isSameFluidSameComponents(a, b);
            }
        });
    }

    protected FluidStack copyOf(FluidStack object) {
        return object.copy();
    }

    protected void writeObject(FluidStack obj, FriendlyByteBuf buffer) {
        FluidStackUtil.write(buffer, obj);
    }

    protected FluidStack readObject(FriendlyByteBuf buffer) throws IOException {
        return FluidStackUtil.read(buffer);
    }

    protected String getCacheName() {
        return "FluidStack";
    }
}
