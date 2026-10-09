package com.moulberry.flashback.gui;

import com.moulberry.flashback.gui.GuiEventLog.MenuState;
import com.moulberry.flashback.gui.GuiEventLog.Move;
import com.moulberry.flashback.gui.GuiEventLog.SlotState;

import java.util.ArrayList;
import java.util.List;

/**
 * What a recording says happened, given a container before and after.
 *
 * <p>This is the part of the recorder that can be wrong in a way nobody notices: a log that says
 * "something changed" is useless, and one that says the wrong thing is worse.
 */
public class GuiLogTest {

    private static int failures = 0;

    public static void main(String[] args) {
        moveAWholeStack();
        movePartOfAStack();
        pickUpAndPutDownOnTheCursor();
        craftConsumesIngredientsAndProducesAResult();
        swapTwoSlots();
        nothingChangedMeansNothingLogged();
        takingAnItemOutOfTheContainer();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All gui log tests passed");
    }

    /** Shift-clicking a stack from a chest into an inventory is one move, not two changes. */
    private static void moveAWholeStack() {
        MenuState before = menu(slot(0, "minecraft:oak_planks", 32));
        MenuState after = menu(slot(27, "minecraft:oak_planks", 32));
        List<Move> moves = GuiEventLog.diff(before, after);
        check("a transfer is one move", moves.size() == 1);
        check("it records where it came from and went to",
            moves.get(0).from() == 0 && moves.get(0).to() == 27);
        check("it records how many moved", moves.get(0).count() == 32);
    }

    /** Moving half a stack must not read as the stack disappearing and another appearing. */
    private static void movePartOfAStack() {
        MenuState before = menu(slot(0, "minecraft:oak_planks", 32));
        MenuState after = menu(slot(0, "minecraft:oak_planks", 20), slot(27, "minecraft:oak_planks", 12));
        List<Move> moves = GuiEventLog.diff(before, after);
        check("a partial transfer is still one move", moves.size() == 1);
        check("it records only what moved", moves.get(0).count() == 12);
    }

    /** Clicking a slot picks the stack up onto the cursor. */
    private static void pickUpAndPutDownOnTheCursor() {
        MenuState before = menu(slot(5, "minecraft:stone", 64));
        MenuState after = new MenuState(List.of(), "minecraft:stone", 64);
        List<Move> moves = GuiEventLog.diff(before, after);
        check("picking up is a move to the cursor",
            moves.size() == 1 && moves.get(0).from() == 5 && moves.get(0).to() == GuiEventLog.CURSOR);

        List<Move> dropped = GuiEventLog.diff(after, before);
        check("putting down is a move from the cursor",
            dropped.size() == 1 && dropped.get(0).from() == GuiEventLog.CURSOR && dropped.get(0).to() == 5);
    }

    /** A craft: ingredients leave, the result arrives, and neither came from a slot. */
    private static void craftConsumesIngredientsAndProducesAResult() {
        MenuState before = menu(
            slot(1, "minecraft:oak_planks", 4),
            slot(2, "minecraft:oak_planks", 4),
            slot(10, "minecraft:stick", 2));
        MenuState after = menu(
            slot(0, "minecraft:stick", 4),
            slot(10, "minecraft:stick", 2));
        List<Move> moves = GuiEventLog.diff(before, after);

        boolean consumed = moves.stream().anyMatch(move ->
            move.item().equals("minecraft:oak_planks") && move.to() == GuiEventLog.OUTSIDE);
        boolean produced = moves.stream().anyMatch(move ->
            move.item().equals("minecraft:stick") && move.from() == GuiEventLog.OUTSIDE
                && move.to() == 0 && move.count() == 4);
        check("the ingredients are recorded as consumed", consumed);
        check("the crafted result is recorded as produced", produced);
        check("the untouched slot is not mentioned",
            moves.stream().noneMatch(move -> move.item().equals("minecraft:stick") && move.to() == 10));
    }

    /** Swapping two different items is two moves, each in its own direction. */
    private static void swapTwoSlots() {
        MenuState before = menu(slot(0, "minecraft:stone", 1), slot(1, "minecraft:dirt", 1));
        MenuState after = menu(slot(0, "minecraft:dirt", 1), slot(1, "minecraft:stone", 1));
        List<Move> moves = GuiEventLog.diff(before, after);
        check("a swap is two moves", moves.size() == 2);
        check("each item is recorded going to the other slot",
            moves.stream().anyMatch(m -> m.item().equals("minecraft:stone") && m.from() == 0 && m.to() == 1)
                && moves.stream().anyMatch(m -> m.item().equals("minecraft:dirt") && m.from() == 1 && m.to() == 0));
    }

    private static void nothingChangedMeansNothingLogged() {
        MenuState state = menu(slot(0, "minecraft:stone", 5));
        check("an unchanged container logs nothing", GuiEventLog.diff(state, state).isEmpty());
    }

    /** Throwing a stack away, or an item being consumed, has nowhere to arrive. */
    private static void takingAnItemOutOfTheContainer() {
        MenuState before = menu(slot(3, "minecraft:bread", 3));
        MenuState after = menu();
        List<Move> moves = GuiEventLog.diff(before, after);
        check("an item that leaves the container goes outside",
            moves.size() == 1 && moves.get(0).to() == GuiEventLog.OUTSIDE && moves.get(0).count() == 3);
    }

    private static MenuState menu(SlotState... slots) {
        return new MenuState(new ArrayList<>(List.of(slots)), null, 0);
    }

    private static SlotState slot(int index, String item, int count) {
        return new SlotState(index, item, count, null);
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            System.out.println("ok:   " + what);
        } else {
            failures += 1;
            System.out.println("FAIL: " + what);
        }
    }
}
