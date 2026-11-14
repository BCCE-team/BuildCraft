package buildcraft.compat.jade;

import buildcraft.lib.internal.mj.MjFormatting;
import buildcraft.lib.compat.minecraft.text.BCTextFormat;
import net.minecraft.network.chat.Component;
import buildcraft.lib.fluid.FluidStack;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.FluidView;

/** Typed Jade 21 payloads; amounts and component patches must survive the client boundary. */
final class JadeViewData {
    private JadeViewData() {}

    static FluidView.Data fluid(FluidStack fluid, long capacity) {
        FluidStack safe = fluid == null ? FluidStack.EMPTY : fluid;
        // Jade Fabric's volume unit is Fabric droplets (not BuildCraft mB).
        // Preserve whole-mB conservation and the complete DataComponentPatch.
        long dropletsPerMb = JadeFluidObject.bucketVolume() / 1_000L;
        long amount = Math.multiplyExact(Math.max(0L, (long) safe.getAmount()), dropletsPerMb);
        long max = Math.multiplyExact(Math.max(0L, capacity), dropletsPerMb);
        JadeFluidObject object = JadeFluidObject.of(safe.getFluid(), amount, safe.getComponentsPatch());
        return new FluidView.Data(object, max);
    }

    static EnergyView.Data energy(long current, long capacity) {
        long safeCapacity = Math.max(0L, capacity);
        return new EnergyView.Data(Math.max(0L, Math.min(safeCapacity, current)), safeCapacity);
    }

    static EnergyView readEnergy(EnergyView.Data data, String unit) {
        EnergyView.Data safe = energy(data.current(), data.capacity());
        if (safe.capacity() == 0L) return null;
        if (!"MJ".equals(unit)) return EnergyView.read(safe, unit);
        EnergyView view = new EnergyView(MjFormatting.formatMicroMj(safe.current()) + " MJ",
                MjFormatting.formatMicroMj(safe.capacity()) + " MJ");
        view.ratio = (float) (safe.current() / (double) safe.capacity());
        view.overrideText = Component.literal(view.current + " / " + view.max).withStyle(BCTextFormat.WHITE);
        return view;
    }
}
