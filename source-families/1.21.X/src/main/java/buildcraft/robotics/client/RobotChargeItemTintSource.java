//? source if >=1.21.11
/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Only the side-light faces have tint index zero; body and bottom remain untinted. */
public final class RobotChargeItemTintSource implements ItemTintSource {
    public static final MapCodec<RobotChargeItemTintSource> MAP_CODEC =
        MapCodec.unit(RobotChargeItemTintSource::new);

    @Override
    public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
        return RobotBatteryVisuals.sideTint(stack);
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }
}
