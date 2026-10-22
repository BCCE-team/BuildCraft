//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model;

import buildcraft.lib.platform.client.ClientModelBaking;
import buildcraft.lib.platform.client.ClientStandaloneModel;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.QuadCollection;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/** Holds a model that will never change except if the JSON file it is defined from is changed. */
public class ModelHolderStatic extends ModelHolder {
    private final ClientStandaloneModel standaloneKey;
    private MutableQuad[][] quads;
    private boolean unseen = true;

    public ModelHolderStatic(String location) {
        this(location, ImmutableMap.of(), false);
    }

    public ModelHolderStatic(String location, String[][] textures, boolean allowTextureFallthrough) {
        this(location, genTextureMap(textures), allowTextureFallthrough);
    }

    public ModelHolderStatic(String modelLocation, ImmutableMap<String, String> textureLookup, boolean allowTextureFallthrough) {
        super(modelLocation);
        // Kept in the signature for source/API compatibility. No current BCCE caller supplies custom static-model
        // texture substitutions, and the 1.21.11 standalone baker resolves the JSON's own texture slots natively.
        this.standaloneKey = new ClientStandaloneModel(this.modelLocation);
    }

    @Override
    public boolean hasBakedQuads() {
        return quads != null;
    }

    private static ImmutableMap<String, String> genTextureMap(String[][] textures) {
        if (textures == null || textures.length == 0) {
            return ImmutableMap.of();
        }
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (String[] ar : textures) {
            if (ar.length != 2) {
                throw new IllegalArgumentException("Must have 2 elements (key,value) but got " + Arrays.toString(ar));
            }
            if (!ar[0].startsWith("~")) {
                throw new IllegalArgumentException("Key must start with '~' otherwise it will never be used!");
            }
            builder.put(ar[0], ar[1]);
        }
        return builder.build();
    }

    @Override
    protected void onTextureStitch(Set<Identifier> toRegisterSprites) {
        // The standalone model pipeline resolves and stitches its own texture dependencies.
    }

    @Override
    protected void onModelBakePre(ClientModelBaking.Additional event) {
        event.register(standaloneKey);
    }

    @Override
    protected void onModelBake(ClientModelBaking.Completed event) {
        try {
            QuadCollection model = standaloneKey.get(event.getModelManager());
            if (model == null || model.getAll().isEmpty()) {
                failReason = "Standalone model baked no quads";
                quads = emptyLayers();
                unseen = false;
                return;
            }

            List<MutableQuad> baked = new ArrayList<>(model.getAll().size());
            for (BakedQuad source : model.getAll()) {
                baked.add(fromNativeQuad(source));
            }
            MutableQuad[] array = baked.toArray(MutableQuad[]::new);

            // A JSON block model has one inherited render_type. Preserve it when assigning standalone quads to
            // BuildCraft render buckets.
            if (isTranslucentModel(modelLocation, 0)) {
                quads = new MutableQuad[][] { MutableQuad.EMPTY_ARRAY, array };
            } else {
                quads = new MutableQuad[][] { array, MutableQuad.EMPTY_ARRAY };
            }
            failReason = null;
            unseen = false;
        } catch (RuntimeException | LinkageError error) {
            failReason = "Standalone model bake failed: " + error.getMessage();
            quads = emptyLayers();
            unseen = false;
            // Do not synthesize unrelated iron/stone boxes. A bad model/resource pack should fail as that model,
            // not silently turn a plug into geometry that never existed in the source JSON.
            BCLog.logger.warn("[lib.model.holder] Failed to bake standalone model " + modelLocation, error);
        }
    }

    private static MutableQuad[][] emptyLayers() {
        return new MutableQuad[][] { MutableQuad.EMPTY_ARRAY, MutableQuad.EMPTY_ARRAY };
    }

    private static boolean isTranslucentModel(Identifier location, int depth) {
        if (location == null || depth > 16) {
            return false;
        }
        Identifier json = Identifier.fromNamespaceAndPath(
            location.getNamespace(),
            "models/" + location.getPath() + ".json"
        );
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(json).orElse(null);
            if (resource == null) return false;
            try (BufferedReader reader = resource.openAsReader()) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) return false;
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("render_type")) {
                    String renderType = object.get("render_type").getAsString();
                    return renderType.equals("translucent") || renderType.endsWith(":translucent");
                }
                if (object.has("parent")) {
                    return isTranslucentModel(Identifier.parse(object.get("parent").getAsString()), depth + 1);
                }
            }
        } catch (Exception error) {
            BCLog.logger.debug("[lib.model.holder] Unable to inspect render_type for " + location, error);
        }
        return false;
    }

    /** Converts Minecraft 1.21.11's native immutable quad into the legacy BuildCraft quad representation. */
    private static MutableQuad fromNativeQuad(BakedQuad source) {
        MutableQuad quad = new MutableQuad(source.tintIndex(), source.direction(), source.shade());
        quad.setSprite(source.sprite());

        Direction direction = source.direction();
        Vec3i normal = direction == null ? null : direction.getUnitVec3i();
        for (int i = 0; i < 4; i++) {
            var sourcePos = source.position(i);
            MutableVertex vertex = quad.vertexs[i];
            vertex.positionf(sourcePos.x(), sourcePos.y(), sourcePos.z());

            long packedUv = source.packedUV(i);
            vertex.texf(
                Float.intBitsToFloat((int) (packedUv >>> 32)),
                Float.intBitsToFloat((int) packedUv)
            );

            if (normal != null) {
                vertex.normalf(normal.getX(), normal.getY(), normal.getZ());
            }
        }
        if (normal == null) {
            quad.setCalculatedNormal();
        }
        return quad;
    }

    public MutableQuad[] getCutoutQuads() {
        return getQuadsChecking()[0];
    }

    public MutableQuad[] getTranslucentQuads() {
        return getQuadsChecking()[1];
    }

    private MutableQuad[][] getQuadsChecking() {
        if (quads == null) {
            if (unseen) {
                unseen = false;
                String warnText = "[lib.model.holder] Tried to use the model " + modelLocation + " before it was baked!";
                if (ModelHolderRegistry.DEBUG) {
                    BCLog.logger.warn(warnText, new Throwable());
                } else {
                    BCLog.logger.warn(warnText);
                }
            }
            return emptyLayers();
        }
        return quads;
    }
}
