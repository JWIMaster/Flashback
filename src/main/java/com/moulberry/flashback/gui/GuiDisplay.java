package com.moulberry.flashback.gui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.packet.FlashbackRemoteContainer;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorCamera;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.NautilusInventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.NautilusInventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shows the container the recorded player had open, using the game's own screen for it.
 *
 * <p>This is a picture of a container, not one. A replay is somebody else's session played back: the
 * items on screen belong to that session and cannot be taken, and the viewer's own menus have
 * nothing to do with them. So the screen built here is never installed on the screen stack and
 * never made the player's menu, and the items live in an inventory made for the purpose rather than
 * in the viewer's.
 *
 * <p>The game draws it. A chest is a chest and a furnace is a furnace, with the same background,
 * slots, labels and progress arrows a player would have seen, because it is the same screen class
 * drawing the same menu - only the contents come from the recording.
 *
 * <p>It is drawn through {@code extractRenderStateWithTooltipAndSubtitles}, which is the call the
 * game itself makes for the screen a player has open. That matters: it is the method that draws the
 * screen's background. Asking only for {@code extractRenderState} produces the contents and none of
 * the container around them, which is what a bare label and a few items floating over the world is.
 */
public final class GuiDisplay {

    /** The container the recording says is open, or -1. */
    private static int shownId = -1;
    private static String menuType = "";
    private static Component title = Component.empty();

    /** The menu the screen draws from. Its slots point at an inventory made for this overlay. */
    @Nullable
    private static AbstractContainerMenu menu;
    /** The game's screen for the container, built but never shown. */
    @Nullable
    private static Screen screen;
    private static int screenWidth = -1;
    private static int screenHeight = -1;

    /** The game's map of menu type to screen, once it has been found. */
    @Nullable
    private static Map<MenuType<?>, ?> screenMap;

    private GuiDisplay() {
    }

    /** Called on the client thread when the replay says a container changed. */
    public static void handle(FlashbackRemoteContainer container) {
        if (!GuiPlayback.shouldShow()) {
            clear();
            return;
        }

        switch (container.kind()) {
            case RESET -> clear();
            case CLOSE -> {
                if (container.containerId() == shownId) {
                    clear();
                }
            }
            case OPEN -> open(container);
            case CONTENT -> {
                if (container.containerId() == shownId) {
                    applyItems(container.items(), container.carried());
                }
            }
            case SLOT -> {
                if (container.containerId() == shownId) {
                    applySlot(container.slot(), container.item());
                }
            }
            case CARRIED -> {
                if (container.containerId() == shownId && menu != null) {
                    menu.setCarried(container.carried().copy());
                }
            }
        }
    }

    /** Forget whatever is being drawn. Safe to call at any time. */
    public static void clear() {
        shownId = -1;
        menuType = "";
        title = Component.empty();
        menu = null;
        screen = null;
        screenWidth = -1;
        screenHeight = -1;
    }

    private static void open(FlashbackRemoteContainer container) {
        clear();
        shownId = container.containerId();
        menuType = container.menuType() == null ? "" : container.menuType();
        title = container.title() == null ? Component.empty() : container.title();

        if (!menuType.isEmpty()) {
            build();
        }
    }

    /**
     * Builds the menu and the screen the recording's container is drawn with.
     *
     * <p>The menu is built over an inventory made here rather than the viewer's, so its slots point
     * at nothing that matters and the recorded items can be put into them without touching anything
     * of the viewer's.
     */
    private static void build() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }

        try {
            Inventory detached = new Inventory(minecraft.player, new EntityEquipment());

            if (menuType.startsWith(FlashbackRemoteContainer.MOUNT_TYPE_PREFIX)) {
                // A mount has no menu type either; the game builds its menu from the entity, and this
                // is the same menu the game would have built.
                buildMount(minecraft, detached);
                return;
            }

            if (FlashbackRemoteContainer.PLAYER_INVENTORY_TYPE.equals(menuType)) {
                // The player's own inventory has no menu type: the client makes it without being
                // told to, so the recording names it and this makes the same menu.
                InventoryMenu detachedMenu = new InventoryMenu(detached, true, minecraft.player);
                menu = detachedMenu;
                screen = inventoryScreen(detachedMenu, minecraft);
                return;
            }

            Identifier id = Identifier.tryParse(menuType);
            if (id == null) {
                return;
            }
            MenuType<?> type = BuiltInRegistries.MENU.getValue(id);
            if (type == null) {
                return;
            }
            AbstractContainerMenu built = createMenu(type, shownId, detached);
            if (built == null) {
                return;
            }
            menu = built;
            screen = containerScreen(type, built, detached, title);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not build the screen for the recorded container {}", menuType, t);
            menu = null;
            screen = null;
        }
    }

    /**
     * The game's screen for a mount's inventory.
     *
     * <p>There is no menu type to look up: the client builds a horse's menu, or a nautilus's, out of
     * the entity the packet names, so this does the same with the entity the recording named. The
     * mount's own slots are backed by a container made here, so the recorded items can be put into
     * them without touching the mount the viewer is watching.
     */
    private static void buildMount(Minecraft minecraft, Inventory detached) {
        int entityId = FlashbackRemoteContainer.mountEntityId(menuType);
        int columns = FlashbackRemoteContainer.mountColumns(menuType);
        if (entityId < 0 || columns <= 0 || minecraft.level == null) {
            return;
        }

        Entity mount = minecraft.level.getEntity(entityId);
        SimpleContainer container =
            new SimpleContainer(AbstractMountInventoryMenu.getInventorySize(columns));

        if (mount instanceof AbstractHorse horse) {
            HorseInventoryMenu built = new HorseInventoryMenu(shownId, detached, container, horse, columns);
            menu = built;
            screen = new HorseInventoryScreen(built, detached, horse, columns);
        } else if (mount instanceof AbstractNautilus nautilus) {
            NautilusInventoryMenu built = new NautilusInventoryMenu(shownId, detached, container, nautilus, columns);
            menu = built;
            screen = new NautilusInventoryScreen(built, detached, nautilus, columns);
        }
    }

    private static void applyItems(List<ItemStack> contents, ItemStack carriedItem) {
        if (menu == null) {
            return;
        }
        // The recording is the authority for the contents, so these are copies: the held stacks are
        // mutated in place by the game and a reference would follow the game rather than the record.
        for (int i = 0; i < menu.slots.size(); i++) {
            ItemStack item = i < contents.size() ? contents.get(i) : ItemStack.EMPTY;
            menu.getSlot(i).set(item.copy());
        }
        menu.setCarried(carriedItem == null ? ItemStack.EMPTY : carriedItem.copy());
    }

    private static void applySlot(int slot, ItemStack item) {
        boolean wrote = menu != null && slot >= 0 && slot < menu.slots.size();
        if (wrote) {
            menu.getSlot(slot).set(item.copy());
        }
        if (slot < 12) {
        }
    }

    /**
     * The game's screen for a container.
     *
     * <p>The game keeps its container screens in a map it does not expose, and that map is the only
     * thing that knows a furnace is drawn differently from a chest. The field is found by its type
     * rather than by its name: names are not the same in a running game as they are when compiling
     * against it, so a name would be a lookup that works in development and fails in the game.
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Screen containerScreen(MenuType<?> type, AbstractContainerMenu menu, Inventory inventory,
                                          Component title) {
        try {
            Object constructor = screens().get(type);
            if (constructor == null) {
                return null;
            }
            return ((MenuScreens.ScreenConstructor) constructor).create(menu, inventory, title);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not build the screen for {}", menuType, t);
            return null;
        }
    }

    /** The game's map of menu type to screen, or an empty map when it cannot be reached. */
    @SuppressWarnings("unchecked")
    private static Map<MenuType<?>, ?> screens() {
        if (screenMap != null) {
            return screenMap;
        }
        for (Field field : MenuScreens.class.getDeclaredFields()) {
            if (!Map.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (field.get(null) instanceof Map<?, ?> map) {
                    screenMap = (Map<MenuType<?>, ?>) map;
                    return screenMap;
                }
            } catch (Throwable ignored) {
                // There is only ever one; try the next if this was not it.
            }
        }
        Flashback.LOGGER.warn("Could not reach the game's container screens; recorded containers will not be shown");
        screenMap = Map.of();
        return screenMap;
    }

    /**
     * The game's inventory screen, showing the recording rather than whoever is watching.
     *
     * <p>The game builds this one over the player it is given, and there is no way to hand it another
     * inventory - the inventory it reads is a final field of the player. So the slots are replaced
     * instead. They sit in the same places, so the screen draws them where it always would, but what
     * it reads is the overlay's own inventory, which holds the recording.
     */
    @Nullable
    private static Screen inventoryScreen(InventoryMenu detachedMenu, Minecraft minecraft) {
        try {
            InventoryScreen built = new InventoryScreen(minecraft.player);
            List<Slot> recorded = new ArrayList<>(detachedMenu.slots);
            built.getMenu().slots.clear();
            built.getMenu().slots.addAll(recorded);
            return built;
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not build the recorded inventory screen", t);
            return null;
        }
    }

    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AbstractContainerMenu createMenu(MenuType<?> type, int containerId, Inventory inventory) {
        try {
            return ((MenuType) type).create(containerId, inventory);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not build the menu for {}", menuType, t);
            return null;
        }
    }

    /**
     * Whether the camera is looking through a player's eyes, which is the only place a replay's
     * container belongs.
     *
     * <p>This is the same thing {@code Flashback.getSpectatingPlayer} answers, asked without its
     * checks for a camera that has briefly been left pointing at an entity the replay has already
     * replaced: those are for deciding whether to read a player's inventory, and here they would
     * blink the window off for a frame whenever an entity was swapped.
     */
    /**
     * The player the replay is of, as this client currently has them.
     *
     * <p>The interface is about them, so the readouts beside it are too - the hotbar, the health and
     * the held item. The camera player is the answer while spectating and the wrong answer the rest
     * of the time, because then it is the viewer, who owns nothing in a replay.
     */
    @Nullable
    public static Player recordedPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        ReplayServer replayServer = Flashback.getReplayServer();
        if (minecraft == null || minecraft.level == null || replayServer == null) {
            return null;
        }
        return minecraft.level.getEntity(replayServer.getLocalPlayerId()) instanceof Player player ? player : null;
    }

    private static boolean showingThroughAPlayer(Minecraft minecraft) {
        if (shotIsSpectate()) {
            return true;
        }
        Entity camera = minecraft.getCameraEntity();
        return camera instanceof AbstractClientPlayer player && player != minecraft.player;
    }

    /**
     * Whether the camera cut being played is a spectate camera.
     *
     * <p>The camera entity is usually the answer to this, and is what is checked above when it is
     * available. It is not available while exporting: the exporter snaps the viewer's own player to
     * the shot and renders from it, so the entity is never a player there and a spectate shot would
     * lose its interface. The cut's own camera says the same thing in both cases, because it is what
     * the exporter itself is following.
     */
    private static boolean shotIsSpectate() {
        EditorState editorState = EditorStateManager.getCurrent();
        ReplayServer replayServer = Flashback.getReplayServer();
        if (editorState == null || replayServer == null) {
            return false;
        }
        EditorScene scene = editorState.currentSceneOrNull();
        if (scene == null) {
            return false;
        }
        EditorCamera camera = scene.resolveCameraAt(replayServer.getReplayTick());
        return camera != null && camera.kind == EditorCamera.Kind.SPECTATE;
    }

    /** Draws the container, if there is one. Called from the HUD render pass. */
    public static void extract(GuiGraphicsExtractor graphics) {
        Screen drawing = screen;
        if (drawing == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        if (!Flashback.isInReplay() || !GuiPlayback.shouldShow()) {
            clear();
            return;
        }
        if (minecraft.gui != null && minecraft.gui.hud != null && minecraft.gui.hud.isHidden()) {
            // F1 hides the interface; a container the recording had open is part of it.
            return;
        }
        if (!showingThroughAPlayer(minecraft)) {
            // The container belongs to the player being recorded, so it is only part of the picture
            // when the camera is that player's eyes. On a free or orbit camera it would be a window
            // floating over a shot nobody is looking through.
            return;
        }

        try {
            // The screen lays itself out around the centre of the interface, so it has to be told how
            // big the interface is. It is told again whenever that changes.
            int width = minecraft.getWindow().getGuiScaledWidth();
            int height = minecraft.getWindow().getGuiScaledHeight();
            if (width != screenWidth || height != screenHeight) {
                screenWidth = width;
                screenHeight = height;
                drawing.init(width, height);
            }
            // Far outside the interface, so nothing counts as hovered and no tooltip is drawn: this
            // is a picture, and there is no pointer in it.
            drawing.extractRenderStateWithTooltipAndSubtitles(graphics, -1, -1, 0.0f);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Could not draw the recorded container {}", menuType, t);
            clear();
        }
    }

}
