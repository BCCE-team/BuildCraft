/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.tooltip;

import javax.annotation.Nullable;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * One-entry cache of a synthetic 1x1 Blueprint for a recorded single-block scheme.
 * Compare serialized schematic data, not ISchematicBlock object identity: repeated
 * tooltip deserialization would otherwise invalidate 26.x PiP caches every frame.
 * Client render thread only; never changes the original ItemStack or the world.
 */
public final class SingleSchematicPreviewCache {
    @Nullable private static Tag cachedTag;
    @Nullable private static Blueprint cachedPreview;

    private SingleSchematicPreviewCache() { }

    @Nullable
    public static Blueprint previewFor(ItemStack stack) {
        if (!ItemSchematicSingle.isUsed(stack)) return null;
        CompoundTag customData = ItemStackUtil.getCustomDataOrNull(stack);
        Tag schematicTag = customData == null ? null : customData.get(ItemSchematicSingle.NBT_KEY);
        if (!(schematicTag instanceof CompoundTag)) {
            clear();
            return null;
        }
        if (cachedTag != null && cachedTag.equals(schematicTag)) return cachedPreview;
        cachedTag = schematicTag.copy();
        cachedPreview = null;
        try {
            ISchematicBlock block = ItemSchematicSingle.getSchematicSafe(stack);
            if (block == null || block.isAir()) return null;
            Blueprint blueprint = new Blueprint();
            blueprint.size = new BlockPos(1, 1, 1);
            blueprint.facing = Direction.NORTH;
            blueprint.offset = BlockPos.ZERO;
            blueprint.data = new int[] { 0 };
            blueprint.palette.add(block);
            cachedPreview = blueprint;
        } catch (RuntimeException invalidOrMissingAddon) {
            // Unavailable/malformed schematic data must never break other tooltips.
        }
        return cachedPreview;
    }

    public static void clear() {
        cachedTag = null;
        cachedPreview = null;
    }
}
