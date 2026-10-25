package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import io.netty.channel.embedded.EmbeddedChannel;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Shared GameTest helpers. GameTests only run in dev ({@code runGameTestServer}); these classes are inert in the
 * shipped jar.
 */
public final class GameTests {
    /** A 3x3x3 air structure at {@code data/mcincremental_prestige/structure/empty.nbt}. */
    public static final String EMPTY_TEMPLATE = "empty";

    private GameTests() {}

    /**
     * ProjectE builds its EMC map only on player join or {@code /reload}, so on the GameTest server every value is 0
     * until this runs. Call it at the start of any test that reads EMC, then wait with {@code succeedWhen}: the
     * rebuild finishes a few ticks later. Batch order is undefined, so don't rely on another test having done it.
     */
    public static void rebuildEmcMap(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        server.reloadResources(server.getPackRepository().getSelectedIds());
    }

    /** For {@code succeedWhen}/{@code thenWaitUntil} after {@link #rebuildEmcMap}: throws until ProjectE has values. */
    public static void assertEmcMapBuilt(GameTestHelper helper) {
        long diamond = IEMCProxy.INSTANCE.getValue(Items.DIAMOND);
        helper.assertTrue(diamond == 8192, "EMC map not rebuilt yet (diamond = " + diamond + ")");
    }

    /**
     * Builds a player that is not yet in the level: call {@link #join} to add it, which is what a real join does
     * from the level's point of view. It has a real connection object but never negotiated any mod channels, so
     * ProjectE's sync packets to it throw; mod code must tolerate that. It is not in the {@code PlayerList}.
     * {@code makeMockServerPlayerInLevel} can't be used: ProjectE's datapack-sync handler throws for it.
     */
    public static ServerPlayer newPlayer(GameTestHelper helper, String name) {
        return newPlayer(helper, name, null);
    }

    /**
     * {@link #joinedPlayer} whose chat and action-bar messages (everything sent through {@code sendSystemMessage},
     * including {@code displayClientMessage}) are added to {@code messages} instead of being sent.
     */
    public static ServerPlayer joinedRecordingPlayer(GameTestHelper helper, String name, List<Component> messages) {
        return join(helper, newPlayer(helper, name, messages));
    }

    private static ServerPlayer newPlayer(GameTestHelper helper, String name, List<Component> messages) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = messages == null
                ? new ServerPlayer(server, level, profile, cookie.clientInformation())
                : new ServerPlayer(server, level, profile, cookie.clientInformation()) {
                    @Override
                    public void sendSystemMessage(Component message, boolean overlay) {
                        messages.add(message);
                    }
                };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player, cookie);
        player.moveTo(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
        return player;
    }

    /** Adds a player from {@link #newPlayer} to the test's level. Remove it with {@code player.discard()}. */
    public static ServerPlayer join(GameTestHelper helper, ServerPlayer player) {
        helper.getLevel().addNewPlayer(player);
        return player;
    }

    /** {@link #newPlayer} then {@link #join}. */
    public static ServerPlayer joinedPlayer(GameTestHelper helper, String name) {
        return join(helper, newPlayer(helper, name));
    }
}
