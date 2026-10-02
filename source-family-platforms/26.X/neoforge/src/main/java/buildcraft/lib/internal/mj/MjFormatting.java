package buildcraft.lib.internal.mj;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.energy.MjAmount;

/** Internal formatting shortcut; public consumers use BuildCraftServices.MJ_FORMATTER directly. */
public final class MjFormatting {
    private MjFormatting() {}

    public static String formatMicroMj(long microMj) {
        return BuildCraftApi.service(BuildCraftServices.MJ_FORMATTER)
            .formatNumber(MjAmount.ofMicro(Math.max(0L, microMj)));
    }
}
