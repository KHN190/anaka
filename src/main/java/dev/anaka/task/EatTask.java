package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.function.Predicate;

/** Holds the use key with food in hand until one item is consumed. */
public final class EatTask extends Task {
    private final String itemId;
    private String eating;
    private int countBefore;
    private int holdTicks;

    public EatTask(String itemId) {
        super("eat");
        this.itemId = itemId;
        this.timeoutTicks = 20 * 10;
    }

    @Override
    public String describe() {
        return "eating " + (eating == null ? "" : eating);
    }

    @Override
    protected void start(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        Predicate<ItemStack> match = itemId == null ? InvUtil::isFood : s -> InvUtil.id(s).equals(itemId);
        int index = InvUtil.find(p, match);
        if (index < 0) {
            fail(itemId == null ? "no food in inventory" : itemId + " is not in the inventory");
            return;
        }
        eating = InvUtil.id(p.getInventory().getStack(index));
        if (!InvUtil.selectIndex(c, index)) {
            fail("could not move " + eating + " into the hand (close open containers first)");
            return;
        }
        countBefore = InvUtil.count(p, eating);
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (InvUtil.count(p, eating) < countBefore) {
            result.addProperty("item", eating);
            result.addProperty("food", p.getHungerManager().getFoodLevel());
            succeed("ate " + eating);
            return;
        }
        // Look at the sky so the use key can't interact with a block in front of us.
        boolean aimed = Agent.rotateTowards(p, p.getYaw(), -90f, 45f);
        if (!aimed || !InvUtil.id(p.getMainHandStack()).equals(eating)) return;
        a.holdUse = true;
        if (++holdTicks > 20 * 4 && !p.isUsingItem()) {
            fail("could not eat " + eating + " (not hungry?)");
        }
    }
}
