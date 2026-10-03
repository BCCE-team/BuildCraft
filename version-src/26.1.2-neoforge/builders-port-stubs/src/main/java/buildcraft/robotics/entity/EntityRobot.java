package buildcraft.robotics.entity;

import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import com.mojang.authlib.GameProfile;

/** Compile-only owner bridge for builders' fake-player placement checks. */
public abstract class EntityRobot extends EntityRobotBase {
    public abstract GameProfile getOwnerProfile();
}
