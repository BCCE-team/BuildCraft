package buildcraft.lib.platform.registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
final class RegistryNames {
    private RegistryNames() {}
    static ResourceLocation id(String namespace, String path) { return new ResourceLocation(namespace, path); }
    static String key(ResourceKey<?> key) { return key.location().toString(); }
}
