package buildcraft.robotics.gui;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.world.item.ItemStack;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.RobotTaskNames;
import buildcraft.robotics.item.ItemRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.vertex.PoseStack;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.client.render.fluid.FluidRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import buildcraft.lib.compat.RenderCompat;
import net.minecraft.client.gui.components.EditBox;
import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import buildcraft.robotics.container.ContainerRobotProcessor;
import buildcraft.robotics.container.ContainerRobotProcessor.CargoEntry;
import buildcraft.robotics.container.ContainerRobotProcessor.RobotSnapshot;
import buildcraft.robotics.container.ContainerRobotProcessor.StationSnapshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.resources.Identifier;

/** Compact robot / docking-station viewer with shared Zone Planner map and palette. */
public class GuiRobotProcessor extends GuiZonePlanner<ContainerRobotProcessor> {
    private static final int VISIBLE_ROWS = 7;
    // These coordinates follow the 323x272 atlas provided for the new panel.
    private static final int HOME_X = 214, HOME_Y = 136;
    private static final int UNLINK_X = 214, UNLINK_Y = 154;
    private static final int MODE_X = 8, RENAME_X = 26, MODE_Y = 4;
    private static final int PLUS_X = 238, MINUS_X = 218, ADJUST_Y = 74;
    private static final int ROW_HEIGHT = 15;
    private static final int CARGO_X = 175, CARGO_Y = 191;
    private static final int CARGO_ROWS = 4, CARGO_ROW_HEIGHT = 18;
    private static final int CARGO_SCROLL_X = 235, CARGO_SCROLL_Y = 191;
    private static final int CARGO_SCROLL_TRAVEL = 62;
    private static final int ROBOT_SCROLL_TRAVEL = 93;

    private static final Identifier BACKGROUND = Identifier.parse("buildcraftrobotics:textures/gui/robot_processor.png");
    // Two 5x12 scrollbar skins are stacked at the top-right of the supplied atlas.
    private static final GuiIcon SCROLL_THUMB = new GuiIcon(BACKGROUND, 507, 0, 5, 12, 512);
    private static final GuiIcon SCROLL_DISABLED = new GuiIcon(BACKGROUND, 507, 12, 5, 12, 512);
    // Updated atlas: robot/station/home/unlink/pencil at x=0/16/32/48/64.
    // The red Home frame is directly above its normal sprite (y=432).
    private static final GuiIcon ROBOT_ICON = new GuiIcon(BACKGROUND, 0, 448, 16, 16, 512);
    private static final GuiIcon STATION_ICON = new GuiIcon(BACKGROUND, 16, 448, 16, 16, 512);
    private static final GuiIcon ROBOT_ICON_HOVER = new GuiIcon(BACKGROUND, 0, 464, 16, 16, 512);
    private static final GuiIcon STATION_ICON_HOVER = new GuiIcon(BACKGROUND, 16, 464, 16, 16, 512);
    private static final GuiIcon HOME_ICON = new GuiIcon(BACKGROUND, 32, 448, 16, 16, 512);
    private static final GuiIcon HOME_ICON_HOVER = new GuiIcon(BACKGROUND, 32, 464, 16, 16, 512);
    private static final GuiIcon HOME_ICON_FORCE = new GuiIcon(BACKGROUND, 32, 432, 16, 16, 512);
    private static final GuiIcon UNLINK_ICON = new GuiIcon(BACKGROUND, 48, 448, 16, 16, 512);
    private static final GuiIcon UNLINK_ICON_HOVER = new GuiIcon(BACKGROUND, 48, 464, 16, 16, 512);
    private static final GuiIcon RENAME_ICON = new GuiIcon(BACKGROUND, 64, 448, 16, 16, 512);
    private static final GuiIcon RENAME_ICON_HOVER = new GuiIcon(BACKGROUND, 64, 464, 16, 16, 512);
    private static final GuiIcon PLUS_ICON = new GuiIcon(BACKGROUND, 32, 480, 16, 16, 512);
    private static final GuiIcon PLUS_ICON_HOVER = new GuiIcon(BACKGROUND, 32, 496, 16, 16, 512);
    private static final GuiIcon MINUS_ICON = new GuiIcon(BACKGROUND, 48, 480, 16, 16, 512);
    private static final GuiIcon MINUS_ICON_HOVER = new GuiIcon(BACKGROUND, 48, 496, 16, 16, 512);

    private final ContainerRobotProcessor processorMenu;
    private int listOffset;
    private boolean draggingScrollbar;
    private boolean draggingCargoScrollbar;
    private int cargoOffset;
    private final Map<String, ItemStack> cachedRobotModels = new HashMap<>();
    private double lastMouseX, lastMouseY;
    private boolean stationMode;
    private long selectedRobotId = -1L;
    private int selectedStationX, selectedStationY, selectedStationZ, selectedStationSide = -1;
    private EditBox renameField;
    private boolean renaming;
    private boolean renameStation;
    private long renameRobotId;
    private int renameX, renameY, renameZ, renameSide;

    public GuiRobotProcessor(ContainerRobotProcessor menu, Inventory inventory, Component title) {
        super(menu, inventory, title, true);
        this.processorMenu = menu;
    }

    @Override
    public void init() {
        super.init();
        renameField = new EditBox(font, leftPos + 16, topPos + 153, 182, 16,
                Component.literal("Rename linked entry"));
        renameField.setMaxLength(32);
        renameField.visible = false;
        renameField.active = false;
        addRenderableWidget(renameField);
        renaming = false;
    }

    private void startRenaming() {
        if (renameField == null) return;
        if (stationMode) {
            StationSnapshot station = selectedStation();
            if (station == null) return;
            renameStation = true;
            renameX = station.x(); renameY = station.y(); renameZ = station.z(); renameSide = station.side();
            renameField.setValue(station.name());
        } else {
            RobotSnapshot robot = selectedRobot();
            if (robot == null) return;
            renameStation = false;
            renameRobotId = robot.id();
            renameField.setValue(robot.name());
        }
        renaming = true;
        renameField.visible = true;
        renameField.active = true;
        setFocused(renameField);
        renameField.setFocused(true);
    }

    private void finishRenaming(boolean save) {
        if (!renaming) return;
        if (save && renameField != null) {
            String value = renameField.getValue();
            if (renameStation) {
                processorMenu.renameStation(renameX, renameY, renameZ, renameSide, value);
            } else {
                processorMenu.renameRobot(renameRobotId, value);
            }
        }
        renaming = false;
        renameField.visible = false;
        renameField.active = false;
        renameField.setFocused(false);
        setFocused(null);
    }

    private boolean inRenameField(double x, double y) {
        return x >= leftPos + 16 && x < leftPos + 198
                && y >= topPos + 153 && y < topPos + 169;
    }

    private boolean inList(double x, double y) {
        return x >= leftPos + 7 && x < leftPos + 99
                && y >= topPos + 21 && y < topPos + 130;
    }

    private boolean inModeButton(double x, double y) {
        return inButton(x, y, MODE_X, MODE_Y);
    }

    private boolean inRenameButton(double x, double y) {
        return inButton(x, y, RENAME_X, MODE_Y);
    }

    private boolean inPlusButton(double x, double y) {
        return inButton(x, y, PLUS_X, ADJUST_Y);
    }

    private boolean inMinusButton(double x, double y) {
        return inButton(x, y, MINUS_X, ADJUST_Y);
    }

    private boolean inButton(double x, double y, int relativeX, int relativeY) {
        return x >= leftPos + relativeX && x < leftPos + relativeX + 16
                && y >= topPos + relativeY && y < topPos + relativeY + 16;
    }

    private boolean inHomeButton(double x, double y) {
        return x >= leftPos + HOME_X && x < leftPos + HOME_X + 16
                && y >= topPos + HOME_Y && y < topPos + HOME_Y + 16;
    }

    private boolean inUnlinkButton(double x, double y) {
        return x >= leftPos + UNLINK_X && x < leftPos + UNLINK_X + 16
                && y >= topPos + UNLINK_Y && y < topPos + UNLINK_Y + 16;
    }

    private int listSize() {
        return stationMode ? processorMenu.getStations().size() : processorMenu.getRobots().size();
    }

    private boolean inScrollTrack(double x, double y) {
        return x >= leftPos + 93 && x < leftPos + 101
                && y >= topPos + 21 && y < topPos + 22 + ROBOT_SCROLL_TRAVEL + 13;
    }

    private void scrollTo(double mouseY) {
        int maxOffset = Math.max(0, listSize() - VISIBLE_ROWS);
        if (maxOffset == 0) { listOffset = 0; return; }
        double fraction = Math.max(0.0D, Math.min(1.0D,
                (mouseY - topPos - 22.0D - 6.0D) / ROBOT_SCROLL_TRAVEL));
        listOffset = (int) Math.round(fraction * maxOffset);
    }

    private void scrollBy(int delta) {
        listOffset = Math.max(0, Math.min(Math.max(0, listSize() - VISIBLE_ROWS), listOffset + delta));
    }

    private int cargoSize() { return processorMenu.getCargo().size(); }

    private void clampCargoOffset() {
        cargoOffset = Math.max(0, Math.min(cargoOffset, Math.max(0, cargoSize() - CARGO_ROWS)));
    }

    private void cargoScrollBy(int delta) {
        cargoOffset += delta;
        clampCargoOffset();
    }

    private boolean inCargoScrollbar(double mouseX, double mouseY) {
        return mouseX >= leftPos + CARGO_SCROLL_X - 1
                && mouseX < leftPos + CARGO_SCROLL_X + 7
                && mouseY >= topPos + CARGO_SCROLL_Y
                && mouseY < topPos + CARGO_SCROLL_Y + CARGO_SCROLL_TRAVEL + 12;
    }

    private boolean inCargoWindow(double mouseX, double mouseY) {
        return mouseX >= leftPos + CARGO_X
                && mouseX < leftPos + CARGO_SCROLL_X - 1
                && mouseY >= topPos + CARGO_Y
                && mouseY < topPos + CARGO_Y + CARGO_ROWS * CARGO_ROW_HEIGHT;
    }

    private void scrollCargoTo(double mouseY) {
        int maxOffset = Math.max(0, cargoSize() - CARGO_ROWS);
        if (maxOffset == 0) { cargoOffset = 0; return; }
        double fraction = Math.max(0, Math.min(1,
                (mouseY - topPos - CARGO_SCROLL_Y - 6.0) / CARGO_SCROLL_TRAVEL));
        cargoOffset = (int) Math.round(fraction * maxOffset);
    }

    /** Hover only; cargo rows are not real slots and never accept clicks. */
    private CargoEntry hoveredCargo(double mouseX, double mouseY) {
        if (stationMode || !inCargoWindow(mouseX, mouseY)
                || processorMenu.getCargoRobotId() != selectedRobotId) return null;
        int row = (int) (mouseY - topPos - CARGO_Y) / CARGO_ROW_HEIGHT;
        int index = cargoOffset + row;
        List<CargoEntry> entries = processorMenu.getCargo();
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    private static String cargoAmount(CargoEntry entry) {
        if (!entry.isFluid()) return "x" + entry.stack().getCount();
        int amount = entry.amountMb();
        return amount >= 1000 && amount % 1000 == 0 ? amount / 1000 + " B" : amount + " mB";
    }

    private Fluid cargoFluid(String id) {
        try {
            return BuiltInRegistries.FLUID.get(net.minecraft.resources.Identifier.parse(id))
                    .map(net.minecraft.core.Holder.Reference::value).orElse(Fluids.EMPTY);
        } catch (IllegalArgumentException exception) {
            return Fluids.EMPTY;
        }
    }

    private void renderCargo(PoseStack pose) {
        // Keep the entire track visible, including an empty or inactive inventory.
        drawScrollTrack(pose, CARGO_SCROLL_X, CARGO_SCROLL_Y, CARGO_SCROLL_TRAVEL);
        if (stationMode) {
            SCROLL_DISABLED.drawAt(getActiveGraphics(),
                    leftPos + CARGO_SCROLL_X, topPos + CARGO_SCROLL_Y);
            return;
        }
        RobotSnapshot selected = selectedRobot();
        if (selected == null) {
            SCROLL_DISABLED.drawAt(getActiveGraphics(), leftPos + CARGO_SCROLL_X, topPos + CARGO_SCROLL_Y);
            return;
        }
        clampCargoOffset();
        List<CargoEntry> contents = processorMenu.getCargo();
        for (int row = 0; row < CARGO_ROWS && row + cargoOffset < contents.size(); row++) {
            CargoEntry entry = contents.get(row + cargoOffset);
            int y = CARGO_Y + row * CARGO_ROW_HEIGHT;
            panelFill(pose, CARGO_X, y, CARGO_SCROLL_X - 1, y + 17,
                    row % 2 == 0 ? 0x18_20_20_20 : 0x12_20_20_20);
            if (entry.isFluid()) {
                Fluid fluid = cargoFluid(entry.fluidId());
                if (fluid != Fluids.EMPTY) renderCargoFluid(pose, fluid, CARGO_X + 2, y);
            } else {
                renderCargoItem(entry.stack(), leftPos + CARGO_X + 2, topPos + y);
            }
            panelSmallText(pose, cargoAmount(entry), CARGO_X + 20, y + 4,
                    CARGO_SCROLL_X - CARGO_X - 23, 0xFF_22_22_22);
        }
        int count = contents.size();
        int thumbY = count <= CARGO_ROWS ? 0
                : (int) Math.round((double) cargoOffset / (count - CARGO_ROWS) * CARGO_SCROLL_TRAVEL);
        (count > CARGO_ROWS ? SCROLL_THUMB : SCROLL_DISABLED)
                .drawAt(getActiveGraphics(), leftPos + CARGO_SCROLL_X, topPos + CARGO_SCROLL_Y + thumbY);
    }

    private void renderCargoItem(ItemStack stack, int x, int y) {
        getActiveGraphics().item(stack, x, y);
    }

    private void renderCargoFluid(PoseStack pose, Fluid fluid, int x, int y) {
        FluidRenderer.drawFlowingFluidForGui(fluid, leftPos + x, topPos + y + 16,
                leftPos + x + 16, topPos + y, getActiveGraphics());
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.render(graphics, mouseX, mouseY, partialTicks);
        renderCargoTooltip(graphics, mouseX, mouseY);
    }
    private void renderCargoTooltip(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY) {
        CargoEntry hovered = hoveredCargo(mouseX, mouseY);
        if (hovered == null) return;
        if (hovered.isFluid()) {
            graphics.renderTooltip(font, java.util.List.of(cargoFluidName(hovered),
                    Component.literal(hovered.amountMb() + " mB")),
                    java.util.Optional.empty(), mouseX, mouseY);
        } else graphics.renderTooltip(font, hovered.stack(), mouseX, mouseY);
    }
    private Component cargoFluidName(CargoEntry entry) {
        String id = entry.fluidId();
        int colon = id.indexOf(':');
        String namespace = colon >= 0 ? id.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return Component.translatable("fluid." + namespace + "." + path);
    }

    private RobotSnapshot selectedRobot() {
        List<RobotSnapshot> robots = processorMenu.getRobots();
        for (RobotSnapshot snapshot : robots) {
            if (snapshot.id() == selectedRobotId) return snapshot;
        }
        if (!robots.isEmpty()) {
            selectedRobotId = robots.get(0).id();
            return robots.get(0);
        }
        selectedRobotId = -1L;
        return null;
    }

    private StationSnapshot selectedStation() {
        List<StationSnapshot> stations = processorMenu.getStations();
        for (StationSnapshot station : stations) {
            if (station.x() == selectedStationX && station.y() == selectedStationY
                    && station.z() == selectedStationZ && station.side() == selectedStationSide) return station;
        }
        if (!stations.isEmpty()) {
            StationSnapshot station = stations.get(0);
            selectStation(station);
            return station;
        }
        selectedStationSide = -1;
        return null;
    }

    private void selectStation(StationSnapshot station) {
        selectedStationX = station.x(); selectedStationY = station.y();
        selectedStationZ = station.z(); selectedStationSide = station.side();
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        super.drawBackgroundLayer(pose, mouseX, mouseY, partialTicks);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        GuiIcon icon = stationMode
                ? (inModeButton(mouseX, mouseY) ? STATION_ICON_HOVER : STATION_ICON)
                : (inModeButton(mouseX, mouseY) ? ROBOT_ICON_HOVER : ROBOT_ICON);
        icon.drawAt(getActiveGraphics(), leftPos + MODE_X, topPos + MODE_Y);
        (inRenameButton(mouseX, mouseY) ? RENAME_ICON_HOVER : RENAME_ICON)
                .drawAt(getActiveGraphics(), leftPos + RENAME_X, topPos + MODE_Y);
        (inPlusButton(mouseX, mouseY) ? PLUS_ICON_HOVER : PLUS_ICON)
                .drawAt(getActiveGraphics(), leftPos + PLUS_X, topPos + ADJUST_Y);
        (inMinusButton(mouseX, mouseY) ? MINUS_ICON_HOVER : MINUS_ICON)
                .drawAt(getActiveGraphics(), leftPos + MINUS_X, topPos + ADJUST_Y);
        renderPanel(pose);
        renderCargo(pose);
    }

    private void renderPanel(PoseStack pose) {
        // Standard machine title uses the localized block name.
        String machineTitle = title.getString();
        panelText(pose, machineTitle, 107 + Math.max(0, (210 - font.width(machineTitle)) / 2),
                6, 210, 0xFF_40_40_40);
        panelText(pose, "Max task queue: " + processorMenu.getMaxExecutionQueue(),
                215, 56, 102, 0xFF_30_30_30);
        panelText(pose, "1 - 8 tasks", 215, 94, 102, 0xFF_4A_4A_4A);
        int count = listSize();
        listOffset = Math.max(0, Math.min(listOffset, Math.max(0, count - VISIBLE_ROWS)));
        if (stationMode) renderStations(pose);
        else renderRobots(pose);
        if (!stationMode && selectedRobot() != null) {
            GuiIcon home = inHomeButton(lastMouseX, lastMouseY)
                    ? (hasShiftDown() ? HOME_ICON_FORCE : HOME_ICON_HOVER) : HOME_ICON;
            home.drawAt(getActiveGraphics(), leftPos + HOME_X, topPos + HOME_Y);
        }
        if ((stationMode ? selectedStation() != null : selectedRobot() != null)) {
            (inUnlinkButton(lastMouseX, lastMouseY) ? UNLINK_ICON_HOVER : UNLINK_ICON)
                    .drawAt(getActiveGraphics(), leftPos + UNLINK_X, topPos + UNLINK_Y);
        }
        // The thumb is a separate atlas sprite; its full-length track was missing.
        drawScrollTrack(pose, 94, 22, ROBOT_SCROLL_TRAVEL);
        int position = count <= VISIBLE_ROWS ? 0
                : (int) Math.round((double) listOffset / (count - VISIBLE_ROWS) * ROBOT_SCROLL_TRAVEL);
        (count > VISIBLE_ROWS ? SCROLL_THUMB : SCROLL_DISABLED)
                .drawAt(getActiveGraphics(), leftPos + 94, topPos + 22 + position);
    }

    private void renderRobots(PoseStack pose) {
        List<RobotSnapshot> robots = processorMenu.getRobots();
        RobotSnapshot selected = selectedRobot();
        if (selected != null) {
            if (processorMenu.getCargoRobotId() != selected.id()) cargoOffset = 0;
            processorMenu.requestCargo(selected.id());
        } else processorMenu.requestCargo(-1L);
        for (int i = 0; i < VISIBLE_ROWS && i + listOffset < robots.size(); i++) {
            RobotSnapshot robot = robots.get(i + listOffset);
            int y = 23 + i * ROW_HEIGHT;
            drawListBackground(pose, y, i, robot.id() == selectedRobotId);
            renderRobotModel(pose, robot.board(), 18, y + 7, 24, false);
            // Two lines share a 15px row, with a larger centered model in the left icon column.
            panelSmallText(pose, robot.name().isBlank() ? "#" + robot.id() : robot.name(),
                    29, y + 1, 57, robot.id() == selectedRobotId ? 0xFF_FF_E0_80 : 0xFF_20_20_20);
            panelSmallText(pose, boardName(robot.board()) + " - " + robot.status(),
                    29, y + 7, 57, 0xFF_54_54_54);
            drawListIndicator(pose, 89, y + 3, statusColor(robot.status()));
        }
        if (selected == null) {
            panelText(pose, "No linked", 10, 23, 82, 0xFF_34_34_34);
            drawCentered(pose, "No robot selected");
            return;
        }
        if (renaming && !renameStation) {
            panelText(pose, "Rename robot (Enter to save)", 12, 136, 180, 0xFF_25_25_25);
            return;
        }
        // Render the real, profession-specific 3D robot model, not the generic empty robot icon.
        renderRobotModel(pose, selected.board(), 25, 160, 32, true);
        panelText(pose, "Robot: " + (selected.name().isBlank() ? boardName(selected.board()) : selected.name()),
                45, 134, 162, 0xFF_25_25_25);
        panelText(pose, "Task: " + RobotTaskNames.shortName(selected.task()), 45, 144, 162, 0xFF_25_25_25);
        panelText(pose, "XYZ: " + selected.x() + ", " + selected.y() + ", " + selected.z(),
                45, 154, 162, 0xFF_25_25_25);
        panelText(pose, "Charge: " + selected.charge() + "% | " + selected.status(),
                45, 164, 162, 0xFF_25_25_25);
        // The scheduler has not been implemented yet, so no execution actions are queued.
        panelText(pose, "Queue: 0/" + processorMenu.getMaxExecutionQueue(),
                45, 176, 162, 0xFF_25_25_25);
    }

    private ItemStack modelForBoard(String boardId) {
        if (boardId == null || boardId.isBlank() || "Unknown".equals(boardId)) return ItemStack.EMPTY;
        return cachedRobotModels.computeIfAbsent(boardId, id ->
                ItemRobot.createRobotStack(BCRoboticsBoards.getById(id), EntityRobotBase.MAX_ENERGY));
    }

    private static int statusColor(String status) {
        return switch (status) {
            case "Working", "Moving" -> 0xFF_41_B4_4F;
            case "Charging" -> 0xFF_E9_B5_42;
            case "Sleeping", "Docked" -> 0xFF_46_8D_D3;
            case "Available" -> 0xFF_41_B4_4F;
            case "Occupied" -> 0xFF_E9_B5_42;
            default -> 0xFF_75_75_75;
        };
    }

    private void drawListBackground(PoseStack pose, int y, int row, boolean selected) {
        int baseColor = row % 2 == 0 ? 0x24_20_20_20 : 0x16_20_20_20;
        int top = y - 1;
        int bottom = y + 14;
        panelFill(pose, 8, top, 93, bottom, baseColor);
        if (selected) {
            panelFill(pose, 9, top + 1, 92, bottom - 1, 0x77_50_69_83);
        }
    }

    private void drawListIndicator(PoseStack pose, int x, int y, int color) {
        panelFill(pose, x, y, x + 3, y + 3, color);
    }

    /** Vanilla-style recessed rail below either the active or disabled thumb. */
    private void drawScrollTrack(PoseStack pose, int thumbX, int thumbY, int travel) {
        // GUI coordinates, not atlas coordinates; 5x12 thumb moves inside this 7px rail.
        // Outer 1px border + continuous inner stripe make the rail visible even when empty.
        panelFill(pose, thumbX - 1, thumbY - 1, thumbX + 6, thumbY + travel + 13, 0xFF_38_38_38);
        panelFill(pose, thumbX, thumbY, thumbX + 5, thumbY + travel + 12, 0xFF_69_69_69);
        panelFill(pose, thumbX, thumbY, thumbX + 1, thumbY + travel + 12, 0xFF_80_80_80);
    }

    private void panelFill(PoseStack pose, int x1, int y1, int x2, int y2, int argb) {
        getActiveGraphics().fill(leftPos + x1, topPos + y1, leftPos + x2, topPos + y2, argb);
    }

    /** Draws the baked 3D robot model selected by its real board ID. */
    private void renderRobotModel(PoseStack pose, String boardId, int cx, int cy, int pixelSize, boolean rotate) {
        ItemStack stack = modelForBoard(boardId);
        if (stack.isEmpty()) return;
        // Extraction-based GUI rendering honors the robot model's gui_light=front lighting.
        // Both list and detail previews use the same front-lit item model.
        // The baked model still contains the real 3D robot geometry and profession texture.
        var graphics = getActiveGraphics();
        BCGraphics.push(graphics);
        BCGraphics.translate(graphics, leftPos + cx, topPos + cy);
        float scale = pixelSize / 16.0F;
        BCGraphics.scale(graphics, scale, scale);
        //? if >=1.21.11 {
        graphics.item(stack, -8, -8);
        //?} else {
        /*?
        graphics.renderItem(stack, -8, -8);
        ?*/
        //?}
        BCGraphics.pop(graphics);
    }

    private void renderStations(PoseStack pose) {
        processorMenu.requestCargo(-1L);
        List<StationSnapshot> stations = processorMenu.getStations();
        StationSnapshot selected = selectedStation();
        for (int i = 0; i < VISIBLE_ROWS && i + listOffset < stations.size(); i++) {
            StationSnapshot station = stations.get(i + listOffset);
            boolean active = selected != null && selected.x() == station.x() && selected.y() == station.y()
                    && selected.z() == station.z() && selected.side() == station.side();
            int y = 23 + i * ROW_HEIGHT;
            drawListBackground(pose, y, i, active);
            panelSmallText(pose, "[S]", 10, y + 1, 15, 0xFF_40_64_74);
            panelSmallText(pose, station.name().isBlank()
                    ? station.x() + "," + station.z() : station.name(),
                    29, y - 1, 57, active ? 0xFF_FF_E0_80 : 0xFF_24_24_24);
            panelSmallText(pose, sideName(station.side()) + " - " + station.status(),
                    29, y + 5, 57, 0xFF_54_54_54);
            drawListIndicator(pose, 89, y + 3, statusColor(station.status()));
        }
        if (selected == null) {
            panelText(pose, "No linked", 10, 23, 82, 0xFF_34_34_34);
            drawCentered(pose, "No station selected");
            return;
        }
        if (renaming && renameStation) {
            panelText(pose, "Rename station (Enter to save)", 12, 136, 180, 0xFF_25_25_25);
            return;
        }
        panelText(pose, selected.name().isBlank() ? "Docking Station" : selected.name(),
                12, 138, 145, 0xFF_25_25_25);
        panelText(pose, "XYZ: " + selected.x() + ", " + selected.y() + ", " + selected.z(),
                12, 150, 146, 0xFF_25_25_25);
        panelText(pose, "Face: " + sideName(selected.side()), 12, 162, 145, 0xFF_25_25_25);
        panelText(pose, "Status: " + selected.status(), 12, 174, 145, 0xFF_25_25_25);
    }

    private void drawCentered(PoseStack pose, String message) {
        // Information panel: x=7..210, y=132..186 in the latest atlas.
        int x = 8 + Math.max(0, (202 - font.width(message)) / 2);
        panelText(pose, message, x, 155, 202, 0xFF_30_30_30);
    }

    private static String sideName(int side) {
        return switch (side) {
            case 0 -> "Down"; case 1 -> "Up"; case 2 -> "North";
            case 3 -> "South"; case 4 -> "West"; case 5 -> "East";
            default -> "?";
        };
    }

    private void panelSmallText(PoseStack pose, String text, int x, int y, int maxWidth, int color) {
        final float factor = 0.65F;
        String fitted = fitText(text, (int) (maxWidth / factor));
        var graphics = getActiveGraphics();
        BCGraphics.push(graphics);
        BCGraphics.translate(graphics, leftPos + x, topPos + y);
        BCGraphics.scale(graphics, factor, factor);
        drawString(pose, font, fitted, 0, 0, color, false);
        BCGraphics.pop(graphics);
    }

    private void panelText(PoseStack pose, String text, int x, int y, int maxWidth, int color) {
        drawString(getActiveGraphics(), font, fitText(text, maxWidth), leftPos + x, topPos + y, color, false);
    }

    private String fitText(String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        if (maxWidth <= font.width("~")) return "";
        int limit = maxWidth - font.width("~");
        int length = text.length();
        while (length > 0 && font.width(text.substring(0, length)) > limit) length--;
        return text.substring(0, length) + "~";
    }

    private static String boardName(String board) {
        if (board == null || board.isBlank()) return "Unprogrammed";
        int colon = board.lastIndexOf(':');
        if (colon >= 0) board = board.substring(colon + 1);
        if (board.startsWith("boardRobot")) board = board.substring("boardRobot".length());
        if (board.startsWith("robot_")) board = board.substring("robot_".length());
        return board.isEmpty() ? "Unprogrammed" : board;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && inCargoScrollbar(x, y) && cargoSize() > CARGO_ROWS) {
            draggingCargoScrollbar = true;
            scrollCargoTo(y);
            return true;
        }
        if (button == 0 && inCargoWindow(x, y)) return true; // Read-only cargo list.
        if (button == 0 && !renaming && inScrollTrack(x, y) && listSize() > VISIBLE_ROWS) {
            draggingScrollbar = true;
            scrollTo(y);
            return true;
        }
        if (renaming) {
            if (inRenameField(x, y)) return super.mouseClicked(x, y, button);
            finishRenaming(true);
        }
        if (button == 0 && inRenameButton(x, y)) {
            startRenaming();
            return true;
        }
        if (button == 0 && inPlusButton(x, y)) {
            processorMenu.setMaxExecutionQueue(hasShiftDown()
                    ? 8 : processorMenu.getMaxExecutionQueue() + 1);
            return true;
        }
        if (button == 0 && inMinusButton(x, y)) {
            processorMenu.setMaxExecutionQueue(hasShiftDown()
                    ? 1 : processorMenu.getMaxExecutionQueue() - 1);
            return true;
        }
        if (button == 0 && inModeButton(x, y)) {
            stationMode = !stationMode;
            listOffset = 0;
            return true;
        }
        if (button == 0 && !stationMode && inHomeButton(x, y)) {
            RobotSnapshot robot = selectedRobot();
            if (robot != null) {
                if (hasShiftDown()) processorMenu.forceRobotChunk(robot.id());
                else processorMenu.recall(robot.id());
            }
            return true;
        }
        if (button == 0 && inUnlinkButton(x, y)) {
            if (stationMode) {
                StationSnapshot station = selectedStation();
                if (station != null) processorMenu.unlinkStation(station.x(), station.y(), station.z(), station.side());
            } else {
                RobotSnapshot robot = selectedRobot();
                if (robot != null) processorMenu.unlinkRobot(robot.id());
            }
            return true;
        }
        if (button == 0 && inList(x, y)) {
            int row = (int) (y - (topPos + 22)) / ROW_HEIGHT;
            int index = listOffset + row;
            if (y >= topPos + 22 && row >= 0 && row < VISIBLE_ROWS && index < listSize()) {
                if (stationMode) selectStation(processorMenu.getStations().get(index));
                else selectedRobotId = processorMenu.getRobots().get(index).id();
            }
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int mouseButton, double dragX, double dragY) {
        if (draggingCargoScrollbar && mouseButton == 0) {
            scrollCargoTo(mouseY);
            return true;
        }
        if (draggingScrollbar && mouseButton == 0) {
            scrollTo(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, mouseButton, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingCargoScrollbar && button == 0) {
            draggingCargoScrollbar = false;
            return true;
        }
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renaming) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                finishRenaming(true);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                finishRenaming(false);
                return true;
            }
            return RenderCompat.keyPressed(renameField, keyCode, scanCode, modifiers);
        }
        // Shift pressed while hovering Home triggers the explicit force-load action.
        if ((keyCode == GLFW.GLFW_KEY_LEFT_SHIFT || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT)
                && !stationMode && inHomeButton(lastMouseX, lastMouseY)) {
            RobotSnapshot robot = selectedRobot();
            if (robot != null) processorMenu.forceRobotChunk(robot.id());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char value, int modifiers) {
        if (renaming) return renameField.charTyped(new net.minecraft.client.input.CharacterEvent(value, modifiers));
        return super.charTyped(value, modifiers);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double deltaX, double deltaY) {
        if (inCargoWindow(x, y) || inCargoScrollbar(x, y)) {
            cargoScrollBy(deltaY > 0 ? -1 : 1);
            return true;
        }
        if (inList(x, y)) {
            scrollBy(deltaY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, deltaX, deltaY);
    }
}
