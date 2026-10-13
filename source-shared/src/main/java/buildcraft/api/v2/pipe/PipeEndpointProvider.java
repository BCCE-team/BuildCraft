package buildcraft.api.v2.pipe;

import java.util.Optional;

/** Resolves an endpoint adjacent to a pipe using only the loaded-world connection context.
 * Return an empty result for unrelated neighbours. Registrations are immutable after freeze.
 */
@FunctionalInterface
public interface PipeEndpointProvider {
    Optional<PipeExternalEndpoint> find(PipeConnectionContext context);
}
