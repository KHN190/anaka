package dev.anaka.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-tick reflex (like WaterClutch and LavaGuard): eat while walking. When the running task is only moving (a walk,
 * a travel between its digs, an approach on foot) and the hunger bar is below the threshold Python set (POST
 * /autoeat), take the best food carried, hold "use" until it is eaten, and put the hand back. Never while mining,
 * attacking or placing: those tasks need the hand. The policy (threshold, foods best first) is Python's; the timing
 * is here, because eating takes 32 ticks and a walk leg is the free time to do it in.
 */
public final class AutoEat {
    private static volatile int below = 0;                 // 0: off
    private static volatile List<String> foods = List.of();
    private static int eating = -1;                        // ticks spent eating, -1 when not
    private static int backTo = -1;                        // the slot to return to
    private static int hungerAt;

    private AutoEat() {}

    public static void configure(int eatBelow, List<String> best) {
        below = Math.max(0, eatBelow);
        foods = new ArrayList<>(best);
    }

    /** Pure: eat now? Walking, hungry below the threshold, and something to eat. */
    public static boolean shouldEat(boolean walking, int hunger, int threshold, boolean hasFood) {
        return walking && threshold > 0 && hunger < threshold && hasFood;
    }

    /** Returns true while eating (the caller keeps "use" held). */
    public static boolean tick(MinecraftClient c, ClientPlayerEntity p, boolean walking) {
        int hunger = p.getHungerManager().getFoodLevel();
        if (eating >= 0) {
            boolean done = hunger > hungerAt || ++eating > 60 || !walking;
            if (!done) return true;
            if (p.isUsingItem() && c.interactionManager != null) c.interactionManager.stopUsingItem(p);
            if (backTo >= 0) InvUtil.selectIndex(c, backTo);
            eating = backTo = -1;
            return false;
        }
        String food = null;
        for (String id : foods) {
            if (InvUtil.count(p, id) > 0) {
                food = id;
                break;
            }
        }
        if (!shouldEat(walking, hunger, below, food != null)) return false;
        final String want = food;
        int before = p.getInventory().getSelectedSlot();
        if (!InvUtil.select(c, s -> InvUtil.id(s).equals(want))) return false;
        backTo = before;
        hungerAt = hunger;
        eating = 0;
        return true;
    }
}
