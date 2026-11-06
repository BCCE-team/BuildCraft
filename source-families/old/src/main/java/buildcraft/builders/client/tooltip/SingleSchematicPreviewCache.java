/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.tooltip;

import javax.annotation.Nullable;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.snapshot.Blueprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Render-thread-only, single-entry preview cache for a recorded single-block schematic.
 * The NBT is the cache key, NOT ISchematicBlock.equals(): reading the same item can
 * deserialize a new ISchematicBlock each tooltip frame. The synthetic 1x1 Blueprint
 * is intentionally stable so the preview renderer can reuse its model/scene caches.
 * No item, NBT or actual world is ever modified by this class.
 */
public final class SingleSchematicPreviewCache {
    @Nullable private static CompoundTag cachedTag;
    @Nullable private static Blueprint cachedPreview;

    private SingleSchematicPreviewCache() { }

    @Nullable
    public static Blueprint previewFor(ItemStack stack) {
        if (!ItemSchematicSingle.isUsed(stack)) return null;
        CompoundTag itemTag = stack.getTag();
        if (itemTag == null || !itemTag.contains(ItemSchematicSingle.NBT_KEY, 10)) {
            clear();
            return null;
        }
        // Only the recorded schematic matters: unrelated stack metadata should not
        // force a geometry rebuild while the tooltip is open.
        CompoundTag schematicTag = itemTag.getCompound(ItemSchematicSingle.NBT_KEY);
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
            // A malformed item or unavailable addon must not crash inventory UI.
        }
        return cachedPreview;
    }

    public static void clear() {
        cachedTag = null;
        cachedPreview = null;
    }
}
