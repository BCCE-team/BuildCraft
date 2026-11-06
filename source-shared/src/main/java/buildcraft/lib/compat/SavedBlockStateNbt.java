/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.lib.compat;

import buildcraft.lib.internal.core.InvalidInputDataException;
//? if >=1.20 {
/*?
import net.minecraft.core.registries.BuiltInRegistries;
?*/
//? }
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stable BuildCraft block-state NBT for schematics and facade state storage.
 *
 * Minecraft 26.3 writes id/properties instead of Name/Properties. Existing
 * BuildCraft saves still contain the legacy spelling, so retain it on disk and
 * normalize a detached copy for the current vanilla reader. Never replace an
 * unparseable block with a different block or mutate the caller's NBT tree.
 */
public final class SavedBlockStateNbt {
    private SavedBlockStateNbt() { }

    public static CompoundTag write(BlockState state) {
        CompoundTag result = NbtUtils.writeBlockState(state);
//? if >=26.3 {
        // BCCE files stay readable by 26.2 and existing .bcnbt utilities.
        moveKey(result, "id", "Name");
        moveKey(result, "properties", "Properties");
//? }
        return result;
    }

    public static BlockState read(CompoundTag saved) throws InvalidInputDataException {
        if (saved == null) {
            throw new InvalidInputDataException("Missing saved block state");
        }
        CompoundTag normalized = saved;
//? if >=26.3 {
        normalized = saved.copy();
        checkConflict(normalized, "Name", "id");
        checkConflict(normalized, "Properties", "properties");
        moveKey(normalized, "Name", "id");
        moveKey(normalized, "Properties", "properties");
        if (!(normalized.get("id") instanceof StringTag)) {
            throw new InvalidInputDataException("Saved block state has no valid block id");
        }
//? }
        //? if <1.20 {
        return NbtUtils.readBlockState(normalized);
        //? } else {
        /*?
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), normalized);
        ?*/
        //? }
    }

    private static void moveKey(CompoundTag tag, String from, String to) {
        Tag oldValue = tag.get(from);
        if (oldValue != null) {
            tag.put(to, oldValue.copy());
            tag.remove(from);
        }
    }

    private static void checkConflict(CompoundTag tag, String oldName, String newName)
        throws InvalidInputDataException {
        Tag oldValue = tag.get(oldName);
        Tag newValue = tag.get(newName);
        if (oldValue != null && newValue != null && !oldValue.equals(newValue)) {
            throw new InvalidInputDataException("Conflicting saved block-state fields: "
                + oldName + " and " + newName);
        }
    }
}
