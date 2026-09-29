package dev.anaka.util;

/** Pure (no game classes): whether a body can occupy a block, from what WorldUtil.passable reads of it. */
public final class Passable {
    private Passable() {}

    /**
     * Climbable, a wooden door (GotoTask opens it walking up), OPEN (any door, iron too, a trapdoor, a gate: its
     * panel's thin collision is no wall), or no collision. A closed iron door stays solid: nothing here opens it.
     */
    public static boolean by(boolean climbable, boolean woodenDoor, Boolean open, boolean noCollision) {
        return climbable || woodenDoor || Boolean.TRUE.equals(open) || noCollision;
    }
}
