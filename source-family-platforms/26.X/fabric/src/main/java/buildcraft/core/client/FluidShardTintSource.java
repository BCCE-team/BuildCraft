/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.client;

import buildcraft.core.item.ItemFragileFluidContainer;
import buildcraft.lib.fluid.FluidStack;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Fabric 26.3 equivalent of the old NeoForge fluid-container item tint.
 * The translucent shard mask is a separate generated-model layer, and its
 * colour follows the fluid (including its Data Components) stored in the item.
 */
public final class FluidShardTintSource implements ItemTintSource {
    public static final MapCodec<FluidShardTintSource> MAP_CODEC =
        MapCodec.unit(FluidShardTintSource::new);

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        FluidStack fluid = ItemFragileFluidContainer.getFluid(stack);
        if (fluid.isEmpty()) {
            // Hide the fluid mask when the shard is empty; the glass frame remains.
            return 0x00FFFFFF;
        }
        return FluidVariantRendering.getColor(
            FluidVariant.of(fluid.getFluid(), fluid.getComponentsPatch()));
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }
}
