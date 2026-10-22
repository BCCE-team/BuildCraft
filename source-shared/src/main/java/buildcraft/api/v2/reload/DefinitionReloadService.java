package buildcraft.api.v2.reload;

import buildcraft.api.v2.registry.RegistrationContext;
import java.util.Collection;
import net.minecraft.resources.ResourceLocation;

/** Loader-neutral typed data registry. Registration happens before API freeze; reloads happen later. */
public interface DefinitionReloadService {
    <T> void registerType(ScriptableDefinitionType<T> type, RegistrationContext context);
    /** Add a named decoder, selected by the optional namespaced "type" field. */
    <T> void registerDecoder(ScriptableDefinitionType<T> type, ResourceLocation decoderId,
                             DefinitionDecoder<T> decoder, RegistrationContext context);
    <T> void registerContributor(ScriptableDefinitionType<T> type, ResourceLocation contributorId,
                                 ReloadContributor<T> contributor, RegistrationContext context);
    <T> void observe(ScriptableDefinitionType<T> type, ResourceLocation listenerId,
                     ReloadObserver<T> observer, RegistrationContext context);
    <T> ScriptedDefinitionSnapshot<T> snapshot(ScriptableDefinitionType<T> type);
    <T> ReloadPublishResult reload(ScriptableDefinitionType<T> type, Collection<ScriptedDefinition> definitions);
    boolean frozen();
}
