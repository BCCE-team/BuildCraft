package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.energy.PowerLossContext;
import buildcraft.api.v2.energy.PowerLossEffectService;

/** Default BCCE backend. Rendering/particle modules can decorate it without changing the API contract. */
final class PowerLossEffectServiceImpl implements PowerLossEffectService {
    @Override
    public void onPowerLost(PowerLossContext context) {
        // Legacy BCCE shipped with a no-op effect manager by default; preserve that behaviour here.
    }
}
