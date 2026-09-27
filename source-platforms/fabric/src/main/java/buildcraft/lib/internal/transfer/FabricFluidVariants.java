package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.fluid.FluidComponentPayload;
import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidVariant;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Lossless 1.20.1 Fabric Transfer API fluid-variant bridge. */
public final class FabricFluidVariants {
    /** Canonical SNBT payload used to preserve Fabric 1.20.x TransferVariant NBT through API v2 and reloads. */
    public static final ResourceLocation NBT_FORMAT = new ResourceLocation("buildcraft", "fabric_transfer_snbt_v1");
    public static final FluidMatchContext MATCH_CONTEXT = FabricFluidVariants::isInTag;

    private FabricFluidVariants() {
    }

    public static FluidVariant toApi(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant nativeVariant) {
        if (nativeVariant == null || nativeVariant.isBlank() || nativeVariant.getFluid() == Fluids.EMPTY) {
            throw new IllegalArgumentException("Cannot convert a blank Fabric fluid variant");
        }
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(nativeVariant.getFluid());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered Fabric fluid: " + nativeVariant.getFluid());
        }
        CompoundTag nbt = nativeVariant.copyNbt();
        if (nbt == null) return FluidVariant.of(id);
        return FluidVariant.of(id, FluidComponentPayload.of(NBT_FORMAT, encode(nbt)));
    }

    /**
     * Converts an API variant without dropping opaque component data.
     * Unknown component formats are rejected instead of silently producing a different fluid variant.
     */
    public static Optional<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> fromApi(FluidVariant variant) {
        if (variant == null) return Optional.empty();
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(variant.fluidId()).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return Optional.empty();

        FluidComponentPayload components = variant.components();
        if (components.isEmpty()) {
            return Optional.of(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid));
        }
        if (!NBT_FORMAT.equals(components.formatId().orElse(null))) {
            return Optional.empty();
        }
        try {
            CompoundTag tag = decode(components.copyCanonicalBytes());
            return Optional.of(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid, tag));
        } catch (IllegalArgumentException malformedPayload) {
            return Optional.empty();
        }
    }

    private static byte[] encode(CompoundTag tag) {
        // StringTagVisitor sorts compound keys, giving FluidComponentPayload stable bytes for equivalent NBT.
        return new StringTagVisitor().visit(tag).getBytes(StandardCharsets.UTF_8);
    }

    private static CompoundTag decode(byte[] bytes) {
        try {
            return TagParser.parseTag(new String(bytes, StandardCharsets.UTF_8));
        } catch (CommandSyntaxException exception) {
            throw new IllegalArgumentException("Invalid Fabric fluid variant payload", exception);
        }
    }

    private static boolean isInTag(ResourceLocation fluidId, ResourceLocation tagId) {
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(fluidId).orElse(Fluids.EMPTY);
        return fluid != Fluids.EMPTY && fluid.is(TagKey.create(Registries.FLUID, tagId));
    }
}
