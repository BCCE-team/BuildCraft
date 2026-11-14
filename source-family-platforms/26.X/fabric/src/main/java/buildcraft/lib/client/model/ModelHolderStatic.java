/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model;

import buildcraft.lib.platform.client.ClientModelBaking;
import buildcraft.lib.platform.client.ClientStandaloneModel;
import buildcraft.lib.client.model.ModelUtil.TexturedFace;
import buildcraft.lib.client.model.json.JsonTexture;
import buildcraft.lib.client.model.json.JsonVariableModel;
import buildcraft.lib.expression.DefaultContexts;
import buildcraft.lib.compat.RenderCompat;
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
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
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
        // texture substitutions, and the standalone baker resolves the JSON's own texture slots natively.
        this.standaloneKey = new ClientStandaloneModel(this.modelLocation);
    }

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

    protected void onTextureStitch(Set<Identifier> toRegisterSprites) {
        // Native standalone baking is not currently dispatched by FabricModelLifecycle263.
        // The atlas bridge below uses the same source JSON directly once its sprites exist.
    }

    /**
     * Fabric fallback for static standalone pluggable models. No synthetic geometry is added:
     * the existing JSON elements, faces, UVs, texture references and inherited parent are used.
     * Runs only when the block atlas is ready or changes (including F3+T).
     */
    public void reloadStandaloneFromResources() {
        quads = null;
        unseen = true;
        Identifier resource = Identifier.fromNamespaceAndPath(
            modelLocation.getNamespace(), "models/" + modelLocation.getPath() + ".json");
        try {
            Resource jsonResource = Minecraft.getInstance().getResourceManager().getResource(resource).orElse(null);
            if (jsonResource == null) throw new java.io.IOException("Missing resource " + resource);
            JsonObject modelJson;
            try (BufferedReader reader = jsonResource.openAsReader()) {
                modelJson = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (modelJson.has("parent")) {
                // Vanilla model parents are relative to assets/*/models/; our variable-model
                // loader accepts resource-root IDs. Translate only for this in-memory copy.
                Identifier parent = Identifier.parse(modelJson.get("parent").getAsString());
                if (!parent.getPath().startsWith("models/")) {
                    modelJson.addProperty("parent", Identifier.fromNamespaceAndPath(parent.getNamespace(),
                        "models/" + parent.getPath()).toString());
                }
            }
            JsonVariableModel model = new JsonVariableModel(modelJson,
                DefaultContexts.createWithAll("standalone:" + modelLocation), new ResourceLoaderContext());
            MutableQuad[] cutout = model.bakePart(model.cutoutElements, lookup -> resolveStaticTexture(model, lookup));
            MutableQuad[] translucent = model.bakePart(model.translucentElements,
                lookup -> resolveStaticTexture(model, lookup));
            if (isTranslucentModel(modelLocation, 0)) {
                // Vanilla elements are represented as cutout geometry by the shared JSON reader.
                MutableQuad[] merged = new MutableQuad[cutout.length + translucent.length];
                System.arraycopy(cutout, 0, merged, 0, cutout.length);
                System.arraycopy(translucent, 0, merged, cutout.length, translucent.length);
                quads = new MutableQuad[][] { MutableQuad.EMPTY_ARRAY, merged };
            } else {
                quads = new MutableQuad[][] { cutout, translucent };
            }
            if (quads[0].length == 0 && quads[1].length == 0) {
                failReason = "Standalone resource has no faces: " + resource;
                BCLog.logger.warn("[lib.model.holder] " + failReason);
            } else {
                failReason = null;
            }
        } catch (Exception | LinkageError error) {
            failReason = "Standalone JSON bake failed: " + error.getMessage();
            quads = emptyLayers();
            BCLog.logger.warn("[lib.model.holder] " + failReason + " (" + resource + ")", error);
        }
        unseen = false;
    }

    private static TexturedFace resolveStaticTexture(JsonVariableModel model, String key) {
        JsonTexture texture = new JsonTexture(key);
        for (int attempts = 0; texture.location.startsWith("#") && attempts < 16; attempts++) {
            String name = texture.location.substring(1);
            JsonTexture inherited = model.textures.get(name);
            if (inherited == null) break;
            texture = texture.inParent(inherited);
        }
        TexturedFace result = new TexturedFace();
        result.faceData = texture.faceData;
        result.sprite = texture.location.startsWith("#")
            ? buildcraft.lib.misc.SpriteUtil.missingSprite()
            : RenderCompat.blockSprites().apply(Identifier.parse(texture.location));
        return result;
    }

    protected void onModelBakePre(ClientModelBaking.Additional event) {
        event.register(standaloneKey);
    }

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

    /** Converts a native immutable quad into the legacy BuildCraft quad representation. */
    private static MutableQuad fromNativeQuad(BakedQuad source) {
        MutableQuad quad = new MutableQuad(source.materialInfo().tintIndex(), source.direction(),
            // A null override uses the face direction for vanilla diffuse shading.
            // An explicit UP override reproduces the pre-26.3 "shade: false" behavior.
            source.materialInfo().shadeDirectionOverride() == null);
        quad.setSprite(source.materialInfo().sprite());

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
