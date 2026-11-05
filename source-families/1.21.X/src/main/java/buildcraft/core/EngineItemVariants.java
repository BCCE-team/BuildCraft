/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core;

import buildcraft.core.block.BlockEngine_BC8;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.item.MultiBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Resolves the actual registered engine item from its captured block-state variant.
 * Every engine variant shares a Block, so Block#asItem() cannot identify it.
 */
public final class EngineItemVariants {
    private EngineItemVariants() {}

    public static ItemStack fromState(BlockState state) {
        if (state == null || !(state.getBlock() instanceof BlockEngine_BC8 engine)) {
            return ItemStack.EMPTY;
        }
        EnumEngineType variant = state.getValue(engine.getEngineProperty());
        MultiBlockItem<EnumEngineType> item = BCCoreItems.ENGINE_ITEM_MAP.get(variant);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
