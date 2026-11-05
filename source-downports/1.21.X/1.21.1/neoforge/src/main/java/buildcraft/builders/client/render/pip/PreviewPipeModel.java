/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * 1.21.1 NeoForge adaptation of the BCCE modern offline pipe preview (MPL-2.0).
 * This class only reconstructs render data; it never places or ticks a pipe.
 */
package buildcraft.builders.client.render.pip;

import java.util.ArrayList;
import java.util.List;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.render.material.LegacyMaterialAlpha;
import buildcraft.lib.internal.core.InvalidInputDataException;
import net.minecraft.client.renderer.RenderType;
import buildcraft.transport.client.PipeRegistryClient;
import buildcraft.transport.internal.pluggable.IPlugDynamicRenderer;
import buildcraft.transport.client.model.PipeModelCacheBase;
import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseCutoutKey;
import buildcraft.transport.client.model.PipeModelCacheBase.PipeBaseTranslucentKey;
import buildcraft.transport.client.model.PipeModelCachePluggable;
import buildcraft.transport.client.model.PipeModelCachePluggable.PluggableKey;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEvent;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.wire.WireManager;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.BlockCapability;


/** Baked pipe body, connections, colour, and static pluggables from a saved schematic. */
public final class PreviewPipeModel {
    private PreviewPipeModel() {}

    /** Cached baked mesh plus pluggables that need the same dynamic renderer as in-world pipes. */
    public record Mesh(List<MutableQuad> cutout, List<MutableQuad> translucent,
                       List<PipePluggable> dynamicPlugs) {
        public boolean isEmpty() {
            return cutout.isEmpty() && translucent.isEmpty() && dynamicPlugs.isEmpty();
        }
    }

    /** Read-only connection profile; never asks the level to update pipe connectivity. */
    public static final class LinkProfile {
        private final Pipe pipe;
        private final boolean[] blocked;

        private LinkProfile(Pipe pipe, boolean[] blocked) {
            this.pipe = pipe;
            this.blocked = blocked;
        }
    }

    public static LinkProfile links(CompoundTag tileData) {
        try {
            OfflineHolder holder = readOfflineHolder(tileData, -1);
            if (holder == null) return null;
            boolean[] blocked = new boolean[6];
            for (Direction face : Direction.values()) {
                PipePluggable plug = holder.getPluggable(face);
                if (plug != PipePluggable.EMPTY) {
                    try {
                        blocked[face.ordinal()] = plug.isBlocking();
                    } catch (RuntimeException | LinkageError brokenAddon) {
                        // Unknown addon attachment: never draw a speculative connection.
                        blocked[face.ordinal()] = true;
                    }
                }
            }
            return new LinkProfile(holder.pipe, blocked);
        } catch (InvalidInputDataException | RuntimeException | LinkageError failure) {
            return null;
        }
    }

    public static boolean canConnect(LinkProfile a, LinkProfile b, Direction side) {
        if (a == null || b == null || a.blocked[side.ordinal()]
            || b.blocked[side.getOpposite().ordinal()]) return false;
        try {
            // This is the real BC compatibility rule (colours, behaviours and flows).
            // It uses only these offline Pipe objects, not a live world or neighbour tile.
            return Pipe.canPipesConnect(side, a.pipe, b.pipe);
        } catch (RuntimeException | LinkageError failedAddon) {
            return false;
        }
    }

    /**
     * Recreate the mesh using the connections inferred from the saved neighbouring
     * schematics. BlueprintSecurity intentionally strips the live `con` tag, so
     * reading it unconditionally used to render disconnected stumps.
     */
    public static Mesh reconstruct(CompoundTag tileData, int connectionMask) {
        try {
            OfflineHolder holder = readOfflineHolder(tileData, connectionMask);
            if (holder == null) return null;
            Pipe pipe = holder.pipe;
            List<MutableQuad> opaque = new ArrayList<>();
            List<MutableQuad> glass = new ArrayList<>();
            copy(opaque, PipeModelCacheBase.generator.generateCutout(new PipeBaseCutoutKey(pipe.getModel())));
            // 1.21.1's pipe model replaces baked per-vertex alpha with atlas
            // material sprites. Direct rendering must do the same for glass
            // facades/paint, otherwise alpha is lost under Sodium/Iris.
            copy(glass, LegacyMaterialAlpha.rewriteAll(
                PipeModelCacheBase.generator.generateTranslucent(new PipeBaseTranslucentKey(pipe.getModel()))));
            copy(opaque, PipeModelCachePluggable.cacheCutoutAll.bake(new PluggableKey(RenderType.cutout(), holder)));
            copy(glass, LegacyMaterialAlpha.rewriteAll(
                PipeModelCachePluggable.cacheTranslucentAll.bake(new PluggableKey(RenderType.translucent(), holder))));
            List<PipePluggable> dynamic = new ArrayList<>();
            for (PipePluggable plug : holder.plugs) {
                if (plug != null && PipeRegistryClient.getPlugRenderer(plug) != null) {
                    dynamic.add(plug);
                }
            }
            Mesh result = new Mesh(List.copyOf(opaque), List.copyOf(glass), List.copyOf(dynamic));
            return result.isEmpty() ? null : result;
        } catch (InvalidInputDataException | RuntimeException | LinkageError failure) {
            // One unavailable addon should not hide every other schematic cell.
            return null;
        }
    }

    private static CompoundTag getCompound(CompoundTag parent, String key) {
        return parent == null ? new CompoundTag() : parent.getCompound(key);
    }

    private static String getString(CompoundTag parent, String key) {
        return parent == null ? "" : parent.getString(key);
    }

    private static OfflineHolder readOfflineHolder(CompoundTag tileData, int connectionMask)
        throws InvalidInputDataException {
        if (tileData == null) return null;
        CompoundTag pipeTag = getCompound(tileData, "pipe");
        if (getString(pipeTag, "def").isEmpty()) return null;
        if (connectionMask >= 0) {
            pipeTag = pipeTag.copy();
            pipeTag.putInt("con", connectionMask);
        }
        OfflineHolder holder = new OfflineHolder();
        holder.pipe = new Pipe(holder, pipeTag);
        CompoundTag plugs = getCompound(tileData, "plugs");
        for (Direction side : Direction.values()) {
            CompoundTag saved = getCompound(plugs, side.getName());
            String id = getString(saved, "id");
            if (id.isEmpty() || PipeApi.pluggableRegistry == null) continue;
            try {
                PluggableDefinition definition = PipeApi.pluggableRegistry.getDefinition(ResourceLocation.parse(id));
                if (definition == null) continue;
                PipePluggable plug = definition.readFromNbt(holder, side,
                    getCompound(saved, "data"));
                if (plug != null) holder.plugs[side.ordinal()] = plug;
            } catch (RuntimeException | LinkageError ignored) {
                // Missing or broken addon attachments do not suppress the pipe body.
            }
        }
        return holder;
    }

    /** Convert quads once per unique schematic, rather than once per block and animation frame. */
    private static void copy(List<MutableQuad> target, List<BakedQuad> from) {
        for (var quad : from) target.add(new MutableQuad().fromBakedBlock(quad));
    }

    /** Direct 1.21.1 GUI rendering: a separate buffer for each actual material layer. */
    public static void submit(Mesh mesh, PoseStack pose, MultiBufferSource buffers, int light) {
        if (mesh == null) return;
        if (!mesh.cutout().isEmpty()) draw(mesh.cutout(), pose.last(),
            buffers.getBuffer(RenderType.cutout()), light);
        if (!mesh.translucent().isEmpty()) draw(mesh.translucent(), pose.last(),
            buffers.getBuffer(RenderType.translucent()), light);
        if (!mesh.dynamicPlugs().isEmpty()) renderDynamic(mesh, pose, buffers, light);
    }

    /** Render-only gate/pulsar/glass-facade path. Never ticks the reconstructed pipe. */
    private static void renderDynamic(Mesh mesh, PoseStack pose, MultiBufferSource buffers, int light) {
        for (PipePluggable plug : mesh.dynamicPlugs()) {
            try {
                renderPlug(plug, pose, buffers, light);
            } catch (RuntimeException | LinkageError brokenAddon) {
                // Addon renderers may depend on a live world: skip only that attachment.
            }
        }
    }

    private static <P extends PipePluggable> void renderPlug(P plug, PoseStack pose,
        MultiBufferSource buffers, int light) {
        IPlugDynamicRenderer<P> renderer = PipeRegistryClient.getPlugRenderer(plug);
        if (renderer != null) renderer.render(plug, 0, pose, buffers, light, OverlayTexture.NO_OVERLAY);
    }

    private static void draw(List<MutableQuad> quads, PoseStack.Pose at, VertexConsumer consumer, int light) {
        for (MutableQuad source : quads) {
            MutableQuad quad = new MutableQuad(source).lighti(light);
            for (var vertex : quad.vertexs) vertex.overlay(OverlayTexture.NO_OVERLAY);
            quad.render(at.pose(), at.normal(), consumer);
        }
    }

    /** Render-only pipe holder: never points to or mutates a live block entity. */
    private static final class OfflineHolder implements IPipeHolder {
        Pipe pipe;
        final PipePluggable[] plugs = new PipePluggable[6];

        @Override public Level getPipeWorld() { return null; }
        @Override public BlockPos getPipePos() { return BlockPos.ZERO; }
        @Override public BlockEntity getPipeTile() { return null; }
        @Override public IPipe getPipe() { return pipe; }
        @Override public boolean canPlayerInteract(Player player) { return false; }
        @Override public PipePluggable getPluggable(Direction side) {
            PipePluggable plug = plugs[side.ordinal()];
            return plug == null ? PipePluggable.EMPTY : plug;
        }
        @Override public BlockEntity getNeighbourTile(Direction side) { return null; }
        @Override public IPipe getNeighbourPipe(Direction side) { return Pipe.EMPTY; }
        @Override public <T> T getCapabilityFromPipe(Direction side, BlockCapability<T, Direction> capability) { return null; }
        @Override public WireManager getWireManager() { return null; }
        @Override public GameProfile getOwner() { return null; }
        @Override public boolean fireEvent(PipeEvent event) { return false; }
        @Override public void scheduleRenderUpdate() {}
        @Override public void scheduleNetworkUpdate(PipeMessageReceiver... parts) {}
        @Override public void scheduleNetworkGuiUpdate(PipeMessageReceiver... parts) {}
        @Override public void sendMessage(PipeMessageReceiver to, IWriter writer) {}
        @Override public void sendGuiMessage(PipeMessageReceiver to, IWriter writer) {}
        @Override public void onPlayerOpen(Player player) {}
        @Override public void onPlayerClose(Player player) {}
        @Override public int getRedstoneInput(Direction side) { return 0; }
        @Override public boolean setRedstoneOutput(Direction side, int value) { return false; }
    }
}
