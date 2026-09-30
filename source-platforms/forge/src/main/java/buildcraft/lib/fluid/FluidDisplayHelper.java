package buildcraft.lib.fluid;

import java.util.List;
import java.util.Locale;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.fluids.FluidStack;

/** Client/server-safe formatting helpers for component-bearing fluid stacks. */
public final class FluidDisplayHelper {
    private FluidDisplayHelper() {}

    public static Component getDisplayName(FluidStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Component.empty();
        }
        return appendPotionDetails(stack.getDisplayName(), potionAmplifier(stack), potionDurationTicks(stack));
    }

    public static int potionAmplifier(FluidStack stack) {
        MobEffectInstance effect = firstPotionEffect(stack);
        return effect == null ? -1 : effect.getAmplifier();
    }

    public static int potionDurationTicks(FluidStack stack) {
        MobEffectInstance effect = firstPotionEffect(stack);
        return effect == null ? -1 : effect.getDuration();
    }

    public static Component appendPotionDetails(Component baseName, int amplifier, int durationTicks) {
        MutableComponent result = baseName.copy();
        if (amplifier > 0) {
            result.append(" ").append(Component.translatable("potion.potency." + amplifier));
        }
        if (durationTicks > 20) {
            int totalSeconds = durationTicks / 20;
            result.append(" (").append(String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)).append(")");
        }
        return result;
    }

    private static MobEffectInstance firstPotionEffect(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getTag() == null) {
            return null;
        }
        List<MobEffectInstance> effects = PotionUtils.getAllEffects(stack.getTag());
        return effects.isEmpty() ? null : effects.get(0);
    }
}
