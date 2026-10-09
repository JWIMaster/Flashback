package com.moulberry.flashback.gui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayGamePacketHandler;
import com.moulberry.flashback.record.IgnoredPacketSet;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The rules a replayed container has to obey, checked against the code rather than by watching it.
 *
 * <p>Every one of these is a fault that has already happened once, and none of them is visible from
 * the inside of the game: packets that were never written, so the feature silently did nothing;
 * hooks on methods that a container screen overrides, so they never ran for the screens they were
 * for; a screen that took the mouse; an overlay whose background was never asked for; and a menu
 * whose slots were the viewer's own, so that watching a replay rearranged the viewer's inventory.
 */
public class ContainerGuiCheck {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "src/main/java");
        if (!Files.isDirectory(root)) {
            System.out.println("FAIL: source root not found: " + root);
            System.exit(1);
        }

        theRecordingKeepsContainersOnlyWhenAsked();
        everyContainerPacketHasItsOwnHandler();
        noContainerHandlerThrowsOrReachesTheClient(root);
        theOverlayShowsTheGameScreenWithoutTakingOver(root);
        theHooksAreOnTheMethodsThatActuallyRun(root);
        theRecordingCarriesWhatTheServerNeverRepeats(root);
        theHotbarPayloadCarriesAnInventoryIndex(root);
        theInterfaceFollowsTheShotAsWellAsTheCamera(root);
        aSeekRestoresWhatTheClientCannotRebuild(root);
        theRecordingWritesTheClosesTheServerDoesNot(root);

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All container display checks passed");
    }

    /**
     * A container packet in both the "worth recording" set and the "never recorded" set is dropped
     * by the second, which is a way for the whole feature to do nothing without failing anything.
     */
    private static void theRecordingKeepsContainersOnlyWhenAsked() {
        Set<Class<?>> containers = IgnoredPacketSet.containerPackets();
        Set<Class<?>> always = IgnoredPacketSet.alwaysIgnored();

        check("there are container packets worth recording", !containers.isEmpty());
        for (Class<?> type : containers) {
            check(type.getSimpleName() + " is not in the never-recorded set", !always.contains(type));
        }

        Flashback.getConfig().internal.showGuisInReplays = true;
        for (Class<?> type : containers) {
            check(type.getSimpleName() + " is recorded when containers are shown",
                !IgnoredPacketSet.isIgnoredType(type));
        }

        Flashback.getConfig().internal.showGuisInReplays = false;
        for (Class<?> type : containers) {
            check(type.getSimpleName() + " is dropped when they are not",
                IgnoredPacketSet.isIgnoredType(type));
        }

        // Put it back, so that a later suite does not inherit a flag it did not set.
        Flashback.getConfig().internal.showGuisInReplays = true;
    }

    /**
     * A packet with no handler of its own falls through to one that reports the packet as
     * unsupported, and that exception is not caught: the replay's tick loop dies with it.
     */
    private static void everyContainerPacketHasItsOwnHandler() {
        for (Class<?> type : IgnoredPacketSet.containerPackets()) {
            Method handler = handlerFor(type);
            check(type.getSimpleName() + " has a handler in the replay's packet handler", handler != null);
            if (handler != null) {
                check(type.getSimpleName() + "'s handler is public, so the packet can reach it",
                    Modifier.isPublic(handler.getModifiers()));
            }
        }
    }

    /** Reads the handlers and refuses the two things that broke playback before. */
    private static void noContainerHandlerThrowsOrReachesTheClient(Path root) throws Exception {
        String source = Files.readString(
            root.resolve("com/moulberry/flashback/playback/ReplayGamePacketHandler.java"));

        for (Class<?> type : IgnoredPacketSet.containerPackets()) {
            Method handler = handlerFor(type);
            if (handler == null) {
                continue;
            }
            String body = methodBody(source, handler.getName());
            check("the handler for " + type.getSimpleName() + " was found in the source", body != null);
            if (body == null) {
                continue;
            }
            check("the handler for " + type.getSimpleName() + " does not report the packet as unsupported",
                !body.contains("UnsupportedPacketException"));
            check("the handler for " + type.getSimpleName() + " does not build anything on the client",
                !body.contains("Minecraft.getInstance"));
        }
    }

    /**
     * The game's own screen draws the container, which is the whole point, and it is asked for it in
     * the one way that includes the container's background.
     *
     * <p>A screen's background is not part of {@code extractRenderState}: it is drawn by
     * {@code extractRenderStateWithTooltipAndSubtitles}, which is the call the game makes for the
     * screen a player has open. Asking for the first alone produces the labels and the items with no
     * container around them, which is exactly what a bare "Large Chest" floating in the sky is.
     */
    private static void theOverlayShowsTheGameScreenWithoutTakingOver(Path root) throws Exception {
        String display = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiDisplay.java"));
        check("the overlay draws the game's own screen", display.contains("ScreenConstructor"));
        check("the overlay asks for the screen's background as well as its contents",
            display.contains("extractRenderStateWithTooltipAndSubtitles")
                && !display.contains(".extractRenderState(graphics"));

        for (String forbidden : List.of("setScreenAndShow", ".setScreen(", "MenuScreens.create",
            "initializeContents", ".removed(", "player.containerMenu")) {
            check("drawing a container does not use " + forbidden, !display.contains(forbidden));
        }
    }

    /**
     * A container screen overrides {@code init} and {@code removed}, so an injection into the ones
     * on {@code Screen} never runs for a container screen at all. That is a hook that compiles, is
     * present, and is blind to exactly the screens the feature is about.
     */
    private static void theHooksAreOnTheMethodsThatActuallyRun(Path root) throws Exception {
        String container = Files.readString(
            root.resolve("com/moulberry/flashback/mixin/gui/MixinAbstractContainerScreen.java"));
        check("a container's own close is hooked where it runs",
            container.contains("method = \"removed()V\"") && container.contains("GuiRecording.screenClosed"));

        String screen = Files.readString(
            root.resolve("com/moulberry/flashback/mixin/gui/MixinScreenGuiRecorder.java"));
        check("a screen being shown is hooked on added, not on init",
            screen.contains("added()V") && screen.contains("GuiRecording.screenShown"));
    }

    /**
     * Closing a container is not in a recording unless the recording writes it.
     *
     * <p>{@code ServerGamePacketListenerImpl.handleContainerClose} calls
     * {@code ServerPlayer.doCloseContainer}, which sends nothing - only the server closing a
     * container for its own reasons goes through {@code closeContainer} and sends a packet. So a
     * recording written without this holds the opening of every chest and the closing of none.
     */
    private static void theRecordingWritesTheClosesTheServerDoesNot(Path root) throws Exception {
        String recording = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiRecording.java"));
        String closed = methodBody(recording, "screenClosed");
        check("the recording handles a screen closing", closed != null);
        if (closed != null) {
            check("closing a container is written into the recording",
                closed.contains("FlashbackRemoteContainer.close"));
            check("every container screen is closed, not only the player's own inventory",
                closed.contains("AbstractContainerScreen") && !closed.contains("InventoryMenu"));
        }
    }

    /**
     * The server never repeats what the client predicted, and it never says what an open container
     * is between its opening packet and the next one, so both have to be written while recording.
     *
     * <p>A click sends the server hashes of every slot the client now believes it has, and the server
     * records those as already known - so a crafting grid, filled entirely by prediction, is never
     * sent and a replay had the crafted result sitting beside an empty grid. And a seek starts from a
     * snapshot, so a container opened before it is never opened again: the recording has to say what
     * was open at the snapshot or seeking into a container's life shows nothing.
     */
    private static void theRecordingCarriesWhatTheServerNeverRepeats(Path root) throws Exception {
        String recording = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiRecording.java"));
        check("the recording mirrors the container the client has",
            recording.contains("ItemStack.matches") && recording.contains("FlashbackRemoteContainer.slot"));
        check("only the crafting grid is mirrored, so server-owned slots are not second-guessed",
            recording.contains("instanceof CraftingContainer"));
        check("the mirror is told what the server said",
            recording.contains("ClientboundContainerSetSlotPacket")
                && recording.contains("ClientboundContainerSetContentPacket"));
        check("the recording can name an open container without its opening packet",
            recording.contains("ClientboundOpenScreenPacket") && recording.contains("restate"));

        String containerScreen = Files.readString(
            root.resolve("com/moulberry/flashback/mixin/gui/MixinAbstractContainerScreen.java"));
        check("the mirror is driven once a tick",
            containerScreen.contains("tick()V") && containerScreen.contains("GuiRecording.tick"));

        String recorder = Files.readString(root.resolve("com/moulberry/flashback/record/Recorder.java"));
        check("the recorder feeds the mirror every packet",
            recorder.contains("GuiRecording.observePacket"));
        check("a snapshot says what was open",
            recorder.contains("GuiRecording.onSnapshot"));
    }

    /**
     * The payload that updates the recorded player's inventory carries an inventory index.
     *
     * <p>A menu puts the hotbar at 36 and the armour at 5; the inventory puts the hotbar at 0 and the
     * armour at 36. Sending the menu's number for a hotbar change wrote it into the armour, offhand,
     * body and saddle instead, which is what made the first-person hotbar look wrong.
     */
    private static void theHotbarPayloadCarriesAnInventoryIndex(Path root) throws Exception {
        String handler = Files.readString(
            root.resolve("com/moulberry/flashback/playback/ReplayGamePacketHandler.java"));
        String body = methodBody(handler, "handleContainerSetSlot");
        check("the container slot handler was found", body != null);
        if (body == null) {
            return;
        }
        check("it asks the slot for its own container's index", body.contains("getContainerSlot()"));
        check("the recorded player's inventory is not addressed with a menu index",
            !body.contains("FlashbackRemoteSetSlot(player.getId(), slot,"));
    }

    /**
     * Whether to draw follows the cut as well as the camera entity, because the exporter renders
     * from the viewer's own player: the entity is never a player there, so a spectate shot would
     * lose its interface.
     */
    private static void theInterfaceFollowsTheShotAsWellAsTheCamera(Path root) throws Exception {
        String display = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiDisplay.java"));
        check("the interface asks what the cut's camera is",
            display.contains("resolveCameraAt") && display.contains("Kind.SPECTATE"));
        check("and still asks what the camera entity is",
            display.contains("getCameraEntity"));
    }

    /**
     * A seek leaves client state that only the packets it skipped would have corrected.
     *
     * <p>A crack overlay belongs to the tick it was sent for and the packet that stops it is not
     * replayed, so it has to be cleared. And the first-person state - the hotbar of the player being
     * watched - has to be re-sent for more than one tick, because the jump can reach the client
     * before the player it describes does, and a payload for an entity that is not there yet is
     * dropped: send it once and the hotbar stays empty until something in it next changes.
     */
    private static void aSeekRestoresWhatTheClientCannotRebuild(Path root) throws Exception {
        String server = Files.readString(root.resolve("com/moulberry/flashback/playback/ReplayServer.java"));
        int jump = server.indexOf("this.jumpToTick = -1;");
        check("the seek is where it was expected", jump >= 0);
        if (jump < 0) {
            return;
        }
        String after = server.substring(jump, Math.min(server.length(), jump + 1600));
        check("a seek clears the crack overlay", after.contains("FlashbackClearBlockDestruction"));
        check("a seek re-sends the first-person state over several ticks",
            after.contains("resendFirstPersonTicks"));

        String player = Files.readString(root.resolve("com/moulberry/flashback/playback/ReplayPlayer.java"));
        check("the resend window is state on the viewer", player.contains("resendFirstPersonTicks"));
    }

    private static Method handlerFor(Class<?> packetType) {
        for (Method method : ReplayGamePacketHandler.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == packetType) {
                return method;
            }
        }
        return null;
    }

    /** The braces of a method in a source file, found by name. */
    private static String methodBody(String source, String name) {
        int from = 0;
        while (true) {
            int at = source.indexOf(name + "(", from);
            if (at < 0) {
                return null;
            }
            from = at + name.length();
            if (at > 0 && Character.isJavaIdentifierPart(source.charAt(at - 1))) {
                continue;
            }

            int depth = 0;
            int i = at + name.length();
            for (; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    i++;
                    break;
                }
            }

            int brace = source.indexOf('{', i);
            int semicolon = source.indexOf(';', i);
            if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
                continue;
            }

            int bodyDepth = 0;
            for (int j = brace; j < source.length(); j++) {
                char c = source.charAt(j);
                if (c == '{') {
                    bodyDepth++;
                } else if (c == '}' && --bodyDepth == 0) {
                    return source.substring(brace, j + 1);
                }
            }
            return null;
        }
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            failures += 1;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

}
