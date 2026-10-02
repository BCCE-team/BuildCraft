package buildcraft.lib.internal.permission;

import buildcraft.api.v2.permission.OwnedView;
import com.mojang.authlib.GameProfile;
import java.util.Optional;
import java.util.UUID;
import buildcraft.lib.compat.GameProfileCompat;

/** Internal classic ownership bridge. Public consumers use API2 {@link OwnedView}. */
public interface IPlayerOwned extends OwnedView {
    GameProfile getOwner();

    default Optional<UUID> ownerId() {
        GameProfile owner = getOwner();
        return owner == null ? Optional.empty() : Optional.ofNullable(GameProfileCompat.id(owner));
    }
}
