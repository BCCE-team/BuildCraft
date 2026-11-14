/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.model;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad;
import net.minecraft.world.item.DyeColor;

import buildcraft.transport.internal.pipe.EnumPipeColourType;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeFaceTex;

import buildcraft.lib.client.model.IModelCache;
import buildcraft.lib.client.model.ModelCache;

import buildcraft.transport.client.model.key.PipeModelKey;

public class PipeModelCacheBase {
    public static IPipeBaseModelGen generator = PipeBaseModelGenStandard.INSTANCE;

    static final IModelCache<PipeBaseCutoutKey> cacheCutout;
    static final IModelCache<PipeBaseTranslucentKey> cacheTranslucent;

    static {
        cacheCutout = new ModelCache<>(PipeModelCacheBase::generateCutout);
        cacheTranslucent = new ModelCache<>(PipeModelCacheBase::generateTranslucent);
    }

    /** Flush every atlas-dependent pipe body after the Fabric block atlas is replaced. */
    public static void clearCaches() {
        cacheCutout.clear();
        cacheTranslucent.clear();
    }

    private static List<BakedQuad> generateCutout(PipeBaseCutoutKey key) {
        return generator.generateCutout(key);
    }

    private static List<BakedQuad> generateTranslucent(PipeBaseTranslucentKey key) {
        return generator.generateTranslucent(key);
    }

//    long PBCK ;//(1:modelType,2-49:size:8:UVs,50-58:TextureNum,59-64:shouldRoateTex)
    
    public static final class PipeBaseCutoutKey {
        public final PipeDefinition definition;
        public final PipeFaceTex centerSprite;
        public final PipeFaceTex[] sideSprites;
        public final float[] connections;
        public final DyeColor colour;
        public final EnumPipeColourType colourType;
        private final int hashCode;

        public PipeBaseCutoutKey(PipeModelKey key) {
            definition = key.definition;
            centerSprite = key.center;
            sideSprites = key.sides;
            connections = key.connected;
            EnumPipeColourType defColourType = key.getColourType();
            if (key.colour != null && canBakeCutoutColour(defColourType)) {
                this.colour = key.colour;
                this.colourType = defColourType;
            } else {
                this.colour = null;
                this.colourType = null;
            }
            hashCode = Objects.hash(System.identityHashCode(definition), centerSprite, Arrays.hashCode(sideSprites),
                Arrays.hashCode(connections), colour, colourType);
        }

        private static boolean canBakeCutoutColour(EnumPipeColourType type) {
            return type == EnumPipeColourType.BORDER_OUTER || type == EnumPipeColourType.BORDER_INNER;
        }

        public int hashCode() {
            return hashCode;
        }

        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            PipeBaseCutoutKey other = (PipeBaseCutoutKey) obj;
            if (definition != other.definition) return false;
            if (centerSprite != other.centerSprite) return false;
            if (colour != other.colour) return false;
            if (colourType != other.colourType) return false;
            if (!Arrays.equals(connections, other.connections)) return false;
            if (!Arrays.equals(sideSprites, other.sideSprites)) return false;
            return true;
        }

        public String toString() {
            return "PipeBaseCutoutKey [center=" + centerSprite + ", sides=" + Arrays.toString(sideSprites)
                + ", connections=" + Arrays.toString(connections) + "]";
        }
    }

    public static final class PipeBaseTranslucentKey {
        public final DyeColor colour;
        public final float[] connections;
        private final int hashCode;

        public PipeBaseTranslucentKey(PipeModelKey key) {
            if (key.getColourType() == EnumPipeColourType.TRANSLUCENT) {
                this.colour = key.colour;
            } else {
                this.colour = null;
            }
            if (colour == null) {
                connections = null;
                hashCode = 0;
            } else {
                connections = key.connected;
                hashCode = Objects.hash(colour, Arrays.hashCode(connections));
            }
        }

        public boolean shouldRender() {
            return colour != null;
        }

        public int hashCode() {
            return hashCode;
        }

        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (!(obj instanceof PipeBaseTranslucentKey)) return false;
            PipeBaseTranslucentKey other = (PipeBaseTranslucentKey) obj;
            /*
             * If we don't have any translucency and neither does the other then we don't care what the other variables
             * are and are considered equal to the other one.
             */
            if (!shouldRender() && !other.shouldRender()) return true;
            if (!Arrays.equals(connections, other.connections)) return false;
            return colour == other.colour;
        }

        public String toString() {
            return "PipeBaseTranslucentKey [colour=" + colour + ", connections=" + Arrays.toString(connections) + "]";
        }
    }
}
