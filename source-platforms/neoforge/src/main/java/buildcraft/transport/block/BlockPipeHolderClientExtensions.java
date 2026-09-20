package buildcraft.transport.block;

import java.util.List;

import javax.annotation.Nullable;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.internal.EnumWirePart;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableModelKey;
import buildcraft.lib.client.sprite.SingleSpriteSet;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.transport.client.model.PipeModelCacheBase;
import buildcraft.transport.client.model.PipeModelCachePluggable;
import buildcraft.transport.client.render.PipeWireRenderer;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.wire.EnumWireBetween;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.extensions.common.IClientBlockExtensions;

final class BlockPipeHolderClientExtensions implements IClientBlockExtensions {
    static final BlockPipeHolderClientExtensions INSTANCE = new BlockPipeHolderClientExtensions();

    private BlockPipeHolderClientExtensions() {
    }

    @Nullable
    private static HitSpriteInfo getHitSpriteInfo(BlockHitResult target, TilePipeHolder pipeHolder) {
        BlockPos pos = pipeHolder.getBlockPos();

        Vec3 location = target.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
        Vec3 dvec = location.subtract(0.5f, 0.5f, 0.5f).scale(-0.00125);
        location = location.add(dvec);
        int p = BlockPipeHolder.computSubhit(pipeHolder, location, BlockPipeHolder.computHitOctant(location));
        VoxelShape aabb = null;
        TextureAtlasSprite sprite = SpriteUtil.missingSprite();
        BCLog.d("" + p);
        if (0 <= p && p <= 6) {
            if (pipeHolder.getPipe() == buildcraft.transport.pipe.Pipe.EMPTY) {
                return null;
            }
            aabb = p == 0 ? BlockPipeHolder.BOX_CENTER : BlockPipeHolder.BOX_FACES[p - 1];
            PipeDefinition def = pipeHolder.getPipe().definition;
            TextureAtlasSprite[] sprites = PipeModelCacheBase.generator.getItemSprites(def);
            sprite = sprites == null || sprites.length == 0 || sprites[0] == null
                ? SpriteUtil.missingSprite()
                : sprites[0];
        } else if (6 + 1 <= p && p < 6 + 6 + 1) {
            PipePluggable plug = pipeHolder.getPluggable(Direction.values()[p - 6 - 1]);
            if (plug == null) {
                return null;
            }
            aabb = plug.getBoundingBox();
            if (aabb == null) {
                return null;
            }
            PluggableModelKey keyC = plug.getModelRenderKey(RenderType.cutout());
            PluggableModelKey keyT = plug.getModelRenderKey(RenderType.translucent());
            if (keyC == null && keyT == null) {
                return null;
            }
            List<BakedQuad> quads = null;
            if (keyC != null) quads = PipeModelCachePluggable.cacheCutoutSingle.bake(keyC);
            if (quads == null || quads.isEmpty()) {
                if (keyT == null) {
                    return null;
                }
                quads = PipeModelCachePluggable.cacheTranslucentSingle.bake(keyT);
                if (quads == null || quads.isEmpty()) {
                    return null;
                }
            }
            sprite = quads.get(0).getSprite();
        } else if (6 + 6 + 1 <= p && p < 1 + 6 + 6 + 8) {
            EnumWirePart wirePart = EnumWirePart.values()[p - 6 - 6 - 1];
            aabb = wirePart.boundingBox;
            DyeColor colour = pipeHolder.getWireManager().getColorOfPart(wirePart);
            if (colour == null) {
                return null;
            }
            sprite = PipeWireRenderer.getWireSprite(colour).getSprite();
        } else if (6 + 6 + 1 + 8 < p && p <= 6 + 6 + 1 + 8 + 36) {
            EnumWireBetween wireBetween = EnumWireBetween.values()[p - 6 - 6 - 1 - 8];
            aabb = wireBetween.boundingBox;
            DyeColor colour = pipeHolder.getWireManager().betweens.get(wireBetween);
            if (colour == null) {
                return null;
            }
            sprite = PipeWireRenderer.getWireSprite(colour).getSprite();
        } else {
            return null;
        }
        if (aabb == null) {
            throw new IllegalStateException("Null aabb for index " + p + " (and sprite " + sprite + ")");
        }
        if (sprite == null) {
            // Cross-version NeoForge fallback. 1.21.11 exposes ModelManager#getMissingBlockStateModel(),
            // but 1.21.1 does not. SpriteUtil resolves the missing sprite directly from the block atlas
            // and is valid for both modern targets.
            sprite = SpriteUtil.missingSprite();
        }
        if (sprite == null) {
            return null;
        }
        return new HitSpriteInfo(aabb, sprite);
    }

    @Override
    public boolean addHitEffects(BlockState state, Level world, HitResult hit, ParticleEngine manager) {
        if (!(hit instanceof BlockHitResult target)) {
            return false;
        }
        BlockPos pos = target.getBlockPos();
        BlockEntity te = world.getBlockEntity(pos);
        if (te instanceof TilePipeHolder) {
            TilePipeHolder pipeHolder = ((TilePipeHolder) te);
            HitSpriteInfo info = getHitSpriteInfo(target, pipeHolder);

            if (info == null) {
                return false;
            }

            double x = Math.random() * (info.aabb.max(Axis.X) - info.aabb.min(Axis.X)) + info.aabb.min(Axis.X);
            double y = Math.random() * (info.aabb.max(Axis.Y) - info.aabb.min(Axis.Y)) + info.aabb.min(Axis.Y);
            double z = Math.random() * (info.aabb.max(Axis.Z) - info.aabb.min(Axis.Z)) + info.aabb.min(Axis.Z);

            int hitface = BlockPipeHolder.computHitFacing(target.getLocation());
            Direction dir = hitface == 0 ? target.getDirection() : Direction.from3DDataValue(hitface - 1);
            switch (dir) {
                case DOWN:
                    y = info.aabb.min(Axis.Y) - 0.1;
                    break;
                case UP:
                    y = info.aabb.max(Axis.Y) + 0.1;
                    break;
                case NORTH:
                    z = info.aabb.min(Axis.Z) - 0.1;
                    break;
                case SOUTH:
                    z = info.aabb.max(Axis.Z) + 0.1;
                    break;
                case WEST:
                    x = info.aabb.min(Axis.X) - 0.1;
                    break;
                default:
                    x = info.aabb.max(Axis.X) + 0.1;
                    break;
            }

            x += pos.getX();
            y += pos.getY();
            z += pos.getZ();

            TerrainParticle particle = new TerrainParticle((ClientLevel) world, x, y, z, 0, 0, 0, state, pos);
            SingleSpriteSet spriteSet = new SingleSpriteSet(info.sprite);
            particle.pickSprite(spriteSet);
            particle.setPower(0.2f);
            particle.scale(0.6f);
            manager.add(particle);
            return true;
        }
        return false;
    }

    @Override
    public boolean addDestroyEffects(BlockState state, Level world, BlockPos pos, ParticleEngine manager) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (hit == null || !(hit instanceof BlockHitResult hitResult) || !pos.equals(hitResult.getBlockPos())) {
            return false;
        }
        BlockEntity te = world.getBlockEntity(pos);
        if (te instanceof TilePipeHolder pipeHolder) {
            HitSpriteInfo info = getHitSpriteInfo(hitResult, pipeHolder);
            if (info == null) {
                return false;
            }

            double sizeX = info.aabb.max(Axis.X) - info.aabb.min(Axis.X);
            double sizeY = info.aabb.max(Axis.Y) - info.aabb.min(Axis.Y);
            double sizeZ = info.aabb.max(Axis.Z) - info.aabb.min(Axis.Z);

            int countX = (int) Math.max(2, 4 * sizeX);
            int countY = (int) Math.max(2, 4 * sizeY);
            int countZ = (int) Math.max(2, 4 * sizeZ);

            for (int x = 0; x < countX; x++) {
                for (int y = 0; y < countY; y++) {
                    for (int z = 0; z < countZ; z++) {

                        double d4 = ((double)x + 0.5D) / (double)countX;
                        double d5 = ((double)y + 0.5D) / (double)countY;
                        double d6 = ((double)z + 0.5D) / (double)countX;

                        double _x = pos.getX() + info.aabb.min(Axis.X) + (x + 0.5) * sizeX / countX;
                        double _y = pos.getY() + info.aabb.min(Axis.Y) + (y + 0.5) * sizeY / countY;
                        double _z = pos.getZ() + info.aabb.min(Axis.Z) + (z + 0.5) * sizeZ / countZ;

                        TerrainParticle particle = new TerrainParticle((ClientLevel) world, _x, _y, _z, d4 - 0.5, d5 - 0.5, d6 - 0.5, state, pos);
                        SingleSpriteSet spriteSet = new SingleSpriteSet(info.sprite);
                        particle.pickSprite(spriteSet);
                        manager.add(particle);
                    }
                }
            }
            return true;
        }
        return false;
    }

    private static final class HitSpriteInfo {
        final VoxelShape aabb;
        final TextureAtlasSprite sprite;

        HitSpriteInfo(VoxelShape aabb, TextureAtlasSprite sprite) {
            this.aabb = aabb;
            this.sprite = sprite;
        }
    }
}
