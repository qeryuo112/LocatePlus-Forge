/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.report;

import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.model.ItemHit;
import dev.locateplus.model.ItemResult;
import dev.locateplus.platform.Services;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Writes the {@code /locate item} export.
 *
 * Same shape and conventions as {@link ExportWriter}: background thread, snapshotted data, streamed
 * straight to disk. The body differs because an item search reports locations and holders rather
 * than block tallies.
 */
public final class ItemExportWriter {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter HUMAN_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ItemExportWriter() {
    }

    public static Path write(ItemResult result) throws IOException {
        Path dir = Services.platform().exportDir();
        Files.createDirectories(dir);

        String base = "items_" + LocalDateTime.now().format(FILE_STAMP);
        Path file = uniquePath(dir, base);

        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write(LPConstants.MOD_NAME + " item search, "
                    + LocalDateTime.now().format(HUMAN_STAMP));
            out.newLine();
            out.newLine();

            writeHeader(result, out);
            writeSummary(result, out);
            writeLocations(result, out);
        }
        return file;
    }

    /** A section title with a rule the width of the title, so it never overruns the content. */
    private static void title(BufferedWriter out, String text) throws IOException {
        out.newLine();
        out.write(text);
        out.newLine();
        out.write("-".repeat(text.length()));
        out.newLine();
    }

    private static Path uniquePath(Path dir, String base) {
        Path candidate = dir.resolve(base + ".txt");
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "_" + suffix++ + ".txt");
        }
        return candidate;
    }

    private static void writeHeader(ItemResult result, BufferedWriter out) throws IOException {
        line(out, "Searched for", result.label());
        line(out, "Dimension", result.dimensionId());
        line(out, "Centred on", Chat.coords(result.originBlock()));
        line(out, "Chunks scanned", result.requestedChunks() > 0
                ? Chat.number(result.chunksScanned()) + " of "
                        + Chat.number(result.requestedChunks()) + " requested"
                : Chat.number(result.chunksScanned()));
        line(out, "Total items", Chat.number(result.totalItems()));
        line(out, "Stacks found", Chat.number(result.stackCount()));
        line(out, "Forceload", result.forceload() ? "yes" : "no");
        line(out, "Took", result.durationMillis() + " ms");
        line(out, "Built by", LPConstants.MOD_NAME + " " + Services.platform().modVersion()
                + " on Minecraft " + Services.platform().minecraftVersion()
                + " (" + Services.platform().loaderName() + ")");
        if (result.truncated()) {
            line(out, "Note", "Location list was cut short; the totals above are still complete.");
        }
    }

    private static void writeSummary(ItemResult result, BufferedWriter out) throws IOException {
        title(out, "Where they are");

        long total = result.totalItems();
        for (ItemHit.Channel source : ItemHit.Channel.values()) {
            long count = result.countFrom(source);
            if (count == 0) {
                continue;
            }
            double share = total == 0 ? 0 : (double) count / total;
            out.write(String.format(Locale.US, "  %-16s %14s  %7s",
                    source.label(), Chat.number(count), Chat.percent(share)));
            out.newLine();
        }
    }

    private static void writeLocations(ItemResult result, BufferedWriter out) throws IOException {
        title(out, "Biggest piles first");

        List<ItemResult.Pile> piles = result.piles();
        for (int i = 0; i < piles.size(); i++) {
            ItemResult.Pile pile = piles.get(i);
            out.write(String.format(Locale.US, "%4d. %10s %-2s %-38s %s",
                    i + 1, Chat.number(pile.count()), preposition(pile.source()),
                    truncate(pile.holder(), 38), Chat.coords(pile.pos())));
            out.newLine();
            out.write(String.format(Locale.US, "       %s%s%s",
                    pile.source().label(),
                    pile.stacks() > 1 ? ", " + pile.stacks() + " stacks" : "",
                    pile.anyEnchanted() ? ", enchanted" : ""));
            out.newLine();
            if (pile.customName() != null) {
                out.write("       named \"" + pile.customName() + "\"");
                out.newLine();
            }
        }

        title(out, "Every stack, nearest first");

        for (ItemHit hit : result.byDistance()) {
            out.write(String.format(Locale.US, "  %-20s %6dx  %-16s %-32s %s",
                    Chat.coords(hit.pos()), hit.count(), hit.source().label(),
                    truncate(hit.holder(), 32), hit.itemId()));
            out.newLine();
        }
    }

    /** Reads correctly for each holder: items are in a chest but on the ground. */
    private static String preposition(ItemHit.Channel source) {
        return switch (source) {
            case DROPPED, ENTITY -> "on";
            case CONTAINER, NESTED, PLAYER -> "in";
        };
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "~";
    }

    private static void line(BufferedWriter out, String label, String value) throws IOException {
        out.write(String.format("%-16s %s", label, value));
        out.newLine();
    }

    /** Convenience used by the command layer's async export path. */
    public static void writeAsyncLogged(ItemResult result, Consumer<Path> onDone,
                                        Consumer<Throwable> onFail) {
        try {
            onDone.accept(write(result));
        } catch (Throwable t) {
            LPLog.error("Item export failed", t);
            onFail.accept(t);
        }
    }
}
