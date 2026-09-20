package buildcraft.transport.internal.pipe;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
public interface IPipeBehaviourRenderer<B extends PipeBehaviour> {
    void render(B behaviour, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int combinedLight, int combinedOverlay);
}
