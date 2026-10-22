package dev.bcce.apifixture;

import buildcraft.api.v2.client.DynamicPresentation;
import buildcraft.api.v2.client.PresentationFrame;
import buildcraft.api.v2.machine.EngineHost;
import buildcraft.api.v2.machine.MachineExecutionContext;
import buildcraft.api.v2.pipe.PipeView;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Selects resources from client snapshots without depending on a concrete renderer. */
public final class PresentationConformanceAddon implements DynamicPresentation {
    public static final ResourceLocation PIPE = ResourceLocation.tryParse("api_fixture:visual_pipe");
    public static final ResourceLocation MACHINE = ResourceLocation.tryParse("api_fixture:visual_machine");
    public static final ResourceLocation ENGINE = ResourceLocation.tryParse("api_fixture:visual_engine");
    public static final ResourceLocation CHANNEL = ResourceLocation.tryParse("api_fixture:strength");
    public static final ResourceLocation CHANNEL_ANIMATION = ResourceLocation.tryParse("api_fixture:movement");
    private static final ResourceLocation BRIGHT_MODEL = ResourceLocation.tryParse("api_fixture:pipe_bright");
    private static final ResourceLocation DARK_MODEL = ResourceLocation.tryParse("api_fixture:pipe_dark");

    @Override public Optional<PresentationFrame> pipe(PipeView pipe, float partialTick) {
        int strength = pipe.presentation(CHANNEL, Integer.class).orElse(0);
        return Optional.of(new PresentationFrame(strength > 0 ? BRIGHT_MODEL : DARK_MODEL,
            List.of(), Map.of(CHANNEL_ANIMATION, Math.min(1d, Math.max(0d, strength / 100d))), null, null));
    }

    @Override public Optional<PresentationFrame> machine(MachineExecutionContext machine, float partialTick) {
        int strength = machine.presentation(CHANNEL, Integer.class).orElse(0);
        return Optional.of(new PresentationFrame(null, List.of(),
            Map.of(CHANNEL_ANIMATION, Math.min(1d, Math.max(0d, strength / 100d))), null, null));
    }

    @Override public Optional<PresentationFrame> engine(EngineHost engine, float partialTick) {
        return Optional.of(PresentationFrame.empty());
    }
}
