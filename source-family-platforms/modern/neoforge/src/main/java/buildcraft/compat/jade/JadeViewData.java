//? source if >=1.21.11
package buildcraft.compat.jade;

import buildcraft.lib.internal.mj.MjFormatting;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.fluids.FluidStack;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.view.EnergyView;
import snownee.jade.api.view.FluidView;

/** Typed Jade 21 payloads; amounts and component patches must survive the client boundary. */
final class JadeViewData {
    private JadeViewData() {}

    static FluidView.Data fluid(FluidStack fluid, long capacity) {
        FluidStack safe = fluid == null ? FluidStack.EMPTY : fluid;
        JadeFluidObject object = JadeFluidObject.of(safe.getFluid(), Math.max(0, safe.getAmount()),
                safe.getComponentsPatch());
        return new FluidView.Data(object, Math.max(0L, capacity));
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
        view.overrideText = Component.literal(view.current + " / " + view.max).withStyle(ChatFormatting.WHITE);
        return view;
    }
}
