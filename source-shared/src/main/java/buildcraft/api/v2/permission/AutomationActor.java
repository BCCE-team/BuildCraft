package buildcraft.api.v2.permission;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Loader-neutral identity used when BuildCraft automation acts on the world.
 *
 * The optional source id describes the system that created the actor (for
 * example a robot or quarry type); it is not a Java class name.
 */
public final class AutomationActor {
    private static final AutomationActor UNKNOWN = new AutomationActor(ActorType.UNKNOWN, null, null, null);

    private final ActorType type;
    private final UUID playerId;
    private final String playerName;
    private final ResourceLocation sourceId;

    private AutomationActor(ActorType type, UUID playerId, String playerName, ResourceLocation sourceId) {
        this.type = Objects.requireNonNull(type, "type");
        this.playerId = playerId;
        this.playerName = normalizeName(playerName);
        this.sourceId = sourceId;
        if ((type == ActorType.PLAYER || type == ActorType.MACHINE_OWNER) && playerId == null) {
            throw new IllegalArgumentException(type + " actor requires a player UUID");
        }
    }

    public static AutomationActor player(UUID playerId, String playerName) {
        return new AutomationActor(ActorType.PLAYER, Objects.requireNonNull(playerId, "playerId"), playerName, null);
    }

    public static AutomationActor machineOwner(UUID playerId, String playerName, ResourceLocation sourceId) {
        return new AutomationActor(
            ActorType.MACHINE_OWNER,
            Objects.requireNonNull(playerId, "playerId"),
            playerName,
            Objects.requireNonNull(sourceId, "sourceId")
        );
    }

    public static AutomationActor system(ResourceLocation sourceId) {
        return new AutomationActor(ActorType.SYSTEM, null, null, Objects.requireNonNull(sourceId, "sourceId"));
    }

    public static AutomationActor unknown() {
        return UNKNOWN;
    }

    public ActorType type() {
        return type;
    }

    public Optional<UUID> playerId() {
        return Optional.ofNullable(playerId);
    }

    public Optional<String> playerName() {
        return Optional.ofNullable(playerName);
    }

    public Optional<ResourceLocation> sourceId() {
        return Optional.ofNullable(sourceId);
    }

    public boolean representsPlayer() {
        return playerId != null;
    }

    private static String normalizeName(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof AutomationActor other)) return false;
        return type == other.type
            && Objects.equals(playerId, other.playerId)
            // An authenticated player keeps the same identity after a username change.
            && (playerId != null || Objects.equals(playerName, other.playerName))
            && Objects.equals(sourceId, other.sourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, playerId, playerId == null ? playerName : null, sourceId);
    }

    @Override
    public String toString() {
        return "AutomationActor[type=" + type + ", player=" + playerId + ", source=" + sourceId + "]";
    }
}
