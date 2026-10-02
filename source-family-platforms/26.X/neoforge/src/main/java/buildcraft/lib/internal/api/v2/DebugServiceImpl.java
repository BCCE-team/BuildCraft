package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.debug.DebugContext;
import buildcraft.api.v2.debug.DebugContributor;
import buildcraft.api.v2.debug.DebugSection;
import buildcraft.api.v2.debug.DebugService;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.tiles.IDebuggable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.Direction;

/** Aggregates built-in tile diagnostics and API2 addon contributors. */
final class DebugServiceImpl implements DebugService {
    public Collection<DebugSection> collect(DebugContext context) {
        List<DebugSection> sections = new ArrayList<>();
        Object blockEntity = context.level().getBlockEntity(context.position());
        if (blockEntity instanceof IDebuggable debuggable) {
            List<String> left = new ArrayList<>();
            List<String> right = new ArrayList<>();
            Direction side = context.side().orElse(null);
            try {
                debuggable.getDebugInfo(left, right, side);
                if (context.clientSide()) {
                    debuggable.getClientDebugInfo(left, right, side);
                }
            } catch (RuntimeException ex) {
                BCLog.logger.warn("Failed to collect BuildCraft debug information at {}", context.position(), ex);
            }
            if (!left.isEmpty()) sections.add(new DebugSection("BuildCraft", left));
            if (!right.isEmpty()) sections.add(new DebugSection("BuildCraft details", right));
        }

        for (DebugContributor contributor : BuildCraftApi.registry(BuildCraftRegistries.DEBUG_CONTRIBUTORS).values()) {
            try {
                Collection<DebugSection> contributed = contributor.collect(context);
                if (contributed != null) sections.addAll(contributed);
            } catch (RuntimeException ex) {
                BCLog.logger.warn("BuildCraft API2 debug contributor failed at {}", context.position(), ex);
            }
        }
        return List.copyOf(sections);
    }
}
