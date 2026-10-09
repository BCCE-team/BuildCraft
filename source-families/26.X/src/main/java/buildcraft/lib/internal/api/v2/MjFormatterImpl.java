package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.energy.EnergyRateUnit;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjFormatter;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class MjFormatterImpl implements MjFormatter {
    private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal.withInitial(
        () -> new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT))
    );

    public String formatAmount(MjAmount amount) {
        return formatNumber(amount) + " MJ";
    }

    public String formatRate(MjAmount perTick, EnergyRateUnit unit) {
        long micro = perTick.microMj();
        if (unit == EnergyRateUnit.PER_SECOND) {
            micro = Math.multiplyExact(micro, 20L);
        }
        return formatNumber(MjAmount.ofMicro(micro)) + (unit == EnergyRateUnit.PER_SECOND ? " MJ/s" : " MJ/t");
    }

    public String formatNumber(MjAmount amount) {
        return FORMAT.get().format(amount.microMj() / (double) MjAmount.MICRO_MJ_PER_MJ);
    }
}
