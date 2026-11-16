/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.builders.menu.ContainerElectronicLibrary;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.gui.button.GuiButtonDrawable;
import buildcraft.lib.gui.button.IButtonClickEventTrigger;
import buildcraft.lib.gui.button.StandardSpriteButtons;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.pos.IGuiPosition;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.gui.help.GuiHelpUtil;

public class GuiElectronicLibrary extends GuiBC8<ContainerElectronicLibrary> {
    private static final ResourceLocation TEXTURE_BASE =
        ResourceLocation.parse("buildcraftbuilders:textures/gui/electronic_library.png");
    private static final int SIZE_X = 244, SIZE_Y = 230;
    // Only the left inventory panel extends below the old 220 px texture cutoff.
    // Crop the footer to avoid pulling progress-arrow sprites from the right side of the atlas.
    private static final int GUI_OLD_HEIGHT = 220;
    private static final GuiIcon ICON_GUI_FOOTER = new GuiIcon(TEXTURE_BASE, 0, GUI_OLD_HEIGHT, 176, SIZE_Y - GUI_OLD_HEIGHT);
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, GUI_OLD_HEIGHT);
    private static final GuiIcon ICON_PROGRESS_DOWN = new GuiIcon(TEXTURE_BASE, 234, 240, 22, 16);
    private static final GuiRectangle RECT_PROGRESS_DOWN = new GuiRectangle(194, 68, 22, 16);
    private static final GuiIcon ICON_PROGRESS_UP = new GuiIcon(TEXTURE_BASE, 234, 224, 22, 16);
    private static final GuiRectangle RECT_PROGRESS_UP = new GuiRectangle(194, 89, 22, 16);
    private static final GuiRectangle RECT_SNAPSHOT_LIST = new GuiRectangle(8, 32, 154, 108);
    private static final GuiRectangle RECT_SEARCH = new GuiRectangle(7, 15, 155, 14);
    private static final int ROW_TITLE_OFFSET = 22;
    private static final float TITLE_SCALE = 1.12F;
    private static final float SUBTITLE_SCALE = 0.80F;
    private static final int SNAPSHOT_ROW_HEIGHT = 20;
    private static final int MAX_VISIBLE_SNAPSHOTS = RECT_SNAPSHOT_LIST.height < SNAPSHOT_ROW_HEIGHT ? 0
        : (int) (RECT_SNAPSHOT_LIST.height / SNAPSHOT_ROW_HEIGHT);
    private static final int SCROLLBAR_WIDTH = 7;
    private static final int SCROLLBAR_GAP = 3;
    private static final int SCROLLBAR_MIN_THUMB_HEIGHT = 12;
    private static final int LIST_TEXT_WIDTH = (int) RECT_SNAPSHOT_LIST.width - SCROLLBAR_WIDTH - SCROLLBAR_GAP - 3;
    private static final GuiRectangle RECT_DELETE_BUTTON = new GuiRectangle(174, 119, 50, 15);

    private final GuiButtonDrawable delButton;
    private final ElectronicLibraryViewport<Snapshot.Key> viewport = new ElectronicLibraryViewport<>(MAX_VISIBLE_SNAPSHOTS);
    private boolean draggingScrollbar;
    private double scrollbarDragOffset;
    private EditBox searchField;
    private final Map<Snapshot.Key, EntryAppearance> entryAppearances = new HashMap<>();

    private record EntryAppearance(ItemStack icon, Component typeLabel) { }

    public GuiElectronicLibrary(ContainerElectronicLibrary container, Inventory inv, Component title) {
        super(container, inv, title, SIZE_X, SIZE_Y);
        IGuiPosition buttonPos = mainGui.rootElement.offset(174, 119);
        delButton = new GuiButtonDrawable(mainGui, "del", buttonPos, StandardSpriteButtons.QUARTER_BUTTON_DRAWABLE);
        delButton.enabled = false;
        delButton.registerListener(this::onDelButtonClick);
        mainGui.shownElements.add(delButton);
        mainGui.shownElements.add(delButton.createTextElement(Component.translatable("gui.buildcraftbuilders.library.delete")));
        // Help Tab highlights the complete search field, including its background frame.
        GuiHelpUtil.addRoot(mainGui, (int) RECT_SEARCH.x, (int) RECT_SEARCH.y,
            (int) RECT_SEARCH.width, (int) RECT_SEARCH.height,
            "buildcraft.help.library.search.title", 0xFF_66_AA_FF,
            "buildcraft.help.library.search.desc");
        GuiHelpUtil.addRoot(mainGui, (int) RECT_SNAPSHOT_LIST.x, (int) RECT_SNAPSHOT_LIST.y, (int) RECT_SNAPSHOT_LIST.width,
            (int) RECT_SNAPSHOT_LIST.height, "buildcraft.help.library.list.title", 0xFF_66_AA_FF,
            "buildcraft.help.library.list.desc");
        GuiHelpUtil.addSlot(mainGui, 175, 67, "buildcraft.help.library.download_output.title", 0xFF_88_CC_88, "buildcraft.help.library.download_output.desc");
        GuiHelpUtil.addSlot(mainGui, 219, 67, "buildcraft.help.library.download_input.title", 0xFF_DD_CC_55, "buildcraft.help.library.download_input.desc");
        GuiHelpUtil.addSlot(mainGui, 175, 89, "buildcraft.help.library.upload_input.title", 0xFF_CC_AA_FF, "buildcraft.help.library.upload_input.desc");
        GuiHelpUtil.addSlot(mainGui, 219, 89, "buildcraft.help.library.upload_output.title", 0xFF_55_BB_DD, "buildcraft.help.library.upload_output.desc");
        // Download and upload have separate progress arrows, not one combined 22x37 zone.
        GuiHelpUtil.addRoot(mainGui, 194, 68, 22, 16, "buildcraft.help.library.download_arrow.title",
            0xFF_88_CC_FF, "buildcraft.help.library.download_arrow.desc");
        GuiHelpUtil.addRoot(mainGui, 194, 89, 22, 16, "buildcraft.help.library.upload_arrow.title",
            0xFF_88_AA_FF, "buildcraft.help.library.upload_arrow.desc");
        GuiHelpUtil.addRoot(mainGui, (int) RECT_DELETE_BUTTON.x, (int) RECT_DELETE_BUTTON.y, (int) RECT_DELETE_BUTTON.width,
            (int) RECT_DELETE_BUTTON.height, "buildcraft.help.library.delete.title", 0xFF_CC_55_55,
            "buildcraft.help.library.delete.desc");
    }

    // All 26.X targets, including the 26.1.2 NeoForge GuiBC8 downport,
    // expose extractRenderState(GuiGraphicsExtractor, int, int, float).
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        renderLibraryEntryTooltip(graphics, mouseX, mouseY);
    }

    @Override
    public void init() {
        // Keep the filter when changing GUI scale or resizing the game window.
        String query = searchField == null ? "" : searchField.getValue();
        boolean searchFocused = searchField != null && searchField.isFocused();
        super.init();
        searchField = new EditBox(font,
            leftPos + (int) RECT_SEARCH.x + 4, topPos + (int) RECT_SEARCH.y + 3,
            (int) RECT_SEARCH.width - 8, (int) RECT_SEARCH.height - 2,
            Component.translatable("gui.buildcraftbuilders.library.search"));
        searchField.setBordered(false);
        searchField.setTextColor(0xFFFFFFFF);
        searchField.setMaxLength(256);
        searchField.setValue(query);
        searchField.setResponder(value -> {
            // A newly typed query starts at the first matching entry.
            viewport.scrollToFraction(filteredSnapshots(GlobalSavedDataSnapshots.getClientSnapshotList()), 0);
        });
        addRenderableWidget(searchField);
        if (searchFocused) {
            setInitialFocus(searchField);
        } else {
            setFocused(null);
        }
    }

    /** Filtering is local only; entries retain their real snapshot keys for all menu actions. */
    private List<Snapshot.Key> filteredSnapshots(List<Snapshot.Key> allEntries) {
        String query = searchField == null ? "" : searchField.getValue().strip().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return allEntries;
        }
        return allEntries.stream()
            .filter(key -> ElectronicLibraryEntryTooltip.displayName(key).toLowerCase(Locale.ROOT).contains(query))
            .toList();
    }

    private EntryAppearance appearanceFor(Snapshot.Key key) {
        return entryAppearances.computeIfAbsent(key, k -> {
            Snapshot snapshot = GlobalSavedDataSnapshots.getClientSnapshot(k);
            if (snapshot == null) {
                return new EntryAppearance(ItemStack.EMPTY,
                    Component.translatable("gui.buildcraftbuilders.library.unavailable"));
            }
            ItemStack icon = ElectronicLibraryEntryTooltip.inventoryItem(k, snapshot);
            // Headerless legacy records still need a recognizable item icon.
            if (icon.isEmpty()) {
                icon = ItemSnapshot.getClean(snapshot.getType());
            }
            return new EntryAppearance(icon,
                Component.translatable(snapshot.getType() == EnumSnapshotType.TEMPLATE
                    ? "item.buildcraftbuilders.template" : "item.buildcraftbuilders.blueprint"));
        });
    }

    private void renderLibraryEntryTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        Snapshot.Key key = hoveredEntry(mouseX, mouseY);
        if (key == null) return;
        Snapshot snapshot = GlobalSavedDataSnapshots.getClientSnapshot(key);
        // The library owns one framed 3D preview. Inventory tooltip lines
        // are intentionally excluded so they cannot overlap the preview.
        List<Component> tooltipLines = ElectronicLibraryEntryTooltip.compactLines(key);
        if (snapshot != null) {
            int x = Math.max(4, Math.min(width - 108, mouseX + 16));
            int y = Math.max(4, Math.min(height - 108, mouseY + 36));
            BlueprintPreviewFrame.draw(graphics::fill, x, y, 100, 100);
            buildcraft.builders.client.render.BlueprintRenderer.renderSnapshot(graphics, snapshot, x, y, 100, 100);
        }
        buildcraft.lib.compat.RenderCompat.renderComponentTooltip(graphics, font,
            tooltipLines, mouseX, mouseY);
    }

    private Snapshot.Key hoveredEntry(int mouseX, int mouseY) {
        // The scrollbar/gap is not an entry, and an empty row must never show a stale tooltip.
        if (draggingScrollbar || !insideList(mouseX, mouseY)
            || mouseX >= listLeft() + LIST_TEXT_WIDTH + 1) {
            return null;
        }
        List<Snapshot.Key> entries = filteredSnapshots(GlobalSavedDataSnapshots.getClientSnapshotList());
        viewport.update(entries);
        int row = (int) ((mouseY - listTop()) / SNAPSHOT_ROW_HEIGHT);
        return viewport.entryAtRow(entries, row);
    }

    private void onDelButtonClick(IButtonClickEventTrigger button, int buttonKey) {
        if (container.tile.selected != null) {
            Snapshot snapshot = GlobalSavedDataSnapshots.getClientSnapshot(container.tile.selected);
            if (snapshot != null) {
                container.sendSelectedToServer(null);
                container.tile.selected = null;
                GlobalSavedDataSnapshots.removeClientSnapshot(snapshot.key);
                entryAppearances.remove(snapshot.key);
                viewport.update(filteredSnapshots(GlobalSavedDataSnapshots.getClientSnapshotList()));
                delButton.enabled = false;
            }
        }
    }

    @Override
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
        String machineName = title.getString();
        int titleX = (int) mainGui.rootElement.getX() + (SIZE_X - font.width(machineName)) / 2;
        int titleY = (int) mainGui.rootElement.getY() + 5;
        drawString(guiGraphics, font, machineName, titleX, titleY, 0xFF_40_40_40, false);
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
        ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);
        ICON_GUI_FOOTER.drawAt(guiGraphics, mainGui.rootElement.getX(),
            mainGui.rootElement.getY() + GUI_OLD_HEIGHT);
        drawHorizontalProgress(guiGraphics, RECT_PROGRESS_DOWN, ICON_PROGRESS_DOWN, container.tile.getDownloadProgress(partialTicks), true);
        drawHorizontalProgress(guiGraphics, RECT_PROGRESS_UP, ICON_PROGRESS_UP, container.tile.getUploadProgress(partialTicks), false);
        drawSearchBackground(guiGraphics);
        List<Snapshot.Key> allEntries = GlobalSavedDataSnapshots.getClientSnapshotList();
        List<Snapshot.Key> entries = filteredSnapshots(allEntries);
        viewport.update(entries);
        // Filtering must never clear the server-side selection. Only actual deletion can do that.
        if (container.tile.selected != null && !allEntries.contains(container.tile.selected)) {
            container.sendSelectedToServer(null);
            container.tile.selected = null;
        }
        iterateSnapshots(entries, (i, rect, key) -> {
            boolean isSelected = key.equals(container.tile.selected);
            if (isSelected) {
                drawGradientRect(guiGraphics, rect, 0xFF_55_55_55, 0xFF_55_55_55);
            }
            EntryAppearance appearance = appearanceFor(key);
            if (!appearance.icon().isEmpty()) {
                GuiUtil.drawItemStackAt(appearance.icon(), guiGraphics, (int) rect.x + 2, (int) rect.y + 2);
            }
            int textX = (int) rect.x + ROW_TITLE_OFFSET;
            int titleWidth = Math.max(1, (int) ((LIST_TEXT_WIDTH - ROW_TITLE_OFFSET - 2) / TITLE_SCALE));
            int colour = isSelected ? 0xFF_FF_FF_A0 : 0xFF_F4_F4_F4;
            String text = ElectronicLibraryEntryTooltip.displayName(key);
            drawHighlightedEntryName(guiGraphics, fitSnapshotName(text, titleWidth), textX, (int) rect.y + 1, colour);
            drawScaledEntryText(guiGraphics, appearance.typeLabel().getString(), textX, (int) rect.y + 12,
                SUBTITLE_SCALE, isSelected ? 0xFF_E0_D3_A5 : 0xFF_BB_BB_BB);
            // A one-pixel separator ensures the 20-pixel entries remain distinct.
            drawGradientRect(guiGraphics, new GuiRectangle(rect.x, rect.y + SNAPSHOT_ROW_HEIGHT - 1,
                RECT_SNAPSHOT_LIST.width, 1), 0xFF_77_77_77, 0xFF_77_77_77);
        });
        drawScrollbar(guiGraphics, entries);
        delButton.enabled = container.tile.selected != null && entries.contains(container.tile.selected)
            && GlobalSavedDataSnapshots.getClientSnapshot(container.tile.selected) != null;
    }

    private void drawSearchBackground(GuiGraphicsExtractor guiGraphics) {
        double x = mainGui.rootElement.getX() + RECT_SEARCH.x;
        double y = mainGui.rootElement.getY() + RECT_SEARCH.y;
        drawGradientRect(guiGraphics, new GuiRectangle(x, y, RECT_SEARCH.width, RECT_SEARCH.height),
            0xFF_40_40_40, 0xFF_40_40_40);
        drawGradientRect(guiGraphics, new GuiRectangle(x + 1, y + 1, RECT_SEARCH.width - 2, RECT_SEARCH.height - 2),
            0xFF_6C_6C_6C, 0xFF_6C_6C_6C);
        if (searchField != null && !searchField.isFocused() && searchField.getValue().isEmpty()) {
            drawString(guiGraphics, font, Component.translatable("gui.buildcraftbuilders.library.search").getString(),
                x + 5, y + 4, 0xFF_C8_C8_C8);
        }
    }

    private void drawScaledEntryText(GuiGraphicsExtractor guiGraphics, String text, int x, int y, float scale, int colour) {
        BCGraphics.push(guiGraphics);
        try {
            BCGraphics.translate(guiGraphics, x, y);
            BCGraphics.scale(guiGraphics, scale, scale);
            drawString(guiGraphics, font, text, 0, 0, colour, false);
        } finally {
            BCGraphics.pop(guiGraphics);
        }
    }

    /** Colour matched letters instead of filling their background; measure at unscaled font width. */
    private void drawHighlightedEntryName(GuiGraphicsExtractor guiGraphics, String visibleName, int x, int y, int baseColour) {
        String query = searchField == null ? "" : searchField.getValue();
        BCGraphics.push(guiGraphics);
        try {
            BCGraphics.translate(guiGraphics, x, y);
            BCGraphics.scale(guiGraphics, TITLE_SCALE, TITLE_SCALE);
            int offset = 0;
            for (ElectronicLibraryNameHighlight.Segment segment : ElectronicLibraryNameHighlight.segments(visibleName, query)) {
                drawString(guiGraphics, font, segment.text(), offset, 0,
                    segment.highlighted() ? 0xFF_FF_D5_35 : baseColour, false);
                offset += font.width(segment.text());
            }
        } finally {
            BCGraphics.pop(guiGraphics);
        }
    }

    private String fitSnapshotName(String text, int maxWidth) {
        if (text == null || text.isEmpty() || font.width(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        int prefixWidth = maxWidth - font.width(ellipsis);
        if (prefixWidth <= 0) {
            return font.plainSubstrByWidth(text, maxWidth);
        }
        return font.plainSubstrByWidth(text, prefixWidth) + ellipsis;
    }

    private void drawHorizontalProgress(GuiGraphicsExtractor guiGraphics, GuiRectangle rect, GuiIcon icon, double progress, boolean rightToLeft) {
        if (progress <= 0) {
            return;
        }
        progress = Math.min(1, progress);
        double width = rect.width * progress;
        double x = mainGui.rootElement.getX() + rect.x;
        if (rightToLeft) {
            x += rect.width - width;
        }
        double y = mainGui.rootElement.getY() + rect.y;
        double u = rightToLeft ? 1 - progress : 0;
        ISprite sprite = GuiUtil.subRelative(icon.sprite, u, 0, progress, 1);
        GuiIcon.draw(guiGraphics, sprite, x, y, x + width, y + rect.height);
    }


    private void iterateSnapshots(List<Snapshot.Key> entries, ISnapshotIterator iterator) {
        int first = viewport.firstIndex(entries);
        int count = viewport.visibleCount(entries);
        GuiRectangle rect = new GuiRectangle(mainGui.rootElement.getX() + RECT_SNAPSHOT_LIST.x,
            mainGui.rootElement.getY() + RECT_SNAPSHOT_LIST.y,
            RECT_SNAPSHOT_LIST.width, SNAPSHOT_ROW_HEIGHT);
        for (int row = 0; row < count; row++) {
            iterator.call(first + row, rect, entries.get(first + row));
            rect = rect.offset(0, SNAPSHOT_ROW_HEIGHT);
        }
    }

    private double listLeft() {
        return mainGui.rootElement.getX() + RECT_SNAPSHOT_LIST.x;
    }

    private double listTop() {
        return mainGui.rootElement.getY() + RECT_SNAPSHOT_LIST.y;
    }

    private double scrollbarLeft() {
        return listLeft() + RECT_SNAPSHOT_LIST.width - SCROLLBAR_WIDTH;
    }

    private boolean insideList(double x, double y) {
        return x >= listLeft() && x < listLeft() + RECT_SNAPSHOT_LIST.width
            && y >= listTop() && y < listTop() + RECT_SNAPSHOT_LIST.height;
    }

    /** Includes the full search background, not just the inset EditBox. */
    private boolean insideSearch(double x, double y) {
        double left = mainGui.rootElement.getX() + RECT_SEARCH.x;
        double top = mainGui.rootElement.getY() + RECT_SEARCH.y;
        return x >= left && x < left + RECT_SEARCH.width
            && y >= top && y < top + RECT_SEARCH.height;
    }

    private int scrollbarThumbHeight(int total) {
        if (total <= MAX_VISIBLE_SNAPSHOTS) {
            return (int) RECT_SNAPSHOT_LIST.height;
        }
        return Math.max(SCROLLBAR_MIN_THUMB_HEIGHT,
            (int) Math.round(RECT_SNAPSHOT_LIST.height * MAX_VISIBLE_SNAPSHOTS / (double) total));
    }

    private double scrollbarThumbTop(List<Snapshot.Key> entries) {
        int movement = (int) RECT_SNAPSHOT_LIST.height - scrollbarThumbHeight(entries.size());
        int max = viewport.maxFirstIndex(entries.size());
        return listTop() + (max == 0 ? 0 : movement * viewport.firstIndex(entries) / (double) max);
    }

    private void drawScrollbar(GuiGraphicsExtractor guiGraphics, List<Snapshot.Key> entries) {
        if (entries.size() <= MAX_VISIBLE_SNAPSHOTS) {
            return;
        }
        double x = scrollbarLeft();
        double y = listTop();
        drawGradientRect(guiGraphics, new GuiRectangle(x, y, SCROLLBAR_WIDTH, RECT_SNAPSHOT_LIST.height),
            0xFF_33_33_33, 0xFF_33_33_33);
        drawGradientRect(guiGraphics, new GuiRectangle(x + 1, scrollbarThumbTop(entries) + 1,
            SCROLLBAR_WIDTH - 2, scrollbarThumbHeight(entries.size()) - 2),
            draggingScrollbar ? 0xFF_DD_DD_DD : 0xFF_B8_B8_B8,
            draggingScrollbar ? 0xFF_DD_DD_DD : 0xFF_B8_B8_B8);
    }

    private void scrollFromMouse(List<Snapshot.Key> entries, double mouseY) {
        int travel = (int) RECT_SNAPSHOT_LIST.height - scrollbarThumbHeight(entries.size());
        if (travel <= 0) {
            return;
        }
        viewport.scrollToFraction(entries, (mouseY - scrollbarDragOffset - listTop()) / travel);
    }

    // Search consumes keyboard input before the container screen can close on the inventory key.
    // Escape is handled before both the EditBox and BuildCraft's container key bindings.
    // First press leaves the search box; the next closes the machine GUI normally.
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Native key codes changed with the SDL input backend in 26.3.
        // Use Minecraft's escape constant instead of the old GLFW value 256.
        if (keyCode == InputConstants.KEY_ESCAPE) {
            if (searchField != null && searchField.isFocused()) {
                searchField.setFocused(false);
                setFocused(null);
            } else {
                onClose();
            }
            return true;
        }
        if (searchField != null && searchField.isFocused()) {
            searchField.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchField != null && searchField.isFocused()) {
            searchField.charTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        // Clicking anywhere outside the search bar ends text entry, without clearing
        // the filter or consuming the click (slots, buttons and list still work).
        if (searchField != null && searchField.isFocused() && !insideSearch(mouseX, mouseY)) {
            searchField.setFocused(false);
            setFocused(null);
        }
        if (mouseButton == 0 && insideList(mouseX, mouseY)) {
            List<Snapshot.Key> entries = filteredSnapshots(GlobalSavedDataSnapshots.getClientSnapshotList());
            viewport.update(entries);
            if (mouseX >= scrollbarLeft()) {
                if (entries.size() > MAX_VISIBLE_SNAPSHOTS) {
                    double thumbTop = scrollbarThumbTop(entries);
                    double thumbHeight = scrollbarThumbHeight(entries.size());
                    scrollbarDragOffset = mouseY >= thumbTop && mouseY < thumbTop + thumbHeight
                        ? mouseY - thumbTop : thumbHeight / 2.0;
                    draggingScrollbar = true;
                    scrollFromMouse(entries, mouseY);
                }
                return true;
            }
            int row = (int) ((mouseY - listTop()) / SNAPSHOT_ROW_HEIGHT);
            Snapshot.Key key = viewport.entryAtRow(entries, row);
            if (key != null) {
                container.sendSelectedToServer(key);
                container.tile.selected = key;
                delButton.enabled = GlobalSavedDataSnapshots.getClientSnapshot(key) != null;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar && button == 0) {
            scrollFromMouse(filteredSnapshots(GlobalSavedDataSnapshots.getClientSnapshotList()), mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingScrollbar && button == 0) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (insideList(mouseX, mouseY) && scrollY != 0) {
            int rows = Math.max(1, (int) Math.round(Math.abs(scrollY) * 3));
            viewport.scrollBy(filteredSnapshots(GlobalSavedDataSnapshots.getClientSnapshotList()), scrollY > 0 ? -rows : rows);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @FunctionalInterface
    private interface ISnapshotIterator {
        void call(int snapshotIndex, GuiRectangle rect, Snapshot.Key key);
    }
}
