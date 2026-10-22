package buildcraft.lib.platform.registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
final class RegistryNames {
    private RegistryNames() {}
    static Identifier id(String namespace, String path) { return Identifier.fromNamespaceAndPath(namespace, path); }
    static String key(ResourceKey<?> key) {
        return key.identifier().toString();
    }
}
