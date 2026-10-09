package buildcraft.lib.internal.api.v2;

import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Internal provenance view used by the Guide Book UI to attribute API-owned guide content to the addon that
 * registered it. This is deliberately not part of the public API2 service contract.
 */
public interface GuideOwnershipView {
    Optional<String> ownerOfSection(Identifier id);
    Optional<String> ownerOfEntry(Identifier id);
}
