package com.moulberry.flashback.gui;

import com.moulberry.flashback.packet.FlashbackRemoteContainer;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * What actually goes on the wire when a recorded container changes.
 *
 * <p>Only the fields a change needs are written, which is what keeps a slot update from carrying a
 * chest. The codec decides that from a single number, so a change that encoded the wrong number of
 * fields would not fail here - it would fail as an item appearing in the wrong slot, or as the
 * client reading the rest of the stream as the wrong thing, a long way from the cause.
 */
public class ContainerWireTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // The title is a chat component, and its codec reaches into the registries: encoding one
        // without the game's bootstrapping walks into a registry that was never built. Starting the
        // game's registries also replaces the output stream, so it is put back afterwards.
        java.io.PrintStream out = System.out;
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        System.setOut(out);

        openingCarriesWhatTheClientNeedsToLayItOut();
        contentsCarryTheWholeContainer();
        aSlotCarriesOneStack();
        closingAndResettingCarryNothing();
        anUnknownChangeIsTreatedAsAReset();
        aSlotUpdateIsFarSmallerThanTheWholeContainer();
        emptyContentsStillDecode();
        aMerchantsTradesSurviveTheWire();

        if (failures > 0) {
            System.out.println("FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("All container wire tests passed");
    }

    private static void openingCarriesWhatTheClientNeedsToLayItOut() {
        FlashbackRemoteContainer sent = FlashbackRemoteContainer.open(4, "minecraft:generic_9x3",
            Component.literal("Chest"));
        FlashbackRemoteContainer received = roundTrip(sent);

        check("the container id survives", received.containerId() == 4);
        check("the change is still an opening", received.kind() == FlashbackRemoteContainer.Kind.OPEN);
        check("the menu type survives", "minecraft:generic_9x3".equals(received.menuType()));
        check("the title survives", Component.literal("Chest").equals(received.title()));
        check("an opening carries no contents", received.items().isEmpty());
    }

    private static void contentsCarryTheWholeContainer() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 63; i++) {
            items.add(ItemStack.EMPTY);
        }
        FlashbackRemoteContainer received =
            roundTrip(FlashbackRemoteContainer.content(2, items, ItemStack.EMPTY));

        check("the change is still contents", received.kind() == FlashbackRemoteContainer.Kind.CONTENT);
        check("every slot survives", received.items().size() == 63);
        check("the cursor survives", received.carried().isEmpty());
    }

    private static void aSlotCarriesOneStack() {
        FlashbackRemoteContainer received =
            roundTrip(FlashbackRemoteContainer.slot(2, 17, ItemStack.EMPTY));

        check("the change is still a slot", received.kind() == FlashbackRemoteContainer.Kind.SLOT);
        check("the slot number survives", received.slot() == 17);
        check("a slot change carries no contents", received.items().isEmpty());
    }

    private static void closingAndResettingCarryNothing() {
        FlashbackRemoteContainer closed = roundTrip(FlashbackRemoteContainer.close(9));
        check("a close is a close", closed.kind() == FlashbackRemoteContainer.Kind.CLOSE);
        check("a close names the container", closed.containerId() == 9);

        FlashbackRemoteContainer reset = roundTrip(FlashbackRemoteContainer.reset());
        check("a reset is a reset", reset.kind() == FlashbackRemoteContainer.Kind.RESET);

        FlashbackRemoteContainer carried = roundTrip(FlashbackRemoteContainer.carried(3, ItemStack.EMPTY));
        check("a cursor change is a cursor change", carried.kind() == FlashbackRemoteContainer.Kind.CARRIED);
        check("the container it belongs to survives", carried.containerId() == 3);
    }

    /** A recording from a newer build must not make an older one read the wrong fields. */
    private static void anUnknownChangeIsTreatedAsAReset() {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeVarInt(5);
        buffer.writeVarInt(999);
        FlashbackRemoteContainer decoded = FlashbackRemoteContainer.STREAM_CODEC.decode(buffer);

        check("an unknown change is not read as a known one",
            decoded.kind() == FlashbackRemoteContainer.Kind.RESET);
        check("an unknown change does not invent a container", decoded.containerId() == 5);
    }

    /** The reason this is a description of a change rather than of a container. */
    private static void aSlotUpdateIsFarSmallerThanTheWholeContainer() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < 63; i++) {
            items.add(ItemStack.EMPTY);
        }

        int whole = sizeOf(FlashbackRemoteContainer.content(1, items, ItemStack.EMPTY));
        int one = sizeOf(FlashbackRemoteContainer.slot(1, 30, ItemStack.EMPTY));

        check("a slot update is a fraction of the container (" + one + " vs " + whole + " bytes)",
            one * 4 < whole);
    }

    private static void emptyContentsStillDecode() {
        FlashbackRemoteContainer received =
            roundTrip(FlashbackRemoteContainer.content(1, List.of(), ItemStack.EMPTY));
        check("an empty container is not mistaken for a missing one",
            received.kind() == FlashbackRemoteContainer.Kind.CONTENT && received.items().isEmpty());
    }

    /**
     * A merchant's rows are not slots, so they travel in a change of their own. A change that lost
     * its kind, or its container, would leave the trading screen drawing an empty list - the fault
     * this is for - and a codec fault here is otherwise silent.
     *
     * <p>The list is left empty: this harness bootstraps the registries but never binds item
     * components, so it cannot build a stack that a trade could cost or pay out. What is checked is
     * the part this codec owns - that an offers change is written and read back as offers, keeping
     * the container it belongs to and the villager's level, experience and flags, rather than
     * falling through to some other change.
     */
    private static void aMerchantsTradesSurviveTheWire() {
        net.minecraft.world.item.trading.MerchantOffers offers =
            new net.minecraft.world.item.trading.MerchantOffers();

        FlashbackRemoteContainer received = roundTrip(
            FlashbackRemoteContainer.offers(7, offers, 2, 40, true, false));

        check("the change is still an offers change", received.kind() == FlashbackRemoteContainer.Kind.OFFERS);
        check("the trades belong to the container they came from", received.containerId() == 7);
        check("the trade list survives", received.offers() != null && received.offers().isEmpty());
        check("the villager's level survives", received.villagerLevel() == 2);
        check("the villager's experience survives", received.villagerXp() == 40);
        check("the progress bar flag survives", received.showProgress());
        check("the restock flag survives", !received.canRestock());
        check("an offers change is not mistaken for contents", received.items().isEmpty());

        // Appending the kind after RESET is what keeps a recording written before it readable: the
        // ordinal is what goes on the wire, so anything inserted earlier shifts every later kind.
        check("the offers kind was appended, not inserted",
            FlashbackRemoteContainer.Kind.OFFERS.ordinal() == FlashbackRemoteContainer.Kind.RESET.ordinal() + 1);
    }

    private static FlashbackRemoteContainer roundTrip(FlashbackRemoteContainer payload) {
        RegistryFriendlyByteBuf buffer = buffer();
        FlashbackRemoteContainer.STREAM_CODEC.encode(buffer, payload);
        return FlashbackRemoteContainer.STREAM_CODEC.decode(buffer);
    }

    private static int sizeOf(FlashbackRemoteContainer payload) {
        RegistryFriendlyByteBuf buffer = buffer();
        FlashbackRemoteContainer.STREAM_CODEC.encode(buffer, payload);
        int size = buffer.readableBytes();
        buffer.release();
        return size;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
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
