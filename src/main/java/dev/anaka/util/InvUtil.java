package dev.anaka.util;

import com.google.gson.JsonObject;
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
        // What an enchanting table or a brewing stand made is in the stack itself: the product, not a proxy.
        if (stack.hasEnchantments()) o.addProperty("enchanted", true);
        var potion = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (potion != null) {
            potion.potion().flatMap(entry -> entry.getKey())
                    .ifPresent(key -> o.addProperty("potion", key.getValue().toString()));
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
     * Holds the item Python named for a task — the one selection the jar makes: {@code null} keeps what is in hand,
     * "hand" frees the hand (an empty hotbar slot, else anything that takes no wear), an item id selects a stack of
     * it with wear left. Returns false when the named item is not carried.
     */
    public static boolean holdItem(MinecraftClient c, String itemId) {
        if (itemId == null) return true;
        ClientPlayerEntity p = c.player;
        if (itemId.equals("hand")) {
            PlayerInventory inv = p.getInventory();
            if (!p.getMainHandStack().isDamageable()) return true;
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
            return true;
        }
        if (id(p.getMainHandStack()).equals(itemId)) return true;
        return select(c, s -> id(s).equals(itemId) && (!s.isDamageable() || s.getMaxDamage() - s.getDamage() > 1));
    }

    public static boolean isFood(ItemStack s) {
        return s.get(DataComponentTypes.FOOD) != null;
    }
}
