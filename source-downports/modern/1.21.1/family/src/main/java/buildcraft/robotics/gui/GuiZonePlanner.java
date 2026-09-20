/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.gui;

import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.help.DummyHelpElement;
import buildcraft.lib.gui.help.ElementHelpInfo;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.core.BCCoreItems;
import buildcraft.robotics.container.ContainerZonePlanner;
import buildcraft.robotics.tile.TileZonePlanner;
import buildcraft.robotics.zone.ZoneChunk;
import buildcraft.robotics.zone.ZonePlan;
import buildcraft.robotics.zone.ZonePlannerMapChunk;
import buildcraft.robotics.zone.ZonePlannerMapChunk.MapColourData;
import buildcraft.robotics.zone.ZonePlannerMapChunkKey;
import buildcraft.robotics.zone.ZonePlannerMapDataClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public class GuiZonePlanner extends GuiBC8<ContainerZonePlanner> {
    public static final int WINDOWED_MAP_WIDTH = 213;
    public static final int WINDOWED_MAP_HEIGHT = 100;

    private static final ResourceLocation TEXTURE_BASE = ResourceLocation.parse("buildcraftrobotics:textures/gui/zone_planner.png");
    private static final ResourceLocation TEXTURE_MAP = ResourceLocation.fromNamespaceAndPath("buildcraftrobotics", "dynamic/zone_planner_map");
    private static final long MAP_TEXTURE_REFRESH_INTERVAL_MS = 100L;
    private static final long MAP_CACHE_REVALIDATE_INTERVAL_MS = 2L * 60L * 1_000L;
    private static final int MAP_BACKGROUND_COLOUR = 0xFF_80_80_80;
    private static final int SIZE_X = 256;
    private static final int SIZE_Y = 228;

    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);
    private static final GuiIcon ICON_PROGRESS = new GuiIcon(TEXTURE_BASE, 16, 228, 8, 28);
    private static final GuiRectangle RECT_PROGRESS = new GuiRectangle(236, 27, 8, 28);

    private static final int MAP_X = 8;
    private static final int MAP_Y = 9;
    private static final int COLOUR_X = 8;
    private static final int COLOUR_Y = 146;
    private static final int COLOUR_STEP = 18;
    private static final int COLOUR_SIZE = 16;
    private static final int NAME_X = 28;
    private static final int NAME_Y = 129;
    private static final int NAME_W = 156;
    private static final int NAME_H = 12;
    private static final int TOOL_X = 27;
    private static final int TOOL_Y = 111;
    private static final int TOOL_W = 15;
    private static final int TOOL_H = 15;
    private static final int FS_X = 44;
    private static final int FS_Y = 111;
    private static final int FS_W = 20;
    private static final int FS_H = 15;

    private float blocksPerPixel = 1.0F;
    private int centerX;
    private int centerZ;

    private boolean selecting = false;
    private int selX1;
    private int selY1;
    private int selX2;
    private int selY2;
    private BlockPos selectionStartXZ;
    private BlockPos selectionEndXZ;

    private boolean addMode = true;
    private boolean fullscreen = false;
    private int selectedLayer = 0;

    private EditBox nameField;
    private DynamicTexture mapTexture;
    private int mapTextureWidth;
    private int mapTextureHeight;
    private boolean mapTextureDirty = true;
    private boolean forceMapTextureRebuild = true;
    private long renderedMapRevision = Long.MIN_VALUE;
    private int renderedDimension = Integer.MIN_VALUE;
    private int renderedMapLevel = Integer.MIN_VALUE;
    private long lastMapTextureBuildTime;
    private ZonePlan renderedZonePlan;

    public GuiZonePlanner(ContainerZonePlanner container, Inventory inv, Component title) {
        super(container, inv, title);
        imageWidth = SIZE_X;
        imageHeight = SIZE_Y;
        if (container.tile != null) {
            BlockPos tilePos = container.tile.getBlockPos();
            centerX = tilePos.getX();
            centerZ = tilePos.getZ();
            selectedLayer = container.tile.getCurrentSelectedArea();
            container.currentLayer = selectedLayer;
            container.currentAreaSelection = new ZonePlan(container.tile.selectArea(selectedLayer));
            container.mapName = container.tile.mapName;
        }
        addHelpElements();
    }

    private void addHelpElements() {
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(MAP_X, MAP_Y, WINDOWED_MAP_WIDTH, WINDOWED_MAP_HEIGHT).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.map.title",
                        0xFF_70_A8_70,
                        "buildcraftrobotics.help.zone_planner.map.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(COLOUR_X, COLOUR_Y, COLOUR_STEP * 4 - 2, COLOUR_STEP * 4 - 2).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.layers.title",
                        0xFF_D0_70_70,
                        "buildcraftrobotics.help.zone_planner.layers.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(TOOL_X, TOOL_Y, TOOL_W, TOOL_H).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.mode.title",
                        0xFF_A0_A0_A0,
                        "buildcraftrobotics.help.zone_planner.mode.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(FS_X, FS_Y, FS_W, FS_H).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.fullscreen.title",
                        0xFF_88_AA_DD,
                        "buildcraftrobotics.help.zone_planner.fullscreen.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(NAME_X, NAME_Y, NAME_W, NAME_H).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.name.title",
                        0xFF_99_99_99,
                        "buildcraftrobotics.help.zone_planner.name.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(232, 8, 20, 68).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.crafting.title",
                        0xFF_CC_BB_88,
                        "buildcraftrobotics.help.zone_planner.crafting.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(7, 124, 20, 20).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.import.title",
                        0xFF_88_CC_CC,
                        "buildcraftrobotics.help.zone_planner.import.desc"
                )
        ));
    }

    @Override
    public void init() {
        super.init();
        nameField = new EditBox(font, leftPos + NAME_X, topPos + NAME_Y, NAME_W, NAME_H, Component.empty());
        nameField.setMaxLength(32);
        nameField.setValue(container.tile == null ? container.mapName : container.tile.mapName);
        nameField.setResponder(container::sendNameToServer);
        addRenderableWidget(nameField);
        clearNameFocus();
        invalidateMapTexture(true);
        container.loadArea(selectedLayer);
    }

    private int mapWidth() {
        return fullscreen ? width : WINDOWED_MAP_WIDTH;
    }

    private int mapHeight() {
        return fullscreen ? height : WINDOWED_MAP_HEIGHT;
    }

    private int mapXScreen() {
        return fullscreen ? 0 : leftPos + MAP_X;
    }

    private int mapYScreen() {
        return fullscreen ? 0 : topPos + MAP_Y;
    }

    private int mapXDraw() {
        return mapXScreen();
    }

    private int mapYDraw() {
        return mapYScreen();
    }

    private boolean isInsideMap(double mouseX, double mouseY) {
        int x = mapXScreen();
        int y = mapYScreen();
        return mouseX >= x && mouseY >= y && mouseX < x + mapWidth() && mouseY < y + mapHeight();
    }

    private boolean isInsideNameField(double mouseX, double mouseY) {
        int x = leftPos + NAME_X;
        int y = topPos + NAME_Y;
        return !fullscreen && nameField != null && nameField.visible
                && mouseX >= x && mouseY >= y && mouseX < x + NAME_W && mouseY < y + NAME_H;
    }

    private void clearNameFocus() {
        if (nameField != null && nameField.isFocused()) {
            // EditBox#setFocused(boolean) is protected in this target, but mouseClicked outside the box
            // defocuses it through the widget's own public event path.
            nameField.mouseClicked(-1, -1, 0);
        }
        setFocused(null);
    }

    private BlockPos screenToWorld(double mouseX, double mouseY) {
        int relX = Mth.floor(mouseX) - mapXScreen();
        int relY = Mth.floor(mouseY) - mapYScreen();
        int worldX = Mth.floor(centerX + (relX - mapWidth() / 2.0F) * blocksPerPixel);
        int worldZ = Mth.floor(centerZ + (relY - mapHeight() / 2.0F) * blocksPerPixel);
        int worldY = 0;
        if (minecraft != null && minecraft.level != null) {
            ZonePlannerMapChunk chunk = ZonePlannerMapDataClient.INSTANCE.getChunk(
                    minecraft.level,
                    new ZonePlannerMapChunkKey(new ChunkPos(worldX >> 4, worldZ >> 4), getDimensionId(), getMapLevel())
            );
            if (chunk != null) {
                MapColourData data = chunk.getData(worldX, worldZ);
                if (data != null) {
                    worldY = data.posY;
                }
            }
        }
        return new BlockPos(worldX, worldY, worldZ);
    }

    private int getMapLevel() {
        if (minecraft == null || minecraft.player == null) {
            return 0;
        }
        return Math.max(0, minecraft.player.blockPosition().getY() / ZonePlannerMapChunkKey.LEVEL_HEIGHT);
    }

    private int getDimensionId() {
        if (minecraft == null || minecraft.level == null) {
            return 0;
        }
        return minecraft.level.dimension().location().hashCode();
    }

    private ZonePlan getBaseSelectedArea() {
        if (container.tile != null && selectedLayer >= 0 && selectedLayer < container.tile.layers.length) {
            ZonePlan layer = container.tile.layers[selectedLayer];
            if (layer != null) {
                return layer;
            }
        }
        return container.currentAreaSelection;
    }

    private int getColourSlotAt(double mouseX, double mouseY) {
        if (fullscreen) {
            return -1;
        }
        int relX = Mth.floor(mouseX) - (leftPos + COLOUR_X);
        int relY = Mth.floor(mouseY) - (topPos + COLOUR_Y);
        if (relX < 0 || relY < 0) {
            return -1;
        }
        int column = relX / COLOUR_STEP;
        int row = relY / COLOUR_STEP;
        if (column < 0 || column >= 4 || row < 0 || row >= 4) {
            return -1;
        }
        if (relX - column * COLOUR_STEP >= COLOUR_SIZE || relY - row * COLOUR_STEP >= COLOUR_SIZE) {
            return -1;
        }
        return column * 4 + row;
    }

    private boolean insideRelativeButton(double mouseX, double mouseY, int x, int y, int w, int h) {
        if (fullscreen) {
            return false;
        }
        int ax = leftPos + x;
        int ay = topPos + y;
        return mouseX >= ax && mouseY >= ay && mouseX < ax + w && mouseY < ay + h;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (!isInsideNameField(mouseX, mouseY)) {
            clearNameFocus();
        }

        if (isInsideMap(mouseX, mouseY)) {
            if (mouseButton == 1) {
                BlockPos newCenter = screenToWorld(mouseX, mouseY);
                centerX = newCenter.getX();
                centerZ = newCenter.getZ();
                selecting = false;
                selectionStartXZ = null;
                selectionEndXZ = null;
                invalidateMapTexture(true);
                return true;
            }

            selecting = true;
            selectionStartXZ = screenToWorld(mouseX, mouseY);
            selectionEndXZ = selectionStartXZ;
            selX1 = Mth.floor(mouseX);
            selY1 = Mth.floor(mouseY);
            selX2 = selX1;
            selY2 = selY1;
            updateBufferedSelection(mouseX, mouseY);
            return true;
        }

        if (insideRelativeButton(mouseX, mouseY, TOOL_X, TOOL_Y, TOOL_W, TOOL_H)) {
            addMode = !addMode;
            return true;
        }
        if (insideRelativeButton(mouseX, mouseY, FS_X, FS_Y, FS_W, FS_H)) {
            toFullscreen();
            return true;
        }

        int colourSlot = getColourSlotAt(mouseX, mouseY);
        if (colourSlot >= 0) {
            selectLayer(colourSlot);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int mouseButton, double dragX, double dragY) {
        if (selecting && selectionStartXZ != null) {
            selX2 = Mth.clamp(Mth.floor(mouseX), mapXScreen(), mapXScreen() + mapWidth() - 1);
            selY2 = Mth.clamp(Mth.floor(mouseY), mapYScreen(), mapYScreen() + mapHeight() - 1);
            updateBufferedSelection(selX2, selY2);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, mouseButton, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (selecting && selectionStartXZ != null) {
            updateBufferedSelection(mouseX, mouseY);
            if (selectionEndXZ != null) {
                ZonePlan updatedArea = new ZonePlan(getBaseSelectedArea());
                applySelection(updatedArea, selectionStartXZ, selectionEndXZ, addMode);
                container.saveArea(selectedLayer, updatedArea);
                invalidateMapTexture(true);
            }
            selecting = false;
            selectionStartXZ = null;
            selectionEndXZ = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInsideMap(mouseX, mouseY)) {
            boolean changed = scrollY > 0 ? decBlocksPerPixel() : incBlocksPerPixel();
            return changed || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void updateBufferedSelection(double mouseX, double mouseY) {
        if (selectionStartXZ == null) {
            return;
        }
        BlockPos end = screenToWorld(
                Mth.clamp(Mth.floor(mouseX), mapXScreen(), mapXScreen() + mapWidth() - 1),
                Mth.clamp(Mth.floor(mouseY), mapYScreen(), mapYScreen() + mapHeight() - 1)
        );
        selectionEndXZ = end;
    }

    private void applySelection(ZonePlan area, BlockPos start, BlockPos end, boolean value) {
        int minX = Math.min(start.getX(), end.getX());
        int maxX = Math.max(start.getX(), end.getX());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxZ = Math.max(start.getZ(), end.getZ());
        int dimension = getDimensionId();
        int mapLevel = getMapLevel();

        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (value && !ZonePlannerMapDataClient.INSTANCE.isChunkAvailable(
                        new ZonePlannerMapChunkKey(new ChunkPos(chunkX, chunkZ), dimension, mapLevel))) {
                    continue;
                }

                int chunkMinX = Math.max(minX, chunkX << 4);
                int chunkMaxX = Math.min(maxX, (chunkX << 4) + 15);
                int chunkMinZ = Math.max(minZ, chunkZ << 4);
                int chunkMaxZ = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = chunkMinX; x <= chunkMaxX; x++) {
                    for (int z = chunkMinZ; z <= chunkMaxZ; z++) {
                        area.set(x, z, value);
                    }
                }
            }
        }
    }

    private void selectLayer(int index) {
        if (index < 0 || index >= TileZonePlanner.LAYER_COUNT) {
            return;
        }
        selectedLayer = index;
        selecting = false;
        selectionStartXZ = null;
        selectionEndXZ = null;
        if (container.tile != null) {
            container.currentAreaSelection = new ZonePlan(container.tile.selectArea(index));
        }
        container.loadArea(index);
        invalidateMapTexture(true);
    }

    private boolean incBlocksPerPixel() {
        if (blocksPerPixel > 0.125F) {
            if (blocksPerPixel <= 1.0F) {
                blocksPerPixel /= 2.0F;
            } else {
                blocksPerPixel -= 1.0F;
            }
            invalidateMapTexture(true);
            return true;
        }
        return false;
    }

    private boolean decBlocksPerPixel() {
        float max = fullscreen ? 4.0F : 8.0F;
        if (blocksPerPixel < max) {
            if (blocksPerPixel >= 1.0F) {
                blocksPerPixel += 1.0F;
            } else {
                blocksPerPixel *= 2.0F;
            }
            invalidateMapTexture(true);
            return true;
        }
        return false;
    }

    private void toFullscreen() {
        if (fullscreen) {
            return;
        }
        fullscreen = true;
        if (blocksPerPixel > 4.0F) {
            blocksPerPixel = 4.0F;
        }
        if (nameField != null) {
            nameField.visible = false;
            clearNameFocus();
        }
        invalidateMapTexture(true);
    }

    private void toWindowed() {
        if (!fullscreen) {
            return;
        }
        fullscreen = false;
        if (nameField != null) {
            nameField.visible = true;
        }
        invalidateMapTexture(true);
    }


    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        if (!fullscreen) {
            super.render(guiGraphics, mouseX, mouseY, partialTicks);
            return;
        }

        renderBackground(guiGraphics, mouseX, mouseY, partialTicks);
        drawBackgroundLayer(guiGraphics, mouseX, mouseY, partialTicks);
        drawForegroundLayer(guiGraphics, mouseX, mouseY);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        drawBackgroundLayer(getActiveGraphics(), mouseX, mouseY, partialTicks);
    }

    private void drawBackgroundLayer(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        if (!fullscreen) {
            ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);
            drawMap(guiGraphics, mouseX, mouseY);
            drawColourSlots(guiGraphics);
            drawButton(guiGraphics, leftPos + TOOL_X, topPos + TOOL_Y, TOOL_W, TOOL_H, addMode ? "+" : "-",
                    insideRelativeButton(mouseX, mouseY, TOOL_X, TOOL_Y, TOOL_W, TOOL_H));
            drawButton(guiGraphics, leftPos + FS_X, topPos + FS_Y, FS_W, FS_H, "FS",
                    insideRelativeButton(mouseX, mouseY, FS_X, FS_Y, FS_W, FS_H));
            if (container.tile != null) {
                double progress = Mth.clamp(container.tile.deltaProgress.getDynamic(partialTicks), 0.0D, 1.0D);
                drawProgress(guiGraphics, RECT_PROGRESS, ICON_PROGRESS, 1.0D, progress);
            }
        } else {
            guiGraphics.fill(0, 0, width, height, 0xFF_20_20_20);
        }
    }

    @Override
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
        drawForegroundLayer(getActiveGraphics(), mouseX, mouseY);
    }

    private void drawForegroundLayer(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (fullscreen) {
            drawMap(guiGraphics, mouseX, mouseY);
        }
    }

    private void drawButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String label, boolean hovered) {
        guiGraphics.fill(x, y, x + w, y + h, hovered ? 0xFF_B8_B8_B8 : 0xFF_A0_A0_A0);
        guiGraphics.fill(x, y, x + w, y + 1, 0xFF_F0_F0_F0);
        guiGraphics.fill(x, y, x + 1, y + h, 0xFF_F0_F0_F0);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, 0xFF_40_40_40);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, 0xFF_40_40_40);
        guiGraphics.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, 0x20_20_20, false);
    }

    private void drawColourSlots(GuiGraphics guiGraphics) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                int index = column * 4 + row;
                int x = leftPos + COLOUR_X + column * COLOUR_STEP;
                int y = topPos + COLOUR_Y + row * COLOUR_STEP;
                Item brush = BCCoreItems.PAINT_BRUSHS.get(DyeColor.byId(index));
                if (brush != null) {
                    ItemStack stack = new ItemStack(brush);
                    guiGraphics.renderItem(stack, x, y);
                    guiGraphics.renderItemDecorations(this.font, stack, x, y);
                } else {
                    int rgb = DyeColor.byId(index).getTextColor() & 0x00_FF_FF_FF;
                    guiGraphics.fill(x + 2, y + 2, x + COLOUR_SIZE - 2, y + COLOUR_SIZE - 2, 0xFF_00_00_00 | rgb);
                }
                if (index == selectedLayer) {
                    guiGraphics.blit(TEXTURE_BASE, x, y, 0, 228, 16, 16);
                }
            }
        }
    }

    private void drawMap(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Minecraft mc = minecraft;
        if (mc == null || mc.level == null) {
            return;
        }

        int x0 = mapXDraw();
        int y0 = mapYDraw();
        int mapW = mapWidth();
        int mapH = mapHeight();
        ZonePlan selected = getBaseSelectedArea();
        long revision = ZonePlannerMapDataClient.INSTANCE.getRevision();
        int dimension = getDimensionId();
        int mapLevel = getMapLevel();

        if (revision != renderedMapRevision) {
            mapTextureDirty = true;
        }
        if (dimension != renderedDimension || mapLevel != renderedMapLevel) {
            invalidateMapTexture(true);
        }
        if (selected != renderedZonePlan) {
            invalidateMapTexture(true);
        }

        long now = System.currentTimeMillis();
        if (mapTexture != null && now - lastMapTextureBuildTime >= MAP_CACHE_REVALIDATE_INTERVAL_MS) {
            invalidateMapTexture(true);
        }
        if (mapTextureDirty && (forceMapTextureRebuild || mapTexture == null
                || now - lastMapTextureBuildTime >= MAP_TEXTURE_REFRESH_INTERVAL_MS)) {
            rebuildMapTexture(mc.level, selected, mapW, mapH, revision, dimension, mapLevel, now);
        }

        if (mapTexture != null) {
            guiGraphics.blit(TEXTURE_MAP, x0, y0, 0.0F, 0.0F, mapW, mapH, mapW, mapH);
        } else {
            guiGraphics.fill(x0, y0, x0 + mapW, y0 + mapH, MAP_BACKGROUND_COLOUR);
        }

        if (selecting) {
            drawSelectionRect(guiGraphics);
        }
    }

    private void rebuildMapTexture(Level level, ZonePlan selected, int mapW, int mapH, long revision,
            int dimension, int mapLevel, long now) {
        ensureMapTexture(mapW, mapH);
        NativeImage pixels = mapTexture == null ? null : mapTexture.getPixels();
        if (pixels == null) {
            return;
        }

        int step = fullscreen ? Math.max(1, Mth.ceil(blocksPerPixel)) : 1;
        int cellsX = (mapW + step - 1) / step;
        int cellsZ = (mapH + step - 1) / step;
        int[] worldXs = new int[cellsX];
        int[] worldZs = new int[cellsZ];
        int minChunkX = Integer.MAX_VALUE;
        int maxChunkX = Integer.MIN_VALUE;
        int minChunkZ = Integer.MAX_VALUE;
        int maxChunkZ = Integer.MIN_VALUE;

        for (int cellX = 0; cellX < cellsX; cellX++) {
            int screenX = cellX * step;
            int worldX = Mth.floor(centerX + (screenX - mapW / 2.0F) * blocksPerPixel);
            worldXs[cellX] = worldX;
            int chunkX = worldX >> 4;
            minChunkX = Math.min(minChunkX, chunkX);
            maxChunkX = Math.max(maxChunkX, chunkX);
        }
        for (int cellZ = 0; cellZ < cellsZ; cellZ++) {
            int screenZ = cellZ * step;
            int worldZ = Mth.floor(centerZ + (screenZ - mapH / 2.0F) * blocksPerPixel);
            worldZs[cellZ] = worldZ;
            int chunkZ = worldZ >> 4;
            minChunkZ = Math.min(minChunkZ, chunkZ);
            maxChunkZ = Math.max(maxChunkZ, chunkZ);
        }

        int chunkWidth = maxChunkX - minChunkX + 1;
        int chunkHeight = maxChunkZ - minChunkZ + 1;
        ZonePlannerMapChunk[] mapChunks = new ZonePlannerMapChunk[chunkWidth * chunkHeight];
        ZoneChunk[] zoneChunks = new ZoneChunk[chunkWidth * chunkHeight];
        Map<ChunkPos, ZoneChunk> selectedChunks = selected == null ? null : selected.getChunkMapping();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                int index = (chunkX - minChunkX) * chunkHeight + chunkZ - minChunkZ;
                ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
                mapChunks[index] = ZonePlannerMapDataClient.INSTANCE.getChunk(
                        level,
                        new ZonePlannerMapChunkKey(chunkPos, dimension, mapLevel)
                );
                if (selectedChunks != null) {
                    zoneChunks[index] = selectedChunks.get(chunkPos);
                }
            }
        }

        int layerRgb = DyeColor.byId(selectedLayer).getTextColor() & 0x00_FF_FF_FF;
        int overlayColour = 0xFF_00_00_00 | layerRgb;
        for (int cellX = 0; cellX < cellsX; cellX++) {
            int screenX = cellX * step;
            int worldX = worldXs[cellX];
            int chunkX = worldX >> 4;
            for (int cellZ = 0; cellZ < cellsZ; cellZ++) {
                int screenZ = cellZ * step;
                int worldZ = worldZs[cellZ];
                int chunkZ = worldZ >> 4;
                int chunkIndex = (chunkX - minChunkX) * chunkHeight + chunkZ - minChunkZ;
                int colour = MAP_BACKGROUND_COLOUR;
                ZonePlannerMapChunk mapChunk = mapChunks[chunkIndex];
                if (mapChunk != null) {
                    MapColourData data = mapChunk.getData(worldX, worldZ);
                    if (data != null) {
                        colour = data.colour;
                    }
                }

                ZoneChunk zoneChunk = zoneChunks[chunkIndex];
                if (zoneChunk != null && zoneChunk.get(worldX & 15, worldZ & 15)) {
                    colour = blendArgb(colour, overlayColour, 0x88);
                }

                fillNativeImage(
                        pixels,
                        screenX,
                        screenZ,
                        Math.min(step, mapW - screenX),
                        Math.min(step, mapH - screenZ),
                        argbToAbgr(colour)
                );
            }
        }

        mapTexture.upload();
        renderedMapRevision = revision;
        renderedDimension = dimension;
        renderedMapLevel = mapLevel;
        renderedZonePlan = selected;
        lastMapTextureBuildTime = now;
        mapTextureDirty = false;
        forceMapTextureRebuild = false;
    }

    private void ensureMapTexture(int width, int height) {
        if (mapTexture != null && mapTextureWidth == width && mapTextureHeight == height) {
            return;
        }
        closeMapTexture();
        mapTexture = new DynamicTexture(width, height, true);
        mapTextureWidth = width;
        mapTextureHeight = height;
        if (minecraft != null) {
            minecraft.getTextureManager().register(TEXTURE_MAP, mapTexture);
        }
    }

    private void closeMapTexture() {
        if (mapTexture != null) {
            if (minecraft != null) {
                minecraft.getTextureManager().release(TEXTURE_MAP);
            } else {
                mapTexture.close();
            }
            mapTexture = null;
        }
        mapTextureWidth = 0;
        mapTextureHeight = 0;
    }

    private void invalidateMapTexture(boolean force) {
        mapTextureDirty = true;
        forceMapTextureRebuild |= force;
    }

    private static void fillNativeImage(NativeImage image, int x, int y, int width, int height, int colour) {
        int maxX = x + width;
        int maxY = y + height;
        for (int py = y; py < maxY; py++) {
            for (int px = x; px < maxX; px++) {
                image.setPixelRGBA(px, py, colour);
            }
        }
    }

    private static int blendArgb(int base, int overlay, int alpha) {
        int inverse = 255 - alpha;
        int red = (((overlay >> 16) & 0xFF) * alpha + ((base >> 16) & 0xFF) * inverse) / 255;
        int green = (((overlay >> 8) & 0xFF) * alpha + ((base >> 8) & 0xFF) * inverse) / 255;
        int blue = ((overlay & 0xFF) * alpha + (base & 0xFF) * inverse) / 255;
        return 0xFF_00_00_00 | red << 16 | green << 8 | blue;
    }

    private static int argbToAbgr(int argb) {
        return argb & 0xFF_00_FF_00 | (argb & 0x00_FF_00_00) >> 16 | (argb & 0x00_00_00_FF) << 16;
    }

    private void drawSelectionRect(GuiGraphics guiGraphics) {
        int x1 = Math.min(selX1, selX2);
        int x2 = Math.max(selX1, selX2);
        int y1 = Math.min(selY1, selY2);
        int y2 = Math.max(selY1, selY2);
        int rgb = DyeColor.byId(selectedLayer).getTextColor() & 0x00_FF_FF_FF;
        int argb = (addMode ? 0x66_00_00_00 : 0x55_00_00_00) | rgb;
        guiGraphics.fill(x1, y1, x2 + 1, y2 + 1, argb);
        guiGraphics.fill(x1, y1, x2 + 1, y1 + 1, 0xCC_FF_FF_FF);
        guiGraphics.fill(x1, y2, x2 + 1, y2 + 1, 0xCC_FF_FF_FF);
        guiGraphics.fill(x1, y1, x1 + 1, y2 + 1, 0xCC_FF_FF_FF);
        guiGraphics.fill(x2, y1, x2 + 1, y2 + 1, 0xCC_FF_FF_FF);
    }

    @Override
    public void containerTick() {
        super.containerTick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!fullscreen && nameField != null && nameField.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                clearNameFocus();
                return true;
            }
            if (nameField.keyPressed(keyCode, scanCode, modifiers) || nameField.canConsumeInput()) {
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_F5) {
            ZonePlannerMapDataClient.INSTANCE.clearCache();
            invalidateMapTexture(true);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD) {
            return incBlocksPerPixel() || super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
            return decBlocksPerPixel() || super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_M) {
            if (Screen.hasShiftDown()) {
                toFullscreen();
            } else {
                toWindowed();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && fullscreen) {
            toWindowed();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!fullscreen && nameField != null && nameField.isFocused()) {
            return nameField.charTyped(codePoint, modifiers);
        }
        if (codePoint == '+') {
            return incBlocksPerPixel();
        }
        if (codePoint == '-') {
            return decBlocksPerPixel();
        }
        if (codePoint == 'm') {
            toWindowed();
            return true;
        }
        if (codePoint == 'M') {
            toFullscreen();
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void removed() {
        fullscreen = false;
        closeMapTexture();
        super.removed();
    }
}
