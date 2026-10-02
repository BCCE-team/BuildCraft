package buildcraft.lib.internal.mj;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;

import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import buildcraft.lib.misc.CapUtil;

/** Provides a quick way to expose all MJ interfaces implemented by one connector. */
public class MjCapabilityHelper implements IBCCapabilityProvider {
    @Nonnull
    private final IMjConnector connector;

    @Nullable
    private final IMjReceiver receiver;

    @Nullable
    private final IMjRedstoneReceiver rsReceiver;

    @Nullable
    private final IMjReadable readable;

    @Nullable
    private final IMjPassiveProvider provider;

    @Nullable
    private final IEnergyStorage feReceiver;

    public MjCapabilityHelper(@Nonnull IMjConnector mj) {
        this.connector = mj;
        this.receiver = mj instanceof IMjReceiver value ? value : null;
        this.rsReceiver = mj instanceof IMjRedstoneReceiver value ? value : null;
        this.readable = mj instanceof IMjReadable value ? value : null;
        this.provider = mj instanceof IMjPassiveProvider value ? value : null;
        this.feReceiver = mj instanceof IMjReceiver value ? new MjReceiverEnergyStorage(value) : null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(BlockCapability<T, Direction> capability, @Nullable Direction facing) {
        if (capability == MjCapabilities.CAP_CONNECTOR) {
            return (T) connector;
        }
        if (capability == MjCapabilities.CAP_RECEIVER) {
            return (T) receiver;
        }
        if (capability == MjCapabilities.CAP_REDSTONE_RECEIVER) {
            return (T) rsReceiver;
        }
        if (capability == MjCapabilities.CAP_READABLE) {
            return (T) readable;
        }
        if (capability == MjCapabilities.CAP_PASSIVE_PROVIDER) {
            return (T) provider;
        }
        if (capability == CapUtil.CAP_FE && BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled()) {
            return (T) feReceiver;
        }
        return null;
    }
}
