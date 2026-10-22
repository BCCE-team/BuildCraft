/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics;

import buildcraft.lib.misc.FluidStackUtil;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;

/** NBT compatibility helpers for pre-1.21 BuildCraft robot data. */
public final class RoboticsNbtUtil {
    private RoboticsNbtUtil() {
    }

    public static BlockPos readBlockPos(CompoundTag parent, String key) {
        return NbtUtils.readBlockPos(parent, key)
            .or(() -> tryReadBlockPos(parent.get(key)))
            .orElse(BlockPos.ZERO);
    }

    /** Reads Forge fluid NBT. The registry argument is retained for call-site compatibility. */
    public static FluidStack readFluidStack(HolderLookup.Provider registries, CompoundTag nbt) {
        return FluidStackUtil.parseOptional(registries, nbt);
    }

    private static Optional<BlockPos> tryReadBlockPos(Tag tag) {
        if (tag instanceof IntArrayTag arrayTag) {
            int[] values = arrayTag.getAsIntArray();
            if (values.length == 3) {
                return Optional.of(new BlockPos(values[0], values[1], values[2]));
            }
        } else if (tag instanceof CompoundTag compound) {
            if (hasCoordinates(compound, "X", "Y", "Z")) {
                return Optional.of(new BlockPos(compound.getInt("X"), compound.getInt("Y"), compound.getInt("Z")));
            }
            if (hasCoordinates(compound, "x", "y", "z")) {
                return Optional.of(new BlockPos(compound.getInt("x"), compound.getInt("y"), compound.getInt("z")));
            }
            if (hasCoordinates(compound, "i", "j", "k")) {
                return Optional.of(new BlockPos(compound.getInt("i"), compound.getInt("j"), compound.getInt("k")));
            }
            if (compound.contains("pos")) {
                return tryReadBlockPos(compound.get("pos"));
            }
        }
        return Optional.empty();
    }

    private static boolean hasCoordinates(CompoundTag tag, String x, String y, String z) {
        return tag.contains(x, Tag.TAG_ANY_NUMERIC)
            && tag.contains(y, Tag.TAG_ANY_NUMERIC)
            && tag.contains(z, Tag.TAG_ANY_NUMERIC);
    }
}
