package dev.anaka.task;

import dev.anaka.Agent;
import dev.anaka.util.InvUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Crafts by clicking slots exactly as a player does. The pattern is row-major, 4 entries (2x2, works in the
 * inventory) or 9 entries (3x3, needs an open crafting table). Null / empty entries are empty cells.
 * Each cell is filled with as many items as the remaining crafts need, then the result is shift-clicked once.
 */
public final class CraftTask extends Task {
    private enum Phase { CLEAR, FILL, WAIT_OUTPUT, TAKE, COUNT, DONE }

    private static final int CLICKS_PER_TICK = 16;

    private final List<String> pattern;
    private final int wanted;

    private Phase phase = Phase.CLEAR;
    private final Deque<int[]> clicks = new ArrayDeque<>();
    private int waitTicks;
    private int crafted;
    private int perCraft;
    private int batch = 1;
    private int syncWaits;
    private String output;
    private int outputBefore;

    public CraftTask(List<String> pattern, int count) {
        super("craft");
        this.pattern = pattern;
        this.wanted = Math.max(1, count);
        this.timeoutTicks = 20 * 60;
    }

    @Override
    public String describe() {
        return "crafting " + (output == null ? "" : output + " ") + crafted + "/" + wanted;
    }

    @Override
    protected void tick(MinecraftClient c, Agent a) {
        ClientPlayerEntity p = c.player;
        if (!(p.currentScreenHandler instanceof AbstractCraftingScreenHandler handler)) {
            fail("no crafting grid open (the inventory grid is 2x2; use a crafting table for 3x3)");
            return;
        }
        int size = (int) Math.round(Math.sqrt(pattern.size()));
        if (size * size != pattern.size() || size > handler.getWidth()) {
            fail(size > handler.getWidth() ? "a 3x3 pattern needs an open crafting table" : "pattern must have 4 or 9 entries");
            return;
        }

        if (!clicks.isEmpty()) {
            for (int i = 0; i < CLICKS_PER_TICK && !clicks.isEmpty(); i++) {
                int[] click = clicks.poll();
                c.interactionManager.clickSlot(handler.syncId, click[0], click[1], SlotActionType.values()[click[2]], p);
            }
            return;
        }

        switch (phase) {
            case CLEAR -> {
                if (!handler.getCursorStack().isEmpty()) {
                    Slot free = firstPlayerSlot(handler, null);
                    if (free == null) {
                        fail("inventory full");
                        return;
                    }
                    click(free.id, 0, SlotActionType.PICKUP);
                }
                clearGrid(handler);
                phase = Phase.FILL;
            }
            case FILL -> {
                if (!fill(handler, size)) return;
                waitTicks = 0;
                phase = Phase.WAIT_OUTPUT;
            }
            case WAIT_OUTPUT -> {
                if (handler.getOutputSlot().hasStack()) {
                    phase = Phase.TAKE;
                } else if (++waitTicks > 10) {
                    clearGrid(handler);
                    if (crafted > 0) {
                        finishWith("ran out of ingredients after " + crafted);
                    } else {
                        fail("no recipe matches this pattern");
                    }
                }
            }
            case TAKE -> {
                ItemStack out = handler.getOutputSlot().getStack();
                output = InvUtil.id(out);
                if (perCraft == 0) perCraft = out.getCount();
                outputBefore = InvUtil.count(p, output);
                click(handler.getOutputSlot().id, 0, SlotActionType.QUICK_MOVE);
                waitTicks = 0;
                phase = Phase.COUNT;
            }
            case COUNT -> {
                // Give the server a moment to report the crafted stack back.
                if (++waitTicks < 3) return;
                crafted += Math.max(0, InvUtil.count(p, output) - outputBefore);
                if (crafted >= wanted) {
                    clearGrid(handler);
                    phase = Phase.DONE;
                } else {
                    batch = Math.max(1, Math.min(64, (int) Math.ceil((wanted - crafted) / (double) perCraft)));
                    phase = Phase.CLEAR;
                }
            }
            case DONE -> finishWith("crafted " + crafted + " " + output);
        }
    }

    private void finishWith(String msg) {
        result.addProperty("item", output);
        result.addProperty("crafted", crafted);
        succeed(msg);
    }

    /** Queues clicks that put {@code batch} items in every pattern cell. Returns false if the task failed. */
    private boolean fill(AbstractCraftingScreenHandler handler, int size) {
        int width = handler.getWidth();
        // Work on a copy of stack counts so several cells can draw from the same source stack.
        java.util.Map<Integer, Integer> remaining = new java.util.HashMap<>();
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                String id = pattern.get(row * size + col);
                if (id == null || id.isEmpty() || id.equals("minecraft:air")) continue;
                Slot grid = handler.getInputSlots().get(row * width + col);
                int need = batch;
                while (need > 0) {
                    Slot source = null;
                    for (Slot s : handler.slots) {
                        if (!(s.inventory instanceof PlayerInventory) || s.getIndex() >= InvUtil.MAIN_SLOTS) continue;
                        int left = remaining.computeIfAbsent(s.id, k -> s.getStack().getCount());
                        if (left > 0 && InvUtil.id(s.getStack()).equals(id)) {
                            source = s;
                            break;
                        }
                    }
                    if (source == null) {
                        if (need == batch && batch == 1 && ++syncWaits <= 20) {
                            // Right after a screen opens (or a hotbar swap) the client copy of the inventory can lag
                            // the server by a few ticks: undo this pass and look again before calling it missing.
                            clicks.clear();
                            phase = Phase.CLEAR;
                            return false;
                        }
                        if (need == batch && batch == 1) {
                            // Say what the grid could draw from: makes an "impossible" failure diagnosable.
                            int seen = 0, free = 0;
                            for (Slot s : handler.slots) {
                                if (!(s.inventory instanceof PlayerInventory) || s.getIndex() >= InvUtil.MAIN_SLOTS) continue;
                                if (InvUtil.id(s.getStack()).equals(id)) seen += s.getStack().getCount();
                                if (!s.hasStack()) free++;
                            }
                            fail("missing ingredient " + id + " (held " + seen + ", free slots " + free
                                + ", cursor " + InvUtil.id(handler.getCursorStack()) + ", crafted so far " + crafted + ")");
                            return false;
                        }
                        batch = Math.max(1, batch - need);
                        break;
                    }
                    int left = remaining.get(source.id);
                    int take = Math.min(need, left);
                    click(source.id, 0, SlotActionType.PICKUP);
                    if (take == left) {
                        click(grid.id, 0, SlotActionType.PICKUP);
                    } else {
                        for (int i = 0; i < take; i++) click(grid.id, 1, SlotActionType.PICKUP);
                        click(source.id, 0, SlotActionType.PICKUP);
                    }
                    remaining.put(source.id, left - take);
                    need -= take;
                }
            }
        }
        return true;
    }

    private void click(int slot, int button, SlotActionType type) {
        clicks.add(new int[]{slot, button, type.ordinal()});
    }

    private void clearGrid(AbstractCraftingScreenHandler handler) {
        for (Slot s : handler.getInputSlots()) {
            if (s.hasStack()) click(s.id, 0, SlotActionType.QUICK_MOVE);
        }
    }

    /** First empty slot of the player's main inventory inside this handler. */
    private static Slot firstPlayerSlot(ScreenHandler handler, String ignored) {
        for (Slot s : handler.slots) {
            if (s.inventory instanceof PlayerInventory && s.getIndex() < InvUtil.MAIN_SLOTS && !s.hasStack()) return s;
        }
        return null;
    }
}
