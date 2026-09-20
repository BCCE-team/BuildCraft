/**
 * Copyright (c) 2011-2017, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * The BuildCraft API is distributed under the terms of the MIT License.
 */
package buildcraft.robotics.internal.legacy.boards;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public abstract class RedstoneBoardNBT<T> {
    private static final Random RAND = new Random();

    public abstract String getID();

    public abstract void addInformation(ItemStack stack, @Nullable Player player, List<Component> list, boolean advanced);

    public abstract IRedstoneBoard<T> create(CompoundTag nbt, T object);

    /** Modern replacement for the 1.7.10 icon registration hook. */
    public void registerSprites(Consumer<ResourceLocation> spriteRegistrar) {
    }

    /** Modern replacement for the 1.7.10 IIcon getter. */
    @Nullable
    public TextureAtlasSprite getIcon(CompoundTag nbt) {
        return null;
    }

    /** Compatibility alias for callers using the legacy method name. */
    @Deprecated
    public void registerIcons(Object iconRegister) {
    }

    public void createBoard(CompoundTag nbt) {
        nbt.putString("id", getID());
    }

    public int getParameterNumber(CompoundTag nbt) {
        if (!nbt.contains("parameters")) {
            return 0;
        }
        return nbt.getList("parameters", Tag.TAG_COMPOUND).size();
    }

    public float nextFloat(int difficulty) {
        return 1.0F - (float) Math.pow(RAND.nextFloat(), 1.0F / difficulty);
    }
}
