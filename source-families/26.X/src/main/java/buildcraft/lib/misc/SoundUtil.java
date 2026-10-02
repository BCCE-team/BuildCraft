/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import buildcraft.lib.internal.debug.BCLog;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.FluidStack;

public class SoundUtil {
    public static void playBlockPlace(Level world, BlockPos pos) {
        playBlockPlace(world, pos, world.getBlockState(pos));
    }

    public static void playBlockPlace(Level world, BlockPos pos, BlockState state) {
        SoundType soundType = state.getBlock().getSoundType(state, world, pos, null);
        float volume = (soundType.getVolume() + 1.0F) / 2.0F;
        float pitch = soundType.getPitch() * 0.8F;
        world.playSound(null, pos, soundType.getPlaceSound(), SoundSource.BLOCKS, volume, pitch);
    }

    public static void playBlockBreak(Level world, BlockPos pos) {
        playBlockBreak(world, pos, world.getBlockState(pos));
    }

    public static void playBlockBreak(Level world, BlockPos pos, BlockState state) {
        SoundType soundType = state.getBlock().getSoundType(state, world, pos, null);
        float volume = (soundType.getVolume() + 1.0F) / 2.0F;
        float pitch = soundType.getPitch() * 0.8F;
        world.playSound(null, pos, soundType.getBreakSound(), SoundSource.BLOCKS, volume, pitch);
    }

    public static void playLeverSwitch(Level world, BlockPos pos, boolean isNowOn) {
        float pitch = isNowOn ? 0.6f : 0.5f;
        SoundEvent soundEvent = SoundEvents.LAVA_POP;
        world.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 0.2f, pitch);
    }

    public static void playChangeColour(Level world, BlockPos pos, @Nullable DyeColor colour) {
        SoundType soundType = SoundType.SLIME_BLOCK;
        final SoundEvent soundEvent;
        if (colour == null) {
            soundEvent = SoundEvents.BUCKET_EMPTY;
        } else {
            soundEvent = SoundEvents.SLIME_SQUISH;
        }
        float volume = (soundType.getVolume() + 1.0F) / 2.0F;
        float pitch = soundType.getPitch() * 0.8F;
        world.playSound(null, pos, soundEvent, SoundSource.BLOCKS, volume, pitch);
    }

    public static void playSlideSound(Level world, BlockPos pos) {
        playSlideSound(world, pos, world.getBlockState(pos));
    }

    public static void playSlideSound(Level world, BlockPos pos, InteractionResult result) {
        playSlideSound(world, pos, world.getBlockState(pos), result);
    }

    public static void playSlideSound(Level world, BlockPos pos, BlockState state) {
        playSlideSound(world, pos, state, InteractionResult.SUCCESS);
    }

    public static void playSlideSound(Level world, BlockPos pos, BlockState state, InteractionResult result) {
        if (result == InteractionResult.PASS) return;
        SoundType soundType = state.getBlock().getSoundType(state, world, pos, null);
        SoundEvent event;
        if (result == InteractionResult.SUCCESS) {
            event = SoundEvents.PISTON_CONTRACT;
        } else {
            event = SoundEvents.PISTON_EXTEND;
        }
        float volume = (soundType.getVolume() + 1.0F) / 2.0F;
        float pitch = soundType.getPitch() * 0.8F;
        world.playSound(null, pos, event, SoundSource.BLOCKS, volume, pitch);
    }
    
    public static void playDefaultBucketEmpty(Level world, BlockPos pos, FluidStack moved) {
    	/**this method disappeared*/
//        SoundEvent sound = moved.getFluid()(moved);
        SoundEvent sound = SoundEvents.BUCKET_EMPTY; 
        world.playSound(null, pos, sound, SoundSource.PLAYERS, 1, 1);
    }
    
    public static void playBucketEmpty(Level level, BlockPos pos, FluidStack resource) {
        playFluidActionSound(
            level, pos, SoundEvents.BUCKET_EMPTY,
            () -> resource.getFluid().getFluidType().getSound(resource, SoundActions.BUCKET_EMPTY),
            "bucket-empty"
        );
    }

    public static void playBucketFill(Level world, BlockPos pos, FluidStack moved) {
        playFluidActionSound(
            world, pos, SoundEvents.BUCKET_FILL,
            () -> moved.getFluid().getFluidType().getSound(moved, SoundActions.BUCKET_FILL),
            "bucket-fill"
        );
    }

    /**
     * Fluid sounds are cosmetic and must never abort an already committed inventory/fluid transaction.
     * Third-party fluid types are allowed to omit an action sound and, in practice, can also throw while resolving
     * one. Fall back to vanilla and suppress playback failures so a sound provider cannot cause dupes or item loss.
     */
    private static void playFluidActionSound(Level level, BlockPos pos, SoundEvent fallback,
        Supplier<SoundEvent> soundProvider, String action) {
        SoundEvent sound = fallback;
        try {
            SoundEvent provided = soundProvider.get();
            if (provided != null) {
                sound = provided;
            }
        } catch (RuntimeException exception) {
            BCLog.logger.warn("Fluid {} sound provider failed; using vanilla fallback", action, exception);
        }

        try {
            level.playSound(null, pos, sound, SoundSource.PLAYERS, 1, 1);
        } catch (RuntimeException exception) {
            BCLog.logger.warn("Failed to play cosmetic fluid {} sound", action, exception);
        }
    }
}
