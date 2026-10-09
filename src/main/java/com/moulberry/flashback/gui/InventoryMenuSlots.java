package com.moulberry.flashback.gui;

/**
 * Slot addresses of the vanilla player's inventory menu (container 0). They are not addresses
 * in {@code Inventory}: 0 is the crafting result, 1-4 the grid, 5-8 armour, 9-35 the main
 * inventory, 36-44 the hotbar, and 45 the offhand. Container screens have their own layout;
 * never run their menu slot numbers through this conversion.
 */
public final class InventoryMenuSlots {
    private InventoryMenuSlots() {
    }

    /** Inventory hotbar index to container-0 menu slot; never use the index as a menu slot. */
    public static int hotbarMenuSlot(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= 9) {
            throw new IllegalArgumentException("Not a hotbar index: " + inventoryIndex);
        }
        return 36 + inventoryIndex;
    }

    /** Inventory index to container-0 menu slot, or -1 for an invalid index. */
    public static int menuSlot(int inventoryIndex) {
        if (inventoryIndex >= 0 && inventoryIndex < 9) {
            return 36 + inventoryIndex;
        }
        if (inventoryIndex >= 9 && inventoryIndex <= 35) {
            return inventoryIndex;
        }
        if (inventoryIndex >= 36 && inventoryIndex <= 39) {
            return 44 - inventoryIndex;
        }
        return inventoryIndex == 40 ? 45 : -1;
    }

    /** Container-0 menu slot to inventory index, or -1 for crafting slots and invalid slots. */
    public static int inventoryIndex(int menuSlot) {
        if (menuSlot >= 36 && menuSlot <= 44) {
            return menuSlot - 36;
        }
        if (menuSlot >= 9 && menuSlot <= 35) {
            return menuSlot;
        }
        if (menuSlot >= 5 && menuSlot <= 8) {
            return 44 - menuSlot;
        }
        return menuSlot == 45 ? 40 : -1;
    }
}
