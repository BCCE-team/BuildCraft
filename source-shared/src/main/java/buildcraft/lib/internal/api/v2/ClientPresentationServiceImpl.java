package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.client.ClientPresentationService;
import buildcraft.api.v2.client.ContentPresentation;
import buildcraft.api.v2.client.DynamicPresentation;
import buildcraft.api.v2.client.MenuPresentation;
import buildcraft.api.v2.client.ParameterPresentation;
import buildcraft.api.v2.client.PipePresentation;
import buildcraft.api.v2.client.PresentationFrame;
import buildcraft.api.v2.client.StatementPresentation;
import buildcraft.api.v2.machine.EngineHost;
import buildcraft.api.v2.machine.EngineVisualState;
import buildcraft.api.v2.machine.MachineExecutionContext;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.lib.internal.debug.BCLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

/** Resolves client visuals from frozen registrations and the existing transport/machine sync paths. */
public final class ClientPresentationServiceImpl implements ClientPresentationService {
    private static final ResourceLocation WORK_PROGRESS = id("buildcraft:work_progress");
    private static final ResourceLocation ENGINE_PISTON = id("buildcraft:engine_piston");
    private static final ResourceLocation ENGINE_HEAT = id("buildcraft:engine_heat");
    private final Set<ResourceLocation> warnedSelectors = ConcurrentHashMap.newKeySet();

    private static ResourceLocation id(String value) {
        return Objects.requireNonNull(ResourceLocation.tryParse(value));
    }

    @Override
    public Optional<ContentPresentation> presentation(ResourceLocation contentId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.CLIENT_PRESENTATIONS).get(contentId));
    }

    @Override
    public Optional<PipePresentation> pipe(ResourceLocation pipeTypeId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.PIPE_PRESENTATIONS).get(pipeTypeId));
    }

    @Override
    public Optional<StatementPresentation> statement(ResourceLocation statementId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_PRESENTATIONS).get(statementId));
    }

    @Override
    public Optional<ParameterPresentation> parameter(ResourceLocation parameterTypeId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_PRESENTATIONS).get(parameterTypeId));
    }

    @Override
    public Optional<MenuPresentation> menu(ResourceLocation contentId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.MENU_PRESENTATIONS).get(contentId));
    }

    @Override
    public Optional<PresentationFrame> frame(PipeView view, float partialTick) {
        Objects.requireNonNull(view, "pipe");
        validatePartialTick(partialTick);
        if (!view.isClientSide()) return Optional.empty();
        ResourceLocation id = view.typeId();
        PipePresentation pipe = pipe(id).orElse(null);
        ContentPresentation base = pipe == null ? presentation(id).orElse(null) : pipe.base();
        PresentationFrame fallback = initial(base, pipe == null ? List.of() : pipe.textureLayers(), Map.of(), null, menu(id).orElse(null));
        return resolve(id, fallback, base != null || pipe != null,
            () -> resolver(id).pipe(view, partialTick));
    }

    @Override
    public Optional<PresentationFrame> frame(MachineExecutionContext view, float partialTick) {
        Objects.requireNonNull(view, "machine");
        validatePartialTick(partialTick);
        if (view.isServerSide()) return Optional.empty();
        ResourceLocation id = view.typeId();
        ContentPresentation base = presentation(id).orElse(null);
        PresentationFrame fallback = initial(base, List.of(), Map.of(WORK_PROGRESS, view.workStatus().progress()), null, menu(id).orElse(null));
        return resolve(id, fallback, true, () -> resolver(id).machine(view, partialTick));
    }

    @Override
    public Optional<PresentationFrame> frame(EngineHost view, float partialTick) {
        Objects.requireNonNull(view, "engine");
        validatePartialTick(partialTick);
        if (view.isServerSide()) return Optional.empty();
        ResourceLocation id = view.typeId();
        ContentPresentation base = presentation(id).orElse(null);
        EngineVisualState engine = view.visualState();
        double heat = engine.heat().criticalTemperature() <= 0 ? 0
            : Math.min(1, Math.max(0, engine.heat().temperature() / engine.heat().criticalTemperature()));
        Map<ResourceLocation, Double> animations = new LinkedHashMap<>();
        animations.put(WORK_PROGRESS, view.workStatus().progress());
        animations.put(ENGINE_PISTON, engine.animationPhase());
        animations.put(ENGINE_HEAT, heat);
        PresentationFrame fallback = initial(base, List.of(), animations, engine.soundId(), menu(id).orElse(null));
        return resolve(id, fallback, true, () -> resolver(id).engine(view, partialTick));
    }

    private Optional<PresentationFrame> resolve(ResourceLocation id, PresentationFrame fallback,
        boolean hasBase, Supplier<Optional<PresentationFrame>> selector) {
        if (BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).get(id) == null) {
            return hasBase ? Optional.of(fallback) : Optional.empty();
        }
        try {
            Optional<PresentationFrame> selected = Objects.requireNonNull(selector.get(), "presentation selector result");
            if (selected.isPresent()) {
                PresentationFrame frame = selected.orElseThrow().over(fallback);
                warnedSelectors.remove(id);
                return Optional.of(frame);
            }
        } catch (RuntimeException ex) {
            if (warnedSelectors.add(id)) BCLog.logger.warn("Presentation selector for {} failed", id, ex);
        }
        return hasBase ? Optional.of(fallback) : Optional.empty();
    }

    private DynamicPresentation resolver(ResourceLocation id) {
        return BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).get(id);
    }

    private static PresentationFrame initial(ContentPresentation base, List<ResourceLocation> textures,
        Map<ResourceLocation, Double> animations, ResourceLocation sound,
        MenuPresentation menu) {
        return new PresentationFrame(base == null ? null : base.model(), textures, animations, sound, menu);
    }

    private static void validatePartialTick(float tick) {
        if (!Float.isFinite(tick) || tick < 0 || tick > 1) {
            throw new IllegalArgumentException("partialTick must be normalized to [0, 1]");
        }
    }
}
