/* Copyright (c) 2026 BuildCraft Community Edition contributors. MPL-2.0. */
package buildcraft.builders.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import buildcraft.builders.BCBuilders;
import buildcraft.builders.BCBuildersItems;
import buildcraft.builders.item.ItemConstructionMarker;
import buildcraft.builders.item.ItemSchematicSingle;
import buildcraft.builders.item.ItemSnapshot.EnumItemSnapshotType;
import buildcraft.lib.platform.client.ClientModelProperties;
import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

/**
 * Client-only item model properties. Never reference this type from an item
 * registry or a server/common Fabric entrypoint: class verification can try to
 * resolve ClientLevel even when a renderer registration method is not called.
 */
@Environment(EnvType.CLIENT)
public final class FabricItemModelProperties {
    private FabricItemModelProperties() {}

    public static void register(ClientModelProperties.Ranges event) {
        event.register(Identifier.fromNamespaceAndPath(BCBuilders.MODID, "used"), SnapshotUsedModelProperty.MAP_CODEC);
        event.register(Identifier.fromNamespaceAndPath(BCBuilders.MODID, "recording"), ConstructionRecordingModelProperty.MAP_CODEC);
    }

    public record SnapshotUsedModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<SnapshotUsedModelProperty> MAP_CODEC = MapCodec.unit(new SnapshotUsedModelProperty());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            if (stack.getItem() == BCBuildersItems.SCHEMATIC_SINGLE.get()) {
                return ItemSchematicSingle.isUsed(stack) ? 1.0F : 0.0F;
            }
            return EnumItemSnapshotType.getFromStack(stack).used ? 1.0F : 0.0F;
        }

        @Override
        public MapCodec<SnapshotUsedModelProperty> type() {
            return MAP_CODEC;
        }
    }

    public record ConstructionRecordingModelProperty() implements RangeSelectItemModelProperty {
        public static final MapCodec<ConstructionRecordingModelProperty> MAP_CODEC =
            MapCodec.unit(new ConstructionRecordingModelProperty());

        @Override
        public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
            return ItemConstructionMarker.isRecording(stack) ? 1.0F : 0.0F;
        }

        @Override
        public MapCodec<ConstructionRecordingModelProperty> type() {
            return MAP_CODEC;
        }
    }
}
