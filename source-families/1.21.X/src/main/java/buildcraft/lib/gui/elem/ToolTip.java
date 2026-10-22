/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.elem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

import com.google.common.collect.ForwardingList;

import buildcraft.lib.misc.StringUtilBC;
import net.minecraft.network.chat.Component;

public class ToolTip extends ForwardingList<Component> implements RandomAccess {

    /* If the impl list class does not implement RandomAccess then the interface MUST be removed from this class */
    private final List<Component> delegate = new ArrayList<>();
    private final long delay;
    private long mouseOverStart;

    /** Creates a {@link ToolTip} based off of an array of localisation keys. The localised strings can use "\n" to
     * split up into separate lines. */
    public static ToolTip createLocalized(Component... localeKeys) {
        List<Component> allLines = new ArrayList<>();
        for (Component component : localeKeys) {
            addLocalizedLines(allLines, component);
        }
        return new ToolTip(allLines);
    }
    
    public static ToolTip createLocalized(String... localeKeys) {
        List<Component> allLines = new ArrayList<>();
        for (String key : localeKeys) {
            addLocalizedLines(allLines, Component.translatable(key));
        }
        return new ToolTip(allLines);
    }

    private static void addLocalizedLines(List<Component> lines, Component localizedComponent) {
        // Resolve the translation before splitting it. Splitting the translation
        // key itself (for example "gui.list.metadata") can never see the line
        // breaks contained in the language file.
        for (String line : StringUtilBC.splitIntoLines(localizedComponent.getString())) {
            lines.add(Component.literal(line).withStyle(localizedComponent.getStyle()));
        }
    }

    public ToolTip(Component... lines) {
        this.delay = 0;
        delegate.addAll(Arrays.asList(lines));
    }

    public ToolTip(int delay, Component... lines) {
        this.delay = delay;
        delegate.addAll(Arrays.asList(lines));
    }

    public ToolTip(List<Component> lines) {
        this.delay = 0;
        delegate.addAll(lines);
    }

    @Override
    protected final List<Component> delegate() {
        return delegate;
    }

    public void onTick(boolean mouseOver) {
        if (delay == 0) {
            return;
        }
        if (mouseOver) {
            if (mouseOverStart == 0) {
                mouseOverStart = System.currentTimeMillis();
            }
        } else {
            mouseOverStart = 0;
        }
    }

    public boolean isReady() {
        if (delay == 0) {
            return true;
        }
        if (mouseOverStart == 0) {
            return false;
        }
        return System.currentTimeMillis() - mouseOverStart >= delay;
    }

    public void refresh() {}
}
