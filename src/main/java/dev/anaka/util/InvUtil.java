package dev.anaka.util;

import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

public final class InvUtil {
    public static final int MAIN_SLOTS = 36;

    private InvUtil() {}

    public static String id(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString();
    }

    public static JsonObject stackJson(ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id(stack));
        o.addProperty("count", stack.getCount());
        if (stack.isDamageable()) {
            o.addProperty("damage", stack.getDamage());
            o.addProperty("maxDamage", stack.getMaxDamage());
        }
        return o;
    }

    public static int count(ClientPlayerEntity p, String itemId) {
        PlayerInventory inv = p.getInventory();
        int total = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && id(s).equals(itemId)) total += s.getCount();
        }
        return total;
    }

    /** Player screen handler slot index for a main inventory index (hotbar 0-8 → 36-44, main 9-35 → 9-35). */
    private static int handlerSlot(int invIndex) {
        return invIndex < 9 ? 36 + invIndex : invIndex;
    }

    /** Puts a matching stack in the main hand, swapping it into the hotbar if needed. Returns false if none found. */
    public static boolean select(MinecraftClient c, Predicate<ItemStack> match) {
        return selectIndex(c, find(c.player, match));
    }

    public static int find(ClientPlayerEntity p, Predicate<ItemStack> match) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && match.test(s)) return i;
        }
        return -1;
    }

    public static boolean selectIndex(MinecraftClient c, int index) {
        if (index < 0) return false;
        ClientPlayerEntity p = c.player;
        PlayerInventory inv = p.getInventory();
        if (index < 9) {
            inv.setSelectedSlot(index);
            return true;
        }
        // Swapping from the main inventory only works while no container is open.
        if (p.currentScreenHandler != p.playerScreenHandler) return false;
        c.interactionManager.clickSlot(p.playerScreenHandler.syncId, handlerSlot(index), inv.getSelectedSlot(),
            SlotActionType.SWAP, p);
        return true;
    }

    /**
     * Selects the fastest tool that still yields drops. A tool is only used when it beats the bare hand, so
     * durability isn't wasted on blocks any item breaks equally fast. Returns false when no item can harvest.
     */
    public static boolean selectBestTool(MinecraftClient c, BlockState state) {
        ClientPlayerEntity p = c.player;
        PlayerInventory inv = p.getInventory();
        double handScore = score(ItemStack.EMPTY, state);
        int best = -1;
        double bestScore = handScore + 0.5;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || (s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 1)) continue;
            double sc = score(s, state) + (i < 9 ? 0.01 : 0);
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        if (best >= 0) {
            selectIndex(c, best);
            return true;
        }
        if (state.isToolRequired()) return false;
        if (p.getMainHandStack().isDamageable()) {
            int spare = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty()) {
                    spare = i;
                    break;
                }
                if (spare < 0 && !s.isDamageable()) spare = i;
            }
            if (spare >= 0) inv.setSelectedSlot(spare);
        }
        return true;
    }

    private static final String[] PRECIOUS = {"minecraft:iron_", "minecraft:golden_", "minecraft:diamond_", "minecraft:netherite_"};

    private static double score(ItemStack s, BlockState state) {
        boolean drops = !state.isToolRequired() || (!s.isEmpty() && s.getItem().isCorrectForDrops(s, state));
        float speed = s.isEmpty() ? 1f : s.getItem().getMiningSpeed(s, state);
        double score = (drops ? 1000 : 0) + speed;
        // Save precious tools for blocks that need them: on soft blocks a stone tool is nearly as good.
        if (drops && speed > 1f && state.getBlock().getHardness() <= 3.0f) {
            String id = id(s);
            for (String prefix : PRECIOUS) {
                if (id.startsWith(prefix)) {
                    score -= 3.5;
                    break;
                }
            }
        }
        return score;
    }

    private static final String[] WEAPONS = {
        "minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:iron_sword", "minecraft:stone_sword",
        "minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe", "minecraft:golden_sword",
        "minecraft:wooden_sword", "minecraft:stone_axe"};

    /** Puts the strongest weapon in hand. Returns false if there is none. */
    public static boolean selectBestWeapon(MinecraftClient c) {
        for (String weapon : WEAPONS) {
            int index = find(c.player, s -> id(s).equals(weapon) && (!s.isDamageable() || s.getMaxDamage() - s.getDamage() > 1));
            if (index >= 0) return selectIndex(c, index);
        }
        return false;
    }

    public static boolean isFood(ItemStack s) {
        return s.get(DataComponentTypes.FOOD) != null;
    }
}
