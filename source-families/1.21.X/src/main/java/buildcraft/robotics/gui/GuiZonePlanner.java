//? source if >=1.21.1
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.robotics.gui;

import java.util.Map;
import java.util.HashMap;

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
import buildcraft.robotics.container.ContainerRobotProcessor;
import buildcraft.robotics.tile.TileRobotProcessor;
import buildcraft.robotics.tile.TileZonePlanner;
import buildcraft.robotics.zone.ZoneChunk;
import buildcraft.robotics.zone.ZonePlan;
import buildcraft.robotics.zone.ZonePlannerEditor;
import buildcraft.robotics.zone.ZonePlannerMapChunk;
import buildcraft.robotics.zone.ZonePlannerMapChunk.MapColourData;
import buildcraft.robotics.zone.ZonePlannerMapChunkKey;
import buildcraft.robotics.zone.ZonePlannerMapDataClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
import buildcraft.lib.client.gui.ClientGuiModePreferences;
import buildcraft.lib.client.gui.ClientGuiModeNavigation;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import buildcraft.lib.compat.RenderCompat;

public class GuiZonePlanner<M extends ContainerZonePlanner> extends GuiBC8<M> {
    public static final int WINDOWED_MAP_WIDTH = 213;
    public static final int WINDOWED_MAP_HEIGHT = 100;

    private static final Identifier TEXTURE_BASE = Identifier.parse("buildcraftrobotics:textures/gui/zone_planner.png");
    private static final Identifier TEXTURE_PROCESSOR = Identifier.parse("buildcraftrobotics:textures/gui/robot_processor.png");
    private static final Identifier TEXTURE_MAP = Identifier.fromNamespaceAndPath("buildcraftrobotics", "dynamic/zone_planner_map");
    private static final Identifier TEXTURE_ERASER = Identifier.parse("buildcraftrobotics:textures/gui/eraser.png");
    private static final Identifier TEXTURE_PEN = Identifier.parse("buildcraftrobotics:textures/gui/pen.png");
    private static final Identifier TEXTURE_RECTANGLE = Identifier.parse("buildcraftbuilders:textures/filler/patterns/2d_square.png");
    private static final Identifier TEXTURE_OVAL = Identifier.parse("buildcraftbuilders:textures/filler/patterns/2d_circle.png");
    private static final Identifier TEXTURE_HISTORY = Identifier.parse("buildcraftrobotics:textures/gui/zone_planner_buttons.png");
    private static final long MAP_TEXTURE_REFRESH_INTERVAL_MS = 100L;
    private static final long MAP_CACHE_REVALIDATE_INTERVAL_MS = 2L * 60L * 1_000L;
    private static final int MAP_BACKGROUND_COLOUR = 0xFF_80_80_80;
    private static final int SIZE_X = 256;
    private static final int SIZE_Y = 228;

    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);
    private static final int PROCESSOR_WIDTH = 331;
    private static final GuiIcon ICON_PROCESSOR = new GuiIcon(TEXTURE_PROCESSOR, 0, 0, PROCESSOR_WIDTH, 272, 512);
    private static final GuiIcon ICON_PROGRESS = new GuiIcon(TEXTURE_BASE, 16, 228, 8, 28);
    private static final GuiRectangle RECT_PROGRESS = new GuiRectangle(236, 26, 8, 28);

    private static final int MAP_X = 8;
    private static final int MAP_Y = 8;
    private static final int COLOUR_X = 8;
    private static final int COLOUR_Y = 146;
    private static final int COLOUR_STEP = 18;
    private static final int COLOUR_SIZE = 16;
    // The map-loading slot moved above the player's inventory; keep the name at
    // a compact offset beside the map-loading slot.
    private static final int NAME_X = 108;
    private static final int NAME_Y = 129;
    private static final int NAME_W = 136;
    private static final int NAME_H = 12;

    // Four editor slots above the 16 paintbrush colours in the updated texture.
    private static final int ERASER_X = 8;
    private static final int SHAPE_Y = 128;
    private static final int EDITOR_SLOT_SIZE = 16;
    private static final int[] SHAPE_X = {26, 44, 62};
    private static final ZonePlannerEditor.Tool[] SHAPE_TOOLS = {
            ZonePlannerEditor.Tool.RECTANGLE, ZonePlannerEditor.Tool.ELLIPSE, ZonePlannerEditor.Tool.BRUSH
    };
    private static final String[] SHAPE_HELP = {"rectangle", "oval", "pen"};

    // Undo and redo now use the supplied 16x16 sprites (normal / hover).
    private static final int UNDO_X = 8;
    private static final int REDO_X = 26;
    // The third atlas icon sits above the pen tool, aligned with Undo and Redo.
    private static final int CLEAR_X = 62;
    private static final int HISTORY_Y = 110;
    private static final int HISTORY_SIZE = 16;

    protected final boolean processorMode;
    private static final int[] PROCESSOR_SHAPE_X = {264, 282, 300};
    private int mapX() { return processorMode ? 104 : MAP_X; }
    private int mapY() { return processorMode ? 22 : MAP_Y; }
    private int colourX() { return processorMode ? 246 : COLOUR_X; }
    private int colourY() { return processorMode ? 195 : COLOUR_Y; }
    private int colourStep() { return processorMode ? 18 : COLOUR_STEP; }
    private int eraserX() { return processorMode ? 246 : ERASER_X; }
    private int shapeY() { return processorMode ? 177 : SHAPE_Y; }
    private int[] shapePositions() { return processorMode ? PROCESSOR_SHAPE_X : SHAPE_X; }
    private int undoX() { return processorMode ? 246 : UNDO_X; }
    private int redoX() { return processorMode ? 264 : REDO_X; }
    private int clearX() { return processorMode ? 300 : CLEAR_X; }
    private int historyY() { return processorMode ? 158 : HISTORY_Y; }

    private ZonePlannerEditor.History editHistory = new ZonePlannerEditor.History();
    private final Map<ChunkPos, Boolean> paintAvailableChunks = new HashMap<>();
    private ZonePlan brushPreview;
    private ZonePlannerEditor.Tool selectedTool = ZonePlannerEditor.Tool.RECTANGLE;

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

    private final Inventory guiPlayerInventory;
    private final Component guiScreenTitle;
    private Button legacyModeButton;
    private Button modernModeButton;

    public GuiZonePlanner(M container, Inventory inv, Component title) {
        this(container, inv, title, false);
    }

    protected GuiZonePlanner(M container, Inventory inv, Component title, boolean processorMode) {
        super(container, inv, title);
        this.processorMode = processorMode;
        if (!processorMode) container.applyClientGuiLayout(false);
        guiPlayerInventory = inv;
        guiScreenTitle = title;
        imageWidth = processorMode ? PROCESSOR_WIDTH : SIZE_X;
        imageHeight = processorMode ? 272 : SIZE_Y;
        if (container.tile != null) {
            BlockPos tilePos = container.tile.getBlockPos();
            centerX = tilePos.getX();
            centerZ = tilePos.getZ();
            selectedLayer = container.tile.getCurrentSelectedArea();
            container.currentLayer = selectedLayer;
            container.currentAreaSelection = new ZonePlan(container.tile.selectArea(selectedLayer));
            container.mapName = container.tile.mapName;
        }
        if (processorMode && container.tile == null && container instanceof ContainerRobotProcessor processor) {
            BlockPos remotePos = processor.getPanelPos();
            if (remotePos != null) {
                centerX = remotePos.getX();
                centerZ = remotePos.getZ();
            }
        }
        addHelpElements();
    }

    private void addHelpElements() {
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(mapX(), mapY(), mapWidth(), mapHeight()).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.map.title",
                        0xFF_70_A8_70,
                        "buildcraftrobotics.help.zone_planner.map.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(colourX(), colourY(), colourStep() * 4 - 2, colourStep() * 4 - 2).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.layers.title",
                        0xFF_D0_70_70,
                        "buildcraftrobotics.help.zone_planner.layers.desc"
                )
        ));
        // The eraser is independent of the chosen coloured brush and drawing shape.
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(eraserX(), shapeY(), EDITOR_SLOT_SIZE, EDITOR_SLOT_SIZE).offset(mainGui.rootElement),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.eraser.title",
                        0xFF_D0_70_70,
                        "buildcraftrobotics.help.zone_planner.eraser.desc"
                )
        ));
        for (int i = 0; i < shapePositions().length; i++) {
            mainGui.shownElements.add(new DummyHelpElement(
                    new GuiRectangle(shapePositions()[i], shapeY(), EDITOR_SLOT_SIZE, EDITOR_SLOT_SIZE).offset(mainGui.rootElement),
                    new ElementHelpInfo(
                            "buildcraftrobotics.help.zone_planner." + SHAPE_HELP[i] + ".title",
                            0xFF_88_BB_CC,
                            "buildcraftrobotics.help.zone_planner." + SHAPE_HELP[i] + ".desc"
                    )
            ));
        }
        for (int i = 0; i < 2; i++) {
            mainGui.shownElements.add(new DummyHelpElement(
                    new GuiRectangle(i == 0 ? undoX() : redoX(), historyY(), HISTORY_SIZE, HISTORY_SIZE)
                            .offset(mainGui.rootElement),
                    new ElementHelpInfo(
                            "buildcraftrobotics.help.zone_planner." + (i == 0 ? "undo" : "redo") + ".title",
                            0xFF_88_BB_CC,
                            "buildcraftrobotics.help.zone_planner." + (i == 0 ? "undo" : "redo") + ".desc"
                    )
            ));
        }
        mainGui.shownElements.add(new DummyHelpElement(
                (processorMode ? new GuiRectangle(clearX(), historyY(), HISTORY_SIZE, HISTORY_SIZE)
                        : new GuiRectangle(CLEAR_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE)).offset(mainGui.rootElement),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.clear.title",
                        0xFF_88_BB_CC,
                        "buildcraftrobotics.help.zone_planner.clear.desc"
                )
        ));
        // The processor has no Zone Planner name or crafting slots in this position.
        if (!processorMode) {
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(NAME_X, NAME_Y, NAME_W, NAME_H).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.name.title",
                        0xFF_99_99_99,
                        "buildcraftrobotics.help.zone_planner.name.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(232, 7, 20, 68).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.crafting.title",
                        0xFF_CC_BB_88,
                        "buildcraftrobotics.help.zone_planner.crafting.desc"
                )
        ));
        mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(87, 127, 20, 20).offset(mainGui.rootElement).expand(2),
                new ElementHelpInfo(
                        "buildcraftrobotics.help.zone_planner.import.title",
                        0xFF_88_CC_CC,
                        "buildcraftrobotics.help.zone_planner.import.desc"
                )
        ));
        }
    }

    public void init() {
        super.init();
        if (!processorMode) {
        nameField = new EditBox(font, leftPos + NAME_X, topPos + NAME_Y, NAME_W, NAME_H, Component.empty());
        nameField.setMaxLength(32);
        nameField.setValue(container.tile == null ? container.mapName : container.tile.mapName);
        nameField.setResponder(container::sendNameToServer);
        addRenderableWidget(nameField);
        clearNameFocus();
        }
        invalidateMapTexture(true);
        container.loadArea(selectedLayer);
        if (!processorMode) initModeButtons();
    }

    private int mapWidth() {
        if (processorMode) return 106;
        return WINDOWED_MAP_WIDTH;
    }

    private int mapHeight() {
        if (processorMode) return 106;
        return WINDOWED_MAP_HEIGHT;
    }

    private int mapXScreen() {
        return leftPos + mapX();
    }

    private int mapYScreen() {
        return topPos + mapY();
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
        if (processorMode) return false;
        int x = leftPos + NAME_X;
        int y = topPos + NAME_Y;
        return nameField != null && nameField.visible
                && mouseX >= x && mouseY >= y && mouseX < x + NAME_W && mouseY < y + NAME_H;
    }

    private void clearNameFocus() {
        if (nameField != null && nameField.isFocused()) {
            // EditBox#setFocused(boolean) is protected in this target, but mouseClicked outside the box
            // defocuses it through the widget's own public event path.
            RenderCompat.mouseClicked(nameField, -1, -1, 0);
        }
        setFocused(null);
    }

    private BlockPos screenToWorld(double mouseX, double mouseY) {
        int relX = Mth.floor(mouseX) - mapXScreen();
        int relY = Mth.floor(mouseY) - mapYScreen();
        int worldX = Mth.floor(centerX + (relX - mapWidth() / 2.0F) * blocksPerPixel);
        int worldZ = Mth.floor(centerZ + (relY - mapHeight() / 2.0F) * blocksPerPixel);
        int worldY = 0;
        if (minecraft != null && minecraft.level != null && inProcessorWorkArea(worldX >> 4, worldZ >> 4)) {
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
        return minecraft.level.dimension().identifier().hashCode();
    }

    protected boolean processorControlsEnabled() {
        return !processorMode || container instanceof ContainerRobotProcessor processor && processor.processorOnline();
    }
    private int observedMediaRevision = -1;
    private boolean observedOnline;
    private void syncProcessorMedia() {
        if (!(container instanceof ContainerRobotProcessor processor)) return;
        boolean online = processor.processorOnline();
        if (observedMediaRevision == processor.mediaRevision() && observedOnline == online) return;
        observedMediaRevision = processor.mediaRevision(); observedOnline = online;
        cancelSelection();
        editHistory = new ZonePlannerEditor.History();
        selectedLayer = processor.diskLayer();
        invalidateMapTexture(true);
        // The server sends the selected layer immediately after the media state.
    }

    private ZonePlan getBaseSelectedArea() {
        if (brushPreview != null) return brushPreview;
        if (processorMode) return container.currentAreaSelection;
        if (container.tile != null && selectedLayer >= 0 && selectedLayer < container.tile.layers.length) {
            ZonePlan layer = container.tile.layers[selectedLayer];
            if (layer != null) {
                return layer;
            }
        }
        return container.currentAreaSelection;
    }

    private int getColourSlotAt(double mouseX, double mouseY) {
        int relX = Mth.floor(mouseX) - (leftPos + colourX());
        int relY = Mth.floor(mouseY) - (topPos + colourY());
        if (relX < 0 || relY < 0) {
            return -1;
        }
        int column = relX / colourStep();
        int row = relY / colourStep();
        if (column < 0 || column >= 4 || row < 0 || row >= 4) {
            return -1;
        }
        if (relX - column * colourStep() >= COLOUR_SIZE || relY - row * colourStep() >= COLOUR_SIZE) {
            return -1;
        }
        return column * 4 + row;
    }

    private boolean insideRelativeButton(double mouseX, double mouseY, int x, int y, int w, int h) {
        int ax = leftPos + x;
        int ay = topPos + y;
        return mouseX >= ax && mouseY >= ay && mouseX < ax + w && mouseY < ay + h;
    }

    private void cancelSelection() {
        selecting = false;
        selectionStartXZ = null;
        selectionEndXZ = null;
        brushPreview = null;
        paintAvailableChunks.clear();
        invalidateMapTexture(true);
    }

    /** A remote controller can open the GUI before the client has loaded the panel chunk. */
    private BlockPos processorOrigin() {
        if (!processorMode) return null;
        if (container.tile != null) return container.tile.getBlockPos();
        return container instanceof ContainerRobotProcessor processor ? processor.getPanelPos() : null;
    }

    /** Fixed working square. Ordinary Zone Planners retain their original unrestricted editor. */
    private ZonePlannerEditor.Bounds processorWorkBounds() {
        BlockPos origin = processorOrigin();
        if (origin == null) return null;
        var layout = ((ContainerRobotProcessor) container).diskLayout();
        int x = layout.minX(origin), z = layout.minZ(origin);
        return new ZonePlannerEditor.Bounds(x, z, x + 111, z + 111);
    }

    private boolean inProcessorWorkArea(int chunkX, int chunkZ) {
        if (!processorMode) return true;
        BlockPos origin = processorOrigin();
        return origin != null && ((ContainerRobotProcessor) container).diskLayout().intersectsChunk(origin, chunkX, chunkZ);
    }

    private ZonePlan restrictProcessorSelection(ZonePlan area) {
        if (!processorMode) return area;
        BlockPos origin = processorOrigin();
        return origin == null ? new ZonePlan() : ((ContainerRobotProcessor) container).diskLayout().clip(area, origin);
    }

    private boolean availableForPaint(ChunkPos chunk) {
        if (!inProcessorWorkArea(chunk.x, chunk.z)) return false;
        if (!addMode) return true;
        return paintAvailableChunks.computeIfAbsent(chunk,
                key -> ZonePlannerMapDataClient.INSTANCE.isChunkAvailable(
                        new ZonePlannerMapChunkKey(key, getDimensionId(), getMapLevel())));
    }

    private ZonePlannerEditor.Bounds visibleMapBounds() {
        BlockPos upperLeft = screenToWorld(mapXScreen(), mapYScreen());
        BlockPos lowerRight = screenToWorld(mapXScreen() + mapWidth() - 1,
                mapYScreen() + mapHeight() - 1);
        int minX = Math.min(upperLeft.getX(), lowerRight.getX());
        int minZ = Math.min(upperLeft.getZ(), lowerRight.getZ());
        int maxX = Math.max(upperLeft.getX(), lowerRight.getX());
        int maxZ = Math.max(upperLeft.getZ(), lowerRight.getZ());
        ZonePlannerEditor.Bounds work = processorWorkBounds();
        if (work != null) {
            minX = Math.max(minX, work.minX());
            minZ = Math.max(minZ, work.minZ());
            maxX = Math.min(maxX, work.maxX());
            maxZ = Math.min(maxZ, work.maxZ());
            if (minX > maxX || minZ > maxZ) return null;
        }
        return new ZonePlannerEditor.Bounds(minX, minZ, maxX, maxZ);
    }

    private void commitEdit(ZonePlan modified) {
        modified = restrictProcessorSelection(modified);
        // The client already updates its tile immediately in saveArea(), and the complete
        // edited layer is transmitted to the server with the existing split-packet codec.
        if (editHistory.record(selectedLayer, getBaseSelectedArea(), modified)) {
            container.saveArea(selectedLayer, modified);
            invalidateMapTexture(true);
        }
        paintAvailableChunks.clear();
    }

    private void undoEdit() {
        ZonePlan previous = editHistory.undo(selectedLayer, getBaseSelectedArea());
        if (previous != null) {
            container.saveArea(selectedLayer, restrictProcessorSelection(previous));
            invalidateMapTexture(true);
        }
    }

    private void redoEdit() {
        ZonePlan next = editHistory.redo(selectedLayer, getBaseSelectedArea());
        if (next != null) {
            container.saveArea(selectedLayer, restrictProcessorSelection(next));
            invalidateMapTexture(true);
        }
    }

    private void selectShape(int index) {
        if (index < 0 || index >= SHAPE_TOOLS.length) return;
        if (selecting) cancelSelection();
        selectedTool = SHAPE_TOOLS[index];
        // Do not modify addMode: selecting a shape must preserve the eraser state.
    }

    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (!processorControlsEnabled() || container instanceof ContainerRobotProcessor processor && !processor.areaReady()) return super.mouseClicked(mouseX, mouseY, mouseButton);
        if (!isInsideNameField(mouseX, mouseY)) {
            clearNameFocus();
        }

        if (isInsideMap(mouseX, mouseY)) {
            // No editing or panning to chunks outside the machine's fixed work square.
            BlockPos clicked = screenToWorld(mouseX, mouseY);
            if (!inProcessorWorkArea(clicked.getX() >> 4, clicked.getZ() >> 4)) return true;
            if (mouseButton == 1) {
                BlockPos newCenter = screenToWorld(mouseX, mouseY);
                centerX = newCenter.getX();
                centerZ = newCenter.getZ();
                cancelSelection();
                return true;
            }
            if (mouseButton != 0) return super.mouseClicked(mouseX, mouseY, mouseButton);

            paintAvailableChunks.clear();
            selectionStartXZ = screenToWorld(mouseX, mouseY);
            selectionEndXZ = selectionStartXZ;
            if (selectedTool == ZonePlannerEditor.Tool.FLOOD) {
                ZonePlan updated = new ZonePlan(getBaseSelectedArea());
                if (ZonePlannerEditor.paint(updated, selectedTool, selectionStartXZ.getX(),
                        selectionStartXZ.getZ(), selectionStartXZ.getX(), selectionStartXZ.getZ(),
                        addMode, this::availableForPaint, visibleMapBounds())) {
                    commitEdit(updated);
                }
                selectionStartXZ = null;
                selectionEndXZ = null;
                return true;
            }

            selecting = true;
            selX1 = Mth.floor(mouseX);
            selY1 = Mth.floor(mouseY);
            selX2 = selX1;
            selY2 = selY1;
            if (selectedTool == ZonePlannerEditor.Tool.BRUSH) {
                brushPreview = new ZonePlan(getBaseSelectedArea());
                ZonePlannerEditor.paint(brushPreview, selectedTool, selectionStartXZ.getX(),
                        selectionStartXZ.getZ(), selectionStartXZ.getX(), selectionStartXZ.getZ(),
                        addMode, this::availableForPaint, processorWorkBounds());
                invalidateMapTexture(true);
            }
            updateBufferedSelection(mouseX, mouseY);
            return true;
        }

        if (mouseButton == 0) {
            if (insideRelativeButton(mouseX, mouseY, undoX(), historyY(), HISTORY_SIZE, HISTORY_SIZE)) {
                if (selecting) cancelSelection();
                undoEdit();
                return true;
            }
            if (insideRelativeButton(mouseX, mouseY, redoX(), historyY(), HISTORY_SIZE, HISTORY_SIZE)) {
                if (selecting) cancelSelection();
                redoEdit();
                return true;
            }
            if ((processorMode ? insideRelativeButton(mouseX, mouseY, clearX(), historyY(), HISTORY_SIZE, HISTORY_SIZE)
                    : insideRelativeButton(mouseX, mouseY, CLEAR_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE))) {
                if (selecting) cancelSelection();
                commitEdit(new ZonePlan());
                return true;
            }
            if (insideRelativeButton(mouseX, mouseY, eraserX(), shapeY(), EDITOR_SLOT_SIZE, EDITOR_SLOT_SIZE)) {
                if (selecting) cancelSelection();
                addMode = !addMode; // Unselected by default; toggles erasing without changing the selected brush.
                return true;
            }
            for (int i = 0; i < shapePositions().length; i++) {
                if (insideRelativeButton(mouseX, mouseY, shapePositions()[i], shapeY(), EDITOR_SLOT_SIZE, EDITOR_SLOT_SIZE)) {
                    selectShape(i);
                    return true;
                }
            }
        }

        int colourSlot = getColourSlotAt(mouseX, mouseY);
        if (colourSlot >= 0) {
            selectLayer(colourSlot);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int mouseButton, double dragX, double dragY) {
        if (!processorControlsEnabled() || container instanceof ContainerRobotProcessor processor && !processor.areaReady()) return super.mouseDragged(mouseX, mouseY, mouseButton, dragX, dragY);
        if (selecting && selectionStartXZ != null) {
            selX2 = Mth.clamp(Mth.floor(mouseX), mapXScreen(), mapXScreen() + mapWidth() - 1);
            selY2 = Mth.clamp(Mth.floor(mouseY), mapYScreen(), mapYScreen() + mapHeight() - 1);
            updateBufferedSelection(selX2, selY2);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, mouseButton, dragX, dragY);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!processorControlsEnabled() || container instanceof ContainerRobotProcessor processor && !processor.areaReady()) return super.mouseReleased(mouseX, mouseY, button);
        if (selecting && selectionStartXZ != null && button == 0) {
            updateBufferedSelection(mouseX, mouseY);
            ZonePlan updatedArea = brushPreview != null ? brushPreview : new ZonePlan(getBaseSelectedArea());
            brushPreview = null;
            if (selectionEndXZ != null && selectedTool != ZonePlannerEditor.Tool.BRUSH) {
                ZonePlannerEditor.paint(updatedArea, selectedTool,
                        selectionStartXZ.getX(), selectionStartXZ.getZ(),
                        selectionEndXZ.getX(), selectionEndXZ.getZ(),
                        addMode, this::availableForPaint, processorWorkBounds());
            }
            commitEdit(updatedArea);
            selecting = false;
            selectionStartXZ = null;
            selectionEndXZ = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!processorControlsEnabled()) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        if (brushPreview != null && selectionEndXZ != null) {
            if (ZonePlannerEditor.paint(brushPreview, ZonePlannerEditor.Tool.BRUSH,
                    selectionEndXZ.getX(), selectionEndXZ.getZ(), end.getX(), end.getZ(),
                    addMode, this::availableForPaint, processorWorkBounds())) {
                invalidateMapTexture(true);
            }
        }
        selectionEndXZ = end;
    }

    private void selectLayer(int index) {
        if (index < 0 || index >= TileZonePlanner.LAYER_COUNT) {
            return;
        }
        selectedLayer = index;
        cancelSelection();
        if (!processorMode && container.tile != null) {
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
        float max = 8.0F;
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

    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        drawBackgroundLayer(getActiveGraphics(), mouseX, mouseY, partialTicks);
    }

    private void drawBackgroundLayer(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        if (processorMode) ICON_PROCESSOR.drawAt(guiGraphics, mainGui.rootElement);
        else ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);
        if (!processorControlsEnabled()) return;
        drawMap(guiGraphics, mouseX, mouseY);
        drawColourSlots(guiGraphics);
        drawEditorSlots(guiGraphics);
        drawHistoryButtons(guiGraphics, mouseX, mouseY);
        if (!processorMode && container.tile != null) {
            double progress = Mth.clamp(container.tile.deltaProgress.getDynamic(partialTicks), 0.0D, 1.0D);
            drawProgress(guiGraphics, RECT_PROGRESS, ICON_PROGRESS, 1.0D, progress);
        }
    }

    private void drawEditorSlots(GuiGraphics guiGraphics) {
        int eraserX = leftPos + eraserX();
        int slotsY = topPos + shapeY();
        drawEditorIcon(guiGraphics, TEXTURE_ERASER, eraserX, slotsY, 0, 0, 16);
        if (!addMode) {
            drawEditorSelection(guiGraphics, eraserX, slotsY);
        }
        for (int i = 0; i < shapePositions().length; i++) {
            int x = leftPos + shapePositions()[i];
            Identifier icon = switch (i) {
                case 0 -> TEXTURE_RECTANGLE;
                case 1 -> TEXTURE_OVAL;
                default -> TEXTURE_PEN;
            };
            drawEditorIcon(guiGraphics, icon, x, slotsY, 0, 0, 16);
            if (selectedTool == SHAPE_TOOLS[i]) {
                drawEditorSelection(guiGraphics, x, slotsY);
            }
        }
    }

    private void drawHistoryButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        drawEditorIcon(guiGraphics, TEXTURE_HISTORY, leftPos + undoX(), topPos + historyY(),
                0, (processorMode ? insideRelativeButton(mouseX, mouseY, undoX(), historyY(), HISTORY_SIZE, HISTORY_SIZE)
                    : insideRelativeButton(mouseX, mouseY, UNDO_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE)) ? 16 : 0, 64);
        drawEditorIcon(guiGraphics, TEXTURE_HISTORY, leftPos + redoX(), topPos + historyY(),
                16, (processorMode ? insideRelativeButton(mouseX, mouseY, redoX(), historyY(), HISTORY_SIZE, HISTORY_SIZE)
                    : insideRelativeButton(mouseX, mouseY, REDO_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE)) ? 16 : 0, 64);
        if (processorMode) {
            drawEditorIcon(guiGraphics, TEXTURE_HISTORY, leftPos + clearX(), topPos + historyY(),
                    32, insideRelativeButton(mouseX, mouseY, clearX(), historyY(), HISTORY_SIZE, HISTORY_SIZE) ? 16 : 0, 64);
        } else {
            drawEditorIcon(guiGraphics, TEXTURE_HISTORY, leftPos + CLEAR_X, topPos + HISTORY_Y,
                    32, insideRelativeButton(mouseX, mouseY, CLEAR_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE) ? 16 : 0, 64);
        }
    }

    private void drawEditorIcon(GuiGraphics guiGraphics, Identifier texture, int x, int y, int u, int v, int texSize) {
        RenderCompat.blit(guiGraphics, texture, x, y, u, v, 16, 16, texSize, texSize);
    }

    private void drawEditorSelection(GuiGraphics guiGraphics, int x, int y) {
        RenderCompat.blit(guiGraphics, TEXTURE_BASE, x, y, 0, 228, 16, 16);
    }

    private void drawColourSlots(GuiGraphics guiGraphics) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                int index = column * 4 + row;
                int x = leftPos + colourX() + column * colourStep();
                int y = topPos + colourY() + row * colourStep();
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
                    RenderCompat.blit(guiGraphics, TEXTURE_BASE, x, y, 0, 228, 16, 16);
                }
            }
        }
    }

    private void drawMap(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        Minecraft mc = minecraft;
        if (mc == null || mc.level == null) {
            return;
        }
        // Requests queued by texture rendering are metered on the client tick.
        ZonePlannerMapDataClient.INSTANCE.flushRequests(mc.level);

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
            RenderCompat.blit(guiGraphics, TEXTURE_MAP, x0, y0, 0, 0, mapW, mapH, mapW, mapH);
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

        int step = 1;
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
                if (!inProcessorWorkArea(chunkX, chunkZ)) continue;
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
                if (!inProcessorWorkArea(chunkX, chunkZ)) {
                    // Visually mask non-operable chunks without requesting their map data.
                    fillNativeImage(pixels, screenX, screenZ,
                            Math.min(step, mapW - screenX), Math.min(step, mapH - screenZ),
                            argbToAbgr(0xFF_4C_4C_4C));
                    continue;
                }
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
                        colour
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
        mapTexture = RenderCompat.newDynamicTexture(width, height, true);
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
                image.setPixel(px, py, colour);
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
        if (selectedTool == ZonePlannerEditor.Tool.BRUSH) return;
        if (selectedTool == ZonePlannerEditor.Tool.LINE || selectedTool == ZonePlannerEditor.Tool.ELLIPSE) {
            int rgb = DyeColor.byId(selectedLayer).getTextColor() & 0x00_FF_FF_FF;
            int argb = 0xFF_00_00_00 | rgb;
            int sx = Math.min(selX1, selX2), ex = Math.max(selX1, selX2);
            int sy = Math.min(selY1, selY2), ey = Math.max(selY1, selY2);
            if (selectedTool == ZonePlannerEditor.Tool.LINE) {
                int steps = Math.max(Math.abs(selX2 - selX1), Math.abs(selY2 - selY1));
                for (int i = 0; i <= steps; i++) {
                    int px = steps == 0 ? selX1 : selX1 + (selX2 - selX1) * i / steps;
                    int py = steps == 0 ? selY1 : selY1 + (selY2 - selY1) * i / steps;
                    guiGraphics.fill(px, py, px + 1, py + 1, argb);
                }
            } else {
                int steps = Math.max(32, Math.min(256, 2 * ((ex - sx) + (ey - sy))));
                double cx = (sx + ex) / 2.0D, cy = (sy + ey) / 2.0D;
                double rx = (ex - sx) / 2.0D, ry = (ey - sy) / 2.0D;
                for (int i = 0; i < steps; i++) {
                    double a = 2.0D * Math.PI * i / steps;
                    int px = (int) Math.round(cx + rx * Math.cos(a));
                    int py = (int) Math.round(cy + ry * Math.sin(a));
                    guiGraphics.fill(px, py, px + 1, py + 1, argb);
                }
            }
            return;
        }
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

    public void containerTick() {
        syncProcessorMedia();
        super.containerTick();
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!processorControlsEnabled() || container instanceof ContainerRobotProcessor processor && !processor.areaReady()) return super.keyPressed(keyCode, scanCode, modifiers);
        if (nameField != null && nameField.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_ESCAPE) {
                clearNameFocus();
                return true;
            }
            if (RenderCompat.keyPressed(nameField, keyCode, scanCode, modifiers) || nameField.canConsumeInput()) {
                return true;
            }
        }

        if (!selecting && (nameField == null || !nameField.isFocused())
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            if (keyCode == GLFW.GLFW_KEY_Z) { undoEdit(); return true; }
            if (keyCode == GLFW.GLFW_KEY_Y) { redoEdit(); return true; }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { commitEdit(new ZonePlan()); return true; }
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!processorControlsEnabled()) return super.charTyped(codePoint, modifiers);
        if (nameField != null && nameField.isFocused()) {
            return nameField.charTyped(new net.minecraft.client.input.CharacterEvent(codePoint, modifiers));
        }
        if (codePoint == '+') {
            return incBlocksPerPixel();
        }
        if (codePoint == '-') {
            return decBlocksPerPixel();
        }
        return super.charTyped(codePoint, modifiers);
    }

    public void removed() {
        closeMapTexture();
        super.removed();
    }

    private void initModeButtons() {
        if (!ClientGuiModePreferences.canSwitch(ClientGuiModePreferences.ZONE_PLANNER)) return;
        int x = leftPos + imageWidth - 38;
        int y = topPos - 16;
        legacyModeButton = addRenderableWidget(Button.builder(Component.literal("L"),
            button -> switchMode(ClientGuiModePreferences.Mode.LEGACY))
            .bounds(x, y, 17, 17).build());
        modernModeButton = addRenderableWidget(Button.builder(Component.literal("M"),
            button -> switchMode(ClientGuiModePreferences.Mode.MODERN))
            .bounds(x + 19, y, 17, 17).build());
        legacyModeButton.active = true;
        modernModeButton.active = false;
    }

    private void switchMode(ClientGuiModePreferences.Mode mode) {
        if (!ClientGuiModePreferences.canSwitch(ClientGuiModePreferences.ZONE_PLANNER)) return;
        if (mode == ClientGuiModePreferences.get(ClientGuiModePreferences.ZONE_PLANNER)) return;
        ClientGuiModePreferences.set(ClientGuiModePreferences.ZONE_PLANNER, mode);
        ClientGuiModeNavigation.show(mode == ClientGuiModePreferences.Mode.LEGACY
            ? new GuiZonePlannerLegacy(container, guiPlayerInventory, guiScreenTitle)
            : new GuiZonePlanner(container, guiPlayerInventory, guiScreenTitle));
    }
}
