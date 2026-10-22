package buildcraft.api.v2.reload;

import java.util.Collection;

/** Contributes code-owned reloadable definitions before decoding resource inputs. */
@FunctionalInterface
public interface ReloadContributor<T> {
    Collection<ScriptedDefinition> contribute(ReloadGeneration generation) throws Exception;
}
