/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.mj;

import buildcraft.lib.internal.mj.IMjRedstoneReceiver;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.internal.mj.MjBattery;

public class MjRedstoneBatteryReceiver extends MjBatteryReceiver implements IMjRedstoneReceiver {
    public MjRedstoneBatteryReceiver(MjBattery battery) {
        super(battery);
    }
}
