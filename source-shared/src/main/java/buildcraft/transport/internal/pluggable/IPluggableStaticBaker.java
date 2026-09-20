package buildcraft.transport.internal.pluggable;

import java.util.List;

import net.minecraft.client.renderer.block.model.BakedQuad;
public interface IPluggableStaticBaker<K extends PluggableModelKey> {
    List<BakedQuad> bake(K key);
}
