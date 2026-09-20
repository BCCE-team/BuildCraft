package buildcraft.lib.platform.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
//? if >=1.21.11 {
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
//? }
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.network.chat.Component;

/** Client-only registrations. Describes WHAT is registered; adapters own events and lifecycle phases. */
public final class ClientRegistration {
    private ClientRegistration() {}
    @FunctionalInterface
    public interface ScreenFactory<M extends AbstractContainerMenu, S extends Screen & MenuAccess<M>> {
        S create(M menu, Inventory inventory, Component title);
    }
    public interface Screens {
        <M extends AbstractContainerMenu, S extends Screen & MenuAccess<M>> void register(MenuType<? extends M> type, ScreenFactory<M, S> factory);
    }
    public enum BlockLayer { CUTOUT, TRANSLUCENT }
    @FunctionalInterface
    public interface Layers { void set(Block block, BlockLayer layer); }
    public interface Renderers {
        //? if >=1.21.11 {
        <T extends BlockEntity, S extends BlockEntityRenderState> void registerBlockEntityRenderer(BlockEntityType<? extends T> type, BlockEntityRendererProvider<T, S> factory);
        //? } else {
        <T extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<? extends T> type, BlockEntityRendererProvider<T> factory);
        //? }
        <T extends Entity> void registerEntityRenderer(EntityType<? extends T> type, EntityRendererProvider<T> factory);
    }
    @FunctionalInterface
    public interface BlockColours { void register(BlockColor colour, Block... blocks); }
    @FunctionalInterface
    public interface Setup { void enqueueWork(Runnable work); }
}
