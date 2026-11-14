/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.silicon.client;

import buildcraft.lib.misc.ColourUtil;
import buildcraft.silicon.item.ItemPluggableLens;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Restores the colored translucent lens/filter insert when using native 26.3 item models. */
public final class LensItemTintSource implements ItemTintSource {
    public static final MapCodec<LensItemTintSource> MAP_CODEC = MapCodec.unit(LensItemTintSource::new);

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        DyeColor color = ItemPluggableLens.getData(stack).colour;
        return color == null ? 0xFFFFFFFF : 0xFF000000 | ColourUtil.getLightHex(color);
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }
}
