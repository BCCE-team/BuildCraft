/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import javax.annotation.Nonnull;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class EntityUtil {
    public static NonNullList<ItemStack> collectItems(Level world, BlockPos around, double radius) {
        return collectItems(world, new Vec3(around.getX(), around.getY(), around.getZ()).add(0.5, 0.5, 0.5), radius);
    }

    public static NonNullList<ItemStack> collectItems(Level world, Vec3 around, double radius) {
        NonNullList<ItemStack> stacks = NonNullList.create();

        AABB aabb = BoundingBoxUtil.makeAround(around, radius);
        for (ItemEntity ent : world.getEntitiesOfClass(ItemEntity.class, aabb)) {
            if (!ent.isRemoved()) {
                ent.setRemoved(RemovalReason.DISCARDED);
                stacks.add(ent.getItem());
            }
        }
        return stacks;
    }

    public static Vec3 getVec(Entity entity) {
        return new Vec3(entity.getX(), entity.getY(), entity.getZ());
    }

    public static void setVec(Entity entity, Vec3 vec) {
        entity.setPos(vec.x, vec.y, vec.z);
    }

    public static InteractionHand getWrenchHand(LivingEntity entity) {
        ItemStack stack = entity.getItemInHand(InteractionHand.MAIN_HAND);
        if (WrenchUtil.isWrench(stack)) {
            return InteractionHand.MAIN_HAND;
        }
        stack = entity.getItemInHand(InteractionHand.OFF_HAND);
        if (WrenchUtil.isWrench(stack)) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    public static void activateWrench(Player player, BlockHitResult trace) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (WrenchUtil.isWrench(stack)) {
            WrenchUtil.wrenchUsed(player, InteractionHand.MAIN_HAND, stack, trace);
            return;
        }
        stack = player.getItemInHand(InteractionHand.OFF_HAND);
        if (WrenchUtil.isWrench(stack)) {
            WrenchUtil.wrenchUsed(player, InteractionHand.OFF_HAND, stack, trace);
        }
    }

    @Nonnull
    public static ItemStack getArrowStack(AbstractArrow arrow) {
        // Vanilla does not expose the pickup stack here, so mirror the known arrow types.
        if (arrow instanceof SpectralArrow) 
            return new ItemStack(Items.SPECTRAL_ARROW);
        else if(arrow instanceof ThrownTrident)
        	return new ItemStack(Items.TRIDENT);
        return new ItemStack(Items.ARROW);
    }
}
