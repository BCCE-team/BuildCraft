package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.filler.FillerPatternService;
import buildcraft.api.v2.filler.FillerPatternType;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Registry-backed filler pattern service. */
public final class FillerPatternServiceImpl implements FillerPatternService {
    public static final FillerPatternServiceImpl INSTANCE = new FillerPatternServiceImpl();

    private FillerPatternServiceImpl() {}

    public Optional<FillerPatternType> type(Identifier id) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.FILLER_PATTERN_TYPES).get(id));
    }

    public Collection<FillerPatternType> types() {
        return java.util.List.copyOf(BuildCraftApi.registry(BuildCraftRegistries.FILLER_PATTERN_TYPES).values());
    }
}
