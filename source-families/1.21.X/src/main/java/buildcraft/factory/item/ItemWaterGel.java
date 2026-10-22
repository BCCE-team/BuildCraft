/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.item;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.block.BlockWaterGel;
import buildcraft.factory.block.BlockWaterGel.GelStage;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

public class ItemWaterGel extends Item {

    public ItemWaterGel() {
        super(new Item.Properties().stacksTo(16));
    }
    
    @Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult ray = getPlayerPOVHitResult(world, player, ClipContext.Fluid.SOURCE_ONLY);//start, end, true, false, true);

        if (ray == null || ray.getBlockPos() == null) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
        }

        Block b = world.getBlockState(ray.getBlockPos()).getBlock();

        if (b != Blocks.WATER) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
        }
        
        // Check the actual server-side placement result before consuming the gel.
        if (world.isClientSide) {
            return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
        }
        if (!world.mayInteract(player, ray.getBlockPos())
            || !player.mayUseItemAt(ray.getBlockPos(), ray.getDirection(), stack)) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
        }
        if (!world.setBlockAndUpdate(ray.getBlockPos(),
            BCFactoryBlocks.WATER_GEL.get().defaultBlockState().setValue(BlockWaterGel.PROP_STAGE, GelStage.SPREAD_0))) {
            return new InteractionResultHolder<>(InteractionResult.FAIL, stack);
        }
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        world.playSound(player, player.getX(), player.getY(), player.getZ(), SoundEvents.SNOWBALL_THROW,
            SoundSource.NEUTRAL, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F));

        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
	}


}
