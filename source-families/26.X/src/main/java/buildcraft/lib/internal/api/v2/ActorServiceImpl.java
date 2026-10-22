package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.permission.ActorService;
import buildcraft.api.v2.permission.AutomationActor;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Internal factory for loader-neutral automation identities. */
final class ActorServiceImpl implements ActorService {
    public AutomationActor player(UUID playerId, String playerName) {
        return AutomationActor.player(playerId, playerName);
    }

    public AutomationActor machineOwner(UUID ownerId, String ownerName, Identifier sourceId) {
        return AutomationActor.machineOwner(ownerId, ownerName, sourceId);
    }

    public AutomationActor system(Identifier sourceId) {
        return AutomationActor.system(sourceId);
    }

    public AutomationActor unknown() {
        return AutomationActor.unknown();
    }
}
