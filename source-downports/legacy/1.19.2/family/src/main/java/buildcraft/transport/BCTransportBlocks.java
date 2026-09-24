/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.core.BCCore;
import buildcraft.transport.block.BlockFilteredBuffer;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.tile.TileFilteredBuffer;
import buildcraft.transport.tile.TilePipeHolder;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class BCTransportBlocks {

    private static final BCDeferredRegister<Block> BLOCKS = BCDeferredRegister.create("minecraft:block", BCTransport.MODID);
    private static final BCDeferredRegister<BlockEntityType<?>> BET = BCDeferredRegister.create("minecraft:block_entity_type", BCTransport.MODID);

    public static final BCRegistryEntry<BlockFilteredBuffer> filterBuffer = BLOCKS.register("filtered_buffer", BlockFilteredBuffer::new);
    public static final BCRegistryEntry<BlockPipeHolder> pipeHolder = BLOCKS.register("pipe_holder", BlockPipeHolder::new);
    public static final BCRegistryEntry<BlockEntityType<TileFilteredBuffer>> FILTERREDBUFFER_BE = BET.register("entity_filtered_buffer",
            () -> BlockEntityType.Builder.of(TileFilteredBuffer::new, filterBuffer.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TilePipeHolder>> PIPE_HOLDER_BE = BET.register("entity_pipe_holder",
            () -> BlockEntityType.Builder.of(TilePipeHolder::new, pipeHolder.get()).build(null));
    public static final BCRegistryEntry<BlockItem> FILTERED_BUFFER_ITEM = BCTransportItems.ITEMS.register("filtered_buffer", () -> new BlockItem(filterBuffer.get(), new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));

    public static void registry(BCRegistryBinder b) {
        BLOCKS.register(b);
        BET.register(b);
    }
}
