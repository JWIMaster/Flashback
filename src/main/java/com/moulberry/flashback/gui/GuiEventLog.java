package com.moulberry.flashback.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns two snapshots of a container into "what actually moved".
 *
 * <p>A container click changes slot contents; the click itself only says which slot was used. Working
 * out that a stack of 32 planks left the crafting grid and arrived in the player's inventory - which
 * is what a recording is for - means comparing the container against itself. That is done here, free
 * of any game types, where it can be reasoned about and tested.
 */
public final class GuiEventLog {

    /**
     * One slot's contents.
     *
     * @param slot  the menu slot number, which is what a click refers to
     * @param item  the item's identity
     * @param count how many
     * @param data  everything else about the stack (components), not including the count, so that two
     *              stacks of the same item of different sizes still compare as the same thing
     */
    public record SlotState(int slot, String item, int count, String data) {
    }

    /** A container at a moment in time: its slots and the stack on the cursor. */
    public record MenuState(List<SlotState> slots, String carried, int carriedCount) {
        public static MenuState empty() {
            return new MenuState(List.of(), null, 0);
        }
    }

    /**
     * One thing that moved.
     *
     * @param from  slot it left, {@link #CURSOR} for the cursor, or {@link #OUTSIDE} if it appeared
     * @param to    slot it arrived in, {@link #CURSOR} for the cursor, or {@link #OUTSIDE} if it left
     * @param count how many moved
     */
    public record Move(int from, int to, String item, int count, String data) {
    }

    /** The stack being carried by the cursor rather than held in a slot. */
    public static final int CURSOR = -1;
    /** Somewhere that is not the container at all: dropped, crafted, or picked up. */
    public static final int OUTSIDE = -2;

    private GuiEventLog() {
    }

    /**
     * The moves implied by a container changing.
     *
     * <p>Places that lost something are matched against places that gained the same thing, so a
     * transfer reads as one move rather than as two unrelated events. Anything left over either left
     * the container or arrived from outside it - a craft, a pickup, a stack thrown on the floor.
     */
    public static List<Move> diff(MenuState before, MenuState after) {
        Map<Integer, SlotState> was = index(before);
        Map<Integer, SlotState> now = index(after);

        List<Change> losses = new ArrayList<>();
        List<Change> gains = new ArrayList<>();

        for (Map.Entry<Integer, SlotState> entry : was.entrySet()) {
            int place = entry.getKey();
            SlotState previous = entry.getValue();
            SlotState current = now.get(place);
            int remaining = current != null && same(previous, current) ? previous.count() - current.count() : previous.count();
            if (remaining > 0) {
                losses.add(new Change(place, previous, remaining));
            }
        }
        for (Map.Entry<Integer, SlotState> entry : now.entrySet()) {
            int place = entry.getKey();
            SlotState current = entry.getValue();
            SlotState previous = was.get(place);
            int arrived = previous != null && same(previous, current) ? current.count() - previous.count() : current.count();
            if (arrived > 0) {
                gains.add(new Change(place, current, arrived));
            }
        }

        List<Move> moves = new ArrayList<>();
        for (Change loss : losses) {
            for (Change gain : gains) {
                if (loss.remaining <= 0) {
                    break;
                }
                if (gain.remaining <= 0 || !same(loss.state, gain.state)) {
                    continue;
                }
                int moved = Math.min(loss.remaining, gain.remaining);
                moves.add(new Move(loss.place, gain.place, loss.state.item(), moved, loss.state.data()));
                loss.remaining -= moved;
                gain.remaining -= moved;
            }
            if (loss.remaining > 0) {
                // Destroyed or taken out of the container entirely.
                moves.add(new Move(loss.place, OUTSIDE, loss.state.item(), loss.remaining, loss.state.data()));
            }
        }
        for (Change gain : gains) {
            if (gain.remaining > 0) {
                // Created, or brought in from outside: a craft, a pickup, a shift-click home.
                moves.add(new Move(OUTSIDE, gain.place, gain.state.item(), gain.remaining, gain.state.data()));
            }
        }
        return moves;
    }

    /** A place and how much it lost or gained, so matching can consume it. */
    private static final class Change {
        final int place;
        final SlotState state;
        int remaining;

        Change(int place, SlotState state, int remaining) {
            this.place = place;
            this.state = state;
            this.remaining = remaining;
        }
    }

    private static Map<Integer, SlotState> index(MenuState state) {
        Map<Integer, SlotState> indexed = new LinkedHashMap<>();
        for (SlotState slot : state.slots()) {
            if (slot.count() > 0) {
                indexed.put(slot.slot(), slot);
            }
        }
        if (state.carried() != null && state.carriedCount() > 0) {
            indexed.put(CURSOR, new SlotState(CURSOR, state.carried(), state.carriedCount(), null));
        }
        return indexed;
    }

    /** The same kind of thing, regardless of how many: a partial transfer is still a transfer. */
    private static boolean same(SlotState a, SlotState b) {
        return a.item().equals(b.item()) && Objects.equals(a.data(), b.data());
    }

    /** Applies a move to a snapshot, for building the expected result in tests and diagnostics. */
    public static Map<Integer, SlotState> asMap(MenuState state) {
        return new HashMap<>(index(state));
    }

}
