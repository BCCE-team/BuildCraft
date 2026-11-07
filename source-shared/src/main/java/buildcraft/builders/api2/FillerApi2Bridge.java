package buildcraft.builders.api2;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.BCLibSprites;
import buildcraft.lib.client.sprite.SpriteRaw;
import buildcraft.api.v2.client.ContentPresentation;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.area.BlockBox;
import buildcraft.api.v2.filler.FillerMask;
import buildcraft.api.v2.filler.FillerPatternContext;
import buildcraft.api.v2.filler.FillerPatternType;
import buildcraft.builders.internal.filler.legacy.FillerManager;
import buildcraft.builders.internal.filler.legacy.IFilledTemplate;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.builders.internal.filler.legacy.IFillerPatternShape;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementMouseClick;
import buildcraft.lib.internal.statement.containers.IFillerStatementContainer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Bridges the legacy-compatible filler executor to the supported API2 filler registry. */
public final class FillerApi2Bridge {
    private static final Set<ResourceLocation> MIRRORED = new java.util.LinkedHashSet<>();
    private static final Map<ResourceLocation, Api2PatternAdapter> NATIVE = new LinkedHashMap<>();

    private FillerApi2Bridge() {}

    public static synchronized void mirrorLegacyPattern(IFillerPattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        ResourceLocation id = ResourceLocation.tryParse(pattern.getUniqueTag());
        if (id == null) return;
        if (BuildCraftApi.registry(BuildCraftRegistries.FILLER_PATTERN_TYPES).get(id) == null) {
            FillerPatternType type = new FillerPatternType(id, context -> createLegacyMask(pattern, context), false);
            BuildCraftApi.registry(BuildCraftRegistries.FILLER_PATTERN_TYPES).register(id, type, () -> id.getNamespace());
        }
        MIRRORED.add(id);
    }

    public static synchronized IFillerPattern nativePattern(String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null || MIRRORED.contains(id)) return null;
        FillerPatternType type = BuildCraftApi.registry(BuildCraftRegistries.FILLER_PATTERN_TYPES).get(id);
        if (type == null || type.internalOnly()) return null;
        return NATIVE.computeIfAbsent(id, ignored -> new Api2PatternAdapter(type));
    }

    public static synchronized List<IFillerPattern> nativePatterns() {
        List<IFillerPattern> result = new ArrayList<>();
        for (FillerPatternType type : BuildCraftApi.registry(BuildCraftRegistries.FILLER_PATTERN_TYPES).values()) {
            if (MIRRORED.contains(type.id()) || type.internalOnly()) continue;
            result.add(NATIVE.computeIfAbsent(type.id(), ignored -> new Api2PatternAdapter(type)));
        }
        return List.copyOf(result);
    }

    private static FillerMask createLegacyMask(IFillerPattern pattern, FillerPatternContext context) {
        BlockBox bounds = context.bounds();
        BlockPos size = bounds.max().subtract(bounds.min()).offset(1, 1, 1);
        IFilledTemplate template = FillerManager.registry.createFilledTemplate(bounds.min(), size);
        boolean filled = false;
        if (pattern instanceof IFillerPatternShape shape) {
            IStatementParameter[] params = defaultParameters(pattern);
            filled = shape.fillTemplate(template, params);
        }
        final boolean valid = filled;
        return new FillerMask() {
            @Override public BlockBox bounds() { return bounds; }
            @Override public boolean includes(BlockPos pos) {
                if (!valid || !bounds.contains(pos)) return false;
                BlockPos local = pos.subtract(bounds.min());
                return template.get(local.getX(), local.getY(), local.getZ());
            }
        };
    }

    private static IStatementParameter[] defaultParameters(IFillerPattern pattern) {
        IStatementParameter[] params = new IStatementParameter[pattern.maxParameters()];
        for (int i = 0; i < params.length; i++) params[i] = pattern.createParameter(i);
        return params;
    }

    private static final class Api2PatternAdapter implements IFillerPattern {
        private final FillerPatternType type;
        Api2PatternAdapter(FillerPatternType type) { this.type = type; }
        @Override public String getUniqueTag() { return type.id().toString(); }
        @Override public int maxParameters() { return 0; }
        @Override public int minParameters() { return 0; }
        @Override public IStatementParameter createParameter(int index) { return null; }
        @Override public IFillerPattern rotateLeft() { return this; }
        @Override public IFillerPattern[] getPossible() { return new IFillerPattern[] { this }; }
        @Override public Component getDescription() { return Component.literal(type.id().toString()); }
        @Override public ISprite getSprite() {
            ContentPresentation presentation = BuildCraftApi.registry(BuildCraftRegistries.CLIENT_PRESENTATIONS).get(type.id());
            return presentation == null || presentation.icon() == null
                ? BCLibSprites.HELP : new SpriteRaw(presentation.icon(), 0, 0, 1, 1);
        }
        @Override
        public IFilledTemplate createTemplate(IFillerStatementContainer filler, IStatementParameter[] params) {
            if (!filler.hasBox()) return null;
            BlockPos min = filler.getBox().min();
            BlockPos max = filler.getBox().max();
            BlockBox bounds = new BlockBox(min, max);
            FillerMask mask = type.pattern().createMask(new FillerPatternContext(bounds, Direction.NORTH, Map.of()));
            IFilledTemplate template = FillerManager.registry.createFilledTemplate(min, filler.getBox().size());
            BlockPos size = filler.getBox().size();
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int x = 0; x < size.getX(); x++) {
                        BlockPos world = min.offset(x, y, z);
                        if (mask.includes(world)) template.set(x, y, z, true);
                    }
                }
            }
            return template;
        }
    }
}
