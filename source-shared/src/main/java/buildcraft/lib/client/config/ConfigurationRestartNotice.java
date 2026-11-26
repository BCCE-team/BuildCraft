package buildcraft.lib.client.config;

import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.ApplyPolicy;
import buildcraft.api.v2.config.ConfigDefinition.Scope;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Classify validated changes before saving, independently of the currently visible tab. */
final class ConfigurationRestartNotice {
    enum Requirement { GAME, WORLD, SERVER, NEXT_WORLD }

    private ConfigurationRestartNotice() {}

    static Map<Requirement, Integer> requirements(Collection<ConfigDefinition> definitions,
            Map<String, String> clientChanges, Map<String, String> serverChanges,
            Map<String, String> effective, boolean inWorld, boolean singleplayer) {
        var result = new EnumMap<Requirement, Integer>(Requirement.class);
        for (ConfigDefinition d : definitions) {
            String next = (d.scope() == Scope.CLIENT ? clientChanges : serverChanges).get(d.id());
            // A change back to the running value cancels an outstanding restart, rather than requiring another.
            if (next == null || Objects.equals(next, effective.get(d.id())) || d.applyPolicy() == ApplyPolicy.LIVE)
                continue;
            Requirement requirement;
            if (d.scope() == Scope.CLIENT) {
                // Currently the registry only reapplies non-LIVE client values on client startup.
                requirement = Requirement.GAME;
            } else if (inWorld && !singleplayer) {
                requirement = Requirement.SERVER;
            } else if (d.applyPolicy() == ApplyPolicy.RESTART) {
                requirement = Requirement.GAME;
            } else {
                requirement = inWorld ? Requirement.WORLD : Requirement.NEXT_WORLD;
            }
            result.merge(requirement, 1, Integer::sum);
        }
        return result;
    }
}
