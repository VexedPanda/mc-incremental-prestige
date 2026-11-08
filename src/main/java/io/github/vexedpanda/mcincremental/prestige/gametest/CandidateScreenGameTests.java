package io.github.vexedpanda.mcincremental.prestige.gametest;

import java.util.List;
import java.util.Optional;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateArea;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateAreas;
import io.github.vexedpanda.mcincremental.prestige.network.CandidatesPayload;
import io.github.vexedpanda.mcincremental.prestige.network.ChooseCandidatePayload;
import io.github.vexedpanda.mcincremental.prestige.preview.CandidatePreview;
import io.github.vexedpanda.mcincremental.prestige.preview.OreVein;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import io.github.vexedpanda.mcincremental.prestige.run.RunAreas;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;

/**
 * The Candidate selection screen's network side (#78). The screen itself holds no game logic and has no automated
 * test (see the pack README's "What to try"); these check what it relies on: the candidates payload survives the
 * wire with its dimension rows intact, and a choice sent from the screen starts the Run like {@code /prestige choose}.
 */
@GameTestHolder(McIncrementalPrestige.MODID)
@PrefixGameTestTemplate(false)
public class CandidateScreenGameTests {
    @GameTest(template = GameTests.EMPTY_TEMPLATE)
    public static void theCandidatesPayloadRoundTripsWithItsRowsByDimension(GameTestHelper helper) {
        // Two rows, the Nether first, so a decode that regroups or reorders the dimensions is caught.
        CandidateArea fortress = new CandidateArea(new RunArea(new ChunkPos(-40, 12), 1), new CandidatePreview(
                Optional.of(id("minecraft", "nether_wastes")), List.of(), List.of(), List.of(id("minecraft", "fortress"))));
        CandidateArea magnetite = new CandidateArea(new RunArea(new ChunkPos(64, -128), 1), new CandidatePreview(
                Optional.of(id("minecraft", "plains")),
                List.of(new OreVein(id("gtceu", "magnetite"), List.of(id("gtceu", "gold"), id("gtceu", "magnetite")))),
                List.of(id("gtceu", "cassiterite"), id("gtceu", "gold"), id("gtceu", "magnetite")),
                List.of(id("minecraft", "mineshaft"), id("minecraft", "village_plains")),
                List.of(id("gtceu", "gold")))); // gold marked new (#79)
        CandidateArea unreadable = new CandidateArea(new RunArea(new ChunkPos(128, 0), 2), CandidatePreview.biomeOnly(Optional.empty()));
        CandidatesPayload sent = new CandidatesPayload(List.of(
                new CandidatesPayload.Row(Level.NETHER, List.of(fortress)),
                new CandidatesPayload.Row(Level.OVERWORLD, List.of(magnetite, unreadable))));

        CandidatesPayload received = roundTrip(helper, sent);

        helper.assertTrue(received.equals(sent), "The payload should decode to what was sent, " + sent + ", got " + received);
        helper.assertTrue(received.rows().stream().map(CandidatesPayload.Row::dimension).toList().equals(List.of(Level.NETHER, Level.OVERWORLD)),
                "The rows should keep their dimensions and order, got " + received.rows());
        helper.assertTrue(received.numbered().equals(List.of(fortress, magnetite, unreadable)),
                "Candidates should be numbered through the rows in order, got " + received.numbered());
        CandidatesPayload none = roundTrip(helper, new CandidatesPayload(List.of()));
        helper.assertTrue(none.rows().isEmpty(), "An empty payload (nothing offered) should stay empty, got " + none);
        helper.succeed();
    }

    @GameTest(template = GameTests.EMPTY_TEMPLATE, batch = "candidate_screen_choose", timeoutTicks = 400)
    public static void aChoiceSentFromTheScreenStartsTheRunLikeTheCommand(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        PrestigeGameTests.withWorldRestored(helper, () -> {
            ServerPlayer player = GameTests.joinedPlayer(helper, "clicker");
            try {
                int runs = RunAreas.runNumber(server);
                PrestigeGameTests.runPrestigeCommand(helper);
                helper.assertTrue(CandidatesPayload.offered(server).numbered().equals(CandidateAreas.offered(server)),
                        "The payload should carry the candidates on offer, in their numbering");
                RunArea second = CandidateAreas.offered(server).get(1).area();

                receive(player, new ChooseCandidatePayload(2));

                helper.assertTrue(RunAreas.current(server).equals(Optional.of(second)),
                        "The Run should start in candidate 2's area " + second + ", got " + RunAreas.current(server));
                helper.assertTrue(RunAreas.runNumber(server) == runs + 1, "Exactly one Run should start");
                helper.assertTrue(second.contains(player.blockPosition()),
                        "The player at " + player.blockPosition() + " should be in the chosen area " + second);
                helper.assertTrue(CandidateAreas.offered(server).isEmpty(), "No candidates should be offered once chosen");

                receive(player, new ChooseCandidatePayload(1));
                helper.assertTrue(RunAreas.runNumber(server) == runs + 1, "A later choice from a screen should be ignored");
            } finally {
                player.discard();
            }
        });
        helper.succeed();
    }

    /** Encodes then decodes {@code payload} with its stream codec, as the network does. */
    private static CandidatesPayload roundTrip(GameTestHelper helper, CandidatesPayload payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            CandidatesPayload.STREAM_CODEC.encode(buf, payload);
            CandidatesPayload decoded = CandidatesPayload.STREAM_CODEC.decode(buf);
            helper.assertTrue(buf.readableBytes() == 0, "The decode should read the whole payload");
            return decoded;
        } finally {
            buf.release();
        }
    }

    /**
     * Delivers a choose payload from {@code player} to the server's handler: through its stream codec, then the handler
     * NeoForge calls with the player's connection. GameTest players never negotiated channels, so this skips only
     * NeoForge's channel check.
     */
    private static void receive(ServerPlayer player, ChooseCandidatePayload payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            ChooseCandidatePayload.STREAM_CODEC.encode(buf, payload);
            ChooseCandidatePayload decoded = ChooseCandidatePayload.STREAM_CODEC.decode(buf);
            ChooseCandidatePayload.handle(decoded, new ServerPayloadContext(player.connection, ChooseCandidatePayload.TYPE.id()));
        } finally {
            buf.release();
        }
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }
}
