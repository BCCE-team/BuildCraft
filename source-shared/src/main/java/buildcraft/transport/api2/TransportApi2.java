package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;

/** One-time Transport -> API2 runtime wiring. */
public final class TransportApi2 {
    private static boolean installed;

    private TransportApi2() {}

    public static synchronized void install() {
        if (installed) return;
        BuildCraftApiRuntime.bootstrap();
        BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.PIPES, PipeServiceImpl.INSTANCE);
        BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.PIPE_RESOURCES, PipeResourceServiceImpl.INSTANCE);
        BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.SIGNALS, SignalServiceImpl.INSTANCE);
        ClassicSignalChannels.register();
        installed = true;
    }
}
