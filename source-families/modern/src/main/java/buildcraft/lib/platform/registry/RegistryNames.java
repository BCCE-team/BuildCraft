package buildcraft.lib.platform.registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
final class RegistryNames {
    private RegistryNames() {}
    static ResourceLocation id(String namespace, String path) { return ResourceLocation.fromNamespaceAndPath(namespace, path); }
    static String key(ResourceKey<?> key) {
        //? if >=1.21.11 {
        return key.identifier().toString();
        //? } else {
        return key.location().toString();
        //? }
    }
}
