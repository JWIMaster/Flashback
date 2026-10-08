package com.moulberry.flashback.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a record of every container the player opens and everything that moves inside it.
 *
 * <p>Two things are logged, because neither alone is a full picture: what the player <em>did</em> -
 * which slot they clicked, with which button, and what the cursor was holding - and what the
 * container <em>did</em> in response. The second is worked out by comparing the container against
 * itself, because the click that moved a stack out of a crafting grid and into an inventory is the
 * same click as far as the click itself is concerned.
 *
 * <p>The result is a JSON Lines file: one self-contained JSON object per line, so a long session can
 * be read, grepped or streamed without loading all of it. It lives in
 * {@code flashback/gui_logs/}, one file per session.
 */
public final class GuiRecorder {

    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault());

    private static BufferedWriter writer;
    private static Path file;
    private static long sequence;
    /** The screen the recorder believes is open, so a close can be logged with everything it held. */
    @Nullable
    private static Screen currentScreen;
    @Nullable
    private static GuiEventLog.MenuState lastMenuState;
    /**
     * Copies of the stacks as they were when the last snapshot was taken.
     *
     * <p>Containers mutate the same stack objects in place, so a reference to yesterday's stack would
     * read as today's; copies are what make "did this change" answerable. They also let an unchanged
     * stack reuse its description instead of being serialised again every tick.
     */
    private static final Map<Integer, ItemStack> lastStacks = new HashMap<>();
    private static final Map<Integer, GuiEventLog.SlotState> lastStates = new HashMap<>();
    private static int lastContainerId = -1;
    private static boolean broken;
    @Nullable
    private static java.lang.reflect.Field hoveredSlotField;
    private static boolean complained;

    private GuiRecorder() {
    }

    public static boolean isRecording() {
        return writer != null;
    }

    /** Where the current session is being written, or null when nothing is being recorded. */
    @Nullable
    public static Path currentFile() {
        return file;
    }

    /**
     * A container screen was shown, or closed.
     *
     * <p>Called for every screen, because the log is also the record of which interfaces were used;
     * only container screens carry items, and only those are snapshotted.
     */
    /** A screen was shown. Also fires when a screen is resized, which is not a new screen. */
    public static void screenOpened(Screen screen) {
        try {
            if (screen == currentScreen) {
                return;
            }
            screenChanged(screen);
        } catch (Throwable t) {
            giveUp("screen open", t);
        }
    }

    /** A screen was dismissed. */
    public static void screenClosed(Screen screen) {
        try {
            if (screen != currentScreen) {
                return;
            }
            screenChanged(null);
        } catch (Throwable t) {
            giveUp("screen close", t);
        }
    }

    private static void screenChanged(@Nullable Screen screen) {
        if (!enabled()) {
            return;
        }
        closeCurrent(screen == null ? "closed" : "replaced");
        if (screen == null) {
            return;
        }

        JsonObject event = event("screen_open");
        event.addProperty("screen", screen.getClass().getName());
        event.addProperty("title", screen.getTitle().getString());
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            AbstractContainerMenu menu = containerScreen.getMenu();
            event.addProperty("menu", menu.getClass().getName());
            event.addProperty("container_id", menu.containerId);
            event.addProperty("slots", menu.slots.size());
            GuiEventLog.MenuState state = snapshot(menu);
            event.add("contents", contents(state));
            lastMenuState = state;
            lastContainerId = menu.containerId;
        }
        write(event);
        currentScreen = screen;
    }

    /**
     * A player clicked in a container.
     *
     * <p>This is the intent: the slot, the button and the kind of click. What it did to the items is
     * logged separately, when the container shows the result.
     */
    /**
     * The player clicked in a container.
     *
     * <p>Only the screen is passed in; which slot was clicked is read from it, because a hook that
     * named the slot would have to name the shape of a method that differs between game builds. The
     * item movements themselves do not depend on this at all - they are worked out from the container
     * changing - so a version where this cannot be read still produces a complete record of what
     * moved, just without the click that caused it.
     */
    public static void slotClicked(Object screen) {
        try {
            if (!enabled() || screen == null) {
                return;
            }
            JsonObject event = event("click");
            Integer slot = hoveredSlot(screen);
            if (slot != null) {
                event.addProperty("slot", slot);
            }
            if (lastMenuState != null) {
                event.addProperty("carried", lastMenuState.carried());
                event.addProperty("carried_count", lastMenuState.carriedCount());
            }
            write(event);
        } catch (Throwable ignored) {
            // A click that cannot be described is not worth stopping the recording for; the movements
            // it caused are recorded by the container comparison regardless.
        }
    }

    /** The slot under the pointer, which is the one being clicked. */
    @Nullable
    private static Integer hoveredSlot(Object screen) {
        try {
            java.lang.reflect.Field field = hoveredSlotField;
            if (field == null) {
                // The screen keeps this as a protected field, so it is searched for up the hierarchy
                // and read reflectively: a name that changes costs the slot number, nothing more.
                for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
                    try {
                        field = type.getDeclaredField("hoveredSlot");
                        field.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                        // Keep looking in the superclass.
                    }
                }
                if (field == null) {
                    return null;
                }
                hoveredSlotField = field;
            }
            Object slot = field.get(screen);
            if (slot instanceof Slot gameSlot) {
                return gameSlot.index;
            }
        } catch (Throwable ignored) {
            // Not readable in this version; the movements are still recorded.
        }
        return null;
    }

    /**
     * Checks the open container for changes.
     *
     * <p>Called once a tick rather than at the moment of the click, because the authoritative result
     * of a click arrives from the server and may land a tick later; comparing here records what the
     * player actually ended up with.
     */
    public static void tick() {
        try {
            tickUnsafe();
        } catch (Throwable t) {
            giveUp("container tick", t);
        }
    }

    private static void tickUnsafe() {
        if (!enabled()) {
            return;
        }

        if (!(currentScreen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        GuiEventLog.MenuState state = snapshot(menu);
        if (lastMenuState == null || menu.containerId != lastContainerId) {
            lastMenuState = state;
            lastContainerId = menu.containerId;
            lastStacks.clear();
            lastStates.clear();
            return;
        }

        List<GuiEventLog.Move> moves = GuiEventLog.diff(lastMenuState, state);
        if (!moves.isEmpty()) {
            JsonObject event = event("items_moved");
            JsonArray array = new JsonArray();
            for (GuiEventLog.Move move : moves) {
                JsonObject json = new JsonObject();
                json.addProperty("from", move.from());
                json.addProperty("to", move.to());
                json.addProperty("item", move.item());
                json.addProperty("count", move.count());
                if (move.data() != null) {
                    json.addProperty("data", move.data());
                }
                array.add(json);
            }
            event.add("moves", array);
            write(event);
            lastMenuState = state;
        }
    }

    /** Finishes the current file. */
    public static void stop() {
        closeCurrent("stopped");
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                Flashback.LOGGER.warn("Could not close the GUI log", e);
            }
            writer = null;
            file = null;
        }
    }

    private static void closeCurrent(String reason) {
        if (currentScreen != null) {
            JsonObject event = event("screen_close");
            event.addProperty("screen", currentScreen.getClass().getName());
            event.addProperty("reason", reason);
            write(event);
        }
        currentScreen = null;
        lastMenuState = null;
        lastContainerId = -1;
        lastStacks.clear();
        lastStates.clear();
    }

    /**
     * Logs the first time something cannot be read.
     *
     * <p>Silence is the worst outcome: a recording that quietly does nothing looks the same as one
     * that is switched off.
     */
    private static void complainOnce(String what) {
        complainOnce(what, null);
    }

    private static void complainOnce(String what, @Nullable Throwable cause) {
        if (complained) {
            return;
        }
        complained = true;
        if (cause == null) {
            Flashback.LOGGER.warn("GUI recording cannot read {}", what);
        } else {
            Flashback.LOGGER.warn("GUI recording cannot read {}", what, cause);
        }
    }

    /**
     * Stops recording after something went wrong.
     *
     * <p>A recording is never worth a crash, and this runs on the client tick path where an
     * unexpected exception would take the game with it.
     */
    private static void giveUp(String what, Throwable t) {
        if (!broken) {
            broken = true;
            Flashback.LOGGER.warn("Stopped recording GUI events after a problem during {}", what, t);
        }
    }

    private static boolean enabled() {
        if (broken) {
            return false;
        }
        try {
            if (!Flashback.getConfig().internal.recordGuiEvents) {
                return false;
            }
        } catch (Throwable t) {
            // This is on the client tick path, so anything unexpected disables recording rather than
            // taking the game down over a log.
            broken = true;
            return false;
        }
        if (writer == null && !open()) {
            return false;
        }
        return true;
    }

    private static boolean open() {
        try {
            Path folder = gameFolder().resolve("flashback").resolve("gui_logs");
            Files.createDirectories(folder);
            file = folder.resolve("gui-" + FILE_STAMP.format(Instant.now()) + ".jsonl");
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            JsonObject event = event("session_start");
            write(event);
            Flashback.LOGGER.info("Recording GUI events to {}", file);
            return true;
        } catch (Exception e) {
            // Recording must never take the game down with it; give up quietly instead.
            broken = true;
            Flashback.LOGGER.warn("Could not start recording GUI events", e);
            return false;
        }
    }

    /** The game's own folder, under whichever name this version keeps it. */
    private static Path gameFolder() {
        Minecraft minecraft = Minecraft.getInstance();
        for (String name : new String[]{"gameDirectory", "gameDir"}) {
            try {
                java.lang.reflect.Field field = minecraft.getClass().getField(name);
                Object value = field.get(minecraft);
                if (value instanceof java.io.File directory) {
                    return directory.toPath();
                }
            } catch (Throwable ignored) {
                // Try the next name.
            }
        }
        // Better somewhere predictable than nowhere.
        complainOnce("the game folder (no field named 'gameDirectory')");
        return Path.of(".");
    }

    private static JsonObject event(String type) {
        JsonObject event = new JsonObject();
        event.addProperty("seq", sequence++);
        event.addProperty("time", Instant.now().toString());
        event.addProperty("type", type);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            event.addProperty("tick", minecraft.level.getGameTime());
        }
        if (minecraft.player != null) {
            event.addProperty("player", minecraft.player.getUUID().toString());
        }
        return event;
    }

    private static void write(JsonObject event) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(event.toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            broken = true;
            Flashback.LOGGER.warn("Could not write to the GUI log", e);
        }
    }

    /**
     * A container's contents, with every stack described in full so the log can be replayed.
     *
     * <p>Only stacks that have changed since the last snapshot are serialised; the rest reuse the
     * description already built for them. A container is snapshotted every tick, so describing all of
     * it each time would mean constantly re-encoding items that are simply sitting there.
     */
    private static GuiEventLog.MenuState snapshot(AbstractContainerMenu menu) {
        List<GuiEventLog.SlotState> slots = new ArrayList<>();
        Map<Integer, ItemStack> stacks = new HashMap<>();
        Map<Integer, GuiEventLog.SlotState> states = new HashMap<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            // Keyed by slot number, not list position: a container with empty slots is the normal
            // case, and positions would then describe the wrong ones.
            GuiEventLog.SlotState state = null;
            ItemStack previous = lastStacks.get(i);
            if (previous != null && ItemStack.matches(previous, stack)) {
                state = lastStates.get(i);
            }
            if (state == null) {
                state = new GuiEventLog.SlotState(i, itemId(stack), stack.getCount(), itemData(stack));
            }
            slots.add(state);
            stacks.put(i, stack.copy());
            states.put(i, state);
        }
        lastStacks.clear();
        lastStacks.putAll(stacks);
        lastStates.clear();
        lastStates.putAll(states);

        ItemStack carried = menu.getCarried();
        return new GuiEventLog.MenuState(slots,
            carried.isEmpty() ? null : itemId(carried),
            carried.isEmpty() ? 0 : carried.getCount());
    }

    private static JsonArray contents(GuiEventLog.MenuState state) {
        JsonArray array = new JsonArray();
        for (GuiEventLog.SlotState slot : state.slots()) {
            JsonObject json = new JsonObject();
            json.addProperty("slot", slot.slot());
            json.addProperty("item", slot.item());
            json.addProperty("count", slot.count());
            if (slot.data() != null) {
                json.addProperty("data", slot.data());
            }
            array.add(json);
        }
        if (state.carried() != null) {
            JsonObject json = new JsonObject();
            json.addProperty("slot", GuiEventLog.CURSOR);
            json.addProperty("item", state.carried());
            json.addProperty("count", state.carriedCount());
            array.add(json);
        }
        return array;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * Everything about a stack except how many there are.
     *
     * <p>The count is kept separate so that moving part of a stack is still recognisable as the same
     * item moving; the rest - enchantments, custom names, contents - is the game's own serialisation,
     * so nothing about the item is lost.
     */
    @Nullable
    private static String itemData(ItemStack stack) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return null;
            }
            RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
            JsonElement encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
            if (encoded == null || !encoded.isJsonObject()) {
                return null;
            }
            JsonObject object = encoded.getAsJsonObject();
            object.remove("count");
            // A plain item with nothing to say about it needs no data field at all.
            if (object.size() <= 1) {
                return null;
            }
            return object.toString();
        } catch (Exception e) {
            // An item that will not serialise must not stop the recording.
            return null;
        }
    }

    /** Convenience for the log reader: a slot number as a readable name. */
    public static String describePlace(int place) {
        if (place == GuiEventLog.CURSOR) {
            return "cursor";
        }
        if (place == GuiEventLog.OUTSIDE) {
            return "outside";
        }
        return "slot " + place;
    }

}
