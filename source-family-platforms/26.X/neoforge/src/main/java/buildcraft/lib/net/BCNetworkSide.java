/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

/** Loader-neutral logical side used by BuildCraft's internal network protocol. */
public enum BCNetworkSide {
    CLIENT,
    SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isServer() {
        return this == SERVER;
    }
}
