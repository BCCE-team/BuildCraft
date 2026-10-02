package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.client.ClientPresentationService;
import buildcraft.api.v2.client.ContentPresentation;
import buildcraft.api.v2.client.ParameterPresentation;
import buildcraft.api.v2.client.PipePresentation;
import buildcraft.api.v2.client.StatementPresentation;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Data-only presentation lookup backed directly by the frozen API2 presentation registries. */
public final class ClientPresentationServiceImpl implements ClientPresentationService {
    public Optional<ContentPresentation> presentation(Identifier contentId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.CLIENT_PRESENTATIONS).get(contentId));
    }

    public Optional<PipePresentation> pipe(Identifier pipeTypeId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.PIPE_PRESENTATIONS).get(pipeTypeId));
    }

    public Optional<StatementPresentation> statement(Identifier statementId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.STATEMENT_PRESENTATIONS).get(statementId));
    }

    public Optional<ParameterPresentation> parameter(Identifier parameterTypeId) {
        return Optional.ofNullable(BuildCraftApi.registry(BuildCraftRegistries.PARAMETER_PRESENTATIONS).get(parameterTypeId));
    }
}
