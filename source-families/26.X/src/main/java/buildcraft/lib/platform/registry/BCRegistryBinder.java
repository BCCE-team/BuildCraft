package buildcraft.lib.platform.registry;

/** Mod-bus binding is injected by loader bootstrap; catalogs only describe content. */
public interface BCRegistryBinder {
    <T> void register(BCDeferredRegister<T> catalog);
}
