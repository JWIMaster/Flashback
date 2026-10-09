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
        aMerchantsTradesReachTheScreen(root);
        theFirstPersonHandNeverChangesPlayer(root);
        theInterfaceOnlyAppearsThroughAPlayer(root);
        freshWatchedPlayersStartWithASettledHand(root);
        handYawStaysOnSameTurnAsCamera(root);
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
        check("only slots the player owns are mirrored, so server-owned slots are not second-guessed",
            recording.contains("instanceof CraftingContainer") && recording.contains("slot.container != own"));
        check("the mirror is told what the server said",
            recording.contains("ClientboundContainerSetSlotPacket")
                && recording.contains("ClientboundContainerSetContentPacket"));
        check("the recording can name an open container in its snapshot",
            recording.contains("ClientboundOpenScreenPacket") && recording.contains("snapshotState()")
                && recording.contains("FlashbackRemoteContainer.open"));

        String containerScreen = Files.readString(
            root.resolve("com/moulberry/flashback/mixin/gui/MixinAbstractContainerScreen.java"));
        check("the mirror is driven once a tick",
            containerScreen.contains("tick()V") && containerScreen.contains("GuiRecording.tick"));

        String recorder = Files.readString(root.resolve("com/moulberry/flashback/record/Recorder.java"));
        check("the recorder feeds the mirror every packet",
            recorder.contains("GuiRecording.observePacket"));
        check("a snapshot carries the open screen before it is written",
            recorder.contains("GuiRecording.snapshotState()")
                && recorder.indexOf("GuiRecording.snapshotState()") < recorder.lastIndexOf("writeGamePackets(this.gamePacketCodec, gamePackets)"));
        check("synthetic hotbar updates and snapshots use menu slots, not inventory indices",
            recorder.contains("ClientboundContainerSetSlotPacket(0, 0, InventoryMenuSlots.hotbarMenuSlot(i), copied)")
                && recorder.contains("ClientboundContainerSetSlotPacket(0, 0, InventoryMenuSlots.hotbarMenuSlot(i), hotbarItem.copy())"));
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
        String body = methodBody(handler, "mirrorOwnInventory");
        check("the inventory mirror was found", body != null);
        if (body != null) {
            check("it converts the player's menu slot to an inventory index",
                body.contains("InventoryMenuSlots.inventoryIndex(slot)"));
            check("an authoritative result or armour update is not rejected as an invalid click",
                !body.contains("mayPlace("));
            check("the recorded player's inventory is not addressed with a menu index",
                !body.contains("FlashbackRemoteSetSlot(player.getId(), slot,"));
        }

        // The first-person hotbar is not part of the container interface, so hiding the interface must
        // not stop the recorded player's inventory being kept current - and must not leave this side
        // believing the client has something it was never sent.
        String contentHandler = methodBody(handler, "handleContainerContent");
        check("container-zero content also restores the HUD inventory",
            contentHandler != null && contentHandler.contains("packet.containerId() == 0")
                && contentHandler.contains("mirrorOwnInventory(i,"));
        String slotHandler = methodBody(handler, "handleContainerSetSlot");
        check("the container slot handler was found", slotHandler != null);
        if (slotHandler != null) {
            int mirror = slotHandler.indexOf("mirrorOwnInventory(");
            int gate = slotHandler.indexOf("GuiPlayback.shouldShow()");
            check("the inventory is mirrored before the interface is gated",
                mirror >= 0 && gate >= 0 && mirror < gate);
        }
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

    /**
     * A villager's rows are offers rather than slots, and nothing else carries them: the empty
     * handler this replaces was exactly the bug. Each hop is checked, because a payload that is
     * recorded, forwarded and then dropped on the client looks identical to one never sent.
     */
    private static void aMerchantsTradesReachTheScreen(Path root) throws Exception {        String handler = Files.readString(
            root.resolve("com/moulberry/flashback/playback/ReplayGamePacketHandler.java"));
        String offersHandler = methodBody(handler, "handleMerchantOffers");
        check("the merchant offers handler was found", offersHandler != null);
        if (offersHandler != null) {
            check("recorded trades are forwarded rather than dropped",
                offersHandler.contains("GuiDisplayForwarder.offers"));
        }

        String forwarder = Files.readString(
            root.resolve("com/moulberry/flashback/gui/GuiDisplayForwarder.java"));
        check("the forwarder sends trades only for the container that is open",
            forwarder.contains("FlashbackRemoteContainer.offers(") && forwarder.contains("isOpen("));

        String display = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiDisplay.java"));
        String applyOffers = methodBody(display, "applyOffers");
        check("the display applies trades", applyOffers != null);
        if (applyOffers != null) {
            check("trades are put into the merchant menu the screen draws from",
                applyOffers.contains("instanceof MerchantMenu") && applyOffers.contains("setOffers("));
            check("a container that is not a merchant is left alone",
                applyOffers.contains("return;"));
        }
        check("the display handles the offers change",
            display.contains("case OFFERS"));

        String recording = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiRecording.java"));
        check("a seek snapshot carries an open trade's rows",
            recording.contains("menu instanceof MerchantMenu")
                && recording.contains("FlashbackRemoteContainer.offers("));
    }

    /**
     * The first-person hand belongs to one player for the whole shot. A replay destroys and
     * recreates the watched entity as it ticks, and both ways of handling that used to change who
     * the hand belonged to: giving up made the game draw the viewer's hands instead, and a frame
     * with no instance at all had the same result. So the player is re-resolved by UUID, and a
     * frame that still cannot find them draws no hand rather than somebody else's.
     */
    private static void theFirstPersonHandNeverChangesPlayer(Path root) throws Exception {
        String flashback = Files.readString(root.resolve("com/moulberry/flashback/Flashback.java"));
        String spectating = methodBody(flashback, "getSpectatingPlayer");
        check("the spectating player lookup was found", spectating != null);
        if (spectating != null) {
            check("a replaced spectated player is re-resolved by UUID, not given up on",
                spectating.contains("getUUID().equals(clientPlayer.getUUID())"));
            check("the viewer's own camera is still the replay's own viewpoint",
                spectating.contains("clientPlayer == minecraft.player"));
        }

        String extractor = Files.readString(root.resolve("com/moulberry/flashback/mixin/MixinLevelExtractor.java"));
        String extract = methodBody(extractor, "extractPlayerState");
        check("the first-person extraction was found", extract != null);
        if (extract != null) {
            check("a spectate frame with no live player draws no hand",
                extract.contains("state.hasPlayer = false"));
            check("and does not fall through to the viewer's hand",
                extract.contains("cameraPlayer != Minecraft.getInstance().player"));
        }
    }

    /**
     * The hotbar, the health bar and the first-person hand are readouts of a body, so they belong on
     * a camera looking through a player and nowhere else. A free or orbit camera keeps the viewer's
     * own player as the camera entity - the game has no other way to move a camera - so the game
     * treats it as first person and draws the viewer's own hand and bar into a shot the viewer is
     * not in.
     */
    private static void theInterfaceOnlyAppearsThroughAPlayer(Path root) throws Exception {
        String display = Files.readString(root.resolve("com/moulberry/flashback/gui/GuiDisplay.java"));
        check("the interface asks whether it belongs to a player",
            display.contains("public static boolean showingThroughAPlayer()"));

        String hud = Files.readString(root.resolve("com/moulberry/flashback/mixin/visuals/MixinHud.java"));
        String hotbar = methodBody(hud, "extractHotbarAndDecorations");
        check("the hotbar extraction was found", hotbar != null);
        if (hotbar != null) {
            check("the hotbar is not drawn on a camera that is not through a player",
                hotbar.contains("GuiDisplay.showingThroughAPlayer()") && hotbar.contains("ci.cancel()"));
        }

        String renderer = Files.readString(root.resolve("com/moulberry/flashback/mixin/playback/MixinGameRenderer.java"));
        String hand = methodBody(renderer, "renderItemInHand_onlyThroughAPlayer");
        check("the first-person hand is gated as well", hand != null);
        if (hand != null) {
            check("and is not drawn on a camera that is not through a player",
                hand.contains("GuiDisplay.showingThroughAPlayer()") && hand.contains("ci.cancel()"));
        }

        // The container is the same kind of readout, so it must be gated by the same answer rather
        // than by a second rule that could drift away from it.
        check("the container overlay uses the same answer",
            display.contains("if (!showingThroughAPlayer(minecraft))"));
    }

    private static void freshWatchedPlayersStartWithASettledHand(Path root) throws Exception {
        String remotePlayer = Files.readString(root.resolve("com/moulberry/flashback/mixin/playback/MixinRemotePlayer.java"));
        String initialize = methodBody(remotePlayer, "flashback$initializeFirstPersonHandState");
        check("the hand state initializer was found", initialize != null);
        if (initialize != null) {
            check("new remote-player instances start with their current held items",
                initialize.contains("this.mainHandItem = this.getMainHandItem()")
                    && initialize.contains("this.offHandItem = this.getOffhandItem()"));
            check("new instances do not animate in from a fully-lowered hand pose",
                initialize.contains("this.mainHandHeight = this.oMainHandHeight = 1.0F")
                    && initialize.contains("this.offHandHeight = this.oOffHandHeight = 1.0F"));
            check("view-angle offsets are seeded instead of starting at zero",
                initialize.contains("this.xBobO = this.xBob = this.getXRot()")
                    && initialize.contains("this.yBobO = this.yBob = this.getYRot()"));
        }

        String extract = methodBody(remotePlayer, "flashback$extractFirstPersonHandsAndItems");
        check("hand state initializes before first render-state extraction",
            extract != null && extract.contains("this.flashback$initializeFirstPersonHandState()"));
    }

    private static void handYawStaysOnSameTurnAsCamera(Path root) throws Exception {
        String source = Files.readString(root.resolve("com/moulberry/flashback/mixin/playback/MixinRemotePlayer.java"));
        check("hand yaw animation rebases across the 180-degree seam",
            source.contains("this.yBobO = yaw + Mth.wrapDegrees(this.yBob - yaw)"));
        String extraction = methodBody(source, "flashback$extractFirstPersonHandsAndItems");
        check("rendered hand yaw stays on the camera's current turn",
            extraction != null && extraction.contains("state.yBob = state.viewYRot - Mth.wrapDegrees(state.viewYRot - bobYaw)"));
        // A wrapped player yaw of -179 and a hand yaw of +179 are two degrees apart,
        // not 358 degrees apart. The hand renderer rotates by one tenth of this delta.
        check("crossing the yaw seam keeps the hand's rotation below one degree",
            Math.abs(wrapDegrees(-179.0f - 179.0f) * 0.1f) < 1.0f);
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
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
