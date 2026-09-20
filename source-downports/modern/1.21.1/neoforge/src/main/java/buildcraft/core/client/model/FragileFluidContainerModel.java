/*
 * Copyright (c) Forge Development LLC and contributors.
 * Adapted for BuildCraft to work around Forge 1.21.1 FluidUtil#getFluidHandler
 * returning an empty capability result for ItemStacks.
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package buildcraft.core.client.model;

import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.google.common.collect.Maps;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import com.mojang.math.Transformation;

import buildcraft.core.item.ItemFragileFluidContainer;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.NeoForgeRenderTypes;
import net.neoforged.neoforge.client.RenderTypeGroup;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.model.CompositeModel;
import net.neoforged.neoforge.client.model.QuadTransformers;
import net.neoforged.neoforge.client.model.SimpleModelState;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import net.neoforged.neoforge.client.model.geometry.StandaloneGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.core.registries.BuiltInRegistries;

import static net.neoforged.neoforge.client.model.geometry.StandaloneGeometryBakingContext.LOCATION;

/**
 * Dynamic fluid-container model for BuildCraft's fragile fluid shard.
 *
 * <p>Forge's stock model resolves the contained fluid through
 * {@code FluidUtil.getFluidContained}. In Forge 52.x that helper currently
 * returns no item fluid handler, so the stock model always falls back to its
 * JSON fluid. This implementation reads the shard's data component directly.</p>
 */
public final class FragileFluidContainerModel implements IUnbakedGeometry<FragileFluidContainerModel> {
    private static final Transformation FLUID_TRANSFORM = new Transformation(
        new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1.002f), new Quaternionf()
    );
    private static final Transformation COVER_TRANSFORM = new Transformation(
        new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1.004f), new Quaternionf()
    );

    private final Fluid fluid;
    private final boolean flipGas;
    private final boolean coverIsMask;
    private final boolean applyFluidLuminosity;

    private FragileFluidContainerModel(
        Fluid fluid,
        boolean flipGas,
        boolean coverIsMask,
        boolean applyFluidLuminosity
    ) {
        this.fluid = fluid;
        this.flipGas = flipGas;
        this.coverIsMask = coverIsMask;
        this.applyFluidLuminosity = applyFluidLuminosity;
    }

    private static RenderTypeGroup getLayerRenderTypes(boolean unlit) {
        return new RenderTypeGroup(
            RenderType.translucent(),
            unlit
                ? NeoForgeRenderTypes.ITEM_UNSORTED_UNLIT_TRANSLUCENT.get()
                : NeoForgeRenderTypes.ITEM_UNSORTED_TRANSLUCENT.get()
        );
    }

    private FragileFluidContainerModel withFluid(Fluid newFluid) {
        return new FragileFluidContainerModel(newFluid, flipGas, coverIsMask, applyFluidLuminosity);
    }

    @Override
    public BakedModel bake(
        IGeometryBakingContext context,
        ModelBaker baker,
        Function<Material, TextureAtlasSprite> spriteGetter,
        ModelState modelState,
        ItemOverrides overrides
    ) {
        Material particleLocation = context.hasMaterial("particle") ? context.getMaterial("particle") : null;
        Material baseLocation = context.hasMaterial("base") ? context.getMaterial("base") : null;
        Material fluidMaskLocation = context.hasMaterial("fluid") ? context.getMaterial("fluid") : null;
        Material coverLocation = context.hasMaterial("cover") ? context.getMaterial("cover") : null;

        TextureAtlasSprite baseSprite = baseLocation != null ? spriteGetter.apply(baseLocation) : null;
        TextureAtlasSprite fluidSprite = fluid != Fluids.EMPTY
            ? spriteGetter.apply(ClientHooks.getBlockMaterial(IClientFluidTypeExtensions.of(fluid).getStillTexture()))
            : null;
        TextureAtlasSprite coverSprite = coverLocation != null && (!coverIsMask || baseLocation != null)
            ? spriteGetter.apply(coverLocation)
            : null;
        TextureAtlasSprite particleSprite = particleLocation != null ? spriteGetter.apply(particleLocation) : null;

        if (particleSprite == null) {
            particleSprite = fluidSprite;
        }
        if (particleSprite == null) {
            particleSprite = baseSprite;
        }
        if (particleSprite == null && !coverIsMask) {
            particleSprite = coverSprite;
        }

        if (flipGas && fluid != Fluids.EMPTY && fluid.getFluidType().isLighterThanAir()) {
            modelState = new SimpleModelState(
                modelState.getRotation().compose(
                    new Transformation(null, new Quaternionf(0, 0, 1, 0), null, null)
                )
            );
        }

        IGeometryBakingContext itemContext = StandaloneGeometryBakingContext.builder(context)
            .withGui3d(false)
            .withUseBlockLight(false)
            .build(LOCATION);
        var modelBuilder = CompositeModel.Baked.builder(
            itemContext,
            particleSprite,
            new ContainedFluidOverrideHandler(overrides, baker, itemContext, this),
            context.getTransforms()
        );

        RenderTypeGroup normalRenderTypes = getLayerRenderTypes(false);
        if (baseLocation != null && baseSprite != null) {
            var unbaked = UnbakedGeometryHelper.createUnbakedItemElements(0, baseSprite);
            var quads = UnbakedGeometryHelper.bakeElements(unbaked, ignored -> baseSprite, modelState);
            modelBuilder.addQuads(normalRenderTypes, quads);
        }

        if (fluidMaskLocation != null && fluidSprite != null) {
            TextureAtlasSprite templateSprite = spriteGetter.apply(fluidMaskLocation);
            if (templateSprite != null) {
                var transformedState = new SimpleModelState(
                    modelState.getRotation().compose(FLUID_TRANSFORM),
                    modelState.isUvLocked()
                );
                var unbaked = UnbakedGeometryHelper.createUnbakedItemMaskElements(1, templateSprite);
                var quads = UnbakedGeometryHelper.bakeElements(unbaked, ignored -> fluidSprite, transformedState);
                boolean emissive = applyFluidLuminosity && fluid.getFluidType().getLightLevel() > 0;
                RenderTypeGroup renderTypes = getLayerRenderTypes(emissive);
                if (emissive) {
                    QuadTransformers.settingMaxEmissivity().processInPlace(quads);
                }
                modelBuilder.addQuads(renderTypes, quads);
            }
        }

        if (coverSprite != null) {
            TextureAtlasSprite sprite = coverIsMask ? baseSprite : coverSprite;
            if (sprite != null) {
                var transformedState = new SimpleModelState(
                    modelState.getRotation().compose(COVER_TRANSFORM),
                    modelState.isUvLocked()
                );
                var unbaked = UnbakedGeometryHelper.createUnbakedItemMaskElements(2, coverSprite);
                var quads = UnbakedGeometryHelper.bakeElements(unbaked, ignored -> sprite, transformedState);
                modelBuilder.addQuads(normalRenderTypes, quads);
            }
        }

        modelBuilder.setParticle(particleSprite);
        return modelBuilder.build();
    }

    public static final class Loader implements IGeometryLoader<FragileFluidContainerModel> {
        public static final Loader INSTANCE = new Loader();

        private Loader() {
        }

        @Override
        public FragileFluidContainerModel read(
            JsonObject jsonObject,
            JsonDeserializationContext deserializationContext
        ) {
            String fluidId = jsonObject.has("fluid")
                ? GsonHelper.getAsString(jsonObject, "fluid")
                : "minecraft:empty";
            ResourceLocation fluidName = ResourceLocation.parse(fluidId);
            Fluid fluid = BuiltInRegistries.FLUID.get(fluidName);
            if (fluid == null) {
                fluid = Fluids.EMPTY;
            }
            boolean flip = GsonHelper.getAsBoolean(jsonObject, "flip_gas", false);
            boolean coverIsMask = GsonHelper.getAsBoolean(jsonObject, "cover_is_mask", true);
            boolean applyFluidLuminosity = GsonHelper.getAsBoolean(jsonObject, "apply_fluid_luminosity", true);
            return new FragileFluidContainerModel(fluid, flip, coverIsMask, applyFluidLuminosity);
        }
    }

    private static final class ContainedFluidOverrideHandler extends ItemOverrides {
        private final Map<ResourceLocation, BakedModel> cache = Maps.newHashMap();
        private final ItemOverrides nested;
        private final ModelBaker baker;
        private final IGeometryBakingContext owner;
        private final FragileFluidContainerModel parent;

        private ContainedFluidOverrideHandler(
            ItemOverrides nested,
            ModelBaker baker,
            IGeometryBakingContext owner,
            FragileFluidContainerModel parent
        ) {
            this.nested = nested;
            this.baker = baker;
            this.owner = owner;
            this.parent = parent;
        }

        @Override
        public BakedModel resolve(
            BakedModel originalModel,
            ItemStack stack,
            @Nullable ClientLevel level,
            @Nullable LivingEntity entity,
            int seed
        ) {
            BakedModel overridden = nested.resolve(originalModel, stack, level, entity, seed);
            if (overridden != originalModel) {
                return overridden;
            }

            FluidStack fluidStack = ItemFragileFluidContainer.getFluid(stack);
            if (fluidStack.isEmpty()) {
                return originalModel;
            }

            Fluid containedFluid = fluidStack.getFluid();
            ResourceLocation name = BuiltInRegistries.FLUID.getKey(containedFluid);
            if (name == null) {
                return originalModel;
            }

            return cache.computeIfAbsent(name, ignored -> {
                FragileFluidContainerModel unbaked = parent.withFluid(containedFluid);
                return unbaked.bake(
                    owner,
                    baker,
                    Material::sprite,
                    BlockModelRotation.X0_Y0,
                    originalModel.getOverrides()
                );
            });
        }
    }

    /** Item tint implementation matching the dynamically selected fluid. */
    public static final class Colors implements ItemColor {
        @Override
        public int getColor(@NotNull ItemStack stack, int tintIndex) {
            if (tintIndex != 1) {
                return 0xFFFFFFFF;
            }
            FluidStack fluidStack = ItemFragileFluidContainer.getFluid(stack);
            return fluidStack.isEmpty()
                ? 0xFFFFFFFF
                : IClientFluidTypeExtensions.of(fluidStack.getFluid()).getTintColor(fluidStack);
        }
    }
}
