package io.github.vexedpanda.mcincremental.prestige.client;

import java.util.ArrayList;
import java.util.List;

import io.github.vexedpanda.mcincremental.prestige.McIncrementalPrestige;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateArea;
import io.github.vexedpanda.mcincremental.prestige.controller.CandidateAreas;
import io.github.vexedpanda.mcincremental.prestige.network.CandidatesPayload;
import io.github.vexedpanda.mcincremental.prestige.network.ChooseCandidatePayload;
import io.github.vexedpanda.mcincremental.prestige.preview.CandidatePreview;
import io.github.vexedpanda.mcincremental.prestige.preview.OreVein;
import io.github.vexedpanda.mcincremental.prestige.run.RunArea;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Candidate selection screen (#78): one row of cards per dimension, each card one Candidate area with its biome,
 * GT ore veins and structures. Clicking a card sends {@link ChooseCandidatePayload}; the server decides whether it
 * counts and closes every screen once a choice is made. Holds no game logic: everything shown comes from the {@link
 * CandidatesPayload}. Doesn't pause the game, so the integrated server keeps handling the choice.
 */
public class CandidateScreen extends Screen {
    private static final String KEY = McIncrementalPrestige.MODID + ".screen.";
    private static final int GUTTER = 16;
    private static final int GAP = 8;
    private static final int MAX_CARD_WIDTH = 180;
    private static final int MAX_CARD_HEIGHT = 150;
    private static final int TOP = 44;
    private static final int ROW_LABEL_HEIGHT = 12;
    private static final int BOTTOM = 32;

    private final CandidatesPayload payload;
    /** Each row's dimension label and the y it is drawn at, laid out by {@link #init}. */
    private final List<RowLabel> rowLabels = new ArrayList<>();

    private record RowLabel(Component name, int y) {}

    public CandidateScreen(CandidatesPayload payload) {
        super(Component.translatable(KEY + "title"));
        this.payload = payload;
    }

    @Override
    protected void init() {
        List<CandidatesPayload.Row> rows = payload.rows();
        int columns = rows.stream().mapToInt(row -> row.candidates().size()).max().orElse(1);
        int cardWidth = Math.max(60, Math.min(MAX_CARD_WIDTH, (width - 2 * GUTTER - (columns - 1) * GAP) / columns));
        int rowHeight = Math.max(40, (height - TOP - BOTTOM) / Math.max(1, rows.size()));
        int cardHeight = Math.min(MAX_CARD_HEIGHT, rowHeight - ROW_LABEL_HEIGHT - GAP);
        int number = 0;
        int y = TOP;
        rowLabels.clear();
        for (CandidatesPayload.Row row : rows) {
            rowLabels.add(new RowLabel(dimensionName(row.dimension().location()), y + 2));
            int rowWidth = row.candidates().size() * cardWidth + (row.candidates().size() - 1) * GAP;
            int x = (width - rowWidth) / 2;
            for (CandidateArea candidate : row.candidates()) {
                addRenderableWidget(new Card(++number, candidate, x, y + ROW_LABEL_HEIGHT, cardWidth, cardHeight));
                x += cardWidth + GAP;
            }
            y += ROW_LABEL_HEIGHT + cardHeight + GAP;
        }
        addRenderableWidget(Button.builder(Component.translatable(KEY + "game_menu"), button -> minecraft.setScreen(new PauseScreen(true)))
                .bounds(width / 2 - 60, height - BOTTOM + 6, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable(KEY + "hint"), width / 2, 26, 0xA0A0A0);
        for (RowLabel label : rowLabels) graphics.drawCenteredString(font, label.name(), width / 2, label.y(), 0xFFFFA0);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Chat (and commands, e.g. /prestige choose) stays usable; the screen comes back when chat closes. Opened on
        // the next frame, as vanilla does, so this key's typed character doesn't land in the chat box.
        if (minecraft.options.keyChat.matches(keyCode, scanCode)) {
            minecraft.tell(() -> minecraft.setScreen(new ChatScreen("")));
            return true;
        }
        if (minecraft.options.keyCommand.matches(keyCode, scanCode)) {
            minecraft.tell(() -> minecraft.setScreen(new ChatScreen("/")));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static Component dimensionName(ResourceLocation dimension) {
        return Component.translatableWithFallback(KEY + "dimension." + dimension.getNamespace() + "." + dimension.getPath(),
                dimension.toString());
    }

    static Component biomeName(CandidatePreview preview) {
        return preview.biome()
                .<Component>map(id -> Component.translatable(Util.makeDescriptionId("biome", id)))
                .orElseGet(() -> Component.literal("?"));
    }

    /** GT vein ids are their main material's id, so a vein is named after that material ("Magnetite vein (Gold, Magnetite)"). */
    static Component veinText(OreVein vein) {
        MutableComponent materials = Component.empty();
        for (int i = 0; i < vein.materials().size(); i++) {
            if (i > 0) materials.append(", ");
            materials.append(materialName(vein.materials().get(i)));
        }
        return Component.translatable(CandidateAreas.VEIN_KEY, materialName(vein.type()), materials);
    }

    /** GT's material names are under {@code material.<namespace>.<name>}. */
    static Component materialName(ResourceLocation material) {
        return Component.translatableWithFallback(material.toLanguageKey("material"), material.getPath());
    }

    static Component structureName(ResourceLocation structure) {
        return Component.literal(structure.getPath().replace('_', ' '));
    }

    /** Every line of a candidate's card, in order. */
    static List<Component> lines(int number, CandidateArea candidate) {
        RunArea area = candidate.area();
        CandidatePreview preview = candidate.preview();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(KEY + "candidate", number, biomeName(preview)).withStyle(ChatFormatting.BOLD));
        lines.add(Component.translatable(KEY + "position", (int) Math.floor(area.middleX()), (int) Math.floor(area.middleZ()))
                .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(KEY + "veins").withStyle(ChatFormatting.GOLD));
        if (preview.veins().isEmpty()) lines.add(Component.translatable(CandidateAreas.NONE_KEY).withStyle(ChatFormatting.DARK_GRAY));
        for (OreVein vein : preview.veins()) lines.add(veinText(vein));
        lines.add(Component.translatable(KEY + "structures").withStyle(ChatFormatting.AQUA));
        if (preview.structures().isEmpty()) lines.add(Component.translatable(CandidateAreas.NONE_KEY).withStyle(ChatFormatting.DARK_GRAY));
        for (ResourceLocation structure : preview.structures()) lines.add(structureName(structure));
        return lines;
    }

    /** One Candidate area's card: its preview, clickable to choose it. */
    private class Card extends AbstractWidget {
        private static final int PADDING = 4;
        private final int number;
        private final List<Component> lines;

        Card(int number, CandidateArea candidate, int x, int y, int width, int height) {
            super(x, y, width, height, Component.translatable(KEY + "choose", number));
            this.number = number;
            this.lines = lines(number, candidate);
            // The whole preview, for a card too small to show it all.
            MutableComponent all = Component.empty();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) all.append("\n");
                all.append(lines.get(i));
            }
            setTooltip(Tooltip.create(all));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, isHoveredOrFocused() ? 0xC0303848 : 0xA0101018);
            graphics.renderOutline(getX(), getY(), width, height, isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF606070);
            List<FormattedCharSequence> wrapped = new ArrayList<>();
            for (Component line : lines) wrapped.addAll(font.split(line, width - 2 * PADDING));
            int fits = Math.max(1, (height - 2 * PADDING) / font.lineHeight);
            boolean cut = wrapped.size() > fits;
            int shown = cut ? fits - 1 : wrapped.size();
            int y = getY() + PADDING;
            for (int i = 0; i < shown; i++, y += font.lineHeight) graphics.drawString(font, wrapped.get(i), getX() + PADDING, y, 0xFFFFFF);
            // The tooltip has the rest.
            if (cut) graphics.drawString(font, CommonComponents.ELLIPSIS, getX() + PADDING, y, 0xA0A0A0);
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            PacketDistributor.sendToServer(new ChooseCandidatePayload(number));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
            output.add(NarratedElementType.HINT, getTooltipText());
        }

        private Component getTooltipText() {
            MutableComponent all = Component.empty();
            for (Component line : lines) all.append(line).append(". ");
            return all;
        }
    }
}
