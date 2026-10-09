import com.moulberry.flashback.gui.InventoryMenuSlots;

import java.util.Arrays;

/** Headless slot-address/state regression: run with run-replay-inventory.sh. */
public final class ReplayInventorySlotsTest {
    private static final class ReplayState {
        private final String[] playerInventory = new String[41];
        private final String[] screen = new String[46];
        private boolean open;

        void open() { open = true; }
        void close() { open = false; Arrays.fill(screen, null); }
        void menuSlot(int slot, String item) {
            int index = InventoryMenuSlots.inventoryIndex(slot);
            if (index >= 0) playerInventory[index] = item;
            if (open) screen[slot] = item;
        }
        void content(String[] slots) {
            for (int i = 0; i < slots.length; i++) menuSlot(i, slots[i]);
        }
        void hotbar(int index, String item) {
            menuSlot(InventoryMenuSlots.hotbarMenuSlot(index), item);
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        for (int i = 0; i < 9; i++) {
            equal(i, InventoryMenuSlots.inventoryIndex(InventoryMenuSlots.hotbarMenuSlot(i)));
        }
        for (int i = 0; i <= 40; i++) {
            equal(i, InventoryMenuSlots.inventoryIndex(InventoryMenuSlots.menuSlot(i)));
        }
        equal(-1, InventoryMenuSlots.menuSlot(41));
        equal(-1, InventoryMenuSlots.menuSlot(-1));
        for (int i = 0; i <= 4; i++) equal(-1, InventoryMenuSlots.inventoryIndex(i));
        for (int i = 9; i <= 35; i++) equal(i, InventoryMenuSlots.inventoryIndex(i));
        for (int i = 5; i <= 8; i++) equal(44 - i, InventoryMenuSlots.inventoryIndex(i));
        equal(40, InventoryMenuSlots.inventoryIndex(45));
        equal(-1, InventoryMenuSlots.inventoryIndex(46));
        equal(-1, InventoryMenuSlots.inventoryIndex(-1));
        for (int index : new int[]{-1, 9}) {
            try {
                InventoryMenuSlots.hotbarMenuSlot(index);
                throw new AssertionError("accepted invalid hotbar index " + index);
            } catch (IllegalArgumentException expected) { /* validated */ }
        }

        ReplayState state = new ReplayState();
        state.open();
        state.menuSlot(0, "crafting table");
        state.menuSlot(1, "oak planks");
        state.menuSlot(7, "leggings");
        for (int i = 0; i < 9; i++) state.hotbar(i, "item " + i);
        equal("crafting table", state.screen[0]);
        equal("oak planks", state.screen[1]);
        equal("leggings", state.screen[7]);
        equal("leggings", state.playerInventory[37]);
        for (int i = 0; i < 9; i++) {
            equal("item " + i, state.screen[36 + i]);
            equal("item " + i, state.playerInventory[i]);
        }
        state.menuSlot(0, "stick x4");
        equal("stick x4", state.screen[0]);
        equal("item 0", state.playerInventory[0]);

        // Full menu content is enough to restore both overlay and HUD after a seek.
        String[] snapshot = state.screen.clone();
        state.close();
        state.hotbar(0, "later item");
        equal("later item", state.playerInventory[0]);
        state.open();
        state.content(snapshot);
        equal("item 0", state.playerInventory[0]);
        equal("stick x4", state.screen[0]);
        equal("leggings", state.screen[7]);
        state.close();
        state.hotbar(0, "no screen");
        equal(null, state.screen[36]);
        equal("no screen", state.playerInventory[0]);
        System.out.println("Replay inventory slot state checks passed");
    }
}
